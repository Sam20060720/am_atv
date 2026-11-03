package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FavoriteMutationConfirmationTest {
    private fun song(library: Boolean = false) = Track("apple:123", "Song", "Artist", "Album", 120_000,
        emptyList(), emptyList(), catalogId = "123", catalogKind = CatalogKind.Song,
        resourceId = if (library) "i.uploaded" else "123", resourceType = if (library) "library-songs" else "songs")
    private fun resource(value: String) = JSONObject("""{"data":[{"id":"123","type":"songs","attributes":{"inFavorites":$value}}]}""")

    @Test fun acceptedWriteIsNotConfirmedUntilTheExactCatalogReadbackSaysTrue() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            calls += method to path
            if (method == "POST") JSONObject() else resource("true")
        }
        val result = actions.addToFavoritesAndConfirm(song(true))
        assertTrue(result.confirmed)
        assertEquals(listOf("POST" to "/v1/me/favorites?ids%5Bsongs%5D=123",
            "GET" to "/v1/catalog/tw/songs/123?extend=inFavorites"), calls)
        assertFalse(calls.any { "ratings" in it.second || it.first == "DELETE" })
    }

    @Test fun acceptedButStillPropagatingOrMissingFlagsRemainPendingWithoutAnotherPost() = runBlocking {
        for (body in listOf(resource("false"), resource("null"), JSONObject(),
            JSONObject("""{"data":[{"id":"other","type":"songs","attributes":{"inFavorites":true}}]}"""),
            JSONObject("""{"data":[{"id":"123","type":"albums","attributes":{"inFavorites":true}}]}"""))) {
            var writes = 0
            val actions = CatalogActions({ "tw" }) { _, method, _ ->
                if (method == "POST") { writes++; JSONObject() } else body
            }
            assertFalse(actions.addToFavoritesAndConfirm(song()).confirmed)
            assertEquals(1, writes)
        }
    }

    @Test fun statusReadFailureAfterAnAcceptedWriteDoesNotReportDefinitiveWriteFailure() = runBlocking {
        var writes = 0
        val actions = CatalogActions({ "tw" }) { _, method, _ ->
            if (method == "POST") { writes++; JSONObject() } else throw IOException("offline")
        }
        val result = actions.addToFavoritesAndConfirm(song())
        assertFalse(result.confirmed)
        assertNull(result.confirmedState)
        assertEquals(1, writes)
    }

    @Test fun aTimedOutWriteIsUncertainAndNeverAutomaticallyRetried() = runBlocking {
        val calls = mutableListOf<String>()
        val actions = CatalogActions({ "tw" }) { _, method, _ -> calls += method; throw IOException("timeout") }
        val failure = runCatching { actions.addToFavoritesAndConfirm(song()) }.exceptionOrNull()
        assertTrue(failure is FavoriteMutationUnconfirmedException)
        assertEquals(listOf("POST"), calls)
    }

    @Test fun definitiveAuthorizationFailureAndCancellationKeepTheirMeaning() = runBlocking {
        val denied = CatalogRequestException(CatalogFailureReason.Authorization, "sign in", 403)
        var calls = 0
        val actions = CatalogActions({ "tw" }) { _, _, _ -> calls++; throw denied }
        assertSame(denied, runCatching { actions.addToFavoritesAndConfirm(song()) }.exceptionOrNull())
        assertEquals(1, calls)
        val cancellation = CancellationException("leave")
        val cancelled = CatalogActions({ "tw" }) { _, _, _ -> throw cancellation }
        assertSame(cancellation, runCatching { cancelled.addToFavoritesAndConfirm(song()) }.exceptionOrNull())
    }

    @Test fun libraryFavoriteReadbackUsesItsVerifiedCatalogLinkRatherThanLibraryFlagsOrRatings() = runBlocking {
        val calls = mutableListOf<String>()
        val actions = CatalogActions({ "tw" }) { path, _, _ ->
            calls += path
            when {
                path.startsWith("/v1/me/ratings/") -> JSONObject()
                path.startsWith("/v1/me/library/") -> JSONObject("""{"data":[{"id":"i.uploaded","type":"library-songs","attributes":{"inFavorites":false}}]}""")
                else -> resource("true")
            }
        }
        assertEquals(true, actions.readState(song(true)).inFavorites)
        assertTrue(calls.contains("/v1/catalog/tw/songs/123?extend=inFavorites"))
        assertFalse(calls.any { it.contains("/songs/i.uploaded") && !it.startsWith("/v1/me/library/") && !it.startsWith("/v1/me/ratings/") })
    }

    @Test fun missingCanonicalFavoriteReadbackCannotBeConfirmedByStaleLibraryAttributes() = runBlocking {
        for (catalogRead in listOf(resource("null"), JSONObject(),
            JSONObject("""{"data":[{"id":"other","type":"songs","attributes":{"inFavorites":true}}]}"""))) {
            val actions = CatalogActions({ "tw" }) { path, _, _ ->
                when {
                    path.startsWith("/v1/me/ratings/") -> JSONObject()
                    path.startsWith("/v1/me/library/") -> JSONObject("""{"data":[{"id":"i.uploaded","type":"library-songs","attributes":{"inFavorites":true}}]}""")
                    else -> catalogRead
                }
            }
            val state = actions.readState(song(true))
            assertNull(state.inFavorites)
            assertEquals(true, state.inLibrary)
        }
    }

    @Test fun librarySourceWithoutACatalogLinkDoesNotExposeItsFavoriteFlagAsCanonical() = runBlocking {
        val actions = CatalogActions({ "tw" }) { path, _, _ ->
            if (path.startsWith("/v1/me/ratings/")) JSONObject()
            else JSONObject("""{"data":[{"id":"i.uploaded","type":"library-songs","attributes":{"inFavorites":true}}]}""")
        }
        val item = song(true).copy(catalogId = null)
        assertFalse(actions.availableActions(item).canFavorite)
        assertNull(actions.readState(item).inFavorites)
    }
}
