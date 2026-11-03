package dev.amatv.ui

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Same spring as LyricTimeline.wordLiftFraction; cache its constants and held end pose. */
internal class LyricWordLiftFrame(
    private val riseStartMs: Long,
    private val vocalEndMs: Long,
    dampingRatio: Float,
    stiffness: Float,
) {
    private val damping = dampingRatio.toDouble()
    private val naturalFrequency = sqrt(stiffness.toDouble())
    private val dampedFrequency = naturalFrequency * sqrt(1.0 - damping * damping)
    private var lastElapsedMs = Long.MIN_VALUE
    private var response = 0f

    fun value(positionMs: Long, activePresence: Float): Float {
        val elapsedMs = (minOf(positionMs, vocalEndMs) - riseStartMs).coerceAtLeast(0L)
        if (elapsedMs == 0L) return 0f
        if (elapsedMs != lastElapsedMs) {
            val seconds = elapsedMs / 1_000.0
            response = (1.0 - exp(-damping * naturalFrequency * seconds) * (
                cos(dampedFrequency * seconds) +
                    damping * naturalFrequency / dampedFrequency * sin(dampedFrequency * seconds)
                )).toFloat().coerceIn(0f, 1.02f)
            lastElapsedMs = elapsedMs
        }
        val exitPresence = if (positionMs < vocalEndMs) 1f else activePresence.coerceIn(0f, 1f)
        return response * exitPresence
    }
}
