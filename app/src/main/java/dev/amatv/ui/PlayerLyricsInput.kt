package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming

internal const val LyricsTimingStepMs = 5L
private const val MaxLyricsTimingOffsetMs = 3_000L

// The control shows a delay. The player applies the inverse to the lyric clock.
internal fun offsetAfterLyricsTimingStep(offsetMs: Long, delayStepMs: Long): Long =
    (offsetMs - delayStepMs).coerceIn(-MaxLyricsTimingOffsetMs, MaxLyricsTimingOffsetMs)

/** Selection and drawing use the same offset-adjusted lyric clock. */
internal fun effectiveLyricsPosition(positionMs: Long, offsetMs: Long): Long =
    (positionMs + offsetMs).coerceAtLeast(0L)

internal fun LyricLine.canSeekLyric(): Boolean = !isStatic && timing != LyricTiming.None
