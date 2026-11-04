package dev.amatv.ui

import androidx.compose.ui.unit.sp

import androidx.compose.foundation.layout.widthIn

import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.integration.*
import dev.amatv.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class CatalogDetailIntent(val item: Track, val metadata: Boolean = false, val returnToPlayer: Boolean = false)

private enum class BrowseAuxiliaryPage { Insights, Genres }

private fun CatalogResourceLink.asTrack(palette: List<Color>): Track = Track(
    id = "apple:${ref.id}", title = title ?: AppText.text(R.string.browse_loading), artist = "", album = "",
    durationMs = 0L, palette = palette, lyrics = emptyList(),
    catalogId = ref.id.takeUnless { ref.type.startsWith("library-") },
    resourceId = ref.id, resourceType = ref.type,
    catalogKind = when (ref.type.removePrefix("library-")) {
        "songs" -> CatalogKind.Song
        "albums" -> CatalogKind.Album
        "artists" -> CatalogKind.Artist
        "playlists" -> CatalogKind.Playlist
        "music-videos" -> CatalogKind.MusicVideo
        "stations" -> CatalogKind.Station
        else -> CatalogKind.Page
    },
)

/** Retain tab data and navigation, not offscreen screen compositions or reads. */
private class BrowseTabSession(scope: CoroutineScope, services: MusicServices, capabilities: CatalogPresentationCapabilities, destination: Destination) {
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
    val searchUi = CatalogSearchRetention()
    val homeUi = HomeShelfRetention()
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
    fun close() { details.close(); search.dispose(); insights.close(); genres.close(); inlineBrowse.close() }
}

