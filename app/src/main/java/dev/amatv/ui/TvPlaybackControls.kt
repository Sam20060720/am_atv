package dev.amatv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.min

/** Normalize only the control overlay. The artwork and lyric viewport retain their density. */
@Composable
internal fun TvPlaybackViewport(lowPerformanceMode: Boolean = false, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(
            LocalDensity provides Density(constraints.maxWidth / 960f, LocalDensity.current.fontScale),
            LocalFocusFrameEnabled provides false,
            LocalFocusMotionStyle provides if (lowPerformanceMode) FocusMotionStyle.Immediate else FocusMotionStyle.Television,
            LocalTelevisionFocusTimings provides TelevisionFocusTimings(75, 150),
        ) { TvTypography { content() } }
    }
}

/** GlassPlayer RegularEmphasized.L: 66px frame, 8px focus growth, 75/150ms. */
@Composable
internal fun TvPlaybackButton(
    controlId: String,
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean,
    iconSize: Dp = 20.dp,
    pressed: Boolean = false,
    pressEpoch: Long = 0L,
    onClick: () -> Unit,
    onBounds: ((Rect) -> Unit)? = null,
) {
    val interactive = enabled && LocalFocusTargetsEnabled.current
    val immediate = LocalFocusMotionStyle.current == FocusMotionStyle.Immediate
    val interaction = remember { TvPressInteractionSource() }
    val pointerPressed by interaction.collectIsPressedAsState()
    val pointerHovered by interaction.collectIsHoveredAsState()
    val inputFocused = focused && interactive
    val focus = animateFloatAsState(
        targetValue = if (inputFocused) 1f else 0f,
        animationSpec = if (immediate) snap() else tween(if (inputFocused) 75 else 150),
        label = "tv-playback-button",
    )
    val hover = animateFloatAsState(
        targetValue = if (pointerHovered && interactive) 1f else 0f,
        animationSpec = if (immediate) snap() else tween(120), label = "tv-playback-hover",
    )
    val pressScale = remember { Animatable(1f) }
    val pointerEpoch = interaction.pressEpoch
    val latestPressed by rememberUpdatedState((pressed || pointerPressed) && interactive)
    val playedEpoch = remember { longArrayOf(pressEpoch, pointerEpoch) }
    LaunchedEffect(pressEpoch, pointerEpoch, interactive, immediate) {
        val hasPress = (pressEpoch > 0L && playedEpoch[0] != pressEpoch) ||
            (pointerEpoch > 0L && playedEpoch[1] != pointerEpoch)
        playedEpoch[0] = pressEpoch
        playedEpoch[1] = pointerEpoch
        if (!interactive || immediate) pressScale.snapTo(1f)
        else if (hasPress) {
            pressScale.animateTo(0.972f, tween(80))
            snapshotFlow { latestPressed }.first { !it }
            pressScale.animateTo(1f, tween(80))
        } else pressScale.animateTo(1f, tween(80))
    }
    val shape = RoundedCornerShape(16.5.dp)
    val idleFill = when {
        !enabled -> Color(0xFF1E1E1E).copy(alpha = 0.16f)
        selected -> Color.White.copy(alpha = 0.12f)
        // GlassPlayer enabled.Idle.background, app.js 2311548:
        // colorFromRGB(30,30,30,.9), independent of foreground/material alpha.
        else -> Color(0xFF1E1E1E).copy(alpha = 0.90f)
    }
    Box(
        Modifier.automationId(controlId)
            .semantics {
                this.selected = focused
                stateDescription = contentDescription
                if (!enabled) disabled()
            }
            .size(33.dp)
            .focusProperties { canFocus = false }
            .hoverable(interactionSource = interaction, enabled = interactive)
            .clickable(interactionSource = interaction, indication = null, enabled = interactive, onClick = onClick)
            .graphicsLayer {
                val scale = if (enabled) (1f + (37f / 33f - 1f) * focus.value) * pressScale.value else 1f
                scaleX = scale
                scaleY = scale
                this.shape = shape
            }
            .onGloballyPositioned { coordinates -> onBounds?.invoke(coordinates.boundsInWindow()) }
            .clip(shape)
            .drawBehind {
                val resting = if (enabled) lerp(idleFill, Color.White.copy(alpha = 0.14f), hover.value) else idleFill
                val fill = if (enabled) lerp(resting, Color.White.copy(alpha = 0.90f), focus.value) else idleFill
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(fill, cornerRadius = radius)
                drawRoundRect(
                    Color.White.copy(alpha = if (enabled) 0.16f * (1f - focus.value) else 0.05f),
                    cornerRadius = radius, style = Stroke(0.5.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                else -> lerp(Color.White.copy(alpha = 0.94f), Color(0xFF1E1E1E), focus.value)
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Remote-only television scrubber: fixed track, thin playhead, following elapsed label. */
@Composable
internal fun TvPlaybackProgress(
    positionState: State<Long>,
    durationMs: Long,
    isFocused: Boolean,
    scrubPositionMs: Long?,
) {
    val second by remember(positionState, scrubPositionMs) {
        derivedStateOf { (scrubPositionMs ?: positionState.value) / 1_000L }
    }
    val focus = animateFloatAsState(if (isFocused) 1f else 0f,
        if (LocalFocusMotionStyle.current == FocusMotionStyle.Immediate) snap() else tween(150), label = "tv-progress-focus")
    val hasDuration = durationMs > 0L
    val duration = durationMs.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().automationId("player-progress").semantics { selected = isFocused }) {
        Canvas(Modifier.fillMaxWidth().height(7.dp)) {
            val fraction = if (hasDuration) (positionState.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val displayed = if (hasDuration) ((scrubPositionMs ?: positionState.value).toFloat() / duration).coerceIn(0f, 1f) else 0f
            val h = size.height
            val radius = CornerRadius(h / 2f)
            val idleColor = Color(0xFFECECEC).copy(alpha = 0.26f)
            val playedColor = lerp(idleColor, Color(0xFFF4F4F4).copy(alpha = 0.56f), focus.value)
            fun segment(start: Float, end: Float, color: Color) {
                if (end <= start) return
                clipRect(left = size.width * start, right = size.width * end) {
                    drawRoundRect(color, cornerRadius = radius)
                }
            }
            // Adjacent clips keep A/B/C opacity exact; translucent segments
            // must not be painted twice over an already drawn base capsule.
            if (scrubPositionMs != null && abs(displayed - fraction) > 0.0001f) {
                val start = min(displayed, fraction)
                val end = maxOf(displayed, fraction)
                segment(0f, start, playedColor)
                segment(start, end, Color(0xFFEBEBEB).copy(alpha = 0.40f))
                segment(end, 1f, idleColor)
            } else {
                segment(0f, fraction, playedColor)
                segment(fraction, 1f, idleColor)
            }
            val width = (0.5f + 0.5f * focus.value).dp.toPx()
            val height = (7f + focus.value).dp.toPx()
            if (hasDuration) drawRoundRect(
                Color.White,
                topLeft = Offset((size.width * displayed - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f)), (h - height) / 2),
                size = Size(width, height), cornerRadius = CornerRadius(width / 2),
            )
        }
        Spacer(Modifier.height(4.dp))
        Layout(
            modifier = Modifier.fillMaxWidth().height(15.dp),
            content = {
                Text(formatPlaybackTime(second * 1_000L), color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.automationId("player-progress-elapsed"), maxLines = 1, softWrap = false,
                    fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (hasDuration) "−${formatPlaybackTime((durationMs - second * 1_000L).coerceAtLeast(0L))}" else "",
                    modifier = Modifier.automationId("player-progress-remaining"), maxLines = 1, softWrap = false,
                    color = Color.White.copy(alpha = 0.8f), fontSize = 11.5.sp,
                    lineHeight = 14.sp, fontWeight = FontWeight.Medium)
            },
        ) { measurables, constraints ->
            val elapsed = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val remaining = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val minimumGap = 8.dp.roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                // Reading the clock in placement moves the label without remeasuring text.
                val position = if (hasDuration) ((scrubPositionMs ?: positionState.value).toFloat() / duration).coerceIn(0f, 1f) else 0f
                val placement = playbackTimePlacement(
                    constraints.maxWidth, elapsed.width, remaining.width, minimumGap, position,
                )
                elapsed.placeRelative(placement.elapsedX, 0)
                if (placement.showRemaining) remaining.placeRelative(placement.remainingX, 0)
            }
        }
    }
}

private fun formatPlaybackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
