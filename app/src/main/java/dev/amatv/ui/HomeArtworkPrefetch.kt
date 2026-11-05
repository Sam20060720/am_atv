package dev.amatv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.amatv.model.Track
import dev.amatv.integration.ContentCache
import dev.amatv.model.ContentShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil

internal data class HomeArtworkViewportRow(val shelf: Int, val visibleItems: List<Int>)
internal data class HomeArtworkPosition(val shelf: Int, val item: Int)

/** Visible covers win; speculative work stays local and never walks the whole feed. */
internal fun homeArtworkPrefetchWindow(
    itemCounts: List<Int>,
    visibleRows: List<HomeArtworkViewportRow>,
    nextRowFirstItem: Int,
    nextRowVisibleCount: Int,
    limit: Int = 18,
): List<HomeArtworkPosition> {
    if (limit <= 0) return emptyList()
    val rows = visibleRows.filter { it.shelf in itemCounts.indices && it.visibleItems.isNotEmpty() }
    if (rows.isEmpty()) return emptyList()
    val positions = LinkedHashSet<HomeArtworkPosition>()
    fun add(shelf: Int, item: Int) {
        if (positions.size < limit && item in 0 until itemCounts.getOrElse(shelf) { 0 }) {
            positions += HomeArtworkPosition(shelf, item)
        }
    }
    rows.forEach { row -> row.visibleItems.forEach { add(row.shelf, it) } }
    // Include a small start of the incoming vertical row before spending the
    // remaining allowance on lateral neighbors of every currently visible row.
    val nextShelf = rows.maxOf { it.shelf } + 1
    val nextStart = nextRowFirstItem.coerceAtLeast(0)
    repeat(nextRowVisibleCount.coerceIn(0, 2)) { add(nextShelf, nextStart + it) }
    for (distance in 1..2) {
        rows.forEach { row ->
            add(row.shelf, row.visibleItems.min() - distance)
            add(row.shelf, row.visibleItems.max() + distance)
        }
    }
    for (offset in 2 until nextRowVisibleCount.coerceIn(0, limit)) add(nextShelf, nextStart + offset)
    return positions.toList()
}

internal data class HomeArtworkPrefetchGeometry(val url: String, val width: Dp, val height: Dp)

internal fun homeArtworkPrefetchGeometry(track: Track, featured: Boolean, layout: HomeShelfLayout): HomeArtworkPrefetchGeometry? {
    val width = if (featured) layout.showcaseWidth else layout.cardWidth
    val portraitAspect = layout.showcaseWidth.value / layout.showcaseHeight.value
    val artwork = track.forHomePlaylistArtwork()
    val portrait = !layout.classic && featured && track.catalogKind != dev.amatv.model.CatalogKind.Playlist && artwork.motionForAspect(
        portraitAspect,
    )?.aspectRatio?.let { it < 0.9f } == true
    val url = artwork.posterForAspect(if (portrait) portraitAspect else 1f) ?: return null
    val height = when {
        layout.classic && featured -> 184.dp
        portrait -> layout.showcaseHeight
        else -> width
    }
    return HomeArtworkPrefetchGeometry(url, width, height)
}

private data class HomeArtworkPrefetchRequest(val url: String, val width: Int, val height: Int)

/**
 * This observes indices, not scroll pixels. Any new scroll cancels pending work;
 * the shared loader continues to bound actual decoding on its background worker.
 */
@Composable
internal fun rememberHomeArtworkPrefetch(
    shelves: List<ContentShelf>,
    columnState: LazyListState,
    rowStates: List<LazyListState>,
    layout: HomeShelfLayout,
    enabled: Boolean,
) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val cache = ContentCache.get(context)
    val settings by cache.settings.collectAsState()
    val active = enabled && LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(shelves, columnState, rowStates, layout, density, active, settings) {
        if (!active || !settings.enabled || !settings.prefetchNextTwo || settings.clearing) return@LaunchedEffect
        val permits = Semaphore(2)
        snapshotFlow {
            if (columnState.isScrollInProgress || rowStates.any { it.isScrollInProgress }) {
                emptyList()
            } else {
                val column = columnState.layoutInfo
                val visible = column.visibleItemsInfo.filter {
                    it.offset < column.viewportEndOffset && it.offset + it.size > column.viewportStartOffset
                }.mapNotNull { row ->
                    val rowLayout = rowStates.getOrNull(row.index)?.layoutInfo ?: return@mapNotNull null
                    HomeArtworkViewportRow(row.index, rowLayout.visibleItemsInfo.filter {
                        it.offset < rowLayout.viewportEndOffset && it.offset + it.size > rowLayout.viewportStartOffset
                    }.map { it.index })
                }
                val nextShelf = visible.maxOfOrNull { it.shelf }?.plus(1)
                val nextFeatured = shelves.getOrNull(nextShelf ?: -1)?.featured == true
                val nextCardWidth = if (nextFeatured) layout.showcaseWidth else layout.cardWidth
                val nextVisibleCount = with(density) {
                    ceil(column.viewportSize.width / (nextCardWidth + layout.columnGap(nextFeatured)).toPx()).toInt()
                }
                val positions = homeArtworkPrefetchWindow(
                    itemCounts = shelves.map { if (it.id == BrowseInlineSection.Genres.shelfId) 0 else it.items.size },
                    visibleRows = visible,
                    nextRowFirstItem = rowStates.getOrNull(nextShelf ?: -1)?.firstVisibleItemIndex ?: 0,
                    nextRowVisibleCount = nextVisibleCount,
                )
                positions.mapNotNull { position ->
                    val shelf = shelves[position.shelf]
                    val track = shelf.items[position.item]
                    val geometry = homeArtworkPrefetchGeometry(track, shelf.featured, layout)
                        ?: return@mapNotNull null
                    with(density) { HomeArtworkPrefetchRequest(geometry.url,
                        geometry.width.roundToPx(), geometry.height.roundToPx()) }
                }.distinctBy { it.url }
            }
        }.collectLatest { requests ->
            if (requests.isEmpty()) return@collectLatest
            delay(180)
            coroutineScope {
                requests.forEach { request -> launch {
                    permits.withPermit {
                        try {
                            ArtworkImages.prefetchDecoded(context, request.url, request.width, request.height)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A speculative cover failure must never affect the feed.
                        }
                    }
                } }
            }
        }
    }
}
