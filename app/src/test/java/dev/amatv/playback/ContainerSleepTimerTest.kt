package dev.amatv.playback

import dev.amatv.integration.MusicServices
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackSessionCodec
import dev.amatv.integration.RecommendationProvider
import dev.amatv.model.Track
import dev.amatv.model.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class ContainerSleepTimerTest {
    private fun entry(id: Long, catalogId: String = "${100 + id}", origin: EntryOrigin = EntryOrigin.Context) =
        QueueEntry(id, Track(catalogId, catalogId, "Artist", "Album", 180_000L, emptyList(), emptyList(), catalogId = catalogId), origin)

    private fun album(current: QueueEntry, next: List<QueueEntry>, original: Set<Long>, pending: Boolean = false) =
        QueueState(current = current, upNext = next, contextEntries = listOf(current) + next,
            containerEntryIds = original, context = PlaybackContext(ContextKind.Album, "500", "Album"),
            contextContinuationPending = pending, sessionId = 7L)

    @Test fun aDownloadedEdgeIsNotTheEndUntilTheContinuationIsComplete() {
        val timer = PlaybackSleepTimer { 0L }
        val first = entry(1)
        val pending = album(first, emptyList(), setOf(1), pending = true)
        assertTrue(timer.select(SleepTimerOption.EndOfContext, 1L, pending))
        assertFalse(timer.consumePlaybackEnd(pending, 1L))
        assertTrue(timer.nativeUpcoming(pending).isEmpty())

        val duplicate = entry(2, first.track.catalogId!!)
        val extended = pending.copy(upNext = listOf(duplicate), contextEntries = listOf(first, duplicate),
            containerEntryIds = setOf(1, 2), contextContinuationPending = false)
        assertFalse(timer.consumeQueueChange(extended))
        assertFalse(timer.consumePlaybackEnd(extended, 1L))
        assertEquals(listOf(2L), timer.nativeUpcoming(extended).map { it.entryId })

        val last = extended.copy(current = duplicate, history = listOf(first), upNext = emptyList())
        assertFalse(timer.consumeQueueChange(last))
        assertTrue(timer.consumePlaybackEnd(last, 2L))
        assertFalse(timer.consumePlaybackEnd(last, 2L))
    }

    @Test fun explicitInsertionsDoNotExtendTheOriginalContainerAndNativeRailStopsAtItsBoundary() {
        val timer = PlaybackSleepTimer { 0L }
        val first = entry(1)
        val inserted = entry(2)
        val last = entry(3, first.track.catalogId!!)
        val appended = entry(4)
        val recommendation = entry(5, origin = EntryOrigin.Autoplay)
        val initial = album(first, listOf(inserted, last, appended), setOf(1, 3)).copy(autoplay = listOf(recommendation), infinite = true)
        assertTrue(timer.select(SleepTimerOption.EndOfContext, 1L, initial))
        assertEquals(listOf(2L, 3L), timer.nativeUpcoming(initial).map { it.entryId })
        assertFalse(timer.consumePlaybackEnd(initial, 1L))

        val inside = initial.copy(current = inserted, history = listOf(first), upNext = listOf(last, appended))
        assertFalse(timer.consumeQueueChange(inside))
        assertFalse(timer.consumePlaybackEnd(inside, 2L))

        val boundary = inside.copy(current = last, history = listOf(first, inserted), upNext = listOf(appended))
        assertFalse(timer.consumeQueueChange(boundary))
        assertTrue(timer.nativeUpcoming(boundary).isEmpty())
        assertTrue(timer.consumePlaybackEnd(boundary, 3L))
        // Clearing the policy restores the existing rail; it did not remove the explicit queue.
        assertEquals(listOf(4L, 5L), timer.nativeUpcoming(boundary).map { it.entryId })
    }

    @Test fun anEmptyTerminalPageCanFinishAnAlreadyEndedDownloadedOccurrence() {
        val timer = PlaybackSleepTimer { 0L }
        val pending = album(entry(1), emptyList(), setOf(1), pending = true).copy(awaitingNext = true)
        timer.select(SleepTimerOption.EndOfContext, 1L, pending)
        assertFalse(timer.consumePlaybackEnd(pending, 1L))
        val complete = pending.copy(contextContinuationPending = false)
        assertFalse(timer.consumeQueueChange(complete))
        assertTrue(timer.consumePlaybackEnd(complete, 1L))
    }

    @Test fun repeatingAMiddleOccurrenceCannotPretendTheContainerHasFinished() {
        val timer = PlaybackSleepTimer { 0L }
        val middle = album(entry(1), listOf(entry(2)), setOf(1, 2)).copy(repeat = PlaybackRepeatMode.One)
        timer.select(SleepTimerOption.EndOfContext, 1L, middle)
        repeat(10) {
            assertFalse(timer.consumePlaybackEnd(middle, 1L))
            assertFalse(timer.consumeQueueChange(middle))
        }
        val last = middle.copy(current = entry(2), history = listOf(entry(1)), upNext = emptyList(), repeat = PlaybackRepeatMode.All)
        assertFalse(timer.consumeQueueChange(last))
        assertTrue(timer.consumePlaybackEnd(last, 2L))
    }

    @Test fun manualPreviousKeepsTheTimerButCrossingIntoARepeatCycleStopsIt() {
        val timer = PlaybackSleepTimer { 0L }
        val first = entry(1)
        val last = entry(2)
        val boundary = album(last, emptyList(), setOf(1, 2)).copy(history = listOf(first), contextEntries = listOf(first, last),
            repeat = PlaybackRepeatMode.All)
        timer.select(SleepTimerOption.EndOfContext, 2L, boundary)
        val previous = boundary.copy(current = first, history = emptyList(), upNext = listOf(last))
        assertFalse(timer.consumeQueueChange(previous))
        assertFalse(timer.consumeQueueChange(boundary))
        val repeated = boundary.copy(current = entry(3), upNext = listOf(entry(4)), history = listOf(first, last),
            containerEntryIds = setOf(1, 2, 3, 4))
        assertTrue(timer.consumeQueueChange(repeated))
    }

    @Test fun changingTheSelectedContainerCancelsInsteadOfPausingTheNewChoice() {
        val timer = PlaybackSleepTimer { 0L }
        val initial = album(entry(1), emptyList(), setOf(1))
        timer.select(SleepTimerOption.EndOfContext, 1L, initial)
        val replacement = initial.copy(current = entry(2), sessionId = 8L,
            context = PlaybackContext(ContextKind.Playlist, "pl.new"), containerEntryIds = setOf(2), contextEntries = listOf(entry(2)))
        assertFalse(timer.consumeQueueChange(replacement))
        assertEquals(SleepTimerOption.Off, timer.state.value.option)
    }

    @Test fun aNativeHandoffCannotCarryAnExpiredContainerIntoRecommendations() {
        val timer = PlaybackSleepTimer { 0L }
        val last = album(entry(1), emptyList(), setOf(1)).copy(autoplay = listOf(entry(2, origin = EntryOrigin.Autoplay)))
        timer.select(SleepTimerOption.EndOfContext, 1L, last)
        assertTrue(timer.consumeNativeHandoff(last, 2L))
        assertFalse(timer.consumeNativeHandoff(last, 2L))
    }

    @Test fun unknownLegacyOrIncompleteProvenanceCannotArmAContainerTimer() {
        val timer = PlaybackSleepTimer { 0L }
        val legacy = album(entry(1), listOf(entry(2)), emptySet())
        assertFalse(timer.canSelectContextEnd(legacy))
        assertFalse(timer.select(SleepTimerOption.EndOfContext, 1L, legacy))
        val incomplete = legacy.copy(containerEntryIds = setOf(1, 2, 3))
        assertFalse(timer.canSelectContextEnd(incomplete))
        assertFalse(timer.canSelectContextEnd(legacy.copy(containerEntryIds = setOf(1, 2), context = PlaybackContext(ContextKind.SongList, "list"))))
    }

    private class Recommendations : RecommendationProvider {
        var requests = 0
        override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> { requests++; return null to emptyList() }
        override suspend fun amStationNextTracks(stationId: String): List<Track> { requests++; return emptyList() }
        override suspend fun ytmRadio(seed: Track, continuation: String?, excludedCatalogIds: Set<String>, continuationSeedVideoId: String?): MusicServices.ResolvedRadioPage {
            requests++; return MusicServices.ResolvedRadioPage(emptyList(), null, null)
        }
    }

    @Test fun queueProvenanceSeparatesDeliberateDuplicatesFromOriginalPagedOccurrences() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = PlaybackQueueController(scope, Recommendations(), TestQueuePreferences(), {}, Dispatchers.Unconfined)
            val repeated = entry(1).track
            queue.playContext(listOf(repeated, repeated), 0, PlaybackContext(ContextKind.Playlist, "pl.original"), pendingTail = true)
            val original = queue.state.value.containerEntryIds
            queue.enqueueNext(listOf(repeated))
            queue.enqueueLast(listOf(repeated))
            assertEquals(original, queue.state.value.containerEntryIds)
            val session = queue.state.value.sessionId
            assertTrue(queue.appendContextTail(session, listOf(repeated), complete = true, allowPlaybackAdvance = false))
            val state = queue.state.value
            assertEquals(3, state.containerEntryIds.size)
            assertEquals(5, state.contextEntries.size)
            assertEquals(5, state.contextEntries.map { it.entryId }.distinct().size)
            assertEquals(2, state.contextEntries.count { it.entryId !in state.containerEntryIds })
        } finally { scope.cancel() }
    }

    @Test fun aCompletedEmptyTailCanBeCommittedWithoutStartingAutoplayBeforeTheStopPolicy() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val recommendations = Recommendations()
            val preferences = TestQueuePreferences().apply { autoplayEnabled = true }
            val played = mutableListOf<Track>()
            val queue = PlaybackQueueController(scope, recommendations, preferences, { played += it }, Dispatchers.Unconfined)
            queue.playContext(listOf(entry(1).track), 0, PlaybackContext(ContextKind.Album, "500"), pendingTail = true)
            val state = queue.state.value
            queue.advanceNext(manual = false)
            assertTrue(queue.state.value.awaitingNext)
            assertTrue(queue.appendContextTail(state.sessionId, emptyList(), complete = true, allowPlaybackAdvance = false))
            assertEquals(state.current?.entryId, queue.state.value.current?.entryId)
            assertEquals(1, played.size)
            assertEquals(0, recommendations.requests)
            assertFalse(queue.state.value.contextContinuationPending)
        } finally { scope.cancel() }
    }

    @Test fun repeatCyclesMapOnlyOriginalOccurrencesAndNeverPromoteInsertedSongsIntoTheContainer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = PlaybackQueueController(scope, Recommendations(), TestQueuePreferences(), {}, Dispatchers.Unconfined)
            queue.playContext(listOf(entry(1).track, entry(2).track), 0, PlaybackContext(ContextKind.Album, "500"))
            queue.enqueueLast(listOf(entry(3).track))
            val original = queue.state.value.containerEntryIds
            queue.setRepeat(PlaybackRepeatMode.All)
            repeat(2) {
                repeat(3) { queue.advanceNext(manual = true) }
                val state = queue.state.value
                assertTrue(state.current!!.entryId in state.containerEntryIds)
                assertTrue(state.upNext[0].entryId in state.containerEntryIds)
                assertFalse(state.upNext[1].entryId in state.containerEntryIds)
                assertTrue(state.containerEntryIds.containsAll(original))
                assertTrue(PlaybackSleepTimer { 0L }.canSelectContextEnd(state))
            }
        } finally { scope.cancel() }
    }

    @Test fun sessionRoundTripRestoresTheContainerAndExplicitOccurrenceBoundary() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = PlaybackQueueController(scope, Recommendations(), TestQueuePreferences(), {}, Dispatchers.Unconfined)
            queue.playContext(listOf(entry(1).track, entry(2).track), 0, PlaybackContext(ContextKind.LibraryPlaylist, "p.original"))
            queue.enqueueLast(listOf(entry(2).track)) // Same recording, deliberately outside the source playlist.
            val before = queue.state.value
            val entries = before.sessionEntries
            val saved = PlaybackSession(entries.map { it.track }, 0, 12_000L, false, Destination.NowPlaying, Destination.Library,
                false, null, null, queueEntryIds = entries.map { it.entryId },
                contextQueue = before.contextEntries.map { it.track }, contextEntryIds = before.contextEntries.map { it.entryId },
                containerEntryIds = before.containerEntryIds, playbackContext = before.context)
            val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(saved))!!
            val restored = PlaybackQueueController(scope, Recommendations(), TestQueuePreferences(), {}, Dispatchers.Unconfined)
            restored.restoreFromSession(decoded)
            val after = restored.state.value
            assertEquals(before.context, after.context)
            assertEquals(before.containerEntryIds, after.containerEntryIds)
            assertTrue(after.upNext[0].entryId in after.containerEntryIds)
            assertFalse(after.upNext[1].entryId in after.containerEntryIds)
            assertTrue(PlaybackSleepTimer { 0L }.canSelectContextEnd(after))
        } finally { scope.cancel() }
    }

    @Test fun aLaterPageCannotInventMissingProvenanceForAnOlderRestoredQueue() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = PlaybackQueueController(scope, Recommendations(), TestQueuePreferences(), {}, Dispatchers.Unconfined)
            val older = PlaybackSession(listOf(entry(1).track), 0, 0L, false, Destination.NowPlaying, Destination.Library,
                false, null, null, queueEntryIds = listOf(1L))
            queue.restoreFromSession(older)
            val session = queue.state.value.sessionId
            queue.markContextTailPending(session, PlaybackContext(ContextKind.Playlist, "pl.legacy"))
            queue.appendContextTail(session, listOf(entry(2).track), complete = true)
            assertTrue(queue.state.value.containerEntryIds.isEmpty())
            assertFalse(PlaybackSleepTimer { 0L }.canSelectContextEnd(queue.state.value))
        } finally { scope.cancel() }
    }
}
