package dev.amatv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import dev.amatv.integration.ContentCache
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class ArtworkPrefetchViewport(val indices: List<Int>, val coverPx: Int)
private data class ArtworkPrefetchRequest(val url: String, val size: Int)

/** Shared across non-home screens, including multiple visible artist/resource shelves. */
private val artworkPrefetchPermits = Semaphore(2)

@Composable
internal fun rememberArtworkPrefetch(
    tracks: List<Track>,
    state: LazyListState,
    enabled: Boolean,
    coverSize: Dp? = null,
    itemIndexOffset: Int = 0,
    parentViewport: LazyListState? = null,
    parentItemKey: Any? = null,
) {
    val pixels = coverSize?.let { with(LocalDensity.current) { it.roundToPx() } }
    val viewport = remember(state, pixels, parentViewport, parentItemKey) { {
        // Read only the scroll booleans while moving: no per-pixel layout walks.
        if (state.isScrollInProgress || parentViewport?.isScrollInProgress == true ||
            !parentItemVisible(parentViewport, parentItemKey)) null
        else {
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo.filter {
                it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset
            }
            ArtworkPrefetchViewport(visible.map { it.index }, pixels ?: visible.firstOrNull()?.size ?: 0)
        }
    } }
    ArtworkPrefetch(tracks, state, enabled, itemIndexOffset, viewport)
}

@Composable
internal fun rememberArtworkPrefetch(
    tracks: List<Track>,
    state: LazyGridState,
    enabled: Boolean,
    coverSize: Dp? = null,
    itemIndexOffset: Int = 0,
) {
    val pixels = coverSize?.let { with(LocalDensity.current) { it.roundToPx() } }
    val viewport = remember(state, pixels) { {
        if (state.isScrollInProgress) null
        else {
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo.filter {
                it.offset.y < layout.viewportEndOffset && it.offset.y + it.size.height > layout.viewportStartOffset
            }
            ArtworkPrefetchViewport(visible.map { it.index }, pixels ?: visible.firstOrNull()?.size?.width ?: 0)
        }
    } }
    ArtworkPrefetch(tracks, state, enabled, itemIndexOffset, viewport)
}

private fun parentItemVisible(parent: LazyListState?, key: Any?): Boolean {
    if (parent == null || key == null) return true
    val layout = parent.layoutInfo
    return layout.visibleItemsInfo.any { it.key == key && it.offset < layout.viewportEndOffset &&
        it.offset + it.size > layout.viewportStartOffset }
}

@Composable
private fun ArtworkPrefetch(
    tracks: List<Track>,
    stateKey: Any,
    enabled: Boolean,
    itemIndexOffset: Int,
    viewport: () -> ArtworkPrefetchViewport?,
) {
    val context = LocalContext.current.applicationContext
    val cache = ContentCache.get(context)
    val settings by cache.settings.collectAsState()
    val active = enabled && LocalFocusTargetsEnabled.current && LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(tracks, stateKey, active, itemIndexOffset, viewport, settings) {
        if (!active || !settings.enabled || !settings.prefetchNextTwo || settings.clearing) return@LaunchedEffect
        snapshotFlow { viewport() }.collectLatest { visible ->
            if (visible == null || visible.coverPx <= 0) return@collectLatest
            delay(180)
            val requests = artworkPrefetchIndices(tracks.size, visible.indices, itemIndexOffset)
                .mapNotNull { index ->
                    val track = tracks[index]
                    val url = track.posterForAspect()
                        ?: return@mapNotNull null
                    ArtworkPrefetchRequest(url, visible.coverPx)
                }.distinctBy { it.url }
            coroutineScope {
                requests.forEach { request -> launch {
                    artworkPrefetchPermits.withPermit {
                        try { ArtworkImages.prefetchDecoded(context, request.url, request.size, request.size) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Optional preloading never fails navigation. */ }
                    }
                } }
            }
        }
    }
}
