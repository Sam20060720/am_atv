package dev.amatv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.ui.classic.ClassicBrowseVisuals
import dev.amatv.R
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

internal enum class LibrarySection(val shelfId: String, @param:StringRes val label: Int) {
    Recent("lib-recently-added", R.string.app_recently_added),
    Albums("lib-albums", R.string.library_albums),
    Artists("lib-artists", R.string.library_artists),
    Playlists("lib-playlists", R.string.app_library_playlists),
    Songs("lib-songs", R.string.app_library_songs),
}

/** The library has its own categories and collection views, never editorial hero cards. */
@Composable
internal fun LibraryScreen(
    shelves: List<ContentShelf>,
    pagination: Map<String, LibraryShelfPagination>,
    onSelectSection: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    onOpen: (Track, List<Track>) -> Unit,
    firstFocus: FocusRequester?,
    menuFocus: FocusRequester?,
    onLibrarySearch: () -> Unit = {},
    onSignIn: () -> Unit = {},
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    var selected by rememberSaveable { mutableStateOf(LibrarySection.Recent) }
    var pendingEntry by remember { mutableStateOf<LibrarySection?>(null) }
    val sectionFocus = remember(firstFocus) {
        LibrarySection.entries.associateWith { FocusRequester() }
    }
    val firstItem = remember(selected) { FocusRequester() }
    val gridEntry = remember(selected) { ContentFocusEntry() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val active = LocalFocusTargetsEnabled.current && resumed
    // An IME owns a separate window. Stop speculative collection there without
    // tearing down the active text-editing session.
    val workActive = active && LocalWindowInfo.current.isWindowFocused
    val selectSection by rememberUpdatedState(onSelectSection)
    val shelf = shelves.firstOrNull { it.id == selected.shelfId }
    val knownPage = pagination[selected.shelfId]
    val page = knownPage ?: LibraryShelfPagination(hasMore = false, loading = shelf == null)
    val workScope = rememberCoroutineScope()
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    val latestRetry by rememberUpdatedState(onSelectSection)
    val latestSignIn by rememberUpdatedState(onSignIn)
    val latestPagination by rememberUpdatedState(pagination)
    val locale = LocalConfiguration.current.locales[0]
    val tools = remember(selected, locale) {
        val sectionId = selected.shelfId
        LibraryBrowseTools(workScope, {
            if (latestPagination[sectionId]?.failureReason == dev.amatv.integration.CatalogFailureReason.Authorization) latestSignIn()
            else latestLoadMore(sectionId)
        }, {
            if (latestPagination[sectionId]?.failureReason == dev.amatv.integration.CatalogFailureReason.Authorization) latestSignIn()
            else {
                // An already-loaded category retries its retained continuation.
                if ((latestPagination[sectionId]?.loadedPages ?: 0) > 0) latestLoadMore(sectionId)
                else latestRetry(sectionId)
            }
        }, locale)
    }
    val toolsState by tools.state.collectAsState()
    // Retain only the applied intent across tab recreation, not jobs or a
    // second copy of the library. The existing index rebuilds from host rows.
    var retainedSort by rememberSaveable(selected) { mutableStateOf(LibrarySortOrder.Original) }
    var retainedFilter by rememberSaveable(selected) { mutableStateOf("") }
    var restoredTools by remember(tools) { mutableStateOf(false) }
    SideEffect {
        if (toolsState.revision > 0L) {
            retainedSort = toolsState.sort
            retainedFilter = toolsState.filter
        }
    }
    val rootLabel = stringResource(R.string.app_library_playlists)
    val playlistRoot = remember(rootLabel) { Track(
        id = "apple:p.playlistsroot", title = rootLabel, artist = "", album = "", durationMs = 0,
        palette = listOf(Color(0xFF3A3A40), Color(0xFF202026)), lyrics = emptyList(), catalogKind = CatalogKind.Page,
        resourceId = "p.playlistsroot", resourceType = "library-playlist-folders",
    ) }
    val rootMenu = catalogMenuFor(playlistRoot)
    val toolsFocus = remember(selected) { FocusRequester() }
    val firstLetterFocus = remember(selected) { FocusRequester() }
    var lettersExpanded by rememberSaveable(selected) { mutableStateOf(false) }
    var jumpSequence by remember(selected) { mutableLongStateOf(0L) }
    var jumpRequest by remember(selected) { mutableStateOf<LibraryJumpRequest?>(null) }
    var preparationBackHeld by remember(selected) { mutableStateOf(false) }
    var progressActionFocused by remember(selected) { mutableStateOf(false) }
    val displayed = toolsState.indexedItems ?: shelf?.items.orEmpty()
    val displayedPage = if (toolsState.indexedItems != null) page.copy(
        hasMore = false, loading = false, failed = false, total = displayed.size, empty = false) else page
    DisposableEffect(tools) { onDispose { tools.cancel() } }
    LaunchedEffect(tools, shelf?.items, page, workActive) {
        tools.update(shelf?.items.orEmpty(), LibraryPagingSnapshot(page.hasMore, page.loading, page.failed, page.loadedPages), workActive, page.refreshGeneration)
        if (workActive && !restoredTools) {
            restoredTools = true
            if (retainedSort != LibrarySortOrder.Original || retainedFilter.isNotEmpty()) tools.request(retainedSort, retainedFilter)
        }
    }
    fun cancelPreparation() {
        jumpRequest = null
        if (progressActionFocused) runCatching { toolsFocus.requestFocus() }
        tools.cancel()
    }
    BackHandler(enabled = active && toolsState.preparing) { cancelPreparation() }
    LaunchedEffect(selected, shelf != null, knownPage?.loading, knownPage?.failed) {
        if (shelf == null && knownPage?.loading != true && knownPage?.failed != true) {
            selectSection(selected.shelfId)
        }
    }
    LaunchedEffect(pendingEntry, selected, displayed.size, displayedPage.hasMore, displayedPage.loading, displayedPage.failed, active) {
        if (!active) {
            pendingEntry = null
            return@LaunchedEffect
        }
        if (pendingEntry != selected || displayedPage.loading) return@LaunchedEffect
        // Right chooses the highlighted category before entering it. Cold or
        // empty categories keep focus on their rail instead of a detached node.
        if (displayed.isNotEmpty() || displayedPage.failed) {
            repeat(3) {
                withFrameNanos { }
                if (gridEntry.requestFocus(firstItem)) {
                    pendingEntry = null
                    return@LaunchedEffect
                }
            }
        }
        if (displayed.isEmpty() && !displayedPage.loading && (!displayedPage.hasMore || displayedPage.failed)) {
            runCatching { toolsFocus.requestFocus() }
            pendingEntry = null
        }
        if (!displayedPage.hasMore || displayedPage.failed) pendingEntry = null
    }
    Row(
        Modifier.fillMaxSize().automationId("library-browser").padding(horizontal = 14.dp)
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode != KeyEvent.KEYCODE_BACK || (!toolsState.preparing && !preparationBackHeld)) false
                else {
                    // Bubble after the filter input has had first refusal.
                    // Hold the matching release after the preparation UI closes.
                    if (native.action == KeyEvent.ACTION_DOWN && !preparationBackHeld) {
                        preparationBackHeld = true; cancelPreparation()
                    } else if (native.action == KeyEvent.ACTION_UP) preparationBackHeld = false
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(if (classic) 24.dp else 20.dp),
    ) {
        Column(Modifier.width(if (classic) 156.dp else 148.dp).fillMaxHeight()) {
            Text(stringResource(R.string.nav_library), color = Color.White, fontSize = if (classic) 25.sp else 24.sp, lineHeight = 30.sp,
                fontWeight = if (classic) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 24.dp))
            LibrarySection.entries.forEachIndexed { index, section ->
                val current = selected == section
                FocusSurface(
                    onClick = {
                        pendingEntry = null
                        if (selected != section) tools.cancel()
                        selected = section
                    },
                    modifier = Modifier.fillMaxWidth().height(39.dp)
                        .automationId("library-category-${section.name.lowercase()}")
                        .focusRequester(sectionFocus.getValue(section))
                        .then(if (current && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .focusProperties {
                            left = menuFocus ?: FocusRequester.Cancel
                            if (index == 0) up = menuFocus ?: FocusRequester.Cancel
                            else up = sectionFocus.getValue(LibrarySection.entries[index - 1])
                            if (index == LibrarySection.entries.lastIndex) down = FocusRequester.Cancel
                            else down = sectionFocus.getValue(LibrarySection.entries[index + 1])
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
                            else when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (selected != section) tools.cancel()
                                    selected = section
                                    pendingEntry = section
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK,
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                    pendingEntry = null
                                    false
                                }
                                else -> false
                            }
                        },
                    cornerRadius = if (classic) 8.dp else 19.5.dp, focusedScale = 1.015f,
                    background = if (current) Color.White.copy(alpha = 0.09f) else Color.Transparent,
                    focusedBackground = Color.White.copy(alpha = 0.84f),
                    padding = PaddingValues(horizontal = 13.dp),
                ) { focused ->
                    Text(stringResource(section.label), fontSize = 14.sp, lineHeight = 18.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                        color = focusSurfaceContentColor(
                            Color.White.copy(alpha = if (current) 0.94f else 0.66f), Color(0xFF1B1B1F)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart))
                }
                Spacer(Modifier.height(5.dp))
            }

        }
        Column(Modifier.weight(1f)) {
            LibraryHeading(stringResource(selected.label), displayedPage)
            key(selected) {
                LibraryToolsBar(tools, toolsState, active, toolsFocus, sectionFocus.getValue(selected), menuFocus,
                artistSortAvailable = selected == LibrarySection.Songs || selected == LibrarySection.Albums,
                onEnterContent = {
                    if (lettersExpanded && toolsState.sort != LibrarySortOrder.Original && toolsState.letters.isNotEmpty())
                        runCatching { firstLetterFocus.requestFocus() }
                    else gridEntry.requestFocus(firstItem)
                },
                onShowLetters = { lettersExpanded = true },
                onOriginal = { lettersExpanded = false },
                onCancelNavigation = { jumpRequest = null }, onLibrarySearch = onLibrarySearch,
                onManagePlaylists = if (selected == LibrarySection.Playlists && rootMenu != null) ({ rootMenu(playlistRoot) }) else null,
                onProgressFocus = { progressActionFocused = it })
            }
            if (lettersExpanded && toolsState.sort != LibrarySortOrder.Original && toolsState.letters.isNotEmpty()) {
                FocusTrackingRow(Modifier.fillMaxWidth().height(43.dp).automationId("library-letter-index"),
                    state = rememberLazyListState(), focusVisualOutset = 2.dp,
                    horizontalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(5.dp)) {
                    itemsIndexed(toolsState.letters, key = { _, letter -> letter.letter }) { index, letter ->
                        LibraryToolButton(if (letter.letter == "#") stringResource(R.string.library_tools_other) else letter.letter,
                            { displayed.getOrNull(letter.index)?.let { item ->
                                jumpRequest = LibraryJumpRequest(++jumpSequence, libraryResourceKey(item), letter.index)
                            } }, Modifier.automationId("library-letter-${letter.letter}")
                                .then(if (index == 0) Modifier.focusRequester(firstLetterFocus) else Modifier)
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode in LibraryNavigationKeys) jumpRequest = null
                                    false
                                }.focusProperties { up = toolsFocus; down = firstItem })
                    }
                }
            }
            key(selected) {
                LibraryItems(
                    tracks = displayed, page = displayedPage,
                    songs = selected == LibrarySection.Songs,
                    firstFocus = firstItem, returnFocus = sectionFocus.getValue(selected),
                    entry = gridEntry,
                    onLoadMore = { if (page.failureReason == dev.amatv.integration.CatalogFailureReason.Authorization) onSignIn() else onLoadMore(selected.shelfId) },
                    onRetry = {
                        if (page.failureReason == dev.amatv.integration.CatalogFailureReason.Authorization) onSignIn()
                        else if (page.loadedPages > 0) onLoadMore(selected.shelfId)
                        else onSelectSection(selected.shelfId)
                    }, onOpen = onOpen,
                    aboveFocus = toolsFocus, transformRevision = toolsState.revision,
                    jumpRequest = jumpRequest, onJumpConsumed = { jumpRequest = null },
                    automaticPaging = !toolsState.preparing, screenActive = workActive,
                    emptyMessage = if (toolsState.filter.isNotBlank()) R.string.library_tools_no_matches else R.string.library_empty,
                )
            }
        }
    }
}

