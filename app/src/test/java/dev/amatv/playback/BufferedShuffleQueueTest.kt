package dev.amatv.playback

import dev.amatv.integration.*
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BufferedShuffleQueueTest {
    private class Preferences : QueuePreferences {
        override var repeatMode = PlaybackRepeatMode.Off
        override var shuffleEnabled = false
        override var autoplayEnabled = false
        override var autoplaySource = AutoplaySource.AppleMusic
        override var lowPerformanceMode = false
    }
    private val recommendations = object : RecommendationProvider {
        override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> = error("No autoplay before a complete population")
        override suspend fun amStationNextTracks(stationId: String): List<Track> = emptyList()
        override suspend fun ytmRadio(seed: Track, continuation: String?, excludedCatalogIds: Set<String>,
            continuationSeedVideoId: String?) = MusicServices.ResolvedRadioPage(emptyList(), null, null)
    }
    private fun song(id: String) = Track(id, id, "Artist", "Album", 180_000L, emptyList(), emptyList(), catalogId = id, catalogKind = CatalogKind.Song)
    private val context = PlaybackContext(ContextKind.Playlist, "playlist", "Playlist")

    @Test fun atomicPopulationKeepsTheSampleDuplicateManualPriorityAndRestorableSourceOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prefs = Preferences()
            val played = mutableListOf<Track>()
            val controller = PlaybackQueueController(scope, recommendations, prefs, { played += it }, Dispatchers.Unconfined)
            controller.playContext(listOf(song("A")), 0, context, shuffle = true, pendingTail = true)
            val anchor = controller.state.value.current!!.entryId
            controller.enqueueNext(listOf(song("next")))
            controller.enqueueLast(listOf(song("last")))
            val sessionId = controller.state.value.sessionId
            assertTrue(controller.appendBufferedShuffleTail(sessionId, listOf(song("A"), song("B")), 2, anchor))
            val state = controller.state.value
            assertEquals(anchor, state.current?.entryId)
            assertEquals(listOf("A"), played.map { it.id })
            assertEquals(listOf("A", "B", "A"), state.contextEntries.filter { it.entryId in state.containerEntryIds }.map { it.track.id })
            assertEquals(3, state.containerEntryIds.size)
            assertEquals("next", state.upNext.first().track.id)
            assertEquals("last", state.upNext.last().track.id)
            assertEquals(listOf("A", "B"), state.upNext.drop(1).dropLast(1).map { it.track.id }.sorted())
            assertEquals(listOf("next", "A", "B", "last"), state.unshuffledUpNext!!.map { it.track.id })
            assertFalse(state.contextContinuationPending)
            assertFalse(controller.appendBufferedShuffleTail(sessionId, emptyList(), 0, anchor))

            val entries = listOf(state.current!!) + state.upNext
            val saved = PlaybackSession(entries.map { it.track }, 0, 42_000L, true, Destination.NowPlaying, Destination.Home,
                false, state.recommendationSeed, null, queueEntryIds = entries.map { it.entryId },
                contextQueue = state.contextEntries.map { it.track }, contextEntryIds = state.contextEntries.map { it.entryId },
                containerEntryIds = state.containerEntryIds, playbackContext = state.context,
                unshuffledEntryIds = state.unshuffledUpNext!!.map { it.entryId })
            val restoredPrefs = Preferences().apply { shuffleEnabled = true }
            val restored = PlaybackQueueController(scope, recommendations, restoredPrefs, {}, Dispatchers.Unconfined)
            restored.restoreFromSession(checkNotNull(PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(saved))))
            restored.setShuffle(false)
            assertEquals(anchor, restored.state.value.current?.entryId)
            assertEquals(listOf("next", "A", "B", "last"), restored.state.value.upNext.map { it.track.id })
            assertEquals(state.containerEntryIds, restored.state.value.containerEntryIds)
        } finally { scope.cancel() }
    }

    @Test fun shuffleCanBeDisabledWhileBufferingAndWaitingNextConsumesOnlyOneOccurrence() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val played = mutableListOf<String>()
            val controller = PlaybackQueueController(scope, recommendations, Preferences(), { played += it.id }, Dispatchers.Unconfined)
            controller.playContext(listOf(song("B")), 0, context, shuffle = true, pendingTail = true)
            val anchor = controller.state.value.current!!.entryId
            controller.setShuffle(false)
            assertNull(controller.advanceNext())
            assertTrue(controller.state.value.awaitingNext)
            assertTrue(controller.appendBufferedShuffleTail(controller.state.value.sessionId, listOf(song("A"), song("A")), 1, anchor))
            assertEquals(listOf("B", "A"), played)
            assertEquals(1, controller.state.value.upNext.size)
            assertEquals(listOf("A", "B", "A"), controller.state.value.contextEntries.map { it.track.id })
            assertEquals(3, controller.state.value.contextEntries.map { it.entryId }.toSet().size)
        } finally { scope.cancel() }
    }
}
