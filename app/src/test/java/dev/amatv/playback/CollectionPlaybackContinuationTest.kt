package dev.amatv.playback

import dev.amatv.integration.LibraryPage
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CollectionPlaybackContinuationTest {
    private val collection = Track("playlist", "Playlist", "", "", 0L, emptyList(), emptyList(),
        catalogKind = CatalogKind.Playlist)
    private fun song(id: String) = collection.copy(id = id, catalogKind = CatalogKind.Song)

    @Test fun sequentialPagesKeepSourceOrderAndEveryRepeatedOccurrence() = runBlocking {
        val repeated = song("repeated")
        val cursors = mutableListOf<String?>()
        val continuation = CollectionPlaybackContinuation(collection, "/second", setOf("/first"), 5) { source, next ->
            assertEquals(collection, source)
            cursors += next
            when (next) {
                "/second" -> LibraryPage(listOf(repeated, song("middle")), "/third", "/second")
                else -> LibraryPage(listOf(repeated, repeated), null, "/third")
            }
        }
        val second = checkNotNull(continuation.loadNext())
        assertFalse(second.complete)
        assertEquals(5, second.total)
        val third = checkNotNull(continuation.loadNext())
        assertTrue(third.complete)
        assertEquals(listOf("repeated", "middle", "repeated", "repeated"),
            (second.tracks + third.tracks).map { it.id })
        assertNull(continuation.loadNext())
        assertEquals(listOf("/second", "/third"), cursors)
    }

    @Test fun concurrentConsumersCannotFetchAndAppendTheSamePageTwice() = runBlocking {
        val gate = CompletableDeferred<LibraryPage>()
        val cursors = mutableListOf<String?>()
        val continuation = CollectionPlaybackContinuation(collection, "/second", setOf("/first"), null) { _, next ->
            cursors += next
            if (next == "/second") gate.await() else LibraryPage(listOf(song("last")), null, "/third")
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { continuation.loadNext() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { continuation.loadNext() }
        assertEquals(listOf("/second"), cursors)
        gate.complete(LibraryPage(listOf(song("next")), "/third", "/second"))
        assertEquals("next", first.await()!!.tracks.single().id)
        assertEquals("last", second.await()!!.tracks.single().id)
        assertEquals(listOf("/second", "/third"), cursors)
    }

    @Test fun cancelledTransportCannotCommitItsCursorAndRetryStartsAtSamePage() = runBlocking {
        val gate = CompletableDeferred<LibraryPage>()
        val cursors = mutableListOf<String?>()
        val continuation = CollectionPlaybackContinuation(collection, "/second", setOf("/first"), null) { _, next ->
            cursors += next
            if (cursors.size == 1) withContext(NonCancellable) { gate.await() }
            else LibraryPage(listOf(song("fresh")), null, "/second")
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { continuation.loadNext() }
        first.cancel()
        gate.complete(LibraryPage(listOf(song("stale")), "/third", "/second"))
        first.cancelAndJoin()
        assertEquals("fresh", continuation.loadNext()!!.tracks.single().id)
        assertEquals(listOf("/second", "/second"), cursors)
    }

    @Test fun replacingPlaybackCancelsEvenAnUncooperativeInFlightPage() = runBlocking {
        val gate = CompletableDeferred<LibraryPage>()
        val continuation = CollectionPlaybackContinuation(collection, "/second", setOf("/first"), null) { _, _ ->
            withContext(NonCancellable) { gate.await() }
        }
        // CancellationException from a child does not fail its parent scope.
        val first = async(start = CoroutineStart.UNDISPATCHED) { continuation.loadNext() }
        continuation.cancel()
        gate.complete(LibraryPage(listOf(song("stale")), null, "/second"))
        try { first.await(); fail("A replaced context must not append its tail") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        try { continuation.loadNext(); fail("A replaced cursor must remain closed") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun failureAndInvalidCycleLeaveTheLastCommittedCursorRetryable() = runBlocking {
        var calls = 0
        val cursors = mutableListOf<String?>()
        val continuation = CollectionPlaybackContinuation(collection, "/second", setOf("/first"), null) { _, next ->
            cursors += next
            when (++calls) {
                1 -> error("Network unavailable")
                2 -> LibraryPage(listOf(song("bad")), "/first", "/second")
                else -> LibraryPage(listOf(song("good")), null, "/second")
            }
        }
        repeat(2) {
            try { continuation.loadNext(); fail("An error/cycle cannot become a partial successful page") }
            catch (_: IllegalStateException) { }
        }
        assertEquals("good", continuation.loadNext()!!.tracks.single().id)
        assertEquals(listOf("/second", "/second", "/second"), cursors)
    }
}
