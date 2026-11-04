package dev.amatv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Text-first categories avoid pretending that a genre without artwork has a broken cover. */
@Composable
internal fun CatalogRootsScreen(
    browser: CatalogRootsBrowser,
    active: Boolean,
    firstFocus: FocusRequester,
    menuFocus: FocusRequester,
    capabilities: CatalogPresentationCapabilities,
    onBack: () -> Unit,
    onOpen: (Track, List<Track>) -> Unit,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    if (browser.type == "station-genres" && !capabilities.canPlayStation) return
    BackHandler(enabled = active, onBack = onBack)
    val page by browser.state.collectAsState()
    val items = remember(page.items, capabilities) { capabilities.filterTracks(page.items) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val entry = LocalContentFocusEntry.current
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val footerFocus = remember { FocusRequester() }
    val focusJob = remember { arrayOfNulls<Job>(1) }
    var selectedKey by rememberSaveable(browser.type) { mutableStateOf("back") }
    // FocusRequester belongs to a composition; the stable key survives a
    // detail visit and is resolved only after its real grid item is composed.
    var restoreKey by remember { mutableStateOf(selectedKey.takeUnless { it == "back" }) }
    var pendingAppend by remember { mutableStateOf<Int?>(null) }
    var waitingForContent by remember { mutableStateOf(false) }
    val currentItems by rememberUpdatedState(items)
    val hasFooter = items.isEmpty() || page.next != null || page.loading || page.failed
    val currentHasFooter by rememberUpdatedState(hasFooter)
    val targetsEnabled = active && LocalFocusTargetsEnabled.current
    val backdrop = LocalBrowseBackdropController.current
    fun itemKey(item: Track) = "${item.resourceType}:${item.resourceId ?: item.id}"
    fun focusIndex(index: Int) {
        focusJob[0]?.cancel()
        focusJob[0] = scope.launch {
            val lastTarget = currentItems.lastIndex + if (currentHasFooter) 1 else 0
            val target = index.coerceIn(0, lastTarget.coerceAtLeast(0))
            grid.scrollToItem(target)
            repeat(3) {
                withFrameNanos { }
                val requester = currentItems.getOrNull(target)?.let { requesters[itemKey(it)] } ?: footerFocus
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
            }
            runCatching { firstFocus.requestFocus() }
        }
    }
    if (selectedKey == "back") SideEffect { entry?.requester = firstFocus }
    DisposableEffect(browser, entry) {
        onDispose {
            focusJob[0]?.cancel()
            val old = entry?.requester
            if (old === firstFocus || old === footerFocus || requesters.values.any { it === old }) entry?.requester = null
        }
    }
    LaunchedEffect(targetsEnabled) {
        if (!targetsEnabled) {
            focusJob[0]?.cancel(); pendingAppend = null; waitingForContent = false
            restoreKey = selectedKey.takeUnless { it == "back" }
        }
    }
    LaunchedEffect(targetsEnabled, restoreKey, items, page.loading, page.loaded, page.failed, hasFooter) {
        val key = restoreKey ?: return@LaunchedEffect
        if (!targetsEnabled) return@LaunchedEffect
        val index = if (key == "continuation") {
            if (hasFooter) items.size else items.lastIndex
        } else items.indexOfFirst { itemKey(it) == key }
        if (index >= 0) {
            restoreKey = null
            focusIndex(index)
        } else if (!page.loading && (page.loaded || page.failed)) {
            restoreKey = null
            focusIndex(0)
        }
    }
    LaunchedEffect(targetsEnabled, page.loading, page.loaded, page.failed, items.size, page.next, pendingAppend, waitingForContent) {
        if (!targetsEnabled) return@LaunchedEffect
        if (page.failed) { pendingAppend = null; waitingForContent = false; return@LaunchedEffect }
        if (page.loading || !page.loaded) return@LaunchedEffect
        val append = pendingAppend
        if (append != null) {
            pendingAppend = null
            waitingForContent = false
            val target = when {
                items.size > append -> append
                hasFooter -> items.size
                else -> items.lastIndex.coerceAtLeast(0)
            }
            focusIndex(target)
        } else if (waitingForContent) {
            waitingForContent = false
            focusIndex(0)
        }
    }
    val cancelPending = Modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)) {
            restoreKey = null; pendingAppend = null; waitingForContent = false; focusJob[0]?.cancel()
        }
        false
    }
    Column(Modifier.fillMaxSize().automationId("catalog-roots-${browser.type}").then(cancelPending)) {
        Text(stringResource(R.string.catalog_all_genres), color = Color.White, fontSize = 24.sp, lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, bottom = 14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val columns = ((maxWidth.value - 28f + 16f) / (190f + 16f)).toInt().coerceAtLeast(1)
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize().automationId("catalog-roots-grid"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(items, key = { _, item -> itemKey(item) }, contentType = { _, _ -> "genre" }) { index, item ->
                    val key = itemKey(item)
                    val requester = requesters.getOrPut(key) { if (index == 0) firstFocus else FocusRequester() }
                    if (selectedKey == key) SideEffect { entry?.requester = requester }
                    FocusSurface({
                        // Pointer activation can open before focus changes.
                        selectedKey = key; entry?.requester = requester
                        onOpen(item, items)
                    },
                        Modifier.fillMaxWidth().heightIn(min = 76.dp).focusRequester(requester).catalogMenuKey(item)
                            .automationId("catalog-root-${item.resourceType}-${item.resourceId ?: item.id}")
                            .onFocusChanged { if (it.isFocused) {
                                selectedKey = key; entry?.requester = requester
                                if (item.artworkUrl != null) backdrop?.select(item)
                            } }
                            .focusProperties {
                                if (index % columns == 0) left = menuFocus
                                if (index % columns == columns - 1 || index == items.lastIndex) right = FocusRequester.Cancel
                                if (index < columns) up = menuFocus
                                if (index + columns >= items.size && !hasFooter) down = FocusRequester.Cancel
                            }.onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                                    index + columns >= items.size && hasFooter) {
                                    focusIndex(items.size); true
                                } else false
                            }, cornerRadius = if (classic) 10.dp else 12.dp, focusedScale = 1.025f,
                        background = Color.White.copy(alpha = 0.055f), focusedBackground = Color.White.copy(alpha = if (classic) 0.14f else 0.86f),
                        padding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
                        onLongClick = catalogMenuFor(item)?.let { open -> {
                            selectedKey = key; entry?.requester = requester
                            open(item)
                        } }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, color = focusSurfaceContentColor(Color.White.copy(alpha = 0.91f), (if (classic) Color.White else Color(0xFF1B1B1F))),
                                fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(17.dp),
                                focusSurfaceContentColor(Color.White.copy(alpha = 0.35f), (if (classic) Color.White else Color(0xFF1B1B1F)).copy(alpha = 0.45f)))
                        }
                    }
                }
                if (hasFooter) item(key = "continuation:${items.size}", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
                    if (selectedKey == "continuation") SideEffect { entry?.requester = footerFocus }
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (items.isEmpty() && !page.loading) Text(stringResource(if (page.failed) R.string.catalog_roots_failed else R.string.catalog_roots_empty),
                            color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                        ArtistTextAction(stringResource(when {
                            page.loading || !page.loaded && !page.failed -> R.string.browse_tracks_loading
                            page.failed -> R.string.browse_reload
                            page.next != null -> R.string.artist_more
                            else -> R.string.browse_reload
                        }), {
                            pendingAppend = items.size
                            when {
                                page.failed || !page.loaded -> browser.retry()
                                page.next != null -> browser.more()
                                else -> browser.reload()
                            }
                        }, Modifier.widthIn(min = 190.dp).focusRequester(footerFocus)
                            .then(if (items.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier).automationId("catalog-roots-more")
                            .onFocusChanged { if (it.isFocused) { selectedKey = "continuation"; entry?.requester = footerFocus; waitingForContent = items.isEmpty() && (page.loading || !page.loaded && !page.failed) } }
                            .focusProperties { left = menuFocus; right = FocusRequester.Cancel; down = FocusRequester.Cancel; if (items.isEmpty()) up = menuFocus }
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && items.isNotEmpty()) {
                                    focusIndex((items.lastIndex / columns) * columns); true
                                } else false
                            }, enabled = !page.loading && (page.loaded || page.failed), retainFocus = true)
                    }
                }
            }
        }
    }
}
