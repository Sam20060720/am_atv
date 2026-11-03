package dev.amatv.ui

import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

class LibraryBrowseToolsTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks += block }
        fun drain() { while (tasks.isNotEmpty()) tasks.poll().run() }
    }
    private fun track(id: String, title: String = id, artist: String = "", album: String = "") = Track(
        id, title, artist, album, 1, emptyList(), emptyList(), resourceId = id, resourceType = "library-songs")
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun page(more: Boolean, pages: Int = 1, loading: Boolean = false, failed: Boolean = false) =
        LibraryPagingSnapshot(more, loading, failed, pages)
    private fun tools(scope: CoroutineScope, load: () -> Unit, retry: () -> Unit = {}, maxPages: Int = 128) =
        LibraryBrowseTools(scope, load, retry, Locale.ENGLISH, Dispatchers.Unconfined,
            pageDelayMillis = 0, maxPagesPerBatch = maxPages)

    @Test fun originalBrowsingDoesNotFetchTheWholeLibrary() {
        val scope = scope(); var reads = 0
        val tools = tools(scope, { reads++ })
        try {
            tools.update(listOf(track("first")), page(true), true)
            assertEquals(0, reads)
            assertNull(tools.state.value.indexedItems)
            assertFalse(tools.state.value.preparing)
            tools.request(LibrarySortOrder.Original)
            assertEquals(0, reads)
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun nameSortWaitsForEveryPageBeforeAtomicallyReplacingTheOriginalView() {
        val scope = scope(); var reads = 0
        val tools = tools(scope, { reads++ })
        val first = listOf(track("z", "Zulu"))
        try {
            tools.update(first, page(true), true)
            tools.request(LibrarySortOrder.Name)
            assertEquals(1, reads)
            assertNull(tools.state.value.indexedItems)
            assertEquals(LibrarySortOrder.Original, tools.state.value.sort)
            tools.update(first, page(true, loading = true), true)
            tools.update(first, page(true, loading = true), true)
            assertEquals(1, reads)
            tools.update(first + track("a", "Alpha"), page(false, pages = 2), true)
            assertEquals(listOf("a", "z"), tools.state.value.indexedItems!!.map { it.id })
            assertEquals(LibrarySortOrder.Name, tools.state.value.sort)
            assertFalse(tools.state.value.preparing)
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun aQueuedContinuationCannotRunAfterTheOrdinaryPagerFinishesTheCategory() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var reads = 0
        val tools = LibraryBrowseTools(scope, { reads++ }, {}, Locale.ENGLISH, Dispatchers.Unconfined,
            pageDelayMillis = 0)
        val first = listOf(track("z", "Zulu"))
        try {
            tools.update(first, page(true), true)
            tools.request(LibrarySortOrder.Name)
            // A normal viewport prefetch completes before the scheduled tools read starts.
            tools.update(first + track("a", "Alpha"), page(false, pages = 2), true)
            dispatcher.drain()
            assertEquals(0, reads)
            assertEquals(listOf("a", "z"), tools.state.value.indexedItems!!.map { it.id })
            assertEquals(LibraryToolsPhase.Idle, tools.state.value.phase)
        } finally { tools.cancel(); scope.cancel(); dispatcher.drain() }
    }

    @Test fun aQueuedContinuationRetriesAFailureThatArrivedWhileItWasWaiting() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var reads = 0; var retries = 0
        val tools = LibraryBrowseTools(scope, { reads++ }, { retries++ }, Locale.ENGLISH, Dispatchers.Unconfined,
            pageDelayMillis = 0)
        val rows = listOf(track("z", "Zulu"))
        try {
            tools.update(rows, page(true), true)
            tools.request(LibrarySortOrder.Name)
            tools.update(rows, page(true, failed = true), true)
            dispatcher.drain()
            assertEquals(0, reads)
            assertEquals(1, retries)
        } finally { tools.cancel(); scope.cancel(); dispatcher.drain() }
    }

    @Test fun emptyFilteredPagesAdvanceByPageRevisionWithoutClaimingTheSameCursorTwice() {
        val scope = scope(); var reads = 0
        val tools = tools(scope, { reads++ })
        val first = listOf(track("z", "Zulu"))
        try {
            tools.update(first, page(true), true)
            tools.request(LibrarySortOrder.Name)
            tools.update(first, page(true), true)
            assertEquals(1, reads)
            // A successful page can contain no supported/displayable resources.
            tools.update(first, page(true, pages = 2), true)
            assertEquals(2, reads)
            tools.update(first + track("a", "Alpha"), page(false, pages = 3), true)
            assertEquals(listOf("a", "z"), tools.state.value.indexedItems!!.map { it.id })
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun cancellingOrHidingStopsFurtherReadsAndDoesNotApplyAPartialIndex() {
        val scope = scope(); var reads = 0
        val tools = tools(scope, { reads++ })
        val first = listOf(track("z", "Zulu"))
        try {
            tools.update(first, page(true), true); tools.request(LibrarySortOrder.Name)
            assertEquals(1, reads)
            tools.cancel()
            tools.update(first + track("a", "Alpha"), page(true, pages = 2), true)
            assertEquals(1, reads)
            assertNull(tools.state.value.indexedItems)
            tools.request(LibrarySortOrder.Name)
            assertEquals(2, reads)
            tools.update(first, page(true), false)
            tools.update(first + track("b", "Beta"), page(true, pages = 3), false)
            tools.update(first + track("b", "Beta"), page(true, pages = 3), true)
            assertEquals(2, reads)
            assertFalse(tools.state.value.preparing)
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun hidingRefreshesTheAppliedIndexButCannotUndoExplicitCancellationOrPause() {
        val scope = scope(); var reads = 0
        val tools = tools(scope, { reads++ })
        val first = listOf(track("z", "Zulu"))
        val refreshed = first + track("a", "Alpha")
        val expanding = refreshed + track("b", "Beta")
        val late = expanding + track("c", "Charlie")
        try {
            tools.update(first, page(false), true)
            tools.request(LibrarySortOrder.Name)
            tools.update(first, page(false), false)
            tools.update(refreshed, page(false, pages = 2), false)
            assertEquals(listOf("z"), tools.state.value.indexedItems!!.map { it.id })
            tools.update(refreshed, page(false, pages = 2), true)
            assertEquals(listOf("a", "z"), tools.state.value.indexedItems!!.map { it.id })
            assertEquals(LibraryToolsPhase.Idle, tools.state.value.phase)
            assertEquals(0, reads)

            // An explicit Cancel still rejects the replacement's late page,
            // including when it arrives while a window covers the library.
            tools.update(expanding, page(true, pages = 3), true)
            assertEquals(1, reads)
            tools.cancel()
            tools.update(late, page(false, pages = 4), false)
            tools.update(late, page(false, pages = 4), true)
            assertEquals(listOf("a", "z"), tools.state.value.indexedItems!!.map { it.id })
            assertEquals(LibraryToolsPhase.Paused, tools.state.value.phase)
            tools.update(late, page(false, pages = 4), false)
            tools.update(late, page(false, pages = 4), true)
            assertEquals(LibraryToolsPhase.Paused, tools.state.value.phase)
            assertEquals(1, reads)
            tools.resume()
            assertEquals(listOf("a", "b", "c", "z"), tools.state.value.indexedItems!!.map { it.id })
            assertEquals(LibraryToolsPhase.Idle, tools.state.value.phase)
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun failedPageWaitsForExplicitResumeAndRetainsTheLastCompleteIndex() {
        val scope = scope(); var reads = 0; var retries = 0
        val tools = tools(scope, { reads++ }, { retries++ })
        val first = listOf(track("z", "Zulu"))
        try {
            tools.update(first, page(false), true); tools.request(LibrarySortOrder.Name)
            assertEquals(listOf("z"), tools.state.value.indexedItems!!.map { it.id })
            val expanded = first + track("a", "Alpha")
            tools.update(expanded, page(true, pages = 2), true)
            assertEquals(1, reads)
            tools.update(expanded, page(true, pages = 2, failed = true), true)
            assertEquals(LibraryToolsPhase.Failed, tools.state.value.phase)
            assertEquals(listOf("z"), tools.state.value.indexedItems!!.map { it.id })
            tools.update(expanded, page(true, pages = 2, failed = true), true)
            assertEquals(0, retries)
            tools.resume()
            assertEquals(1, retries)
            tools.update(expanded, page(false, pages = 3), true)
            assertEquals(listOf("a", "z"), tools.state.value.indexedItems!!.map { it.id })
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun batchBudgetPausesAndOnlyAnExplicitContinueCanRequestAnotherPage() {
        val scope = scope(); var reads = 0
        val tools = tools(scope, { reads++ }, maxPages = 2)
        val rows = listOf(track("one"))
        try {
            tools.update(rows, page(true), true); tools.request(LibrarySortOrder.Name)
            tools.update(rows, page(true, pages = 2), true)
            assertEquals(2, reads)
            tools.update(rows, page(true, pages = 3), true)
            assertEquals(LibraryToolsPhase.Paused, tools.state.value.phase)
            tools.update(rows, page(true, pages = 3), true)
            assertEquals(2, reads)
            tools.resume()
            assertEquals(3, reads)
            tools.update(rows, page(false, pages = 4), true)
            assertNotNull(tools.state.value.indexedItems)
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun filteringUsesTheCompletedCategoryIncludingArtistsAndAlbumsWithoutChangingOrder() = runBlocking {
        val rows = listOf(track("one", "Zulu", "Beyoncé"), track("two", "Alpha", album = "FAVORITES"), track("three", "Third"))
        val artist = buildLibraryIndex(rows, LibrarySortOrder.Original, "beyonce", Locale.ENGLISH)
        assertEquals(listOf("one"), artist.items.map { it.id })
        val album = buildLibraryIndex(rows, LibrarySortOrder.Original, "favorites", Locale.ENGLISH)
        assertEquals(listOf("two"), album.items.map { it.id })
        val all = buildLibraryIndex(rows, LibrarySortOrder.Original, "", Locale.ENGLISH)
        assertEquals(rows, all.items)
        assertTrue(all.letters.isEmpty())
    }

    @Test fun nameIndexHasOnlyLettersActuallyPresentAndStableTies() = runBlocking {
        val rows = listOf(track("z", "Zulu"), track("a1", "Alpha"), track("a2", "Álpha"), track("cn", "中文"), track("b", "Béta"))
        val index = buildLibraryIndex(rows, LibrarySortOrder.Name, "", Locale.ENGLISH)
        assertEquals(listOf("A", "B", "Z", "#"), index.letters.map { it.letter })
        assertTrue(index.items.indexOfFirst { it.id == "a1" } < index.items.indexOfFirst { it.id == "a2" })
        index.letters.forEach { letter -> assertTrue(letter.index in index.items.indices) }
        assertFalse(index.letters.any { it.letter == "C" })
    }

    @Test fun focusRemapsByResourceIdentityAndUsesTheNearestSurvivorWhenFilteredOut() {
        val rows = listOf(track("a"), track("b"), track("c"))
        assertEquals(2, libraryFocusTarget(rows.reversed(), libraryResourceKey(rows[0]), 0))
        assertEquals(1, libraryFocusTarget(rows.take(2), libraryResourceKey(rows[2]), 2))
        assertNull(libraryFocusTarget(emptyList(), "missing", 3))
        assertEquals(15, libraryViewportFirstItem(oldFirst = 10, oldFocused = 16, newFocused = 23, columns = 5))
        assertEquals(1, libraryViewportFirstItem(oldFirst = 10, oldFocused = 12, newFocused = 3, columns = 1))
    }

    @Test fun artistOrderingGroupsArtistsThenTitlesWhileRetainingDistinctResourcesAndLetterTargets() = runBlocking {
        val rows = listOf(
            track("beta-z", "Zulu", "Beta"), track("alpha-e", "Éclair", "Álpha"),
            track("alpha-a", "Apple", "Alpha"), track("alpha-a-copy", "Ápple", "Alpha"),
            track("unknown", "Unknown", ""), track("beta-a", "Aardvark", "Beta"),
        )
        val result = buildLibraryIndex(rows, LibrarySortOrder.Artist, "", Locale.ENGLISH)
        assertEquals(listOf("unknown", "alpha-a", "alpha-a-copy", "alpha-e", "beta-a", "beta-z"), result.items.map { it.id })
        assertEquals(listOf(LibraryLetterPosition("A", 1), LibraryLetterPosition("B", 4), LibraryLetterPosition("#", 0)), result.letters)
        assertEquals(5, libraryFocusTarget(result.items, libraryResourceKey(rows.first()), 0))
        assertEquals(listOf("beta-a", "beta-z"), buildLibraryIndex(rows, LibrarySortOrder.Artist, "Beta", Locale.ENGLISH).items.map { it.id })
        assertEquals(rows, buildLibraryIndex(rows, LibrarySortOrder.Original, "", Locale.ENGLISH).items)
    }

    @Test fun cancellingAReplacementIndexCannotRestartFromItsLateNetworkPage() {
        val scope = scope(); var reads = 0
        val tools = tools(scope, { reads++ })
        val first = listOf(track("z", "Zulu"))
        try {
            tools.update(first, page(false), true); tools.request(LibrarySortOrder.Name)
            tools.update(first + track("a", "Alpha"), page(true, pages = 2), true)
            assertEquals(1, reads)
            tools.cancel()
            tools.update(first + track("a", "Alpha") + track("b", "Beta"), page(true, pages = 3), true)
            assertEquals(1, reads)
            assertEquals(LibraryToolsPhase.Paused, tools.state.value.phase)
            assertEquals(listOf("z"), tools.state.value.indexedItems!!.map { it.id })
        } finally { tools.cancel(); scope.cancel() }
    }

    @Test fun aCancelledWorkerCannotPublishAStaleSortedView() {
        val scope = scope(); val worker = QueuedDispatcher()
        val tools = LibraryBrowseTools(scope, {}, {}, Locale.ENGLISH, worker, pageDelayMillis = 0)
        try {
            tools.update(listOf(track("z"), track("a")), page(false), true)
            tools.request(LibrarySortOrder.Name)
            assertEquals(LibraryToolsPhase.Indexing, tools.state.value.phase)
            tools.cancel()
            while (worker.tasks.isNotEmpty()) worker.tasks.poll().run()
            assertNull(tools.state.value.indexedItems)
            assertFalse(tools.state.value.preparing)
        } finally { tools.cancel(); scope.cancel() }
    }
}
