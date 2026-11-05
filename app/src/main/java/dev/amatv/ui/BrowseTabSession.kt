package dev.amatv.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import dev.amatv.integration.*
import dev.amatv.model.*
import kotlinx.coroutines.CoroutineScope

internal enum class BrowseAuxiliaryPage { Insights, Genres }

/** Retain tab data and navigation, not offscreen screen compositions or reads. */
internal class BrowseTabSession(scope: CoroutineScope, services: MusicServices, capabilities: CatalogPresentationCapabilities, destination: Destination) {
    private val catalogConfigured = services.isCatalogConfigured
    val backdrop = BrowseBackdropController()
    val details = DetailBrowser(scope, services::collectionTracksPage, { services.relatedArtistsPage(it) }, services::artistProfile,
        { artist, section, next -> services.artistSectionPage(artist, section, next).let { it.copy(items = capabilities.filterTracks(it.items)) } },
        services::resourceProfile, { ref, section, next -> services.resourceSectionPage(ref, section, next).let { it.copy(items = capabilities.filterTracks(it.items)) } },
        services::editorialArtwork, loadShuffleSample = services::sampleCollectionShuffleSong)
    val search = SearchBrowser(scope, { query, type, next, library ->
        (if (library) services.librarySearchPage(query, type, next) else services.searchPage(query, type, next))
            .let { it.copy(items = capabilities.filterTracks(it.items)) }
    }, services::searchSuggestions)
    // Layouts have different card widths. Retain each view's pixel offsets
    // independently while queries, loaded pages and detail visits stay shared.
    private val searchViews = mutableMapOf<InterfaceStyle, CatalogSearchRetention>()
    private val homeViews = mutableMapOf<InterfaceStyle, HomeShelfRetention>()
    fun searchUi(style: InterfaceStyle) = searchViews.getOrPut(style) { CatalogSearchRetention() }
    fun homeUi(style: InterfaceStyle) = homeViews.getOrPut(style) { HomeShelfRetention() }
    val insights = ListeningInsightsBrowser(scope, services.accountInsights)
    val genres = CatalogRootsBrowser(scope, services.catalogPages, if (destination == Destination.Radio) "station-genres" else "genres")
    val inlineBrowse = BrowseInlineBrowser(scope) { section, cursor ->
        val category = section.chart
        if (category == null) {
            require(cursor == null || cursor is BrowseInlineCursor.Genres)
            val page = services.catalogPages.rootPage("genres", (cursor as? BrowseInlineCursor.Genres)?.path)
            BrowseInlinePage(capabilities.filterTracks(page.items), page.requestedPage,
                page.nextPage?.let { BrowseInlineCursor.Genres(it) }, page.nextPage, page.total)
        } else {
            require(cursor == null || cursor is BrowseInlineCursor.Chart)
            val page = services.catalogCharts.page(category, (cursor as? BrowseInlineCursor.Chart)?.cursor)
            BrowseInlinePage(capabilities.filterTracks(page.items), page.requestedPage,
                page.nextPage?.let { BrowseInlineCursor.Chart(it) }, page.nextPage?.key, page.total, page.title)
        }
    }
    val auxiliary = mutableStateOf<BrowseAuxiliaryPage?>(null)
    val insightsFocus = ContentFocusEntry()
    val insightsFirstFocus = FocusRequester()
    val genresFocus = ContentFocusEntry()
    val genresFirstFocus = FocusRequester()
    val returnCount = mutableIntStateOf(0)
    val firstFocus = FocusRequester()
    val contentFocus = ContentFocusEntry()
    // A first visit may begin on the page pill; a returning visit belongs to
    // its previous card/control even when a lazy requester was recreated.
    var rootHasBeenFocused = false
    val savedDetailKeys = mutableSetOf<Long>()

    init { details.setVisible(false); search.setVisible(false) }
    fun setVisible(visible: Boolean, isSearch: Boolean, isNew: Boolean, hasEditorial: Boolean) {
        val hasDetail = details.visits.value.isNotEmpty()
        details.setVisible(visible)
        insights.setVisible(visible && !hasDetail && auxiliary.value == BrowseAuxiliaryPage.Insights)
        genres.setVisible(visible && !hasDetail && auxiliary.value == BrowseAuxiliaryPage.Genres)
        search.setVisible(visible && !hasDetail && auxiliary.value == null && isSearch)
        inlineBrowse.setVisible(visible && !hasDetail && auxiliary.value == null && isNew && hasEditorial && catalogConfigured)
    }
    private var libraryRevision = 0L
    fun invalidateLibrary(revision: Long) {
        if (libraryRevision == revision) return
        libraryRevision = revision
        details.invalidateLibrary()
        search.invalidateLibrary()
    }
    fun clearFocusIntents() {
        // These entries bridge mounted views. A style may reuse the same data
        // visit while its old exact-return target is no longer attached.
        val entries = listOf(contentFocus, insightsFocus, genresFocus) + details.visits.value.map { it.focus }
        entries.forEach { entry ->
            entry.requester = null
            entry.preferredReturnRequester = null
        }
    }
    fun close() { details.close(); search.dispose(); insights.close(); genres.close(); inlineBrowse.close() }
}

