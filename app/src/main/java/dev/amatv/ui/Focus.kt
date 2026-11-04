package dev.amatv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first

/** Keep entry on an actual card/control, rather than a lazy container. */
internal class ContentFocusEntry {
    var requester: FocusRequester? = null
    // Read synchronously at the start of an Up press, before a child advances
    // its requested cursor or cancels its still-running reveal.
    var navigationUpReady: (() -> Boolean)? = null
    // A native group may temporarily focus a neighbouring lazy item while its
    // activated source is being reattached. That incidental report must not
    // replace a still-unfulfilled return destination.
    var preferredReturnRequester by mutableStateOf<FocusRequester?>(null)

    fun requestFocus(fallback: FocusRequester): Boolean {
        preferredReturnRequester?.let { return runCatching { it.requestFocus() }.getOrDefault(false) }
        if (requester?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return true
        return runCatching { fallback.requestFocus() }.getOrDefault(false)
    }
}

/** A held Up belongs to content even after it reaches the page's first row. */
internal class BrowseUpBoundary {
    private var upPressed = false
    private var allowMenu = true

    fun upDown(repeatCount: Int, readyAtPressOrigin: Boolean) {
        if (repeatCount == 0) {
            upPressed = true
            allowMenu = readyAtPressOrigin
        } else {
            upPressed = true
            allowMenu = false
        }
    }
    fun release() { upPressed = false; allowMenu = true }
    fun allowsMenuFocus(): Boolean = !upPressed || allowMenu
}

/** Publish placement readiness without observing the viewport in composition. */
@Composable
internal fun ContentUpExitReadiness(entry: ContentFocusEntry?, ready: () -> Boolean) {
    val latestReady = rememberUpdatedState(ready)
    DisposableEffect(entry) {
        val callback = { latestReady.value() }
        entry?.navigationUpReady = callback
        onDispose { if (entry?.navigationUpReady === callback) entry.navigationUpReady = null }
    }
}

internal val LocalContentFocusEntry = staticCompositionLocalOf<ContentFocusEntry?> { null }
/** A retained screen must remove its actual targets from remote navigation. */
internal val LocalFocusTargetsEnabled = staticCompositionLocalOf { true }

internal enum class FocusMotionStyle { Spring, Television, Immediate }
internal val LocalFocusMotionStyle = staticCompositionLocalOf { FocusMotionStyle.Spring }
/** SelectableWidget default; GlassPlayer/sidebar/menu provide their own overrides. */
internal data class TelevisionFocusTimings(val enterMillis: Int = 85, val exitMillis: Int = 230)
internal val LocalTelevisionFocusTimings = staticCompositionLocalOf { TelevisionFocusTimings() }
private val TvFocusEnterEasing = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val TvFocusExitEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val LocalFocusSurfaceProgress = staticCompositionLocalOf<State<Float>?> { null }

/** Match foreground and fill throughout the same transition, including focus loss. */
@Composable
internal fun focusSurfaceContentColor(resting: Color, focused: Color): Color {
    if (resting == focused) return resting
    val progress = LocalFocusSurfaceProgress.current ?: return resting
    return lerp(resting, focused, progress.value.coerceIn(0f, 1f))
}

@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 18.dp,
    focusedScale: Float = 1.025f,
    focusedLift: Dp = 0.dp,
    restingElevation: Dp = 0.dp,
    focusedElevation: Dp = 0.dp,
    showBorder: Boolean = false,
    padding: PaddingValues = PaddingValues(0.dp),
    background: Color = Color.White.copy(alpha = 0.08f),
    focusedBackground: Color = Color.White.copy(alpha = 0.16f),
    hoveredBackground: Color = background,
    interactionFeedbackEnabled: Boolean = true,
    deferIdleVisuals: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    // Home opts in to stable coordinators and placement-only focus draw order.
    stableGeometry: Boolean = false,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interactive = enabled && LocalFocusTargetsEnabled.current
    val feedbackEnabled = interactive && interactionFeedbackEnabled
    val motionStyle = LocalFocusMotionStyle.current
    val focusTimings = LocalTelevisionFocusTimings.current
    val menuAnchorReporter = LocalCatalogMenuAnchorReporter.current
    var focused by remember { mutableStateOf(false) }
    val interaction = remember(deferIdleVisuals, motionStyle) {
        when {
            deferIdleVisuals -> DeferredFocusInteractionSource()
            motionStyle != FocusMotionStyle.Spring -> TvPressInteractionSource()
            else -> MutableInteractionSource()
        }
    }
    val deferredInteraction = interaction as? DeferredFocusInteractionSource
    val hoverInteraction = if (deferredInteraction != null) deferredInteraction.hovered
        else interaction.collectIsHoveredAsState().value
    val pressInteraction = if (deferredInteraction != null) deferredInteraction.pressed
        else interaction.collectIsPressedAsState().value
    val inputFocused = focused && interactive
    val hovered = hoverInteraction && feedbackEnabled
    val pressed = pressInteraction && feedbackEnabled
    val visualsStarted = deferredInteraction == null || deferredInteraction.activated
    if (deferredInteraction != null) {
        DisposableEffect(deferredInteraction) {
            onDispose {
                focused = false
                deferredInteraction.reset()
            }
        }
    }
    val active = inputFocused || hovered
    if (!interactive) {
        LaunchedEffect(Unit) {
            // Keep the native focus observation intact. Disabling a retained
            // page hides feedback through inputFocused; it is not a focus event.
            // Clearing this flag could leave the returning native target focused
            // with no corresponding highlight until the next directional move.
            // Keep activation and the last content requester for menu return.
            deferredInteraction?.cancelPointerInteractions()
        }
    }
    val density = LocalDensity.current
    val frameController = LocalFocusFrameController.current
    val frameEnabled = LocalFocusFrameEnabled.current
    val frameVisibility = LocalFocusFrameVisibility.current
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val localFrameFallback = !frameEnabled || frameController == null
    val scrollPlacement = LocalFocusScrollPlacement.current
    val frameKey = remember { Any() }
    val contentEntry = LocalContentFocusEntry.current
    val contentRequester = remember { FocusRequester() }
    val frameGeometry = remember { FocusFrameGeometry() }
    val placedCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    // Idle Home cards need the native focus/click target, but no animation
    // coroutines or disposable overlay registrations before first interaction.
    if (visualsStarted) {
        DisposableEffect(frameController, frameKey) {
            onDispose { frameController?.clear(frameKey) }
        }
        DisposableEffect(contentEntry, contentRequester) {
            onDispose { if (contentEntry?.requester === contentRequester) contentEntry.requester = null }
        }
    }
    val targetScale = if (motionStyle == FocusMotionStyle.Spring) {
        // Keep the existing spring/press motion for legacy player settings.
        when {
            pressed && active -> focusedScale * 0.972f
            pressed -> 0.985f
            active -> focusedScale
            else -> 1f
        }
    } else if (inputFocused) focusedScale else 1f
    val scaleSpec: FiniteAnimationSpec<Float> = when (motionStyle) {
        FocusMotionStyle.Immediate -> snap()
        FocusMotionStyle.Television -> tween(
            durationMillis = if (inputFocused) focusTimings.enterMillis else focusTimings.exitMillis,
            easing = if (inputFocused) TvFocusEnterEasing else TvFocusExitEasing,
        )
        FocusMotionStyle.Spring -> spring(dampingRatio = 0.82f, stiffness = 430f)
    }
    val progressSpec: FiniteAnimationSpec<Float> = when (motionStyle) {
        FocusMotionStyle.Spring -> spring(dampingRatio = 0.86f, stiffness = 380f)
        else -> scaleSpec
    }
    val focusScale: State<Float>
    val focusProgress: State<Float>
    if (deferredInteraction != null) {
        if (visualsStarted) {
            // A newly focused card must spring from rest, never initialize at
            // its first active target. The epoch also resets reused lazy slots.
            val scaleAnimation = remember(deferredInteraction.activationEpoch) { Animatable(1f) }
            val progressAnimation = remember(deferredInteraction.activationEpoch) { Animatable(0f) }
            LaunchedEffect(scaleAnimation, targetScale, motionStyle) {
                scaleAnimation.animateTo(targetScale, scaleSpec)
            }
            LaunchedEffect(progressAnimation, inputFocused, motionStyle) {
                progressAnimation.animateTo(if (inputFocused) 1f else 0f, progressSpec)
            }
            focusScale = scaleAnimation.asState()
            focusProgress = progressAnimation.asState()
        } else {
            focusScale = RestingFocusScale
            focusProgress = RestingFocusProgress
        }
    } else {
        focusScale = animateFloatAsState(
            targetValue = targetScale,
            animationSpec = scaleSpec,
            label = "focus-scale",
        )
        focusProgress = animateFloatAsState(
            targetValue = if (inputFocused) 1f else 0f,
            animationSpec = progressSpec,
            label = "focus-progress",
        )
    }
    val pressEpoch = if (motionStyle == FocusMotionStyle.Television) {
        (interaction as? TvPressInteractionSource)?.pressEpoch ?: 0L
    } else 0L
    val playedPressEpoch = remember(interaction) { longArrayOf(pressEpoch) }
    val latestPressed by rememberUpdatedState(pressed)
    val pressScale = if (motionStyle == FocusMotionStyle.Television && visualsStarted) {
        val animation = remember(interaction) { Animatable(1f) }
        LaunchedEffect(animation, pressEpoch, feedbackEnabled) {
            if (!feedbackEnabled) {
                playedPressEpoch[0] = pressEpoch
                animation.snapTo(1f)
            } else if (pressEpoch != playedPressEpoch[0]) {
                playedPressEpoch[0] = pressEpoch
                animation.animateTo(if (inputFocused) 0.972f else 0.985f, tween(80, easing = TvFocusExitEasing))
                snapshotFlow { latestPressed }.first { !it }
                animation.animateTo(1f, tween(80, easing = TvFocusEnterEasing))
            }
        }
        animation.asState()
    } else RestingFocusScale
    val scale = if (motionStyle == FocusMotionStyle.Spring) focusScale else remember(focusScale, pressScale) {
        derivedStateOf { focusScale.value * pressScale.value }
    }
    val hoverProgress = if (visualsStarted && hoveredBackground != background) {
        animateFloatAsState(
            targetValue = if (hovered) 1f else 0f,
            animationSpec = if (motionStyle == FocusMotionStyle.Immediate) snap() else tween(120),
            label = "focus-hover-material",
        )
    } else RestingFocusProgress
    val shape = RoundedCornerShape(cornerRadius)
    val restingElevationPx = with(density) { restingElevation.toPx() }
    val focusedElevationPx = with(density) { focusedElevation.toPx() }
    val liftPx = with(density) { focusedLift.toPx() }
    val frameTransform = remember(scale, focusProgress, liftPx, focusedScale) {
        FocusFrameTransform(scale, focusProgress, liftPx, focusedScale)
    }
    if (visualsStarted) SideEffect {
        if (inputFocused) contentEntry?.requester = contentRequester
        if (inputFocused && frameEnabled) frameGeometry.bounds?.let { bounds ->
            frameController?.report(
                frameKey,
                bounds, cornerRadiusPx, frameVisibility, frameTransform, scrollPlacement,
            )
        } else frameController?.clear(frameKey)
    }

