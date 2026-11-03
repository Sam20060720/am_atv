package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

class NativeQueueRailTest {
    private fun e(id: Long, catalog: String = "$id") = NativeQueueEntry(id,
        Track("song:$catalog", catalog, "Artist", "Album", 180_000L, emptyList(), emptyList(), catalogId = catalog))
    private fun q(nativeId: Long, catalog: String = "$nativeId") = NativeQueueObservation(nativeId, catalog)

    @Test fun uploadsOnlyTwoAdjacentKnownSongsAndDoesNotSubmitAnAsyncAddTwice() {
        val rail = NativeQueueRail()
        rail.begin(10, e(1), listOf(e(2), e(3), e(4)))
        val queue = listOf(q(101, "1"))
        assertEquals(1L, rail.observe(queue, 101)!!.entry.entryId)
        val edit = rail.edit(queue, 101)!!
        assertEquals(listOf(2L, 3L), edit.append.map { it.entryId })
        rail.committed(edit)
        assertNull(rail.edit(queue, 101))
        assertFalse(rail.hasReadySuccessor())
        rail.observe(queue + listOf(q(102, "2"), q(103, "3")), 101)
        assertTrue(rail.hasReadySuccessor())
        assertNull(rail.edit(queue + listOf(q(102, "2"), q(103, "3")), 101))
    }

    @Test fun identicalCatalogOccurrencesBindThroughDistinctNativeQueueIds() {
        val rail = NativeQueueRail()
        rail.begin(7, e(1, "900"), listOf(e(2, "900"), e(3, "900")))
        val initial = listOf(q(101, "900"))
        rail.observe(initial, 101)
        rail.committed(rail.edit(initial, 101)!!)
        val queue = initial + listOf(q(102, "900"), q(103, "900"))
        rail.observe(queue, 101)
        val second = rail.observe(queue, 102)!!
        assertTrue(second.changed)
        assertEquals(2L, second.entry.entryId)
        assertTrue(rail.handedOff)
        val third = rail.observe(queue, 103)!!
        assertTrue(third.changed)
        assertEquals(3L, third.entry.entryId)
        assertEquals(3L, rail.entryForNativeId(103)!!.entryId)
    }

    @Test fun futureReplacementRemovesOnlyTheChangedSuffixAndStaleGenerationsCannotEdit() {
        val rail = NativeQueueRail()
        rail.begin(7, e(1), listOf(e(2), e(3)))
        val first = listOf(q(101, "1"))
        rail.observe(first, 101)
        rail.committed(rail.edit(first, 101)!!)
        val queue = first + listOf(q(102, "2"), q(103, "3"))
        rail.observe(queue, 101)
        assertFalse(rail.updateUpcoming(6, listOf(e(9))))
        assertNull(rail.edit(queue, 101))
        assertTrue(rail.updateUpcoming(7, listOf(e(2), e(4))))
        val edit = rail.edit(queue, 101)!!
        assertEquals(listOf(103L), edit.removeIds)
        assertEquals(listOf(4L), edit.append.map { it.entryId })
    }

    @Test fun unknownLibraryEntryBlocksAllLaterItemsRatherThanSkippingIt() {
        for (unknown in listOf("i.library", "l.playlist", "", "0", "-1")) {
            val rail = NativeQueueRail()
            rail.begin(1, e(1), listOf(e(2, unknown), e(3)))
            val queue = listOf(q(101, "1"))
            rail.observe(queue, 101)
            assertNull("Unmapped first future item $unknown must block the rail", rail.edit(queue, 101))
        }
    }

    @Test fun missingOrReorderedNativeMetadataCannotGuessAnOccurrenceHandoff() {
        val rail = NativeQueueRail()
        rail.begin(1, e(1), listOf(e(2), e(3)))
        val first = listOf(q(101, "1"))
        rail.observe(first, 101)
        rail.committed(rail.edit(first, 101)!!)
        val wrong = first + listOf(q(103, "3"), q(102, "2"))
        assertNull(rail.observe(wrong, 103))
        assertFalse(rail.hasReadySuccessor())
        assertEquals(1L, rail.current!!.entryId)
    }

    @Test fun clearingTheAppFutureRejectsAnObsoleteSdkHandoff() {
        val rail = NativeQueueRail()
        rail.begin(1, e(1), listOf(e(2)))
        val first = listOf(q(101, "1"))
        rail.observe(first, 101)
        rail.committed(rail.edit(first, 101)!!)
        val queue = first + q(102, "2")
        rail.observe(queue, 101)
        rail.updateUpcoming(1, emptyList())
        assertFalse(rail.hasReadySuccessor())
        val rejected = rail.observe(queue, 102)!!
        assertTrue(rejected.rejected)
        assertFalse(rejected.changed)
        assertEquals(1L, rail.current!!.entryId)
        val edit = rail.edit(queue, 101)!!
        assertEquals(listOf(102L), edit.removeIds)
        assertTrue(edit.append.isEmpty())
    }

