package dev.amatv.playback

import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.MusicServices
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.autoplaySourceFromStoredValue
import dev.amatv.model.Destination
import dev.amatv.integration.RecommendationProvider
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueControllerTest {

    private class FakeRecommendationProvider : RecommendationProvider {
        var amStationTracks: List<Track> = emptyList()
        var ytmRadioTracks: List<Track> = emptyList()
        var deferredPage: CompletableDeferred<MusicServices.ResolvedRadioPage>? = null
        val pages = mutableListOf<MusicServices.ResolvedRadioPage>()
        val requests = mutableListOf<Pair<String?, String?>>()
        val seeds = mutableListOf<String>()
        val appleSeeds = mutableListOf<String>()
        var timeOut = false

        override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> {
            appleSeeds += songId
            return Pair("fake-station-$songId", amStationTracks)
        }

        override suspend fun amStationNextTracks(stationId: String): List<Track> {
            return amStationTracks
        }

        override suspend fun ytmRadio(
            seed: Track,
            continuation: String?,
            excludedCatalogIds: Set<String>,
            continuationSeedVideoId: String?,
        ): MusicServices.ResolvedRadioPage {
            requests += continuation to continuationSeedVideoId
            seeds += seed.id
            if (timeOut) withTimeout(1) { delay(10_000) }
            deferredPage?.let { return it.await() }
            if (pages.isNotEmpty()) return pages.removeAt(0)
            return MusicServices.ResolvedRadioPage(
                tracks = ytmRadioTracks,
                continuation = "fake-continuation",
                seedVideoId = "fake-video-id",
            )
        }
    }

    private fun testTrack(id: String, title: String, artist: String): Track =
        queueTestSong(id, title, artist, album = "Test Album", durationMs = 210_000L)

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    // Existing radio scenarios exercise an explicitly chosen YTM provider.
    private val prefs = TestQueuePreferences(autoplaySource = AutoplaySource.YouTubeMusic)
    private val fakeRecommendations = FakeRecommendationProvider()
    private var playedTracks = mutableListOf<Track>()

    @Test fun libraryRelinkingPreservesEntryOrderHistoryAndRecommendationSeed() {
        val controller = createController()
        val old = testTrack("1465074689", "君はQueen", "Punipunidenki").copy(
            resourceId = "i.queen", resourceType = "library-songs",
        )
        val unrelated = testTrack("other", "Other", "Other")
        controller.playContext(listOf(old, unrelated, old), 0)
        val before = controller.state.value
        val callbacks = playedTracks.size
        val resolved = old.copy(id = "apple:6790045614", catalogId = "6790045614")
        controller.updateCatalogIdentity(old, resolved)
        val after = controller.state.value
        assertEquals(before.sessionEntries.map { it.entryId }, after.sessionEntries.map { it.entryId })
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.current?.origin, after.current?.origin)
        assertEquals(listOf("6790045614", "other", "6790045614"), after.sessionEntries.map { it.track.catalogId })
        assertEquals("6790045614", after.recommendationSeed?.catalogId)
        assertEquals(listOf("6790045614", "other", "6790045614"), after.contextEntries.map { it.track.catalogId })
        assertEquals(callbacks, playedTracks.size)
    }

    @Test fun retiredCatalogQueueRepairKeepsDuplicateEntriesAndOriginalSources() {
        val controller = createController()
        val original = testTrack("1465074689", "君はQueen", "Punipunidenki")
        controller.playContext(listOf(original, original, testTrack("other", "Other", "Artist")), 0)
        val before = controller.state.value
        controller.updateCatalogIdentity(original, original.copy(id = "apple:6790045614", catalogId = "6790045614"))
        val after = controller.state.value
        assertEquals(before.sessionEntries.map { it.entryId }, after.sessionEntries.map { it.entryId })
        assertEquals(listOf("6790045614", "6790045614", "other"), after.sessionEntries.map { it.track.catalogId })
        assertEquals(listOf("1465074689", "1465074689"), after.sessionEntries.take(2).map { it.track.resourceId })
        assertEquals("6790045614", after.recommendationSeed?.catalogId)
    }

    private fun createController(): PlaybackQueueController {
        playedTracks.clear()
        return PlaybackQueueController(
            scope = scope,
            services = fakeRecommendations,
            playbackPrefs = prefs,
            onPlayTrack = { playedTracks.add(it) },
            recommendationDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test fun manualInsertionKeepsThePlayingSessionAndRepeatedEntryIdentity() {
        prefs.autoplayEnabled = true
        fakeRecommendations.ytmRadioTracks = (10..30).map { testTrack("$it", "Radio $it", "Other $it") }
        val controller = createController()
        val repeated = testTrack("2", "Repeated", "Artist")
        assertFalse(controller.enqueueNext(listOf(repeated)))
        controller.playContext(listOf(testTrack("1", "Past", "One"), repeated, testTrack("3", "Future", "Three")), 1,
            PlaybackContext(ContextKind.Album, "album", "Album"))
        controller.markCurrentPlaybackStarted(controller.state.value.current!!.entryId)
        val before = controller.state.value
        val callbacks = playedTracks.size
        assertFalse(controller.enqueueLast(emptyList()))
        assertFalse(controller.enqueueLast(listOf(repeated.copy(catalogKind = CatalogKind.Album))))
        assertTrue(controller.enqueueNext(listOf(repeated, repeated)))
        assertTrue(controller.enqueueLast(listOf(testTrack("4", "Manual last", "Four"))))
        val after = controller.state.value
        assertEquals(before.current, after.current)
        assertEquals(before.history, after.history)
        assertEquals(before.context, after.context)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.persistenceId, after.persistenceId)
        assertEquals(before.recommendationSeed, after.recommendationSeed)
        assertEquals(before.autoplay, after.autoplay)
        assertEquals(before.ytmContinuation, after.ytmContinuation)
        assertEquals(callbacks, playedTracks.size)
        assertEquals(listOf("2", "2", "3", "4"), after.upNext.map { it.track.id })
        assertEquals(after.sessionEntries.size, after.sessionEntries.map { it.entryId }.toSet().size)
        assertTrue(after.upNext.take(2).all { it.origin == EntryOrigin.Context && !it.wasPlayed })
    }

    @Test fun manualOrderSurvivesShuffleRestoreAndRepeatAllIncludesAddedOccurrences() {
        val controller = createController()
        controller.playContext((1..3).map { testTrack("$it", "Song $it", "Artist $it") }, 0)
        controller.setShuffle(true)
        val originalShuffled = controller.state.value.upNext
        val next = testTrack("next", "Next", "New")
        val last = testTrack("last", "Last", "New")
        controller.enqueueNext(listOf(next, next))
        controller.enqueueLast(listOf(last))
        assertEquals(listOf("next", "next") + originalShuffled.map { it.track.id } + "last",
            controller.state.value.upNext.map { it.track.id })
        controller.setShuffle(false)
        assertEquals(listOf("next", "next", "2", "3", "last"), controller.state.value.upNext.map { it.track.id })
        controller.setRepeat(PlaybackRepeatMode.All)
        repeat(5) { controller.advanceNext() }
        assertEquals("last", controller.state.value.current?.track?.id)
        controller.advanceNext(manual = false)
        assertEquals("1", controller.state.value.current?.track?.id)
        assertEquals(listOf("next", "next", "2", "3", "last"), controller.state.value.upNext.map { it.track.id })
        assertEquals(6, (listOfNotNull(controller.state.value.current) + controller.state.value.upNext).map { it.entryId }.toSet().size)
        controller.setRepeat(PlaybackRepeatMode.One)
        val repeatedEntry = controller.state.value.current
        controller.enqueueNext(listOf(next))
        assertEquals(repeatedEntry, controller.advanceNext(manual = false))
        assertEquals("next", controller.advanceNext(manual = true)?.track?.id)
    }

    @Test
    fun testPlayContextSplitsHistoryCurrentAndUpNext() {
        val controller = createController()
        val tracks = (1..5).map { testTrack(it.toString(), "Track $it", "Artist") }

        controller.playContext(tracks, startIndex = 2)

        val state = controller.state.value
        assertEquals(2, state.history.size)
        assertEquals("Track 1", state.history[0].track.title)
        assertEquals("Track 2", state.history[1].track.title)

        assertEquals("Track 3", state.current?.track?.title)

        assertEquals(2, state.upNext.size)
        assertEquals("Track 4", state.upNext[0].track.title)
        assertEquals("Track 5", state.upNext[1].track.title)

        assertEquals(1, playedTracks.size)
        assertEquals("Track 3", playedTracks.first().title)
    }

    @Test
    fun testSelectEntryFromUpNext() {
        val controller = createController()
        val tracks = (1..5).map { testTrack(it.toString(), "Track $it", "Artist") }
        controller.playContext(tracks, startIndex = 0)

        // Select Track 3 from upNext
        val target = controller.state.value.upNext[1] // Track 3
        controller.selectEntry(target)

        val state = controller.state.value
        assertEquals(2, state.history.size) // Track 1 and skipped Track 2
        assertEquals("Track 1", state.history[0].track.title)
        assertEquals("Track 2", state.history[1].track.title)
        assertEquals("Track 3", state.current?.track?.title)
        assertEquals(2, state.upNext.size) // Track 4 and Track 5
    }

    @Test
    fun testAdvanceNextThroughContextQueue() {
        val controller = createController()
        val tracks = (1..3).map { testTrack(it.toString(), "Track $it", "Artist") }
        controller.playContext(tracks, startIndex = 0)

        val next1 = controller.advanceNext(manual = false)
        assertNotNull(next1)
        assertEquals("Track 2", next1?.track?.title)

        val next2 = controller.advanceNext(manual = false)
        assertNotNull(next2)
        assertEquals("Track 3", next2?.track?.title)

        val next3 = controller.advanceNext(manual = false)
        // With no autoplay tracks and repeat=Off, queue finishes
        assertNull(next3)
    }

    @Test
    fun testRepeatOneReplaysCurrentTrack() {
        val controller = createController()
        val tracks = (1..3).map { testTrack(it.toString(), "Track $it", "Artist") }
        controller.playContext(tracks, startIndex = 0)
        controller.setRepeat(PlaybackRepeatMode.One)

        val advanced = controller.advanceNext(manual = false)
        assertEquals("Track 1", advanced?.track?.title)
        assertEquals(2, controller.state.value.upNext.size)
    }

    @Test
    fun testAdvancePreviousRestartsOrPopsHistory() {
        val controller = createController()
        val tracks = (1..3).map { testTrack(it.toString(), "Track $it", "Artist") }
        controller.playContext(tracks, startIndex = 1) // Track 2 is current, Track 1 in history

        // > 3000ms restarts current track
        val restarted = controller.advancePrevious(positionMs = 5000L)
        assertEquals("Track 2", restarted?.track?.title)
        assertEquals(1, controller.state.value.history.size)

        // <= 3000ms goes to previous track in history
        val prev = controller.advancePrevious(positionMs = 1200L)
        assertEquals("Track 1", prev?.track?.title)
        assertEquals(0, controller.state.value.history.size)
        assertEquals(2, controller.state.value.upNext.size) // Track 2 prepended back to upNext
    }

    @Test
    fun testClearUpcomingRemovesUpNextAndAutoplay() {
        val controller = createController()
        val tracks = (1..5).map { testTrack(it.toString(), "Track $it", "Artist") }
        controller.playContext(tracks, startIndex = 1)

        assertEquals(3, controller.state.value.upNext.size)
        controller.clearUpcoming()

        val state = controller.state.value
        assertEquals("Track 2", state.current?.track?.title)
        assertTrue(state.upNext.isEmpty())
        assertTrue(state.autoplay.isEmpty())
    }

    @Test
    fun testShuffleAndUnshuffleRestoresOrder() {
        val controller = createController()
        val tracks = (1..20).map { testTrack(it.toString(), "Track $it", "Artist") }
        controller.playContext(tracks, startIndex = 0)

        val originalOrder = controller.state.value.upNext.map { it.track.id }

        controller.setShuffle(true)
        assertTrue(controller.state.value.shuffle)
        assertNotNull(controller.state.value.unshuffledUpNext)

        controller.setShuffle(false)
        assertFalse(controller.state.value.shuffle)
        assertNull(controller.state.value.unshuffledUpNext)
        val restoredOrder = controller.state.value.upNext.map { it.track.id }
        assertEquals(originalOrder, restoredOrder)
    }

    @Test fun explicitCollectionShuffleIncludesSongsBeforeRandomStart() {
        val controller = createController()
        val tracks = (1..6).map { testTrack("$it", "Track $it", "Artist") }
        controller.playContext(tracks, 4, shuffle = true)
        assertEquals("5", controller.state.value.current?.track?.id)
        assertTrue(controller.state.value.history.isEmpty())
        assertEquals(setOf("1", "2", "3", "4", "6"), controller.state.value.upNext.map { it.track.id }.toSet())
        assertTrue(prefs.shuffleEnabled)
        controller.playContext(tracks, 2, shuffle = false)
        assertFalse(controller.state.value.shuffle)
        assertFalse(prefs.shuffleEnabled)
        assertEquals(listOf("4", "5", "6"), controller.state.value.upNext.map { it.track.id })
    }

    @Test
    fun duplicatePlaylistEntriesRetainSelectionIndexAndDistinctEntryIds() {
        val controller = createController()
        val repeated = testTrack("1", "Repeated", "Artist")
        val last = testTrack("2", "Last", "Other")
        controller.playContext(listOf(repeated, repeated, last), startIndex = 1)

        assertEquals("1", controller.state.value.current?.track?.id)
        assertEquals(1, controller.state.value.history.size)
        assertTrue(controller.state.value.history.single().entryId != controller.state.value.current?.entryId)
        assertEquals("2", controller.advanceNext()?.track?.id)
    }

    @Test
    fun repeatAllTakesPrecedenceOverAlreadyFetchedAutoplay() {
        prefs.autoplayEnabled = true
        fakeRecommendations.ytmRadioTracks = listOf(testTrack("2", "Radio song", "Other"))
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Selected", "Artist")), 0)
        assertEquals(1, controller.state.value.autoplay.size)

        controller.setRepeat(PlaybackRepeatMode.All)
        assertEquals("1", controller.advanceNext()?.track?.id)
        assertEquals("1", controller.advanceNext(manual = false)?.track?.id)
    }

    @Test
    fun repeatAllRetainsEntireContextBeyondBoundedHistory() {
        val controller = createController()
        controller.playContext((1..250).map { testTrack("$it", "Track $it", "Artist") }, 249)
        assertEquals(249, controller.state.value.history.size)
        controller.setRepeat(PlaybackRepeatMode.All)
        assertEquals("1", controller.advanceNext()?.track?.id)
        assertEquals(249, controller.state.value.upNext.size)
    }

    @Test
    fun previousPreservesForwardEntryWhenShuffleIsDisabledLater() {
        val controller = createController()
        controller.playContext((1..5).map { testTrack("$it", "Track $it", "Artist") }, 2)
        controller.setShuffle(true)
        assertEquals("2", controller.advancePrevious(0)?.track?.id)
        controller.setShuffle(false)
        assertEquals(listOf("3", "4", "5"), controller.state.value.upNext.map { it.track.id })
        assertEquals("3", controller.advanceNext()?.track?.id)
    }

    @Test
    fun pendingNextAdvancesOnceWhenRecommendationsArrive() {
        prefs.autoplayEnabled = true
        val page = CompletableDeferred<MusicServices.ResolvedRadioPage>()
        fakeRecommendations.deferredPage = page
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Current", "Artist")), 0)
        assertNull(controller.advanceNext())
        assertNull(controller.advanceNext())
        assertTrue(controller.state.value.awaitingNext)
        assertEquals(1, playedTracks.size)

        page.complete(MusicServices.ResolvedRadioPage(
            listOf(testTrack("2", "Next", "Other")), "continuation", "dQw4w9WgXcQ",
        ))

        assertEquals("2", controller.state.value.current?.track?.id)
        assertFalse(controller.state.value.awaitingNext)
        assertEquals(listOf("1", "2"), playedTracks.map { it.id })
        assertEquals("continuation" to "dQw4w9WgXcQ", fakeRecommendations.requests.last())
    }

    @Test
    fun previousCancelsPendingNextWithoutDroppingForwardHistory() {
        prefs.autoplayEnabled = true
        val page = CompletableDeferred<MusicServices.ResolvedRadioPage>()
        fakeRecommendations.deferredPage = page
        val controller = createController()
        controller.playContext((1..2).map { testTrack("$it", "Track $it", "Artist") }, 1)
        controller.advanceNext()
        controller.advancePrevious()
        page.complete(MusicServices.ResolvedRadioPage(
            listOf(testTrack("3", "Radio", "Other")), null, "dQw4w9WgXcQ",
        ))

        assertEquals("1", controller.state.value.current?.track?.id)
        assertEquals("2", controller.state.value.upNext.first().track.id)
        assertFalse(controller.state.value.awaitingNext)
    }

    @Test
    fun startingAnotherContextDiscardsDelayedRecommendation() {
        prefs.autoplayEnabled = true
        val page = CompletableDeferred<MusicServices.ResolvedRadioPage>()
        fakeRecommendations.deferredPage = page
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Old", "Artist")), 0)
        controller.advanceNext()
        controller.setInfinite(false)
        controller.playContext(listOf(testTrack("2", "New", "Other")), 0)
        page.complete(MusicServices.ResolvedRadioPage(
            listOf(testTrack("3", "Stale", "Artist")), null, "dQw4w9WgXcQ",
        ))

        assertEquals("2", controller.state.value.current?.track?.id)
        assertTrue(controller.state.value.autoplay.isEmpty())
        assertFalse(controller.state.value.awaitingNext)
    }

    @Test
    fun duplicateOnlyRadioPageContinuesToNextPageWithSameSeed() {
        prefs.autoplayEnabled = true
        val original = testTrack("1", "Song", "Artist")
        fakeRecommendations.pages += listOf(
            MusicServices.ResolvedRadioPage(
                listOf(testTrack("2", "Song (Remastered)", "Artist")), "page-two", "dQw4w9WgXcQ",
            ),
            MusicServices.ResolvedRadioPage(
                listOf(testTrack("3", "Fresh", "Other")), null, "dQw4w9WgXcQ",
            ),
        )
        val controller = createController()
        controller.playContext(listOf(original), 0)

        assertEquals(listOf("3"), controller.state.value.autoplay.map { it.track.id })
        assertEquals(listOf(null to null, "page-two" to "dQw4w9WgXcQ"), fakeRecommendations.requests.take(2))
    }

    @Test
    fun duplicateOnlyRadioPagesHaveBoundedRetries() {
        prefs.autoplayEnabled = true
        val original = testTrack("1", "Song", "Artist")
        fakeRecommendations.pages += (1..8).map { page ->
            MusicServices.ResolvedRadioPage(listOf(original), "page-$page", "dQw4w9WgXcQ")
        }
        val controller = createController()
        controller.playContext(listOf(original), 0)

        assertEquals(3, fakeRecommendations.requests.size)
        assertTrue(controller.state.value.autoplayStatus is AutoplayStatus.Error)
        assertTrue(controller.state.value.autoplay.isEmpty())
    }
    @Test
    fun appleStationMetadataCannotPolluteManualNextOrReplaceExplicitQueue() {
        prefs.autoplayEnabled = true
        prefs.autoplaySource = AutoplaySource.AppleMusic
        fakeRecommendations.amStationTracks = CatalogKind.entries
            .filter { it != CatalogKind.Song }.map { kind ->
                testTrack(kind.name, kind.name, "Provider").copy(catalogKind = kind)
            }
        val controller = createController()
        val selected = testTrack("1", "Selected", "Artist")
        controller.playContext(listOf(selected, selected, testTrack("2", "Explicit next", "Artist")), 1)

        assertTrue(controller.state.value.autoplay.isEmpty())
        assertEquals("2", controller.advanceNext()?.track?.id)
        assertNull(controller.advanceNext())
        assertEquals(listOf("1", "2"), playedTracks.map { it.id })
        assertTrue(playedTracks.all { it.catalogKind == CatalogKind.Song })
        assertEquals("2", controller.state.value.current?.track?.id)
    }

    @Test
    fun restoredAutoplayOriginsRejectMalformedResourcesAndRetainExplicitContext() {
        val controller = createController()
        val first = testTrack("1", "Explicit", "Artist")
        val bad = testTrack("bad", "Station", "Apple Music").copy(catalogKind = CatalogKind.Station)
        val next = testTrack("2", "Recommendation", "Other")
        controller.restoreFromSession(PlaybackSession(
            queue = listOf(first, bad, next), currentIndex = 0, positionMs = 0L,
            wasPlaying = false, destination = Destination.NowPlaying,
            previousDestination = Destination.Home, radioActive = true,
            radioSeed = first, radioContinuation = null, autoplayEntryIndices = setOf(1, 2),
            autoplaySource = AutoplaySource.YouTubeMusic,
        ))

        assertTrue(controller.state.value.upNext.isEmpty())
        assertEquals(listOf("2"), controller.state.value.autoplay.map { it.track.id })
        assertEquals(EntryOrigin.Autoplay, controller.state.value.autoplay.single().origin)
        assertEquals(listOf("1"), controller.state.value.contextEntries.map { it.track.id })
        assertEquals("2", controller.advanceNext()?.track?.id)
        assertTrue(playedTracks.all { it.catalogKind == CatalogKind.Song })
    }

    @Test
    fun restoredRadioKeepsOriginalStationSeedAfterCurrentSongChanges() {
        prefs.autoplayEnabled = true
        val controller = createController()
        val first = testTrack("1", "Earlier", "Artist")
        val current = testTrack("2", "Current", "Artist").copy(ytmVideoId = "new-video-id")
        controller.restoreFromSession(PlaybackSession(
            queue = listOf(first, current), currentIndex = 1, positionMs = 10_000L,
            wasPlaying = true, destination = Destination.NowPlaying,
            previousDestination = Destination.Home, radioActive = true,
            radioSeed = current, radioContinuation = "station-page",
            radioSeedVideoId = "original-id",
            autoplaySource = AutoplaySource.YouTubeMusic,
        ))
        assertTrue(fakeRecommendations.requests.isEmpty())
        controller.activatePlaybackSession()
        assertEquals("station-page" to "original-id", fakeRecommendations.requests.first())
        assertEquals(first.id, controller.state.value.history.single().track.id)
        assertEquals(current.id, controller.state.value.current?.track?.id)
    }


    @Test fun generatedSongsNeverBecomeTheSeedWhenChangingProvider() {
        prefs.autoplayEnabled = true
        val anchor = testTrack("1", "Selected", "Artist")
        fakeRecommendations.ytmRadioTracks = (2..22).map { testTrack("$it", "Candidate $it", "Other $it") }
        fakeRecommendations.amStationTracks = (30..50).map { testTrack("$it", "Apple $it", "Other $it") }
        val controller = createController()
        controller.playContext(listOf(anchor), 0)
        controller.advanceNext()
        assertEquals("2", controller.state.value.current?.track?.id)
        val before = controller.state.value
        val oldRecommendations = before.autoplay.map { it.entryId }.toSet()
        assertTrue(oldRecommendations.isNotEmpty())
        assertNotNull(before.ytmContinuation)
        assertNotNull(before.ytmSeedVideoId)
        controller.setAutoplaySource(AutoplaySource.AppleMusic)
        val after = controller.state.value
        assertEquals(AutoplaySource.AppleMusic, after.source)
        assertTrue(after.autoplay.isNotEmpty())
        assertTrue(after.autoplay.none { it.entryId in oldRecommendations })
        assertNull(after.ytmContinuation)
        assertNull(after.ytmSeedVideoId)
        assertEquals("fake-station-1", after.amStationId)
        assertEquals(listOf("1"), fakeRecommendations.appleSeeds)
        assertEquals("1", controller.state.value.recommendationSeed?.id)
    }

    @Test fun timelineSelectionAndNextKeepTheRadioSeed() {
        val controller = createController()
        controller.playContext((1..4).map { testTrack("$it", "Track $it", "Artist") }, 0)
        controller.advanceNext()
        assertEquals("1", controller.state.value.recommendationSeed?.id)
        controller.selectEntry(controller.state.value.upNext.last())
        assertEquals("1", controller.state.value.recommendationSeed?.id)
    }

    @Test fun failuresIgnoreStaleEntriesAndBypassRepeatOneWithoutRecordingFailedHistory() {
        val controller = createController()
        val first = testTrack("1", "Unavailable", "Artist")
        controller.playContext(listOf(first, first, testTrack("2", "Good", "Other")), 0)
        controller.setRepeat(PlaybackRepeatMode.One)
        val failedId = controller.state.value.current!!.entryId
        assertEquals("2", controller.skipFailedCurrent(failedId)?.track?.id)
        assertEquals(1, controller.state.value.consecutiveFailures)
        assertTrue(controller.state.value.history.isEmpty())
        assertEquals(3, controller.state.value.contextEntries.size)
        assertNull(controller.skipFailedCurrent(failedId))
        assertEquals("2", controller.state.value.current?.track?.id)
        assertEquals(1, controller.state.value.consecutiveFailures)
    }

    @Test fun failureCircuitStopsAnUnboundedAutomaticSkipChainAndSuccessResetsIt() {
        val controller = createController()
        controller.playContext((1..10).map { testTrack("$it", "Track $it", "Artist") }, 0)
        repeat(3) { controller.skipFailedCurrent(controller.state.value.current!!.entryId) }
        assertEquals(3, controller.state.value.consecutiveFailures)
        controller.markCurrentPlaybackStarted(-1)
        assertEquals(3, controller.state.value.consecutiveFailures)
        controller.markCurrentPlaybackStarted(controller.state.value.current!!.entryId)
        assertEquals(0, controller.state.value.consecutiveFailures)
        repeat(PlaybackQueueController.MAX_CONSECUTIVE_FAILURES) {
            controller.skipFailedCurrent(controller.state.value.current!!.entryId)
        }
        assertTrue(controller.state.value.failureLimitReached)
        val stopped = controller.state.value.current!!.entryId
        assertNull(controller.skipFailedCurrent(stopped))
        assertEquals(stopped, controller.state.value.current?.entryId)
        assertFalse(controller.state.value.awaitingNext)
        assertTrue(controller.state.value.autoplayStatus is AutoplayStatus.Error)
    }

    @Test fun repeatAllCyclesOnlyThePlayableRemainingContext() {
        val controller = createController()
        controller.playContext((1..3).map { testTrack("$it", "Track $it", "Artist") }, 0)
        controller.setRepeat(PlaybackRepeatMode.All)
        controller.skipFailedCurrent(controller.state.value.current!!.entryId)
        assertEquals("3", controller.advanceNext(manual = false)?.track?.id)
        assertEquals("2", controller.advanceNext(manual = false)?.track?.id)
        assertEquals("3", controller.advanceNext(manual = false)?.track?.id)
    }

    @Test fun failedAutoplayCannotReturnThroughAnotherProviderPage() {
        prefs.autoplayEnabled = true
        fakeRecommendations.ytmRadioTracks = (2..22).map { testTrack("$it", "Candidate $it", "Artist $it") }
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Anchor", "Original")), 0)
        controller.advanceNext()
        controller.skipFailedCurrent(controller.state.value.current!!.entryId)
        repeat(10) { controller.advanceNext() }
        assertTrue(controller.state.value.autoplay.none { it.track.id == "2" })
        assertTrue(controller.state.value.history.none { it.track.id == "2" })
        assertEquals("1", controller.state.value.recommendationSeed?.id)
    }

    @Test fun failedSingleSeedCanStillWaitForItsOriginalRadioWithoutReplayingIt() {
        prefs.autoplayEnabled = true
        val pending = CompletableDeferred<MusicServices.ResolvedRadioPage>()
        fakeRecommendations.deferredPage = pending
        val controller = createController()
        val anchor = testTrack("1", "Unavailable audio", "Artist")
        controller.playContext(listOf(anchor), 0)
        val failed = controller.state.value.current!!.entryId
        assertNull(controller.skipFailedCurrent(failed))
        assertTrue(controller.state.value.awaitingNext)
        assertEquals("1", controller.state.value.recommendationSeed?.id)
        pending.complete(MusicServices.ResolvedRadioPage(
            listOf(anchor, testTrack("2", "Playable next", "Other")), null, "dQw4w9WgXcQ",
        ))
        assertEquals("2", controller.state.value.current?.track?.id)
        assertEquals(listOf("1", "2"), playedTracks.map { it.id })
        assertFalse(controller.state.value.awaitingNext)
        assertEquals(1, controller.state.value.consecutiveFailures)
        assertEquals("2", controller.state.value.recommendationSeed?.id)
    }

    @Test fun failedHistoricalOccurrenceIsRemovedBeforePreviousNavigation() {
        val controller = createController()
        val unavailable = testTrack("1", "Unavailable", "Artist")
        val middle = testTrack("2", "Middle", "Other")
        val last = testTrack("3", "Last", "Another")
        controller.playContext(listOf(unavailable, middle, unavailable, last), 2)
        controller.skipFailedCurrent(controller.state.value.current!!.entryId)
        assertEquals(listOf("2"), controller.state.value.history.map { it.track.id })
        assertEquals("2", controller.advancePrevious(0)?.track?.id)
        assertTrue(controller.state.value.history.isEmpty())
        assertEquals(listOf("3"), controller.state.value.upNext.map { it.track.id })
        assertTrue(playedTracks.drop(1).none { it.id == "1" })
    }

    @Test fun previousDoesNotRestartAKnownFailedCurrentWithoutAHealthyHistory() {
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Unavailable", "Artist")), 0)
        controller.skipFailedCurrent(controller.state.value.current!!.entryId)
        assertNull(controller.advancePrevious(5_000))
        assertEquals(listOf("1"), playedTracks.map { it.id })
    }

    @Test fun unavailableAnchorUsesAHealthyQueuedFallbackRatherThanRecreatingItsBrokenStation() {
        prefs.autoplayEnabled = true
        fakeRecommendations.ytmRadioTracks = (2..22).map { testTrack("$it", "Candidate $it", "Artist $it") }
        fakeRecommendations.amStationTracks = (30..50).map { testTrack("$it", "Apple $it", "Artist $it") }
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Removed anchor", "Original")), 0)
        controller.skipFailedCurrent(controller.state.value.current!!.entryId)
        assertEquals("2", controller.state.value.current?.track?.id)
        assertEquals("2", controller.state.value.recommendationSeed?.id)
        assertNull(controller.state.value.ytmContinuation)
        controller.setAutoplaySource(AutoplaySource.AppleMusic)
        assertEquals(listOf("2"), fakeRecommendations.appleSeeds)
    }

    @Test fun recommendationTimeoutLeavesRetryableStateRatherThanPermanentLoading() = runBlocking {
        prefs.autoplayEnabled = true
        fakeRecommendations.timeOut = true
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Anchor", "Artist")), 0)
        controller.advanceNext()
        withTimeout(1_000) { controller.state.first { it.autoplayStatus is AutoplayStatus.Error } }
        assertFalse(controller.state.value.awaitingNext)
        fakeRecommendations.timeOut = false
        fakeRecommendations.ytmRadioTracks = (2..22).map { testTrack("$it", "Fresh $it", "Other $it") }
        controller.advanceNext()
        assertEquals("2", controller.state.value.current?.track?.id)
    }

    @Test fun playedTimelineHasNoTwoHundredSongCutoffAndDropsOldLyrics() {
        val controller = createController()
        val lyrics = listOf(dev.amatv.model.LyricLine(words = listOf(dev.amatv.model.LyricWord("word", 0, 1_000))))
        controller.playContext((1..320).map { testTrack("$it", "Song $it", "Artist").copy(lyrics = lyrics) }, 0)
        repeat(310) {
            controller.markCurrentPlaybackStarted(controller.state.value.current!!.entryId)
            controller.advanceNext()
        }
        assertEquals(310, controller.state.value.history.size)
        assertEquals(310, controller.state.value.currentTimelineIndex)
        assertEquals(320, controller.state.value.timelineEntries.size)
        assertEquals("1", controller.state.value.history.first().track.id)
        assertTrue(controller.state.value.history.all { it.track.lyrics.isEmpty() && it.wasPlayed })
    }

    @Test fun unplayedAlbumPrefixRemainsNavigableWithoutPretendingToBePlayed() {
        val controller = createController()
        controller.playContext((1..5).map { testTrack("$it", "Song $it", "Artist") }, 2)
        assertEquals(2, controller.state.value.history.size)
        assertEquals(0, controller.state.value.currentTimelineIndex)
        assertEquals(listOf("3", "4", "5"), controller.state.value.timelineEntries.map { it.track.id })
        assertEquals(5, controller.state.value.sessionEntries.size)
        assertEquals("2", controller.advancePrevious(0)?.track?.id)
    }

    @Test fun tappingPlayedHistoryPreservesFutureRailSeedContextAndSessionIdentity() {
        prefs.autoplayEnabled = true
        fakeRecommendations.ytmRadioTracks = (2..22).map { testTrack("$it", "Candidate $it", "Artist $it") }
        val controller = createController()
        controller.playContext(listOf(testTrack("1", "Anchor", "Original")), 0)
        controller.markCurrentPlaybackStarted(controller.state.value.current!!.entryId)
        controller.advanceNext()
        controller.markCurrentPlaybackStarted(controller.state.value.current!!.entryId)
        controller.advanceNext()
        val before = controller.state.value
        val future = before.autoplay.map { it.entryId }
        assertTrue(controller.selectTimelineEntry(before.history.first().entryId))
        val after = controller.state.value
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.persistenceId, after.persistenceId)
        assertEquals(before.contextEntries, after.contextEntries)
        assertEquals(before.recommendationSeed, after.recommendationSeed)
        assertEquals(before.ytmContinuation, after.ytmContinuation)
        assertEquals(future, after.autoplay.map { it.entryId })
        assertEquals(listOf("2", "3"), after.upNext.map { it.track.id })
    }

    @Test fun timelineSelectUsesEntryIdToDistinguishRepeatedRecordings() {
        val controller = createController()
        val repeat = testTrack("1", "Repeated", "Artist")
        controller.playContext(listOf(repeat, repeat, testTrack("2", "Last", "Other")), 0)
        val firstId = controller.state.value.current!!.entryId
        controller.markCurrentPlaybackStarted(firstId)
        controller.advanceNext()
        val secondId = controller.state.value.current!!.entryId
        controller.markCurrentPlaybackStarted(secondId)
        controller.advanceNext()
        assertTrue(controller.selectTimelineEntry(firstId))
        assertEquals(firstId, controller.state.value.current?.entryId)
        assertEquals(secondId, controller.advanceNext()?.entryId)
    }

    @Test fun restoredSessionAndConfigurationChangesDoNotFetchBeforeExplicitPlay() {
        prefs.autoplayEnabled = true
        val controller = createController()
        val anchor = testTrack("1", "Anchor", "Artist")
        controller.restoreFromSession(PlaybackSession(
            listOf(anchor), 0, 10_000, true, Destination.NowPlaying, Destination.Home,
            true, anchor, null,
        ))
        controller.setInfinite(false)
        controller.setAutoplaySource(AutoplaySource.AppleMusic)
        controller.setInfinite(true)
        controller.triggerRefillIfNeeded()
        assertTrue(fakeRecommendations.requests.isEmpty())
        assertTrue(fakeRecommendations.appleSeeds.isEmpty())
        controller.activatePlaybackSession()
        assertEquals(listOf("1"), fakeRecommendations.appleSeeds)
    }

    @Test fun confirmedPlayedHistoryRemainsVisibleWhenLaterOccurrenceFailsAndPreviousSkipsIt() {
        val controller = createController()
        val first = testTrack("1", "First", "One")
        val unavailable = testTrack("2", "Later unavailable", "Two")
        val middle = testTrack("3", "Middle", "Three")
        val last = testTrack("4", "Last", "Four")
        controller.playContext(listOf(first, unavailable, middle, unavailable, last), 0)
        repeat(3) {
            controller.markCurrentPlaybackStarted(controller.state.value.current!!.entryId)
            controller.advanceNext()
        }
        controller.skipFailedCurrent(controller.state.value.current!!.entryId)
        val timelineIds = controller.state.value.timelineEntries.map { it.entryId }
        assertEquals(listOf("1", "2", "3", "4"), controller.state.value.timelineEntries.map { it.track.id })
        assertEquals("3", controller.advancePrevious(0)?.track?.id)
        assertEquals("1", controller.advancePrevious(0)?.track?.id)
        assertEquals("3", controller.advanceNext()?.track?.id)
        assertEquals(timelineIds, controller.state.value.timelineEntries.map { it.entryId })
        val oldSeed = controller.state.value.recommendationSeed
        val historicalUnavailable = controller.state.value.history.first { it.track.id == "2" }
        assertTrue(controller.selectTimelineEntry(historicalUnavailable.entryId))
        assertEquals(oldSeed, controller.state.value.recommendationSeed)
    }

    @Test fun restoredRadioModeOverridesStaleEnabledPreferenceWithoutEagerRequests() {
        prefs.autoplayEnabled = true
        val controller = createController()
        val anchor = testTrack("1", "Saved paused session", "Artist")
        controller.restoreFromSession(PlaybackSession(
            listOf(anchor), 0, 30_000, false, Destination.NowPlaying, Destination.Home,
            false, anchor, null,
        ))
        assertFalse(controller.state.value.infinite)
        assertTrue(fakeRecommendations.requests.isEmpty())
        assertTrue(fakeRecommendations.appleSeeds.isEmpty())
        controller.activatePlaybackSession()
        assertTrue(fakeRecommendations.requests.isEmpty())
        assertTrue(fakeRecommendations.appleSeeds.isEmpty())
    }

    @Test fun defaultSourceMakesNoYtmRequestUntilTheUserSelectsYtm() {
        prefs.autoplaySource = autoplaySourceFromStoredValue(null)
        prefs.autoplayEnabled = true
        fakeRecommendations.amStationTracks = listOf(testTrack("2", "Apple suggestion", "Second"))
        fakeRecommendations.ytmRadioTracks = listOf(testTrack("3", "YTM suggestion", "Third"))
        val controller = createController()
        assertEquals(AutoplaySource.AppleMusic, controller.state.value.source)
        assertTrue(fakeRecommendations.requests.isEmpty())
        controller.playContext(listOf(testTrack("1", "Selected", "First")), 0)
        assertEquals(listOf("1"), fakeRecommendations.appleSeeds)
        assertTrue(fakeRecommendations.requests.isEmpty())
        controller.advanceNext(manual = false)
        assertTrue(fakeRecommendations.requests.isEmpty())

        controller.setAutoplaySource(AutoplaySource.YouTubeMusic)
        assertEquals(AutoplaySource.YouTubeMusic, prefs.autoplaySource)
        assertEquals(AutoplaySource.YouTubeMusic, controller.state.value.source)
        assertTrue(fakeRecommendations.requests.isNotEmpty())
        assertEquals(null to null, fakeRecommendations.requests.first())
    }

    @Test fun staleYtmCheckpointAndFullSessionCannotOverrideApplePreference() {
        prefs.autoplaySource = AutoplaySource.AppleMusic
        prefs.autoplayEnabled = true
        val controller = createController()
        val earlier = testTrack("1", "Earlier", "First")
        val current = testTrack("2", "Current", "Second")
        val explicitNext = testTrack("3", "Playlist next", "Third")
        val pendingRecommendation = testTrack("4", "Old YTM suggestion", "Fourth")
        val saved = PlaybackSession(
            queue = listOf(earlier, current, explicitNext, pendingRecommendation),
            currentIndex = 1, positionMs = 42_000, wasPlaying = false,
            destination = Destination.NowPlaying, previousDestination = Destination.Home,
            radioActive = true, radioSeed = earlier, radioContinuation = "old-ytm-cursor",
            autoplaySource = AutoplaySource.YouTubeMusic, radioSeedVideoId = "old-video",
            autoplayEntryIndices = setOf(1, 3), queueEntryIds = listOf(11L, 12L, 13L, 14L),
            playedEntryIds = setOf(11L, 12L), contextQueue = listOf(earlier, explicitNext),
            contextEntryIds = listOf(11L, 13L), persistenceId = "saved-session",
        )
        controller.restoreFromSession(saved)
        controller.restoreFromSession(saved.copy(radioContinuation = "full-session-ytm-cursor"))
        val state = controller.state.value
        assertEquals(AutoplaySource.AppleMusic, state.source)
        assertEquals(AutoplaySource.AppleMusic, prefs.autoplaySource)
        assertEquals(listOf(11L), state.history.map { it.entryId })
        assertEquals(12L, state.current?.entryId)
        assertEquals(listOf(13L), state.upNext.map { it.entryId })
        assertEquals(listOf(11L, 13L), state.contextEntries.map { it.entryId })
        assertEquals("saved-session", state.persistenceId)
        assertTrue(state.autoplay.isEmpty())
        assertNull(state.ytmContinuation)
        assertNull(state.ytmSeedVideoId)
        assertTrue(fakeRecommendations.requests.isEmpty())
        assertTrue(fakeRecommendations.appleSeeds.isEmpty())
        assertTrue(playedTracks.isEmpty())
        controller.activatePlaybackSession()
        assertEquals(listOf("1"), fakeRecommendations.appleSeeds)
        assertTrue(fakeRecommendations.requests.isEmpty())
    }

    @Test fun explicitYtmPreferenceSurvivesAppleSessionAndStartsWithAFreshCursor() {
        prefs.autoplaySource = AutoplaySource.YouTubeMusic
        prefs.autoplayEnabled = true
        val controller = createController()
        val current = testTrack("1", "Current", "First")
        controller.restoreFromSession(PlaybackSession(
            queue = listOf(current, testTrack("2", "Old Apple suggestion", "Second")),
            currentIndex = 0, positionMs = 0, wasPlaying = false,
            destination = Destination.NowPlaying, previousDestination = Destination.Home,
            radioActive = true, radioSeed = current, radioContinuation = "foreign-cursor",
            autoplaySource = AutoplaySource.AppleMusic, radioSeedVideoId = "foreign-video",
            autoplayEntryIndices = setOf(1), queueEntryIds = listOf(21L, 22L),
        ))
        assertEquals(AutoplaySource.YouTubeMusic, controller.state.value.source)
        assertTrue(controller.state.value.autoplay.isEmpty())
        assertNull(controller.state.value.ytmContinuation)
        assertNull(controller.state.value.ytmSeedVideoId)
        assertTrue(fakeRecommendations.requests.isEmpty())
        controller.activatePlaybackSession()
        assertEquals(null to null, fakeRecommendations.requests.first())
        assertTrue(fakeRecommendations.appleSeeds.isEmpty())
    }

    @Test fun providerChangeKeepsPreviouslyPlayedForwardEntriesAndDropsOnlyNewRecommendations() {
        prefs.autoplaySource = AutoplaySource.AppleMusic
        val controller = createController()
        val current = testTrack("1", "Current", "First")
        controller.restoreFromSession(PlaybackSession(
            queue = listOf(current, testTrack("2", "Already played", "Second"), testTrack("3", "Unplayed", "Third")),
            currentIndex = 0, positionMs = 0, wasPlaying = false,
            destination = Destination.NowPlaying, previousDestination = Destination.Home,
            radioActive = true, radioSeed = current, radioContinuation = "old-cursor",
            autoplaySource = AutoplaySource.YouTubeMusic, autoplayEntryIndices = setOf(1, 2),
            queueEntryIds = listOf(31L, 32L, 33L), playedEntryIds = setOf(32L),
        ))
        assertEquals(31L, controller.state.value.current?.entryId)
        assertEquals(listOf(32L), controller.state.value.autoplay.map { it.entryId })
        assertTrue(controller.state.value.autoplay.single().wasPlayed)
        assertTrue(fakeRecommendations.requests.isEmpty())
    }

    @Test fun resetClearsAllQueuesAndRestoresDefaultState() {
        val controller = createController()
        val current = testTrack("1", "Current", "First")
        controller.playContext(listOf(current, testTrack("2", "Upcoming", "Second")), startIndex = 0)
        assertNotNull(controller.state.value.current)
        assertEquals(1, controller.state.value.upNext.size)

        controller.reset()
        assertNull(controller.state.value.current)
        assertTrue(controller.state.value.upNext.isEmpty())
        assertTrue(controller.state.value.autoplay.isEmpty())
        assertTrue(controller.state.value.history.isEmpty())
    }
}
