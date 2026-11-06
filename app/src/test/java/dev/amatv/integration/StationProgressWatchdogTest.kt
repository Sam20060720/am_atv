package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class StationProgressWatchdogTest {
    @Test fun anObservedStalledLiveBufferEventuallyBecomesRecoverable() {
        val w = StationProgressWatchdog()
        assertFalse(w.observe(1,true,true,false,true,100,0))
        assertFalse(w.observe(1,true,true,false,true,100,29_999))
        assertTrue(w.observe(1,true,true,false,true,100,30_000))
    }
    @Test fun genuinePlaybackProgressAndWindowRebasingResetTheDeadline() {
        val w = StationProgressWatchdog()
        w.observe(1,true,true,true,false,10_000,0)
        assertFalse(w.observe(1,true,true,true,false,11_000,29_999))
        assertFalse(w.observe(1,true,true,true,false,100,50_000))
        assertFalse(w.observe(1,true,true,true,false,100,79_999))
    }
    @Test fun explicitPauseAndAudioFocusPauseNeverTriggerNetworkRecovery() {
        val w = StationProgressWatchdog()
        w.observe(1,true,true,true,false,100,0)
        assertFalse(w.observe(1,true,false,false,false,100,100_000))
        assertFalse(w.observe(1,true,true,false,false,100,200_000))
    }
    @Test fun unexpectedIdleAfterPlaybackCannotKeepResettingTheProgressDeadline() {
        val w = StationProgressWatchdog()
        assertFalse(w.observe(1,true,true,true,false,100,0))
        assertFalse(w.observe(1,true,true,false,false,100,1_000,idle = true))
        assertFalse(w.observe(1,true,true,false,false,100,29_999,idle = true))
        assertTrue(w.observe(1,true,true,false,false,100,30_000,idle = true))
        // A focus pause is not unexpected idle, and an explicit user Pause
        // wins even if the SDK still reports a stale None/Stopped snapshot.
        assertFalse(w.observe(1,true,true,false,false,100,60_000))
        assertFalse(w.observe(1,true,false,false,false,100,100_000,idle = true))
    }
    @Test fun aNewGenerationCannotInheritAnOldStall() {
        val w = StationProgressWatchdog()
        w.observe(1,true,true,false,true,0,0)
        assertFalse(w.observe(2,true,true,false,true,0,100_000))
    }
}
