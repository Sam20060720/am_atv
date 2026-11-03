package dev.amatv.playback

import dev.amatv.integration.CollectionShuffleSample
import dev.amatv.integration.LibraryPage
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BufferedCollectionShuffleTest {
    private val collection = Track("playlist", "Playlist", "", "", 0L, emptyList(), emptyList(), catalogKind = CatalogKind.Playlist)
    private fun song(id: String) = collection.copy(id = id, catalogKind = CatalogKind.Song, catalogId = id)
    private val sample get() = CollectionShuffleSample(song("repeat"), 3, 4, "/first")
    private fun first() = LibraryPage(listOf(song("repeat")), "/second", "/first", 4, 2, listOf(0)) // A, video.
    private fun last() = LibraryPage(listOf(song("middle"), song("repeat")), null, "/second", 4, 2, listOf(0, 1))

    @Test fun noPartialTailEscapesAndCheckpointRestartsTheUnpublishedPopulation() = runBlocking {
        val gate = CompletableDeferred<LibraryPage>()
        val requests = mutableListOf<String?>()
        val cursor = CollectionPlaybackContinuation.sampledShuffle(collection, sample) { _, path ->
            requests += path
            if (path == "/first") first() else gate.await()
        }
        cursor.bindShuffleAnchor(17L)
        val result = async(start = CoroutineStart.UNDISPATCHED) { cursor.loadNext() }
        yield()
        assertFalse(result.isCompleted)
        val checkpoint = checkNotNull(cursor.checkpoint("a".repeat(32)))
        assertEquals("/first", checkpoint.nextPage)
        assertTrue(checkpoint.visitedPages.isEmpty())
        assertEquals(3, checkpoint.shuffle?.rawIndex)
        assertEquals(17L, checkpoint.shuffle?.anchorEntryId)
        gate.complete(last())
        val complete = checkNotNull(result.await())
        assertTrue(complete.complete)
        assertEquals(listOf("repeat", "middle"), complete.tracks.map { it.id }) // The OTHER A remains.
        assertEquals(2, complete.shuffleSourceIndex)
        assertEquals(17L, complete.shuffleAnchorEntryId)
        assertNull(cursor.loadNext())

        val restoredRequests = mutableListOf<String?>()
        val restored = CollectionPlaybackContinuation.restore(checkpoint) { _, path ->
            restoredRequests += path
            if (path == "/first") first() else last()
        }
        assertEquals(complete, restored.loadNext())
        assertEquals(listOf("/first", "/second"), restoredRequests)
    }

    @Test fun failedPageDoesNotDuplicateTheBufferAndPopulationChangesFailUntilRetried() = runBlocking {
        var calls = 0
        val paths = mutableListOf<String?>()
        val cursor = CollectionPlaybackContinuation.sampledShuffle(collection, sample) { _, path ->
            paths += path
            if (path == "/first") first() else when (++calls) {
                1 -> last().copy(total = 5)
                2 -> last().copy(items = listOf(song("middle"), song("moved")))
                else -> last()
            }
        }
        cursor.bindShuffleAnchor(17L)
        repeat(2) {
            try { cursor.loadNext(); fail("Changed population/selected occurrence must fail") }
            catch (_: IllegalStateException) { }
        }
        assertEquals(listOf("repeat", "middle"), cursor.loadNext()!!.tracks.map { it.id })
        assertEquals(listOf("/first", "/second", "/second", "/second"), paths)
    }

    @Test fun cancellationRejectsANonCooperativeLatePopulation() = runBlocking {
        val pending = CompletableDeferred<LibraryPage>()
        val started = CompletableDeferred<Unit>()
        val cursor = CollectionPlaybackContinuation.sampledShuffle(collection, sample) { _, path ->
            if (path == "/first") first() else withContext(NonCancellable) { started.complete(Unit); pending.await() }
        }
        cursor.bindShuffleAnchor(17L)
        val result = async(start = CoroutineStart.UNDISPATCHED) { cursor.loadNext() }
        started.await()
        cursor.cancel()
        pending.complete(last())
        try { result.await(); fail("Replaced context cannot publish its tail") }
        catch (_: CancellationException) { }
        assertNull(cursor.checkpoint("a".repeat(32)))
    }
}
