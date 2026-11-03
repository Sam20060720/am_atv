package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class MotionOutputLifecycleTest {
    @Test fun reentrantVoidClearCannotReleaseStillConsumedOutput() {
        val lifecycle = MotionOutputLifecycle()
        lifecycle.attached()
        assertFalse(lifecycle.releaseOnce())
        assertTrue(lifecycle.beginClear())
        // A synchronous onPlayerError may reenter after the public player has
        // already nulled its output, even though native detach timed out.
        assertFalse(lifecycle.beginClear())
        assertFalse(lifecycle.releaseOnce())
        assertFalse(lifecycle.released)
    }

    @Test fun timeoutRetainsOutputUntilLatePlaybackTargetConfirmsThenReleasesExactlyOnce() {
        val lifecycle = MotionOutputLifecycle()
        lifecycle.attached()
        lifecycle.beginClear()
        assertFalse(lifecycle.releaseOnce()) // BlockUntilDelivered timed out, not an ACK.
        assertTrue(lifecycle.playbackMarkerDelivered())
        assertTrue(lifecycle.releaseOnce())
        assertFalse(lifecycle.playbackMarkerDelivered()) // Late duplicate callback.
        assertFalse(lifecycle.releaseOnce())
    }

    @Test fun oldAcknowledgmentCannotConfirmADifferentOutputOccurrence() {
        val old = MotionOutputLifecycle().apply { attached(); beginClear() }
        val incoming = MotionOutputLifecycle().apply { attached(); beginClear() }
        assertTrue(old.playbackMarkerDelivered())
        assertTrue(old.releaseOnce())
        assertFalse(incoming.releaseOnce())
        assertFalse(incoming.released)
    }

    @Test fun voidReleaseIsNotProofButActualPlaybackThreadTerminationCanConfirmPendingDetach() {
        val lifecycle = MotionOutputLifecycle().apply { attached(); beginClear() }
        assertFalse(lifecycle.releaseOnce()) // Normal return or release timeout alike.
        lifecycle.playbackThreadTerminated()
        assertTrue(lifecycle.releaseOnce())
        assertFalse(lifecycle.releaseOnce())
    }

    @Test fun neverAttachedSurfaceNeedsNoCodecAcknowledgment() {
        val lifecycle = MotionOutputLifecycle()
        assertFalse(lifecycle.playbackMarkerDelivered())
        assertTrue(lifecycle.releaseOnce())
        assertFalse(lifecycle.releaseOnce())
    }
}
