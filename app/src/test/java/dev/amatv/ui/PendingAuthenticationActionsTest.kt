package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.playback.CollectionPlaybackContinuation
import dev.amatv.playback.CollectionPlaybackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PendingAuthenticationActionsTest {
    private fun song(id: String) = Track(id, id, "Artist", "Album", 180_000, emptyList(), emptyList(), catalogId = id)
    private fun collection() = song("playlist").copy(catalogKind = CatalogKind.Playlist)

    @Test fun stationIntentSurvivesAuthenticationAndIsConsumedOnce() {
        val pending = PendingAuthenticationActions()
        pending.remember(PendingAuthenticationAction.PlayTrack(song("seed"), origin = Destination.Search, startStation = true))
        val attempt = pending.beginAttempt()
        val action = pending.completeAttempt(attempt) as PendingAuthenticationAction.PlayTrack
        assertTrue(action.startStation)
        assertEquals("seed", action.track.id)
        assertEquals(Destination.Search, action.origin)
        assertNull(pending.completeAttempt(attempt))
    }
    private fun continuation(): CollectionPlaybackContinuation = CollectionPlaybackContinuation(
        collection(), "second", setOf("first"), 3,
    ) { _, cursor -> LibraryPage(listOf(song("tail")), null, checkNotNull(cursor)) }

    @Test fun latestSelectionWinsAndIsConsumedExactlyOnce() {
        val pending = PendingAuthenticationActions()
        val old = pending.remember(PendingAuthenticationAction.PlayTrack(song("first"), origin = Destination.Search))
        val latest = pending.remember(PendingAuthenticationAction.PlayTrack(song("second"), origin = Destination.Library))
        assertNull(pending.take(old))
        val taken = pending.take(latest) as PendingAuthenticationAction.PlayTrack
        assertEquals("second", taken.track.id)
        assertEquals(Destination.Library, taken.origin)
        assertNull(pending.take(latest))
        assertFalse(pending.hasPending)
    }

    @Test fun snapshotKeepsOrderedRepeatedOccurrencesIfCallerChangesItsList() {
        val pending = PendingAuthenticationActions()
        val queue = mutableListOf(song("same"), song("other"), song("same"))
        pending.remember(PendingAuthenticationAction.PlayTrack(queue.last(), queue, Destination.Home))
        queue.clear()
        val taken = pending.take() as PendingAuthenticationAction.PlayTrack
        assertEquals(listOf("same", "other", "same"), taken.contextQueue.map { it.id })
    }

    @Test fun failedAttemptDoesNotConsumeSelectionOrAllowALateCompletion() {
        val pending = PendingAuthenticationActions()
        pending.remember(PendingAuthenticationAction.PlayTrack(song("chosen"), origin = Destination.Browse))
        val failed = pending.beginAttempt()
        pending.failAttempt(failed)
        assertTrue(pending.hasPending)
        assertNull(pending.completeAttempt(failed))
        val retry = pending.beginAttempt()
        assertEquals("chosen", (pending.completeAttempt(retry) as PendingAuthenticationAction.PlayTrack).track.id)
        assertNull(pending.completeAttempt(retry))
    }

    @Test fun oldAttemptCannotConsumeNewSelectionOrStealFromNewAttempt() {
        val pending = PendingAuthenticationActions()
        pending.remember(PendingAuthenticationAction.PlayTrack(song("old"), origin = Destination.Search))
        val old = pending.beginAttempt()
        pending.remember(PendingAuthenticationAction.PlayTrack(song("new"), origin = Destination.Library))
        assertNull(pending.completeAttempt(old))
        assertEquals("new", (pending.peek() as PendingAuthenticationAction.PlayTrack).track.id)
        val retry = pending.beginAttempt()
        assertNull(pending.completeAttempt(old))
        assertEquals("new", (pending.completeAttempt(retry) as PendingAuthenticationAction.PlayTrack).track.id)
    }

    @Test fun accountOnlyLoginCannotAutoplayAChoiceMadeAfterItBegan() {
        val pending = PendingAuthenticationActions()
        val accountLogin = pending.beginAttempt()
        pending.remember(PendingAuthenticationAction.PlayTrack(song("later"), origin = Destination.Home))
        assertNull(pending.completeAttempt(accountLogin))
        assertTrue(pending.hasPending)
    }

    @Test fun explicitDismissalInvalidatesAttemptsAndCancelsUnownedCollectionTail() = runBlocking {
        val pending = PendingAuthenticationActions()
        val tail = continuation()
        pending.remember(PendingAuthenticationAction.PlayCollection(
            CollectionPlaybackRequest(collection(), listOf(song("first")), 0, false, tail), Destination.Browse,
        ))
        val auth = pending.beginAttempt()
        pending.clear()
        assertNull(pending.completeAttempt(auth))
        assertFalse(pending.hasPending)
        try { tail.loadNext(); fail("Dismissed playback must cancel its tail") }
        catch (_: CancellationException) { }
    }

    @Test fun replacingCollectionCancelsOnlyDiscardedTailAndTakeTransfersOwnership() = runBlocking {
        val pending = PendingAuthenticationActions()
        val discarded = continuation()
        val kept = continuation()
        fun action(tail: CollectionPlaybackContinuation) = PendingAuthenticationAction.PlayCollection(
            CollectionPlaybackRequest(collection(), listOf(song("same"), song("same")), 1, false, tail), Destination.Library,
        )
        pending.remember(action(discarded))
        pending.remember(action(kept))
        try { discarded.loadNext(); fail("Replaced tail must be cancelled") }
        catch (_: CancellationException) { }
        val taken = pending.take() as PendingAuthenticationAction.PlayCollection
        pending.clear()
        assertEquals(1, taken.request.startIndex)
        assertEquals(listOf("same", "same"), taken.request.tracks.map { it.id })
        assertSame(kept, taken.request.continuation)
        assertEquals(listOf("tail"), checkNotNull(kept.loadNext()).tracks.map { it.id })
    }

    @Test fun rememberingSameTransferredCursorDoesNotAccidentallyCancelIt() = runBlocking {
        val pending = PendingAuthenticationActions()
        val tail = continuation()
        val request = CollectionPlaybackRequest(collection(), listOf(song("first")), 0, false, tail)
        pending.remember(PendingAuthenticationAction.PlayCollection(request, Destination.Home))
        pending.remember(PendingAuthenticationAction.PlayCollection(request, Destination.Library))
        assertNotNull(tail.loadNext())
    }
    @Test fun resumeRetainsCursorAndAcceptsOnlyTheOriginalSessionOccurrence() {
        val pending = PendingAuthenticationActions()
        val selected = song("same")
        pending.remember(PendingAuthenticationAction.ResumeCurrent(selected, 7L, 11L, 67_000L, Destination.NowPlaying))
        val action = pending.take() as PendingAuthenticationAction.ResumeCurrent
        assertEquals(67_000L, action.positionMs)
        assertTrue(action.matchesCurrent(selected.copy(title = "Updated title", artworkUrl = "https://example.test/new"), 7L, 11L))
        assertFalse("Same recording at another occurrence is not the original request", action.matchesCurrent(selected, 7L, 12L))
        assertFalse("New queue containing the same recording cannot inherit old autoplay", action.matchesCurrent(selected, 8L, 11L))
        assertFalse(action.matchesCurrent(song("other"), 7L, 11L))
        assertFalse(action.matchesCurrent(null, 7L, 11L))
    }

    @Test fun aMetadataOnlyResumeCannotMatchANewHydratedOccurrence() {
        val selected = song("same")
        val action = PendingAuthenticationAction.ResumeCurrent(selected, 0L, null, 6_000L, Destination.NowPlaying)
        assertFalse(action.matchesCurrent(selected, 0L, 1L))
        assertFalse(action.matchesCurrent(selected, 1L, null))
        assertTrue(action.matchesCurrent(selected, 0L, null))
    }

}
