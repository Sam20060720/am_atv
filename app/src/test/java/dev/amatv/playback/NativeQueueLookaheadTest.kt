package dev.amatv.playback

import androidx.compose.ui.graphics.Color
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

class NativeQueueLookaheadTest {
    private fun entry(id: Long, catalog: String) = QueueEntry(id, Track(catalog, catalog, "artist", "album", 1, listOf(Color.Black), emptyList(), catalogId = catalog), EntryOrigin.Context)
    @Test fun duplicateRecordingsKeepOccurrenceIdentityAndBoundTheWindow() {
        val state = QueueState(current = entry(1, "100"), upNext = listOf(entry(2, "200"), entry(3, "200"), entry(4, "300")))
        assertEquals(listOf(2L, 3L), nativeQueueLookahead(state).map { it.entryId })
    }
    @Test fun unresolvedNextItemCannotBeSkippedToPrepareAnotherSong() {
        assertTrue(nativeQueueLookahead(QueueState(current = entry(1, "100"), upNext = listOf(entry(2, "i.library"), entry(3, "300")))).isEmpty())
    }
    @Test fun singleRepeatAndStationKeepTheirOwnTransportSemantics() {
        val state = QueueState(current = entry(1, "100"), upNext = listOf(entry(2, "200")))
        assertTrue(nativeQueueLookahead(state.copy(repeat = PlaybackRepeatMode.One)).isEmpty())
        assertTrue(nativeQueueLookahead(state.copy(current = checkNotNull(state.current).let { it.copy(track = it.track.copy(catalogKind = CatalogKind.Station)) })).isEmpty())
    }
    @Test fun pendingPlayLastUsesTheSamePlayablePrefixAsQueueAdvancement() {
        val state = QueueState(current = entry(1, "100"),
            upNext = listOf(entry(2, "200"), entry(3, "300")),
            autoplay = listOf(entry(4, "400").copy(origin = EntryOrigin.Autoplay)),
            contextContinuationPending = true, deferredLastEntryIds = setOf(3L))
        assertEquals(listOf(2L), nativeQueueLookahead(state).map { it.entryId })
        assertTrue(nativeQueueLookahead(state.copy(upNext = state.upNext.drop(1))).isEmpty())
        assertEquals(listOf(2L, 3L), nativeQueueLookahead(state.copy(contextContinuationPending = false)).map { it.entryId })
    }
    @Test fun repeatAllStopsTheNativeRailAtTheExplicitCycleBoundary() {
        val state = QueueState(current = entry(1, "100"), upNext = listOf(entry(2, "200")),
            autoplay = listOf(entry(3, "300").copy(origin = EntryOrigin.Autoplay)), repeat = PlaybackRepeatMode.All)
        assertEquals(listOf(2L), nativeQueueLookahead(state).map { it.entryId })
        assertTrue(nativeQueueLookahead(state.copy(upNext = emptyList())).isEmpty())
    }
}
