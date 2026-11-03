package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class SpectrumCaptureRetryTest {
    @Test fun failedRoutesBackOffInsteadOfRecreatingAnEffectEveryProbe() {
        val retry = SpectrumCaptureRetry()
        retry.session(17)
        var now = 10_000L
        for (delay in listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L)) {
            assertTrue(retry.canAttempt(now))
            retry.failed(now)
            assertFalse(retry.canAttempt(now + delay - 1))
            assertTrue(retry.canAttempt(now + delay))
            now += delay
        }
    }

    @Test fun aNewPlayerSessionDoesNotInheritThePreviousRoutesFailureDelay() {
        val retry = SpectrumCaptureRetry()
        retry.session(17)
        repeat(5) { retry.failed(10_000L) }
        assertFalse(retry.canAttempt(10_001L))
        retry.session(18)
        assertTrue(retry.canAttempt(10_001L))
        retry.failed(10_001L)
        assertTrue(retry.canAttempt(10_501L))
    }

    @Test fun aRealSilentCallbackRestoresTheNormalRetryAllowance() {
        val retry = SpectrumCaptureRetry()
        retry.session(17)
        repeat(5) { retry.failed(10_000L) }
        retry.captured()
        assertTrue(retry.canAttempt(10_001L))
        retry.failed(10_001L)
        assertFalse(retry.canAttempt(10_500L))
        assertTrue(retry.canAttempt(10_501L))
    }
}