    @Test fun handoffPrunesPlayedNativeHistoryAndPreservesTheAlreadyPreparedNextItem() {
        val rail = NativeQueueRail()
        rail.begin(1, e(1), listOf(e(2), e(3)))
        val first = listOf(q(101, "1"))
        rail.observe(first, 101)
        rail.committed(rail.edit(first, 101)!!)
        val queue = first + listOf(q(102, "2"), q(103, "3"))
        rail.observe(queue, 101)
        rail.observe(queue, 102)
        rail.updateUpcoming(1, listOf(e(3), e(4)))
        val edit = rail.edit(queue, 102)!!
        assertEquals(listOf(101L), edit.removeIds)
        assertEquals(listOf(4L), edit.append.map { it.entryId })
        assertEquals(listOf(3L, 4L), edit.window.map { it.entryId })
    }

    @Test fun aRemovedSdkSuccessorIsNotConsideredReadyFromAnOldBinding() {
        val rail = NativeQueueRail()
        rail.begin(1, e(1), listOf(e(2), e(3)))
        val first = listOf(q(101, "1"))
        rail.observe(first, 101)
        rail.committed(rail.edit(first, 101)!!)
        rail.observe(first + listOf(q(102, "2"), q(103, "3")), 101)
        assertTrue(rail.hasReadySuccessor())
        rail.observe(first + q(103, "3"), 101)
        assertFalse(rail.hasReadySuccessor())
        assertNull(rail.edit(first + q(103, "3"), 101))
    }

    @Test fun alreadyPlayingNativeAdoptionNeverSeeksBackOrWaitsForSeekCapability() {
        val intent = NativePlaybackIntent()
        intent.adoptPrepared("123", 23_456L, true, 1_000L)
        val probe = NativePlaybackProbe("123", true, 23_456L, 180_000L,
            playing = true, buffering = false, canSeek = false, paused = false, ended = false, nowMs = 1_010L)
        val decision = intent.observe(probe)
        assertTrue(decision.prepared)
        assertTrue(decision.playing)
        assertTrue(decision.commands.isEmpty())
        assertEquals(23_456L, decision.positionMs)
        intent.requestPlaying(false)
        val paused = intent.observe(probe.copy(nowMs = 1_020L))
        assertFalse(paused.playing)
        assertTrue(paused.commands.contains(NativePlaybackCommand.Pause))
    }

    @Test fun lateRootProjectionCannotReinsertAnAlreadyPlayedOccurrence() {
        val rail = NativeQueueRail()
        rail.begin(1, e(1), listOf(e(2), e(3)))
        val first = listOf(q(101, "1"))
        rail.observe(first, 101)
        rail.committed(rail.edit(first, 101)!!)
        val queue = first + listOf(q(102, "2"), q(103, "3"))
        rail.observe(queue, 101)
        rail.observe(queue, 102)
        rail.observe(queue, 103)
        rail.updateUpcoming(1, listOf(e(2), e(3), e(4)))
        val edit = rail.edit(queue, 103)!!
        assertEquals(listOf(4L), edit.append.map { it.entryId })
    }
    @Test fun sdkFutureOnlyEmptyWindowStillBindsRealCurrentAndPreparesNextTwo() {
        val rail = NativeQueueRail()
        rail.begin(1, e(20, "900"), listOf(e(21, "901"), e(22, "902")))
        val window = nativeQueueObservationWindow(q(100, "900"), emptyList())
        assertEquals(listOf(q(100, "900")), window)
        assertEquals(20L, rail.observe(window, 100)!!.entry.entryId)
        assertEquals(listOf(21L, 22L), rail.edit(window, 100)!!.append.map { it.entryId })
    }

    @Test fun futureOnlyWindowPreservesDuplicateOccurrencesThroughHandoffWithoutHistory() {
        val rail = NativeQueueRail()
        rail.begin(1, e(1, "900"), listOf(e(2, "900"), e(3, "900")))
        val first = nativeQueueObservationWindow(q(100, "900"), emptyList())
        rail.observe(first, 100)
        rail.committed(rail.edit(first, 100)!!)
        val prepared = nativeQueueObservationWindow(q(100, "900"), listOf(q(101, "900"), q(102, "900")))
        rail.observe(prepared, 100)
        assertTrue(rail.hasReadySuccessor())
        val advanced = nativeQueueObservationWindow(q(101, "900"), listOf(q(102, "900")))
        assertEquals(2L, rail.observe(advanced, 101)!!.entry.entryId)
        assertEquals(2, advanced.size)
        assertTrue(rail.hasReadySuccessor())
        assertEquals(3L, rail.observe(nativeQueueObservationWindow(q(102, "900"), emptyList()), 102)!!.entry.entryId)
    }

    @Test fun futureOnlyWindowCannotResurrectRemovedOrUnobservedSuccessor() {
        val rail = NativeQueueRail()
        rail.begin(1, e(1, "900"), listOf(e(2, "900")))
        val first = nativeQueueObservationWindow(q(100, "900"), emptyList())
        rail.observe(first, 100)
        rail.committed(rail.edit(first, 100)!!)
        // SDK switching to an unbound duplicate is not enough to infer which
        // occurrence played; it must have appeared in the uploaded future rail.
        assertNull(rail.observe(nativeQueueObservationWindow(q(101, "900"), emptyList()), 101))
        assertEquals(1L, rail.current!!.entryId)
        rail.observe(nativeQueueObservationWindow(q(100, "900"), listOf(q(101, "900"))), 100)
        rail.updateUpcoming(1, emptyList())
        assertTrue(rail.observe(nativeQueueObservationWindow(q(101, "900"), emptyList()), 101)!!.rejected)
        assertEquals(1L, rail.current!!.entryId)
    }

}
