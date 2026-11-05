package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class NativePlaybackIntentTest {
    @Test fun pauseAndImmediatePlayAfterASeekUseTheNewTargetAsRecoveryBaseline() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0, true, 0)
        intent.observe(probe(now = 100))
        intent.observe(probe(now = 500, position = 1_000, playing = true))
        intent.requestSeek(50_000)
        intent.requestPlaying(false)
        intent.requestPlaying(true)
        intent.observe(probe(now = 1_000, position = 50_000, playing = true))
        assertFalse(intent.hasStartedPlayback)
        intent.observe(probe(now = 1_500, position = 50_300, playing = true))
        assertTrue(intent.hasStartedPlayback)
        assertTrue(intent.observe(probe(now = 50_000, position = 50_300)).commands.isEmpty())
    }

    @Test fun playingFlagWithAnUnmovingResumedCursorStillExhaustsRecoveryDeadline() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 67_000, true, 0)
        intent.observe(probe(now = 100, position = 67_000, playing = true))
        val stuck = intent.observe(probe(now = 45_101, position = 67_000, playing = true))
        assertEquals(PlaybackFailureKind.Network, stuck.failure)
        assertEquals(listOf(NativePlaybackCommand.Pause), stuck.commands)
    }

    @Test fun explicitPauseSuspendsLoadingAndResumeGetsANewBoundedReadinessBudget() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0, true, 0)
        intent.requestPlaying(false)
        val noItem = probe(now = 120_000).copy(hasCurrentItem = false)
        assertNull(intent.observe(noItem).failure)
        intent.requestPlaying(true)
        assertNull(intent.observe(noItem.copy(nowMs = 150_000)).failure)
        assertEquals(PlaybackFailureKind.Network, intent.observe(noItem.copy(nowMs = 210_001)).failure)
    }

    @Test fun explicitPauseAlsoSuspendsAnOutstandingRestoreSeekDeadline() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 67_000, true, 0)
        intent.observe(probe(now = 100))
        intent.requestPlaying(false)
        assertNull(intent.observe(probe(now = 120_000)).failure)
        intent.requestPlaying(true)
        assertNull(intent.observe(probe(now = 150_000)).failure)
        assertNull(intent.observe(probe(now = 153_000)).failure)
        assertNull(intent.observe(probe(now = 156_000)).failure)
        assertEquals(PlaybackFailureKind.PositionRestore, intent.observe(probe(now = 166_001)).failure)
    }

    @Test fun lookaheadWaitsForActualProgressAndStopsDuringUserPauseOrBufferRecovery() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 45_000L, true, 0L)
        assertFalse(intent.hasStartedPlayback)
        intent.observe(probe(now = 100L, position = 45_000L))
        assertFalse(intent.hasStartedPlayback)
        intent.observe(probe(now = 200L, position = 45_000L, playing = true))
        assertFalse("A resumed cursor is not proof that audio advanced", intent.hasStartedPlayback)
        intent.observe(probe(now = 300L, position = 45_100L, playing = true))
        assertFalse(intent.hasStartedPlayback)
        intent.observe(probe(now = 500L, position = 45_300L, playing = true))
        assertTrue(intent.hasStartedPlayback)
        intent.requestPlaying(false)
        assertFalse(intent.hasStartedPlayback)
        intent.requestPlaying(true)
        intent.observe(probe(now = 600L, position = 45_300L, buffering = true))
        assertFalse(intent.hasStartedPlayback)
        intent.observe(probe(now = 700L, position = 45_300L, playing = true))
        assertFalse(intent.hasStartedPlayback)
        intent.observe(probe(now = 1_000L, position = 45_600L, playing = true))
        assertTrue(intent.hasStartedPlayback)
    }

    private fun probe(
        now: Long = 1_000L,
        position: Long = 0L,
        playing: Boolean = false,
        buffering: Boolean = false,
        id: String = "song",
        canSeek: Boolean = true,
    ) = NativePlaybackProbe(id, true, position, 200_000L, playing, buffering, canSeek,
        paused = !playing, ended = false, nowMs = now)

    @Test fun initialStoppedTrackWithNoCanSeekTransitionsToReadyAndPlays() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        val initial = intent.observe(
            NativePlaybackProbe(
                catalogId = "song",
                hasCurrentItem = true,
                positionMs = 0L,
                durationMs = 200_000L,
                playing = false,
                buffering = false,
                canSeek = false,
                paused = false,
                ended = false,
                nowMs = 1_000L,
            ),
        )
        assertTrue(initial.prepared)
        assertEquals(listOf(NativePlaybackCommand.Play), initial.commands)
    }

    @Test fun restoreSeeksSilentlyAndWaitsForPositionAcknowledgementBeforePlaying() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 80_000L, true, 0L)
        val initial = intent.observe(probe())
        assertEquals(listOf(NativePlaybackCommand.Seek(80_000L)), initial.commands)
        assertFalse(initial.prepared)
        assertFalse(initial.playing)
        assertEquals(80_000L, initial.positionMs)
        assertTrue(intent.observe(probe(now = 1_500L)).commands.isEmpty())
        val acknowledged = intent.observe(probe(now = 2_000L, position = 80_000L))
        assertTrue(acknowledged.prepared)
        assertEquals(listOf(NativePlaybackCommand.Play), acknowledged.commands)
        assertFalse(acknowledged.playing)
        assertTrue(intent.observe(probe(now = 2_250L, position = 80_250L, playing = true)).playing)
    }

    @Test fun pausedRestoreNeverStartsAudio() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 50_000L, false, 0L)
        assertEquals(listOf(NativePlaybackCommand.Seek(50_000L)), intent.observe(probe()).commands)
        val restored = intent.observe(probe(position = 50_000L))
        assertTrue(restored.prepared)
        assertTrue(restored.commands.isEmpty())
        assertFalse(restored.playing)
    }

    @Test fun userPauseDuringRestoreSeekWinsOverSavedAutoplay() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 50_000L, true, 0L)
        intent.observe(probe())
        intent.requestPlaying(false)
        assertTrue(intent.observe(probe(position = 50_000L)).commands.isEmpty())
    }

    @Test fun aSameSongRestartCannotReuseOldNonzeroPlaybackPosition() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        val oldPosition = intent.observe(probe(position = 30_000L))
        assertFalse(oldPosition.prepared)
        assertEquals(listOf(NativePlaybackCommand.Seek(0L)), oldPosition.commands)
        assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe(now = 2_000L)).commands)
    }

    @Test fun matchingMetadataIsNotEnoughWhileTheNativeItemIsStillBuffering() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        val pending = intent.observe(probe(buffering = true))
        assertFalse(pending.prepared)
        assertTrue(pending.commands.isEmpty())
        assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe(now = 3_000L)).commands)
    }

    @Test fun readyButPausedAfterBufferGetsBoundedResumeWithoutChangingIntent() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 1_250L, position = 250L, playing = true))
        intent.observe(probe(now = 2_000L, position = 250L, buffering = true))
        val recovered = intent.observe(probe(now = 5_000L, position = 250L))
        assertEquals(listOf(NativePlaybackCommand.Play), recovered.commands)
        assertTrue(intent.observe(probe(now = 5_250L, position = 250L)).commands.isEmpty())
    }

    @Test fun pausingDuringBufferingPreventsAutomaticResumeAfterBuffer() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 2_000L, buffering = true))
        intent.requestPlaying(false)
        val ready = intent.observe(probe(now = 5_000L))
        assertTrue(ready.commands.isEmpty())
        assertFalse(ready.playing)
    }

    @Test fun nextTrackGetsFreshPlayAttemptsWhenBufferingFinishesAfterEarlyAttemptsWereIgnored() {
        val intent = NativePlaybackIntent()
        // The app prepares the next item silently, then applies the current user intent.
        intent.begin("next", 0L, false, 0L)
        assertTrue(intent.observe(probe(id = "next")).commands.isEmpty())
        intent.requestPlaying(true)
        for (now in listOf(1_250L, 4_250L, 7_250L)) {
            assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe(now = now, id = "next")).commands)
        }
        assertTrue(intent.observe(probe(now = 8_000L, id = "next", buffering = true)).commands.isEmpty())
        assertTrue(intent.observe(probe(now = 18_000L, id = "next", buffering = true)).commands.isEmpty())
        val ready = intent.observe(probe(now = 19_000L, id = "next"))
        assertNull(ready.failure)
        assertEquals(listOf(NativePlaybackCommand.Play), ready.commands)
        assertTrue(intent.observe(probe(now = 19_250L, id = "next", position = 250L, playing = true)).playing)
    }

    @Test fun bufferRecoveryStillHasABoundedBudgetRatherThanResettingAtEveryReadyPoll() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        for (now in listOf(1_000L, 4_000L, 7_000L)) intent.observe(probe(now = now))
        intent.observe(probe(now = 8_000L, buffering = true))
        for (now in listOf(9_000L, 12_000L, 15_000L)) {
            assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe(now = now)).commands)
        }
        assertTrue(intent.observe(probe(now = 18_000L)).commands.isEmpty())
        assertNull(intent.observe(probe(now = 30_000L)).failure)
        assertEquals(PlaybackFailureKind.Network, intent.observe(probe(now = 55_000L)).failure)
    }

    @Test fun nativePauseAfterConfirmedPlaybackDoesNotStealFocusOrTimeOut() {
        for (position in listOf(20_000L, 199_000L)) {
            val intent = NativePlaybackIntent()
            intent.begin("song", 0L, true, 0L)
            intent.observe(probe())
            intent.observe(probe(now = 199_750L, position = position - 250L, playing = true))
            intent.observe(probe(now = 200_000L, position = position, playing = true))
            for (now in listOf(201_000L, 205_000L, 280_000L)) {
                val paused = intent.observe(probe(now = now, position = position))
                assertTrue(paused.prepared)
                assertFalse(paused.playing)
                assertFalse(paused.ended)
                assertNull(paused.failure)
                assertTrue(paused.commands.isEmpty())
            }
        }
    }

    @Test fun explicitPlayCanResumeANativePauseWithABoundedRetryBudget() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 19_750L, position = 19_750L, playing = true))
        intent.observe(probe(now = 20_000L, position = 20_000L, playing = true))
        assertTrue(intent.observe(probe(now = 21_000L, position = 20_000L)).commands.isEmpty())
        intent.requestPlaying(true)
        for (now in listOf(22_000L, 25_000L, 28_000L)) {
            assertEquals(listOf(NativePlaybackCommand.Play),
                intent.observe(probe(now = now, position = 20_000L)).commands)
        }
        assertTrue(intent.observe(probe(now = 31_000L, position = 20_000L)).commands.isEmpty())
    }

    @Test fun musicKitCanResumeItsOwnFocusPauseWithoutAnotherAppPlayCommand() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 19_750L, position = 19_750L, playing = true))
        intent.observe(probe(now = 20_000L, position = 20_000L, playing = true))
        assertTrue(intent.observe(probe(now = 21_000L, position = 20_000L)).commands.isEmpty())
        val resumed = intent.observe(probe(now = 25_000L, position = 20_250L, playing = true))
        assertTrue(resumed.playing)
        assertTrue(resumed.commands.isEmpty())
        assertTrue(intent.observe(probe(now = 26_000L, position = 20_250L)).commands.isEmpty())
        intent.requestPlaying(false)
        val unwantedResume = intent.observe(probe(now = 27_000L, position = 20_500L, playing = true))
        assertFalse(unwantedResume.playing)
        assertEquals(listOf(NativePlaybackCommand.Pause), unwantedResume.commands)
    }

    @Test fun transientPlayingAtZeroDoesNotDisableOrResetTheInitialBoundedRetries() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, false, 0L)
        intent.observe(probe(now = 500L))
        intent.requestPlaying(true)
        for (now in listOf(1_000L, 4_000L, 7_000L)) {
            assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe(now = now)).commands)
            assertTrue(intent.observe(probe(now = now + 250L, playing = true)).commands.isEmpty())
        }
        assertTrue(intent.observe(probe(now = 10_000L)).commands.isEmpty())
        assertNull(intent.observe(probe(now = 22_000L)).failure)
        assertEquals(PlaybackFailureKind.Network, intent.observe(probe(now = 47_000L)).failure)
    }

    @Test fun actualInitialProgressDisablesRetriesForASubsequentNativePause() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 1_250L, playing = true))
        intent.observe(probe(now = 1_500L, position = 250L, playing = true))
        for (now in listOf(2_000L, 25_000L)) {
            val paused = intent.observe(probe(now = now, position = 250L))
            assertTrue(paused.commands.isEmpty())
            assertNull(paused.failure)
        }
    }

    @Test fun explicitAndBufferRecoveryRequireProgressFromTheirOwnMiddleOfSongBaseline() {
        for (buffered in listOf(false, true)) {
            val intent = NativePlaybackIntent()
            intent.begin("song", 0L, true, 0L)
            intent.observe(probe())
            intent.observe(probe(now = 1_250L, playing = true))
            intent.observe(probe(now = 1_500L, position = 250L, playing = true))
            intent.observe(probe(now = 20_000L, position = 20_000L, playing = true))
            intent.observe(probe(now = 21_000L, position = 20_000L, buffering = buffered))
            if (!buffered) intent.requestPlaying(true)
            assertEquals(listOf(NativePlaybackCommand.Play),
                intent.observe(probe(now = 22_000L, position = 20_000L)).commands)
            intent.observe(probe(now = 22_250L, position = 20_000L, playing = true))
            assertEquals(listOf(NativePlaybackCommand.Play),
                intent.observe(probe(now = 25_000L, position = 20_000L)).commands)
            intent.observe(probe(now = 25_250L, position = 20_000L, playing = true))
            intent.observe(probe(now = 25_500L, position = 20_250L, playing = true))
            val paused = intent.observe(probe(now = 26_000L, position = 20_250L))
            assertTrue(paused.commands.isEmpty())
            assertNull(paused.failure)
        }
    }

    @Test fun unrelatedOldItemNeverStartsOrMovesTheRequestedSong() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 70_000L, true, 0L)
        val old = intent.observe(probe(id = "old", position = 10_000L, playing = true))
        assertFalse(old.prepared)
        assertFalse(old.playing)
        assertEquals(70_000L, old.positionMs)
        assertTrue(old.commands.isEmpty())
    }

    @Test fun ignoredSeekFailsPausedInsteadOfPlayingFromTheBeginning() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 70_000L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 4_000L))
        intent.observe(probe(now = 7_000L))
        val failure = intent.observe(probe(now = 18_000L))
        assertEquals(PlaybackFailureKind.PositionRestore, failure.failure)
        assertFalse(failure.playing)
        assertEquals(listOf(NativePlaybackCommand.Pause), failure.commands)
    }

    @Test fun repeatedNativeIdleIsBoundedAndIsNotClassifiedAsAnUnavailableSong() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe()).commands)
        intent.observe(probe(now = 4_000L))
        intent.observe(probe(now = 7_000L))
        assertTrue(intent.observe(probe(now = 10_000L)).commands.isEmpty())
        assertNull(intent.observe(probe(now = 22_000L)).failure)
        assertEquals(PlaybackFailureKind.Network, intent.observe(probe(now = 47_000L)).failure)
    }

    @Test fun onlyTheSdkIoTypeIdentifiesANativeNetworkFailure() {
        assertEquals(PlaybackFailureKind.Network, classifyPlaybackFailure(1, null, null))
        assertEquals(PlaybackFailureKind.Network, classifyPlaybackFailure(1, 401, 403))
        assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(null, null, null))
    }

    @Test fun drmLeaseAndAgeVerificationDoNotMeanTheAccountNeedsToSignInAgain() {
        for (type in listOf(2, 4, 6)) {
            assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(type, null, null))
            assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(type, 401, 403))
            // MusicKit ErrorConditionException has this broad IOException superclass.
            assertEquals(PlaybackFailureKind.Unexpected,
                classifyPlaybackFailure(type, 401, 403, java.io.IOException("native component condition")))
        }
    }

    @Test fun queueAndComponentCodesDoNotProveHttpErrorsOrAnUnavailableRecording() {
        for (type in listOf(null, 0, 2, 3, 4, 5, 6, 99)) {
            for (code in listOf(Int.MIN_VALUE, 401, 403, 404, 408, 410, 429, 500, 503)) {
                assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(type, code, null))
                assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(type, null, code))
            }
        }
    }

    @Test fun genuineJavaTransportFailuresRemainRetryableWithoutANativeErrorType() {
        val transportFailure = java.lang.reflect.InvocationTargetException(java.io.IOException("offline"))
        assertEquals(PlaybackFailureKind.Network, classifyPlaybackFailure(null, null, null, transportFailure))
        assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(null, null, null, IllegalStateException("not ready")))
    }

    @Test fun continuousBufferTimeoutIsRetryableNetworkFailureNotTrackFailure() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 2_000L, position = 1_000L, buffering = true))
        val timedOut = intent.observe(probe(now = 63_000L, position = 1_000L, buffering = true))
        assertEquals(PlaybackFailureKind.Network, timedOut.failure)
        assertFalse(timedOut.playing)
        assertEquals(listOf(NativePlaybackCommand.Pause), timedOut.commands)
    }

    @Test fun explicitPauseDuringLongBufferDoesNotCauseARecoveryFailure() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 2_000L, buffering = true))
        intent.requestPlaying(false)
        val paused = intent.observe(probe(now = 90_000L, buffering = true))
        assertNull(paused.failure)
        assertFalse(paused.playing)
        assertTrue(paused.commands.isEmpty())
    }

    @Test fun completedEventRemainsValidWithoutReadingTheStoppedControllersResetPosition() {
        assertTrue(isCompletedPlaybackItem(7L, 7L, true, "song", "song", 42L, 42L, 200_000L, 200_000L))
        assertTrue(isCompletedPlaybackItem(7L, 7L, true, "song", "song", 42L, 42L, 196_500L, 200_000L))
        // Stop/skip before the media end is not a completed song.
        assertFalse(isCompletedPlaybackItem(7L, 7L, true, "song", "song", 42L, 42L, 35_000L, 200_000L))
    }

    @Test fun oldSameSongEndRequiresTheCurrentRequestAndNativeEntry() {
        assertFalse(isCompletedPlaybackItem(6L, 7L, true, "song", "song", 42L, 42L, 200_000L, 200_000L))
        assertFalse(isCompletedPlaybackItem(7L, 7L, true, "song", "song", 43L, 42L, 200_000L, 200_000L))
        assertFalse(isCompletedPlaybackItem(7L, 7L, false, "song", "song", null, 42L, 200_000L, 200_000L))
    }

    @Test fun authoritativeEndSurvivesNativeItemAndPositionCleanupAfterALongSong() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        val ended = intent.observe(probe(now = 200_000L).copy(hasCurrentItem = false, ended = true))
        assertTrue(ended.prepared)
        assertFalse(ended.playing)
        assertNull(ended.failure)
        assertTrue(ended.commands.isEmpty())
        assertTrue(ended.ended)
    }

    @Test fun lostCompletionCallbackIsRecoveredWithoutReplayingTheFinishedSong() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 199_000L, position = 199_000L, playing = true))
        val cleanup = probe(now = 200_000L).copy(hasCurrentItem = false)
        val confirming = intent.observe(cleanup)
        assertFalse(confirming.ended)
        assertEquals(199_000L, confirming.positionMs)
        assertTrue(intent.observe(cleanup.copy(nowMs = 200_500L)).commands.isEmpty())
        assertFalse(intent.observe(cleanup.copy(nowMs = 201_000L)).ended)
        val completed = intent.observe(cleanup.copy(nowMs = 201_500L))
        assertTrue(completed.ended)
        assertTrue(completed.prepared)
        assertNull(completed.failure)
        assertTrue(completed.commands.isEmpty())
        assertEquals(199_000L, completed.positionMs)
        val retainedCompletion = intent.observe(cleanup.copy(nowMs = 230_000L))
        assertTrue(retainedCompletion.ended)
        assertEquals(199_000L, retainedCompletion.positionMs)

        intent.begin("next", 0L, true, 231_000L)
        val nextReady = intent.observe(probe(now = 232_000L, id = "next"))
        assertFalse(nextReady.ended)
        assertEquals(listOf(NativePlaybackCommand.Play), nextReady.commands)
    }

    @Test fun stoppedFinalSegmentWithRetainedItemCanAlsoCompleteWithoutCallback() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 199_000L, position = 199_000L, playing = true))
        assertTrue(intent.observe(probe(now = 200_000L, position = 200_000L)).commands.isEmpty())
        assertFalse(intent.observe(probe(now = 201_000L, position = 200_000L)).ended)
        assertTrue(intent.observe(probe(now = 201_500L, position = 200_000L)).ended)
    }

    @Test fun completionFallbackWaitsForTheUnobservedTailButAnAuthoritativeEndDoesNot() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 198_500L, position = 198_500L, playing = true))
        val stopped = probe(now = 198_750L, position = 198_500L).copy(paused = false)
        assertFalse(intent.observe(stopped).ended)
        assertFalse(intent.observe(stopped.copy(nowMs = 199_750L)).ended)
        assertFalse(intent.observe(stopped.copy(nowMs = 200_500L)).ended)
        assertTrue(intent.observe(stopped.copy(nowMs = 200_750L)).ended)

        intent.begin("song", 0L, true, 201_000L)
        intent.observe(probe(now = 202_000L))
        assertTrue(intent.observe(probe(now = 202_250L).copy(ended = true)).ended)
    }

    @Test fun nearEndBufferingMustResumeInsteadOfBeingMistakenForCompletion() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 199_000L, position = 199_000L, playing = true))
        assertFalse(intent.observe(probe(now = 200_000L, position = 199_000L, buffering = true)).ended)
        val recovered = intent.observe(probe(now = 220_000L, position = 199_000L))
        assertFalse(recovered.ended)
        assertEquals(listOf(NativePlaybackCommand.Play), recovered.commands)
    }

    @Test fun explicitPauseOrSeekAtTheEndInvalidatesAutomaticCompletionEvidence() {
        for (seek in listOf(false, true)) {
            val intent = NativePlaybackIntent()
            intent.begin("song", 0L, true, 0L)
            intent.observe(probe())
            intent.observe(probe(now = 199_000L, position = 199_000L, playing = true))
            if (seek) intent.requestSeek() else intent.requestPlaying(false)
            val idle = intent.observe(probe(now = 200_000L))
            assertFalse(idle.ended)
            assertEquals(if (seek) listOf(NativePlaybackCommand.Play) else emptyList<NativePlaybackCommand>(), idle.commands)
            assertFalse(intent.observe(probe(now = 202_000L)).ended)
        }
    }

    @Test fun midSongIdleOrUnrelatedItemsNeverBecomeCompletedSongs() {
        for (position in listOf(30_000L, 199_000L)) {
            val intent = NativePlaybackIntent()
            intent.begin("song", 0L, true, 0L)
            intent.observe(probe())
            intent.observe(probe(now = 199_000L, position = position, playing = true))
            val idle = probe(now = 200_000L, id = if (position == 199_000L) "other" else "song")
            assertFalse(intent.observe(idle).ended)
            assertFalse(intent.observe(idle.copy(nowMs = 202_000L)).ended)
        }
    }
}
