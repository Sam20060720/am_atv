package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.model.CatalogSearchSuggestions
import dev.amatv.model.CatalogSearchTerm
import dev.amatv.model.CatalogSearchType
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchBrowserTest {
    private fun song(id: String, library: Boolean = false) = Track(
        id, id, "Artist", "", 1, emptyList(), emptyList(),
        resourceId = id, resourceType = if (library) "library-songs" else "songs",
    )
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun hints(term: String) = CatalogSearchSuggestions(terms = listOf(CatalogSearchTerm(term, term)))
    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(2_000) {
        while (!predicate()) delay(5)
    }

    @Test fun libraryMutationInvalidatesCoveredEmptyResultsAndKeepsCatalogCache() {
        val scope = scope()
        var libraryCalls = 0
        var catalogCalls = 0
        val browser = SearchBrowser(scope, { _, _, _, library ->
            if (library) {
                libraryCalls++
                LibraryPage(if (libraryCalls == 1) emptyList() else listOf(song("added", true)), null, "/library")
            } else { catalogCalls++; LibraryPage(listOf(song("catalog")), null, "/catalog") }
        }, { CatalogSearchSuggestions() })
        try {
            browser.submit("query")
            browser.selectScope(SearchScope.Library)
            assertTrue(browser.state.value.loaded)
            assertTrue(browser.state.value.items.isEmpty())
            browser.setVisible(false)
            browser.invalidateLibrary()
            assertEquals(1, libraryCalls)
            browser.setVisible(true)
            assertEquals("added", browser.state.value.items.single().id)
            browser.selectScope(SearchScope.Catalog)
            assertEquals("catalog", browser.state.value.items.single().id)
            assertEquals(1, catalogCalls)
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun completedSuggestionsSurviveSubmitCategoryAndScopeChangesAndReuseQueryCache() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val reads = mutableListOf<String>()
        val browser = SearchBrowser(scope, { query, _, _, _ -> LibraryPage(listOf(song(query)), null, "/$query") }, {
            reads += it; hints("$it refinement")
        })
        try {
            browser.submit("first")
            awaitCondition { browser.state.value.suggestions.terms.isNotEmpty() }
            val first = browser.state.value.suggestions
            browser.submit() // Same route used by IME Search after leaving editing.
            browser.selectType(CatalogSearchType.Albums)
            browser.setVisible(false); browser.setVisible(true)
            browser.selectScope(SearchScope.Library); browser.selectScope(SearchScope.Catalog)
            assertEquals(first, browser.state.value.suggestions)
            assertFalse(browser.state.value.suggesting)
            assertEquals(listOf("first"), reads)
            browser.submit("second")
            awaitCondition { browser.state.value.suggestions.terms.singleOrNull()?.searchTerm == "second refinement" }
            browser.submit(" first ")
            assertEquals(first, browser.state.value.suggestions)
            assertFalse(browser.state.value.suggesting)
            assertEquals(listOf("first", "second"), reads)
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun imeSubmitSharesAnAlreadyRunningSuggestionLookup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val pending = CompletableDeferred<CatalogSearchSuggestions>()
        var reads = 0
        val browser = SearchBrowser(scope, { _, _, _, _ -> LibraryPage(emptyList(), null, "/first") }, {
            reads++; pending.await()
        })
        try {
            browser.setQuery("first")
            awaitCondition { reads == 1 }
            repeat(3) { browser.submit() }
            pending.complete(hints("first refinement"))
            awaitCondition { browser.state.value.suggestions.terms.isNotEmpty() }
            assertEquals(1, reads)
            assertFalse(browser.state.value.suggesting)
        } finally { pending.complete(hints("cleanup")); browser.dispose(); scope.cancel() }
    }

    @Test fun cancelledOldSuggestionsCannotReplaceNewQueryOrClearItsLoadingState() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val old = CompletableDeferred<CatalogSearchSuggestions>()
        val current = CompletableDeferred<CatalogSearchSuggestions>()
        val reads = mutableListOf<String>()
        val browser = SearchBrowser(scope, { query, _, _, _ -> LibraryPage(emptyList(), null, "/$query") }, { query ->
            reads += query
            withContext(NonCancellable) { if (query == "old" && reads.count { it == "old" } == 1) old.await() else current.await() }
        })
        try {
            browser.setQuery("old"); awaitCondition { reads.size == 1 }
            browser.setQuery("current"); awaitCondition { reads.size == 2 }
            old.complete(hints("stale")); yield()
            assertTrue(browser.state.value.suggestions.terms.isEmpty())
            assertTrue(browser.state.value.suggesting)
            current.complete(hints("fresh"))
            awaitCondition { !browser.state.value.suggesting }
            assertEquals("fresh", browser.state.value.suggestions.terms.single().searchTerm)
            browser.setQuery("old")
            assertTrue(browser.state.value.suggestions.terms.isEmpty()) // Stale callback was not cached either.
            awaitCondition { reads.size == 3 }
        } finally { old.complete(hints("cleanup")); current.complete(hints("cleanup")); browser.dispose(); scope.cancel() }
    }

    @Test fun hiddenSuggestionCompletionCannotFinishTheReplacementVisibleLookup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val hidden = CompletableDeferred<CatalogSearchSuggestions>()
        val visible = CompletableDeferred<CatalogSearchSuggestions>()
        var reads = 0
        val browser = SearchBrowser(scope, { _, _, _, _ -> LibraryPage(emptyList(), null, "/first") }, {
            val read = ++reads
            withContext(NonCancellable) { if (read == 1) hidden.await() else visible.await() }
        })
        try {
            browser.submit("first"); awaitCondition { reads == 1 }
            browser.setVisible(false)
            assertFalse(browser.state.value.suggesting)
            browser.setVisible(true); awaitCondition { reads == 2 }
            hidden.complete(hints("hidden")); yield()
            assertTrue(browser.state.value.suggesting)
            assertTrue(browser.state.value.suggestions.terms.isEmpty())
            visible.complete(hints("visible"))
            awaitCondition { !browser.state.value.suggesting }
            assertEquals("visible", browser.state.value.suggestions.terms.single().searchTerm)
        } finally { hidden.complete(hints("cleanup")); visible.complete(hints("cleanup")); browser.dispose(); scope.cancel() }
    }

    @Test fun switchingAccountScopeDiscardsSuggestionCacheAndLateOldAccountWork() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val old = CompletableDeferred<CatalogSearchSuggestions>()
        val storage = object : SearchHistoryStorage {
            override suspend fun read(scopeKey: String) = emptyList<String>()
            override suspend fun write(scopeKey: String, queries: List<String>) = Unit
        }
        var reads = 0
        val browser = SearchBrowser(scope, { _, _, _, _ -> LibraryPage(emptyList(), null, "/first") }, {
            val read = ++reads
            if (read == 1) withContext(NonCancellable) { old.await() } else hints("new-account")
        })
        try {
            browser.setHistoryScope("account-a", storage); browser.submit("first")
            awaitCondition { reads == 1 }
            browser.setHistoryScope("account-b", storage)
            old.complete(hints("old-account")); yield()
            assertTrue(browser.state.value.suggestions.terms.isEmpty())
            browser.submit("first")
            awaitCondition { browser.state.value.suggestions.terms.isNotEmpty() }
            assertEquals(2, reads)
            assertEquals("new-account", browser.state.value.suggestions.terms.single().searchTerm)
            browser.submit("first")
            assertEquals(2, reads)
            browser.setHistoryScope("account-c", storage)
            assertTrue(browser.state.value.suggestions.terms.isEmpty())
            browser.submit("first")
            awaitCondition { reads == 3 && !browser.state.value.suggesting }
        } finally { old.complete(hints("cleanup")); browser.dispose(); scope.cancel() }
    }

    @Test fun lateQueryAndDisposedRequestsCannotPublish() = runBlocking {
        val first = CompletableDeferred<LibraryPage>()
        val second = CompletableDeferred<LibraryPage>()
        val scope = scope()
        val browser = SearchBrowser(scope, { query, _, _, _ ->
            withContext(NonCancellable) { if (query == "first") first.await() else second.await() }
        }, { CatalogSearchSuggestions() })
        browser.setQuery("first"); browser.submit()
        browser.setQuery("second"); browser.submit()
        first.complete(LibraryPage(listOf(song("stale")), null, "/first"))
        assertEquals("second", browser.state.value.query)
        assertTrue(browser.state.value.items.isEmpty())
        browser.dispose()
        second.complete(LibraryPage(listOf(song("disposed")), null, "/second"))
        assertTrue(browser.state.value.items.isEmpty())
        scope.cancel()
    }

    @Test fun categoryAndScopeCachesRetainDistinctResourceIdentity() {
        val calls = mutableListOf<Pair<CatalogSearchType, Boolean>>()
        val scope = scope()
        val browser = SearchBrowser(scope, { _, type, _, library ->
            calls += type to library
            LibraryPage(listOf(song(if (library) "i.saved" else type.name, library)), null, "/${type.name}/$library")
        }, { CatalogSearchSuggestions() })
        browser.setQuery("Music"); browser.submit()
        browser.selectType(CatalogSearchType.Albums)
        browser.selectType(CatalogSearchType.Songs)
        assertEquals("Songs", browser.state.value.items.single().resourceId)
        assertEquals(2, calls.size)
        browser.selectScope(SearchScope.Library)
        assertEquals("library-songs", browser.state.value.items.single().resourceType)
        browser.selectScope(SearchScope.Catalog)
        assertEquals("songs", browser.state.value.items.single().resourceType)
        assertEquals(3, calls.size)
        browser.dispose(); scope.cancel()
    }

    @Test fun failedAppendKeepsCursorAndRetryDeduplicatesWithoutDroppingSavedSongs() {
        var calls = 0
        val scope = scope()
        val browser = SearchBrowser(scope, { _, _, next, _ ->
            calls++
            when (calls) {
                1 -> LibraryPage(listOf(song("i.one", true)), "/next", "/first", 3)
                2 -> { assertEquals("/next", next); error("Connection lost") }
                else -> LibraryPage(listOf(song("i.one", true), song("i.two", true)), null, "/next", 3)
            }
        }, { CatalogSearchSuggestions() })
        browser.selectScope(SearchScope.Library)
        browser.setQuery("Song"); browser.submit(); browser.loadMore()
        assertTrue(browser.state.value.failed)
        assertEquals(listOf("i.one"), browser.state.value.items.map { it.resourceId })
        assertEquals("/next", browser.state.value.next)
        browser.retry()
        assertFalse(browser.state.value.failed)
        assertEquals(listOf("i.one", "i.two"), browser.state.value.items.map { it.resourceId })
        assertNull(browser.state.value.next)
        browser.loadMore()
        assertEquals(3, calls)
        browser.dispose(); scope.cancel()
    }

    @Test fun hiddenSearchCancelsWorkAndResumesOnlyUnfinishedSelectedCategory() = runBlocking {
        val pending = CompletableDeferred<LibraryPage>()
        var calls = 0
        val scope = scope()
        val browser = SearchBrowser(scope, { _, _, _, _ ->
            if (++calls == 1) withContext(NonCancellable) { pending.await() }
            else LibraryPage(listOf(song("current")), null, "/current")
        }, { CatalogSearchSuggestions() })
        browser.setQuery("Music"); browser.submit()
        browser.setVisible(false)
        pending.complete(LibraryPage(listOf(song("hidden")), null, "/hidden"))
        assertFalse(browser.state.value.loading)
        assertTrue(browser.state.value.items.isEmpty())
        browser.submit(); browser.retry(); browser.loadMore()
        assertEquals(1, calls)
        browser.setVisible(true)
        assertEquals("current", browser.state.value.items.single().id)
        browser.setVisible(false); browser.setVisible(true)
        assertEquals(2, calls)
        browser.dispose(); scope.cancel()
    }

    @Test fun matchingSongLabelsDoNotCollapseDifferentEditionsAcrossSearchPages() {
        val original = song("1537477069").copy(title = "positions", artist = "Ariana Grande", album = "Positions", contentRating = "explicit")
        val deluxe = song("1553944486").copy(title = "positions", artist = "Ariana Grande", album = "Positions (Deluxe)", contentRating = "explicit")
        val clean = song("1537486846").copy(title = "positions", artist = "Ariana Grande", album = "Positions", contentRating = "clean")
        val scope = scope()
        val browser = SearchBrowser(scope, { _, _, next, _ ->
            if (next == null) LibraryPage(listOf(original, deluxe), "/next", "/first")
            else LibraryPage(listOf(deluxe, clean), null, "/next")
        }, { CatalogSearchSuggestions() })
        browser.setQuery("positions"); browser.submit(); browser.loadMore()
        assertEquals(listOf(original, deluxe, clean), browser.state.value.items)
        assertEquals(listOf("explicit", "explicit", "clean"), browser.state.value.items.map { it.contentRating })
        browser.dispose(); scope.cancel()
    }
}
