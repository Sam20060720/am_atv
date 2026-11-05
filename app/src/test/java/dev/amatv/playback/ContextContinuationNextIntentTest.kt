package dev.amatv.playback

import org.junit.Assert.*
import org.junit.Test

class ContextContinuationNextIntentTest {
    @Test fun failedManualNextAdvancesOnceAfterRetryWithoutReplayingTheAnchor() {
        val intent = ContextContinuationNextIntent()
        intent.request(1, 5)
        intent.fail(1, 5)
        assertTrue(intent.failed)
        assertTrue(intent.matches(1, 5))
        assertFalse(intent.consumeIfReady(1, 5, false, false))
        assertTrue(intent.consumeIfReady(1, 5, true, false))
        assertFalse(intent.consumeIfReady(1, 5, true, false))
    }

    @Test fun pausePreviousReplacementOrTerminalEmptyConsumesTheOldRequest() {
        val intent = ContextContinuationNextIntent()
        intent.request(1, 5)
        intent.cancel()
        assertFalse(intent.consumeIfReady(1, 5, true, false))
        intent.request(1, 5)
        intent.cancelIfReplaced(1, 6)
        assertFalse(intent.consumeIfReady(1, 5, true, false))
        intent.request(1, 5)
        assertFalse(intent.consumeIfReady(2, 5, true, false))
        intent.request(1, 5)
        assertFalse(intent.consumeIfReady(1, 5, false, true))
        assertFalse(intent.matches(1, 5))
    }
}
