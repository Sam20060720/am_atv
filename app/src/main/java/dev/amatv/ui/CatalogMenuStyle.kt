package dev.amatv.ui

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Bounds are screen pixels; the root keeps the last value outside snapshot state. */
internal val LocalCatalogMenuAnchorReporter = staticCompositionLocalOf<((Rect) -> Unit)?> { null }

/** Supplied Apple TV app.js modules 46261 / 51690 on the normalized 960×540 canvas. */
internal object CatalogMenuStyle {
    val Width = 227.dp
    val MaxHeight = 300.dp
    val Radius = 27.dp
    val Padding = 10.5.dp
    val RowHeight = 33.dp
    val TwoLineHeight = 43.dp
    val RowSpacing = 2.dp
    val IconSlot = 33.dp
    val IconSize = 13.5.dp
    val Gap = 10.dp
    const val ShowMillis = 380
    const val HideMillis = 210
    const val SubmenuMillis = 150
    const val StartingScale = 0.35f
    val EaseOut = Easing { it * (2f - it) }
}

internal data class CatalogMenuPlacement(val bounds: Rect, val origin: TransformOrigin)

internal fun catalogMenuPlacement(
    anchor: Rect?, menu: IntSize, screen: IntSize, horizontalSafe: Float, verticalSafe: Float, gap: Float,
): CatalogMenuPlacement {
    val width = menu.width.toFloat()
    val height = menu.height.toFloat()
    val leftBound = horizontalSafe
    val rightBound = (screen.width - horizontalSafe - width).coerceAtLeast(leftBound)
    val topBound = verticalSafe
    val bottomBound = (screen.height - verticalSafe - height).coerceAtLeast(topBound)
    if (anchor == null || anchor.width <= 0 || anchor.height <= 0) {
        val left = ((screen.width - width) / 2f).coerceIn(leftBound, rightBound)
        val top = ((screen.height - height) / 2f).coerceIn(topBound, bottomBound)
        return CatalogMenuPlacement(Rect(left, top, left + width, top + height), TransformOrigin.Center)
    }
    var left = anchor.right + gap
    var originX = 0f
    if (left < leftBound || left + width > screen.width - horizontalSafe) {
        val flipped = anchor.left - gap - width
        if (flipped >= leftBound && flipped + width <= screen.width - horizontalSafe) {
            left = flipped
            originX = 1f
        }
    }
    var top = anchor.top
    var originY = 0f
    if (top < topBound || top + height > screen.height - verticalSafe) {
        val flipped = anchor.bottom - height
        if (flipped >= topBound && flipped + height <= screen.height - verticalSafe) {
            top = flipped
            originY = 1f
        }
    }
    left = left.coerceIn(leftBound, rightBound)
    top = top.coerceIn(topBound, bottomBound)
    return CatalogMenuPlacement(Rect(left, top, left + width, top + height), TransformOrigin(originX, originY))
}
