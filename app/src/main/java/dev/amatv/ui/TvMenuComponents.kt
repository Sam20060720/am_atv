package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlin.math.roundToInt

internal val LocalCatalogMenuCanActivate = staticCompositionLocalOf<() -> Boolean> { { true } }

/** Presentation values shared by context actions and lyric display menus. */
internal data class TvMenuMetrics(
    val width: Dp,
    val maxHeight: Dp,
    val radius: Dp,
    val padding: Dp,
    val viewportPadding: Dp,
    val rowHeight: Dp,
    val twoLineHeight: Dp,
    val rowSpacing: Dp,
    val iconSlot: Dp,
    val iconSize: Dp,
    val textSize: TextUnit,
    val lineHeight: TextUnit,
)

private val appleMenuMetrics = TvMenuMetrics(
    CatalogMenuStyle.Width, CatalogMenuStyle.MaxHeight, CatalogMenuStyle.Radius,
    CatalogMenuStyle.Padding, 4.dp, CatalogMenuStyle.RowHeight, CatalogMenuStyle.TwoLineHeight,
    CatalogMenuStyle.RowSpacing, CatalogMenuStyle.IconSlot, CatalogMenuStyle.IconSize, 12.5.sp, 16.sp,
)
private val classicMenuMetrics = TvMenuMetrics(
    300.dp, 380.dp, 22.dp, 16.dp, 10.dp, 44.dp, 64.dp, 4.dp, 40.dp, 18.dp, 15.sp, 20.sp,
)

@Composable
internal fun tvMenuMetrics(): TvMenuMetrics =
    if (LocalInterfaceStyle.current == InterfaceStyle.Classic) classicMenuMetrics else appleMenuMetrics

/** The overlay belongs to this native window, outside panel and lazy-list clipping. */
@Composable
internal fun TvMenuFocusScope(
    visibility: State<Float>? = null,
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val controller = remember { FocusFrameController() }
    val activeNow by rememberUpdatedState(active)
    val focusVisibility = remember(visibility) {
        object : State<Float> {
            override val value: Float get() = if (activeNow) visibility?.value ?: 1f else 0f
        }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalFocusFrameController provides if (classic) controller else LocalFocusFrameController.current,
            LocalFocusFrameEnabled provides (classic && active),
            LocalFocusFrameVisibility provides focusVisibility,
            LocalFocusTargetsEnabled provides (LocalFocusTargetsEnabled.current && active),
        ) {
            content()
            if (classic) FocusFrameOverlay(controller)
        }
    }
}

/** Shared menu chrome; callers keep their own input gates, placement and history. */
@Composable
internal fun TvMenuSurface(
    modifier: Modifier = Modifier,
    material: Boolean = false,
    motionEnabled: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val metrics = tvMenuMetrics()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(metrics.radius)
    Column(modifier.automationId("tv-menu-surface")
        .dropShadow(shape) {
            radius = with(density) { (if (classic) 20.dp else 15.dp).toPx() }
            offset = Offset(0f, with(density) { 5.dp.toPx() })
            color = Color.Black; alpha = if (classic) .40f else .30f
        }
        .clip(shape)
        .background(if (classic) Color(0xFF1B2923) else if (material)
            Color(0xFF1E1E22).copy(alpha = .72f) else Color(0xE61E1E1E))
        .border(.5.dp, Color.White.copy(alpha = if (classic) .13f else .08f), shape)
        .pointerInput(Unit) { detectTapGestures { } }
        .animateContentSize(if (motionEnabled) tween(200, easing = CatalogMenuStyle.EaseOut) else tween(0))
        .padding(metrics.padding), content = content)
}

