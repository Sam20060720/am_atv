package dev.amatv.ui.rendering

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.roundToInt

/**
 * A small, cached alpha texture uses the ordinary bitmap shader path. Lyric
 * progress changes only its matrix; it cannot select new gradient colorizers
 * while the first lyric is appearing on a slow TV GPU driver.
 */
class TextureAlphaMask {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val matrix = Matrix()
    private val matrixValues = floatArrayOf(
        0f, 0f, 0f,
        0f, 0f, 0f,
        0f, 0f, 1f,
    )
    private var textureKey = -1
    private var shader: BitmapShader? = null

    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        start: Float,
        end: Float,
        startAlpha: Float,
        endAlpha: Float,
        vertical: Boolean = false,
    ) {
        if (right <= left || bottom <= top) return
        val first = (startAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val last = (endAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        if (first == last || end <= start) {
            paint.shader = null
            val edge = if (vertical) top else left
            val limit = if (vertical) bottom else right
            if (first != last && start > edge && start < limit) {
                // Production feathers are positive. Retain exact one-pass
                // coverage if a caller explicitly asks for an interior step.
                val fraction = (start - edge) / (limit - edge)
                val a = Color.argb(first, 0, 0, 0)
                val b = Color.argb(last, 0, 0, 0)
                paint.shader = LinearGradient(left, top, if (vertical) left else right,
                    if (vertical) bottom else top, intArrayOf(a, a, b, b),
                    floatArrayOf(0f, fraction, fraction, 1f), Shader.TileMode.CLAMP)
                paint.color = Color.WHITE
            } else {
                paint.color = Color.argb(if (end <= start && start <= edge) last else first, 255, 255, 255)
            }
        } else if (!canvas.isHardwareAccelerated) {
            // Software canvases have no GPU colorizer cost. Keep their original
            // gradient rasterization instead of quantizing alpha through a texture.
            val origin = if (vertical) top else left
            val extent = if (vertical) bottom - top else right - left
            val a = Color.argb(first, 0, 0, 0)
            val b = Color.argb(last, 0, 0, 0)
            paint.shader = LinearGradient(left, top, if (vertical) left else right,
                if (vertical) bottom else top, intArrayOf(a, a, b, b),
                floatArrayOf(0f, (start - origin) / extent, (end - origin) / extent, 1f), Shader.TileMode.CLAMP)
            paint.color = Color.WHITE
        } else {
            val key = (first shl 8) or last
            if (key != textureKey) {
                shader = BitmapShader(textures.getOrCreate(key, first, last), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                textureKey = key
            }
            val scale = (end - start) / (RAMP_SIZE - 1)
            // The first/last texel centres map exactly onto the feather's
            // endpoints. Clamp supplies the constant alpha on either side.
            if (vertical) {
                matrixValues[1] = right - left
                matrixValues[2] = left
                matrixValues[3] = scale
                matrixValues[5] = start - scale * .5f
                matrix.setValues(matrixValues)
            } else {
                matrix.setScale(scale, bottom - top)
                matrix.postTranslate(start - scale * .5f, top)
            }
            shader?.setLocalMatrix(matrix)
            paint.isFilterBitmap = true
            paint.shader = shader
            paint.color = Color.WHITE
        }
        // One pass across the full row preserves glyph edge coverage.
        canvas.drawRect(left, top, right, bottom, paint)
    }

    companion object {
        private const val RAMP_SIZE = 64
        // Small ramps + bilinear filtering preserve 8-bit feather precision.
        // Shared across lyric rows and released by LRU.
        // Evicted bitmaps are not recycled while a display list may reference them.
        private val textures = object : LruCache<Int, Bitmap>(128) {
            @Synchronized fun getOrCreate(key: Int, first: Int, last: Int): Bitmap = get(key) ?: run {
                val pixels = IntArray(RAMP_SIZE) { index ->
                    Color.argb((first + (last - first) * index.toFloat() / (RAMP_SIZE - 1)).roundToInt(), 0, 0, 0)
                }
                Bitmap.createBitmap(pixels, RAMP_SIZE, 1, Bitmap.Config.ARGB_8888).also {
                    it.prepareToDraw()
                    put(key, it)
                }
            }
        }
    }
}