    fun reportMenuAnchor() {
        val coordinates = placedCoordinates[0]
        val currentBounds = if (coordinates?.isAttached == true) coordinates.focusFrameBounds() else frameGeometry.bounds
        currentBounds?.let { bounds ->
            menuAnchorReporter?.invoke(visualBounds(bounds, scale.value, liftPx * focusProgress.value))
        }
    }
    fun rememberActivationSource() {
        // A pointer/accessibility click may activate a card that never received
        // DPAD focus. Save that actual source before a screen/dialog replaces it.
        if (interactive && contentEntry != null) {
            contentEntry.requester = contentRequester
            runCatching { contentRequester.requestFocus() }
        }
        reportMenuAnchor()
    }
    val click = { rememberActivationSource(); onClick() }
    val longClick = onLongClick?.let { action -> { rememberActivationSource(); action() } }

    Box(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && event.nativeKeyEvent.action == KeyEvent.ACTION_UP) reportMenuAnchor()
            false
        }.then(modifier)
            // Focused cards draw above their siblings, so their enlarged edge
            // and shadow are not cut by an adjacent card.
            .then(if (stableGeometry) Modifier.placementOnlyZIndex(if (active) 1f else 0f)
                else Modifier.zIndex(if (active) 1f else 0f))
            // Measure the layout box before the animated layer. Its visual scale
            // must never feed back into the overlay's spring target.
            .then(if (frameEnabled) Modifier.onGloballyPositioned { coordinates ->
                placedCoordinates[0] = coordinates
                val bounds = coordinates.focusFrameBounds()
                frameGeometry.bounds = bounds
                if (inputFocused) frameController?.report(
                    frameKey, bounds, cornerRadiusPx, frameVisibility, frameTransform, scrollPlacement,
                )
            } else Modifier.onPlaced { coordinates ->
                // Coordinates are a live coordinator, not a position snapshot.
                // Keep its reference without registering another global-position
                // callback or reading ancestor positions during placement. Menu
                // activation samples the current attached bounds on demand.
                placedCoordinates[0] = coordinates
            })
            .then(if (stableGeometry || visualsStarted || restingElevationPx != 0f) Modifier.graphicsLayer {
                // Keeping the node installed avoids a coordinator insertion on
                // first deferred focus; animation jobs still wait for activation.
                if (stableGeometry) {
                    alpha = 1f
                    compositingStrategy = CompositingStrategy.Auto
                }
                scaleX = scale.value
                scaleY = scale.value
                translationY = -liftPx * focusProgress.value
                shadowElevation = (
                    restingElevationPx +
                        (focusedElevationPx - restingElevationPx) * focusProgress.value
                    ) * if (pressed) 0.68f else 1f
                this.shape = shape
                clip = false
                ambientShadowColor = Color.Black.copy(alpha = 0.58f)
                spotShadowColor = Color.Black.copy(alpha = 0.72f)
            } else Modifier)
            .then(if (!deferIdleVisuals || background.alpha > 0f || focusedBackground.alpha > 0f || hoveredBackground.alpha > 0f || showBorder) Modifier.drawBehind {
                val radius = cornerRadius.toPx()
                val focus = focusProgress.value.coerceIn(0f, 1f)
                val resting = lerp(background, hoveredBackground, hoverProgress.value)
                drawRoundRect(
                    color = lerp(resting, focusedBackground, focus),
                    cornerRadius = CornerRadius(radius, radius),
                )
                if (showBorder) {
                    // A dialog's unified outer frame owns Classic focus;
                    // retain only the idle decorative stroke beneath it.
                    val borderFocus = if (classic && localFrameFallback) 0f else focus
                    drawRoundRect(
                        color = lerp(
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.92f),
                            borderFocus,
                        ),
                        cornerRadius = CornerRadius(radius, radius),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = (1.dp.toPx() * (1f + borderFocus)),
                        ),
                    )
                }
            } else Modifier)
            .then(if (classic) Modifier.localFocusFrame(cornerRadius) {
                if (!interactive) 0f else {
                    // Dialog controls cannot draw into the Activity overlay.
                    // Keep keyboard focus there equally visible, while normal
                    // browse focus continues to use only its shared overlay.
                    val focusOpacity = if (localFrameFallback) focusProgress.value else 0f
                    val hoverOpacity = if (hovered && !inputFocused) 0.45f else 0f
                    maxOf(focusOpacity, hoverOpacity) * (frameVisibility?.value ?: 1f)
                }
            } else Modifier)
            .focusRequester(contentRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && interactive) {
                    reportMenuAnchor()
                    deferredInteraction?.activate()
                    contentEntry?.requester = contentRequester
                }
                if (it.isFocused && interactive && frameEnabled) {
                    frameGeometry.bounds?.let { bounds ->
                        frameController?.report(
                            frameKey, bounds, cornerRadiusPx, frameVisibility, frameTransform, scrollPlacement,
                        )
                    }
                } else {
                    frameController?.clear(frameKey)
                }
            }
            .hoverable(interactionSource = interaction, enabled = feedbackEnabled)
            .then(if (onLongClick == null) Modifier.clickable(
                enabled = interactive, interactionSource = interaction, indication = null, onClick = click,
            ) else Modifier.combinedClickable(
                enabled = interactive, interactionSource = interaction, indication = null,
                onClick = click, onLongClick = longClick, onLongClickLabel = onLongClickLabel,
                hapticFeedbackEnabled = false,
            ))
            .then(if (deferIdleVisuals && padding == ZeroFocusPadding) Modifier else Modifier.padding(padding)),
        content = {
            CompositionLocalProvider(LocalFocusSurfaceProgress provides focusProgress) {
                content(inputFocused)
            }
        },
    )
}


