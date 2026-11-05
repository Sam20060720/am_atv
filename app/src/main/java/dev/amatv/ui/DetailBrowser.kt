package dev.amatv.ui

import androidx.compose.ui.focus.FocusRequester
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.CollectionShuffleSample
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.CatalogResourceProfile
import dev.amatv.model.CatalogResourceSection
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.ArtistProfile
import dev.amatv.model.ArtistSection
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DetailVisit(
    val key: Long,
    val source: Track,
    val artistKey: Long? = null,
    val collection: CollectionBrowser? = null,
    val page: CatalogPageBrowser? = null,
    val returnToPlayer: Boolean = false,
) {
    val focus = ContentFocusEntry()
    val firstFocus = FocusRequester()
}

/** A real back stack for album → artist → album and related-artist browsing. */
internal class DetailBrowser(
    private val scope: CoroutineScope,
    private val loadCollection: suspend (Track, String?) -> LibraryPage,
    private val loadRelatedArtists: suspend (Track) -> LibraryPage,
    loadProfile: suspend (Track) -> ArtistProfile,
    loadArtistSection: suspend (Track, ArtistSection, String?) -> LibraryPage,
    private val loadResourceProfile: suspend (CatalogResourceRef) -> CatalogResourceProfile,
    private val loadResourceSection: suspend (CatalogResourceRef, CatalogResourceSection, String?) -> CatalogResourcePage,
    private val enrichArtwork: (suspend (Track) -> Track)? = null,
    private val loadShuffleSample: (suspend (Track, Int) -> CollectionShuffleSample?)? = null,
) {
    private var sequence = 0L
    private var visible = true
    private val mutableVisits = MutableStateFlow<List<DetailVisit>>(emptyList())
    val visits = mutableVisits.asStateFlow()
    val artists = ArtistBrowser(scope, loadProfile, loadArtistSection, enrichArtwork)

    fun open(track: Track, returnToPlayer: Boolean = false) {
        if (track.catalogKind == CatalogKind.Page) { openPage(track, returnToPlayer); return }
        mutableVisits.value.lastOrNull()?.page?.setVisible(false)
        mutableVisits.value.lastOrNull()?.collection?.setVisible(false)
        val visit = if (track.catalogKind == CatalogKind.Artist) {
            // Replace the hidden artist before resuming requests. Resuming first
            // would briefly reload the covered visit only to cancel it in open().
            val artistKey = artists.open(track)
            artists.setVisible(visible)
            DetailVisit(++sequence, track, artistKey = artistKey, returnToPlayer = returnToPlayer)
        } else {
            artists.setVisible(false)
            val collection = CollectionBrowser(scope, loadCollection, { loadRelatedArtists(it).items }, { source ->
                val metadata = CatalogResourceRef.fromTrack(source)?.let { loadResourceProfile(it).item }
                enrichArtwork?.invoke(metadata ?: source) ?: metadata
            }, loadShuffleSample)
            collection.setVisible(visible)
            collection.open(track)
            DetailVisit(++sequence, track, collection = collection, returnToPlayer = returnToPlayer)
        }
        mutableVisits.value = mutableVisits.value + visit
    }

    fun openPage(track: Track, returnToPlayer: Boolean = false) {
        artists.setVisible(false)
        mutableVisits.value.lastOrNull()?.page?.setVisible(false)
        mutableVisits.value.lastOrNull()?.collection?.setVisible(false)
        val page = CatalogPageBrowser(scope, track, loadResourceProfile, loadResourceSection, enrichArtwork)
        page.setVisible(visible)
        mutableVisits.value = mutableVisits.value + DetailVisit(++sequence, track, page = page, returnToPlayer = returnToPlayer)
    }

    fun pop(): DetailVisit? {
        val top = mutableVisits.value.lastOrNull() ?: return null
        artists.setVisible(false)
        if (top.artistKey != null) artists.pop() else { top.collection?.close(); top.page?.close() }
        mutableVisits.value = mutableVisits.value.dropLast(1)
        artists.setVisible(visible && mutableVisits.value.lastOrNull()?.artistKey != null)
        mutableVisits.value.lastOrNull()?.page?.setVisible(visible)
        mutableVisits.value.lastOrNull()?.collection?.setVisible(visible)
        return top
    }

    /** A deleted source invalidates its own branch, including covered descendants. */
    fun removeResource(ref: CatalogResourceRef): List<DetailVisit> {
        val visits = mutableVisits.value
        val index = visits.indexOfFirst { CatalogResourceRef.fromTrack(it.source) == ref }
        if (index < 0) return emptyList()
        val removed = visits.drop(index)
        artists.setVisible(false)
        removed.asReversed().forEach { visit ->
            if (visit.artistKey != null) artists.pop()
            else { visit.collection?.close(); visit.page?.close() }
        }
        mutableVisits.value = visits.take(index)
        val survivor = mutableVisits.value.lastOrNull()
        artists.setVisible(visible && survivor?.artistKey != null)
        survivor?.page?.setVisible(visible)
        survivor?.collection?.setVisible(visible)
        return removed
    }

    fun invalidateLibrary() {
        artists.invalidateLibrary()
        mutableVisits.value.forEach { visit ->
            if (visit.source.resourceType?.startsWith("library-") == true || visit.source.catalogKind == CatalogKind.Playlist) {
                visit.collection?.invalidate()
                visit.page?.invalidate()
            }
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        artists.setVisible(value && mutableVisits.value.lastOrNull()?.artistKey != null)
        mutableVisits.value.lastOrNull()?.page?.setVisible(value)
        mutableVisits.value.lastOrNull()?.collection?.setVisible(value)
    }

    fun close() {
        mutableVisits.value.forEach { it.collection?.close(); it.page?.close() }
        artists.close()
        mutableVisits.value = emptyList()
    }
}
