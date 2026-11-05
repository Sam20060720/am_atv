package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusScrollPlacementTest {
    @Test fun predictionStaysAtOneDestinationWhileTheRowScrolls() {
        // TV pivot is at x=300. Content and remaining scroll move together.
        for (consumed in 0..480 step 16) {
            val currentLeft = 780f - consumed
            val remaining = reachableFocusScroll(currentLeft - 300f, null, null, 1000, 28)
            assertEquals(300f, currentLeft - remaining, 0f)
        }
    }

    @Test fun beginningDoesNotPredictPastTheReachableStart() {
        assertEquals(0f, reachableFocusScroll(-300f, 0, null, 1000, 28), 0f)
        assertEquals(-80f, reachableFocusScroll(-300f, -80, null, 1000, 28), 0f)
    }

    @Test fun lastCardUsesTheReachableEndRatherThanAnImpossiblePivot() {
        assertEquals(148f, reachableFocusScroll(500f, null, 1120, 1000, 28), 0f)
        assertEquals(0f, reachableFocusScroll(500f, null, 972, 1000, 28), 0f)
    }

    @Test fun mirroredForecastClampsAtTheSameLogicalEndpoints() {
        // In RTL, scrolling toward logical end moves the physical target right.
        assertEquals(0f, reachableFocusScroll(300f, 0, null, 1000, 28, isRtl = true), 0f)
        assertEquals(80f, reachableFocusScroll(300f, -80, null, 1000, 28, isRtl = true), 0f)
        assertEquals(-148f, reachableFocusScroll(-500f, null, 1120, 1000, 28, isRtl = true), 0f)
        for (requested in listOf(-500f, -80f, 0f, 40f, 500f)) {
            assertEquals(-reachableFocusScroll(requested, -80, 1120, 1000, 28),
                reachableFocusScroll(-requested, -80, 1120, 1000, 28, isRtl = true), 0f)
        }
    }

    @Test fun shortRowHasNoPredictedScrollInEitherDirection() {
        assertEquals(0f, reachableFocusScroll(450f, 0, 600, 1000, 28), 0f)
        assertEquals(0f, reachableFocusScroll(-300f, 0, 600, 1000, 28), 0f)
    }
}