@Composable
private fun LibraryHeading(label: String, page: LibraryShelfPagination) {
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        page.total?.let { Text(stringResource(R.string.library_item_count, it),
            color = Color.White.copy(alpha = 0.48f), fontSize = 12.sp, lineHeight = 16.sp) }
    }
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryItems(
    tracks: List<Track>, page: LibraryShelfPagination, songs: Boolean,
    firstFocus: FocusRequester, returnFocus: FocusRequester, entry: ContentFocusEntry,
    onLoadMore: () -> Unit, onRetry: () -> Unit, onOpen: (Track, List<Track>) -> Unit,
    aboveFocus: FocusRequester = returnFocus, transformRevision: Long = 0,
    jumpRequest: LibraryJumpRequest? = null, onJumpConsumed: () -> Unit = {},
    automaticPaging: Boolean = true, screenActive: Boolean = true,
    @StringRes emptyMessage: Int = R.string.library_empty,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val columns = if (songs) 1 else if (classic) 4 else 5
    val active = LocalFocusTargetsEnabled.current && screenActive
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    if (songs) rememberArtworkPrefetch(tracks, listState, active, coverSize = if (classic) 42.dp else 31.dp)
    else rememberArtworkPrefetch(tracks, gridState, active, coverSize = if (classic) ClassicBrowseVisuals.CardWidth else BrowseTokens.CardWidth)
    var savedItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var savedItemIndex by rememberSaveable { mutableIntStateOf(0) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val retainedRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val resourceIndices = remember(tracks) { libraryResourceIndices(tracks) }
    fun itemRequester(index: Int): FocusRequester = retainedRequesters.getOrPut(libraryItemKey(tracks[index])) { FocusRequester() }
    val anchorIndex = libraryFocusTarget(resourceIndices, tracks.size, savedItemKey, savedItemIndex)
    val restoredItemKey = anchorIndex?.let { libraryItemKey(tracks[it]) }
    val currentJump by rememberUpdatedState(jumpRequest)
    val currentContentFocus by rememberUpdatedState(contentHasFocus)
    var navigationGeneration by remember { mutableLongStateOf(0L) }
    val lastRevision = remember { longArrayOf(transformRevision) }
    val oldViewport = remember { intArrayOf(0, 0) }
    LaunchedEffect(songs, listState, gridState) {
        snapshotFlow { if (songs) listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            else gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }.collect {
            oldViewport[0] = it.first; oldViewport[1] = it.second
        }
    }
    SideEffect { anchorIndex?.let { entry.requester = itemRequester(it) } }
    LaunchedEffect(transformRevision, active) {
        val changed = lastRevision[0] != transformRevision
        lastRevision[0] = transformRevision
        if (!changed || !active || currentJump != null) return@LaunchedEffect
        val target = libraryFocusTarget(resourceIndices, tracks.size, savedItemKey, savedItemIndex) ?: return@LaunchedEffect
        val first = libraryViewportFirstItem(oldViewport[0], savedItemIndex, target, columns)
        val offset = oldViewport[1]
        val ticket = ++navigationGeneration
        val shouldFocus = currentContentFocus
        savedItemIndex = target; savedItemKey = libraryItemKey(tracks[target])
        if (songs) listState.scrollToItem(first, offset) else gridState.scrollToItem(first, offset)
        if (shouldFocus) repeat(3) {
            withFrameNanos { }
            if (ticket != navigationGeneration || currentJump != null || !active) return@LaunchedEffect
            if (runCatching { itemRequester(target).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(jumpRequest, active) {
        val request = jumpRequest ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        val target = libraryFocusTarget(resourceIndices, tracks.size, request.resourceKey, request.index) ?: return@LaunchedEffect
        val ticket = ++navigationGeneration
        if (songs) listState.scrollToItem(target) else gridState.scrollToItem(target)
        repeat(3) {
            withFrameNanos { }
            if (ticket != navigationGeneration || currentJump != request || !active) return@LaunchedEffect
            if (runCatching { itemRequester(target).requestFocus() }.getOrDefault(false)) {
                onJumpConsumed(); return@LaunchedEffect
            }
        }
        onJumpConsumed()
    }
    var retryRequested by remember { mutableStateOf(false) }
    LaunchedEffect(active, page.failed) {
        if (!active || page.failed) retryRequested = false
    }
    LaunchedEffect(tracks.isEmpty(), page.hasMore, page.loading, page.failed, active) {
        // A successful retry may legitimately finish with no library items.
        // Remove the retained retry target and return to its real category.
        if (active && retryRequested && tracks.isEmpty() && !page.hasMore && !page.loading && !page.failed) {
            retryRequested = false
            withFrameNanos { }
            runCatching { returnFocus.requestFocus() }
        }
    }
    LaunchedEffect(tracks.isEmpty(), page.hasMore, page.loading, page.failed, active) {
        if (active && tracks.isEmpty() && page.hasMore && !page.loading && !page.failed) onLoadMore()
    }
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize().automationId(if (page.loading || (page.hasMore && !page.failed)) "library-loading" else "library-empty"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (page.loading || (page.hasMore && !page.failed)) AppleLoadingIndicator() else {
                    Text(stringResource(if (page.failed) R.string.library_load_failed else emptyMessage),
                        color = Color.White.copy(alpha = 0.58f), fontSize = 15.sp, lineHeight = 20.sp)
                }
                if (page.failed || retryRequested) LibraryPageAction(page.copy(failed = true),
                    { retryRequested = true; onRetry() }, Modifier.focusRequester(firstFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,
                                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK)) retryRequested = false
                            false
                        }
                        .focusProperties {
                            left = returnFocus; up = returnFocus
                            right = FocusRequester.Cancel; down = FocusRequester.Cancel
                        })
            }
        }
        return
    }
    var pendingAppendIndex by remember { mutableIntStateOf(-1) }
    fun requestNextPage() {
        if (page.loading) return
        pendingAppendIndex = tracks.size
        onLoadMore()
    }
    val continuationFocus = Modifier.onFocusChanged {
        // Automatic prefetch can finish while the footer is focused. Transfer
        // to its first replacement instead of following the footer 100 rows on.
        if (it.isFocused && !page.failed) pendingAppendIndex = tracks.size
    }
    val cancelPendingNavigation = Modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) {
            pendingAppendIndex = -1
            navigationGeneration++
        }
        false
    }
    val loadMore by rememberUpdatedState(onLoadMore)
    val guard = with(LocalDensity.current) { 18.dp.toPx() }
    val bringIntoView = remember(guard) { object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            minimumCardScroll(offset - guard, size + guard * 2, containerSize)
    } }
    LaunchedEffect(retryRequested, active) {
        if (retryRequested && active) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
            retryRequested = false
        }
    }
    LaunchedEffect(tracks.size, page.hasMore, page.loading, page.failed, songs, active, automaticPaging) {
        if (!active || !automaticPaging || !page.hasMore || page.loading || page.failed) return@LaunchedEffect
        snapshotFlow {
            if (songs) listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            else gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.distinctUntilChanged().collect { last ->
            if (last >= (tracks.size - if (songs) 6 else columns * 2).coerceAtLeast(0)) loadMore()
        }
    }
    LaunchedEffect(tracks.size, page.hasMore, page.loading, page.failed, pendingAppendIndex, active) {
        if (!active || page.failed) {
            pendingAppendIndex = -1
            return@LaunchedEffect
        }
        if (pendingAppendIndex < 0 || page.loading) return@LaunchedEffect
        if (tracks.size <= pendingAppendIndex && page.hasMore) return@LaunchedEffect
        val target = pendingAppendIndex.coerceAtMost(tracks.lastIndex)
        if (songs) listState.scrollToItem(target) else gridState.scrollToItem(target)
        withFrameNanos { }
        runCatching { itemRequester(target).requestFocus() }
        pendingAppendIndex = -1
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
        if (songs) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().automationId("library-song-list").then(cancelPendingNavigation)
                .onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(tracks, key = { _, item -> libraryItemKey(item) }, contentType = { _, _ -> "song" }) { index, item ->
                    val requester = itemRequester(index)
                    LibrarySong(item, Modifier.automationId("library-item-$index-song").focusRequester(requester)
                        .then(if (libraryItemKey(item) == restoredItemKey || (restoredItemKey == null && index == 0)) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) { entry.requester = requester; savedItemKey = libraryItemKey(item); savedItemIndex = index } }
                        .focusProperties {
                            left = returnFocus; right = FocusRequester.Cancel
                            if (index == 0) up = aboveFocus
                            if (index == tracks.lastIndex && !page.showAction) down = FocusRequester.Cancel
                        },
                        onClick = { onOpen(item, tracks) })
                }
                if (page.showAction) item(key = "library-continuation") {
                    LibraryPageAction(page, ::requestNextPage, Modifier.padding(vertical = 8.dp).then(continuationFocus)
                        .focusProperties {
                            left = returnFocus; down = FocusRequester.Cancel; right = FocusRequester.Cancel
                        })
                }
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState,
                modifier = Modifier.fillMaxSize().automationId("library-grid").then(cancelPendingNavigation)
                    .onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                gridItemsIndexed(tracks, key = { _, item -> libraryItemKey(item) },
                    contentType = { _, item -> if (item.catalogKind == CatalogKind.Artist) "artist" else "collection" }) { index, item ->
                    val requester = itemRequester(index)
                    LibraryCover(item, Modifier.automationId("library-item-$index-${item.catalogKind.name.lowercase()}")
                        .focusRequester(requester)
                        .then(if (libraryItemKey(item) == restoredItemKey || (restoredItemKey == null && index == 0)) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) { entry.requester = requester; savedItemKey = libraryItemKey(item); savedItemIndex = index } }
                        .focusProperties {
                            if (index % columns == 0) left = returnFocus
                            if (index < columns) up = aboveFocus
                            if (index % columns == columns - 1 || index == tracks.lastIndex) right = FocusRequester.Cancel
                            if (index / columns == tracks.lastIndex / columns && !page.showAction) down = FocusRequester.Cancel
                        }, onClick = { onOpen(item, tracks) })
                }
                if (page.showAction) item(key = "library-continuation", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryPageAction(page, ::requestNextPage, continuationFocus
                        .focusProperties {
                            left = returnFocus; down = FocusRequester.Cancel; right = FocusRequester.Cancel
                        })
                }
            }
        }
    }
}

