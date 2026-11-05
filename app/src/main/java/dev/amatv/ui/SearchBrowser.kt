package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.model.CatalogSearchSuggestions
import dev.amatv.model.CatalogSearchType
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SearchScope { Catalog, Library }

internal data class SearchBrowserState(
    val query: String = "",
    val scope: SearchScope = SearchScope.Catalog,
    val type: CatalogSearchType = CatalogSearchType.Songs,
    val items: List<Track> = emptyList(),
    val next: String? = null,
    val total: Int? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val failureReason: CatalogFailureReason? = null,
    val suggestions: CatalogSearchSuggestions = CatalogSearchSuggestions(),
    val suggesting: Boolean = false,
    val history: List<String> = emptyList(),
    val historyLoading: Boolean = false,
    val cacheRevision: Long = 0L,
    val cachedResultKeys: Set<String> = emptySet(),
) {
    val resultKey: String get() = "${scope.name}:${type.name}:${query.trim()}"
}

/** UI-scope controller. Only the selected category loads; old queries cannot replace new results. */
internal class SearchBrowser(
    private val scope: CoroutineScope,
    private val load: suspend (query: String, type: CatalogSearchType, next: String?, library: Boolean) -> LibraryPage,
    private val suggestions: suspend (query: String) -> CatalogSearchSuggestions,
) {
    private data class Key(val scope: SearchScope, val type: CatalogSearchType, val query: String) {
        val resultKey: String get() = "${scope.name}:${type.name}:$query"
    }
    private data class Results(
        var items: List<Track> = emptyList(), var next: String? = null, var total: Int? = null,
        var loaded: Boolean = false, var loading: Boolean = false, var failed: Boolean = false,
        var failureReason: CatalogFailureReason? = null,
        val visited: MutableSet<String> = mutableSetOf(),
    )
    private val mutableState = MutableStateFlow(SearchBrowserState())
    val state = mutableState.asStateFlow()
    private val pages = linkedMapOf<Key, Results>()
    private var activeQuery = ""
    private var queryGeneration = 0L
    private var requestGeneration = 0L
    private var debounce: Job? = null
    private var requestJob: Job? = null
    private var requestKey: Key? = null
    private var suggestionJob: Job? = null
    private var suggestionGeneration = 0L
    private var suggestedQuery: String? = null
    private val suggestionCache = linkedMapOf<String, CatalogSearchSuggestions>()
    private var visible = true
    private var historyStorage: SearchHistoryStorage? = null
    private var historyScopeKey: String? = null
    private var historyGeneration = 0L
    private var historyReadJob: Job? = null
    private var historyWriteJob: Job? = null
    private var pendingHistory = emptyList<String>()
    private var historyClearedDuringRead = false

    /** The host provides an opaque stable account+locale scope, never a saved access token. */
    fun setHistoryScope(scopeKey: String, storage: SearchHistoryStorage) {
        if (historyScopeKey == scopeKey && historyStorage === storage) return
        historyGeneration++
        val generation = historyGeneration
        historyReadJob?.cancel(); historyWriteJob?.cancel()
        historyScopeKey = scopeKey
        historyStorage = storage
        pendingHistory = emptyList()
        historyClearedDuringRead = false
        queryGeneration++
        cancelRequest(); debounce?.cancel(); debounce = null
        cancelSuggestions()
        pages.clear(); suggestionCache.clear(); activeQuery = ""; suggestedQuery = null
        mutableState.value = SearchBrowserState(historyLoading = true, cacheRevision = mutableState.value.cacheRevision + 1)
        historyReadJob = scope.launch {
            val saved = try { storage.read(scopeKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            if (generation != historyGeneration) return@launch
            val changed = pendingHistory.isNotEmpty() || historyClearedDuringRead
            val history = normalizedSearchHistory(pendingHistory + if (historyClearedDuringRead) emptyList() else saved)
            mutableState.value = mutableState.value.copy(history = history, historyLoading = false)
            pendingHistory = emptyList()
            if (changed) saveHistory()
        }
    }

    fun clearHistory() {
        pendingHistory = emptyList()
        historyClearedDuringRead = true
        mutableState.value = mutableState.value.copy(history = emptyList())
        saveHistory()
    }

    fun recordSelection() = recordQuery(activeQuery)

    /** A history choice restores the most recently used cached scope/category for that query. */
    fun restoreQuery(query: String) {
        val previous = pages.keys.lastOrNull { it.query == query.trim() }
        if (previous != null) mutableState.value = mutableState.value.copy(scope = previous.scope, type = previous.type)
        submit(query)
    }

    private fun recordQuery(query: String) {
        val value = normalizedSearchHistory(listOf(query)).firstOrNull() ?: return
        if (mutableState.value.historyLoading) pendingHistory = normalizedSearchHistory(listOf(value) + pendingHistory)
        val updated = normalizedSearchHistory(listOf(value) + mutableState.value.history)
        if (updated == mutableState.value.history && !mutableState.value.historyLoading) return
        mutableState.value = mutableState.value.copy(history = updated)
        saveHistory()
    }

    private fun saveHistory() {
        if (mutableState.value.historyLoading) return
        val store = historyStorage ?: return
        val key = historyScopeKey ?: return
        val generation = historyGeneration
        val snapshot = mutableState.value.history
        historyWriteJob?.cancel()
        historyWriteJob = scope.launch {
            if (generation != historyGeneration) return@launch
            try { store.write(key, snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* History storage cannot break a real search or playback action. */ }
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            cancelRequest()
            debounce?.cancel(); debounce = null
            cancelSuggestions()
            mutableState.value = mutableState.value.copy(suggesting = false)
            publish()
        } else {
            ensureResults()
            ensureSuggestions()
        }
    }

    fun setQuery(value: String) {
        val query = value.trim()
        if (value == mutableState.value.query) return
        mutableState.value = mutableState.value.copy(query = value)
        if (query == activeQuery) return
        activeQuery = query
        queryGeneration++
        cancelRequest()
        debounce?.cancel()
        cancelSuggestions()
        val cachedSuggestions = suggestionCache.remove(query)?.also { suggestionCache[query] = it }
        suggestedQuery = query.takeIf { cachedSuggestions != null }
        mutableState.value = mutableState.value.copy(items = emptyList(), next = null, total = null,
            loading = false, loaded = false, failed = false, failureReason = null,
            suggestions = cachedSuggestions ?: CatalogSearchSuggestions(), suggesting = cachedSuggestions == null && visible && query.isNotEmpty() && mutableState.value.scope == SearchScope.Catalog)
        val cached = pages[key()]
        if (cached?.loaded == true || cached?.failed == true) {
            publish()
            ensureSuggestions()
            return
        }
        if (query.isEmpty() || !visible) { publish(); return }
        val generation = queryGeneration
        debounce = scope.launch {
            delay(400)
            if (generation != queryGeneration) return@launch
            debounce = null
            ensureResults()
        }
        publish()
        ensureSuggestions()
    }

    private fun ensureSuggestions() {
        val query = activeQuery
        if (!visible || query.isEmpty() || mutableState.value.scope != SearchScope.Catalog || suggestedQuery == query) return
        // Submit/category actions for the same query share its current lookup.
        // Query, scope, visibility and account changes already cancel it below
        // their own generation boundary; restarting here postpones every IME submit.
        if (suggestionJob?.isActive == true) return
        cancelSuggestions()
        val generation = queryGeneration
        val suggestionEpoch = suggestionGeneration
        mutableState.value = mutableState.value.copy(suggesting = true)
        suggestionJob = scope.launch {
            delay(180)
            try {
                val result = suggestions(query)
                currentCoroutineContext().ensureActive()
                if (generation == queryGeneration && suggestionEpoch == suggestionGeneration) {
                    suggestedQuery = query
                    suggestionCache.remove(query)
                    suggestionCache[query] = result
                    while (suggestionCache.size > SearchResultCacheLimit) suggestionCache.remove(suggestionCache.keys.first())
                    mutableState.value = mutableState.value.copy(suggestions = result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Suggestions never make the real result search fail. */ }
            finally {
                if (generation == queryGeneration && suggestionEpoch == suggestionGeneration) {
                    suggestionJob = null
                    mutableState.value = mutableState.value.copy(suggesting = false)
                }
            }
        }
    }

    fun submit(value: String = mutableState.value.query) {
        if (value != mutableState.value.query) setQuery(value)
        debounce?.cancel()
        debounce = null
        if (activeQuery.isNotEmpty()) {
            recordQuery(activeQuery)
            if (pages[key()]?.failed == true) retry() else ensureResults()
            ensureSuggestions()
        } else publish()
    }

    fun selectType(type: CatalogSearchType) {
        if (mutableState.value.scope == SearchScope.Library && type.libraryResourceType == null) return
        if (type == mutableState.value.type) { submit(); return }
        cancelRequest()
        debounce?.cancel(); debounce = null
        mutableState.value = mutableState.value.copy(type = type)
        publish()
        ensureResults()
    }

    fun selectScope(value: SearchScope) {
        if (value == mutableState.value.scope) return
        cancelRequest()
        debounce?.cancel(); debounce = null
        val type = mutableState.value.type.takeUnless { value == SearchScope.Library && it.libraryResourceType == null }
            ?: CatalogSearchType.Songs
        mutableState.value = mutableState.value.copy(scope = value, type = type)
        if (value == SearchScope.Library) {
            cancelSuggestions()
            mutableState.value = mutableState.value.copy(suggesting = false)
        } else ensureSuggestions()
        publish()
        ensureResults()
    }

    fun loadMore() {
        if (!visible) return
        val page = pages[key()] ?: return
        if (page.next != null && !page.loading && !page.failed) request(first = false)
    }

    fun retry() {
        val page = pages[key()]
        request(first = page?.loaded != true)
    }

    fun invalidateLibrary() {
        if (requestKey?.scope == SearchScope.Library) cancelRequest()
        pages.keys.removeAll { it.scope == SearchScope.Library }
        if (mutableState.value.scope == SearchScope.Library) {
            publish()
            ensureResults()
        }
    }

    fun dispose() {
        visible = false
        queryGeneration++
        cancelRequest()
        debounce?.cancel(); debounce = null
        cancelSuggestions()
        pages.clear(); suggestionCache.clear()
        historyGeneration++
        historyReadJob?.cancel(); historyWriteJob?.cancel()
    }

    private fun key() = Key(mutableState.value.scope, mutableState.value.type, activeQuery)
    private fun cancelSuggestions() {
        suggestionGeneration++
        suggestionJob?.cancel(); suggestionJob = null
    }
    private fun touch(key: Key) {
        pages.remove(key)?.let { pages[key] = it }
        while (pages.size > SearchResultCacheLimit) pages.remove(pages.keys.first())
    }
    private fun ensureResults() {
        if (!visible) return
        if (activeQuery.isBlank()) { publish(); return }
        val page = pages[key()]
        if (page?.loaded == true || page?.loading == true || page?.failed == true) { publish(); return }
        request(first = true)
    }

    private fun cancelRequest() {
        requestGeneration++
        requestJob?.cancel(); requestJob = null
        requestKey?.let { pages[it]?.loading = false }
        requestKey = null
    }

    private fun request(first: Boolean) {
        if (!visible || activeQuery.isBlank()) return
        val key = key()
        val page = pages.getOrPut(key) { Results() }
        touch(key)
        if (page.loading) return
        val next = if (first) null else page.next ?: return
        cancelRequest()
        page.loading = true
        page.failed = false
        page.failureReason = null
        requestKey = key
        val query = activeQuery
        val queryEpoch = queryGeneration
        val requestEpoch = ++requestGeneration
        publish()
        requestJob = scope.launch {
            try {
                val result = load(query, key.type, next, key.scope == SearchScope.Library)
                currentCoroutineContext().ensureActive()
                if (queryEpoch != queryGeneration || requestEpoch != requestGeneration) return@launch
                if (first) page.visited.clear()
                check(result.requestedPage !in page.visited && result.nextPage != result.requestedPage &&
                    (result.nextPage == null || result.nextPage !in page.visited))
                page.visited += result.requestedPage
                page.items = if (first) result.items else mergeLibraryPage(page.items, result.items)
                page.next = result.nextPage
                page.total = result.total ?: page.total
                page.loaded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (queryEpoch == queryGeneration && requestEpoch == requestGeneration) {
                    val reason = failure.catalogUiFailureReason()
                    page.failed = true
                    page.failureReason = reason
                }
            } finally {
                if (queryEpoch == queryGeneration && requestEpoch == requestGeneration) {
                    page.loading = false
                    requestKey = null
                    requestJob = null
                    publish()
                }
            }
        }
    }

    private fun publish() {
        touch(key())
        val page = pages[key()]
        mutableState.value = mutableState.value.copy(items = page?.items.orEmpty(), next = page?.next,
            total = page?.total, loaded = page?.loaded == true, failed = page?.failed == true,
            failureReason = page?.failureReason,
            cachedResultKeys = pages.keys.mapTo(linkedSetOf()) { it.resultKey },
            loading = page?.loading == true || debounce?.isActive == true)
    }
}
