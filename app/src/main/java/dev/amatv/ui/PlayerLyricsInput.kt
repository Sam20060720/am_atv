package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming

/** Selection and drawing use the same offset-adjusted lyric clock. */
internal fun effectiveLyricsPosition(positionMs: Long, offsetMs: Long): Long =
    (positionMs + offsetMs).coerceAtLeast(0L)

internal fun LyricLine.canSeekLyric(): Boolean = !isStatic && timing != LyricTiming.None