private fun libraryItemKey(item: Track): String = libraryResourceKey(item)

@Composable
private fun LibraryCover(track: Track, modifier: Modifier, onClick: () -> Unit) {
    val artist = track.catalogKind == CatalogKind.Artist
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val radius = if (classic) ClassicBrowseVisuals.CardRadius else 7.dp
    val backdrop = LocalBrowseBackdropController.current
    val actionMenu = catalogMenuFor(track)
    FocusSurface(onClick, modifier.catalogMenuKey(track).onFocusChanged { if (it.isFocused) backdrop?.select(track) }.fillMaxWidth(),
        onLongClick = actionMenu?.let { open -> { open(track) } }, onLongClickLabel = stringResource(R.string.actions_menu),
        focusedScale = if (classic) ClassicBrowseVisuals.CardFocusScale else 1.025f, focusedLift = if (classic) ClassicBrowseVisuals.CardLift else 0.dp, focusedElevation = 0.dp,
        cornerRadius = if (classic) ClassicBrowseVisuals.CardRadius else if (artist) 64.dp else radius,
        background = Color.Transparent, focusedBackground = Color.Transparent,
    ) { focused ->
        Column(horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start) {
            val shape = if (artist) CircleShape else RoundedCornerShape(radius)
            Box(Modifier.fillMaxWidth().aspectRatio(1f)
                .coverShadow(focused, if (artist) 96.dp else radius)
                .coverHairline(if (artist) 96.dp else radius)) {
                if (track.posterForAspect().isNullOrBlank() && track.motionForAspect(1f) == null) {
                    Box(Modifier.fillMaxSize().clip(shape).background(Color.White.copy(alpha = 0.055f)), Alignment.Center) {
                        Icon(if (artist) Icons.Rounded.Person else if (track.resourceType == "library-playlist-folders") Icons.Rounded.Folder else Icons.Rounded.Album, null,
                            tint = Color.White.copy(alpha = 0.32f), modifier = Modifier.size(34.dp))
                    }
                } else MotionArtwork(track, Modifier.fillMaxSize().clip(shape), animate = rememberBrowseMotionReady(focused),
                    cornerRadius = if (artist) 96.dp else radius)
            }
            Text(track.title, color = Color.White.copy(alpha = 0.92f), fontSize = if (classic) ClassicBrowseVisuals.CardTitleSize else 12.sp, lineHeight = if (classic) 20.sp else 16.sp,
                fontWeight = if (classic) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (classic) 9.dp else 7.dp))
            if (!artist) Text(track.artist, color = Color.White.copy(alpha = 0.53f), fontSize = if (classic) ClassicBrowseVisuals.CardSubtitleSize else 11.sp, lineHeight = if (classic) 17.sp else 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (classic) 1.dp else 2.dp))
            else Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LibrarySong(track: Track, modifier: Modifier, onClick: () -> Unit) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val artworkSize = if (classic) 42.dp else 31.dp
    val actionMenu = catalogMenuFor(track)
    FocusSurface(onClick, modifier.catalogMenuKey(track).fillMaxWidth().height(if (classic) 64.dp else 48.dp), cornerRadius = 8.dp,
        onLongClick = actionMenu?.let { open -> { open(track) } }, onLongClickLabel = stringResource(R.string.actions_menu),
        focusedScale = 1f, background = Color.Transparent, focusedBackground = Color.White.copy(alpha = 0.12f),
        padding = PaddingValues(horizontal = 9.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (track.posterForAspect().isNullOrBlank() && track.motionForAspect(1f) == null) Icon(Icons.Rounded.Album, null, Modifier.size(artworkSize), Color.White.copy(alpha = 0.3f))
            else MotionArtwork(track, Modifier.size(artworkSize), animate = false, cornerRadius = 4.dp)
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White.copy(alpha = 0.92f), fontSize = if (classic) 16.sp else 13.sp, lineHeight = if (classic) 20.sp else 17.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color.White.copy(alpha = 0.52f), fontSize = if (classic) 12.sp else 11.sp, lineHeight = if (classic) 16.sp else 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (track.durationMs > 0L) {
                val seconds = track.durationMs / 1_000
                Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                    color = Color.White.copy(alpha = 0.46f), fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun LibraryPageAction(page: LibraryShelfPagination, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface({ if (!page.loading) onClick() }, modifier.fillMaxWidth().height(38.dp).automationId("library-load-more"),
        interactionFeedbackEnabled = !page.loading, cornerRadius = 19.dp, focusedScale = 1f,
        background = Color.White.copy(alpha = 0.04f), focusedBackground = Color.White.copy(alpha = 0.13f)) {
        if (page.loading) Box(Modifier.fillMaxSize(), Alignment.Center) { AppleLoadingIndicator(Modifier.size(18.dp)) }
        else Text(if (page.failed) catalogRecoveryLabel(page.failureReason) else stringResource(R.string.browse_load_more),
            color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.align(Alignment.Center))
    }
}

private val LibraryNavigationKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryToolsBar(
    tools: LibraryBrowseTools, state: LibraryBrowseToolsState, active: Boolean,
    firstFocus: FocusRequester, categoryFocus: FocusRequester, menuFocus: FocusRequester?,
    artistSortAvailable: Boolean,
    onEnterContent: () -> Unit, onShowLetters: () -> Unit, onOriginal: () -> Unit,
    onCancelNavigation: () -> Unit, onLibrarySearch: () -> Unit,
    onManagePlaylists: (() -> Unit)?,
    onProgressFocus: (Boolean) -> Unit,
) {
    val nameFocus = remember { FocusRequester() }
    val artistFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val filterActionFocus = remember { FocusRequester() }
    val letterFocus = remember { FocusRequester() }
    val progressActionFocus = remember { FocusRequester() }
    var progressActionFocused by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(state.filter) }
    var editing by remember { mutableStateOf(false) }
    var consumeBackRelease by remember { mutableStateOf(false) }
    var preserveDraftOnImeDismiss by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    val currentFilter by rememberUpdatedState(state.filter)
    fun finishEditing(apply: Boolean) {
        if (apply) tools.request(state.sort, draft) else draft = currentFilter
        editing = false; preserveDraftOnImeDismiss = false; keyboard?.hide()
    }
    BackHandler(enabled = active && editing) { finishEditing(false); runCatching { filterFocus.requestFocus() } }
    BackHandler(enabled = active && state.preparing && progressActionFocused) {
        runCatching { firstFocus.requestFocus() }; tools.cancel()
    }
    fun navigateDown() {
        if (state.preparing || state.phase == LibraryToolsPhase.Paused || state.phase == LibraryToolsPhase.Failed)
            runCatching { progressActionFocus.requestFocus() }
        else onEnterContent()
    }
    val progressVisible = state.preparing || state.phase == LibraryToolsPhase.Paused || state.phase == LibraryToolsPhase.Failed
    SideEffect {
        if (!progressVisible && progressActionFocused) {
            runCatching { firstFocus.requestFocus() }
            progressActionFocused = false
            onProgressFocus(false)
        }
    }
    LaunchedEffect(state.filter) { if (!editing) draft = state.filter }
    LaunchedEffect(editing, active) {
        if (!active) { editing = false; draft = currentFilter; keyboard?.hide(); return@LaunchedEffect }
        if (!editing) return@LaunchedEffect
        withFrameNanos { }
        keyboard?.show()
        var shown = false
        snapshotFlow { imeVisible }.collect { visible ->
            if (visible) shown = true
            else if (shown && editing) {
                if (preserveDraftOnImeDismiss) preserveDraftOnImeDismiss = false
                else { editing = false; draft = currentFilter }
            }
        }
    }
    fun toolModifier() = Modifier.height(33.dp).onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
        else {
            if (event.nativeKeyEvent.keyCode in LibraryNavigationKeys) onCancelNavigation()
            if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { navigateDown(); true } else false
        }
    }.focusProperties { up = menuFocus ?: categoryFocus }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).automationId("library-tools")
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.keyCode != KeyEvent.KEYCODE_BACK || (!editing && !consumeBackRelease)) false
            else {
                if (native.action == KeyEvent.ACTION_DOWN) {
                    consumeBackRelease = true; finishEditing(false); runCatching { filterFocus.requestFocus() }
                } else if (native.action == KeyEvent.ACTION_UP) consumeBackRelease = false
                true
            }
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LibraryToolButton(stringResource(R.string.library_tools_original), {
            onCancelNavigation(); finishEditing(false); onOriginal(); tools.request(LibrarySortOrder.Original)
        }, toolModifier().focusRequester(firstFocus).automationId("library-sort-original")
            .focusProperties { left = categoryFocus }, chosen = state.sort == LibrarySortOrder.Original)
        LibraryToolButton(stringResource(R.string.library_tools_name), {
            onCancelNavigation(); finishEditing(false); tools.request(LibrarySortOrder.Name)
        }, toolModifier().focusRequester(nameFocus).automationId("library-sort-name"), chosen = state.sort == LibrarySortOrder.Name)
        if (artistSortAvailable) LibraryToolButton(stringResource(R.string.library_tools_artist), {
            onCancelNavigation(); finishEditing(false); tools.request(LibrarySortOrder.Artist)
        }, toolModifier().focusRequester(artistFocus).automationId("library-sort-artist"), chosen = state.sort == LibrarySortOrder.Artist)
        TvTextField(draft, onValueChange = { draft = it.take(200) }, enabled = active, readOnly = !editing,
            placeholder = stringResource(R.string.library_tools_filter), singleLine = true,
            onActivate = { runCatching { filterFocus.requestFocus() }; editing = true },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { finishEditing(true); runCatching { filterFocus.requestFocus() } }),
            modifier = Modifier.width(170.dp).height(33.dp).automationId("library-filter")
                .focusRequester(filterFocus)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.keyCode == KeyEvent.KEYCODE_BACK && native.action == KeyEvent.ACTION_UP && consumeBackRelease) {
                        consumeBackRelease = false; true
                    } else if (native.action != KeyEvent.ACTION_DOWN) false
                    else when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (native.repeatCount == 0) { if (editing) finishEditing(true) else editing = true }; true
                        }
                        KeyEvent.KEYCODE_BACK -> if (editing) { consumeBackRelease = true; finishEditing(false); true } else false
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onCancelNavigation()
                            if (editing) {
                                preserveDraftOnImeDismiss = true; keyboard?.hide()
                                runCatching { filterActionFocus.requestFocus() }
                            } else navigateDown()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> { finishEditing(false); runCatching { (menuFocus ?: categoryFocus).requestFocus() }; true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (!editing) { runCatching { (if (artistSortAvailable) artistFocus else nameFocus).requestFocus() }; true } else false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (!editing) {
                            runCatching { (if (state.filter.isNotEmpty()) filterActionFocus else letterFocus).requestFocus() }; true
                        } else false
                        else -> false
                    }
                }.focusProperties { up = menuFocus ?: categoryFocus })
        if (editing) LibraryToolButton(stringResource(R.string.library_tools_apply), {
            // This control disappears after applying, so place focus on the persistent field first.
            runCatching { filterFocus.requestFocus() }; finishEditing(true)
        }, toolModifier().automationId("library-filter-apply").focusRequester(filterActionFocus))
        else if (state.filter.isNotEmpty()) LibraryToolButton(stringResource(R.string.library_tools_clear_filter), {
            onCancelNavigation(); draft = ""; runCatching { filterFocus.requestFocus() }; tools.request(state.sort, "")
        }, toolModifier().automationId("library-filter-clear").focusRequester(filterActionFocus))
        LibraryToolButton(stringResource(R.string.library_tools_letters), {
            onCancelNavigation(); finishEditing(false); onShowLetters()
            if (state.sort == LibrarySortOrder.Original || state.indexedItems == null || state.preparing)
                tools.request(if (state.sort == LibrarySortOrder.Artist) LibrarySortOrder.Artist else LibrarySortOrder.Name)
        }, toolModifier().automationId("library-letter-open").focusRequester(letterFocus))
        if (onManagePlaylists != null) FocusSurface({
            onCancelNavigation(); finishEditing(false); onManagePlaylists()
        }, toolModifier().width(33.dp).automationId("library-playlists-manage"),
            focusedScale = 1.015f, cornerRadius = 16.5.dp, background = Color.White.copy(alpha = 0.045f),
            focusedBackground = Color.White.copy(alpha = 0.84f)) {
            Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.catalog_more_actions), Modifier.size(19.dp).align(Alignment.Center),
                tint = focusSurfaceContentColor(Color.White.copy(alpha = 0.86f), Color(0xFF1B1B1F)))
        }
        FocusSurface({ onCancelNavigation(); finishEditing(false); tools.cancel(); onLibrarySearch() },
            toolModifier().width(33.dp).automationId("library-search-all").focusProperties { right = FocusRequester.Cancel },
            focusedScale = 1.015f, cornerRadius = 16.5.dp, background = Color.White.copy(alpha = 0.045f),
            focusedBackground = Color.White.copy(alpha = 0.84f)) {
            Icon(Icons.Rounded.Search, stringResource(R.string.library_tools_search_all), Modifier.size(19.dp).align(Alignment.Center),
                tint = focusSurfaceContentColor(Color.White.copy(alpha = 0.86f), Color(0xFF1B1B1F)))
        }
    }
    Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val label = when (state.phase) {
            LibraryToolsPhase.Collecting -> stringResource(R.string.library_tools_loaded, state.loadedCount)
            LibraryToolsPhase.Indexing -> stringResource(R.string.library_tools_indexing)
            LibraryToolsPhase.Paused -> stringResource(R.string.library_tools_paused, state.loadedCount)
            LibraryToolsPhase.Failed -> stringResource(R.string.library_tools_failed, state.loadedCount)
            LibraryToolsPhase.Idle -> if (state.filter.isNotEmpty() && state.indexedItems != null)
                stringResource(R.string.library_tools_filtered_count, state.indexedItems.size) else ""
        }
        Text(label, Modifier.weight(1f).automationId("library-tools-progress"),
            color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.preparing) LibraryToolButton(stringResource(R.string.library_tools_cancel), {
            runCatching { firstFocus.requestFocus() }; tools.cancel()
        },
            Modifier.height(26.dp).automationId("library-tools-cancel").focusRequester(progressActionFocus)
                .onFocusChanged { progressActionFocused = it.isFocused; onProgressFocus(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { onEnterContent(); true } else false
                }.focusProperties {
                up = firstFocus; left = firstFocus; right = FocusRequester.Cancel
            })
        else if (state.phase == LibraryToolsPhase.Paused || state.phase == LibraryToolsPhase.Failed) LibraryToolButton(
            stringResource(if (state.phase == LibraryToolsPhase.Failed) R.string.recovery_retry else R.string.library_tools_continue),
            { runCatching { firstFocus.requestFocus() }; tools.resume() }, Modifier.height(26.dp).automationId("library-tools-resume").focusRequester(progressActionFocus)
                .onFocusChanged { progressActionFocused = it.isFocused; onProgressFocus(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { onEnterContent(); true } else false
                }.focusProperties { up = firstFocus; left = firstFocus; right = FocusRequester.Cancel })
    }
}

@Composable
private fun LibraryToolButton(label: String, onClick: () -> Unit, modifier: Modifier, chosen: Boolean = false) {
    FocusSurface(onClick, modifier.widthIn(min = 42.dp).semantics { selected = chosen }, cornerRadius = 17.dp,
        focusedScale = 1.015f, background = Color.White.copy(alpha = if (chosen) 0.13f else 0.045f),
        focusedBackground = Color.White.copy(alpha = 0.84f), padding = PaddingValues(horizontal = 12.dp)) {
        Text(label, Modifier.align(Alignment.Center), color = focusSurfaceContentColor(Color.White.copy(alpha = 0.86f), Color(0xFF1B1B1F)),
            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
