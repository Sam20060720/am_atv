package dev.amatv.playback

import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.MusicServices
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.QueuePreferences
import dev.amatv.integration.RecommendationProvider
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class QueueEditingAndContinuationTest {
    private class Preferences : QueuePreferences {
        override var repeatMode = PlaybackRepeatMode.Off
        override var shuffleEnabled = false
        override var autoplayEnabled = false
        override var autoplaySource = AutoplaySource.AppleMusic
        override var lowPerformanceMode = false
    }
    private class Recommendations : RecommendationProvider {
        var calls = 0
        var pending: CompletableDeferred<Pair<String?, List<Track>>>? = null
        override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> {
            calls++
            return pending?.await() ?: (null to emptyList())
        }
        override suspend fun amStationNextTracks(stationId: String): List<Track> = emptyList()
        override suspend fun ytmRadio(seed: Track, continuation: String?, excludedCatalogIds: Set<String>,
            continuationSeedVideoId: String?): MusicServices.ResolvedRadioPage =
            MusicServices.ResolvedRadioPage(emptyList(), null, null)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val preferences = Preferences()
    private val recommendations = Recommendations()
    private val played = mutableListOf<String>()
    private val controller = PlaybackQueueController(scope, recommendations, preferences,
        { played += it.id }, Dispatchers.Unconfined)
    private fun song(id: String) = Track(id, id, "Artist", "Album", 120_000,
        emptyList(), emptyList(), catalogId = id, catalogKind = CatalogKind.Song)
    @After fun close() = scope.cancel()

    @Test fun enablingInfinityClearsRepeatInBothStateAndPreferences() {
        controller.playContext(listOf(song("one"), song("two")), 0)
        controller.setRepeat(PlaybackRepeatMode.One)
        controller.setInfinite(true)
        assertEquals(PlaybackRepeatMode.Off, controller.state.value.repeat)
        assertEquals(PlaybackRepeatMode.Off, preferences.repeatMode)
        assertTrue(controller.state.value.infinite)
    }

    @Test fun aQueuedNativeSnapshotCannotSkipANewlyInsertedAdjacentOccurrence() {
        controller.playContext(listOf(song("current"), song("next"), song("later")), 0)
        val originalNext = controller.state.value.upNext.first()
        assertTrue(controller.moveUpcoming(controller.state.value.upNext.last().entryId, 0))
        val edited = controller.state.value
        assertFalse(controller.adoptNativeCurrent(originalNext.entryId))
        assertEquals(edited, controller.state.value)
        assertTrue(controller.adoptNativeCurrent(edited.upNext.first().entryId))
    }

    @Test fun providerIdentityInvalidationKeepsExplicitQueueAndDropsPrivateRecommendationsAndCursor() {
        preferences.autoplaySource = AutoplaySource.YouTubeMusic
        val tracks = listOf(song("past"), song("current"), song("explicit"), song("old-private"))
        controller.restoreFromSession(PlaybackSession(tracks, 1, 0, false, Destination.NowPlaying,
            Destination.Home, radioActive = true, radioSeed = tracks[1], radioContinuation = "old-cursor", radioSeedVideoId = "old-seed",
            autoplaySource = AutoplaySource.YouTubeMusic, autoplayEntryIndices = setOf(3),
            queueEntryIds = listOf(1L, 2L, 3L, 4L)))
        val before = controller.state.value
        assertEquals(1, before.autoplay.size)
        controller.invalidateRecommendationAuthorization()
        val after = controller.state.value
        assertEquals(before.history, after.history)
        assertEquals(before.current, after.current)
        assertEquals(before.upNext, after.upNext)
        assertTrue(after.autoplay.isEmpty())
        assertNull(after.ytmContinuation)
        assertNull(after.ytmSeedVideoId)
    }

    @Test fun removeOnlyTheChosenDuplicateAndDoNotRestartOrAlterCurrentHistory() {
        controller.playContext(listOf(song("past"), song("current"), song("repeat"), song("repeat")), 1)
        val before = controller.state.value
        val removed = before.upNext.first().entryId
        assertTrue(controller.removeUpcoming(removed))
        val after = controller.state.value
        assertEquals(before.current, after.current)
        assertEquals(before.history, after.history)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(listOf(before.upNext.last()), after.upNext)
        assertFalse(after.contextEntries.any { it.entryId == removed })
        assertEquals(listOf("current"), played)
        assertFalse(controller.removeUpcoming(before.current!!.entryId))
        assertFalse(controller.removeUpcoming(before.history.first().entryId))
        assertFalse(controller.removeUpcoming(removed))
    }

    @Test fun userOrderAndRemovalSurviveShuffleOffAndRepeatAll() {
        controller.playContext((1..4).map { song("$it") }, 0)
        controller.setShuffle(true)
        val move = controller.state.value.upNext.last().entryId
        assertTrue(controller.moveUpcoming(move, 0))
        val ordered = controller.state.value.upNext
        val removed = ordered.last().entryId
        assertTrue(controller.removeUpcoming(removed))
        val edited = controller.state.value.upNext
        controller.setShuffle(false)
        assertEquals(edited, controller.state.value.upNext)
        controller.setRepeat(PlaybackRepeatMode.All)
        repeat(edited.size) { controller.advanceNext() }
        controller.advanceNext(manual = false)
        assertEquals("1", controller.state.value.current!!.track.id)
        assertEquals(edited.map { it.track.id }, controller.state.value.upNext.map { it.track.id })
        assertFalse(controller.state.value.contextEntries.any { it.entryId == removed })
    }

    @Test fun editingTheSecondRepeatCycleKeepsDistinctSourceOccurrencesWithoutGrowingTheNextCycle() {
        controller.playContext(listOf(song("A"), song("B"), song("B"), song("C")), 0)
        val sourceIds = controller.state.value.contextEntries.map { it.entryId }
        controller.setRepeat(PlaybackRepeatMode.All)
        repeat(4) { controller.advanceNext() }
        val second = controller.state.value
        assertEquals(sourceIds, (listOf(second.current!!) + second.upNext).map { it.sourceEntryId })
        assertTrue(second.upNext.none { it.entryId in sourceIds })

        assertTrue(controller.moveUpcoming(second.upNext.last().entryId, 0))
        assertEquals(listOf(sourceIds[0], sourceIds[3], sourceIds[1], sourceIds[2]),
            controller.state.value.contextEntries.map { it.entryId })
        repeat(4) { controller.advanceNext() }
        val third = controller.state.value
        assertEquals(listOf("A", "C", "B", "B"), (listOf(third.current!!) + third.upNext).map { it.track.id })
        assertEquals(4, third.contextEntries.size)
        assertEquals(4, third.contextEntries.map { it.sourceEntryId }.toSet().size)
        assertEquals(listOf(sourceIds[3], sourceIds[1], sourceIds[2]), third.upNext.map { it.sourceEntryId })
    }

    @Test fun previousAcrossRepeatCyclesKeepsEnqueueRemoveAndMoveAttachedToTheirSourceOccurrence() {
        controller.playContext(listOf(song("A"), song("B"), song("C")), 0)
        val sources = controller.state.value.contextEntries
        controller.setRepeat(PlaybackRepeatMode.All)
        repeat(3) { controller.advanceNext() }
        assertTrue(controller.enqueueNext(listOf(song("next"))))
        assertEquals(listOf("A", "next", "B", "C"), controller.state.value.contextEntries.map { it.track.id })
        controller.advancePrevious(0)
        controller.advancePrevious(0)
        assertEquals(sources[1].entryId, controller.state.value.current!!.entryId)
        val secondB = controller.state.value.upNext.single { it.sourceEntryId == sources[1].entryId }
        assertTrue(controller.removeUpcoming(secondB.entryId))
        assertFalse(controller.state.value.contextEntries.any { it.entryId == sources[1].entryId })
        val secondC = controller.state.value.upNext.last { it.sourceEntryId == sources[2].entryId }
        assertTrue(controller.moveUpcoming(secondC.entryId, 0))
        val state = controller.state.value
        assertEquals(listOf("C", "A", "next"), state.contextEntries.map { it.track.id })
        assertEquals(3, state.contextEntries.map { it.entryId }.toSet().size)
        assertEquals(2, state.upNext.count { it.sourceEntryId == sources[2].entryId })
    }

    @Test fun restoredRepeatOccurrencesStillEditTheCanonicalSourceAndCheckpointFallback() {
        val tracks = listOf(song("A"), song("B"), song("C"))
        val session = PlaybackSession(
            queue = tracks + tracks, currentIndex = 3, positionMs = 0, wasPlaying = false,
            destination = Destination.NowPlaying, previousDestination = Destination.Home,
            radioActive = false, radioSeed = null, radioContinuation = null,
            queueEntryIds = (1L..6L).toList(), queueSourceEntryIds = listOf(1L, 2L, 3L, 1L, 2L, 3L),
            contextQueue = tracks, contextEntryIds = listOf(1L, 2L, 3L),
        )
        controller.restoreFromSession(session)
        assertTrue(controller.moveUpcoming(6L, 0))
        assertEquals(listOf(1L, 3L, 2L), controller.state.value.contextEntries.map { it.entryId })

        controller.restoreFromSession(session.copy(contextQueue = emptyList(), contextEntryIds = emptyList()))
        assertEquals(listOf(1L, 2L, 3L), controller.state.value.contextEntries.map { it.entryId })
        assertTrue(controller.enqueueNext(listOf(song("next"))))
        assertEquals(listOf("A", "next", "B", "C"), controller.state.value.contextEntries.map { it.track.id })
    }

    @Test fun movingRecommendationMakesAStableExplicitChoiceWithoutChangingThePlayingItem() {
        controller.setInfinite(true)
        recommendations.pending = CompletableDeferred()
        controller.playContext(listOf(song("current"), song("explicit")), 0)
        recommendations.pending!!.complete(null to listOf(song("radio-1"), song("radio-2"), song("radio-3")))
        val before = controller.state.value
        val chosen = before.autoplay[1].entryId
        assertTrue(controller.moveUpcoming(chosen, 0))
        val after = controller.state.value
        assertEquals(listOf("radio-2", "explicit"), after.upNext.map { it.track.id })
        assertEquals(listOf("radio-1", "radio-3"), after.autoplay.map { it.track.id })
        assertTrue(after.upNext.all { it.origin == EntryOrigin.Context })
        assertEquals(before.current, after.current)
        assertEquals(before.history, after.history)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(listOf("current"), played)
        controller.setShuffle(true)
        controller.setShuffle(false)
        assertEquals(listOf("radio-2", "explicit"), controller.state.value.upNext.map { it.track.id })
    }

    @Test fun movingExplicitPastRecommendationsKeepsDisplayAndPlaybackOrderIdentical() {
        controller.setInfinite(true)
        recommendations.pending = CompletableDeferred()
        controller.playContext(listOf(song("current"), song("explicit")), 0)
        recommendations.pending!!.complete(null to listOf(song("radio-1"), song("radio-2")))
        val chosen = controller.state.value.upNext.single().entryId
        assertTrue(controller.moveUpcoming(chosen, 2))
        val state = controller.state.value
        assertEquals(listOf("radio-1", "radio-2", "explicit"), state.upNext.map { it.track.id })
        assertTrue(state.autoplay.isEmpty())
        assertEquals(listOf("current", "radio-1", "radio-2", "explicit"), state.contextEntries.map { it.track.id })
        repeat(3) { controller.advanceNext() }
        assertEquals(listOf("current", "radio-1", "radio-2", "explicit"), played)
    }

    @Test fun clearStopsAutoRefillAndDiscardsTheUnplayedRepeatPopulationAndPendingTail() {
        controller.setInfinite(true)
        controller.playContext(listOf(song("past"), song("current"), song("future")), 1, pendingTail = true)
        val before = controller.state.value
        controller.setRepeat(PlaybackRepeatMode.All)
        controller.clearUpcoming()
        val after = controller.state.value
        assertFalse(after.infinite)
        assertFalse(preferences.autoplayEnabled)
        assertFalse(after.contextContinuationPending)
        assertEquals(before.current, after.current)
        assertEquals(before.history, after.history)
        assertEquals(listOf("past", "current"), after.contextEntries.map { it.track.id })
        assertTrue(after.upNext.isEmpty())
        assertFalse(controller.appendContextTail(after.sessionId, listOf(song("late")), true))
        controller.advanceNext(manual = false)
        assertEquals("past", controller.state.value.current!!.track.id)
        assertFalse(controller.state.value.contextEntries.any { it.track.id == "future" })
        assertEquals(0, recommendations.calls)
    }

    @Test fun clearInvalidatesAnAlreadyRunningRecommendationResponse() {
        controller.setInfinite(true)
        recommendations.pending = CompletableDeferred()
        controller.playContext(listOf(song("current")), 0)
        assertEquals(1, recommendations.calls)
        controller.clearUpcoming()
        recommendations.pending!!.complete(null to listOf(song("late")))
        controller.triggerRefillIfNeeded()
        assertTrue(controller.state.value.autoplay.isEmpty())
        assertEquals(1, recommendations.calls)
        assertEquals(listOf("current"), played)
    }

    @Test fun partialContextDoesNotGenerateAutoplayOrRepeatTheDownloadedPrefix() {
        controller.setInfinite(true)
        controller.playContext(listOf(song("one"), song("two")), 0, pendingTail = true)
        controller.setRepeat(PlaybackRepeatMode.All)
        controller.advanceNext()
        assertNull(controller.advanceNext(manual = false))
        assertEquals("two", controller.state.value.current!!.track.id)
        assertTrue(controller.state.value.awaitingNext)
        assertEquals(0, recommendations.calls)
        val session = controller.state.value.sessionId
        assertTrue(controller.appendContextTail(session, listOf(song("three"), song("three")), false))
        assertEquals(listOf("one", "two", "three"), played)
        assertEquals(1, controller.state.value.upNext.size)
        assertTrue(controller.state.value.contextContinuationPending)
        assertEquals(4, controller.state.value.contextEntries.map { it.entryId }.toSet().size)
        controller.advanceNext()
        assertNull(controller.advanceNext())
        assertTrue(controller.appendContextTail(session, emptyList(), true))
        assertEquals("one", controller.state.value.current!!.track.id)
        assertFalse(controller.state.value.contextContinuationPending)
    }

    @Test fun emptyFinalTailEndsAWaitingQueueWithoutReplayingOrLeavingAFalseWait() {
        controller.playContext(listOf(song("current")), 0, pendingTail = true)
        val session = controller.state.value.sessionId
        assertNull(controller.advanceNext())
        assertTrue(controller.state.value.awaitingNext)
        assertTrue(controller.appendContextTail(session, emptyList(), true))
        assertFalse(controller.state.value.awaitingNext)
        assertFalse(controller.state.value.contextContinuationPending)
        assertEquals(listOf("current"), played)
        assertFalse(controller.state.value.canSkipNext)
    }

    @Test fun failedTailIsRetryableAndNeverReplacedByAutoplayOrAnOldSessionPage() {
        controller.setInfinite(true)
        controller.playContext(listOf(song("current")), 0, pendingTail = true)
        val session = controller.state.value.sessionId
        assertTrue(controller.failContextTail(session))
        assertTrue(controller.state.value.contextContinuationPending)
        assertTrue(controller.state.value.contextContinuationFailed)
        assertFalse(controller.state.value.canSkipNext)
        assertNull(controller.advanceNext())
        assertFalse(controller.state.value.awaitingNext)
        assertTrue(controller.retryContextTail(session))
        assertFalse(controller.state.value.contextContinuationFailed)
        assertNull(controller.advanceNext())
        assertTrue(controller.state.value.awaitingNext)
        assertTrue(controller.appendContextTail(session, listOf(song("next")), true))
        assertEquals("next", controller.state.value.current!!.track.id)
        controller.playContext(listOf(song("replacement")), 0, pendingTail = true)
        assertFalse(controller.appendContextTail(session, listOf(song("stale")), true))
        assertFalse(controller.failContextTail(session))
        assertEquals("replacement", controller.state.value.current!!.track.id)
    }

    @Test fun nativeHandoffAdoptsTheExactDuplicateOccurrenceWithoutPreparingOrChangingSession() {
        controller.playContext(listOf(song("same"), song("same"), song("third")), 0)
        val before = controller.state.value
        val next = before.upNext.first()
        assertTrue(controller.adoptNativeCurrent(next.entryId))
        val after = controller.state.value
        assertEquals(next.entryId, after.current!!.entryId)
        assertEquals(listOf(before.current!!.entryId), after.history.map { it.entryId })
        assertTrue(after.history.single().wasPlayed)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.persistenceId, after.persistenceId)
        assertEquals(listOf("same"), played)
        assertTrue(controller.adoptNativeCurrent(next.entryId))
        assertEquals(after, controller.state.value)
        assertFalse(controller.adoptNativeCurrent(before.current!!.entryId))
        assertFalse(controller.adoptNativeCurrent(Long.MAX_VALUE))
    }

    @Test fun naturalNativeAutoplayHandoffsStillRefillTheReserve() {
        controller.setInfinite(true)
        recommendations.pending = CompletableDeferred()
        controller.playContext(listOf(song("current")), 0)
        recommendations.pending!!.complete(null to (1..20).map { song("radio-$it") })
        assertEquals(1, recommendations.calls)
        repeat(5) {
            val next = controller.state.value.autoplay.first()
            assertTrue(controller.adoptNativeCurrent(next.entryId))
        }
        assertEquals(2, recommendations.calls)
        assertEquals(listOf("current"), played)
        assertEquals("radio-5", controller.state.value.current!!.track.id)
    }

    @Test fun restoredTailMayBeMarkedPendingWithoutStartingAudioOrRecommendations() {
        controller.playContext(listOf(song("current"), song("future")), 0)
        val before = controller.state.value
        val context = PlaybackContext(ContextKind.Playlist, "playlist", "Playlist", "/page-2")
        assertTrue(controller.markContextTailPending(before.sessionId, context))
        assertTrue(controller.state.value.contextContinuationPending)
        assertEquals(context, controller.state.value.context)
        assertEquals(before.current, controller.state.value.current)
        assertEquals(before.upNext, controller.state.value.upNext)
        assertEquals(listOf("current"), played)
        assertEquals(0, recommendations.calls)
        controller.failContextTail(before.sessionId)
        assertTrue(controller.markContextTailPending(before.sessionId))
        assertFalse(controller.state.value.contextContinuationFailed)
        assertFalse(controller.markContextTailPending(before.sessionId + 1))
    }

    @Test fun playLastStaysAfterTheUnknownCollectionTailAndPagesDoNotRestartCurrentAudio() {
        controller.playContext(listOf(song("current"), song("prefix")), 0, pendingTail = true)
        val session = controller.state.value.sessionId
        val current = controller.state.value.current
        assertTrue(controller.enqueueLast(listOf(song("last"))))
        assertTrue(controller.enqueueNext(listOf(song("next"))))
        assertTrue(controller.appendContextTail(session, listOf(song("tail"), song("tail")), true))
        assertEquals(listOf("next", "prefix", "tail", "tail", "last"), controller.state.value.upNext.map { it.track.id })
        assertEquals(current, controller.state.value.current)
        assertEquals(listOf("current"), played)
        assertFalse(controller.moveUpcoming(current!!.entryId, 0))
        assertFalse(controller.moveUpcoming(controller.state.value.upNext.first().entryId, -1))
    }

    @Test fun playLastWaitsAtTheDownloadedEdgeUntilTheActualTailIsComplete() {
        controller.playContext(listOf(song("current"), song("prefix")), 0, pendingTail = true)
        val session = controller.state.value.sessionId
        assertTrue(controller.enqueueLast(listOf(song("last"))))
        val lastId = controller.state.value.upNext.last().entryId
        controller.advanceNext()
        assertNull(controller.advanceNext())
        assertTrue(controller.state.value.awaitingNext)
        assertFalse(controller.adoptNativeCurrent(lastId))
        assertEquals(listOf("current", "prefix"), played)

        assertTrue(controller.appendContextTail(session, listOf(song("tail")), complete = false))
        assertEquals("tail", controller.state.value.current!!.track.id)
        assertNull(controller.advanceNext())
        assertTrue(controller.state.value.awaitingNext)
        assertTrue(controller.appendContextTail(session, emptyList(), complete = true))
        assertEquals(lastId, controller.state.value.current!!.entryId)
        assertTrue(controller.state.value.deferredLastEntryIds.isEmpty())
        assertEquals(listOf("current", "prefix", "tail", "last"), played)
    }

    @Test fun restoredPlayLastBarrierSurvivesAuthenticatedContinuationAndFailureRetry() {
        val current = song("current")
        val last = song("last")
        controller.restoreFromSession(PlaybackSession(
            queue = listOf(current, last), currentIndex = 0, positionMs = 0, wasPlaying = false,
            destination = Destination.NowPlaying, previousDestination = Destination.Home,
            radioActive = false, radioSeed = null, radioContinuation = null,
            queueEntryIds = listOf(11L, 12L), contextQueue = listOf(current, last),
            contextEntryIds = listOf(11L, 12L), deferredLastEntryIds = setOf(12L),
        ))
        val session = controller.state.value.sessionId
        assertTrue(controller.markContextTailPending(session))
        assertNull(controller.advanceNext())
        assertTrue(controller.failContextTail(session))
        assertFalse(controller.state.value.canSkipNext)
        assertTrue(controller.retryContextTail(session))
        assertNull(controller.advanceNext())
        assertTrue(controller.appendContextTail(session, listOf(song("tail")), complete = true))
        assertEquals(listOf("tail"), played)
        assertEquals(listOf("last"), controller.state.value.upNext.map { it.track.id })
    }
}
