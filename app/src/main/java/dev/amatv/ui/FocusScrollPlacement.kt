package dev.amatv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Uses the row's real scrolling policy, including its reachable start/end. */
@OptIn(ExperimentalFoundationApi::class)
internal class FocusScrollPlacement(
    private val state: LazyListState,
    private val spec: BringIntoViewSpec,
    private val viewport: FocusFrameGeometry,
    private val verticalScrollOffset: State<Float>? = null,
    private val direction: LayoutDirection = LayoutDirection.Ltr,
) {
    fun destination(bounds: Rect, focusedScale: Float): Rect {
        val viewportBounds = viewport.bounds ?: return bounds
        if (viewportBounds.width <= 0f) return bounds
        val visual = visualBounds(bounds, focusedScale)
        val requested = spec.calculateScrollDistance(
            visual.left - viewportBounds.left, visual.width, viewportBounds.width,
        )
        val layout = state.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull()
        val last = layout.visibleItemsInfo.lastOrNull()
        val distance = reachableFocusScroll(
            requested,
            first?.takeIf { it.index == 0 }?.offset,
            last?.takeIf { it.index == layout.totalItemsCount - 1 }?.let { it.offset + it.size },
            layout.viewportEndOffset,
            layout.afterContentPadding,
            isRtl = direction == LayoutDirection.Rtl,
        )
        return bounds.translate(-distance, -(verticalScrollOffset?.value ?: 0f))
    }
}

internal fun reachableFocusScroll(
    requested: Float,
    firstItemOffset: Int?,
    lastItemEnd: Int?,
    viewportEnd: Int,
    afterPadding: Int,
    isRtl: Boolean = false,
): Float {
    // Lazy offsets are measured from logical start; bounds and requested
    // distance are physical. Clamp in one coordinate system, then mirror back.
    val sign = if (isRtl) -1f else 1f
    val minimum = firstItemOffset?.coerceAtMost(0)?.toFloat() ?: Float.NEGATIVE_INFINITY
    val maximum = lastItemEnd?.let { (it + afterPadding - viewportEnd).coerceAtLeast(0).toFloat() }
        ?: Float.POSITIVE_INFINITY
    return (requested * sign).coerceIn(minimum, maximum) * sign
}

internal val LocalFocusScrollPlacement = staticCompositionLocalOf<FocusScrollPlacement?> { null }

/** Keep visible cards in place; TV's pivot policy otherwise drags a new row sideways on entry. */
internal fun minimumCardScroll(offset: Float, size: Float, containerSize: Float): Float = when {
    containerSize <= 0f -> 0f
    offset < 0f -> offset
    offset + size > containerSize -> minOf(offset, offset + size - containerSize)
    else -> 0f
}

internal class PageTopTargetGeometry {
    var bounds: Rect? = null
    var focused = false
}

internal val LocalPageTopReveal = staticCompositionLocalOf<PageTopReveal?> { null }

