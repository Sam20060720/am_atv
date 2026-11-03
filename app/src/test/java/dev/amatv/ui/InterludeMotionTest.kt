package dev.amatv.ui

import dev.amatv.model.LyricTimeline
import org.junit.Assert.*
import org.junit.Test

class InterludeMotionTest {
    private fun gap(start: Long, duration: Long) = LyricTimeline.InstrumentalGap(1, start, start + duration, duration, duration - 800)

    @Test fun thirdDotGraduallyLightsBeforeExitForShortAndLongInterludes() {
        for (duration in listOf(7_000L, 10_500L, 28_000L)) {
            val gap = gap(3_000, duration)
            val exit = InterludeMotion.exitStart(gap)
            val segment = (exit - gap.startMs) / 3
            assertEquals(0f, InterludeMotion.dotFraction(gap, gap.startMs + segment * 2, 2), .001f)
            assertEquals(.5f, InterludeMotion.dotFraction(gap, exit - segment / 2, 2), .001f)
            assertEquals(1f, InterludeMotion.dotFraction(gap, exit, 2), 0f)
            assertEquals(1f, InterludeMotion.alpha(gap, exit), 0f)
        }
    }

    @Test fun exitStartsFromTheCurrentPulseInsteadOfResettingItsSize() {
        for (duration in listOf(7_000L, 8_100L, 10_500L, 17_000L)) {
            val gap = gap(1_250, duration)
            val exit = InterludeMotion.exitStart(gap)
            assertEquals(InterludeMotion.scale(gap, exit - 1), InterludeMotion.scale(gap, exit), .001f)
            assertEquals(InterludeMotion.scale(gap, exit), InterludeMotion.scale(gap, exit + 1), .001f)
            val shrink = exit + MotionTokens.InstrumentalExitExpandMillis
            assertEquals(InterludeMotion.scale(gap, shrink - 1), InterludeMotion.scale(gap, shrink), .001f)
            assertEquals(0f, InterludeMotion.alpha(gap, shrink + MotionTokens.InstrumentalExitShrinkMillis), 0f)
        }
    }

    @Test fun seekingBackRecomputesTheCountdownAndPulseWithoutStaleExitState() {
        val gap = gap(5_000, 12_000)
        assertEquals(0f, InterludeMotion.alpha(gap, 17_000), 0f)
        assertEquals(1f, InterludeMotion.alpha(gap, 6_000), 0f)
        assertEquals(0f, InterludeMotion.dotFraction(gap, 6_000, 2), 0f)
    }

    @Test fun placementClockSleepsBetweenGapsWithoutTruncatingTheLastStaggerOrSeek() {
        val gaps = listOf(gap(0, 8_000), gap(30_000, 10_000))
        val positions = listOf(0L, 1L, 7_499L, 7_500L, 7_700L, 8_250L, 8_400L, 8_413L,
            15_000L, 29_999L, 30_000L, 30_300L, 39_500L, 40_400L, 40_413L, 50_000L)
        for (position in positions + positions.reversed()) {
            val sampled = instrumentalDisplacementPosition(gaps, position)
            for (row in 0..12) {
                assertEquals(
                    evaluateInstrumentalDisplacementFraction(gaps, row, position).toRawBits(),
                    evaluateInstrumentalDisplacementFraction(gaps, row, sampled).toRawBits(),
                )
            }
        }
        assertEquals(-1L, instrumentalDisplacementPosition(gaps, 15_000L))
        assertEquals(8_400L, instrumentalDisplacementPosition(gaps, 8_400L))
        assertEquals(-1L, instrumentalDisplacementPosition(emptyList(), 1_000L))
    }
}
