package dev.amatv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.dp

/** Drawing outsets belong to the artwork, never the metadata/focus wrapper. */
internal object CardVisuals {
    val ShadowOutset = 14.dp
    val ShadowRadius = 12.dp

    fun focusOutset(featured: Boolean): Dp {
        val width = if (featured) BrowseTokens.ShowcaseWidth else BrowseTokens.CardWidth
        val scale = if (featured) BrowseTokens.ShowcaseFocusScale else BrowseTokens.CardFocusScale
        return width * ((scale - 1f) / 2f) + ShadowOutset
    }
}

@Composable
internal fun Modifier.coverShadow(focused: Boolean, radius: Dp, isolated: Boolean = false): Modifier {
    val graphicsContext = LocalGraphicsContext.current
    val focusTimings = LocalTelevisionFocusTimings.current
    val opacity = animateFloatAsState(
        targetValue = if (focused) 0.38f else 0.16f,
        animationSpec = if (LocalFocusMotionStyle.current == FocusMotionStyle.Immediate) snap()
            else tween(if (focused) focusTimings.enterMillis else focusTimings.exitMillis),
        label = "cover-shadow-opacity",
    )
    return then(if (isolated) Modifier.graphicsLayer {
        // Only the empty shadow sibling opts in: one image can modulate alpha
        // directly, without repainting or an implicit clipped offscreen buffer.
        alpha = opacity.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
        clip = false
    } else Modifier).drawWithCache {
        val shape = RoundedCornerShape(radius)
        val shadow = Shadow(radius = CardVisuals.ShadowRadius, color = Color.Black,
            offset = DpOffset(0.dp, 2.dp), alpha = 1f)
        var cachedContext: ShadowContext? = null
        var cachedPainter: DropShadowPainter? = null
        onDrawBehind {
            // Trim/detach can replace the owner's shadow context without a
            // recomposition. Reacquire its painter only when that happens.
            val context = graphicsContext.shadowContext
            val painter = if (context === cachedContext) checkNotNull(cachedPainter)
                else context.createDropShadowPainter(shape, shadow).also {
                    cachedContext = context
                    cachedPainter = it
                }
            // Keep Shadow.alpha (part of the renderer cache key) fixed. Empty
            // isolated slots animate only their layer property; content-owning
            // callers continue applying opacity directly to this painter.
            with(painter) { draw(size, alpha = if (isolated) 1f else opacity.value) }
        }
    }
}

/** One design pixel. Theme hairline stays the same at rest and at focus. */
@Composable
internal fun Modifier.coverHairline(radius: Dp): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val color = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.10f)
    return border(0.5.dp, color, RoundedCornerShape(radius))
}
