package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimePlacementTest {
    @Test fun elapsedFollowsPlayheadUntilItReachesTheReservedTrailingTime() {
        assertEquals(0, playbackTimePlacement(400, 30, 40, 8, 0f).elapsedX)
        assertEquals(185, playbackTimePlacement(400, 30, 40, 8, 0.5f).elapsedX)
        val end = playbackTimePlacement(400, 30, 40, 8, 1f)
        assertEquals(322, end.elapsedX)
        assertEquals(360, end.remainingX)
        assertTrue(end.showRemaining)
    }

    @Test fun allPlayheadPositionsReserveMeasuredTextWidthsAndGap() {
        // Include long-hour timestamps, larger fonts and narrow player columns.
        for (width in listOf(80, 240, 400, 960)) {
            for (elapsed in listOf(30, 55, 70)) {
                for (remaining in listOf(35, 60, 75)) {
                    for (step in 0..100) {
                        val result = playbackTimePlacement(width, elapsed, remaining, 8, step / 100f)
                        assertTrue(result.elapsedX >= 0)
                        assertTrue(result.elapsedX + elapsed <= width)
                        if (result.showRemaining) {
                            assertTrue(result.elapsedX + elapsed + 8 <= result.remainingX)
                            assertEquals(width, result.remainingX + remaining)
                        }
                    }
                }
            }
        }
    }

    @Test fun exactFitKeepsBothTimesAndInsufficientWidthKeepsOnlyElapsed() {
        val exact = playbackTimePlacement(78, 30, 40, 8, 1f)
        assertTrue(exact.showRemaining)
        assertEquals(0, exact.elapsedX)
        val narrow = playbackTimePlacement(77, 30, 40, 8, 1f)
        assertFalse(narrow.showRemaining)
        assertEquals(47, narrow.elapsedX)
    }

    @Test fun unknownDurationHasNoTrailingReservation() {
        val result = playbackTimePlacement(100, 30, 0, 8, 1f)
        assertFalse(result.showRemaining)
        assertEquals(70, result.elapsedX)
    }

    @Test fun transientOutOfRangeClockAndZeroWidthStayWithinBounds() {
        assertEquals(0, playbackTimePlacement(100, 30, 40, 8, -1f).elapsedX)
        assertEquals(22, playbackTimePlacement(100, 30, 40, 8, 2f).elapsedX)
        assertEquals(0, playbackTimePlacement(100, 30, 40, 8, Float.NaN).elapsedX)
        assertEquals(PlaybackTimePlacement(0, 0, false), playbackTimePlacement(0, 30, 40, 8, 1f))
    }
}
