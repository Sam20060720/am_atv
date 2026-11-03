package dev.amatv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.produceState
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.LruCache
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.ui.unit.IntSize
import dev.amatv.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

/** Focus changes update only this backdrop's observer, never the browse page. */
internal class BrowseBackdropController {
    var requestedTrack by mutableStateOf<Track?>(null)
        private set

    fun select(track: Track) {
        val previous = requestedTrack
        if (previous != null && previous.artworkUrl == track.artworkUrl && previous.palette == track.palette) return
        requestedTrack = track
    }
}

internal val LocalBrowseBackdropController = staticCompositionLocalOf<BrowseBackdropController?> { null }

/** Precomposed soft material: one texture draw, or two during the 700ms blend. */
@Composable
internal fun BrowseBackdrop(
    controller: BrowseBackdropController,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val started = rememberMotionLifecycleStarted()
    val enabled = active && started
    var settledTrack by remember(controller) { mutableStateOf<Track?>(null) }
    LaunchedEffect(controller, enabled) {
        if (!enabled) {
            settledTrack = null
            return@LaunchedEffect
        }
        snapshotFlow { controller.requestedTrack }.collectLatest { candidate ->
            // Official SuperBrowse waits 300ms before changing focus-dependent content.
            delay(300)
            settledTrack = candidate
        }
    }
    val selected = settledTrack
    // Removing this composition branch also cancels an in-flight palette decode.
    val colors = if (enabled && selected != null) {
        key(selected.artworkUrl, selected.palette) {
            rememberArtworkPalette(selected.artworkUrl, selected.palette)
        }
    } else emptyList()
    val prepared = if (enabled) rememberBlurredBackdrop(selected) else null
    val composed = rememberComposedBrowseBackdrop(prepared, colors, enabled)
    val latestPrepared = rememberUpdatedState(composed)
    var displayedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var incomingImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val imageBlend = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            incomingImage = null
            imageBlend.snapTo(0f)
        }
        // Finish the current ready-image blend before accepting the newest one.
        // Rapid navigation never clears the last complete background.
        snapshotFlow { latestPrepared.value }.filterNotNull().collect { next ->
            if (next === displayedImage) return@collect
            if (!enabled) { displayedImage = next; return@collect }
            imageBlend.snapTo(0f)
            incomingImage = next
            imageBlend.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            displayedImage = next
            incomingImage = null
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val displayed = displayedImage
        // The base material is also precomposed off-main; this solid color is
        // only the initial-frame fallback while its tiny bitmap is prepared.
        if (displayed == null) drawRect(Color(0xFF5B596C))
        else drawImage(displayed, dstSize = destination)
        incomingImage?.let { drawImage(it, dstSize = destination, alpha = imageBlend.value) }
    }
}

private data class BrowseMaterialKey(val photo: ImageBitmap?, val colors: List<Int>, val enabled: Boolean)
private val browseMaterialCache = LruCache<BrowseMaterialKey, ImageBitmap>(12)
private const val MaterialWidth = 256
private const val MaterialHeight = 144

/** Gradients and the 96px blurred photo are rasterized only when inputs change. */
@Composable
private fun rememberComposedBrowseBackdrop(photo: ImageBitmap?, colors: List<Color>, enabled: Boolean): ImageBitmap? {
    val key = remember(photo, colors, enabled) { BrowseMaterialKey(photo, colors.map(Color::toArgb), enabled) }
    return produceState<ImageBitmap?>(browseMaterialCache.get(key), key) {
        browseMaterialCache.get(key)?.let { value = it; return@produceState }
        val image = withContext(ArtworkImages.decodeDispatcher) {
            currentCoroutineContext().ensureActive()
            composeBrowseMaterial(key).also { currentCoroutineContext().ensureActive() }
        }
        browseMaterialCache.put(key, image)
        value = image
    }.value
}

private fun composeBrowseMaterial(key: BrowseMaterialKey): ImageBitmap {
    val bitmap = Bitmap.createBitmap(MaterialWidth, MaterialHeight, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
    }
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    val width = MaterialWidth.toFloat()
    val height = MaterialHeight.toFloat()
    val background = Color(0xFF5B596C)
    paint.shader = LinearGradient(0f, 0f, 0f, height,
        intArrayOf(Color(0xFF536172).toArgb(), background.toArgb()), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, paint)
    if (key.enabled) {
        paint.shader = null
        key.photo?.let { canvas.drawBitmap(it.asAndroidBitmap(), null, Rect(0, 0, MaterialWidth, MaterialHeight), paint) }
        val primary = key.colors.firstOrNull()?.let { Color(it).copy(alpha = if (key.photo == null) 0.28f else 0.1f) }
        val secondary = (key.colors.getOrNull(2) ?: key.colors.lastOrNull())
            ?.let { Color(it).copy(alpha = if (key.photo == null) 0.24f else 0.08f) }
        if (primary != null) {
            paint.shader = RadialGradient(width * 0.19f, height * 0.08f, width * 0.55f,
                intArrayOf(primary.toArgb(), android.graphics.Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width, height, paint)
        }
        if (secondary != null) {
            paint.shader = RadialGradient(width * 0.78f, -height * 0.05f, width * 0.48f,
                intArrayOf(secondary.toArgb(), android.graphics.Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width, height, paint)
        }
        paint.shader = LinearGradient(0f, height * 0.18f, 0f, height,
            intArrayOf(android.graphics.Color.TRANSPARENT, background.copy(alpha = 0.36f).toArgb()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
    }
    bitmap.prepareToDraw()
    return bitmap.asImageBitmap()
}
