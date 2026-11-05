package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.playback.CollectionPlaybackContinuation
import dev.amatv.playback.CollectionPlaybackRequest
import dev.amatv.ui.CollectionBrowser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class ReversePlaylistPagingTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
    private fun playlist(library: Boolean = false) = Track("apple:pl.reverse", "Playlist", "", "", 0,
        emptyList(), emptyList(), catalogId = "pl.reverse", catalogKind = CatalogKind.Playlist,
        resourceId = if (library) "p.reverse" else "pl.reverse",
        resourceType = if (library) "library-playlists" else "playlists")

    private suspend fun CollectionBrowser.awaitLoaded() {
        val current = withTimeout(5_000L) { state.first { it.loaded || it.error != null } }
        assertNull("Fixture page failed", current.error)
    }

    private class Source(val size: Int, private val endpoint: String = "/v1/catalog/tw/playlists/pl.reverse/tracks") {
        val paths = mutableListOf<String>()
        var includeTotal = true
        var reportedTotal = size
        var ignoreRange = false
        val ids = (0 until size).map { if (it == size - 2 || it == size - 4) "repeat" else "$it" }
        val videoIndex = size - 3
        val expected get() = ids.filterIndexed { index, _ -> index != videoIndex }.asReversed()
        suspend fun fetch(path: String, user: Boolean): JSONObject {
            paths += path
            assertEquals(endpoint.startsWith("/v1/me/"), user)
            assertFalse(path.startsWith("amatv-"))
            val uri = URI(path)
            assertEquals(endpoint, uri.path)
            val query = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            if (user) assertEquals("catalog", query["include"])
            val offset = if (ignoreRange) 0 else query["offset"]?.toInt() ?: 0
            val limit = query.getValue("limit").toInt()
            val end = minOf(size, offset + limit)
            val rows = JSONArray()
            for (index in offset until end) rows.put(JSONObject().put("id", ids[index])
                .put("type", if (index == videoIndex) "music-videos" else "songs")
                .put("attributes", JSONObject().put("name", ids[index]).put("artistName", "Artist")))
            return JSONObject().put("data", rows).also { root ->
                if (end < size) root.put("next", "$endpoint?offset=$end")
                if (includeTotal) root.put("meta", JSONObject().put("total", reportedTotal))
            }
        }
    }

    @Test fun tailRangesPreserveRepeatedOccurrencesAndRawUnsupportedPositions() = runBlocking {
        val source = Source(608)
        val first = api.collectionTracksPage(playlist(), null, true, source::fetch)
        assertEquals(2, source.paths.size) // Metadata page, then tail; no full population wait.
        assertTrue(source.paths[1].contains("offset=308&limit=300"))
        assertEquals(listOf("607", "repeat", "repeat"), first.items.take(3).map { it.catalogId })
        assertEquals(listOf(0, 1, 3, 4), first.itemRawOffsets.take(4))
        val second = api.collectionTracksPage(playlist(), first.nextPage, false, source::fetch)
        val third = api.collectionTracksPage(playlist(), second.nextPage, false, source::fetch)
        assertEquals(source.expected, (first.items + second.items + third.items).map { it.catalogId })
        assertNull(third.nextPage)
        assertTrue(source.paths[2].contains("offset=8&limit=300"))
        assertTrue(source.paths[3].contains("offset=0&limit=8"))
    }

    @Test fun forwardContinuationAndAlbumsKeepSourceOrderAfterThePreferenceChanges() = runBlocking {
        val source = Source(608)
        val first = api.collectionTracksPage(playlist(), null, false, source::fetch)
        val next = api.collectionTracksPage(playlist(), first.nextPage, true, source::fetch)
        assertEquals("300", next.items.first().catalogId)
        assertFalse(next.requestedPage.startsWith("amatv-"))
        var path = ""
        val album = playlist().copy(catalogKind = CatalogKind.Album, resourceType = "albums")
        api.collectionTracksPage(album, null, true) { requested, _ ->
            path = requested; JSONObject("""{"data":[]}""")
        }
        assertEquals("/v1/catalog/tw/albums/pl.reverse/tracks?limit=100", path)
    }

    @Test fun libraryRangesRetainLibraryIdentityAndAuthenticatedPageLimit() = runBlocking {
        val source = Source(205, "/v1/me/library/playlists/p.reverse/tracks")
        val first = api.collectionTracksPage(playlist(true), null, true, source::fetch)
        assertEquals(100, first.rawItemCount)
        assertTrue(source.paths[1].contains("offset=105&limit=100&include=catalog"))
        assertEquals("204", first.items.first().catalogId)
    }

    @Test fun selectingAMiddleOccurrenceAndRestoringItsCursorKeepTheSameQueueOrder() = runBlocking {
        val source = Source(608)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope,
            load = { collection, next -> api.collectionTracksPage(collection, next, true, source::fetch) },
            playlistOrder = { true })
        try {
            browser.open(playlist())
            browser.awaitLoaded()
            var ready: CollectionPlaybackRequest? = null
            assertTrue(browser.preparePlayback(2, false) { ready = it })
            val request = checkNotNull(ready)
            val cursor = checkNotNull(request.continuation)
            val checkpoint = checkNotNull(cursor.checkpoint("a".repeat(32)))
            // Restore uses a loader whose current preference is off. The saved
            // reverse cursor, not that setting, owns the rest of this session.
            val restored = CollectionPlaybackContinuation.restore(checkpoint) { collection, next ->
                api.collectionTracksPage(collection, next, false, source::fetch)
            }
            val actual = request.tracks.drop(request.startIndex).toMutableList()
            while (true) {
                val page = restored.loadNext() ?: break
                actual += page.tracks
            }
            assertEquals(source.expected.drop(2), actual.map { it.catalogId })
            assertEquals("repeat", actual.first().catalogId)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun reversedSampledShuffleCheckpointUsesLogicalRawCoordinates() = runBlocking {
        val source = Source(608)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope,
            load = { collection, next -> api.collectionTracksPage(collection, next, true, source::fetch) },
            loadShuffleSample = { _, _ -> CollectionShuffleSample(
                api.parseResourceItem(JSONObject("""{"id":"repeat","type":"songs","attributes":{"name":"Repeated"}}""")),
                606, 608, "/v1/catalog/tw/playlists/pl.reverse/tracks?limit=300") },
            playlistOrder = { true })
        try {
            browser.open(playlist())
            browser.awaitLoaded()
            var ready: CollectionPlaybackRequest? = null
            assertTrue(browser.preparePlayback(0, true) { ready = it })
            val cursor = checkNotNull(ready?.continuation)
            cursor.bindShuffleAnchor(17L)
            val checkpoint = checkNotNull(cursor.checkpoint("a".repeat(32)))
            assertEquals(1, checkpoint.shuffle?.rawIndex)
            assertEquals(608, ReversePlaylistCursor.parse(checkpoint.nextPage)?.end)
            val restored = CollectionPlaybackContinuation.restore(checkpoint) { collection, next ->
                api.collectionTracksPage(collection, next, false, source::fetch)
            }
            val population = checkNotNull(restored.loadNext())
            assertTrue(population.complete)
            assertEquals(1, population.shuffleSourceIndex)
            assertEquals(17L, population.shuffleAnchorEntryId)
            assertEquals(source.expected.filterIndexed { index, _ -> index != 1 }, population.tracks.map { it.catalogId })
            assertEquals(1, population.tracks.count { it.catalogId == "repeat" })
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun returningToARetainedPlaylistAdoptsTheSettingWithoutReorderingAnExistingQueue() = runBlocking {
        val source = Source(608)
        var reversed = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope,
            load = { collection, next -> api.collectionTracksPage(collection, next, reversed, source::fetch) },
            playlistOrder = { reversed })
        try {
            browser.open(playlist())
            browser.awaitLoaded()
            var oldQueue: CollectionPlaybackRequest? = null
            browser.preparePlayback(0, false) { oldQueue = it }
            val original = checkNotNull(oldQueue)
            assertEquals("0", original.tracks.first().catalogId)
            browser.setVisible(false)
            reversed = true
            browser.setVisible(true)
            browser.awaitLoaded()
            assertEquals("607", browser.state.value.tracks.first().catalogId)
            assertEquals("0", original.tracks.first().catalogId)
            assertEquals("300", original.continuation!!.loadNext()!!.tracks.first().catalogId)
            browser.setVisible(false)
            reversed = false
            browser.setVisible(true)
            browser.awaitLoaded()
            assertEquals("0", browser.state.value.tracks.first().catalogId)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun unverifiableShuffleSampleResolvesTheSameReversedOccurrenceInItsCompleteFallback() = runBlocking {
        val source = Source(608)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope,
            load = { collection, next -> api.collectionTracksPage(collection, next, true, source::fetch) },
            loadShuffleSample = { _, _ -> CollectionShuffleSample(null, 606, 608,
                "/v1/catalog/tw/playlists/pl.reverse/tracks?limit=300") },
            playlistOrder = { true })
        try {
            browser.open(playlist())
            browser.awaitLoaded()
            val ready = CompletableDeferred<CollectionPlaybackRequest>()
            browser.preparePlayback(0, true) { ready.complete(it) }
            val request = withTimeout(5_000L) { ready.await() }
            assertNull(request.continuation)
            assertEquals(1, request.startIndex)
            assertEquals(source.expected, request.tracks.map { it.catalogId })
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun missingTotalCountsPagesThenReturnsOnlyTheTailPage() = runBlocking {
        val source = Source(608).apply { includeTotal = false }
        val first = api.collectionTracksPage(playlist(), null, true, source::fetch)
        assertEquals(4, source.paths.size)
        assertEquals(608, first.total)
        assertEquals(300, first.rawItemCount)
        assertNotNull(first.nextPage)
        assertEquals("607", first.items.first().catalogId)
    }

    @Test fun changedTotalOrIgnoredOffsetFailWithoutAdvancingTheSavedCursor() = runBlocking {
        val source = Source(608)
        val first = api.collectionTracksPage(playlist(), null, true, source::fetch)
        val cursor = checkNotNull(first.nextPage)
        source.reportedTotal++
        try { api.collectionTracksPage(playlist(), cursor, true, source::fetch); fail("Changed population must fail") }
        catch (_: IllegalStateException) { }
        source.reportedTotal--
        source.ignoreRange = true
        try { api.collectionTracksPage(playlist(), cursor, true, source::fetch); fail("Ignored offset must fail") }
        catch (_: IllegalStateException) { }
        source.ignoreRange = false
        assertEquals("307", api.collectionTracksPage(playlist(), cursor, false, source::fetch).items.first().catalogId)
    }

    @Test fun foreignReverseCursorIsRejectedBeforeAnyRequestAndCountingIsCancellable() = runBlocking {
        var calls = 0
        val foreign = ReversePlaylistCursor("/v1/catalog/us/playlists/pl.other/tracks", 500, 200).encoded
        try {
            api.collectionTracksPage(playlist(), foreign, true) { _, _ -> calls++; JSONObject() }
            fail("Foreign source accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
        val source = Source(608).apply { includeTotal = false }
        try {
            withTimeout(40L) {
                api.collectionTracksPage(playlist(), null, true) { path, user ->
                    if (source.paths.isNotEmpty()) delay(200L)
                    source.fetch(path, user)
                }
            }
            fail("Cancelled count returned content")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(1, source.paths.size)
    }
}
