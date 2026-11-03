package dev.amatv.playback

import dev.amatv.integration.LibraryPage
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CollectionPlaybackCheckpointTest {
    private fun song(id: String) = Track(id, id, "Artist", "", 180_000L, emptyList(), emptyList())
    private val collection = song("p.test").copy(catalogKind = CatalogKind.Playlist, resourceId = "p.test", resourceType = "library-playlists")
    private val scope = "a".repeat(32)

    @Test fun cursorCheckpointFollowsOnlyCommittedPagesAndCompletionRemovesIt() = runBlocking {
        val cursor = CollectionPlaybackContinuation(collection, "second", setOf("first"), 300) { _, page ->
            if (page == "second") LibraryPage(listOf(song("same"), song("same")), "third", "second", 300)
            else LibraryPage(listOf(song("last")), null, "third", 300)
        }
        assertEquals("second", cursor.checkpoint(scope)?.nextPage)
        cursor.loadNext()
        val saved = cursor.checkpoint(scope)!!
        assertEquals("third", saved.nextPage)
        assertEquals(setOf("first", "second"), saved.visitedPages)
        cursor.loadNext()
        assertNull(cursor.checkpoint(scope))
    }

    @Test fun restoreKeepsSourceAndOpaqueCursorWithoutLoadingUntilExplicitlyConsumed() = runBlocking {
        val saved = CollectionPlaybackCheckpoint(collection, "next?opaque=a%2Fb", setOf("first"), 200, scope)
        var requests = 0
        val restored = CollectionPlaybackContinuation.restore(saved) { source, cursor ->
            requests++
            assertEquals("p.test", source.resourceId)
            assertEquals("next?opaque=a%2Fb", cursor)
            LibraryPage(listOf(song("same"), song("same")), null, checkNotNull(cursor), 200)
        }
        assertEquals(0, requests)
        assertEquals(listOf("same", "same"), restored.loadNext()!!.tracks.map { it.id })
        assertEquals(1, requests)
    }

    @Test fun discardedOrGuestCursorCannotBePersistedAndMalformedHistoryIsRejected() {
        val cursor = CollectionPlaybackContinuation(collection, "second", setOf("first"), 300) { _, page ->
            LibraryPage(emptyList(), null, checkNotNull(page))
        }
        assertNull(cursor.checkpoint("guest"))
        cursor.cancel()
        assertNull(cursor.checkpoint(scope))
        try {
            CollectionPlaybackCheckpoint(collection, "next", (1..513).map { "page-$it" }.toSet(), 100, scope)
            fail("Unbounded saved page history must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
