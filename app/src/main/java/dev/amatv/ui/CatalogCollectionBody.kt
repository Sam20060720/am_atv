package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.ui.classic.ClassicBrowseVisuals
import dev.amatv.R
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.model.CatalogKind
import dev.amatv.model.ListeningRecommendation
import dev.amatv.model.Track
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/** Resource identity stays stable through artwork enrichment; repeated occurrences remain distinct. */
internal fun catalogCollectionKeys(items: List<Track>): List<String> {
    val occurrences = HashMap<String, Int>()
    return items.map { item ->
        val identity = "${item.resourceType ?: item.catalogKind.name}:${item.resourceId ?: item.id}"
        val occurrence = occurrences[identity] ?: 0
        occurrences[identity] = occurrence + 1
        "item:$identity:$occurrence"
    }
}

/** Group rows span the grid: pagination counts actual rows, not an assumed number of cells. */
internal fun catalogCollectionNearEnd(lastVisibleRow: Int, groupCount: Int, itemCount: Int, columns: Int): Boolean {
    val rows = groupCount + (itemCount + columns.coerceAtLeast(1) - 1) / columns.coerceAtLeast(1)
    return rows == 0 || lastVisibleRow >= 0 && lastVisibleRow >= rows - 3
}

/** The existing browsers own data and cursors; this is only the complete-content viewport. */
@Composable
internal fun CatalogCollectionBody(
    contentKey: String,
    items: List<Track>,
    active: Boolean,
    loaded: Boolean,
    loading: Boolean,
    nextPage: String?,
    failed: Boolean,
    firstFocus: FocusRequester,
    menuFocus: FocusRequester,
    onOpen: (Track, List<Track>) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    failureReason: CatalogFailureReason? = null,
    onSignIn: () -> Unit = {},
    topFocus: FocusRequester? = null,
    groups: List<ListeningRecommendation> = emptyList(),
    onOpenGroup: (ListeningRecommendation) -> Unit = {},
) = key(contentKey) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val cardWidth = if (classic) ClassicBrowseVisuals.CardWidth else BrowseTokens.CardWidth
    val contentActive = active && LocalFocusTargetsEnabled.current
    val latestActive by rememberUpdatedState(contentActive)
    val grid = rememberLazyGridState()
    val songs = items.isNotEmpty() && items.all { it.catalogKind == CatalogKind.Song }
    val itemKeys = remember(items) { catalogCollectionKeys(items) }
    val groupKeys = remember(groups) { groups.map { "group:${it.id}" } }
    val footer = items.isNotEmpty() || groups.isNotEmpty()
    val hasFooter = footer && (nextPage != null || failed || loading)
    val bodyKeys = remember(itemKeys, groupKeys) { groupKeys + itemKeys }
    val availableKeys = remember(bodyKeys, hasFooter) {
        if (bodyKeys.isEmpty()) listOf("status") else bodyKeys + if (hasFooter) listOf("more") else emptyList()
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val retainedKey = selectedKey?.takeIf(availableKeys::contains) ?: availableKeys.first()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var bodyHasFocus by remember { mutableStateOf(false) }
    var wantsStatusEntry by remember { mutableStateOf(false) }
    var appendFrom by remember { mutableStateOf<Int?>(null) }
    val entry = LocalContentFocusEntry.current
    val columns by remember(grid, songs) { derivedStateOf { if (songs) 1 else grid.layoutInfo.maxSpan.coerceAtLeast(1) } }
    val rows = groups.size + (items.size + columns - 1) / columns + if (hasFooter || bodyKeys.isEmpty()) 1 else 0
    val latestMore by rememberUpdatedState(onMore)
    val latestItems by rememberUpdatedState(items)
    rememberArtworkPrefetch(items, grid, contentActive, if (songs) { if (classic) 42.dp else 34.dp } else cardWidth,
        itemIndexOffset = groups.size)

    LaunchedEffect(contentActive) {
        if (!contentActive) { wantsStatusEntry = false; appendFrom = null }
    }
    LaunchedEffect(contentActive, loaded, loading, failed, nextPage, items.size, groups.size) {
        if (!contentActive || !loaded || loading || failed || nextPage == null) return@LaunchedEffect
        snapshotFlow {
            catalogCollectionNearEnd(grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.row } ?: -1,
                groups.size, items.size, grid.layoutInfo.maxSpan.coerceAtLeast(1))
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd && latestActive) latestMore() }
    }
    // Only a focused waiting anchor can hand input to arriving content. Any exit cancels it.
    LaunchedEffect(contentActive, loading, failed, itemKeys, groupKeys, nextPage, wantsStatusEntry, appendFrom) {
        if (!contentActive || loading || failed || bodyKeys.isEmpty()) return@LaunchedEffect
        val append = appendFrom
        val target = when {
            wantsStatusEntry -> bodyKeys.first()
            append != null && items.size > append -> itemKeys[append]
            append != null && nextPage == null -> bodyKeys.last()
            else -> return@LaunchedEffect
        }
        val targetIndex = bodyKeys.indexOf(target)
        grid.scrollToItem(targetIndex.coerceAtLeast(0))
        repeat(3) {
            withFrameNanos { }
            if (!latestActive || (!wantsStatusEntry && appendFrom == null)) return@LaunchedEffect
            if (requesters[target]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) {
                wantsStatusEntry = false; appendFrom = null
                return@LaunchedEffect
            }
        }
    }
    ContentUpExitReadiness(entry) {
        !bodyHasFocus || (topFocus == null && !grid.isScrollInProgress &&
            grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == retainedKey }?.row == 0)
    }
    @Composable fun itemModifier(key: String, automation: String, firstRow: Boolean, firstColumn: Boolean,
        lastColumn: Boolean = false): Modifier {
        val requester = requesters.getOrPut(key) { FocusRequester() }
        if (contentActive && bodyHasFocus && retainedKey == key) SideEffect { entry?.requester = requester }
        return Modifier.automationId(automation).focusRequester(requester)
            .then(if (retainedKey == key) Modifier.focusRequester(firstFocus) else Modifier)
            .semantics { selected = retainedKey == key }
            .onFocusChanged {
                if (it.isFocused) {
                    selectedKey = key
                    entry?.requester = requester
                    if (key == "status") wantsStatusEntry = true
                    if (key == "more") appendFrom = latestItems.size
                }
            }.focusProperties {
                if (firstColumn) left = menuFocus
                if (lastColumn) right = FocusRequester.Cancel
                if (firstRow) up = topFocus ?: menuFocus
            }
    }
    Box(modifier.fillMaxSize().automationId("catalog-collection-body")
        .semantics { collectionInfo = CollectionInfo(rows, columns) }) {
    LazyVerticalGrid(
        columns = if (songs) GridCells.Fixed(1) else GridCells.Adaptive(cardWidth),
        state = grid,
        modifier = Modifier.fillMaxSize()
            .onFocusChanged { bodyHasFocus = it.hasFocus }.focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode in listOf(
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) {
                    wantsStatusEntry = false; appendFrom = null
                }
                false
            }.automationId(if (songs) "catalog-collection-songs" else "catalog-collection-covers"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(BrowseTokens.ColumnGap),
        verticalArrangement = Arrangement.spacedBy(if (songs) 5.dp else BrowseTokens.ShelfGap),
    ) {
        itemsIndexed(groups, key = { index, _ -> groupKeys[index] }, span = { _, _ -> GridItemSpan(maxLineSpan) },
            contentType = { _, _ -> "group" }) { index, group ->
            val key = groupKeys[index]
            Column(Modifier.fillMaxWidth()) {
                ArtistTextAction(group.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.insights_recommendations),
                    { onOpenGroup(group) }, itemModifier(key, "catalog-collection-group-$index", index == 0, true)
                        .fillMaxWidth())
                group.reason?.takeIf(String::isNotBlank)?.let { reason ->
                    Text(reason, color = Color.White.copy(alpha = .5f), fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
                }
            }
        }
        itemsIndexed(items, key = { index, _ -> itemKeys[index] }, contentType = { _, item ->
            if (songs) "song" else if (item.catalogKind == CatalogKind.Artist) "artist" else "cover"
        }) { index, item ->
            val key = itemKeys[index]
            val cell = itemModifier(key, "catalog-collection-item-$index-${item.catalogKind.name.lowercase()}",
                groups.isEmpty() && index < columns, index % columns == 0,
                index % columns == columns - 1 || index == items.lastIndex)
            if (songs) CollectionSongRow(item, cell) { onOpen(item, items) }
            else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                ArtistCover(item, cell) { onOpen(item, items) }
            }
        }
        if (bodyKeys.isEmpty()) item(key = "status", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
            val target = itemModifier("status", "catalog-collection-empty", true, true)
            if (failed) ArtistTextAction(catalogRecoveryLabel(failureReason),
                { performCatalogRecovery(failureReason, onRetry, onSignIn) }, target.fillMaxWidth())
            else Box(target.fillMaxWidth().heightIn(min = 96.dp).focusable(contentActive), Alignment.Center) {
                if (loading || !loaded) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppleLoadingIndicator(Modifier.size(18.dp))
                    Text(stringResource(R.string.browse_tracks_loading), color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
                } else Text(stringResource(R.string.collection_empty), color = Color.White.copy(alpha = .6f), fontSize = 14.sp)
            }
        }
        if (hasFooter) item(key = "more", span = { GridItemSpan(maxLineSpan) }, contentType = "continuation") {
            ArtistTextAction(when {
                loading -> stringResource(R.string.browse_tracks_loading)
                failed -> catalogRecoveryLabel(failureReason, continuation = true)
                else -> stringResource(R.string.browse_load_more)
            }, {
                appendFrom = items.size
                if (failed) performCatalogRecovery(failureReason, onRetry, onSignIn) else onMore()
            }, itemModifier("more", "catalog-collection-more", false, true).fillMaxWidth(),
                enabled = !loading, retainFocus = true)
        }
    }
    }
}

