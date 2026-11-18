package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.ui.classic.ClassicBrowseVisuals
import dev.amatv.R
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogSearchTerm
import dev.amatv.model.CatalogSearchType
import dev.amatv.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class SearchZone { Field, History, Suggestions, Scope, Categories, Results }
private data class SearchChoice(val key: String, val label: String, val term: CatalogSearchTerm? = null, val item: Track? = null)

/** Keeps viewport and focus intent only; no retained text connection or artwork composition. */
internal class CatalogSearchRetention {
    var cacheRevision = -1L
    var resultKey: String? = null
    var zone = SearchZone.Field
    val grids = mutableMapOf<String, LazyGridState>()
    val lists = mutableMapOf<String, LazyListState>()
    val categories = mutableMapOf<SearchScope, LazyListState>()
    val savedKeys = mutableMapOf<String, String>()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CatalogSearchScreen(
    browser: SearchBrowser,
    recentTracks: List<Track>,
    signedIn: Boolean,
    capabilities: CatalogPresentationCapabilities,
    firstFocus: FocusRequester?,
    menuFocus: FocusRequester?,
    onOpen: (Track, List<Track>) -> Unit,
    onSignIn: () -> Unit,
    retention: CatalogSearchRetention? = null,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val state by browser.state.collectAsState()
    val active = LocalFocusTargetsEnabled.current
    val retained = retention ?: remember { CatalogSearchRetention() }
    if (retained.cacheRevision != state.cacheRevision) {
        retained.cacheRevision = state.cacheRevision
        retained.grids.clear(); retained.lists.clear(); retained.savedKeys.clear(); retained.categories.clear()
        retained.zone = SearchZone.Field; retained.resultKey = null
    }
    // Keep metadata only for the bounded decoded-result contexts plus this
    // active request and the independent recently-played view.
    val retainedKeys = state.cachedResultKeys + state.resultKey + "recent"
    retained.grids.keys.retainAll(retainedKeys)
    retained.lists.keys.retainAll(retainedKeys)
    retained.savedKeys.keys.retainAll(retainedKeys)
    val scope = rememberCoroutineScope()
    val field = firstFocus ?: remember { FocusRequester() }
    val menu = menuFocus ?: FocusRequester.Cancel
    val scopeFocus = remember { SearchScope.entries.associateWith { FocusRequester() } }
    val categoryFocus = remember { CatalogSearchType.entries.associateWith { FocusRequester() } }
    var editing by remember { mutableStateOf(false) }
    var consumeImeBackRelease by remember { mutableStateOf(false) }
    var zone by remember(state.cacheRevision) { mutableStateOf(retained.zone) }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentImeVisibility by rememberUpdatedState(WindowInsets.isImeVisible)
    val currentWindowFocus by rememberUpdatedState(LocalWindowInfo.current.isWindowFocused)
    val categories = remember(state.scope, capabilities) {
        listOf(CatalogSearchType.Songs, CatalogSearchType.Artists, CatalogSearchType.Albums,
            CatalogSearchType.Playlists, CatalogSearchType.Curators, CatalogSearchType.AppleCurators,
            CatalogSearchType.RecordLabels, CatalogSearchType.Activities, CatalogSearchType.MusicVideos,
            CatalogSearchType.Stations).filter {
            (state.scope != SearchScope.Library || it.libraryResourceType != null) && capabilities.canOpenResourceType(it.resourceType)
        }
    }
    LaunchedEffect(categories, state.type) {
        if (state.type !in categories) categories.firstOrNull()?.let(browser::selectType)
    }
    LaunchedEffect(signedIn) { if (!signedIn && state.scope == SearchScope.Library) browser.selectScope(SearchScope.Catalog) }
    val showingRecent = state.query.isBlank() && state.scope == SearchScope.Catalog
    val showHistory = state.query.isBlank() && state.history.isNotEmpty()
    val historyRow = remember(state.cacheRevision) { LazyListState() }
    val historyRequesters = remember(state.cacheRevision) { mutableMapOf<String, FocusRequester>() }
    val clearHistoryFocus = remember { FocusRequester() }
    var lastHistory by remember(state.cacheRevision) { mutableStateOf<String?>(null) }
    val firstHistory = state.history.firstOrNull()?.let { historyRequesters.retainedFocusRequester(it) }
    val items = remember(state.items, recentTracks, showingRecent, capabilities) {
        capabilities.filterTracks(if (showingRecent) recentTracks else state.items)
    }
    val choices = remember(state.suggestions, state.query, capabilities) {
        // The input already submits this exact query. A duplicate chip adds a
        // whole chrome row while providing neither a refinement nor a result.
        state.suggestions.terms.filterNot { it.searchTerm.trim().equals(state.query.trim(), true) }
            .distinctBy { it.searchTerm.trim().lowercase() }
            .map { SearchChoice("term:${it.searchTerm}", it.displayTerm, term = it) } +
            capabilities.filterTracks(state.suggestions.topResults).map { SearchChoice("resource:${searchItemKey(it)}", it.title, item = it) }
    }
    // Suggestions belong to the query, not to the IME's lifetime. Keep them
    // mounted after Search/Back and while moving through scope/categories/results.
    val showSuggestions = state.scope == SearchScope.Catalog && choices.isNotEmpty() && state.query.isNotBlank()
    val hintFocus = remember(state.query.trim()) { mutableMapOf<String, FocusRequester>() }
    val hintRow = rememberLazyListState()
    var lastHint by remember(state.query.trim()) { mutableStateOf<String?>(null) }
    val firstHint = if (showSuggestions) hintFocus.retainedFocusRequester(choices.first().key) else null
    val selectedScopeFocus = scopeFocus.getValue(state.scope)
    val selectedCategory = categoryFocus.getValue(state.type)
    val categoryList = retained.categories.getOrPut(state.scope) { LazyListState() }
    var pendingEntry by remember { mutableStateOf<String?>(null) }
    val focusJob = remember { arrayOfNulls<Job>(1) }
    val key = if (showingRecent) "recent" else state.resultKey
    fun rowKey(index: Int, item: Track) = if (showingRecent) "$index:${searchItemKey(item)}" else searchItemKey(item)
    val grids = retained.grids
    val lists = retained.lists
    val grid = grids.getOrPut(key) { LazyGridState() }
    val songs = lists.getOrPut(key) { LazyListState() }
    val songRows = !showingRecent && state.type == CatalogSearchType.Songs
    val horizontalResults = classic && !songRows
    if (songRows) rememberArtworkPrefetch(items, songs, active, coverSize = if (classic) 42.dp else 33.dp)
    else if (horizontalResults) rememberArtworkPrefetch(items, songs, active, coverSize = ClassicBrowseVisuals.CardWidth)
    else rememberArtworkPrefetch(items, grid, active)
    val requesters = remember(key) { mutableMapOf<String, FocusRequester>() }
    val savedKeys = retained.savedKeys
    val firstResult = remember(key) { FocusRequester() }
    val footer = remember(key) { FocusRequester() }
    var appendEntry by remember(key) { mutableStateOf<Int?>(null) }
    val hasFooter = !showingRecent && (state.next != null || state.failed || (state.loading && items.isNotEmpty()))
    val density = LocalDensity.current
    val reveal = remember(density) { object : BringIntoViewSpec {
        val guard = with(density) { 18.dp.toPx() }
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            minimumCardScroll(offset - guard, size + guard * 2, containerSize)
    } }
    val entry = LocalContentFocusEntry.current
    fun leaveEditing() { editing = false; keyboard?.hide() }
    fun cancelFocusIntent() { focusJob[0]?.cancel(); pendingEntry = null; appendEntry = null }
    LaunchedEffect(editing, active) {
        if (editing && active) {
            // The native input connection is created after readOnly changes.
            withFrameNanos { }
            keyboard?.show()
            var imeWasVisible = false
            snapshotFlow { currentImeVisibility to currentWindowFocus }.collect { (visible, windowFocused) ->
                if (visible) imeWasVisible = true
                else if (imeWasVisible && windowFocused) {
                    // Android IME can consume Back before the field receives it.
                    // End this editing session only after an observed dismissal;
                    // initial hidden insets and external voice windows are not one.
                    // Keep DPAD focus on the field; the next Back can navigate.
                    editing = false
                }
            }
        }
    }
    fun requestCategory(index: Int) {
        val target = index.coerceIn(0, categories.lastIndex.coerceAtLeast(0))
        val type = categories.getOrNull(target) ?: return
        focusJob[0]?.cancel()
        focusJob[0] = scope.launch {
            if (categoryList.layoutInfo.visibleItemsInfo.none { it.index == target }) categoryList.scrollToItem(target)
            withFrameNanos { }
            runCatching { categoryFocus.getValue(type).requestFocus() }
        }
    }
    fun requestResult(index: Int? = null) {
        focusJob[0]?.cancel()
        focusJob[0] = scope.launch {
            if (items.isEmpty()) {
                if (state.failed) { withFrameNanos { }; runCatching { footer.requestFocus() } }
                return@launch
            }
            val position = index ?: items.indices.firstOrNull { rowKey(it, items[it]) == savedKeys[key] } ?: 0
            val target = position.coerceIn(items.indices)
            if (songRows || horizontalResults) {
                if (songs.layoutInfo.visibleItemsInfo.none { it.index == target }) songs.scrollToItem(target)
            } else if (grid.layoutInfo.visibleItemsInfo.none { it.index == target }) grid.scrollToItem(target)
            repeat(3) {
                withFrameNanos { }
                val requester = requesters[rowKey(target, items[target])] ?: if (target == 0) firstResult else null
                if (requester?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return@launch
            }
        }
    }
    fun requestHistory() {
        if (!showHistory) {
            if (items.isNotEmpty() || state.failed) requestResult()
            else {
                // Blank Search may have neither history nor recent/library
                // results. Its scope control is mounted even in this state.
                focusJob[0]?.cancel()
                runCatching { selectedScopeFocus.requestFocus() }
            }
            return
        }
        focusJob[0]?.cancel()
        val index = state.history.indexOfFirst { it == lastHistory }.coerceAtLeast(0)
        val query = state.history[index]
        zone = SearchZone.History
        retained.zone = zone
        focusJob[0] = scope.launch {
            if (historyRow.layoutInfo.visibleItemsInfo.none { it.index == index }) historyRow.scrollToItem(index)
            withFrameNanos { }
            historyRequesters[query]?.let { runCatching { it.requestFocus() } }
        }
    }

    fun requestHint() {
        focusJob[0]?.cancel()
        if (!showSuggestions) {
            if (state.query.isBlank()) requestHistory() else requestCategory(categories.indexOf(state.type).coerceAtLeast(0))
            return
        }
        zone = SearchZone.Suggestions
        retained.zone = zone
        val index = choices.indexOfFirst { it.key == lastHint }.coerceAtLeast(0)
        val choice = choices[index]
        focusJob[0] = scope.launch {
            if (hintRow.layoutInfo.visibleItemsInfo.none { it.index == index }) hintRow.scrollToItem(index)
            withFrameNanos { }
            val granted = hintFocus[choice.key]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
            if (!granted) runCatching { selectedScopeFocus.requestFocus() }
        }
    }
    fun requestFooter() {
        focusJob[0]?.cancel()
        focusJob[0] = scope.launch {
            if (songRows || horizontalResults) songs.scrollToItem(items.size) else grid.scrollToItem(items.size)
            withFrameNanos { }
            runCatching { footer.requestFocus() }
        }
    }
    LaunchedEffect(key, state.next, state.loading, state.failed, items.isEmpty(), active) {
        // Filtering can leave an empty page before a valid continuation. There
        // is no result/footer target yet, so continue until data or a terminal
        // page arrives. SearchBrowser guards repeated continuation tokens.
        if (active && !showingRecent && items.isEmpty() && state.next != null && !state.loading && !state.failed) browser.loadMore()
    }
    LaunchedEffect(pendingEntry, key, state.next, state.loading, state.failed, items.size, active) {
        if (!active) { pendingEntry = null; return@LaunchedEffect }
        if (pendingEntry != key || state.loading) return@LaunchedEffect
        if (items.isNotEmpty() || state.failed) requestResult()
        else if (state.next == null) runCatching { field.requestFocus() }
        if (items.isNotEmpty() || state.failed || state.next == null) pendingEntry = null
    }
    LaunchedEffect(appendEntry, state.loading, state.failed, items.size, state.next, active) {
        val index = appendEntry ?: return@LaunchedEffect
        if (!active || state.failed) { appendEntry = null; return@LaunchedEffect }
        if (state.loading) return@LaunchedEffect
        if (items.size > index) { requestResult(index); appendEntry = null }
        else if (state.next == null && items.isNotEmpty()) { requestResult(items.lastIndex); appendEntry = null }
    }
    DisposableEffect(active, key) {
        onDispose { focusJob[0]?.cancel() }
    }
    LaunchedEffect(active) { if (!active) { leaveEditing(); pendingEntry = null; appendEntry = null } }
    LaunchedEffect(key) {
        val changedQueryOrType = retained.resultKey != null && retained.resultKey != key
        retained.resultKey = key
        if (pendingEntry != null && pendingEntry != key) pendingEntry = null
        if (changedQueryOrType && active && zone == SearchZone.Results && pendingEntry == null) runCatching { field.requestFocus() }
    }
    SideEffect {
        retained.zone = zone
        // A retained requester belongs to a disposed node after a tab change.
        // Publish the new node at the restored viewport before Host enters it.
        entry?.requester = when (zone) {
            SearchZone.Field -> field
            SearchZone.History -> historyRequesters[lastHistory] ?: firstHistory ?: field
            SearchZone.Scope -> selectedScopeFocus
            SearchZone.Categories -> selectedCategory
            SearchZone.Suggestions -> hintFocus[lastHint] ?: firstHint ?: field
            SearchZone.Results -> if (items.isEmpty()) { if (state.failed) footer else field }
                else requesters[savedKeys[key]] ?: firstResult
        }
    }
    LaunchedEffect(key, state.next, state.loading, state.failed, items.size, active) {
        if (!active || showingRecent || state.next == null || state.loading || state.failed || items.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            if (songRows || horizontalResults) songs.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            else grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.distinctUntilChanged().collect { last -> if (last >= (items.size - if (songRows) 5 else 8).coerceAtLeast(0)) browser.loadMore() }
    }
    val cancelPending = Modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK)) {
            cancelFocusIntent()
        }
        false
    }
    val inputHint = stringResource(R.string.browse_search_hint)
    val remoteInputHint = stringResource(R.string.browse_search_hint_remote)
    val categoryStyle = LocalTextStyle.current.copy(fontSize = 14.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val textMeasurer = rememberTextMeasurer()
    Column(Modifier.fillMaxSize().automationId("catalog-search").then(cancelPending)) {
        // Keep SearchPageFactory's input geometry and title typography while
        // the shared field supplies native input and TV focus/hover feedback.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TvTextField(
                value = state.query, onValueChange = { cancelFocusIntent(); browser.setQuery(it) }, enabled = active, readOnly = !editing,
                size = TvTextFieldSize.Search, placeholder = inputHint,
                onActivate = { cancelFocusIntent(); runCatching { field.requestFocus() }; editing = true },
                modifier = Modifier.weight(1f)
                    .automationId("search-field").focusRequester(field)
                    .semantics { if (!editing) stateDescription = remoteInputHint }
                    .onFocusChanged { if (it.isFocused) { zone = SearchZone.Field; retained.zone = zone; entry?.requester = field } }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.keyCode == KeyEvent.KEYCODE_BACK && native.action == KeyEvent.ACTION_UP && consumeImeBackRelease) {
                            consumeImeBackRelease = false; return@onPreviewKeyEvent true
                        }
                        if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (native.repeatCount == 0) {
                                    if (!editing) editing = true
                                    else { leaveEditing(); browser.submit() }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> if (editing) { consumeImeBackRelease = true; leaveEditing(); true } else false
                            KeyEvent.KEYCODE_DPAD_DOWN -> { leaveEditing(); requestHint(); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!editing) {
                                runCatching { selectedScopeFocus.requestFocus() }; true
                            } else false
                            KeyEvent.KEYCODE_DPAD_LEFT -> if (!editing) {
                                runCatching { menu.requestFocus() }; true
                            } else false
                            KeyEvent.KEYCODE_DPAD_UP -> { leaveEditing(); runCatching { menu.requestFocus() }; true }
                            else -> false
                        }
                    }.focusProperties {
                        up = menu; down = firstHint ?: if (state.query.isBlank()) {
                            firstHistory ?: if (items.isNotEmpty() || state.failed) firstResult else selectedScopeFocus
                        } else selectedCategory
                        if (!editing) { left = menu; right = selectedScopeFocus }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { leaveEditing(); browser.submit() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchScope.entries.forEachIndexed { index, value ->
                    val current = state.scope == value
                    FocusSurface({ cancelFocusIntent(); leaveEditing(); if (value == SearchScope.Library && !signedIn) onSignIn() else browser.selectScope(value) },
                        Modifier.width(if (value == SearchScope.Catalog) 132.dp else 104.dp).height(33.dp).automationId("search-scope-${value.name.lowercase()}")
                            .focusRequester(scopeFocus.getValue(value)).onFocusChanged { if (it.isFocused) { zone = SearchZone.Scope; retained.zone = zone } }
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                    if (showSuggestions) requestHint()
                                    else if (state.query.isBlank()) requestHistory() else requestCategory(categories.indexOf(state.type).coerceAtLeast(0))
                                    true
                                } else false
                            }.focusProperties {
                                up = field; down = firstHint ?: if (showHistory) firstHistory ?: firstResult else if (state.query.isBlank()) {
                                    if (items.isNotEmpty() || state.failed) firstResult else field
                                } else selectedCategory
                                left = if (index == 0) field else scopeFocus.getValue(SearchScope.Catalog)
                                right = if (index == 0) scopeFocus.getValue(SearchScope.Library) else FocusRequester.Cancel
                            },
                        cornerRadius = 17.dp, focusedScale = 1f, padding = PaddingValues(horizontal = 15.dp),
                        background = Color.White.copy(alpha = if (current) 0.12f else 0.035f), focusedBackground = Color.White.copy(alpha = 0.85f)) {
                        Text(stringResource(if (value == SearchScope.Catalog) R.string.catalog_search_scope_catalog else R.string.catalog_search_scope_library),
                            fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = focusSurfaceContentColor(Color.White.copy(alpha = if (current) 0.95f else 0.65f), Color(0xFF171717)),
                            modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        if (showHistory) {
            Text(stringResource(R.string.search_history_title),
                Modifier.padding(start = 6.dp, top = 14.dp, bottom = 3.dp),
                color = Color.White.copy(alpha = 0.78f), fontSize = 15.sp, lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold)
            FocusTrackingRow(Modifier.fillMaxWidth().height(51.dp).automationId("search-history"),
                state = historyRow, focusVisualOutset = 2.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 7.dp, horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(state.history, key = { _, query -> "query:$query" }) { index, query ->
                    val requester = historyRequesters.retainedFocusRequester(query)
                    FocusSurface({
                        cancelFocusIntent(); leaveEditing()
                        // The history chip disappears synchronously when the query changes.
                        runCatching { field.requestFocus() }
                        zone = SearchZone.Field; retained.zone = zone
                        browser.restoreQuery(query)
                        pendingEntry = browser.state.value.resultKey
                    }, Modifier.widthIn(max = 260.dp).height(33.dp).automationId("search-history-$index")
                        .focusRequester(requester).onFocusChanged { if (it.isFocused) {
                            zone = SearchZone.History; retained.zone = zone; lastHistory = query
                        } }.onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
                            else when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> { runCatching { field.requestFocus() }; true }
                                KeyEvent.KEYCODE_DPAD_DOWN -> { if (items.isNotEmpty()) requestResult() else runCatching { selectedScopeFocus.requestFocus() }; true }
                                else -> false
                            }
                        }.focusProperties { up = field; if (index == 0) left = menu },
                        cornerRadius = 16.5.dp, focusedScale = 1.02f,
                        padding = PaddingValues(horizontal = 15.dp),
                        background = Color.White.copy(alpha = 0.10f), focusedBackground = Color.White.copy(alpha = 0.90f)) {
                        Text(query, color = focusSurfaceContentColor(Color.White.copy(alpha = 0.9f), Color(0xFF171717)),
                            fontSize = 15.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center))
                    }
                }
                item(key = "clear-search-history") {
                    ArtistTextAction(stringResource(R.string.search_history_clear), {
                        cancelFocusIntent(); runCatching { field.requestFocus() }; browser.clearHistory()
                    }, Modifier.height(33.dp).automationId("search-history-clear").focusRequester(clearHistoryFocus)
                        .onFocusChanged { if (it.isFocused) { zone = SearchZone.History; retained.zone = zone } }
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                if (items.isNotEmpty()) requestResult() else runCatching { selectedScopeFocus.requestFocus() }; true
                            } else false
                        }.focusProperties { up = field; right = FocusRequester.Cancel })
                }
            }
        }
        if (showSuggestions) FocusTrackingRow(Modifier.fillMaxWidth().height(47.dp).automationId("search-suggestions"), state = hintRow, focusVisualOutset = 2.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 7.dp, horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(choices, key = { _, choice -> choice.key }) { index, choice ->
                val requester = hintFocus.retainedFocusRequester(choice.key)
                FocusSurface({
                    cancelFocusIntent(); leaveEditing()
                    if (choice.term != null) { runCatching { selectedScopeFocus.requestFocus() }; browser.submit(choice.term.searchTerm) }
                    else choice.item?.let { browser.recordSelection(); onOpen(it, state.suggestions.topResults.filter(capabilities::canOpen)) }
                }, Modifier.widthIn(max = 250.dp).height(33.dp).automationId("search-suggestion-$index").focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) { zone = SearchZone.Suggestions; retained.zone = zone; lastHint = choice.key } }
                    .focusProperties { up = field; down = selectedCategory; if (index == 0) left = menu; if (index == choices.lastIndex) right = FocusRequester.Cancel },
                    cornerRadius = 16.5.dp, focusedScale = 1.02f, padding = PaddingValues(start = 6.5.dp, end = 9.dp),
                    background = Color.White.copy(alpha = 0.10f), focusedBackground = Color.White.copy(alpha = 0.90f)) {
                    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.5.dp)) {
                        Box(Modifier.size(20.dp), Alignment.Center) {
                            Icon(SearchHintIcon, null, Modifier.size(width = (76.111f * 25f / 70.46f / 2f).dp, height = (76.817f * 25f / 70.46f / 2f).dp),
                                tint = focusSurfaceContentColor(Color.White, Color.Black))
                        }
                        Text(choice.label, color = focusSurfaceContentColor(Color.White.copy(alpha = 0.8f), Color(0xFF171717)),
                            fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.query.isNotBlank()) {
            // TV17 searchResultsSegmentPlatter: Regular.Plain 66px buttons,
            // 9px padding, 8px spacing and 1260px maximum platter width.
            val platterShape = RoundedCornerShape(21.dp)
            Box(Modifier.widthIn(max = 630.dp).fillMaxWidth().height(42.dp)
                // Music uses the same translucent light tint as its scope and
                // navigation controls, over the already softened page backdrop.
                .background(Color.White.copy(alpha = 0.065f), platterShape)
                .border(0.5.dp, Color.White.copy(alpha = 0.06f), platterShape)
                .searchCategoryFades(categoryList.canScrollBackward, categoryList.canScrollForward).padding(4.5.dp)) {
                FocusTrackingRow(Modifier.fillMaxSize(), state = categoryList, focusVisualOutset = 2.dp,
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    itemsIndexed(categories, key = { _, type -> type.name }) { index, type ->
                        val label = stringResource(type.searchLabel())
                        val labelWidth = remember(label, categoryStyle, density) { textMeasurer.measure(label, categoryStyle).size.width }
                        val buttonWidth = with(density) { labelWidth.toDp() } + 30.dp
                        FocusSurface({ cancelFocusIntent(); leaveEditing(); browser.selectType(type) },
                            Modifier.width(buttonWidth).height(33.dp).automationId("search-category-${type.name.lowercase()}")
                                .focusRequester(categoryFocus.getValue(type)).onFocusChanged { if (it.isFocused) {
                                    zone = SearchZone.Categories; retained.zone = zone
                                    if (state.type != type) browser.selectType(type)
                                } }
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
                                    else when (event.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_UP -> {
                                            if (showSuggestions) requestHint() else runCatching { field.requestFocus() }
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_LEFT -> { if (index == 0) runCatching { menu.requestFocus() } else requestCategory(index - 1); true }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> { if (index < categories.lastIndex) requestCategory(index + 1); true }
                                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            leaveEditing(); browser.selectType(type)
                                            pendingEntry = "${state.scope.name}:${type.name}:${state.query.trim()}"; true
                                        }
                                        else -> false
                                    }
                                }.focusProperties { up = firstHint ?: field; down = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel },
                            cornerRadius = 16.5.dp, focusedScale = (buttonWidth.value + 4f) / buttonWidth.value,
                            padding = PaddingValues(horizontal = 15.dp), background = if (state.type == type) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                            hoveredBackground = Color.White.copy(alpha = 0.15f), focusedBackground = Color.White.copy(alpha = 0.90f)) {
                            Text(label, style = categoryStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = focusSurfaceContentColor(Color.White, Color(0xFF171717)), modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            // The selected segment already names the result kind. Only the
            // empty-query recent section needs an additional section heading.
            if (showingRecent) {
                Text(stringResource(R.string.browse_recently_played), Modifier.padding(start = 18.dp, end = 18.dp, bottom = 5.dp),
                    color = Color.White.copy(alpha = 0.92f), fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides reveal) {
                if (items.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            state.loading -> AppleLoadingIndicator()
                            state.failed -> {
                                Text(stringResource(catalogFailurePresentation(state.failureReason).message), color = Color.White.copy(alpha = 0.56f), fontSize = 14.sp, lineHeight = 18.sp)
                                SearchMore(state, { pendingEntry = key; browser.retry() }, Modifier.width(185.dp).focusRequester(footer)
                                    .focusProperties { left = menu; up = if (state.query.isBlank()) selectedScopeFocus else selectedCategory }, loading = false, onSignIn = onSignIn)
                            }
                            else -> Text(stringResource(when {
                                state.query.isBlank() && state.scope == SearchScope.Library -> R.string.catalog_search_empty_library
                                state.query.isBlank() -> R.string.catalog_search_start
                                else -> R.string.catalog_search_empty
                            }), color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, lineHeight = 18.sp)
                        }
                    }
                } else {
                    fun modifier(index: Int, item: Track, columns: Int): Modifier {
                        val itemKey = rowKey(index, item)
                        val requester = if (index == 0) firstResult else requesters.retainedFocusRequester(itemKey)
                        requesters[itemKey] = requester
                        return Modifier.automationId("search-result-$index").focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) { zone = SearchZone.Results; retained.zone = zone; savedKeys[key] = itemKey; entry?.requester = requester } }
                            .onPreviewKeyEvent { event ->
                                if (!showingRecent && index < columns && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                    requestCategory(categories.indexOf(state.type).coerceAtLeast(0)); true
                                } else if (hasFooter && !horizontalResults && index / columns == items.lastIndex / columns &&
                                    event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                    requestFooter(); true
                                } else false
                            }.focusProperties {
                                if (index < columns) up = if (showingRecent) selectedScopeFocus else FocusRequester.Cancel
                                if (index % columns == 0) left = menu
                                if (index % columns == columns - 1 || index == items.lastIndex) {
                                    right = if (horizontalResults && hasFooter) footer else FocusRequester.Cancel
                                }
                                if (horizontalResults || !hasFooter && index / columns == items.lastIndex / columns) down = FocusRequester.Cancel
                            }
                    }
                    val onContinue: () -> Unit = {
                        appendEntry = items.size; if (state.failed) browser.retry() else browser.loadMore()
                    }
                    val continuationModifier = (if (horizontalResults) Modifier.width(ClassicBrowseVisuals.CardWidth).height(ClassicBrowseVisuals.CardHeight)
                        else Modifier.fillMaxWidth()).focusRequester(footer)
                        .onPreviewKeyEvent { event ->
                            if (horizontalResults && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                requestResult(items.lastIndex); true
                            } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                requestResult(); true
                            } else false
                        }.focusProperties { left = menu; right = FocusRequester.Cancel; down = FocusRequester.Cancel }
                    if (songRows) LazyColumn(state = songs, modifier = Modifier.fillMaxSize().automationId("search-result-list"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(items, key = { index, item -> rowKey(index, item) }) { index, item ->
                            SearchSong(item, modifier(index, item, 1), onOpen = { cancelFocusIntent(); browser.recordSelection(); onOpen(item, items) })
                        }
                        if (hasFooter) item(key = "continuation") { SearchMore(state, onContinue, continuationModifier, onSignIn = onSignIn) }
                    } else if (horizontalResults) FocusTrackingRow(state = songs,
                        modifier = Modifier.fillMaxSize().automationId("search-result-row"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        itemsIndexed(items, key = { index, item -> rowKey(index, item) }) { index, item ->
                            SearchCover(item, modifier(index, item, items.size),
                                onOpen = { cancelFocusIntent(); browser.recordSelection(); onOpen(item, items) })
                        }
                        if (hasFooter) item(key = "continuation") {
                            SearchMore(state, onContinue, continuationModifier, onSignIn = onSignIn)
                        }
                    } else LazyVerticalGrid(columns = GridCells.Fixed(4), state = grid,
                        modifier = Modifier.fillMaxSize().automationId("search-result-grid"), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        gridItemsIndexed(items, key = { index, item -> rowKey(index, item) }) { index, item ->
                            SearchCover(item, modifier(index, item, 4), onOpen = { cancelFocusIntent(); browser.recordSelection(); onOpen(item, items) })
                        }
                        if (hasFooter) item(key = "continuation", span = { GridItemSpan(maxLineSpan) }) { SearchMore(state, onContinue, continuationModifier, onSignIn = onSignIn) }
                    }
                }
            }
        }
    }
}

private fun searchItemKey(item: Track) = "${item.resourceType}:${item.resourceId ?: item.id}"

private fun CatalogSearchType.searchLabel(): Int = when (this) {
    CatalogSearchType.Artists -> R.string.catalog_search_artists
    CatalogSearchType.Songs -> R.string.catalog_search_songs
    CatalogSearchType.Albums -> R.string.catalog_search_albums
    CatalogSearchType.Playlists -> R.string.catalog_search_playlists
    CatalogSearchType.MusicVideos -> R.string.catalog_search_videos
    CatalogSearchType.Stations -> R.string.catalog_search_stations
    CatalogSearchType.Curators -> R.string.catalog_search_curators
    CatalogSearchType.AppleCurators -> R.string.catalog_search_apple_curators
    CatalogSearchType.RecordLabels -> R.string.catalog_search_labels
    CatalogSearchType.Activities -> R.string.catalog_search_activities
}

@Composable
private fun SearchCover(item: Track, modifier: Modifier, onOpen: () -> Unit) {
    val artist = item.catalogKind == CatalogKind.Artist
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val radius = if (artist) 100.dp else if (classic) ClassicBrowseVisuals.CardRadius else BrowseTokens.CardRadius
    val shape = if (artist) CircleShape else RoundedCornerShape(radius)
    val actions = catalogMenuFor(item)
    val backdrop = LocalBrowseBackdropController.current
    FocusSurface(onOpen, modifier.catalogMenuKey(item)
        .then(if (classic) Modifier.width(ClassicBrowseVisuals.CardWidth).height(ClassicBrowseVisuals.CardHeight) else Modifier.fillMaxWidth())
        .onFocusChanged { if (it.isFocused) backdrop?.select(item) },
        cornerRadius = if (classic) ClassicBrowseVisuals.CardRadius else 18.dp,
        focusedScale = 1.035f, focusedLift = if (classic) ClassicBrowseVisuals.CardLift else 0.dp, background = Color.Transparent, focusedBackground = Color.Transparent,
        onLongClick = actions?.let { { it(item) } }) { focused ->
        Column(horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).coverShadow(focused, radius).coverHairline(radius)) {
                if (item.posterForAspect().isNullOrBlank() && item.motionForAspect(1f) == null) Box(Modifier.fillMaxSize().clip(shape).background(Color.White.copy(alpha = 0.045f)), Alignment.Center) {
                    Icon(if (artist) Icons.Rounded.Person else if (item.catalogKind == CatalogKind.Page) Icons.Rounded.GridView else Icons.Rounded.Album,
                        null, Modifier.size(34.dp), tint = Color.White.copy(alpha = 0.3f))
                } else MotionArtwork(item, Modifier.fillMaxSize().clip(shape), animate = rememberBrowseMotionReady(focused), cornerRadius = radius)
            }
            TrackTitle(item, color = Color.White.copy(alpha = 0.94f), fontSize = if (classic) ClassicBrowseVisuals.CardTitleSize else 12.sp, lineHeight = if (classic) 20.sp else 16.sp,
                fontWeight = if (classic) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (classic) 9.dp else 7.dp))
            if (!artist) Text(item.artist, color = Color.White.copy(alpha = 0.53f),
                fontSize = if (classic) ClassicBrowseVisuals.CardSubtitleSize else 11.sp, lineHeight = if (classic) 17.sp else 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (classic) 1.dp else 2.dp))
        }
    }
}

