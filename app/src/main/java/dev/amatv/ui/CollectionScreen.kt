package dev.amatv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.amatv.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.model.Track
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionScreen(
    state: CollectionBrowserState,
    currentTrack: Track,
    isPlaying: Boolean,
    firstFocus: FocusRequester,
    navigationFocus: FocusRequester,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit = {},
    onCancelPreparation: () -> Unit = {},
    onPlay: (index: Int, shuffle: Boolean) -> Unit,
    onOpenArtist: (Track) -> Unit = {},
) {
    val collection = state.collection ?: return
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val songHeaderCount = if (classic) 0 else 2
    val songRowHeight = if (classic) 64.dp else 58.dp
    val openActions = LocalCatalogActionMenu.current
    val moreFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusEntry = LocalContentFocusEntry.current
    var savedSongFocus by rememberSaveable { mutableIntStateOf(-1) }
    var savedControlFocus by rememberSaveable { mutableStateOf("play") }
    val songFocus = remember(collection.id) { mutableListOf<FocusRequester>() }
    while (songFocus.size < state.tracks.size) songFocus += FocusRequester()
    val continuationFocus = remember { FocusRequester() }
    var wantsContinuationFocus by remember { mutableStateOf(false) }
    var continuationIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<Job?>(null) }
    val songNavigation = remember(collection.id) { CollectionSongNavigation(savedSongFocus.coerceAtLeast(0)) }
    var rightContentHasFocus by remember { mutableStateOf(false) }
    var classicActionsHaveFocus by remember { mutableStateOf(false) }
    val songSelection = remember(collection.id) { Animatable(savedSongFocus.coerceAtLeast(0).toFloat()) }
    val selectionWasVisible = remember(collection.id) { booleanArrayOf(false) }
    val playFocus = firstFocus
    val shuffleFocus = remember { FocusRequester() }
    val artistRowState = rememberLazyListState()
    val artistRequesters = remember(collection.id) { mutableMapOf<String, FocusRequester>() }
    var lastArtistKey by rememberSaveable(collection.id) { mutableStateOf<String?>(null) }
    val ready = !state.loading && !state.preparing && state.tracks.isNotEmpty()
    val initialEmptyError = !state.loading && !state.preparing && state.error != null && state.tracks.isEmpty()
    val showPlayControls = initialEmptyError || !state.complete || state.tracks.isNotEmpty()
    val actionEntryFocus = when {
        showPlayControls -> playFocus
        openActions != null -> moreFocus
        else -> navigationFocus
    }
    if (savedSongFocus < 0 && savedControlFocus in setOf("play", "retry")) SideEffect { focusEntry?.requester = actionEntryFocus }
    if (savedSongFocus < 0 && savedControlFocus == "shuffle") SideEffect {
        focusEntry?.requester = if (showPlayControls && !initialEmptyError) shuffleFocus else actionEntryFocus
    }
    if (savedSongFocus < 0 && savedControlFocus == "more") SideEffect {
        focusEntry?.requester = if (openActions != null) moreFocus else actionEntryFocus
    }
    if (savedSongFocus < 0 && savedControlFocus == "continuation") SideEffect {
        focusEntry?.requester = continuationFocus
    }
    val duration = remember(state.tracks) { state.tracks.sumOf { it.durationMs }.coerceAtLeast(0L) }
    val durationComplete = remember(state.tracks) { state.tracks.isNotEmpty() && state.tracks.all { it.durationMs > 0L } }
    val active = LocalFocusTargetsEnabled.current
    // Failure changes the existing primary node into Retry, retaining native
    // Play focus through loading/error. Only a currently owned Shuffle needs
    // a handoff before its error-state focusability is disabled. Capture that
    // ownership in composition; a sidebar/pill focus must never be reclaimed.
    val moveFailedShuffleToRetry = initialEmptyError && active && (rightContentHasFocus || classicActionsHaveFocus) &&
        savedSongFocus < 0 && savedControlFocus == "shuffle"
    SideEffect {
        if (moveFailedShuffleToRetry) runCatching { playFocus.requestFocus() }
    }
    rememberArtworkPrefetch(state.tracks, listState, active, coverSize = if (classic) 42.dp else 38.dp, itemIndexOffset = songHeaderCount)
    val latestActive by rememberUpdatedState(active)
    val guard = with(LocalDensity.current) { 24.dp.toPx() }
    val rowStep = with(LocalDensity.current) { songRowHeight.toPx() }
    val motionStyle = LocalFocusMotionStyle.current
    val selectionVisible = active && (rightContentHasFocus || songNavigation.pending) && savedSongFocus >= 0
    LaunchedEffect(savedSongFocus, selectionVisible, motionStyle) {
        if (!selectionVisible) { selectionWasVisible[0] = false; return@LaunchedEffect }
        if (!selectionWasVisible[0] || motionStyle == FocusMotionStyle.Immediate) {
            songSelection.snapTo(savedSongFocus.toFloat())
        } else {
            // One persistent pill changes position; independent per-row fades
            // cannot overlap or drop the highlight during fast focus changes.
            songSelection.animateTo(savedSongFocus.toFloat(), tween(85, easing = CubicBezierEasing(.42f, 0f, 1f, 1f)))
        }
        selectionWasVisible[0] = true
    }
    val pageTop = rememberPageTopReveal(listState, edgeClearance = 24.dp) {
        if (!active || !rightContentHasFocus) null
        else when {
            savedSongFocus == 0 -> songHeaderCount
            !classic && savedSongFocus < 0 && savedControlFocus.startsWith("artist:") -> 0
            !classic && savedSongFocus < 0 && savedControlFocus in setOf("play", "retry", "shuffle", "more") -> 1
            else -> null
        }
    }
    ContentUpExitReadiness(focusEntry) {
        savedSongFocus < 0 && !songNavigation.pending && focusJob?.isActive != true &&
            !pageTop.isRevealing && !listState.isScrollInProgress
    }
    val reveal = remember(guard) { object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            minimumCardScroll(offset - guard, size + guard * 2f, containerSize)
    } }
    val pageReveal = remember(pageTop, reveal) { pageTop.spec(reveal) }

    fun cancelPendingFocus() {
        focusJob?.cancel()
        focusJob = null
        // Discard the requested-but-not-focused row. A later Down starts from
        // the actual retained selection rather than skipping several songs.
        songNavigation.cancel(savedSongFocus.takeIf { it in state.tracks.indices } ?: 0)
        wantsContinuationFocus = false
        continuationIndex = -1
    }
    DisposableEffect(collection.id) { onDispose { focusJob?.cancel() } }

    fun moveToActions() {
        focusJob?.cancel()
        focusJob = null
        // Focus owns the reveal when the target is already placed. Scrolling
        // first would leave the selection pill attached to the previous song.
        if (classic) {
            if (latestActive) runCatching { actionEntryFocus.requestFocus() }
            return
        }
        if (latestActive && listState.layoutInfo.visibleItemsInfo.any { it.index == 1 } &&
            runCatching { actionEntryFocus.requestFocus() }.getOrDefault(false)) return
        focusJob = scope.launch {
            listState.animateScrollToItem(1)
            withFrameNanos { }
            if (!latestActive) return@launch
            runCatching { actionEntryFocus.requestFocus() }
        }
    }
    fun moveToArtist() {
        if (state.artists.isEmpty()) { runCatching { navigationFocus.requestFocus() }; return }
        val index = state.artists.indexOfFirst { "artist:${it.resourceType}:${it.resourceId ?: it.id}" == lastArtistKey }.coerceAtLeast(0)
        val artist = state.artists[index]
        val key = "artist:${artist.resourceType}:${artist.resourceId ?: artist.id}"
        focusJob?.cancel()
        focusJob = scope.launch {
            if (!classic) listState.animateScrollToItem(0)
            artistRowState.scrollToItem(index)
            repeat(3) {
                withFrameNanos { }
                if (!latestActive) return@launch
                if (artistRequesters[key]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return@launch
            }
            runCatching { navigationFocus.requestFocus() }
        }
    }
    fun moveToSong(index: Int) {
        if (index !in songFocus.indices) return
        val ticket = songNavigation.request(index)
        focusJob?.cancel()
        focusJob = null
        if (latestActive &&
            runCatching { songFocus[index].requestFocus() }.getOrDefault(false)) {
            songNavigation.focused(index)
            return
        }
        focusJob = scope.launch {
            withFrameNanos { }
            while (latestActive && songNavigation.isCurrent(ticket) && songNavigation.pending) {
                // Catch up through adjacent rows. Repeated keys may update the
                // final intent, but must not scroll the actual focused row far
                // offscreen while waiting for an uncomposed target.
                val next = songNavigation.nextStep()
                val target = next + songHeaderCount
                var acquired = false
                if (runCatching { songFocus[next].requestFocus() }.getOrDefault(false)) {
                    songNavigation.focused(next)
                    acquired = true
                } else coroutineScope {
                    val scroll = launch {
                        val layout = listState.layoutInfo
                        val anchor = layout.visibleItemsInfo.filter { it.index in songHeaderCount until state.tracks.size + songHeaderCount }
                            .minByOrNull { kotlin.math.abs(it.index - target) }
                        if (anchor != null) {
                            // A lazy item also contains its trailing divider.
                            // Use the measured slot stride, not the 58dp focus surface.
                            val offset = (anchor.offset - layout.viewportStartOffset + (target - anchor.index) * anchor.size).toFloat()
                            val distance = reveal.calculateScrollDistance(offset, rowStep,
                                (layout.viewportEndOffset - layout.viewportStartOffset).toFloat())
                            if (kotlin.math.abs(distance) > .5f) listState.animateScrollBy(distance)
                        } else listState.animateScrollToItem(target, -guard.roundToInt())
                    }
                    do {
                        withFrameNanos { }
                        if (!latestActive || !songNavigation.isCurrent(ticket)) { scroll.cancel(); return@coroutineScope }
                        // Transfer focus on its first placed frame, not after
                        // the entire reveal animation has finished.
                        if (runCatching { songFocus[next].requestFocus() }.getOrDefault(false)) {
                            songNavigation.focused(next)
                            acquired = true
                            scroll.cancel()
                            return@coroutineScope
                        }
                    } while (scroll.isActive)
                }
                if (!latestActive || !songNavigation.isCurrent(ticket)) return@launch
                // A failed/cancelled placement is not permission to continue
                // spinning a frame loop or to activate a stale song.
                if (!acquired) { songNavigation.cancel(savedSongFocus.coerceAtLeast(0)); return@launch }
                withFrameNanos { }
            }
        }
    }
    LaunchedEffect(active) { if (!active) cancelPendingFocus() }
    LaunchedEffect(state.tracks.size, state.next, state.loadingMore, state.preparing, state.error, active) {
        if (!active || state.loadingMore || state.preparing || state.error != null || state.next == null) return@LaunchedEffect
        if (state.tracks.isEmpty()) onLoadMore()
        else snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                // Prefetch by song position: Classic keeps metadata outside
                // this list, while Apple has two header items before the songs.
                val lastSong = last - songHeaderCount
                if (lastSong >= (state.tracks.size - 4).coerceAtLeast(0)) onLoadMore()
            }
    }
    LaunchedEffect(state.tracks.size, state.next, state.loadingMore, state.error, active, wantsContinuationFocus) {
        if (!active || !wantsContinuationFocus || state.loadingMore || state.error != null || continuationIndex < 0) return@LaunchedEffect
        val target = when {
            state.tracks.size > continuationIndex -> continuationIndex
            state.next == null && state.tracks.isNotEmpty() -> state.tracks.lastIndex
            else -> return@LaunchedEffect
        }
        withFrameNanos { }
        if (!latestActive) return@LaunchedEffect
        if (!runCatching { songFocus[target].requestFocus() }.getOrDefault(false)) moveToSong(target)
        wantsContinuationFocus = false
        continuationIndex = -1
    }
    val description = remember(collection.description) {
        collection.description?.let {
            android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
        }?.takeIf(String::isNotBlank)
    }
    val backdropController = LocalBrowseBackdropController.current
    LaunchedEffect(backdropController, collection.artworkUrl, collection.palette) {
        backdropController?.select(collection)
    }

    val metadataContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().automationId("collection-metadata")) {
            Text(collection.title, fontSize = if (classic) 25.sp else 22.sp, lineHeight = if (classic) 29.sp else 27.sp,
                color = Color.White, fontWeight = if (classic) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().automationId("collection-title"))
            if (state.artists.isEmpty()) Text(collection.artist, color = if (classic) Color(0xFFFF788B) else Color.White.copy(alpha = 0.74f), fontSize = if (classic) 16.sp else 14.sp, lineHeight = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (state.artists.isNotEmpty()) LazyRow(
                Modifier.fillMaxWidth().padding(top = 4.dp), state = artistRowState, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(state.artists, key = { _, artist -> "${artist.resourceType}:${artist.resourceId ?: artist.id}" }) { index, artist ->
                    val artistKey = "artist:${artist.resourceType}:${artist.resourceId ?: artist.id}"
                    val artistFocus = artistRequesters.getOrPut(artistKey) { FocusRequester() }
                    if (savedSongFocus < 0 && savedControlFocus == artistKey) SideEffect { focusEntry?.requester = artistFocus }
                    FocusSurface({
                        savedSongFocus = -1; savedControlFocus = artistKey; lastArtistKey = artistKey; focusEntry?.requester = artistFocus
                        onOpenArtist(artist)
                    }, Modifier.height(30.dp).focusRequester(artistFocus).pageTopRevealTarget()
                        .automationId("collection-artist-$index")
                        .onFocusChanged { if (it.isFocused) { savedSongFocus = -1; savedControlFocus = artistKey; lastArtistKey = artistKey; pageTop.request() } }
                        .focusProperties { if (index == 0) left = navigationFocus; if (index == state.artists.lastIndex) right = FocusRequester.Cancel; up = navigationFocus; down = actionEntryFocus },
                        cornerRadius = 8.dp, focusedScale = 1f, background = Color.Transparent,
                        focusedBackground = Color.White.copy(alpha = 0.13f), padding = PaddingValues(horizontal = 6.dp)) {
                        Text(artist.title, color = if (classic) Color(0xFFFF788B) else Color.White.copy(alpha = 0.74f), fontSize = if (classic) 16.sp else 14.sp, lineHeight = 18.sp,
                            modifier = Modifier.align(Alignment.Center), maxLines = 1)
                    }
                }
            }
            if (description != null) Text(description,
                color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = if (classic) 2 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).automationId("collection-description"))
            Text(when {
                state.loading -> stringResource(R.string.browse_tracks_loading)
                state.preparing -> stringResource(R.string.collection_preparing_shuffle_count, state.tracks.size)
                state.error != null && state.tracks.isEmpty() -> stringResource(R.string.browse_tracks_unavailable)
                !state.complete -> stringResource(R.string.collection_loaded_count, state.tracks.size)
                !durationComplete -> pluralStringResource(R.plurals.browse_song_count, state.tracks.size, state.tracks.size)
                else -> stringResource(R.string.browse_collection_summary,
                    pluralStringResource(R.plurals.browse_song_count, state.tracks.size, state.tracks.size),
                    collectionDuration(duration))
            }, color = Color.White.copy(alpha = 0.48f), fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 18.dp))
        }
    }
    val actionsContent: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp).automationId("collection-actions")
            .then(if (classic) Modifier.onFocusChanged { classicActionsHaveFocus = it.hasFocus }.focusGroup() else Modifier)
            .onPreviewKeyEvent { event ->
                if (state.artists.isNotEmpty() && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    moveToArtist(); true
                } else false
            },
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showPlayControls) CollectionAction(
                stringResource(when {
                    initialEmptyError -> R.string.browse_reload
                    state.preparing -> R.string.collection_cancel_preparation
                    else -> R.string.browse_play
                }),
                if (state.preparing || initialEmptyError) null else Icons.Rounded.PlayArrow,
                { when {
                    initialEmptyError -> onRetry()
                    state.preparing -> onCancelPreparation()
                    else -> onPlay(0, false)
                } },
                enabled = initialEmptyError || ready || state.preparing, retainFocus = true, primary = true, cornerRadius = if (classic) 8.dp else 21.dp,
                modifier = Modifier.automationId(if (initialEmptyError) "collection-retry" else "collection-play").then(if (classic) Modifier.weight(1f) else Modifier.width(132.dp)).height(42.dp)
                    .focusRequester(playFocus)
                    .onFocusChanged { if (it.isFocused) { savedSongFocus = -1; savedControlFocus = if (initialEmptyError) "retry" else "play"; pageTop.request() } }
                    .onPreviewKeyEvent { event ->
                        if (ready && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            moveToSong(songNavigation.requestedIndex); true
                        } else false
                    }.focusProperties {
                        left = navigationFocus
                        right = when {
                            ready -> shuffleFocus
                            initialEmptyError && openActions != null -> moreFocus
                            else -> FocusRequester.Cancel
                        }
                        down = FocusRequester.Cancel
                        up = navigationFocus
                    })
            if (showPlayControls) CollectionAction(stringResource(R.string.browse_shuffle), Icons.Rounded.Shuffle,
                { onPlay(0, true) }, enabled = ready, retainFocus = !initialEmptyError,
                iconOnly = !classic, cornerRadius = if (classic) 8.dp else 21.dp,
                modifier = Modifier.automationId("collection-shuffle").then(if (classic) Modifier.weight(1f).height(42.dp) else Modifier.size(42.dp)).focusRequester(shuffleFocus)
                    .onFocusChanged { if (it.isFocused) { savedSongFocus = -1; savedControlFocus = "shuffle"; pageTop.request() } }
                    .onPreviewKeyEvent { event ->
                        if (ready && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                                classic && openActions == null && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                            moveToSong(songNavigation.requestedIndex); true
                        } else false
                    }.focusProperties {
                        left = playFocus; up = navigationFocus
                        right = if (openActions != null) moreFocus else FocusRequester.Cancel; down = FocusRequester.Cancel
                    })
            if (openActions != null) CollectionAction(stringResource(R.string.catalog_more_actions), Icons.Rounded.MoreHoriz,
                {
                    savedSongFocus = -1; savedControlFocus = "more"; focusEntry?.requester = moreFocus
                    openActions(collection)
                }, iconOnly = true, cornerRadius = if (classic) 8.dp else 21.dp,
                modifier = Modifier.automationId("collection-more").size(42.dp).focusRequester(moreFocus)
                    .onFocusChanged { if (it.isFocused) { savedSongFocus = -1; savedControlFocus = "more"; pageTop.request() } }
                    .then(if (!showPlayControls) Modifier.focusRequester(firstFocus) else Modifier)
                    .onPreviewKeyEvent { event ->
                        if (classic && ready && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)) {
                            moveToSong(songNavigation.requestedIndex); true
                        } else false
                    }
                    .catalogMenuKey(collection).focusProperties {
                        left = when {
                            initialEmptyError -> playFocus
                            showPlayControls -> shuffleFocus
                            else -> navigationFocus
                        }
                        up = navigationFocus; right = FocusRequester.Cancel
                    })
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().automationId("collection-screen")
        .onPreviewKeyEvent { event ->
            // A delayed lazy placement must not route Center/Menu to the
            // previous row. Pointer actions still address their actual row.
            if (songNavigation.pending && event.nativeKeyEvent.keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MENU)) return@onPreviewKeyEvent true
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) cancelPendingFocus()
            false
        }) {
        val coverWidth = if (classic) minOf(270.dp, maxWidth * .38f)
            else minOf(maxWidth * 0.43f, (maxHeight - 24.dp).coerceAtLeast(0.dp))
        val coverRadius = if (classic) 10.dp else 12.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (classic) 36.dp else 32.dp)) {
            Column(Modifier.width(coverWidth).fillMaxHeight()
                .automationId("collection-profile-${collection.resourceType}-${collection.resourceId ?: collection.catalogId ?: collection.id}")) {
                Box(if (classic) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxSize(),
                    contentAlignment = if (classic) Alignment.Center else Alignment.CenterStart) {
                    MotionArtwork(collection,
                        (if (classic) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()).aspectRatio(1f)
                            .automationId("collection-artwork").coverHairline(coverRadius),
                        animate = rememberBrowseMotionReady(LocalFocusTargetsEnabled.current), cornerRadius = coverRadius)
                }
                if (classic) {
                    Spacer(Modifier.height(12.dp))
                    metadataContent()
                    actionsContent()
                }
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides pageReveal, LocalPageTopReveal provides pageTop) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight().automationId("collection-content").pageTopRevealViewport(pageTop)
                        .onFocusChanged { rightContentHasFocus = it.hasFocus }.focusGroup()
                        .collectionEdgeFade(listState)
                        .drawBehind {
                            if (!selectionVisible) return@drawBehind
                            val layout = listState.layoutInfo
                            val anchor = layout.visibleItemsInfo.firstOrNull { it.index in songHeaderCount until state.tracks.size + songHeaderCount }
                                ?: return@drawBehind
                            val top = collectionSongHighlightTop(songSelection.value, anchor.index, anchor.offset,
                                layout.viewportStartOffset, anchor.size.toFloat(), headerCount = songHeaderCount)
                            val inset = 10.dp.toPx()
                            drawRoundRect(Color.White.copy(alpha = .14f), topLeft = Offset(inset, top),
                                size = Size((size.width - inset * 2f).coerceAtLeast(0f), rowStep),
                                cornerRadius = CornerRadius((if (classic) 8.dp else 12.dp).toPx()))
                        },
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 28.dp),
                ) {
                    if (!classic) {
                        item(key = "collection-metadata", contentType = "metadata") { metadataContent() }
                        item(key = "collection-actions", contentType = "actions") { actionsContent() }
                    }
                    when {
                        state.loading -> item(key = "collection-loading", contentType = "status") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 45.dp).automationId("collection-loading"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                AppleLoadingIndicator(Modifier.size(26.dp))
                                Text(stringResource(R.string.browse_tracks_loading), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                            }
                        }
                        state.error != null && state.tracks.isEmpty() -> item(key = "collection-error", contentType = "status") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp).automationId("collection-error"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(stringResource(state.error), color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                            }
                        }
                        state.tracks.isEmpty() -> item(key = "collection-empty", contentType = "status") {
                            Text(stringResource(R.string.browse_tracks_empty), color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp).automationId("collection-empty"))
                        }
                        else -> itemsIndexed(state.tracks, key = { index, song -> "$index:${song.id}" }, contentType = { _, _ -> "song" }) { index, song ->
                            val selected = song.catalogId == currentTrack.catalogId && song.catalogId != null
                            if (savedSongFocus == index) SideEffect { focusEntry?.requester = songFocus[index] }
                            CollectionSong(song, index, selected, isPlaying,
                                showArtwork = !classic || collection.catalogKind == dev.amatv.model.CatalogKind.Playlist,
                                modifier = Modifier.automationId("collection-song-$index").focusRequester(songFocus[index])
                                    .onFocusChanged { if (it.isFocused) { songNavigation.focused(index); savedSongFocus = index; savedControlFocus = ""; wantsContinuationFocus = false; if (index == 0) pageTop.request() } }
                                    .onPreviewKeyEvent { event ->
                                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
                                        else when (event.nativeKeyEvent.keyCode) {
                                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                val target = songNavigation.nextIndex(index, 1, state.tracks.lastIndex)
                                                if (target == index && !songNavigation.pending) {
                                                    cancelPendingFocus()
                                                    if (state.next != null) runCatching { continuationFocus.requestFocus() }
                                                } else moveToSong(target)
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_UP -> {
                                                if (index == 0 && !songNavigation.pending) { cancelPendingFocus(); moveToActions() }
                                                else moveToSong(songNavigation.nextIndex(index, -1, state.tracks.lastIndex))
                                                true
                                            }
                                            else -> false
                                        }
                                    }.focusProperties {
                                        left = if (classic) {
                                            if (showPlayControls && !initialEmptyError) shuffleFocus else actionEntryFocus
                                        } else navigationFocus
                                        right = FocusRequester.Cancel
                                        if (index == state.tracks.lastIndex) down = if (state.next != null) continuationFocus else FocusRequester.Cancel
                                    }, onBeforeAction = { cancelPendingFocus() }, onClick = { onPlay(index, false) })
                        }
                    }
                    if (state.next != null || state.loadingMore || (state.error != null && state.tracks.isNotEmpty())) item(key = "collection-continuation") {
                        CollectionAction(stringResource(when {
                            state.preparing -> R.string.collection_preparing
                            state.loadingMore -> R.string.browse_tracks_loading
                            state.error != null -> R.string.browse_reload
                            else -> R.string.artist_more
                        }), null, {
                            continuationIndex = state.tracks.size; wantsContinuationFocus = true
                            if (state.error != null) onRetry() else onLoadMore()
                        }, enabled = !state.loadingMore && !state.preparing, retainFocus = true,
                            modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 6.dp)
                                .automationId("collection-load-more").focusRequester(continuationFocus)
                                .onFocusChanged { if (it.isFocused) {
                                    savedSongFocus = -1; savedControlFocus = "continuation"
                                    continuationIndex = state.tracks.size; wantsContinuationFocus = true
                                } }
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode in
                                        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) {
                                        wantsContinuationFocus = false; continuationIndex = -1
                                    }
                                    false
                                }.focusProperties { left = navigationFocus; down = FocusRequester.Cancel; right = FocusRequester.Cancel })
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionSong(song: Track, index: Int, selected: Boolean, isPlaying: Boolean, showArtwork: Boolean, modifier: Modifier,
    onBeforeAction: () -> Unit, onClick: () -> Unit) {
    val openActions = LocalCatalogActionMenu.current
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val resting = if (selected) Color.White.copy(alpha = 0.045f) else Color.Transparent
    FocusSurface({ onBeforeAction(); onClick() }, modifier.catalogMenuKey(song).fillMaxWidth().height(if (classic) 64.dp else 58.dp).pageTopRevealTarget(), cornerRadius = if (classic) 8.dp else 12.dp, focusedScale = 1f,
        background = resting, focusedBackground = resting, padding = PaddingValues(horizontal = 12.dp),
        onLongClick = openActions?.let { { onBeforeAction(); it(song) } }) { focused ->
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected && isPlaying) "♫" else stringResource(R.string.browse_track_index, index + 1),
                color = if (classic && selected) Color(0xFFFF788B) else Color.White.copy(alpha = if (selected) 0.94f else 0.4f), fontSize = if (classic) 13.sp else 12.sp, modifier = Modifier.width(if (classic) 30.dp else 22.dp))
            if (showArtwork) {
                MotionArtwork(song, Modifier.size(if (classic) 42.dp else 38.dp), animate = false, cornerRadius = 5.dp)
                Spacer(Modifier.width(if (classic) 12.dp else 10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song.title, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (classic && selected) Color(0xFFFF91A0) else Color.White.copy(alpha = if (focused || selected) 1f else 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, fontSize = 12.sp, color = Color.White.copy(alpha = if (focused) 0.72f else 0.48f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(songDuration(song.durationMs), fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f), modifier = Modifier.padding(start = 16.dp))
        }
    }
    HorizontalDivider(Modifier.padding(start = if (classic && !showArtwork) 42.dp else 82.dp, end = 12.dp).height(1.dp), color = Color.White.copy(alpha = if (classic) 0.07f else 0.045f))
}

/** Fade only this viewport; keep the fixed artwork and navigation fully opaque. */
private fun Modifier.collectionEdgeFade(state: androidx.compose.foundation.lazy.LazyListState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val edge = 20.dp.toPx()
        val top = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = edge)
        val bottom = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - edge, endY = size.height)
        onDrawWithContent {
            drawContent()
            if (state.canScrollBackward) drawRect(top, size = Size(size.width, edge), blendMode = BlendMode.DstIn)
            if (state.canScrollForward) drawRect(bottom, topLeft = Offset(0f, size.height - edge),
                size = Size(size.width, edge), blendMode = BlendMode.DstIn)
        }
    }

