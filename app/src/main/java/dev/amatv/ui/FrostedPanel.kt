package dev.amatv.ui

import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Samples an already recorded viewport without recomposing its content or
 * taking a bitmap snapshot. Bounds are pixels in the same viewport coordinate space.
 * The caller mounts this only while the menu needs its frosted background.
 */
@RequiresApi(31)
@Composable
internal fun FrostedPanel(
    source: GraphicsLayer,
    expandedBounds: Rect,
    animatedBounds: () -> Rect,
    animatedRadius: () -> Float,
    modifier: Modifier = Modifier,
    opacity: () -> Float = { 1f },
) {
    val region = rememberGraphicsLayer()
    Box(modifier.fillMaxSize().drawWithCache {
        // Keep the texture independent of animated geometry. The surrounding
        // pixels supply the blur kernel without allocating a viewport texture.
        val sample = expandedBounds.inflate(24.dp.toPx())
        val sampleSize = IntSize(
            ceil(sample.width).toInt().coerceAtLeast(1),
            ceil(sample.height).toInt().coerceAtLeast(1),
        )
        val blur = 16.dp.toPx()
        region.renderEffect = BlurEffect(radiusX = blur, radiusY = blur, edgeTreatment = TileMode.Clamp)
        region.clip = true
        val outline = Path()

        onDrawBehind {
            val bounds = animatedBounds()
            val alpha = opacity().coerceIn(0f, 1f)
            if (bounds.width <= 0f || bounds.height <= 0f || alpha <= 0f) return@onDrawBehind
            region.alpha = alpha
            region.record(size = sampleSize) {
                clipRect {
                    translate(left = -sample.left, top = -sample.top) {
                        drawLayer(source)
                    }
                }
            }
            outline.reset()
            outline.addRoundRect(RoundRect(bounds, CornerRadius(animatedRadius().coerceAtLeast(0f))))
            clipPath(outline) {
                translate(left = sample.left, top = sample.top) {
                    drawLayer(region)
                }
            }
        }
    })
}
