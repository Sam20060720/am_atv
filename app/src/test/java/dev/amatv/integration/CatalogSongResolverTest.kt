package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogSongResolverTest {
    private fun resolver(
        account: () -> String? = { "account" },
        storefront: suspend () -> String = { "tw" },
        library: suspend (String) -> String = { error("A catalog recording must not use the library endpoint") },
        maxEntries: Int = 64,
        equivalent: suspend (String, String) -> String,
    ) = CatalogSongResolver(account, storefront, library, equivalent, { "Sign in" }, maxEntries)

    @Test fun ordinaryRetiredCatalogSongsUseValidatedEquivalenceAndCacheCanonicalAliases() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val resolver = resolver { storefront, id ->
            requests += storefront to id
            assertEquals("1465074689", id)
            "6790045614"
        }
        assertEquals("6790045614", resolver.resolve("1465074689"))
        assertEquals("6790045614", resolver.resolve("1465074689"))
        assertEquals("6790045614", resolver.resolve("6790045614"))
        assertEquals(listOf("tw" to "1465074689"), requests)
    }

    @Test fun libraryAndCatalogCallersShareOneValidatedRecordingMapping() = runBlocking {
        val libraryRequests = mutableListOf<String>()
        val catalogRequests = mutableListOf<String>()
        val resolver = resolver(library = { libraryRequests += it; "1465074689" }) { _, id ->
            catalogRequests += id
            "6790045614"
        }
        assertEquals("6790045614", resolver.resolve("i.library"))
        assertEquals("6790045614", resolver.resolve("1465074689"))
        assertEquals("6790045614", resolver.resolve("6790045614"))
        assertEquals("6790045614", resolver.resolve("i.library"))
        assertEquals(listOf("i.library"), libraryRequests)
        assertEquals(listOf("1465074689"), catalogRequests)
    }

    @Test fun accountAndStorefrontChangesNeverReuseAnotherScopeMapping() = runBlocking {
        var account = "first-account"
        var storefront = "tw"
        var calls = 0
        val resolver = resolver(account = { account }, storefront = { storefront }) { sf, _ ->
            calls++
            "$account-$sf"
        }
        assertEquals("first-account-tw", resolver.resolve("old"))
        storefront = "cn"
        assertEquals("first-account-cn", resolver.resolve("old"))
        account = "second-account"
        assertEquals("second-account-cn", resolver.resolve("old"))
        assertEquals(3, calls)
    }

    @Test fun parallelLyricsAndPlaybackCallersCoalesceTheirProviderLookup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val resolver = resolver { _, _ ->
            calls++
            started.complete(Unit)
            finish.await()
            "current"
        }
        val lyrics = async { resolver.resolve("old") }
        started.await()
        val playback = async { resolver.resolve("old") }
        finish.complete(Unit)
        assertEquals("current", lyrics.await())
        assertEquals("current", playback.await())
        assertEquals(1, calls)
    }

    @Test fun accountReplacementDuringLookupCancelsOldResultBeforeItCanBeCached() = runBlocking {
        var account = "first"
        var calls = 0
        val resolver = resolver(account = { account }) { _, _ ->
            calls++
            if (calls == 1) account = "second"
            "current-$account"
        }
        try {
            resolver.resolve("old")
            fail("A result from the previous account must not be published")
        } catch (_: CancellationException) {
            assertEquals("current-second", resolver.resolve("old"))
        }
        assertEquals(2, calls)
    }

    @Test fun confirmedFailuresKeepTheirTypeAndAreNotMemoized() = runBlocking {
        for (reason in CatalogFailureReason.entries) {
            val failure = CatalogRequestException(reason, "failed")
            var calls = 0
            val resolver = resolver { _, _ ->
                if (++calls == 1) throw failure
                "current"
            }
            try {
                resolver.resolve("old")
                fail("The provider failure must propagate")
            } catch (actual: CatalogRequestException) {
                assertSame(failure, actual)
            }
            assertEquals("current", resolver.resolve("old"))
            assertEquals(2, calls)
        }
    }

    @Test fun longSessionsEvictOldResolutionEntriesInsteadOfGrowingTheCache() = runBlocking {
        var calls = 0
        val resolver = resolver(maxEntries = 2) { _, id -> calls++; id }
        for (id in listOf("first", "second", "third", "first")) resolver.resolve(id)
        assertEquals(4, calls)
    }

    private fun song(catalogId: String, resourceId: String? = catalogId) = Track(
        id = "apple:$catalogId", title = "Same recording", artist = "Artist", album = "Album",
        durationMs = 297_600L, palette = emptyList(), lyrics = emptyList(), catalogId = catalogId,
        resourceId = resourceId, resourceType = if (resourceId?.startsWith("i.") == true) "library-songs" else "songs",
        recommendedByYtm = true, ytmVideoId = "original-video",
    )

    @Test fun catalogTrackRepairPreservesOriginalResourceAndRecommendationProvenance() = runBlocking {
        val original = song("1465074689")
        val resolved = resolvePlayableSongTrack(original) { id ->
            assertEquals("1465074689", id)
            "6790045614"
        }
        assertEquals("apple:6790045614", resolved.id)
        assertEquals("6790045614", resolved.catalogId)
        assertEquals("1465074689", resolved.resourceId)
        assertEquals("songs", resolved.resourceType)
        assertEquals(original.copy(id = resolved.id, catalogId = resolved.catalogId), resolved)
        assertSame(resolved, resolvePlayableSongTrack(resolved) { it })
    }

    @Test fun libraryTrackUsesDurableSourceAndNonSongsNeverEnterSongResolution() = runBlocking {
        val library = song("1465074689", "i.library")
        val resolved = resolvePlayableSongTrack(library) { id ->
            assertEquals("i.library", id)
            "6790045614"
        }
        assertEquals("i.library", resolved.resourceId)
        assertEquals("library-songs", resolved.resourceType)
        val station = library.copy(catalogKind = CatalogKind.Station)
        assertSame(station, resolvePlayableSongTrack(station) { fail("Not a song"); it })
        val legacy = song("1465074689", resourceId = null).copy(resourceType = null)
        assertEquals("1465074689", resolvePlayableSongTrack(legacy) { "6790045614" }.resourceId)
    }

    @Test fun unmatchedLibrarySongResolvesThroughItsSourceBeforeAcquiringACatalogId() = runBlocking {
        val uploaded = song("unused", "i.uploaded").copy(id = "apple:i.uploaded", catalogId = null)
        val resolved = resolvePlayableSongTrack(uploaded) { id ->
            assertEquals("i.uploaded", id)
            "6790045614"
        }
        assertEquals("6790045614", resolved.catalogId)
        assertEquals("i.uploaded", resolved.resourceId)
        assertEquals("library-songs", resolved.resourceType)
    }

    @Test fun unmatchedLibrarySongPropagatesUnavailableInsteadOfDisappearingFromLibrary() = runBlocking {
        val uploaded = song("unused", "i.uploaded").copy(catalogId = null)
        val unavailable = CatalogRequestException(CatalogFailureReason.Unavailable, "No catalog match")
        try {
            resolvePlayableSongTrack(uploaded) { throw unavailable }
            fail("An unmatched upload is not a catalog recording")
        } catch (actual: CatalogRequestException) {
            assertSame(unavailable, actual)
        }
    }

    @Test fun absentCatalogIdDoesNotAuthorizeAnArbitraryResourceAsASong() = runBlocking {
        for (resourceType in listOf("songs", "library-albums", null)) {
            val invalid = song("unused", "i.uploaded").copy(catalogId = null, resourceType = resourceType)
            var requested = false
            try {
                resolvePlayableSongTrack(invalid) { requested = true; "unexpected" }
                fail("Only an explicit library-songs resource can resolve without a catalog ID")
            } catch (failure: CatalogRequestException) {
                assertEquals(CatalogFailureReason.Unavailable, failure.reason)
                assertFalse(requested)
            }
        }
    }
}
