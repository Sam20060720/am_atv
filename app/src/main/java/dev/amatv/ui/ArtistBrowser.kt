package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.model.ArtistProfile
import dev.amatv.model.ArtistSection
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class ArtistSectionState(
    val items: List<Track> = emptyList(),
    val next: String? = null,
    val total: Int? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val failureReason: CatalogFailureReason? = null,
)

internal data class ArtistVisit(
    val key: Long,
    val source: Track,
    val profile: ArtistProfile? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val failureReason: CatalogFailureReason? = null,
    val sections: Map<ArtistSection, ArtistSectionState> = emptyMap(),
)

/** Owns artist visits, not playback. Hidden visits retain data but do no network work. */
internal class ArtistBrowser(
    private val scope: CoroutineScope,
    private val loadProfile: suspend (Track) -> ArtistProfile,
    private val loadSection: suspend (Track, ArtistSection, String?) -> LibraryPage,
    private val enrichArtwork: (suspend (Track) -> Track)? = null,
) {
    private val mutableVisits = MutableStateFlow<List<ArtistVisit>>(emptyList())
    val visits = mutableVisits.asStateFlow()
    private var sequence = 0L
    private var visible = true
    private val requests = Semaphore(2)
    private val jobs = mutableMapOf<Pair<Long, ArtistSection?>, Job>()
    private val visitedPages = mutableMapOf<Pair<Long, ArtistSection>, MutableSet<String>>()
    private val artworkCompleted = mutableSetOf<Long>()

    fun open(artist: Track): Long {
        cancelRequests()
        val visit = ArtistVisit(++sequence, artist)
        mutableVisits.value = mutableVisits.value.map(::notLoading) + visit
        if (visible) profile(visit.key)
        return visit.key
    }

    fun pop() {
        cancelRequests()
        val removed = mutableVisits.value.lastOrNull()?.key ?: return
        visitedPages.keys.removeAll { it.first == removed }
        artworkCompleted.remove(removed)
        mutableVisits.value = mutableVisits.value.dropLast(1).map(::notLoading)
        mutableVisits.value.lastOrNull()?.takeIf { visible && needsProfileWork(it) }?.let { profile(it.key) }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            cancelRequests()
            mutableVisits.value = mutableVisits.value.map(::notLoading)
        } else mutableVisits.value.lastOrNull()?.takeIf { !it.failed && needsProfileWork(it) }?.let { profile(it.key) }
    }

    fun retryProfile() { mutableVisits.value.lastOrNull()?.let { artworkCompleted.remove(it.key); profile(it.key, force = true) } }

    private fun needsProfileWork(visit: ArtistVisit) = visit.profile == null ||
        (enrichArtwork != null && visit.key !in artworkCompleted)

    fun ensureSection(key: Long, section: ArtistSection) {
        val state = mutableVisits.value.lastOrNull()?.takeIf { visible && it.key == key } ?: return
        val page = state.sections[section]
        if (page == null || (!page.loaded && !page.loading && !page.failed)) section(key, section, append = false)
    }

    fun more(key: Long, section: ArtistSection) = section(key, section, append = true)
    fun retrySection(key: Long, section: ArtistSection) {
        val page = mutableVisits.value.lastOrNull()?.sections?.get(section)
        section(key, section, append = page?.loaded == true)
    }

    private fun profile(key: Long, force: Boolean = false) {
        val visit = mutableVisits.value.lastOrNull()?.takeIf { visible && it.key == key } ?: return
        val jobKey = key to null
        if (jobs[jobKey]?.isActive == true) return
        if (visit.profile == null || force) update(key) { it.copy(loading = true, failed = false, failureReason = null) }
        jobs[jobKey] = scope.launch {
            try {
                val profile = visit.profile?.takeUnless { force } ?: requests.withPermit { loadProfile(visit.source) }
                currentCoroutineContext().ensureActive()
                // Biography/sections are usable immediately. Optional public
                // artwork must never hold the first visible profile hostage.
                update(key) { it.copy(profile = profile, loading = false, failed = false, failureReason = null) }
                val enrich = enrichArtwork
                if (enrich != null && key !in artworkCompleted) {
                    try {
                        val source = profile.catalogArtist ?: profile.artist
                        val artwork = enrich(source)
                        currentCoroutineContext().ensureActive()
                        if (dev.amatv.model.CatalogResourceRef.fromTrack(artwork) == dev.amatv.model.CatalogResourceRef.fromTrack(source)) {
                            update(key) { current -> current.copy(profile = current.profile?.let {
                                if (it.catalogArtist != null) it.copy(catalogArtist = artwork) else it.copy(artist = artwork)
                            }) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { currentCoroutineContext().ensureActive() }
                    artworkCompleted.add(key)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // A loader can finish with an ordinary error after its visit was
                // cancelled. It must not mark a resumed request as failed.
                currentCoroutineContext().ensureActive()
                update(key) { it.copy(loading = false, failed = true, failureReason = failure.catalogUiFailureReason()) }
            }
            finally { if (jobs[jobKey] === currentCoroutineContext()[Job]) jobs.remove(jobKey) }
        }
    }

    private fun section(key: Long, section: ArtistSection, append: Boolean) {
        val visit = mutableVisits.value.lastOrNull()?.takeIf { visible && it.key == key } ?: return
        val profile = visit.profile ?: return
        if (section !in profile.applicableSections) return
        val old = visit.sections[section] ?: ArtistSectionState()
        if (old.loading || (append && old.next == null && !old.failed)) return
        val source = if (section == ArtistSection.LibraryAlbums) profile.artist else profile.catalogArtist ?: profile.artist
        val next = if (append) old.next else null
        val jobKey = key to section
        updateSection(key, section) { it.copy(loading = true, failed = false, failureReason = null) }
        jobs[jobKey] = scope.launch {
            try {
                val page = requests.withPermit { loadSection(source, section, next) }
                currentCoroutineContext().ensureActive()
                val seen = visitedPages.getOrPut(key to section) { mutableSetOf() }
                if (!append) seen.clear()
                check(page.requestedPage !in seen && page.nextPage != page.requestedPage && (page.nextPage == null || page.nextPage !in seen))
                seen.add(page.requestedPage)
                updateSection(key, section) {
                    it.copy(items = if (append) mergeLibraryPage(it.items, page.items) else page.items,
                        next = page.nextPage, total = page.total ?: it.total, loaded = true, loading = false, failed = false, failureReason = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                updateSection(key, section) { it.copy(loading = false, failed = true, failureReason = failure.catalogUiFailureReason()) }
            }
            finally { if (jobs[jobKey] === currentCoroutineContext()[Job]) jobs.remove(jobKey) }
        }
    }

    fun close() {
        cancelRequests()
        visitedPages.clear()
        artworkCompleted.clear()
        mutableVisits.value = emptyList()
    }

    private fun cancelRequests() { jobs.values.toList().forEach(Job::cancel); jobs.clear() }
    private fun notLoading(visit: ArtistVisit) = visit.copy(loading = false,
        sections = visit.sections.mapValues { (_, page) -> page.copy(loading = false) })
    private inline fun update(key: Long, transform: (ArtistVisit) -> ArtistVisit) {
        mutableVisits.value = mutableVisits.value.map { if (it.key == key) transform(it) else it }
    }
    private inline fun updateSection(key: Long, section: ArtistSection, transform: (ArtistSectionState) -> ArtistSectionState) {
        update(key) { it.copy(sections = it.sections + (section to transform(it.sections[section] ?: ArtistSectionState()))) }
    }
}
