package dev.amatv.ui

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Experimental CPU equivalent of LavaGlRenderer.FIELD + its two BLUR passes.
 * No Android/GLES state and no frame allocations. The final 8-bit bitmap is
 * filtered by Canvas; fullscreen GLSL output dither is deliberately not claimed.
 */
internal class LavaCpuField(val width: Int = 96, val height: Int = 54) {
    init {
        require(width in 32..128 && height in 18..72)
    }

    private class Ellipse(private val cullDistance: Float) {
        var x = 0f
        var y = 0f
        var inverseX = 1f
        var inverseY = 1f
        var radius = 1f
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        fun set(cx: Float, cy: Float, rx: Float, ry: Float) {
            x = cx; y = cy
            inverseX = 1f / rx; inverseY = 1f / ry
            radius = min(rx, ry)
            val extent = 1f + cullDistance / radius
            left = cx - rx * extent; right = cx + rx * extent
            top = cy - ry * extent; bottom = cy + ry * extent
        }
        fun at(px: Float, py: Float): Float {
            val dx = (px - x) * inverseX
            val dy = (py - y) * inverseY
            return (sqrt(dx * dx + dy * dy) - 1f) * radius
        }
    }

    private val shapes = Array(8) { index ->
        // smooth-min(a,b,k) >= min(a,b)-k/4. Outside all expanded
        // rectangles the group's final mask is exactly zero, even after every
        // merge. The extra .001 guard is conservative for Float rounding.
        Ellipse(when (index) {
            in 0..2 -> .018f + (.12f + .10f) / 4f + .001f
            in 3..4 -> .018f + .08f / 4f + .001f
            else -> .018f + (.08f + .10f) / 4f + .001f
        })
    }
    private val support = ByteArray(width * height)
    private val field = FloatArray(width * height * 3)
    private val temporary = FloatArray(field.size)
    private val warpedX = FloatArray(height)
    private val warpedY = FloatArray(width)
    private val inkRowSin = FloatArray(height)
    private val inkRowCos = FloatArray(height)
    private val inkColumnSin = FloatArray(width)
    private val inkColumnCos = FloatArray(width)
    private val xCoordinates = FloatArray(width)
    private val yCoordinates = FloatArray(height)
    private val horizontalWeights = FloatArray(MAX_TAPS)
    private val verticalWeights = FloatArray(MAX_TAPS)
    private val horizontalIndices = IntArray(width * MAX_TAPS)
    private val verticalIndices = IntArray(height * MAX_TAPS)
    private var horizontalRadius = 0
    private var verticalRadius = 0
    private var aspect = Float.NaN

    /** Resize changes the sampling geometry only, not motion or palette state. */
    fun setAspect(value: Float) {
        require(value.isFinite() && value in 0.5f..4f)
        if (aspect == value) return
        aspect = value
        for (x in 0 until width) xCoordinates[x] = (x + .5f) / width * aspect
        for (y in 0 until height) yCoordinates[y] = (y + .5f) / height
        horizontalRadius = kernel(.02475f / aspect * width, horizontalWeights)
        verticalRadius = kernel(.02475f * height, verticalWeights)
        indices(width, horizontalRadius, horizontalIndices, 3)
        indices(height, verticalRadius, verticalIndices, width * 3)
    }