@Composable
private fun SearchSong(item: Track, modifier: Modifier, onOpen: () -> Unit) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val focusedColor = if (classic) Color.White else Color(0xFF171717)
    val artworkSize = if (classic) 42.dp else 33.dp
    val actions = catalogMenuFor(item)
    val backdrop = LocalBrowseBackdropController.current
    FocusSurface(onOpen, modifier.onFocusChanged { if (it.isFocused) backdrop?.select(item) }.catalogMenuKey(item).fillMaxWidth().height(if (classic) 64.dp else 49.dp), cornerRadius = if (classic) 8.dp else 9.dp, focusedScale = 1f,
        background = Color.Transparent, focusedBackground = Color.White.copy(alpha = if (classic) 0.12f else 0.85f), padding = PaddingValues(horizontal = 10.dp),
        onLongClick = actions?.let { { it(item) } }) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (item.posterForAspect().isNullOrBlank() && item.motionForAspect(1f) == null) Icon(Icons.Rounded.Album, null, Modifier.size(artworkSize), Color.White.copy(alpha = 0.3f))
            else MotionArtwork(item, Modifier.size(artworkSize), animate = false, cornerRadius = 4.dp)
            Column(Modifier.weight(1.15f)) {
                TrackTitle(item, color = focusSurfaceContentColor(Color.White.copy(alpha = 0.94f), focusedColor), fontSize = if (classic) 16.sp else 13.sp,
                    lineHeight = if (classic) 20.sp else 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.artist, color = focusSurfaceContentColor(Color.White.copy(alpha = 0.52f), focusedColor.copy(alpha = 0.65f)), fontSize = if (classic) 12.sp else 11.sp, lineHeight = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.album, Modifier.weight(1f).padding(horizontal = 10.dp), color = focusSurfaceContentColor(Color.White.copy(alpha = 0.52f), focusedColor.copy(alpha = 0.65f)),
                fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.durationMs > 0L) Text(searchDuration(item.durationMs), color = focusSurfaceContentColor(Color.White.copy(alpha = 0.4f), focusedColor.copy(alpha = 0.55f)), fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SearchMore(state: SearchBrowserState, onClick: () -> Unit, modifier: Modifier, loading: Boolean = state.loading,
    onSignIn: () -> Unit = {},
) {
    val presentation = catalogFailurePresentation(state.failureReason, continuation = state.items.isNotEmpty())
    val label = when {
        loading -> stringResource(R.string.browse_tracks_loading)
        state.failed && state.items.isNotEmpty() -> catalogRecoveryLabel(state.failureReason, continuation = true)
        state.failed -> stringResource(presentation.action)
        else -> stringResource(R.string.catalog_search_more)
    }
    ArtistTextAction(label, { if (state.failed) performCatalogRecovery(state.failureReason, onClick, onSignIn) else onClick() },
        modifier.automationId("search-more"), enabled = !loading, retainFocus = true)
}

private fun searchDuration(ms: Long): String {
    val seconds = ms / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private val SearchHintIcon: ImageVector by lazy {
    ImageVector.Builder("TV17.SearchHint", (76.111f * 25f / 70.46f / 2f).dp, (76.817f * 25f / 70.46f / 2f).dp, 76.111f, 76.817f).addPath(
        pathData = PathParser().parsePathString("M0 31.617C0 49.072 14.162 63.194 31.617 63.194 37.927 63.194 43.745 61.32 48.643 58.094L65.576 75.067C66.813 76.264 68.398 76.817 70.033 76.817 73.533 76.817 76.111 74.15 76.111 70.66A6.35 6.35 0 0 0 74.393 66.3L57.574 49.442C61.133 44.39 63.194 38.27 63.194 31.617 63.194 14.162 49.072 0 31.617 0 14.162 0 0 14.162 0 31.617ZM9.04 31.617C9.04 19.117 19.116 9.04 31.617 9.04 44.078 9.04 54.194 19.116 54.194 31.617 54.194 44.078 44.078 54.194 31.617 54.194 19.117 54.194 9.04 44.078 9.04 31.617Z").toNodes(),
        fill = SolidColor(Color.White),
    ).build()
}

/** Fade only content at the platter's scrolling edges; its focus outsets remain padded. */
private fun Modifier.searchCategoryFades(left: Boolean, right: Boolean): Modifier =
    if (!left && !right) this else graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val edge = 45.dp.toPx().coerceAtMost(size.width / 2f)
            val leading = Brush.horizontalGradient(0f to Color.Transparent, (1f / 3f) to Color.Transparent, 1f to Color.Black,
                startX = 0f, endX = edge)
            val trailing = Brush.horizontalGradient(0f to Color.Black, (2f / 3f) to Color.Transparent, 1f to Color.Transparent,
                startX = size.width - edge, endX = size.width)
            onDrawWithContent {
                drawContent()
                if (left) drawRect(leading, blendMode = BlendMode.DstIn)
                if (right) drawRect(trailing, blendMode = BlendMode.DstIn)
            }
        }
