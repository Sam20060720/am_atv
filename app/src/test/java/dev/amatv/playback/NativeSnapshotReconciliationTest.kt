package dev.amatv.playback

import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

class NativeSnapshotReconciliationTest {
    private fun entry(id: Long) = QueueEntry(id,
        Track("$id", "Song", "Artist", "Album", 120_000, emptyList(), emptyList(), catalogId = "$id"), EntryOrigin.Context)
    private fun snapshot(entry: QueueEntry) = PlaybackSnapshot(entry.track, positionMs = 0, durationMs = 120_000,
        isPlaying = false, isBuffering = false, canSkipNext = false, canSkipPrevious = false,
        capturedAtElapsedRealtimeMs = 0, requestGeneration = 7, appQueueEntryId = entry.entryId)

    @Test fun terminalRailRejectionReconcilesEvenThoughTheRejectedOccurrenceIsNotCurrent() {
        val current = entry(1); val rejected = entry(2)
        val state = QueueState(current = current)
        val value = snapshot(rejected).copy(nativeQueueRejected = true, errorMessage = "Rejected")
        assertEquals(NativeSnapshotAction.ReprepareCurrent, nativeSnapshotAction(state, value))
        assertEquals(NativeSnapshotAction.Ignore, nativeSnapshotAction(state, value.copy(nativeQueueRejected = false)))
    }

    @Test fun snapshotQueuedBeforeAnEditCannotSkipTheNewAdjacentOccurrence() {
        val current = entry(1); val oldNext = entry(2); val inserted = entry(3)
        val value = snapshot(oldNext).copy(nativeQueueHandoff = true)
        assertEquals(NativeSnapshotAction.AdoptAdjacent,
            nativeSnapshotAction(QueueState(current = current, upNext = listOf(oldNext, inserted)), value))
        assertEquals(NativeSnapshotAction.ReprepareCurrent,
            nativeSnapshotAction(QueueState(current = current, upNext = listOf(inserted, oldNext)), value))
        assertEquals(NativeSnapshotAction.ReprepareCurrent,
            nativeSnapshotAction(QueueState(current = current, upNext = listOf(inserted)), value))
    }
}