/** One owner reveals as much page header as the actual focused surface permits. */
@OptIn(ExperimentalFoundationApi::class)
internal class PageTopReveal(
    private val scope: CoroutineScope,
    private val target: () -> Int?,
    private val clearance: Float,
    private val atTop: () -> Boolean,
    private val scrollToTop: suspend () -> Unit,
    private val scrollBy: suspend (Float) -> Unit,
) {
    var viewport: Rect? = null
        set(value) {
            val firstPlacement = field == null
            field = value
            if (firstPlacement && value != null && focusedTarget?.focused == true) request()
        }
    private var focusedTarget: PageTopTargetGeometry? = null
    private var job: Job? = null
    fun focused(geometry: PageTopTargetGeometry, focused: Boolean) {
        geometry.focused = focused
        if (focused) { focusedTarget = geometry; request() }
        else if (focusedTarget === geometry) { focusedTarget = null; cancel() }
    }
    fun request() {
        job?.cancel()
        job = scope.launch {
            // Let parent hasFocus and the actual target placement publish first.
            withFrameNanos { }
            target() ?: return@launch
            val geometry = focusedTarget?.takeIf { it.focused } ?: return@launch
            val bounds = geometry.bounds ?: return@launch
            val window = viewport ?: return@launch
            if (bounds.height > window.height - clearance * 2f) {
                // A long reading block cannot fit as one focus rectangle. Show
                // the title and its beginning; its own Up/Down paging stays free.
                if (!atTop()) scrollToTop()
            } else {
                // Negative distance pulls back toward the page start. LazyList
                // clamps at zero, so a short header returns fully to the top;
                // a tall header stops only where the real focused control fits.
                val distance = bounds.bottom - window.bottom + clearance
                if (abs(distance) > .5f && (!atTop() || distance > 0f)) scrollBy(distance)
            }
        }
    }
    val isRevealing: Boolean get() = job?.isActive == true
    fun cancel() { job?.cancel(); job = null }
    fun spec(fallback: BringIntoViewSpec): BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            // Yield only while this explicit reveal owns the scroll. Later
            // header expansion can move the same focused control offscreen
            // without another focus event, so its native reveal must work again.
            if (job?.isActive == true && target() != null && focusedTarget?.focused == true && viewport != null) 0f
            else fallback.calculateScrollDistance(offset, size, containerSize)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPageTopReveal(
    state: LazyListState,
    edgeClearance: Dp = 12.dp,
    target: () -> Int?,
): PageTopReveal {
    val scope = rememberCoroutineScope()
    val latestTarget = rememberUpdatedState(target)
    val clearance = with(LocalDensity.current) { edgeClearance.toPx() }
    val reveal = remember(state, scope, clearance) { PageTopReveal(scope, { latestTarget.value() }, clearance,
        { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 },
        { state.animateScrollToItem(0) }, { state.animateScrollBy(it) }) }
    LaunchedEffect(reveal) {
        snapshotFlow { latestTarget.value() }.collect { if (it == null) reveal.cancel() else reveal.request() }
    }
    DisposableEffect(reveal) { onDispose { reveal.cancel() } }
    return reveal
}

internal fun Modifier.pageTopRevealViewport(reveal: PageTopReveal): Modifier =
    onGloballyPositioned { reveal.viewport = it.focusFrameBounds() }

/** Report the focus surface, excluding the shelf heading, labels and other rows. */
@Composable
internal fun Modifier.pageTopRevealTarget(): Modifier {
    val reveal = LocalPageTopReveal.current ?: return this
    val geometry = remember(reveal) { PageTopTargetGeometry() }
    DisposableEffect(reveal, geometry) { onDispose { reveal.focused(geometry, false) } }
    return onGloballyPositioned {
        val firstPlacement = geometry.bounds == null
        geometry.bounds = it.focusFrameBounds()
        if (firstPlacement && geometry.focused) reveal.request()
    }
        .onFocusChanged { reveal.focused(geometry, it.isFocused) }
}

@OptIn(ExperimentalFoundationApi::class)
private class VisibleCardBringIntoView(
    private val state: LazyListState,
    private val visualOutset: Float,
    private val direction: LayoutDirection,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (visualOutset <= 0f) return minimumCardScroll(offset, size, containerSize)
        val layout = state.layoutInfo
        val center = offset + size / 2f
        fun itemLeft(item: androidx.compose.foundation.lazy.LazyListItemInfo): Float {
            val fromStart = (item.offset - layout.viewportStartOffset).toFloat()
            return if (direction == LayoutDirection.Rtl) containerSize - fromStart - item.size else fromStart
        }
        // The focus node sits inside an animated graphicsLayer. Anchor the
        // reveal to stable lazy layout bounds and the final drawing footprint;
        // otherwise scaling changes the scroll target while it is being chased.
        val item = layout.visibleItemsInfo.minByOrNull {
            abs(itemLeft(it) + it.size / 2f - center)
        }?.takeIf {
            abs(itemLeft(it) + it.size / 2f - center) <= it.size / 4f
        }
        val left = item?.let(::itemLeft) ?: offset
        val width = item?.size?.toFloat() ?: size
        return minimumCardScroll(left - visualOutset, width + visualOutset * 2f, containerSize)
    }
}

/** Shares the same viewport and scrolling policy with the overlay's forecast. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FocusTrackingRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    verticalScrollOffset: State<Float>? = null,
    focusVisualOutset: Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    // Lazy placement rounds to physical pixels. Leave one additional pixel so
    // a settled footprint cannot remain half a pixel outside the viewport.
    val outsetPx = with(density) { if (focusVisualOutset > 0.dp) focusVisualOutset.toPx() + 1f else 0f }
    val safeOutset = with(density) { outsetPx.toDp() }
    val direction = LocalLayoutDirection.current
    val spec = remember(state, outsetPx, direction) { VisibleCardBringIntoView(state, outsetPx, direction) }
    // The first/last items need the same drawing clearance as a middle item.
    // Keep viewport clipping: only reserve space inside its reachable endpoints.
    val safePadding = PaddingValues(
        start = maxOf(contentPadding.calculateStartPadding(direction), safeOutset),
        end = maxOf(contentPadding.calculateEndPadding(direction), safeOutset),
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )
    val viewport = remember { FocusFrameGeometry() }
    val frameEnabled = LocalFocusFrameEnabled.current
    val placement = remember(state, spec, verticalScrollOffset, direction) {
        FocusScrollPlacement(state, spec, viewport, verticalScrollOffset, direction)
    }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides spec,
        LocalFocusScrollPlacement provides placement,
    ) {
        LazyRow(
            state = state,
            // Browse draws focus on the actual card, not the old global frame.
            // Tracking every row's global position during vertical scroll would
            // compute viewport bounds for an overlay that is disabled there.
            modifier = if (frameEnabled) modifier.onGloballyPositioned { viewport.bounds = it.focusFrameBounds() } else modifier,
            contentPadding = safePadding,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}
