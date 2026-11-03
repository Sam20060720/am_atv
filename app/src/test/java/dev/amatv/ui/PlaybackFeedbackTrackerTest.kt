package dev.amatv.ui

import dev.amatv.integration.PlaybackFailureKind
import org.junit.Assert.*
import org.junit.Test

class PlaybackFeedbackTrackerTest {
    private val preparing = PlaybackFeedbackInput(1, true, false, false, true)

    @Test fun shortPrepareDoesNotFlashAndLongPrepareRevealsOnce() {
        val tracker = PlaybackFeedbackTracker()
        assertEquals(PlaybackFeedbackPhase.Hidden, tracker.update(preparing, 100).phase)
        assertEquals(1L, tracker.remainingRevealDelayMs(1_599))
        assertEquals(PlaybackFeedbackPhase.Hidden, tracker.update(preparing, 1_599).phase)
        assertEquals(PlaybackFeedbackPhase.Preparing, tracker.update(preparing, 1_600).phase)
        assertEquals(0L, tracker.remainingRevealDelayMs(1_650))
    }

    @Test fun aDifferentSelectionGetsItsOwnWaitingWindow() {
        val tracker = PlaybackFeedbackTracker()
        tracker.update(preparing, 0)
        assertTrue(tracker.update(preparing, 1_501).visible)
        assertFalse(tracker.update(preparing.copy(requestKey = 2), 1_502).visible)
        assertEquals(1_500L, tracker.remainingRevealDelayMs(1_502))
    }

    @Test fun playingAndPauseBothClearPendingWaitWithoutALaterReveal() {
        val tracker = PlaybackFeedbackTracker()
        tracker.update(preparing, 0)
        assertFalse(tracker.update(preparing.copy(isPlaying = true, isPrepared = true, isBuffering = false), 100).visible)
        assertNull(tracker.remainingRevealDelayMs(1_000))
        assertFalse(tracker.update(preparing.copy(playRequested = false), 1_001).visible)
        assertNull(tracker.remainingRevealDelayMs(2_000))
    }

    @Test fun aRealBufferingFlagCanRevealEvenWhileSdkStillReportsPlaying() {
        val tracker = PlaybackFeedbackTracker()
        val buffering = preparing.copy(isPrepared = true, isPlaying = true)
        tracker.update(buffering, 0)
        val shown = tracker.update(buffering, 1_500)
        assertEquals(PlaybackFeedbackPhase.Buffering, shown.phase)
        assertTrue(shown.busy)
    }

    @Test fun waitingForNextIsPreparingRatherThanBufferingTheEndedSong() {
        val tracker = PlaybackFeedbackTracker()
        val waiting = preparing.copy(isPrepared = true, waitingForNext = true)
        tracker.update(waiting, 0)
        assertEquals(PlaybackFeedbackPhase.Preparing, tracker.update(waiting, 1_500).phase)
    }

    @Test fun reconnectAndTerminalErrorsAreImmediateAndHaveCorrectRecovery() {
        val tracker = PlaybackFeedbackTracker()
        assertEquals(PlaybackFeedbackPhase.Reconnecting,
            tracker.update(preparing.copy(isReconnecting = true, message = "Retry in 3"), 0).phase)
        val auth = tracker.update(preparing.copy(playRequested = false, failureKind = PlaybackFailureKind.Authorization, message = "Sign in"), 10)
        assertEquals(PlaybackFeedbackPhase.Error, auth.phase)
        assertEquals(PlaybackRecoveryAction.SignIn, auth.recoveryAction)
        val network = tracker.update(preparing.copy(playRequested = false, failureKind = PlaybackFailureKind.Network, message = "Disconnected"), 20)
        assertEquals(PlaybackRecoveryAction.Retry, network.recoveryAction)
        val unavailable = tracker.update(preparing.copy(playRequested = false, failureKind = PlaybackFailureKind.TrackUnavailable, message = "Unavailable"), 30)
        assertEquals(PlaybackRecoveryAction.None, unavailable.recoveryAction)
    }

    @Test fun staleErrorClassificationCannotTurnNewPrepareIntoFailure() {
        val tracker = PlaybackFeedbackTracker()
        val retrying = preparing.copy(failureKind = PlaybackFailureKind.Network, message = "Previous failure")
        assertFalse(tracker.update(retrying, 0).visible)
        assertEquals(PlaybackFeedbackPhase.Preparing, tracker.update(retrying, 1_500).phase)
    }

    @Test fun resetPreventsAnOffscreenWaitFromAppearingImmediatelyOnReturn() {
        val tracker = PlaybackFeedbackTracker()
        tracker.update(preparing, 0)
        tracker.reset()
        assertFalse(tracker.update(preparing, 5_000).visible)
        assertEquals(1_500L, tracker.remainingRevealDelayMs(5_000))
        assertFalse(tracker.update(preparing, 4_999).visible)
    }
}
