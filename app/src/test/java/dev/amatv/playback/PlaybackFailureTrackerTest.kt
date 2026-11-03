package dev.amatv.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureTrackerTest {
    @Test fun pollingTheSameFailureDoesNotConsumeEveryRetryBeforeTheDelay() {
        val tracker = PlaybackFailureTracker()
        assertTrue(tracker.shouldHandle(1, true))
        repeat(100) { assertFalse(tracker.shouldHandle(1, true)) }
        assertTrue(tracker.shouldHandle(2, true))
    }
    @Test fun aRecoveredStreamCanReportANewFailureInTheSameGeneration() {
        val tracker = PlaybackFailureTracker()
        assertTrue(tracker.shouldHandle(1, true))
        assertFalse(tracker.shouldHandle(1, false))
        assertTrue(tracker.shouldHandle(1, true))
    }
    @Test fun explicitResetTransfersFailureOwnershipWithoutBlockingRecovery() {
        val tracker = PlaybackFailureTracker()
        tracker.shouldHandle(1, true)
        tracker.reset()
        assertTrue(tracker.shouldHandle(1, true))
    }
}