private val RestingFocusScale: State<Float> = object : State<Float> { override val value = 1f }
private val RestingFocusProgress: State<Float> = object : State<Float> { override val value = 0f }
private val ZeroFocusPadding = PaddingValues(0.dp)

/** Records paired input directly, avoiding a collector coroutine for every idle Home card. */
internal class DeferredFocusInteractionSource(
    delegate: MutableInteractionSource = MutableInteractionSource(),
) : TvPressInteractionSource(delegate) {
    var activated by mutableStateOf(false)
        private set
    var hovered by mutableStateOf(false)
        private set
    var pressed by mutableStateOf(false)
        private set
    var activationEpoch = 0L
        private set
    private var hovers: MutableSet<HoverInteraction.Enter>? = null
    private var presses: MutableSet<PressInteraction.Press>? = null

    fun activate() {
        if (!activated) {
            activationEpoch++
            activated = true
        }
    }

    override fun record(interaction: Interaction) {
        super.record(interaction)
        when (interaction) {
            is HoverInteraction.Enter -> {
                activate()
                (hovers ?: mutableSetOf<HoverInteraction.Enter>().also { hovers = it }).add(interaction)
                hovered = true
            }
            is HoverInteraction.Exit -> {
                hovers?.remove(interaction.enter)
                hovered = hovers?.isNotEmpty() == true
            }
            is PressInteraction.Press -> {
                activate()
                (presses ?: mutableSetOf<PressInteraction.Press>().also { presses = it }).add(interaction)
                pressed = true
            }
            is PressInteraction.Release -> {
                presses?.remove(interaction.press)
                pressed = presses?.isNotEmpty() == true
            }
            is PressInteraction.Cancel -> {
                presses?.remove(interaction.press)
                pressed = presses?.isNotEmpty() == true
            }
        }
    }

    fun cancelPointerInteractions() {
        hovers?.clear()
        presses?.clear()
        hovered = false
        pressed = false
    }

    fun reset() {
        cancelPointerInteractions()
        activated = false
    }
}
