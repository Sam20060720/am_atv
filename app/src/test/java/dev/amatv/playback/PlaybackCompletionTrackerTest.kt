package dev.amatv.playback

import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

class PlaybackCompletionTrackerTest {
    private val song = Track("song", "Song", "Artist", "Album", 180_000L,
        emptyList(), emptyList(), catalogId = "123", catalogKind = CatalogKind.Song)
    private val current = QueueEntry(10L, song, EntryOrigin.Context)
    private val state = QueueState(current = current, sessionId = 100L)
    private val ended = PlaybackSnapshot(song, positionMs = song.durationMs, durationMs = song.durationMs,
        isPlaying = false, isBuffering = false, canSkipNext = true, canSkipPrevious = true,
        capturedAtElapsedRealtimeMs = 0L, isEnded = true, requestGeneration = 7L, appQueueEntryId = current.entryId)

    @Test fun repeatedCompletionStaysConsumedAcrossFluctuatingNonEndedPolls() {
        val tracker = PlaybackCompletionTracker()
        assertTrue(tracker.shouldHandle(ended, state))
        repeat(3) {
            assertFalse(tracker.shouldHandle(ended, state))
            assertFalse(tracker.shouldHandle(ended.copy(isEnded = false), state))
        }
        assertFalse(tracker.shouldHandle(ended, state))
    }

    @Test fun incompleteOrFailedEvidenceCannotConsumeTheActualCompletion() {
        for (invalid in listOf(
            ended.copy(requestGeneration = 0L),
            ended.copy(requestGeneration = -1L),
            ended.copy(currentTrack = null),
            ended.copy(isPrepared = false),
            ended.copy(isEnded = false),
            ended.copy(errorMessage = "offline"),
        )) {
            val tracker = PlaybackCompletionTracker()
            assertFalse(tracker.shouldHandle(invalid, state))
            assertTrue(tracker.shouldHandle(ended, state))
        }
        val tracker = PlaybackCompletionTracker()
        assertFalse(tracker.shouldHandle(ended, state.copy(current = null)))
        assertTrue(tracker.shouldHandle(ended, state))
    }

    @Test fun wrongRecordingKindOrOccurrenceCannotConsumeTheSelectedItem() {
        for (invalid in listOf(
            ended.copy(currentTrack = song.copy(catalogId = "other")),
            ended.copy(currentTrack = song.copy(catalogKind = CatalogKind.Station)),
            ended.copy(appQueueEntryId = current.entryId + 1L),
        )) {
            val tracker = PlaybackCompletionTracker()
            assertFalse(tracker.shouldHandle(invalid, state))
            assertTrue(tracker.shouldHandle(ended, state))
        }
    }

    @Test fun adjacentOccurrencesOfTheSameRecordingCompleteIndependently() {
        val tracker = PlaybackCompletionTracker()
        val next = current.copy(entryId = 11L)
        val nextState = state.copy(current = next)
        val nextEnd = ended.copy(appQueueEntryId = next.entryId)
        assertFalse(tracker.shouldHandle(nextEnd, state))
        assertTrue(tracker.shouldHandle(ended, state))
        assertFalse(tracker.shouldHandle(ended, nextState))
        assertTrue(tracker.shouldHandle(nextEnd, nextState))
        assertFalse(tracker.shouldHandle(nextEnd, nextState))
    }

    @Test fun aNewRequestOrLogicalSessionHasItsOwnCompletion() {
        val tracker = PlaybackCompletionTracker()
        assertTrue(tracker.shouldHandle(ended, state))
        val restarted = ended.copy(requestGeneration = 8L)
        assertTrue(tracker.shouldHandle(restarted, state))
        assertFalse(tracker.shouldHandle(restarted, state))
        assertTrue(tracker.shouldHandle(restarted, state.copy(sessionId = 101L)))
        assertFalse(tracker.shouldHandle(restarted, state.copy(sessionId = 101L)))
    }

    @Test fun missingNativeOccurrenceUsesMatchingRecordingMetadata() {
        val tracker = PlaybackCompletionTracker()
        val wrapped = song.copy(id = "native-wrapper", playbackQueueId = 999L)
        assertTrue(tracker.shouldHandle(ended.copy(currentTrack = wrapped, appQueueEntryId = null), state))
        assertFalse(tracker.shouldHandle(ended, state))
    }

    @Test fun missingCatalogMetadataRequiresTheSameLocalRecordingIdentity() {
        val local = song.copy(id = "library-recording", catalogId = null)
        val localState = state.copy(current = current.copy(track = local))
        val localEnd = ended.copy(currentTrack = local)
        val tracker = PlaybackCompletionTracker()
        assertFalse(tracker.shouldHandle(localEnd.copy(currentTrack = local.copy(id = "other-local")), localState))
        assertFalse(tracker.shouldHandle(localEnd.copy(currentTrack = song), localState))
        assertTrue(tracker.shouldHandle(localEnd, localState))
    }

    @Test fun explicitResetReleasesThePreviousCompletionOwnership() {
        val tracker = PlaybackCompletionTracker()
        assertTrue(tracker.shouldHandle(ended, state))
        tracker.reset()
        assertTrue(tracker.shouldHandle(ended, state))
    }
}