@Composable
internal fun CollectionAction(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    retainFocus: Boolean = false,
    primary: Boolean = false,
    iconOnly: Boolean = false,
    cornerRadius: Dp = 12.dp,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    FocusSurface(
        onClick = { if (enabled) onClick() }, enabled = enabled || retainFocus, modifier = modifier.pageTopRevealTarget(),
        cornerRadius = if (classic) 8.dp else cornerRadius, focusedScale = if (enabled) 1.025f else 1f,
        focusedLift = 0.dp, focusedElevation = 0.dp, showBorder = false,
        interactionFeedbackEnabled = enabled,
        background = Color.White.copy(alpha = when {
            !enabled -> 0.04f
            classic -> 0.12f
            primary -> 0.14f
            else -> 0.08f
        }),
        focusedBackground = Color.White.copy(alpha = when {
            !enabled -> 0.04f
            classic -> 0.24f
            primary -> 0.90f
            else -> 0.24f
        }),
        hoveredBackground = Color.White.copy(alpha = when {
            !enabled -> 0.04f
            primary -> 0.20f
            else -> 0.14f
        }),
    ) { _ ->
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            val color = if (!enabled) Color.White.copy(alpha = 0.35f) else focusSurfaceContentColor(
                resting = Color.White.copy(alpha = 0.95f),
                focused = if (primary && !classic) Color(0xFF171719) else Color.White.copy(alpha = 0.95f),
            )
            if (icon != null) {
                Icon(icon, contentDescription = if (iconOnly) label else null, tint = color, modifier = Modifier.size(18.dp))
                if (!iconOnly) Spacer(Modifier.width(5.dp))
            }
            if (!iconOnly) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun songDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val seconds = ms.coerceAtLeast(0L) / 1000
    return stringResource(R.string.browse_song_duration, seconds / 60, seconds % 60)
}

@Composable
private fun collectionDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) stringResource(R.string.browse_duration_hours, minutes / 60, minutes % 60)
    else stringResource(R.string.browse_duration_minutes, minutes)
}
