package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class BackdropRecoveryTest {
    @Test fun consecutiveRendererFailuresAreBoundedAndBackOff() {
        val recovery = BackdropRecovery()
        assertEquals(250L, recovery.retryDelayMs())
        assertEquals(1_000L, recovery.retryDelayMs())
        assertEquals(4_000L, recovery.retryDelayMs())
        repeat(20) { assertNull(recovery.retryDelayMs()) }
    }

    @Test fun aSuccessfulFrameOrNewSurfaceAllowsAFutureIndependentRecovery() {
        val recovery = BackdropRecovery()
        repeat(4) { recovery.retryDelayMs() }
        recovery.succeeded()
        assertEquals(250L, recovery.retryDelayMs())
    }
}