@Composable
private fun CollectionSongRow(track: Track, modifier: Modifier, onClick: () -> Unit) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val actionMenu = catalogMenuFor(track)
    FocusSurface(onClick, modifier.catalogMenuKey(track).fillMaxWidth().height(if (classic) 64.dp else 52.dp),
        cornerRadius = 8.dp, focusedScale = 1f,
        onLongClick = actionMenu?.let { open -> { open(track) } },
        onLongClickLabel = stringResource(R.string.actions_menu), background = Color.Transparent,
        focusedBackground = Color.White.copy(alpha = .12f), padding = PaddingValues(horizontal = 9.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MotionArtwork(track, Modifier.size(if (classic) 42.dp else 34.dp), animate = false, cornerRadius = 4.dp)
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White.copy(alpha = .94f), fontSize = if (classic) 16.sp else 14.sp, lineHeight = if (classic) 20.sp else 18.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color.White.copy(alpha = .54f), fontSize = if (classic) 12.sp else 11.sp, lineHeight = if (classic) 16.sp else 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (track.album.isNotBlank()) Text(track.album, color = Color.White.copy(alpha = .5f),
                fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(.55f))
            if (track.durationMs > 0) {
                val seconds = track.durationMs / 1_000
                Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                    color = Color.White.copy(alpha = .46f), fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.widthIn(min = 33.dp))
            }
        }
    }
}
