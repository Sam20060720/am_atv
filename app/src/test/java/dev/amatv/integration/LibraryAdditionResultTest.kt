package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryAdditionResultTest {
    private fun song(id: String = "123") = Track("apple:$id", "Song", "Artist", "Album", 1,
        emptyList(), emptyList(), catalogId = id, catalogKind = CatalogKind.Song, resourceId = id, resourceType = "songs")
    private fun state(id: String = "123", saved: Boolean) = JSONObject("""{"data":[{"id":"$id","type":"songs",
        "attributes":{"inFavorites":true},"relationships":{"library":{"data":[${if (saved) """{"id":"i.$id","type":"library-songs"}""" else ""}]}}}]}""")

    @Test fun rejectedOptionalLibraryAdditionDoesNotUndoConfirmedFavoriteOrRetryItsPost() = runBlocking {
        val posts = mutableListOf<String>()
        var rejected = true
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            if (method == "POST") {
                posts += path
                if (path.startsWith("/v1/me/library") && rejected)
                    throw CatalogRequestException(CatalogFailureReason.Authorization, "Rejected", 403)
                JSONObject()
            } else state(saved = !rejected)
        }
        val favorite = actions.addToFavoritesAndConfirm(song())
        assertTrue(favorite.confirmed)
        val partial = actions.addEligibleItemsToLibraryAndConfirm(listOf(song()))
        assertEquals(listOf(song()), partial.pendingItems)
        assertTrue(partial.retryAllowed)
        assertTrue(favorite.confirmed)
        rejected = false
        assertTrue(actions.addEligibleItemsToLibraryAndConfirm(partial.pendingItems).pendingItems.isEmpty())
        assertEquals(1, posts.count { it.startsWith("/v1/me/favorites") })
        assertEquals(2, posts.count { it.startsWith("/v1/me/library") })
    }

    @Test fun timeoutAfterOptionalLibraryWriteIsRecoveredOnlyByReadsAndKeepsPrimarySuccess() = runBlocking {
        var favoritePosts = 0
        var libraryPosts = 0
        var saved = false
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            when {
                method == "POST" && path.startsWith("/v1/me/favorites") -> { favoritePosts++; JSONObject() }
                method == "POST" -> { libraryPosts++; saved = true; throw IOException("Response lost") }
                else -> state(saved = saved)
            }
        }
        assertTrue(actions.addToFavoritesAndConfirm(song()).confirmed)
        val partial = actions.addEligibleItemsToLibraryAndConfirm(listOf(song()))
        assertFalse(partial.retryAllowed)
        assertEquals(1, partial.pendingItems.size)
        assertTrue(actions.libraryItemsAwaitingConfirmation(partial.pendingItems).isEmpty())
        assertTrue(actions.libraryItemsAwaitingConfirmation(partial.pendingItems).isEmpty())
        assertEquals(1, favoritePosts)
        assertEquals(1, libraryPosts)
    }

    @Test fun optionalBatchSkipsExistingLibrarySourcesAndUnsupportedFavoritesAndOnlyKeepsUnconfirmedItems() = runBlocking {
        var posts = 0
        var savedAll = false
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            if (method == "POST") {
                posts++
                assertEquals("/v1/me/library?ids%5Bsongs%5D=123%2C456", path)
                JSONObject()
            } else {
                val id = if ("/456?" in path) "456" else "123"
                state(id, saved = id == "123" || savedAll)
            }
        }
        val uploaded = song().copy(resourceId = "i.uploaded", resourceType = "library-songs", catalogId = null)
        val artist = song().copy(resourceId = "789", resourceType = "artists", catalogKind = CatalogKind.Artist)
        val result = actions.addEligibleItemsToLibraryAndConfirm(listOf(song(), song(), song("456"), uploaded, artist))
        assertEquals(listOf(song(), song("456")), result.attemptedItems)
        assertEquals(listOf(song("456")), result.pendingItems)
        assertFalse(result.retryAllowed)
        savedAll = true
        assertTrue(actions.libraryItemsAwaitingConfirmation(result.pendingItems).isEmpty())
        assertEquals(1, posts)
    }

    @Test fun failedLibraryReadAfterConfirmedAppendDoesNotResubmitPlaylistAppend() = runBlocking {
        var appended = false
        var appendPosts = 0
        var libraryPosts = 0
        val playlist = song().copy(resourceId = "p.test", resourceType = "library-playlists", catalogKind = CatalogKind.Playlist)
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            when {
                method == "POST" && "/p.test/tracks" in path -> { appendPosts++; appended = true; JSONObject() }
                method == "POST" -> { libraryPosts++; JSONObject() }
                "/p.test/tracks" in path -> if (appended)
                    JSONObject("""{"data":[{"id":"i.saved","type":"library-songs","attributes":{"playParams":{"catalogId":"123"}}}]}""")
                    else JSONObject("""{"data":[]}""")
                else -> throw IOException("Library read offline")
            }
        }
        val pending = actions.preparePlaylistAppend(playlist, listOf(song()))
        actions.submitPlaylistAppend(pending)
        assertTrue(actions.confirmPlaylistAppend(pending))
        val partial = actions.addEligibleItemsToLibraryAndConfirm(pending.tracks)
        assertEquals(listOf(song()), partial.pendingItems)
        assertFalse(partial.retryAllowed)
        assertEquals(listOf(song()), actions.libraryItemsAwaitingConfirmation(partial.pendingItems))
        assertEquals(1, appendPosts)
        assertEquals(1, libraryPosts)
    }

    @Test fun cancellationDoesNotTurnIntoAFalseSuccessfulSecondaryResult() = runBlocking {
        val cancelled = CancellationException("Leave")
        val actions = CatalogActions({ "tw" }) { _, _, _ -> throw cancelled }
        assertSame(cancelled, runCatching { actions.addEligibleItemsToLibraryAndConfirm(listOf(song())) }.exceptionOrNull())
        assertSame(cancelled, runCatching { actions.libraryItemsAwaitingConfirmation(listOf(song())) }.exceptionOrNull())
    }
}