@Composable
internal fun BrowseShell(
    services: MusicServices,
    contextMenuVisible: Boolean,
    isContextMenuOpen: () -> Boolean,
    onDismissPendingContextMenu: () -> Unit,
    destination: Destination,
    isBrowseVisible: Boolean,
    currentTrack: Track,
    hasCurrentTrack: Boolean,
    isPlaying: Boolean,
    recentTracks: List<Track>,
    shelves: List<ContentShelf>,
    shelfPagination: Map<String, LibraryShelfPagination>,
    onLoadMoreShelf: (String) -> Unit,
    onSelectLibrarySection: (String) -> Unit,
    loading: Boolean,
    connectionState: MusicConnectionState,
    setupMessage: String?,
    onDestination: (Destination) -> Unit,
    onBackExhausted: () -> Unit,
    onSignIn: () -> Unit,
    ytmSignedIn: Boolean,
    ytmPairing: GoogleTvPairing?,
    ytmPairingConfigured: Boolean,
    onYtmSignIn: () -> Unit,
    onYtmSignOut: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    requestedDetail: CatalogDetailIntent?,
    onDetailHandled: () -> Unit,
    modalExitToken: Int,
    onPlayCollection: (dev.amatv.playback.CollectionPlaybackRequest) -> Unit,
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    autoplaySource: AutoplaySource = AutoplaySource.AppleMusic,
    onAutoplaySourceChange: (AutoplaySource) -> Unit = {},
    lowPerformanceMode: Boolean = false,
    onLowPerformanceModeChange: (Boolean) -> Unit = {},
    cacheSettingsContent: @Composable (FocusRequester) -> Unit = {},
    onPhoneSignIn: (() -> Unit)? = null,
    searchHistoryScopeKey: String = "guest",
    onRootVisibleChange: (Boolean) -> Unit = {},
    contentNotice: String? = null,
    contentFailureReason: dev.amatv.integration.CatalogFailureReason? = null,
    onReturnToPlayer: () -> Unit = {},
    deletedResource: CatalogResourceRef? = null,
    onDeletedResourceHandled: () -> Unit = {},
) {
    val capabilities = remember(services) { services.presentationCapabilities }
    val navigationDestinations = remember {
        listOf(
            Destination.Home,
            Destination.Browse,
            Destination.Sing,
            Destination.Videos,
            Destination.Radio,
            Destination.Library,
            Destination.Search,
            Destination.Account,
            Destination.NowPlaying,
        )
    }
    val navigationFocus = remember {
        navigationDestinations.associateWith { FocusRequester() }
    }
    val scope = rememberCoroutineScope()
    val menuRequester = remember { FocusRequester() }
    var navigationExpanded by remember { mutableStateOf(false) }
    var navigationPanelVisible by remember { mutableStateOf(false) }
    var expandedBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var expandedBackHeld by remember { mutableStateOf(false) }
    var pendingExpandedBack by remember { mutableStateOf(false) }
    fun handleExpandedBack(): Boolean {
        val handler = expandedBackHandler
        if (handler != null) return handler()
        pendingExpandedBack = true
        return true
    }
    LaunchedEffect(navigationExpanded, expandedBackHandler, pendingExpandedBack) {
        if (!navigationExpanded) {
            expandedBackHeld = false
            pendingExpandedBack = false
        } else if (pendingExpandedBack && expandedBackHandler != null) {
            expandedBackHandler?.invoke()
            pendingExpandedBack = false
        }
    }
    var enterContentAfterNavigation by remember { mutableStateOf(false) }
    var contentEntryPending by remember(destination) { mutableStateOf(false) }
    var contentEntryEpoch by remember { mutableLongStateOf(0L) }
    var playerReturnPending by remember { mutableStateOf(!isBrowseVisible) }
    val focusManager = LocalFocusManager.current
    val tabSessions = remember(services, connectionState) { mutableMapOf<Destination, BrowseTabSession>() }
    val tab = tabSessions.getOrPut(destination) { BrowseTabSession(scope, services, capabilities, destination) }
    DisposableEffect(tabSessions) { onDispose { tabSessions.values.forEach(BrowseTabSession::close) } }
    val deckOpacity = remember { Animatable(1f) }
    var switchingTab by remember { mutableStateOf(false) }
    val currentDestination by rememberUpdatedState(destination)
    val currentlyBrowseVisible by rememberUpdatedState(isBrowseVisible)
    var resetRootPending by remember(destination) { mutableStateOf(false) }
    var resetRootStarted by remember(destination) { mutableStateOf(false) }
    var rootContentHasFocus by remember(destination) { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var modalReturnPending by remember { mutableStateOf(false) }
    val backdropController = tab.backdrop
    val browseLayer = rememberGraphicsLayer()
    val details = tab.details
    val search = tab.search
    var pendingLibrarySearch by remember { mutableStateOf(false) }
    val historyContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val historyStorage = remember(historyContext) { SharedPreferencesSearchHistory(historyContext) }
    LaunchedEffect(search, searchHistoryScopeKey, destination, pendingLibrarySearch) {
        if (destination == Destination.Search) {
            search.setHistoryScope(searchHistoryScopeKey, historyStorage)
            if (pendingLibrarySearch) {
                pendingLibrarySearch = false
                search.selectScope(SearchScope.Library)
            }
        }
    }
    val insights = tab.insights
    val genres = tab.genres
    val inlineBrowseState by tab.inlineBrowse.state.collectAsState()
    val inlineShelves = if (destination == Destination.Browse && services.isCatalogConfigured && shelves.isNotEmpty()) BrowseInlineSection.entries.map { section ->
        ContentShelf(section.shelfId, inlineBrowseState.titles[section] ?: AppText.text(section.labelResource()),
            inlineBrowseState.pages[section]?.items.orEmpty())
    } else emptyList()
    val rootShelves = if (inlineShelves.isEmpty()) shelves else shelves + inlineShelves
    val rootPagination = if (inlineShelves.isEmpty()) shelfPagination else shelfPagination + BrowseInlineSection.entries.associate { section ->
        val page = inlineBrowseState.pages[section]
        section.shelfId to LibraryShelfPagination(
            hasMore = page?.next != null,
            loading = page?.loading == true || page?.loaded != true && page?.failed != true,
            failed = page?.failed == true,
            total = page?.total,
            empty = page?.loaded == true && page.items.isEmpty() && page.next == null,
        )
    }
    var auxiliaryPage by tab.auxiliary
    val auxiliaryOpen = auxiliaryPage != null
    val insightsFocus = tab.insightsFocus
    val insightsFirstFocus = tab.insightsFirstFocus
    val genresFocus = tab.genresFocus
    val genresFirstFocus = tab.genresFirstFocus
    val auxiliaryFocus = when (auxiliaryPage) {
        BrowseAuxiliaryPage.Genres -> genresFocus
        else -> insightsFocus
    }
    val auxiliaryFirstFocus = when (auxiliaryPage) {
        BrowseAuxiliaryPage.Genres -> genresFirstFocus
        else -> insightsFirstFocus
    }
    val detailVisits by details.visits.collectAsState()
    val artistVisits by details.artists.visits.collectAsState()
    val detail = detailVisits.lastOrNull()
    // The root remains visible behind the sidebar. Its first data must load
    // before Right can hand focus into it; gating on sidebar closure deadlocks.
    SideEffect { onRootVisibleChange(isBrowseVisible && detail == null && !auxiliaryOpen &&
        !contextMenuVisible && windowFocused) }
    var detailContentHasFocus by remember(destination, detail?.key) { mutableStateOf(false) }
    var auxiliaryContentHasFocus by remember(destination, auxiliaryPage) { mutableStateOf(false) }
    val currentContentHasFocus = when {
        detail != null -> detailContentHasFocus
        auxiliaryOpen -> auxiliaryContentHasFocus
        else -> rootContentHasFocus
    }
    val observedContentHasFocus by rememberUpdatedState(currentContentHasFocus)
    val upBoundary = remember { BrowseUpBoundary() }
    // A native Dialog releases window focus separately from its composition.
    // Keep the pill's auto-open timer suspended until real content wins focus
    // in the returned window, rather than trusting requestFocus's return value.
    SideEffect {
        if (contextMenuVisible) modalReturnPending = true
        else if (!isBrowseVisible || (windowFocused && currentContentHasFocus)) modalReturnPending = false
    }
    val collectionState = detail?.collection?.state?.collectAsState()?.value ?: CollectionBrowserState()
    val collection = collectionState.collection
    val artist = detail?.takeIf { it.artistKey != null }?.source
    val currentArtistVisit = artistVisits.firstOrNull { it.key == detail?.artistKey }
    val resourceState = detail?.page?.state?.collectAsState()?.value
    val rootStateHolder = key(services, connectionState) { rememberSaveableStateHolder() }
    // Navigation visits are remember-only. Keep their saved UI state in this
    // session too, so recreated ordinal visit keys cannot restore another item.
    // The root holder above still saves its viewport through the Activity.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
    val detailStateHolder = key(services, connectionState) { rememberSaveableStateHolder() }
    val auxiliaryStateHolder = key(services, connectionState) { rememberSaveableStateHolder() }
    val savedDetailKeys = tab.savedDetailKeys
    LaunchedEffect(tab, detailVisits.map { it.key }) {
        val keys = detailVisits.mapTo(mutableSetOf()) { it.key }
        (savedDetailKeys - keys).forEach { detailStateHolder.removeState("${destination.name}:$it") }
        savedDetailKeys.clear()
        savedDetailKeys.addAll(keys)
    }
    LaunchedEffect(tab, isBrowseVisible, detail?.key, contextMenuVisible, auxiliaryPage, switchingTab, shelves.isNotEmpty()) {
        tabSessions.forEach { (route, session) ->
            session.setVisible(session === tab && isBrowseVisible && !contextMenuVisible && !switchingTab,
                route == Destination.Search, route == Destination.Browse, session === tab && shelves.isNotEmpty())
        }
    }
    LaunchedEffect(isBrowseVisible, requestedDetail) {
        val intent = requestedDetail
        if (isBrowseVisible && intent != null && capabilities.canOpen(intent.item)) {
            if (intent.metadata) details.openPage(intent.item, intent.returnToPlayer)
            else details.open(intent.item, intent.returnToPlayer)
            onDetailHandled()
        }
    }
    var returnToContent by tab.returnCount
    val firstContentFocus = tab.firstFocus
    val contentFocusEntry = tab.contentFocus
    var rootReturnToTop by remember(destination) { mutableStateOf<(() -> Boolean)?>(null) }
    val registerRootReturnToTop = remember(destination) { { handler: (() -> Boolean)? -> rootReturnToTop = handler } }
    fun closeDetail() {
        val removed = details.pop() ?: return
        returnToContent++
        if (removed.returnToPlayer) onReturnToPlayer()
    }
    LaunchedEffect(deletedResource, tabSessions) {
        val ref = deletedResource ?: return@LaunchedEffect
        var currentRemoved = emptyList<DetailVisit>()
        tabSessions.values.forEach { session ->
            val removed = session.details.removeResource(ref)
            if (session === tab) currentRemoved = removed
        }
        if (currentRemoved.isNotEmpty()) {
            returnToContent++
            if (currentRemoved.any { it.returnToPlayer }) onReturnToPlayer()
        }
        onDeletedResourceHandled()
    }
    fun backAuxiliary() {
        if (auxiliaryPage != BrowseAuxiliaryPage.Insights || !insights.back()) {
            auxiliaryPage = null
            returnToContent++
        }
    }
    fun openBrowseItem(item: Track, queue: List<Track>) {
        when (item.openAction) {
            CatalogOpenAction.Collection, CatalogOpenAction.Artist, CatalogOpenAction.Page -> details.open(item)
            CatalogOpenAction.Station -> if (capabilities.canPlayStation) onOpenTrack(item, listOf(item))
            CatalogOpenAction.Play -> if (capabilities.canOpen(item)) onOpenTrack(item, queue.filter { it.openAction == CatalogOpenAction.Play && capabilities.canOpen(it) })
        }
    }
    fun enterContent() {
        if (switchingTab) return
        contentEntryEpoch++
        navigationExpanded = false
        contentEntryPending = true
        resetRootStarted = false
        resetRootPending = false
    }
    fun handleRootBack() {
        modalReturnPending = false
        if (rootReturnToTop?.invoke() == true) return
        contentEntryEpoch++
        contentEntryPending = false
        enterContentAfterNavigation = false
        resetRootStarted = false
        resetRootPending = false
        navigationExpanded = true
    }
    LaunchedEffect(isBrowseVisible) {
        if (!isBrowseVisible) {
            playerReturnPending = true
        } else if (playerReturnPending) {
            playerReturnPending = false
            // The player and its source stay composed through their crossfade.
            // Release the old native owner before restoring the actual source;
            // a retained group's hasFocus flag is not a completed handoff.
            focusManager.clearFocus(force = true)
            enterContentAfterNavigation = true
            enterContent()
        }
    }
    LaunchedEffect(modalExitToken, isBrowseVisible) {
        if (modalExitToken > 0 && isBrowseVisible && !contextMenuVisible && requestedDetail == null) {
            // Establish ownership before waiting for native window focus. A
            // two-frame delay here used to let the pill reopen the sidebar.
            enterContent()
        }
    }
    LaunchedEffect(contextMenuVisible, isBrowseVisible) {
        if (contextMenuVisible || !isBrowseVisible) {
            resetRootStarted = false
            resetRootPending = false
        }
    }
    LaunchedEffect(resetRootPending, resetRootStarted, navigationExpanded, rootReturnToTop, auxiliaryPage, detail?.key, switchingTab) {
        if (!resetRootPending || resetRootStarted || navigationExpanded || switchingTab || detail != null || auxiliaryOpen) return@LaunchedEffect
        withFrameNanos { }
        if (rootReturnToTop?.invoke() == true) resetRootStarted = true
        else enterContent()
    }
    LaunchedEffect(resetRootPending, resetRootStarted, rootContentHasFocus) {
        // The scroll callback starts asynchronously. A pill taking transient
        // focus must not restart its sidebar timer before real content wins it.
        if (resetRootPending && resetRootStarted && rootContentHasFocus) {
            resetRootStarted = false
            resetRootPending = false
        }
    }
    fun handleSidebarBack() {
        if (isContextMenuOpen()) {
            onDismissPendingContextMenu()
        } else if (!handleExpandedBack()) {
            // Home focus is the end of sidebar navigation, but a page may
            // still be open behind it. Exhaust that page before asking to exit.
            when {
                detail != null -> { navigationExpanded = false; closeDetail() }
                auxiliaryOpen -> { navigationExpanded = false; backAuxiliary() }
                destination != Destination.Home -> {
                    navigationExpanded = false
                    onDestination(Destination.Home)
                }
                else -> onBackExhausted()
            }
        }
    }
    BackHandler(enabled = isBrowseVisible && switchingTab) { }
    BackHandler(enabled = isBrowseVisible && !switchingTab && navigationExpanded && !contextMenuVisible) {
        handleSidebarBack()
    }
    BackHandler(enabled = isBrowseVisible && !switchingTab && detail == null && !auxiliaryOpen &&
        !navigationExpanded && !contextMenuVisible) {
        if (isContextMenuOpen()) onDismissPendingContextMenu() else handleRootBack()
    }
    BackHandler(enabled = isBrowseVisible && !switchingTab && detail == null && auxiliaryOpen && !navigationExpanded && !contextMenuVisible) {
        if (isContextMenuOpen()) onDismissPendingContextMenu() else backAuxiliary()
    }
    BackHandler(enabled = isBrowseVisible && !switchingTab && detail != null && !navigationExpanded && !contextMenuVisible) {
        if (isContextMenuOpen()) onDismissPendingContextMenu() else closeDetail()
    }
    LaunchedEffect(destination, isBrowseVisible, detail?.key, collectionState.loading, collectionState.error, returnToContent, auxiliaryPage, contextMenuVisible, modalExitToken, switchingTab, windowFocused) {
        if (!isBrowseVisible || !windowFocused || contextMenuVisible || switchingTab || resetRootPending) return@LaunchedEffect
        val requestEpoch = contentEntryEpoch
        withFrameNanos { }
        if (requestEpoch != contentEntryEpoch || navigationExpanded) return@LaunchedEffect
        // Returning from the player changes visibility without popping a
        // detail or incrementing returnToContent. The old default requested the
        // pill here, and its dwell then opened the sidebar over the source card.
        // Every content return uses the same placed-target retry path, including
        // loading details; only a never-entered root starts at the page pill.
        if (detail != null || auxiliaryOpen || tab.rootHasBeenFocused ||
            returnToContent > 0 || enterContentAfterNavigation || modalExitToken > 0) {
            contentEntryPending = true
        } else runCatching { menuRequester.requestFocus() }
    }
    LaunchedEffect(destination, tab, contentEntryEpoch, contentEntryPending, contentFocusEntry.preferredReturnRequester, currentContentHasFocus, navigationExpanded, isBrowseVisible, detail?.key, loading, shelves, connectionState, contextMenuVisible, auxiliaryPage, switchingTab, windowFocused, collectionState.loading, collectionState.error, currentArtistVisit?.profile, currentArtistVisit?.failed, resourceState?.profile, resourceState?.failed) {
        if (!contentEntryPending || navigationExpanded || !isBrowseVisible || !windowFocused || contextMenuVisible || switchingTab || resetRootPending) return@LaunchedEffect
        val requestEpoch = contentEntryEpoch
        fun completeEntry(): Boolean {
            // A requester can accept a transaction while a lazy target or its
            // native window is being replaced. Only the visible content owner
            // winning actual focus completes this navigation intent.
            if (!observedContentHasFocus) return false
            if (detail == null && !auxiliaryOpen && contentFocusEntry.preferredReturnRequester != null) return false
            enterContentAfterNavigation = false
            contentEntryPending = false
            return true
        }
        fun ownsEntry() = requestEpoch == contentEntryEpoch && contentEntryPending && !navigationExpanded
        // Data can finish before a lazy/native target is attached. No further
        // feed state needs to change after that placement, so three frames
        // cannot be the final readiness signal. Retry only this entry intent,
        // with a real time bound even if its window stops delivering frames.
        val completed = withTimeoutOrNull(2_000L) {
            while (ownsEntry()) {
                withFrameNanos { }
                if (!ownsEntry()) return@withTimeoutOrNull false
                val entered = when {
                    detail != null -> detail.focus.requestFocus(detail.firstFocus)
                    auxiliaryOpen -> auxiliaryFocus.requestFocus(auxiliaryFirstFocus)
                    else -> contentFocusEntry.requestFocus(firstContentFocus)
                }
                if (entered) {
                    withFrameNanos { }
                    if (!ownsEntry()) return@withTimeoutOrNull false
                    if (completeEntry()) return@withTimeoutOrNull true
                }
            }
            false
        } == true
        if (completed || !ownsEntry()) return@LaunchedEffect
        if (completeEntry()) return@LaunchedEffect
        // A cold catalog page may still have only a spinner. Keep the request
        // pending until content arrives, and leave a usable menu in the meantime.
        runCatching { menuRequester.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (switchingTab) return@onPreviewKeyEvent true
                // Snapshot the actual origin before Home/sections advance a
                // pending cursor. A fast next press during reveal is not a new
                // permission to leave content; repeats never cross this edge.
                if (native.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (native.action == KeyEvent.ACTION_UP) upBoundary.release()
                    else if (native.action == KeyEvent.ACTION_DOWN && observedContentHasFocus &&
                        !navigationExpanded && !contextMenuVisible) {
                        val entry = when {
                            detail != null -> detail.focus
                            auxiliaryOpen -> auxiliaryFocus
                            else -> contentFocusEntry
                        }
                        upBoundary.upDown(native.repeatCount, entry.navigationUpReady?.invoke() != false)
                    }
                } else if (native.action == KeyEvent.ACTION_DOWN) upBoundary.release()
                if (contentEntryPending && observedContentHasFocus && !navigationExpanded &&
                    native.action == KeyEvent.ACTION_DOWN && native.keyCode in setOf(
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) {
                    // A new remote action now owns the content. A late entry
                    // coroutine must not pull it back to its previous target.
                    contentEntryEpoch++
                    contentEntryPending = false
                    enterContentAfterNavigation = false
                }
                if (native.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (expandedBackHeld) {
                        if (native.action == KeyEvent.ACTION_UP) {
                            expandedBackHeld = false
                            if (!native.isCanceled && navigationExpanded) handleSidebarBack()
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (isBrowseVisible && navigationExpanded && !isContextMenuOpen() &&
                        native.action == KeyEvent.ACTION_DOWN) {
                        // Own the whole press, including Home's final Back.
                        // Letting it fall through clears Compose focus before
                        // the Activity's BackHandler can open the exit dialog.
                        expandedBackHeld = native.repeatCount == 0
                        return@onPreviewKeyEvent true
                    }
                }
                // Search/Account/Library text input gets first refusal for IME Back.
                if (isBrowseVisible && detail == null && !auxiliaryOpen && !navigationExpanded && !contextMenuVisible &&
                    destination != Destination.Search && destination != Destination.Account && destination != Destination.Library &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) handleRootBack()
                    true
                } else false
            }
            .onKeyEvent { event ->
                // Library first lets its input cancel editing and its child
                // bubble cancel preparation. Only an otherwise unhandled Back
                // reaches the root navigation action.
                if (isBrowseVisible && detail == null && !auxiliaryOpen && !navigationExpanded && !contextMenuVisible &&
                    destination == Destination.Library && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) handleRootBack()
                    true
                } else false
            }
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF111113), BrowseTokens.Background),
                ),
            ),
    ) {
        val captureForGlass = (navigationExpanded || navigationPanelVisible) && isBrowseVisible &&
            Build.VERSION.SDK_INT >= 31 && !lowPerformanceMode
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = deckOpacity.value }.drawWithContent {
            if (captureForGlass) {
                browseLayer.record { this@drawWithContent.drawContent() }
                drawLayer(browseLayer)
            } else drawContent()
        }) {
        BrowseBackdrop(
            controller = backdropController,
            active = isBrowseVisible && !lowPerformanceMode,
        )
        CompositionLocalProvider(
            LocalBrowseBackdropController provides backdropController,
            LocalFocusFrameEnabled provides false,
            LocalFocusMotionStyle provides if (lowPerformanceMode) FocusMotionStyle.Immediate else FocusMotionStyle.Television,
            LocalFocusTargetsEnabled provides (detail == null && !auxiliaryOpen && isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible),
            // Visible fades still mount one artwork card per frame. Input,
            // browser requests and motion keep their separate switching gates.
            LocalBrowseContentVisible provides (detail == null && !auxiliaryOpen && isBrowseVisible && !contextMenuVisible),
            LocalBrowseMotionEnabled provides (detail == null && !auxiliaryOpen && isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible && !lowPerformanceMode),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .onFocusChanged {
                    rootContentHasFocus = it.hasFocus
                    if (it.hasFocus) tab.rootHasBeenFocused = true
                }
                .focusGroup()
                .then(if (detail != null || auxiliaryOpen) Modifier.clearAndSetSemantics {} else Modifier)
                .graphicsLayer { alpha = if (detail == null && !auxiliaryOpen) 1f else 0f },
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(
                        horizontal = if (destination == Destination.Search || destination == Destination.Account)
                            BrowseTokens.SafeHorizontal else BrowseTokens.SafeHorizontal - BrowseTokens.RowHorizontalPadding,
                    )
                    .padding(top = BrowseTokens.ContentTop),
            ) {
                key(destination) {
                    rootStateHolder.SaveableStateProvider(destination.name) {
                    CompositionLocalProvider(LocalContentFocusEntry provides contentFocusEntry, LocalHomeShelfRetention provides tab.homeUi) {
                        HomeScreen(
                            destination = destination,
                            currentTrack = currentTrack,
                            homeShelves = rootShelves,
                            homeShelfPagination = rootPagination,
                            onLoadMoreShelf = { id ->
                                if (BrowseInlineSection.entries.any { it.shelfId == id }) tab.inlineBrowse.more(id)
                                else onLoadMoreShelf(id)
                            },
                            onSelectLibrarySection = onSelectLibrarySection,
                            onLibrarySearch = {
                                tabSessions[Destination.Search]?.let { session ->
                                    session.details.close()
                                    session.auxiliary.value = null
                                }
                                pendingLibrarySearch = true
                                enterContentAfterNavigation = true
                                onDestination(Destination.Search)
                            },
                            homeLoading = loading,
                            connectionState = connectionState,
                            setupMessage = setupMessage,
                            contentAuthorizationRequired = contentFailureReason == dev.amatv.integration.CatalogFailureReason.Authorization,
                            onSignIn = onSignIn,
                            onPhoneSignIn = onPhoneSignIn,
                            ytmSignedIn = ytmSignedIn,
                            ytmPairing = ytmPairing,
                            ytmPairingConfigured = ytmPairingConfigured,
                            onYtmSignIn = onYtmSignIn,
                            onYtmSignOut = onYtmSignOut,
                            onSignOut = onSignOut,
                            onRetry = onRetry,
                            onOpenTrack = ::openBrowseItem,
                            onOpenInsights = { link ->
                                insights.selectSource(link.source, link.recommendationId, link.title)
                                auxiliaryPage = BrowseAuxiliaryPage.Insights
                            },
                            onOpenGenres = { auxiliaryPage = BrowseAuxiliaryPage.Genres },
                            onRegisterReturnToTop = registerRootReturnToTop,
                            lyricOffsetMs = lyricOffsetMs,
                            onLyricOffsetChange = onLyricOffsetChange,
                            autoplaySource = autoplaySource,
                            onAutoplaySourceChange = onAutoplaySourceChange,
                            lowPerformanceMode = lowPerformanceMode,
                            onLowPerformanceModeChange = onLowPerformanceModeChange,
                            cacheSettingsContent = { cacheSettingsContent(menuRequester) },
                            searchContent = { first, menu ->
                                CatalogSearchScreen(search, recentTracks, connectionState == MusicConnectionState.SignedIn,
                                    capabilities, first, menu, ::openBrowseItem, onSignIn, retention = tab.searchUi)
                            },
                            firstContentFocusRequester = firstContentFocus,
                            navigationFocusRequester = menuRequester,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    }
                }
            }
        }
        }
        if (auxiliaryOpen) {
            Box(Modifier.fillMaxSize()
                .onFocusChanged { auxiliaryContentHasFocus = it.hasFocus }.focusGroup()
                .onPreviewKeyEvent { event ->
                    if (detail == null && isBrowseVisible && !switchingTab && !navigationExpanded && !isContextMenuOpen() &&
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) backAuxiliary()
                        true
                    } else false
                }
                .padding(horizontal = 40.dp).padding(top = BrowseTokens.ContentTop, bottom = 12.dp)
                .then(if (detail != null) Modifier.clearAndSetSemantics {} else Modifier)
                .graphicsLayer { alpha = if (detail == null) 1f else 0f }) {
                CompositionLocalProvider(
                    LocalContentFocusEntry provides auxiliaryFocus,
                    LocalFocusTargetsEnabled provides (detail == null && isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible),
                    LocalBrowseMotionEnabled provides (detail == null && isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible && !lowPerformanceMode),
                    LocalFocusFrameEnabled provides false,
                    LocalFocusMotionStyle provides if (lowPerformanceMode) FocusMotionStyle.Immediate else FocusMotionStyle.Television,
                ) {
                    val active = detail == null && isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible
                    val onBack = { auxiliaryPage = null; returnToContent++; Unit }
                    auxiliaryStateHolder.SaveableStateProvider("${destination.name}:aux:${auxiliaryPage?.name}") {
                        when (auxiliaryPage) {
                            BrowseAuxiliaryPage.Insights -> ListeningInsightsScreen(insights, active,
                                insightsFirstFocus, menuRequester, capabilities, onBack, ::openBrowseItem, onSignIn = onSignIn)
                            BrowseAuxiliaryPage.Genres -> CatalogRootsScreen(genres, active,
                                genresFirstFocus, menuRequester, capabilities, onBack, ::openBrowseItem)
                            null -> Unit
                        }
                    }
                }
            }
        }
        detail?.let { visit ->
            Box(Modifier.fillMaxSize()
                .onFocusChanged { detailContentHasFocus = it.hasFocus }.focusGroup()
                .onPreviewKeyEvent { event ->
                    if (isBrowseVisible && !switchingTab && !navigationExpanded && !isContextMenuOpen() && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) closeDetail()
                        true
                    } else false
                }
                .padding(horizontal = 40.dp).padding(top = if (visit.artistKey != null || visit.page != null) BrowseTokens.ContentTop else 28.dp, bottom = 12.dp)) {
                CompositionLocalProvider(
                    LocalContentFocusEntry provides visit.focus,
                    LocalBrowseBackdropController provides backdropController,
                    LocalFocusTargetsEnabled provides (isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible),
                    LocalBrowseContentVisible provides (isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible),
                    LocalBrowseMotionEnabled provides (isBrowseVisible && !switchingTab && !navigationExpanded && !contextMenuVisible && !lowPerformanceMode),
                    LocalFocusFrameEnabled provides false,
                    LocalFocusMotionStyle provides if (lowPerformanceMode) FocusMotionStyle.Immediate else FocusMotionStyle.Television,
                ) {
                    detailStateHolder.SaveableStateProvider("${destination.name}:${visit.key}") {
                        if (visit.artistKey != null) {
                            artistVisits.firstOrNull { it.key == visit.artistKey }?.let { state ->
                                ArtistScreen(state, active = isBrowseVisible && !switchingTab && !navigationExpanded,
                                    firstFocus = visit.firstFocus, menuFocus = menuRequester,
                                    capabilities = capabilities,
                                    onRetry = details.artists::retryProfile,
                                    onEnsure = { details.artists.ensureSection(state.key, it) },
                                    onMore = { details.artists.more(state.key, it) },
                                    onRetrySection = { details.artists.retrySection(state.key, it) }, onOpen = ::openBrowseItem,
                                    onSignIn = onSignIn)
                            }
                        } else if (visit.page != null) CatalogResourceScreen(
                            source = visit.source, browser = visit.page, onSignIn = onSignIn, active = isBrowseVisible && !switchingTab && !navigationExpanded,
                            firstFocus = visit.firstFocus, menuFocus = menuRequester, capabilities = capabilities,
                            onOpen = ::openBrowseItem,
                            onOpenLink = { link ->
                                val target = link.asTrack(visit.source.palette)
                                if (capabilities.canOpen(target)) details.open(target)
                            })
                        else CollectionScreen(
                            state = collectionState, currentTrack = currentTrack, isPlaying = isPlaying,
                            firstFocus = visit.firstFocus, navigationFocus = menuRequester,
                            onRetry = { visit.collection?.retry() },
                            onLoadMore = { visit.collection?.more() },
                            onOpenArtist = { details.open(it) },
                            onPlay = { index, shuffle ->
                                visit.collection?.preparePlayback(index, shuffle, onPlayCollection)
                            },
                            onCancelPreparation = { visit.collection?.cancelPreparation() })
                    }
                }
            }
        }
        } // End page capture; the global navigation draws above every browse/detail page.
        if (isBrowseVisible && detail == null && !auxiliaryOpen && contentNotice != null &&
            !navigationExpanded && !contextMenuVisible && windowFocused) {
            androidx.compose.material3.Text(contentNotice,
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = 31.dp, end = BrowseTokens.SafeHorizontal)
                    .widthIn(max = 390.dp).automationId("browse-content-notice"))
        }
        if (isBrowseVisible) {
            TvNavigation(
                selected = destination,
                capabilities = capabilities,
                expanded = navigationExpanded,
                hasCurrentTrack = hasCurrentTrack,
                connectionState = connectionState,
                lowPerformanceMode = lowPerformanceMode,
                autoExpandOnFocus = windowFocused && !modalReturnPending && !contentEntryPending && !enterContentAfterNavigation && !contextMenuVisible &&
                    !switchingTab && !resetRootPending,
                backdropLayer = browseLayer.takeIf { captureForGlass },
                menuRequester = menuRequester,
                canFocusMenu = upBoundary::allowsMenuFocus,
                focusRequesters = navigationFocus,
                registerExpandedBackHandler = { expandedBackHandler = it },
                onPanelVisibleChange = { navigationPanelVisible = it },
                onExpandedChange = expandedChange@{ expanded ->
                    if (switchingTab) return@expandedChange
                    if (expanded) {
                        modalReturnPending = false
                        resetRootStarted = false
                        resetRootPending = false
                        contentEntryEpoch++
                        contentEntryPending = false
                        enterContentAfterNavigation = false
                    }
                    navigationExpanded = expanded
                },
                onDestination = destinationSelection@{ next ->
                    if (switchingTab) return@destinationSelection
                    modalReturnPending = false
                    resetRootStarted = false
                    resetRootPending = false
                    contentEntryEpoch++
                    contentEntryPending = false
                    enterContentAfterNavigation = false
                    navigationExpanded = false
                    if (next == destination) {
                        // Confirming the current tab resets its vertical root;
                        // Right uses onEnterContent and resumes the deck instead.
                        details.close()
                        auxiliaryPage = null
                        resetRootPending = true
                    } else if (next == Destination.NowPlaying || lowPerformanceMode) {
                        enterContentAfterNavigation = next != Destination.NowPlaying
                        onDestination(next)
                    } else {
                        switchingTab = true
                        scope.launch {
                            try {
                                // TabTransition(None): no Y travel; do not mount
                                // the arriving tab until the departing fade ends.
                                deckOpacity.animateTo(0f, tween(150, easing = LinearEasing))
                                if (currentDestination != destination || !currentlyBrowseVisible) return@launch
                                enterContentAfterNavigation = true
                                onDestination(next)
                                withFrameNanos { }
                                deckOpacity.animateTo(1f, tween(150, easing = LinearEasing))
                            } finally {
                                deckOpacity.snapTo(1f)
                                switchingTab = false
                            }
                        }
                    }
                },
                onEnterContent = ::enterContent,
            )
        }
    }
    } // Navigation state belongs to the current tab sessions.
}
