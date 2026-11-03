package dev.amatv.ui

import kotlin.math.roundToInt

internal data class PlaybackTimePlacement(
    val elapsedX: Int,
    val remainingX: Int,
    val showRemaining: Boolean,
)

/** Keep the following timestamp clear of the fixed trailing timestamp. */
internal fun playbackTimePlacement(
    width: Int,
    elapsedWidth: Int,
    remainingWidth: Int,
    minimumGap: Int,
    fraction: Float,
): PlaybackTimePlacement {
    val available = width.coerceAtLeast(0)
    val elapsed = elapsedWidth.coerceIn(0, available)
    val remaining = remainingWidth.coerceIn(0, available)
    val gap = minimumGap.coerceAtLeast(0)
    val remainingX = available - remaining
    // At exceptional font scales/narrow widths retain the elapsed time rather
    // than stacking or drawing two unreadable timestamps over one another.
    val showRemaining = remaining > 0 && elapsed.toLong() + remaining + gap <= available
    val limit = if (showRemaining) remainingX - gap - elapsed else available - elapsed
    val position = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val desired = (available * position - elapsed / 2f).roundToInt()
    return PlaybackTimePlacement(desired.coerceIn(0, limit.coerceAtLeast(0)), remainingX, showRemaining)
}
