package dev.amatv.playback

import org.junit.Assert.*
import org.junit.Test

class ContextContinuationResumeIntentTest {
    @Test fun advancesExactlyOnceAfterExplicitPlayWhenNextPageArrives() {
        val intent = ContextContinuationResumeIntent()
        assertFalse(intent.consumeIfReady(1, 5, true, false, true))
        intent.request(1, 5)
        assertFalse(intent.consumeIfReady(1, 5, false, false, true))
        assertTrue(intent.isPending)
        assertTrue(intent.consumeIfReady(1, 5, true, false, true))
        assertFalse(intent.consumeIfReady(1, 5, true, false, true))
    }
    @Test fun pauseAndLatePageCannotStartPlayback() {
        val intent = ContextContinuationResumeIntent()
        intent.request(1, 5)
        intent.cancel()
        assertFalse(intent.consumeIfReady(1, 5, true, false, true))
        intent.request(1, 5)
        assertFalse(intent.consumeIfReady(1, 5, true, false, false))
        assertFalse(intent.isPending)
    }
    @Test fun differentSessionOrDuplicateOccurrenceCannotConsumeOldIntent() {
        val intent = ContextContinuationResumeIntent()
        intent.request(1, 5)
        assertFalse(intent.consumeIfReady(2, 5, true, false, true))
        intent.request(1, 5)
        assertFalse(intent.consumeIfReady(1, 6, true, false, true))
    }
    @Test fun emptyFinalPageStopsInsteadOfReplayingOldContext() {
        val intent = ContextContinuationResumeIntent()
        intent.request(1, 5)
        assertFalse(intent.consumeIfReady(1, 5, false, true, true))
        assertFalse(intent.isPending)
    }
}