    fun render(
        elapsedNanos: Long,
        bass: Float,
        impact: Float,
        mid: Float,
        treble: Float,
        palette: FloatArray,
        pixels: IntArray,
    ) {
        require(palette.size == 15 && pixels.size == width * height)
        if (!aspect.isFinite()) setAspect(width.toFloat() / height)
        val t = elapsedNanos / 1_000_000_000f * 2.4f
        val center = aspect * .61f
        shapes[0].set(center + .19f * sin(t * .17f) - .07f * impact,
            .07f + .15f * sin(t * .12f), .165f + .06f * bass + .14f * impact,
            .49f + .025f * bass + .04f * impact)
        shapes[1].set(center + .21f * sin(t * .13f + .8f) + .09f * impact,
            .43f + .14f * sin(t * .18f), .18f + .055f * bass + .12f * impact,
            .30f - .025f * impact)
        shapes[2].set(center - .08f + .25f * sin(t * .11f + 2f),
            .91f + .20f * cos(t * .17f) - .07f * impact,
            .12f + .035f * bass + .08f * impact, .15f)
        shapes[3].set(-.13f + .18f * sin(t * .12f + .7f),
            .79f + .18f * sin(t * .10f), .34f + .04f * impact, .61f)
        shapes[4].set(aspect + .14f + .07f * cos(t * .13f),
            .24f + .12f * cos(t * .11f), .27f, .70f)
        shapes[5].set(aspect * .34f + .18f * sin(t * .1f) - .05f * impact,
            1.07f + .18f * cos(t * .14f), .31f - .025f * impact, .45f)
        shapes[6].set(aspect * .85f + .16f * sin(t * .11f + 2f),
            .17f + .16f * cos(t * .15f), .16f, .57f)
        shapes[7].set(aspect * .94f + .14f * cos(t * .09f),
            1.19f + .10f * sin(t * .12f), .37f, .43f)

        // sin(2*py + px) is separable after the shader's row/column warp.
        // Preserve that expression without calling sin for every field pixel.
        for (y in 0 until height) {
            val py = yCoordinates[y]
            val warp = .06f * sin(py * 5f + t * .16f) + .03f * sin(py * 9f - t * .11f)
            warpedX[y] = warp
            val angle = 2f * py + warp
            inkRowSin[y] = sin(angle); inkRowCos[y] = cos(angle)
        }
        for (x in 0 until width) {
            val px = xCoordinates[x]
            val warp = .045f * sin(px * 3.8f - t * .12f)
            warpedY[x] = warp
            val angle = px + 2f * warp
            inkColumnSin[x] = sin(angle); inkColumnCos[x] = cos(angle)
        }
        markSupport()
        var offset = 0
        var pixelIndex = 0
        for (y in 0 until height) for (x in 0 until width) {
            val px = xCoordinates[x] + warpedX[y]
            val py = yCoordinates[y] + warpedY[x]
            val groups = support[pixelIndex++].toInt()
            val g = if (groups and 0x07 == 0) .018f else
                merge(merge(shapes[0].at(px, py), shapes[1].at(px, py), .12f), shapes[2].at(px, py), .10f)
            val side = if (groups and 0x18 == 0) .018f else
                merge(shapes[3].at(px, py), shapes[4].at(px, py), .08f)
            val m = if (groups and 0xe0 == 0) .018f else
                merge(merge(shapes[5].at(px, py), shapes[6].at(px, py), .08f), shapes[7].at(px, py), .10f)
            val gm = 1f - smooth(-.012f, .018f, g)
            val sm = (1f - smooth(-.012f, .018f, side)) * .9f
            val mm = (1f - smooth(-.012f, .018f, m)) * (1f - gm * .65f)
            val inkScale = .96f + .045f * (inkRowSin[y] * inkColumnCos[x] +
                inkRowCos[y] * inkColumnSin[x]) + .012f * mid + .006f * treble
            val edgeMix = smooth(0f, aspect, px)
            val gScale = .84f + .16f * (1f - smooth(-.15f, .05f, g))
            val mScale = .84f + .16f * (1f - smooth(-.18f, .05f, m))
            for (channel in 0..2) {
                val edge = mix(palette[3 + channel], mix(palette[9 + channel],
                    palette[12 + channel], edgeMix), .24f)
                var color = mix(palette[channel] * inkScale, edge, sm)
                color = mix(color, palette[3 + channel] * gScale, gm)
                field[offset++] = mix(color, palette[6 + channel] * mScale, mm)
            }
        }
        blurHorizontal()
        blurVertical()
        for (pixel in pixels.indices) {
            val channel = pixel * 3
            val r = (field[channel].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (field[channel + 1].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (field[channel + 2].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[pixel] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Conservative coverage, prepared once per frame rather than testing eight
     * rectangles at every pixel. The row uses the actual column-warp extrema;
     * the extra column on either side keeps inverse-coordinate rounding outward.
     */
    private fun markSupport() {
        support.fill(0)
        var lowWarp = Float.POSITIVE_INFINITY
        var highWarp = Float.NEGATIVE_INFINITY
        for (value in warpedY) {
            lowWarp = minOf(lowWarp, value)
            highWarp = maxOf(highWarp, value)
        }
        for (shapeIndex in shapes.indices) {
            val shape = shapes[shapeIndex]
            val bit = 1 shl shapeIndex
            for (y in 0 until height) {
                val lowY = yCoordinates[y] + lowWarp
                val highY = yCoordinates[y] + highWarp
                if (highY < shape.top || lowY > shape.bottom) continue
                val first = (floor((shape.left - warpedX[y]) / aspect * width - .5f).toInt() - 1).coerceAtLeast(0)
                val last = (ceil((shape.right - warpedX[y]) / aspect * width - .5f).toInt() + 1).coerceAtMost(width - 1)
                val row = y * width
                for (x in first..last) support[row + x] = (support[row + x].toInt() or bit).toByte()
            }
        }
    }

    private fun blurHorizontal() {
        val taps = horizontalRadius * 2 + 1
        for (y in 0 until height) {
            val row = y * width * 3
            for (x in 0 until width) {
                val destination = row + x * 3
                val tapBase = x * MAX_TAPS
                var r = 0f; var g = 0f; var b = 0f
                for (tap in 0 until taps) {
                    val source = row + horizontalIndices[tapBase + tap]
                    val weight = horizontalWeights[tap]
                    r += field[source] * weight
                    g += field[source + 1] * weight
                    b += field[source + 2] * weight
                }
                temporary[destination] = r; temporary[destination + 1] = g; temporary[destination + 2] = b
            }
        }
    }

    private fun blurVertical() {
        val taps = verticalRadius * 2 + 1
        for (y in 0 until height) {
            val row = y * width * 3
            val tapBase = y * MAX_TAPS
            for (x in 0 until width) {
                val column = x * 3
                val destination = row + column
                var r = 0f; var g = 0f; var b = 0f
                for (tap in 0 until taps) {
                    val source = verticalIndices[tapBase + tap] + column
                    val weight = verticalWeights[tap]
                    r += temporary[source] * weight
                    g += temporary[source + 1] * weight
                    b += temporary[source + 2] * weight
                }
                field[destination] = r; field[destination + 1] = g; field[destination + 2] = b
            }
        }
    }

    private fun kernel(step: Float, destination: FloatArray): Int {
        destination.fill(0f)
        val radius = ceil(step * 4f).toInt()
        require(radius * 2 + 1 <= MAX_TAPS)
        destination[radius] = .227027027f
        for (distance in 1..4) {
            val weight = when (distance) {
                1 -> .194594595f
                2 -> .121621622f
                3 -> .054054054f
                else -> .016216216f
            }
            val sample = step * distance
            val low = floor(sample).toInt()
            val fraction = sample - low
            for (sign in intArrayOf(-1, 1)) {
                destination[radius + sign * low] += weight * (1f - fraction)
                if (fraction > 0f) destination[radius + sign * (low + 1)] += weight * fraction
            }
        }
        return radius
    }

    private fun indices(size: Int, radius: Int, destination: IntArray, stride: Int) {
        for (coordinate in 0 until size) for (tap in 0..radius * 2)
            destination[coordinate * MAX_TAPS + tap] = (coordinate + tap - radius).coerceIn(0, size - 1) * stride
    }

    private companion object {
        const val MAX_TAPS = 53 // Worst case: width128, aspect0.5, four blur steps.
        fun mix(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
        fun smooth(low: Float, high: Float, value: Float): Float {
            val p = ((value - low) / (high - low)).coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }
        fun merge(a: Float, b: Float, k: Float): Float {
            val h = (.5f + .5f * (b - a) / k).coerceIn(0f, 1f)
            return mix(b, a, h) - k * h * (1f - h)
        }
    }
}
