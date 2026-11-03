package dev.amatv.ui

import dev.amatv.model.LyricTimeline
import kotlin.math.abs

/** One word's original character wave, shared by its glyph and shadow layers. */
internal class LyricCharacterFrame(
    private val timings: List<LyricTimeline.CharacterEmphasisTiming>,
    private val characterCount: Int,
    private val visibleCharacterCount: Int,
    private val groupOffset: Int,
    private val vocalEndMs: Long,
    private val isRtl: Boolean,
    private val easing: (Float) -> Float,
) {
    private val widths = FloatArray(characterCount)
    private val amounts = FloatArray(characterCount)
    private val scales = FloatArray(characterCount) { 1f }
    private val halfExpansions = FloatArray(visibleCharacterCount)
    private val offsets = FloatArray(visibleCharacterCount)
    private var geometryRevision = 0
    private var renderedGeometryRevision = -1
    private var renderedPosition = Long.MIN_VALUE
    private var renderedPresence = Float.NaN

    init { require(timings.isNotEmpty() && visibleCharacterCount in 0..characterCount) }

    // Retained bright words can remain mounted for many more playback frames.
    // Once every wave has settled, only presence or measured geometry can change.
    private val settledPosition = maxOf(vocalEndMs, timings.maxOf {
        maxOf(it.riseStartMs, it.settleStartMs) + it.animationDurationMs.coerceAtLeast(1L)
    })

    fun setWidth(index: Int, width: Float) {
        if (widths[index] != width) { widths[index] = width; geometryRevision++ }
    }

    fun update(positionMs: Long, presence: Float) {
        val sampledPosition = minOf(positionMs, settledPosition)
        if (sampledPosition == renderedPosition && presence == renderedPresence &&
            renderedGeometryRevision == geometryRevision) return
        for (index in 0 until characterCount) {
            val timing = timings[(groupOffset + index).coerceIn(0, timings.lastIndex)]
            val amount = LyricTimeline.characterEmphasisAmountAt(timing, positionMs, vocalEndMs, presence, easing)
            amounts[index] = amount
            scales[index] = 1f + (timing.maximumScale - 1f) * amount
            if (index < visibleCharacterCount) halfExpansions[index] = (scales[index] - 1f) * widths[index] * 0.5f
        }
        offsets.fill(0f)
        // Same accumulation order as symmetricExpansionOffsets, using reusable
        // primitive arrays rather than boxed Lists for each character layer.
        if (visibleCharacterCount > 1) {
            val left = visibleCharacterCount / 2 - 1
            val right = (visibleCharacterCount + 1) / 2
            val centre = (visibleCharacterCount - 1) * 0.5f
            for (index in left downTo 0) {
                var displacement = halfExpansions[index]
                val next = index + 1
                if (next <= centre) displacement += halfExpansions[next]
                if (next < centre) displacement += abs(offsets[next])
                offsets[index] = displacement * if (isRtl) 1f else -1f
            }
            for (index in right until visibleCharacterCount) {
                var displacement = halfExpansions[index]
                val previous = index - 1
                if (previous >= centre) displacement += halfExpansions[previous]
                if (previous > centre) displacement += abs(offsets[previous])
                offsets[index] = displacement * if (isRtl) -1f else 1f
            }
        }
        renderedPosition = sampledPosition
        renderedPresence = presence
        renderedGeometryRevision = geometryRevision
    }

    fun amount(index: Int): Float = amounts[index]
    fun scale(index: Int): Float = scales[index]
    fun offset(index: Int): Float = offsets.getOrElse(index) { 0f }
}
