package dev.amatv.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val BackdropSize = 96
private const val BackdropBlurRadius = 10

// Eight 96×96 ARGB images occupy 288 KiB. Do not recycle on eviction: a fading
// previous layer can still reference an image after it leaves this cache.
private val blurredBackdropCache = LruCache<String, ImageBitmap>(8)

/** Mount only for the active background; disposal cancels the request and processing. */
@Composable
internal fun rememberBlurredBackdrop(track: Track?): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val url = track?.artworkUrl?.takeIf { it.startsWith("https://") }
    var image by remember(url) { mutableStateOf(url?.let(blurredBackdropCache::get)) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        blurredBackdropCache.get(url)?.let {
            image = it
            return@LaunchedEffect
        }
        val prepared = try {
            withContext(ArtworkImages.decodeDispatcher) {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(BackdropSize)
                    .allowHardware(false)
                    .memoryCacheKey("backdrop-source:$url")
                    .build()
                // The shared loader also runs its decoder on decodeDispatcher.
                val decoded = ArtworkImages.loader(context).execute(request).image
                    ?: return@withContext null
                currentCoroutineContext().ensureActive()
                val source = decoded.toBitmap()
                val sampled = if (source.width == BackdropSize && source.height == BackdropSize) source
                    else Bitmap.createScaledBitmap(source, BackdropSize, BackdropSize, true)
                val pixels = IntArray(BackdropSize * BackdropSize)
                try {
                    sampled.getPixels(pixels, 0, BackdropSize, 0, 0, BackdropSize, BackdropSize)
                } finally {
                    // The original bitmap belongs to Coil's cache and remains untouched.
                    if (sampled !== source) sampled.recycle()
                }
                val scratch = IntArray(pixels.size)
                repeat(3) {
                    currentCoroutineContext().ensureActive()
                    boxBlur(pixels, scratch, horizontal = true)
                    boxBlur(scratch, pixels, horizontal = false)
                }
                currentCoroutineContext().ensureActive()
                // Display-space sampling/tone approximation of the photo's soft
                // material, not an assertion about Apple's rendering algorithm.
                for (index in pixels.indices) {
                    val pixel = pixels[index]
                    val red = (pixel ushr 16 and 255).toFloat()
                    val green = (pixel ushr 8 and 255).toFloat()
                    val blue = (pixel and 255).toFloat()
                    val gray = red * 0.2126f + green * 0.7152f + blue * 0.0722f
                    fun tone(channel: Float): Int =
                        ((gray + (channel - gray) * 0.45f) * 0.28f + 45f).roundToInt().coerceIn(0, 255)
                    pixels[index] = (0xFF shl 24) or (tone(red) shl 16) or (tone(green) shl 8) or tone(blue)
                }
                currentCoroutineContext().ensureActive()
                Bitmap.createBitmap(pixels, BackdropSize, BackdropSize, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (prepared != null) {
            blurredBackdropCache.put(url, prepared)
            image = prepared
        }
    }
    return image
}

/** A sliding window visits each pixel once per axis, with replicated edge samples. */
private fun boxBlur(source: IntArray, destination: IntArray, horizontal: Boolean) {
    val radius = BackdropBlurRadius
    val window = radius * 2 + 1
    val step = if (horizontal) 1 else BackdropSize
    for (line in 0 until BackdropSize) {
        val start = if (horizontal) line * BackdropSize else line
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val pixel = source[start + offset.coerceIn(0, BackdropSize - 1) * step]
            red += pixel ushr 16 and 255
            green += pixel ushr 8 and 255
            blue += pixel and 255
        }
        for (position in 0 until BackdropSize) {
            destination[start + position * step] = (0xFF shl 24) or
                ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            val outgoing = source[start + (position - radius).coerceIn(0, BackdropSize - 1) * step]
            val incoming = source[start + (position + radius + 1).coerceIn(0, BackdropSize - 1) * step]
            red += (incoming ushr 16 and 255) - (outgoing ushr 16 and 255)
            green += (incoming ushr 8 and 255) - (outgoing ushr 8 and 255)
            blue += (incoming and 255) - (outgoing and 255)
        }
    }
}
