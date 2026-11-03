package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class StationReconnectPolicyTest {
    @Test fun aNetworkFailureGetsOnlyTwoIncreasingSameStationReconnects() {
        val first = plan(failures = 0)!!
        val second = plan(failures = 1)!!
        assertEquals("ra.123", first.stationId)
        assertEquals("ra.123", second.stationId)
        assertEquals(1, first.attempt)
        assertEquals(2, second.attempt)
        assertEquals(3_000L, first.delayMs)
        assertEquals(6_000L, second.delayMs)
        assertNull(plan(failures = 2))
        assertNull(plan(failures = -1))
    }

    @Test fun authorizationUnsupportedAndOtherTerminalFailuresNeverAutoReconnect() {
        for (kind in PlaybackFailureKind.entries.filterNot { it == PlaybackFailureKind.Network }) {
            assertNull(plan(kind = kind))
        }
    }

    @Test fun pauseOrAChangedSelectionMakesThePendingReconnectIneligible() {
        val pending = plan()!!
        assertTrue(pending.matches("ra.123", 42L, true))
        assertFalse(pending.matches("ra.123", 42L, false))
        assertFalse(pending.matches("ra.456", 42L, true))
        // Selecting the same station again is still a new user intent.
        assertFalse(pending.matches("ra.123", 43L, true))
        assertFalse(pending.matches(null, 42L, true))
    }

    @Test fun aPausedUserOrMissingStationDoesNotStartABackgroundRequest() {
        assertNull(plan(playing = false))
        assertNull(StationReconnectPolicy.plan(null, 42L, 0, PlaybackFailureKind.Network, true))
        assertNull(StationReconnectPolicy.plan(" ", 42L, 0, PlaybackFailureKind.Network, true))
    }

    @Test fun liveRetryAlwaysStartsAtLiveEdgeRegardlessOfOldBufferPosition() {
        repeat(2) { assertEquals(0L, plan(failures = it)!!.resumePositionMs) }
        assertEquals(0L, StationReconnectPolicy.plan("ra.live", 42L, 0,
            PlaybackFailureKind.Network, true, isLiveStream = true, positionMs = 65_000L)!!.resumePositionMs)
    }

    @Test fun finiteEpisodeReconnectRetainsItsPositionInsteadOfStartingOver() {
        val episode = StationReconnectPolicy.plan("ra.episode", 42L, 0,
            PlaybackFailureKind.Network, true, isLiveStream = false, positionMs = 65_000L)!!
        assertEquals("ra.episode", episode.stationId)
        assertEquals(65_000L, episode.resumePositionMs)
        assertTrue(episode.matches("ra.episode", 42L, true))
        assertFalse(episode.matches("ra.episode", 42L, false))
        assertEquals(0L, StationReconnectPolicy.plan("ra.episode", 42L, 0,
            PlaybackFailureKind.Network, true, isLiveStream = false, positionMs = -1L)!!.resumePositionMs)
    }

    @Test fun playingWithoutProgressExhaustsRetriesAcrossRepeatedWatchdogFailures() {
        val health = StationReconnectPolicy.PlaybackHealth()
        val watchdog = StationProgressWatchdog()
        var failures = 0
        repeat(3) { round ->
            val start = round * 31_000L
            health.onSnapshot(65_000L, start, isPlaying = false, isPrepared = false)
            var stalled = false
            for (elapsed in 0L..30_000L step 250L) {
                if (health.onSnapshot(65_000L, start + elapsed, isPlaying = true, isPrepared = true)) failures = 0
                stalled = watchdog.observe(round.toLong(), prepared = true, desired = true, playing = true,
                    buffering = false, positionMs = 65_000L, nowMs = start + elapsed)
            }
            assertTrue(stalled)
            val reconnect = plan(failures)
            if (round < 2) {
                assertEquals(round + 1, reconnect!!.attempt)
                failures = reconnect.attempt
            } else assertNull(reconnect)
        }
    }

    @Test fun onlyRealProgressAcrossTheHealthyWindowRestoresRetries() {
        val health = StationReconnectPolicy.PlaybackHealth()
        for (elapsed in 0L..<10_000L step 250L) {
            assertFalse(health.onSnapshot(65_000L + elapsed, 100_000L + elapsed, true, true))
        }
        assertTrue(health.onSnapshot(75_000L, 110_000L, true, true))
        // A repeated Playing callback must not keep reporting healthy while stalled.
        assertFalse(health.onSnapshot(75_000L, 120_000L, true, true))
        assertFalse(health.onSnapshot(75_250L, 120_250L, true, true))
    }

    @Test fun pauseAndPreparationBreakTheHealthyWindow() {
        for ((playing, prepared) in listOf(false to true, true to false)) {
            val health = StationReconnectPolicy.PlaybackHealth()
            for (elapsed in 0L..9_000L step 250L) {
                assertFalse(health.onSnapshot(elapsed, elapsed, true, true))
            }
            assertFalse(health.onSnapshot(9_000L, 9_250L, playing, prepared))
            for (elapsed in 0L..<10_000L step 250L) {
                assertFalse(health.onSnapshot(9_000L + elapsed, 9_500L + elapsed, true, true))
            }
            assertTrue(health.onSnapshot(19_000L, 19_500L, true, true))
        }
    }

    @Test fun aRadioSongClockRestartNeedsItsOwnHealthyWindow() {
        val health = StationReconnectPolicy.PlaybackHealth()
        for (elapsed in 0L..9_000L step 250L) {
            assertFalse(health.onSnapshot(65_000L + elapsed, 100_000L + elapsed, true, true))
        }
        for (elapsed in 0L..<10_000L step 250L) {
            assertFalse(health.onSnapshot(elapsed, 109_250L + elapsed, true, true))
        }
        assertTrue(health.onSnapshot(10_000L, 119_250L, true, true))
    }

    @Test fun duplicateCallbacksDoNotEraseRecentRealProgressButALongGapDoes() {
        val health = StationReconnectPolicy.PlaybackHealth()
        for (elapsed in 0L..9_000L step 250L) {
            assertFalse(health.onSnapshot(elapsed, elapsed, true, true))
        }
        assertFalse(health.onSnapshot(9_000L, 9_250L, true, true))
        assertFalse(health.onSnapshot(9_000L, 9_500L, true, true))
        assertTrue(health.onSnapshot(10_000L, 10_000L, true, true))
        assertFalse(health.onSnapshot(12_000L, 12_000L, true, true))
    }

    @Test fun aSelectionResetOrBackwardClockCannotReuseEarlierHealth() {
        for (resetSelection in listOf(false, true)) {
            val health = StationReconnectPolicy.PlaybackHealth()
            for (elapsed in 0L..9_000L step 250L) {
                assertFalse(health.onSnapshot(elapsed, 100_000L + elapsed, true, true))
            }
            if (resetSelection) health.reset()
            val start = if (resetSelection) 110_000L else 50_000L
            for (elapsed in 0L..<10_000L step 250L) {
                assertFalse(health.onSnapshot(10_000L + elapsed, start + elapsed, true, true))
            }
            assertTrue(health.onSnapshot(20_000L, start + 10_000L, true, true))
        }
    }

    private fun plan(failures: Int = 0, kind: PlaybackFailureKind = PlaybackFailureKind.Network,
        playing: Boolean = true) = StationReconnectPolicy.plan("ra.123", 42L, failures, kind, playing)
}
