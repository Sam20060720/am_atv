package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSessionSanitizationTest {
    private fun track(id: String, kind: CatalogKind = CatalogKind.Song) = Track(
        id = id, title = id, artist = "Artist", album = "Album", durationMs = 200_000L,
        palette = emptyList(), lyrics = emptyList(), catalogId = id, catalogKind = kind,
    )

    private fun session(queue: List<Track>, index: Int, autoplay: Set<Int>) = PlaybackSession(
        queue = queue, currentIndex = index, positionMs = 18_000L, wasPlaying = true,
        destination = Destination.NowPlaying, previousDestination = Destination.Home,
        radioActive = true, radioSeed = queue[index], radioContinuation = "old-continuation",
        autoplayEntryIndices = autoplay,
    )

    @Test fun removesOnlyMarkedMalformedAutoplayAndKeepsExplicitResourcesAndRepeats() {
        val repeated = track("1")
        val explicitStation = track("explicit", CatalogKind.Station)
        val original = session(listOf(
            repeated, repeated, explicitStation,
            track("bad", CatalogKind.Station), track("next"),
        ), 1, setOf(3, 4))

        val restored = original.sanitizedAutoplayEntries()!!
        assertEquals(listOf("1", "1", "explicit", "next"), restored.queue.map { it.id })
        assertEquals(1, restored.currentIndex)
        assertEquals(18_000L, restored.positionMs)
        assertEquals(setOf(3), restored.autoplayEntryIndices)
    }

    @Test fun malformedCurrentAutoplayMovesToNextSongAndResetsPlayheadAndSeed() {
        val next = track("next")
        val original = session(listOf(track("previous"), track("bad", CatalogKind.Station), next), 1, setOf(1, 2))
        val restored = original.sanitizedAutoplayEntries()!!

        assertEquals(listOf("previous", "next"), restored.queue.map { it.id })
        assertEquals(1, restored.currentIndex)
        assertEquals(0L, restored.positionMs)
        assertEquals(next, restored.radioSeed)
        assertNull(restored.radioContinuation)
        assertEquals(setOf(1), restored.autoplayEntryIndices)
    }

    @Test fun malformedLastAutoplayFallsBackToPreviousSongWithoutRetainingBadHistory() {
        val original = session(listOf(track("previous"), track("bad", CatalogKind.Album)), 1, setOf(1))
        val restored = original.sanitizedAutoplayEntries()!!
        assertEquals(listOf("previous"), restored.queue.map { it.id })
        assertEquals(0, restored.currentIndex)
        assertEquals(0L, restored.positionMs)
    }

    @Test fun legacySessionWithoutOriginsIsPreservedRatherThanGuessed() {
        val original = session(listOf(track("explicit", CatalogKind.Station), track("song")), 0, emptySet())
        assertEquals(original, original.sanitizedAutoplayEntries())
    }

    @Test fun entirelyMalformedAutoplayDoesNotRestoreAnUnplayableSession() {
        val original = session(listOf(track("bad", CatalogKind.Station)), 0, setOf(0))
        assertNull(original.sanitizedAutoplayEntries())
    }

    @Test fun sanitizationKeepsOccurrenceSourcesAlignedAndPreservesOffWindowDeferredIds() {
        val original = session(listOf(track("same"), track("bad", CatalogKind.Station), track("same"), track("last")),
            0, setOf(1)).copy(
            queueEntryIds = listOf(10L, 11L, 12L, 13L),
            queueSourceEntryIds = listOf(1L, 2L, 1L, 3L),
            deferredLastEntryIds = setOf(11L, 13L, 99L),
        )
        val restored = original.sanitizedAutoplayEntries()!!
        assertEquals(listOf(10L, 12L, 13L), restored.queueEntryIds)
        assertEquals(listOf(1L, 1L, 3L), restored.queueSourceEntryIds)
        assertEquals(setOf(13L, 99L), restored.deferredLastEntryIds)
    }
}
