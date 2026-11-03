package dev.amatv.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackRuntimeRetentionTest {
    @Test fun activePlaybackSurvivesWithoutAUi() {
        val retention = PlaybackRuntimeRetention()
        assertNull(retention.remainingIdleMs(false, true, 0))
        assertNull(retention.remainingIdleMs(false, true, 999_999))
    }
    @Test fun aQuickConfigurationReplacementCancelsTheIdleCountdown() {
        val retention = PlaybackRuntimeRetention()
        assertEquals(30_000L, retention.remainingIdleMs(false, false, 0))
        assertEquals(29_000L, retention.remainingIdleMs(false, false, 1_000))
        assertNull(retention.remainingIdleMs(true, false, 1_001))
        assertEquals(30_000L, retention.remainingIdleMs(false, false, 2_000))
    }
    @Test fun pausedUnownedEngineEventuallyReleasesButBackwardClockDoesNotExpireIt() {
        val retention = PlaybackRuntimeRetention()
        assertEquals(30_000L, retention.remainingIdleMs(false, false, 500))
        assertEquals(30_000L, retention.remainingIdleMs(false, false, 499))
        assertEquals(0L, retention.remainingIdleMs(false, false, 30_500))
    }
}
