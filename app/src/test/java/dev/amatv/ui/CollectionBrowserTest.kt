package dev.amatv.ui

import dev.amatv.R
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.CollectionShuffleSample
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.playback.CollectionPlaybackRequest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CollectionBrowserTest {
    private fun collection(id: String) = Track(
        id, id, "Curator", "", 0, emptyList(), emptyList(), catalogKind = CatalogKind.Playlist,
    )

    @Test fun metadataResumesWithoutChangingLibraryIdentityOrAcceptingCancelledArtwork() = runBlocking {
        val old = CompletableDeferred<Track>()
        val fresh = CompletableDeferred<Track>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = collection("apple:p.demo").copy(resourceId = "p.demo", resourceType = "library-playlists", catalogId = "pl.original")
        var calls = 0
        val browser = CollectionBrowser(scope,
            load = { _, _ -> LibraryPage(emptyList(), null, "/tracks") },
            loadMetadata = { withContext(NonCancellable) { if (++calls == 1) old.await() else fresh.await() } })
        browser.open(source)
        browser.setVisible(false)
        browser.setVisible(true)
        old.complete(source.copy(artworkUrl = "https://example.com/stale.jpg"))
        assertNull(browser.state.value.collection?.artworkUrl)
        fresh.complete(source.copy(catalogId = "pl.other", title = "Fresh", artworkUrl = "https://example.com/cover.jpg",
            motionArtworkUrl = "https://example.com/motion.m3u8", description = "Editorial description"))
        val enriched = checkNotNull(browser.state.value.collection)
        assertEquals("Fresh", enriched.title)
        assertEquals("https://example.com/motion.m3u8", enriched.motionArtworkUrl)
        assertEquals(source.id, enriched.id)
        assertEquals(source.resourceId, enriched.resourceId)
        assertEquals(source.resourceType, enriched.resourceType)
        assertEquals(source.catalogId, enriched.catalogId)
        browser.setVisible(false)
        browser.setVisible(true)
        assertEquals(2, calls)
        scope.cancel()
    }

    @Test fun lateResultCannotReplaceNewSelectionOrReopenDismissedPage() = runBlocking {
        val first = CompletableDeferred<LibraryPage>()
        val second = CompletableDeferred<LibraryPage>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope, { item, _ ->
            // Model a non-cooperative transport completing after cancellation.
            withContext(NonCancellable) { if (item.id == "first") first.await() else second.await() }
        })
        browser.open(collection("first"))
        assertTrue(browser.state.value.loading)
        browser.open(collection("second"))
        first.complete(LibraryPage(listOf(collection("stale")), null, "/first"))
        assertEquals("second", browser.state.value.collection?.id)
        browser.close()
        second.complete(LibraryPage(listOf(collection("also stale")), null, "/second"))
        assertNull(browser.state.value.collection)
        assertTrue(browser.state.value.tracks.isEmpty())
        scope.cancel()
    }

    @Test fun failedLoadCanRetryAndNeverBecomesAnEmptySuccessfulPage() {
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope, { _, _ ->
            if (++calls == 1) error("Try again") else LibraryPage(listOf(collection("loaded")), null, "/first")
        })
        val item = collection("playlist")
        browser.open(item)
        assertEquals(R.string.browse_tracks_error_detail, browser.state.value.error)
        assertFalse(browser.state.value.loading)
        browser.open(item)
        assertNull(browser.state.value.error)
        assertEquals("loaded", browser.state.value.tracks.single().id)
        scope.cancel()
    }

    @Test fun playStartsFromLoadedPrefixAndPlaybackCanContinueAfterClosingBrowse() = runBlocking {
        val pending = CompletableDeferred<LibraryPage>()
        val requests = mutableListOf<String?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repeat = collection("song").copy(catalogKind = CatalogKind.Song)
        val browser = CollectionBrowser(scope, { _, next ->
            requests += next
            when (next) {
                null -> LibraryPage(listOf(repeat), "/second", "/first", 4)
                "/second" -> LibraryPage(listOf(repeat, collection("middle").copy(catalogKind = CatalogKind.Song)), "/third", "/second")
                else -> pending.await()
            }
        })
        browser.open(collection("playlist"))
        assertEquals(listOf<String?>(null), requests)
        assertEquals(4, browser.state.value.total)
        assertFalse(browser.state.value.complete)
        assertFalse(browser.state.value.loading)
        browser.more()
        assertEquals(listOf(null, "/second"), requests)
        var ready: CollectionPlaybackRequest? = null
        assertTrue(browser.preparePlayback(1, false) { ready = it })
        val request = checkNotNull(ready)
        assertEquals(listOf("song", "song", "middle"), request.tracks.map { it.id })
        assertEquals(1, request.startIndex) // The second occurrence, not the first matching ID.
        assertFalse(browser.state.value.preparing)
        assertFalse(browser.state.value.loadingMore)
        assertEquals(2, requests.size) // No continuation request before the ready callback.
        browser.close()
        val tail = async(start = CoroutineStart.UNDISPATCHED) { request.continuation!!.loadNext() }
        assertEquals(3, requests.size)
        pending.complete(LibraryPage(listOf(repeat), null, "/third", 4))
        assertEquals(listOf("song", "song", "middle", "song"), (request.tracks + tail.await()!!.tracks).map { it.id })
        assertNull(request.continuation!!.loadNext())
        assertNull(browser.state.value.collection)
        assertEquals(3, requests.size)
        scope.cancel()
    }

    @Test fun shuffleWaitsForCompletePopulationAndCancellationKeepsLoadedProgress() = runBlocking {
        val pending = CompletableDeferred<LibraryPage>()
        val song = collection("song").copy(catalogKind = CatalogKind.Song)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var requests = 0
        val browser = CollectionBrowser(scope, { _, next ->
            requests++
            when (next) {
                null -> LibraryPage(listOf(song), "/second", "/first", 3)
                "/second" -> LibraryPage(listOf(song), "/third", "/second")
                else -> pending.await()
            }
        })
        browser.open(collection("playlist"))
        var ready: CollectionPlaybackRequest? = null
        assertTrue(browser.preparePlayback(0, true) { ready = it })
        assertTrue(browser.state.value.preparing)
        assertEquals(2, browser.state.value.tracks.size)
        assertNull(ready)
        browser.cancelPreparation()
        assertFalse(browser.state.value.preparing)
        assertFalse(browser.state.value.loadingMore)
        assertEquals(2, browser.state.value.tracks.size)
        pending.complete(LibraryPage(listOf(song), null, "/third"))
        assertNull(ready)
        assertFalse(browser.state.value.complete)
        assertTrue(browser.preparePlayback(0, true) { ready = it })
        assertTrue(checkNotNull(ready).shuffle)
        assertNull(ready!!.continuation)
        assertEquals(3, ready!!.tracks.size)
        assertTrue(browser.state.value.complete)
        assertEquals(4, requests)
        scope.cancel()
    }

    @Test fun shuffleReusesInFlightBrowsePageAndStillWaitsForTheCompletePopulation() = runBlocking {
        val second = CompletableDeferred<LibraryPage>()
        val third = CompletableDeferred<LibraryPage>()
        val song = collection("song").copy(catalogKind = CatalogKind.Song)
        val requests = mutableListOf<String?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope, { _, next ->
            requests += next
            when (next) {
                null -> LibraryPage(listOf(song), "/second", "/first", 3)
                "/second" -> second.await()
                else -> third.await()
            }
        })
        browser.open(collection("playlist"))
        browser.more()
        var ready: CollectionPlaybackRequest? = null
        assertTrue(browser.preparePlayback(0, true) { ready = it })
        assertFalse(browser.preparePlayback(0, true) { fail("Duplicate preparation") })
        assertEquals(listOf(null, "/second"), requests)
        assertTrue(browser.state.value.preparing)
        assertNull(ready)

        second.complete(LibraryPage(listOf(song), "/third", "/second"))
        assertEquals(listOf(null, "/second", "/third"), requests)
        assertEquals(2, browser.state.value.tracks.size)
        assertTrue(browser.state.value.preparing)
        assertNull(ready)
        third.complete(LibraryPage(listOf(song), null, "/third"))
        assertEquals(3, checkNotNull(ready).tracks.size)
        assertTrue(ready!!.shuffle)
        assertNull(ready!!.continuation)
        assertFalse(browser.state.value.preparing)
        scope.cancel()
    }

    @Test fun albumShuffleKeepsTheInFlightPageWithoutSamplingOrRefetching() = runBlocking {
        val pending = CompletableDeferred<LibraryPage>()
        val song = collection("repeat").copy(catalogKind = CatalogKind.Song)
        val requests = mutableListOf<String?>()
        var cancelledLoads = 0
        var sampleCalls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = CollectionBrowser(scope, { _, next ->
                requests += next
                if (next == null) LibraryPage(listOf(song), "/second", "/first", 3, 1, listOf(0))
                else try { pending.await() }
                catch (cancelled: CancellationException) { cancelledLoads++; throw cancelled }
            }, loadShuffleSample = { _, _ -> sampleCalls++; null })
            browser.open(collection("album").copy(catalogKind = CatalogKind.Album))
            browser.more()
            var ready: CollectionPlaybackRequest? = null
            assertTrue(browser.preparePlayback(0, true) { ready = it })
            assertEquals(0, sampleCalls)
            assertEquals(0, cancelledLoads)
            assertEquals(listOf(null, "/second"), requests)
            assertTrue(browser.state.value.preparing)
            assertNull(ready)

            pending.complete(LibraryPage(listOf(song, song.copy(id = "last")), null, "/second", 3, 2, listOf(0, 1)))
            assertEquals(listOf("repeat", "repeat", "last"), checkNotNull(ready).tracks.map { it.id })
            assertTrue(ready!!.shuffle)
            assertNull(ready!!.continuation)
            assertEquals(listOf(null, "/second"), requests)
            assertEquals(0, cancelledLoads)
            assertFalse(browser.state.value.preparing)
        } finally { scope.cancel() }
    }

    @Test fun fastShufflePagesYieldToQueuedCancellationBeforeDrainingThePlaylist() = runBlocking {
        val dispatcher = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            withContext(dispatcher) {
                var requests = 0
                var callbacks = 0
                val song = collection("song").copy(catalogKind = CatalogKind.Song)
                val browser = CollectionBrowser(scope, { _, _ ->
                    val page = ++requests
                    LibraryPage(listOf(song), if (page < 100) "/page-${page + 1}" else null, "/page-$page", 100)
                })
                browser.open(collection("playlist"))
                yield()
                assertEquals(1, browser.state.value.tracks.size)
                val cancel = launch(start = CoroutineStart.LAZY) { browser.cancelPreparation() }
                assertTrue(browser.preparePlayback(0, true) { callbacks++ })
                cancel.start()
                cancel.join()
                assertEquals(0, callbacks)
                assertEquals(2, browser.state.value.tracks.size)
                assertEquals(2, requests)
                assertFalse(browser.state.value.preparing)
                assertFalse(browser.state.value.complete)
            }
        } finally { scope.cancel() }
    }

    @Test fun reliableShuffleSampleStartsBeforeTheRemainingPopulationIsRequested() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = collection("first").copy(catalogKind = CatalogKind.Song)
        val selected = first.copy(id = "sampled-late-occurrence")
        val requests = mutableListOf<String?>()
        val browser = CollectionBrowser(scope, { _, next ->
            requests += next
            LibraryPage(listOf(first), "/second", "/first", 1_000)
        }, loadShuffleSample = { _, total ->
            assertEquals(1_000, total)
            CollectionShuffleSample(selected, 999, total, "/first")
        })
        browser.open(collection("playlist"))
        var ready: CollectionPlaybackRequest? = null
        browser.preparePlayback(0, true) { ready = it }
        assertEquals(listOf<String?>(null), requests)
        assertEquals(listOf(selected), checkNotNull(ready).tracks)
        assertTrue(ready!!.shuffle)
        assertTrue(ready!!.continuation!!.bufferedShuffle)
        browser.close()
        assertFalse(browser.state.value.preparing)
        scope.cancel()
    }

    @Test fun absentTotalOrUnverifiableSampleUsesTheCompletePopulationFallback() {
        for (total in listOf<Int?>(null, 2)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var sampleCalls = 0
            val song = collection("song").copy(catalogKind = CatalogKind.Song)
            val browser = CollectionBrowser(scope, { _, next ->
                if (next == null) LibraryPage(listOf(song), "/second", "/first", total)
                else LibraryPage(listOf(song), null, "/second", total)
            }, loadShuffleSample = { _, _ -> sampleCalls++; null })
            browser.open(collection("playlist"))
            var ready: CollectionPlaybackRequest? = null
            browser.preparePlayback(0, true) { ready = it }
            assertEquals(if (total == null) 0 else 1, sampleCalls)
            assertEquals(2, checkNotNull(ready).tracks.size)
            assertNull(ready!!.continuation)
            scope.cancel()
        }
    }

    @Test fun aLateSampleCannotStartPlaybackAfterTheCollectionWasClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<CollectionShuffleSample?>()
        val song = collection("song").copy(catalogKind = CatalogKind.Song)
        val browser = CollectionBrowser(scope, { _, _ -> LibraryPage(listOf(song), "/second", "/first", 2) },
            loadShuffleSample = { _, _ -> withContext(NonCancellable) { pending.await() } })
        browser.open(collection("playlist"))
        var callbacks = 0
        browser.preparePlayback(0, true) { callbacks++ }
        browser.close()
        pending.complete(CollectionShuffleSample(song, 1, 2, "/first"))
        assertEquals(0, callbacks)
        assertNull(browser.state.value.collection)
        scope.cancel()
    }

    @Test fun unverifiableSampleKeepsItsRawDrawThroughFullFallbackAndRejectsChangedTotal() {
        for (finalTotal in listOf(4, 5)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repeated = collection("A").copy(catalogKind = CatalogKind.Song)
            val browser = CollectionBrowser(scope, { _, next ->
                if (next == null) LibraryPage(listOf(repeated), "/second", "/first", 4, 2, listOf(0))
                else LibraryPage(listOf(repeated, repeated.copy(id = "B")), null, "/second", finalTotal, 2, listOf(0, 1))
            }, loadShuffleSample = { _, _ -> CollectionShuffleSample(null, 2, 4, "/first") })
            browser.open(collection("playlist"))
            var ready: CollectionPlaybackRequest? = null
            browser.preparePlayback(0, true) { ready = it }
            if (finalTotal == 4) {
                assertEquals(listOf("A", "A", "B"), checkNotNull(ready).tracks.map { it.id })
                assertEquals(1, ready!!.startIndex) // The second A occurrence, never a redraw by song ID.
            } else {
                assertNull(ready)
                assertEquals(R.string.browse_tracks_error_detail, browser.state.value.error)
            }
            scope.cancel()
        }
    }

    @Test fun completedShuffleFailureCanRetryFromTheFirstPageWithACleanPopulation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = collection("playlist")
            val old = collection("old").copy(catalogKind = CatalogKind.Song)
            val fresh = old.copy(id = "fresh")
            val requests = mutableListOf<String?>()
            var firstPages = 0
            val browser = CollectionBrowser(scope, { item, next ->
                assertEquals(source, item)
                requests += next
                if (next == null) {
                    if (++firstPages == 1) LibraryPage(listOf(old), "/second", "/first", 4, 2, listOf(0))
                    else LibraryPage(listOf(fresh), "/second", "/first", 2, 1, listOf(0))
                } else if (firstPages == 1) LibraryPage(listOf(old), null, "/second", 5, 2, listOf(0))
                else LibraryPage(listOf(fresh), null, "/second", 2, 1, listOf(0))
            }, loadShuffleSample = { _, total -> CollectionShuffleSample(null, if (total == 4) 2 else 1, total, "/first") })
            browser.open(source)
            var ready: CollectionPlaybackRequest? = null
            assertTrue(browser.preparePlayback(0, true) { ready = it })
            assertNull(ready)
            assertTrue(browser.state.value.complete)
            assertEquals(R.string.browse_tracks_error_detail, browser.state.value.error)

            browser.retry()
            assertEquals(listOf(null, "/second", null), requests)
            assertEquals(source, browser.state.value.collection)
            assertNull(browser.state.value.error)
            assertFalse(browser.state.value.complete)
            assertEquals(listOf(fresh), browser.state.value.tracks)
            assertTrue(browser.preparePlayback(0, true) { ready = it })
            assertEquals(listOf("fresh", "fresh"), checkNotNull(ready).tracks.map { it.id })
            assertEquals(1, ready!!.startIndex)
            assertEquals(listOf(null, "/second", null, "/second"), requests)
            assertNull(browser.state.value.error)
            assertTrue(browser.state.value.complete)
        } finally { scope.cancel() }
    }

    @Test fun normalPlayTransfersCommittedCursorWithoutAcceptingCancelledBrowsePage() = runBlocking {
        val old = CompletableDeferred<LibraryPage>()
        val fresh = CompletableDeferred<LibraryPage>()
        val song = collection("song").copy(catalogKind = CatalogKind.Song)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val browser = CollectionBrowser(scope, { _, next ->
            if (next == null) LibraryPage(listOf(song), "/second", "/first")
            else withContext(NonCancellable) { if (++calls == 1) old.await() else fresh.await() }
        })
        browser.open(collection("playlist"))
        browser.more()
        var ready: CollectionPlaybackRequest? = null
        browser.preparePlayback(0, false) { ready = it }
        val continuation = checkNotNull(ready?.continuation)
        val tail = async(start = CoroutineStart.UNDISPATCHED) { continuation.loadNext() }
        old.complete(LibraryPage(listOf(song.copy(id = "stale")), null, "/second"))
        assertEquals(listOf("song"), browser.state.value.tracks.map { it.id })
        assertFalse(tail.isCompleted)
        fresh.complete(LibraryPage(listOf(song), null, "/second"))
        assertEquals(listOf("song"), tail.await()!!.tracks.map { it.id })
        scope.cancel()
    }

    @Test fun hiddenOrReplacedVisitCannotFinishAnOldPlayPreparation() = runBlocking {
        val pending = mutableListOf<CompletableDeferred<LibraryPage>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val song = collection("song").copy(catalogKind = CatalogKind.Song)
        val browser = CollectionBrowser(scope, { item, next ->
            if (next == null) LibraryPage(listOf(song), "/${item.id}/second", "/${item.id}/first")
            else {
                val page = CompletableDeferred<LibraryPage>().also(pending::add)
                withContext(NonCancellable) { page.await() }
            }
        })
        var callbacks = 0
        browser.open(collection("first"))
        browser.preparePlayback(0, true) { callbacks++ }
        browser.setVisible(false)
        pending[0].complete(LibraryPage(listOf(song), null, "/first/second"))
        assertEquals(0, callbacks)
        assertEquals(1, browser.state.value.tracks.size)
        assertFalse(browser.state.value.preparing)
        browser.setVisible(true)
        assertEquals(1, pending.size) // Returning does not drain the saved continuation.
        browser.preparePlayback(0, true) { callbacks++ }
        browser.open(collection("second"))
        pending[1].complete(LibraryPage(listOf(song), null, "/first/second"))
        assertEquals("second", browser.state.value.collection?.id)
        assertEquals(0, callbacks)
        browser.preparePlayback(0, true) { callbacks++ }
        browser.close()
        pending[2].complete(LibraryPage(listOf(song), null, "/second/second"))
        assertNull(browser.state.value.collection)
        assertEquals(0, callbacks)
        scope.cancel()
    }
}
