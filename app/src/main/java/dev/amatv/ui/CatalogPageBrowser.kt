package dev.amatv.ui

import dev.amatv.integration.mergeLibraryPage
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class CatalogPageState(
    val profile: CatalogResourceProfile? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val failureReason: CatalogFailureReason? = null,
    val sections: Map<String, ArtistSectionState> = emptyMap(),
)

internal fun CatalogResourceSection.pageKey() = "$id:$isView:${chartType.orEmpty()}"

/** One generic resource visit; no offscreen UI, no eager relationship fan-out. */
internal class CatalogPageBrowser(
    private val scope: CoroutineScope,
    private val source: Track,
    private val loadProfile: suspend (CatalogResourceRef) -> CatalogResourceProfile,
    private val loadSection: suspend (CatalogResourceRef, CatalogResourceSection, String?) -> CatalogResourcePage,
    private val enrichArtwork: (suspend (Track) -> Track)? = null,
) {
    private val mutableState = MutableStateFlow(CatalogPageState())
    val state = mutableState.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val cursors = mutableMapOf<String, MutableSet<String>>()
    private val permits = Semaphore(2)
    private var visible = true
    private var artworkCompleted = false
    init { retryProfile() }

    fun invalidate() {
        jobs.values.toList().forEach(Job::cancel); jobs.clear(); cursors.clear()
        artworkCompleted = false
        mutableState.value = CatalogPageState(loading = visible)
        if (visible) requestProfile(force = true)
    }

    fun retryProfile() { artworkCompleted = false; requestProfile(force = true) }

    private fun requestProfile(force: Boolean = false) {
        if (jobs["profile"]?.isActive == true) return
        val ref = CatalogResourceRef.fromTrack(source)
        if (ref == null) { mutableState.value = CatalogPageState(loading = false, failed = true, failureReason = CatalogFailureReason.Unavailable); return }
        val existing = mutableState.value.profile?.takeUnless { force }
        if (existing == null) mutableState.value = mutableState.value.copy(loading = true, failed = false, failureReason = null)
        jobs["profile"] = scope.launch {
            try {
                val profile = existing ?: permits.withPermit { loadProfile(ref) }
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(profile = profile, loading = false, failed = false, failureReason = null)
                val enrich = enrichArtwork
                if (enrich != null && !artworkCompleted) {
                    try {
                        val artwork = enrich(profile.item)
                        currentCoroutineContext().ensureActive()
                        if (CatalogResourceRef.fromTrack(artwork) == CatalogResourceRef.fromTrack(profile.item)) {
                            mutableState.value = mutableState.value.copy(profile = profile.copy(item = artwork,
                                motionArtworkUrl = artwork.motionArtworkUrl, motionPreviewUrl = artwork.motionPreviewUrl))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { currentCoroutineContext().ensureActive() }
                    artworkCompleted = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(loading = false, failed = true, failureReason = failure.catalogUiFailureReason())
            }
            finally { if (jobs["profile"] === currentCoroutineContext()[Job]) jobs.remove("profile") }
        }
    }

    fun ensure(section: CatalogResourceSection) {
        val page = mutableState.value.sections[section.pageKey()]
        if (page == null || (!page.loaded && !page.loading && !page.failed)) request(section, false)
    }
    fun more(section: CatalogResourceSection) = request(section, true)
    fun retry(section: CatalogResourceSection) = request(section, mutableState.value.sections[section.pageKey()]?.loaded == true)

    private fun request(section: CatalogResourceSection, append: Boolean) {
        if (!visible) return
        val profile = mutableState.value.profile ?: return
        val key = section.pageKey()
        val old = mutableState.value.sections[key] ?: ArtistSectionState()
        if (old.loading || (append && old.next == null && !old.failed)) return
        update(key) { it.copy(loading = true, failed = false, failureReason = null) }
        jobs[key] = scope.launch {
            try {
                val page = permits.withPermit { loadSection(profile.ref, section, if (append) old.next else null) }
                currentCoroutineContext().ensureActive()
                val seen = cursors.getOrPut(key) { mutableSetOf() }
                if (!append) seen.clear()
                check(page.requestedPage !in seen && page.nextPage != page.requestedPage && page.nextPage !in seen)
                seen.add(page.requestedPage)
                update(key) { it.copy(items = if (append) mergeLibraryPage(it.items, page.items) else page.items,
                    next = page.nextPage, total = page.total ?: it.total, loaded = true, loading = false, failed = false, failureReason = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                update(key) { it.copy(loading = false, failed = true, failureReason = failure.catalogUiFailureReason()) }
            }
            finally { if (jobs[key] === currentCoroutineContext()[Job]) jobs.remove(key) }
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            jobs.values.toList().forEach(Job::cancel); jobs.clear()
            mutableState.value = mutableState.value.copy(loading = false,
                sections = mutableState.value.sections.mapValues { it.value.copy(loading = false) })
        } else if (!mutableState.value.failed && (mutableState.value.profile == null || enrichArtwork != null && !artworkCompleted)) requestProfile()
    }
    fun close() { visible = false; jobs.values.toList().forEach(Job::cancel); jobs.clear() }
    private inline fun update(key: String, transform: (ArtistSectionState) -> ArtistSectionState) {
        val state = mutableState.value
        mutableState.value = state.copy(sections = state.sections + (key to transform(state.sections[key] ?: ArtistSectionState())))
    }
}