/** One action/choice row for catalog actions, languages, translation and pronunciation. */
@Composable
internal fun CatalogSheetRow(
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false,
    retainFocusWhenDisabled: Boolean = true,
    artwork: Track? = null, icon: ImageVector? = null, submenu: Boolean = false,
    subtitle: String? = null, value: String? = null, onClick: () -> Unit,
) {
    val metrics = tvMenuMetrics()
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var rowFocused by remember { mutableStateOf(false) }
    val rowBackground = if (rowFocused) Color(0xFFEBEBEB)
        else if (classic && selected) Color.White.copy(alpha = .07f) else Color.Transparent
    val style = TextStyle(fontSize = metrics.textSize, lineHeight = metrics.lineHeight,
        fontWeight = FontWeight.Medium, fontFamily = LocalTextStyle.current.fontFamily)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val submenuKey = if (isRtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val valueWidth = if (value != null) maxWidth * .34f else 0.dp
        val titleWidth = with(density) { (maxWidth - metrics.iconSlot - 10.dp - valueWidth -
            if (submenu) 15.dp else 0.dp).toPx().roundToInt().coerceAtLeast(1) }
        val measured = textMeasurer.measure(label, style, maxLines = 2, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = titleWidth))
        val baseHeight = if (measured.lineCount > 1 || subtitle != null) metrics.twoLineHeight else metrics.rowHeight
        val subtitleStyle = TextStyle(fontSize = if (classic) 12.sp else 11.sp,
            lineHeight = if (classic) 17.sp else 14.sp, fontFamily = style.fontFamily)
        val rowHeight = if (subtitle == null) {
            if (classic) maxOf(baseHeight, with(density) { measured.size.height.toDp() } + 8.dp) else baseHeight
        } else {
            val subtitleHeight = textMeasurer.measure(subtitle, subtitleStyle, maxLines = 1,
                constraints = Constraints(maxWidth = titleWidth)).size.height
            maxOf(baseHeight, with(density) { (measured.size.height + subtitleHeight).toDp() } + 8.dp)
        }
        val expandScale = if (classic) 1.015f else 1f + 4f / maxWidth.value.coerceAtLeast(1f)
        val active = LocalFocusTargetsEnabled.current
        val canActivate = LocalCatalogMenuCanActivate.current
        val submenuHeld = remember(submenu) { booleanArrayOf(false) }
        FocusSurface({ if (enabled && canActivate()) onClick() }, Modifier.fillMaxWidth().height(rowHeight)
            .semantics { this.selected = selected; if (!enabled) disabled() }
            .onFocusChanged { rowFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!submenu || native.keyCode != submenuKey) false
                else when (native.action) {
                    KeyEvent.ACTION_DOWN -> { if (native.repeatCount == 0) submenuHeld[0] = enabled && active && canActivate(); true }
                    KeyEvent.ACTION_UP -> {
                        if (submenuHeld[0] && enabled && active && canActivate()) onClick()
                        submenuHeld[0] = false
                        true
                    }
                    else -> true
                }
            },
            enabled = enabled || retainFocusWhenDisabled,
            interactionFeedbackEnabled = enabled, cornerRadius = rowHeight / 2,
            focusedScale = expandScale, focusedElevation = if (rowFocused) 4.dp else 0.dp,
            background = rowBackground,
            hoveredBackground = if (rowFocused) rowBackground else Color.White.copy(alpha = .15f),
            focusedBackground = rowBackground,
            padding = PaddingValues(end = 10.dp)) {
            val color = when {
                rowFocused && enabled -> Color(0xFF1E1E22)
                rowFocused -> Color(0xFF1E1E22).copy(alpha = .40f)
                enabled -> Color.White.copy(alpha = .94f)
                else -> Color.White.copy(alpha = .40f)
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(metrics.iconSlot).fillMaxHeight(), Alignment.Center) {
                    when {
                        selected -> Icon(Icons.Rounded.Check, null, Modifier.size(if (classic) 14.dp else 10.dp), tint = color)
                        artwork != null -> MotionArtwork(artwork, Modifier.size(if (classic) 30.dp else 24.dp), animate = false,
                            cornerRadius = if (artwork.catalogKind == CatalogKind.Artist) {
                                if (classic) 15.dp else 12.dp
                            } else 4.dp)
                        icon != null -> Icon(icon, null, Modifier.size(metrics.iconSize), tint = color)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(label, color = color, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, color = color.copy(alpha = color.alpha * .65f), style = subtitleStyle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (value != null) Text(value, color = color.copy(alpha = color.alpha * .72f),
                    fontSize = if (classic) 13.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = valueWidth).padding(start = 8.dp))
                if (submenu) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    Modifier.padding(start = 3.dp).size(12.dp), tint = color.copy(alpha = color.alpha * .65f))
            }
        }
    }
}

@Composable
internal fun CatalogSheetSeparator() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)
        .height(.5.dp).background(Color.White.copy(alpha = .10f)))
}
