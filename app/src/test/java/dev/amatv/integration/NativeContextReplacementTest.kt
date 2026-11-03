package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

/** Old native lookahead must not authorize playing the previous paused context. */
class NativeContextReplacementTest {
    private fun entry(id: Long, catalog: String) = NativeQueueEntry(id,
        Track("apple:$catalog", "Track $catalog", "", "", 180_000L, emptyList(), emptyList(),
            catalogId = catalog, catalogKind = CatalogKind.Song))

    @Test fun replacingPausedCurrentWithLookaheadWaitsForNewOccurrenceBeforePlay() {
        val rail = NativeQueueRail()
        val old = entry(867L, "1571169281")
        val next = entry(868L, "1571169287")
        val oldWindow = listOf(NativeQueueObservation(40L, old.track.catalogId))
        rail.begin(1L, old, listOf(next))
        rail.observe(oldWindow, 40L)
        rail.committed(checkNotNull(rail.edit(oldWindow, 40L)))
        rail.observe(oldWindow + NativeQueueObservation(41L, next.track.catalogId), 40L)
        assertTrue(rail.hasReadySuccessor())

        val replacement = entry(892L, "1029609244")
        rail.begin(2L, replacement, emptyList())
        val intent = NativePlaybackIntent()
        intent.begin(replacement.track.catalogId, 0L, false, 0L)
        intent.requestPlaying(true) // UI's explicit Play arrives after silent prepare.
        assertNull(rail.observe(oldWindow + NativeQueueObservation(41L, next.track.catalogId), 40L))
        val waiting = intent.observe(NativePlaybackProbe(old.track.catalogId, true, 85_501L,
            180_000L, false, false, true, true, false, 1_000L))
        assertFalse(waiting.prepared)
        assertTrue(waiting.commands.isEmpty())
        assertNull(rail.entryForNativeId(40L))
        assertFalse(rail.hasReadySuccessor())

        val adopted = rail.observe(listOf(NativeQueueObservation(42L, replacement.track.catalogId)), 42L)
        assertEquals(replacement.entryId, adopted?.entry?.entryId)
        val ready = intent.observe(NativePlaybackProbe(replacement.track.catalogId, true, 0L,
            180_000L, false, false, true, true, false, 2_000L))
        assertTrue(ready.prepared)
        assertEquals(listOf(NativePlaybackCommand.Play), ready.commands)
        assertEquals(replacement.entryId, rail.entryForNativeId(42L)?.entryId)
        assertFalse(rail.handedOff)
    }
}
