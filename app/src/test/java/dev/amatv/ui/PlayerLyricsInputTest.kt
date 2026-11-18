package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricsInputTest {
    @Test fun timingButtonsAdjustInFiveMillisecondStepsAlongTheDisplayTimeline() {
        val earlier = offsetAfterLyricsTimingStep(0L, -LyricsTimingStepMs)
        val later = offsetAfterLyricsTimingStep(0L, LyricsTimingStepMs)
        assertEquals(1_005L, effectiveLyricsPosition(1_000L, earlier))
        assertEquals(995L, effectiveLyricsPosition(1_000L, later))
        assertEquals(0L, offsetAfterLyricsTimingStep(earlier, LyricsTimingStepMs))
        assertEquals(3_000L, offsetAfterLyricsTimingStep(2_998L, -LyricsTimingStepMs))
        assertEquals(-3_000L, offsetAfterLyricsTimingStep(-2_998L, LyricsTimingStepMs))
    }

    @Test fun selectionClockUsesTheDisplayedOffsetAndClampsAtZero() {
        val lines = listOf(
            LyricLine(listOf(LyricWord("One", 1_000L, 2_000L))),
            LyricLine(listOf(LyricWord("Two", 3_000L, 4_000L))),
        )
        assertEquals(1, LyricTimeline.activeLineAt(lines, effectiveLyricsPosition(1_500L, 1_800L), true))
        assertEquals(0L, effectiveLyricsPosition(500L, -1_000L))
    }

    @Test fun untimedLyricsCannotBecomeZeroPositionSeekTargets() {
        val line = LyricLine(listOf(LyricWord("Untimed", 0L, 0L)), isStatic = true)
        assertFalse(line.canSeekLyric())
        assertFalse(line.copy(isStatic = false, timing = LyricTiming.None).canSeekLyric())
        assertTrue(line.copy(isStatic = false, timing = LyricTiming.Line).canSeekLyric())
    }

    @Test fun languageRowsCollapseEnglishRegionsButKeepChineseScripts() {
        assertEquals(1, lyricsLanguageOptions("en").count { lyricsLanguageOptionKey(it) == "en" })
        assertEquals(1, lyricsLanguageOptions("en-GB").count { lyricsLanguageOptionKey(it) == "en" })
        assertTrue(sameLyricsMenuSelection("en", "en-US"))
        assertTrue(sameLyricsMenuSelection("", ""))
        assertFalse(sameLyricsMenuSelection("", "en-US"))
        assertEquals(setOf("zh-Hans", "zh-Hant"), lyricsLanguageOptions("zh-Hant")
            .map(::lyricsLanguageOptionKey).filter { it.startsWith("zh-") }.toSet())
    }
}
