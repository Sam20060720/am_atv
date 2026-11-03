package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeResumeRecoveryTest {
    private fun armed() = NativeResumeRecovery().apply { arm(7L, "song", 11L, 67_000L, prepared = true, paused = true) }
    private fun NativeResumeRecovery.fail(selection: Long = 7L, id: String = "song", generation: Long = 11L,
        kind: PlaybackFailureKind = PlaybackFailureKind.Unexpected, wantsPlay: Boolean = true) =
        consumeFailure(selection, id, generation, kind, wantsPlay)

    @Test fun failedResumeRestoresSavedCursorEvenWhenSdkResetsItsPositionToZero() {
        val recovery = armed()
        recovery.observePlaying(7L, "song", 11L, 0L, isPlaying = false)
        assertEquals(67_000L, recovery.fail())
        assertNull(recovery.fail())
        assertNull(recovery.fail(selection = 8L, generation = 12L))
    }

    @Test fun successRequiresActualForwardProgressInsteadOfOnlyAnEarlyPlayingFlag() {
        val pending = armed()
        pending.observePlaying(7L, "song", 11L, 67_000L, isPlaying = true)
        pending.observePlaying(7L, "song", 11L, 67_249L, isPlaying = true)
        assertEquals(67_000L, pending.fail())

        val successful = armed()
        successful.observePlaying(7L, "song", 11L, 67_250L, isPlaying = true)
        assertNull(successful.fail())
    }

    @Test fun pausedPositionUpdatesCannotClaimSuccessfulResume() {
        val recovery = armed()
        recovery.observePlaying(7L, "song", 11L, 70_000L, isPlaying = false)
        assertEquals(67_000L, recovery.fail())
    }

    @Test fun oldSelectionItemOrNativeGenerationCannotConsumeTheCurrentResume() {
        for ((selection, id, generation) in listOf(Triple(6L, "song", 11L), Triple(7L, "other", 11L), Triple(7L, "song", 10L))) {
            val recovery = armed()
            assertNull(recovery.fail(selection, id, generation))
            recovery.observePlaying(selection, id, generation, 70_000L, isPlaying = true)
            assertEquals(67_000L, recovery.fail())
        }
    }

    @Test fun explicitPauseOrSeekCancelsRecoveryRatherThanReplayingAnOldCursor() {
        val recovery = armed()
        recovery.cancel()
        assertNull(recovery.fail())
        val intentLost = armed()
        assertNull(intentLost.fail(wantsPlay = false))
        assertNull(intentLost.fail())
    }

    @Test fun authorizationNetworkUnavailableAndPositionFailuresDoNotBecomeUnexpectedRetries() {
        for (kind in PlaybackFailureKind.entries.filterNot { it == PlaybackFailureKind.Unexpected }) {
            val recovery = armed()
            assertNull(recovery.fail(kind = kind))
            assertNull(recovery.fail())
        }
    }

    @Test fun unpreparedPlayingOrInvalidNativeSessionCannotArmResumeRecovery() {
        data class Arm(val id: String? = "song", val generation: Long = 11L, val position: Long = 67_000L,
            val prepared: Boolean = true, val paused: Boolean = true)
        val cases = listOf(
            Arm(prepared = false), Arm(paused = false), Arm(generation = 0L),
            Arm(id = null), Arm(id = ""), Arm(position = -1L),
        )
        for (case in cases) {
            val recovery = NativeResumeRecovery()
            recovery.arm(7L, case.id, case.generation, case.position, case.prepared, case.paused)
            assertNull(recovery.fail())
        }
    }

    @Test fun focusLossAfterConfirmedPlaybackNeverRearmsAutomaticReplay() {
        val recovery = armed()
        recovery.observePlaying(7L, "song", 11L, 67_500L, isPlaying = true)
        recovery.observePlaying(7L, "song", 11L, 67_500L, isPlaying = false)
        assertNull(recovery.fail())
    }

    @Test fun aNewExplicitResumeReplacesItsPredecessorWithoutGivingOldCallbacksControl() {
        val recovery = armed()
        recovery.arm(8L, "song", 12L, 80_000L, prepared = true, paused = true)
        assertNull(recovery.fail())
        recovery.observePlaying(7L, "song", 11L, 90_000L, isPlaying = true)
        assertEquals(80_000L, recovery.fail(selection = 8L, generation = 12L))
    }
}
