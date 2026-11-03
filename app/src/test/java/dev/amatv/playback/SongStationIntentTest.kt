package dev.amatv.playback

import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.MusicServices
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.QueuePreferences
import dev.amatv.integration.RecommendationProvider
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class SongStationIntentTest {
    private class Preferences : QueuePreferences {
        override var repeatMode = PlaybackRepeatMode.All
        override var shuffleEnabled = true
        override var autoplayEnabled = true
        override var autoplaySource = AutoplaySource.YouTubeMusic
        override var lowPerformanceMode = false
    }
    private class Recommendations : RecommendationProvider {
        val appleSeeds = mutableListOf<String>()
        val youtubeSeeds = mutableListOf<String>()
        val page = CompletableDeferred<Pair<String?, List<Track>>>()
        override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> {
            appleSeeds += songId
            return page.await()
        }
        override suspend fun amStationNextTracks(stationId: String): List<Track> = emptyList()
        override suspend fun ytmRadio(seed: Track, continuation: String?, excludedCatalogIds: Set<String>,
            continuationSeedVideoId: String?): MusicServices.ResolvedRadioPage {
            youtubeSeeds += seed.id
            return MusicServices.ResolvedRadioPage(emptyList(), null, null)
        }
    }
    private fun song(id: String) = Track(id, id, "Artist", "Album", 180_000L,
        emptyList(), emptyList(), catalogId = id, catalogKind = CatalogKind.Song)

    @Test fun selectingSongStationPublishesOneNewContextWithItsOwnSeedAndAppleModes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prefs = Preferences()
            val recommendations = Recommendations()
            val played = mutableListOf<Track>()
            val controller = PlaybackQueueController(scope, recommendations, prefs, { played += it }, Dispatchers.Unconfined)
            controller.playContext(listOf(song("old"), song("old-tail")), 0, pendingTail = true)
            val oldSession = controller.state.value.sessionId
            val observed = mutableListOf<QueueState>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { controller.state.collect { observed += it } }
            observed.clear()
            val seed = song("selected")
            assertTrue(controller.playSongStation(seed))

            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it.current?.track == seed && it.recommendationSeed == seed &&
                it.source == AutoplaySource.AppleMusic && it.infinite && it.repeat == PlaybackRepeatMode.Off &&
                !it.shuffle && !it.contextContinuationPending && it.sessionId != oldSession })
            assertEquals(listOf("selected"), recommendations.appleSeeds)
            assertTrue(recommendations.youtubeSeeds.isEmpty())
            assertEquals(listOf("old", "selected"), played.map { it.id })
            assertEquals(listOf(seed), controller.state.value.contextEntries.map { it.track })
            assertEquals(AutoplaySource.AppleMusic, prefs.autoplaySource)
            assertTrue(prefs.autoplayEnabled)
            assertEquals(PlaybackRepeatMode.Off, prefs.repeatMode)
            assertFalse(prefs.shuffleEnabled)
        } finally { scope.cancel() }
    }

    @Test fun nonSongCannotReplaceTheQueueOrStartAnyStationRequest() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prefs = Preferences()
            val recommendations = Recommendations()
            val controller = PlaybackQueueController(scope, recommendations, prefs, {}, Dispatchers.Unconfined)
            controller.playContext(listOf(song("old")), 0)
            val before = controller.state.value
            assertFalse(controller.playSongStation(song("playlist").copy(catalogKind = CatalogKind.Playlist)))
            assertSame(before, controller.state.value)
            assertTrue(recommendations.appleSeeds.isEmpty())
            assertTrue(recommendations.youtubeSeeds.isEmpty())
        } finally { scope.cancel() }
    }
}
