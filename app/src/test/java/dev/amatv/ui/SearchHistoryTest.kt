package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.model.CatalogSearchSuggestions
import dev.amatv.model.CatalogSearchType
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchHistoryTest {
    private fun track(id: String) = Track(id, id, "Artist", "", 1, emptyList(), emptyList())
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private class Store : SearchHistoryStorage {
        val saved = mutableMapOf<String, List<String>>()
        val writes = mutableListOf<Pair<String, List<String>>>()
        var loading: CompletableDeferred<List<String>>? = null
        override suspend fun read(scopeKey: String): List<String> = loading?.await() ?: saved[scopeKey].orEmpty()
        override suspend fun write(scopeKey: String, queries: List<String>) {
            saved[scopeKey] = queries.toList(); writes += scopeKey to queries.toList()
        }
    }

    @Test fun explicitSearchesAndResultSelectionsRecordButTypingDoesNot() {
        val scope = scope(); val store = Store()
        val browser = SearchBrowser(scope, { query, _, _, _ -> LibraryPage(listOf(track(query)), null, "/$query") }, { CatalogSearchSuggestions() })
        try {
            browser.setHistoryScope("account-a:zh-TW", store)
            browser.setQuery("p"); browser.setQuery("po"); browser.setQuery("positions")
            assertTrue(browser.state.value.history.isEmpty())
            assertTrue(store.writes.isEmpty())
            browser.submit()
            assertEquals(listOf("positions"), browser.state.value.history)
            browser.setQuery("newjeans")
            assertEquals(listOf("positions"), browser.state.value.history)
            browser.recordSelection()
            assertEquals(listOf("newjeans", "positions"), store.saved["account-a:zh-TW"])
            browser.submit("  POSITIONS  ")
            assertEquals(listOf("POSITIONS", "newjeans"), browser.state.value.history)
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun historyIsBoundedDeduplicatedAndClearSurvivesANewBrowser() {
        val scope = scope(); val store = Store()
        val browser = SearchBrowser(scope, { query, _, _, _ -> LibraryPage(emptyList(), null, "/$query") }, { CatalogSearchSuggestions() })
        try {
            browser.setHistoryScope("account-a:en", store)
            repeat(25) { browser.submit("query-$it") }
            assertEquals(20, browser.state.value.history.size)
            assertEquals("query-24", browser.state.value.history.first())
            assertEquals("query-5", browser.state.value.history.last())
            browser.clearHistory()
            assertTrue(store.saved.getValue("account-a:en").isEmpty())
            val restored = SearchBrowser(scope, { _, _, _, _ -> error("No eager search") }, { CatalogSearchSuggestions() })
            try { restored.setHistoryScope("account-a:en", store); assertTrue(restored.state.value.history.isEmpty()) }
            finally { restored.dispose() }
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun accountAndLocaleSwitchClearsQueryResultsAndShowsOnlyMatchingHistory() {
        val scope = scope(); val store = Store()
        store.saved["account-b:en"] = listOf("other account")
        store.saved["account-a:zh-TW"] = listOf("另一種語言")
        var loads = 0
        val browser = SearchBrowser(scope, { query, _, _, _ -> loads++; LibraryPage(listOf(track(query)), null, "/$query") }, { CatalogSearchSuggestions() })
        try {
            browser.setHistoryScope("account-a:en", store)
            browser.submit("private-a")
            val originalRevision = browser.state.value.cacheRevision
            browser.setHistoryScope("account-b:en", store)
            assertEquals("", browser.state.value.query)
            assertTrue(browser.state.value.items.isEmpty())
            assertTrue(browser.state.value.cachedResultKeys.isEmpty())
            assertEquals(listOf("other account"), browser.state.value.history)
            assertTrue(browser.state.value.cacheRevision > originalRevision)
            browser.setHistoryScope("account-a:zh-TW", store)
            assertEquals(listOf("另一種語言"), browser.state.value.history)
            browser.setHistoryScope("account-a:en", store)
            assertEquals(listOf("private-a"), browser.state.value.history)
            assertEquals(1, loads)
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun aSubmissionDuringHistoryReadMergesAheadOfSavedQueries() = runBlocking {
        val scope = scope(); val store = Store(); val pending = CompletableDeferred<List<String>>()
        store.loading = pending
        val browser = SearchBrowser(scope, { query, _, _, _ -> LibraryPage(emptyList(), null, "/$query") }, { CatalogSearchSuggestions() })
        try {
            browser.setHistoryScope("account-a:en", store)
            browser.submit("new")
            assertTrue(store.writes.isEmpty())
            pending.complete(listOf("old", "older"))
            assertEquals(listOf("new", "old", "older"), browser.state.value.history)
            assertEquals(browser.state.value.history, store.saved["account-a:en"])
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun clearingWhileHistoryLoadsCannotResurrectOldQueries() = runBlocking {
        val scope = scope(); val store = Store(); val pending = CompletableDeferred<List<String>>()
        store.loading = pending
        val browser = SearchBrowser(scope, { _, _, _, _ -> error("No search") }, { CatalogSearchSuggestions() })
        try {
            browser.setHistoryScope("account-a:en", store)
            browser.clearHistory()
            pending.complete(listOf("must stay cleared"))
            assertTrue(browser.state.value.history.isEmpty())
            assertEquals(emptyList<String>(), store.saved["account-a:en"])
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun returningToACachedQueryRestoresItsCategoryRowsAndCursorWithoutANewRead() {
        val scope = scope()
        val calls = mutableListOf<String>()
        val browser = SearchBrowser(scope, { query, type, next, _ ->
            calls += "$query:$type:$next"
            if (next == null) LibraryPage(listOf(track(query)), "/more", "/$query/$type")
            else LibraryPage(listOf(track("$query-more")), null, "/more")
        }, { CatalogSearchSuggestions() })
        try {
            browser.selectType(CatalogSearchType.Albums); browser.submit("first"); browser.loadMore()
            browser.submit("second"); browser.selectType(CatalogSearchType.Songs)
            val readsBeforeReturn = calls.size
            browser.restoreQuery("first")
            assertEquals(CatalogSearchType.Albums, browser.state.value.type)
            assertEquals(listOf("first", "first-more"), browser.state.value.items.map { it.id })
            assertNull(browser.state.value.next)
            assertEquals(readsBeforeReturn, calls.size)
            assertTrue(browser.state.value.cachedResultKeys.contains("Catalog:Albums:first"))
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun resultCacheEvictsTheLeastRecentlyUsedOfSixContexts() {
        val scope = scope(); var calls = 0
        val browser = SearchBrowser(scope, { query, _, _, _ -> calls++; LibraryPage(listOf(track(query)), null, "/$query") }, { CatalogSearchSuggestions() })
        try {
            repeat(6) { browser.submit("q$it") }
            browser.restoreQuery("q0")
            assertEquals(6, calls)
            browser.submit("q6")
            assertEquals(6, browser.state.value.cachedResultKeys.size)
            assertFalse(browser.state.value.cachedResultKeys.contains("Catalog:Songs:q1"))
            browser.restoreQuery("q0")
            assertEquals(7, calls)
            browser.restoreQuery("q1")
            assertEquals(8, calls)
            assertEquals(6, browser.state.value.cachedResultKeys.size)
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun malformedOrOversizedQueriesAndOpaqueScopeKeysStayBounded() {
        assertEquals(listOf("One", "Two"), normalizedSearchHistory(listOf(" One ", "one", "", "Two", "x".repeat(201))))
        val account = searchHistoryStorageKey("account-id:zh-TW")
        assertEquals(64, account.length)
        assertTrue(account.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(account, searchHistoryStorageKey("account-id:en"))
        assertFalse(account.contains("account-id"))
    }
}
