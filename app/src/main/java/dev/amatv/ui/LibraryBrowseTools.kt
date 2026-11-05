package dev.amatv.ui

import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

internal enum class LibrarySortOrder { Original, Name, Artist }
internal enum class LibraryToolsPhase { Idle, Collecting, Indexing, Paused, Failed }
internal data class LibraryLetterPosition(val letter: String, val index: Int)
internal data class LibraryJumpRequest(val sequence: Long, val resourceKey: String, val index: Int)
internal fun libraryViewportFirstItem(oldFirst: Int, oldFocused: Int, newFocused: Int, columns: Int): Int {
    val count = columns.coerceAtLeast(1)
    val rowsBeforeFocus = (oldFocused.coerceAtLeast(0) / count - oldFirst.coerceAtLeast(0) / count).coerceAtLeast(0)
    return (newFocused.coerceAtLeast(0) / count - rowsBeforeFocus).coerceAtLeast(0) * count
}
internal data class LibraryPagingSnapshot(val hasMore: Boolean, val loading: Boolean, val failed: Boolean, val loadedPages: Int)
internal data class LibraryBrowseToolsState(
    val sort: LibrarySortOrder = LibrarySortOrder.Original,
    val filter: String = "",
    /** Null means the server's current original-order rows, not a partial full-library index. */
    val indexedItems: List<Track>? = null,
    val letters: List<LibraryLetterPosition> = emptyList(),
    val phase: LibraryToolsPhase = LibraryToolsPhase.Idle,
    val loadedCount: Int = 0,
    val revision: Long = 0,
) {
    val preparing: Boolean get() = phase == LibraryToolsPhase.Collecting || phase == LibraryToolsPhase.Indexing
}

internal fun libraryResourceKey(item: Track): String = "${item.resourceType}:${item.resourceId ?: item.id}"
/** Preserve indexOfFirst semantics when a source repeats the same resource identity. */
internal fun libraryResourceIndices(items: List<Track>): Map<String, Int> = LinkedHashMap<String, Int>(items.size).apply {
    items.forEachIndexed { index, item -> putIfAbsent(libraryResourceKey(item), index) }
}

internal fun libraryFocusTarget(indices: Map<String, Int>, itemCount: Int, resourceKey: String?, previousIndex: Int): Int? {
    if (itemCount <= 0) return null
    return resourceKey?.let(indices::get) ?: previousIndex.coerceIn(0, itemCount - 1)
}

internal fun libraryFocusTarget(items: List<Track>, resourceKey: String?, previousIndex: Int): Int? =
    libraryFocusTarget(libraryResourceIndices(items), items.size, resourceKey, previousIndex)

internal data class LibraryIndexedResult(val items: List<Track>, val letters: List<LibraryLetterPosition>)

/** Called on the worker, never for each composition or keyboard character. */
internal suspend fun buildLibraryIndex(items: List<Track>, sort: LibrarySortOrder, query: String, locale: Locale): LibraryIndexedResult {
    fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(locale)
    val needle = normalized(query.trim())
    val selected = ArrayList<Pair<Int, Track>>(items.size)
    items.forEachIndexed { index, item ->
        if (index % 64 == 0) currentCoroutineContext().ensureActive()
        if (needle.isEmpty() || normalized(item.title + "\n" + item.artist + "\n" + item.album).contains(needle)) selected += index to item
    }
    if (sort != LibrarySortOrder.Original) {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        selected.sortWith { left, right ->
            val primary = if (sort == LibrarySortOrder.Artist) collator.compare(left.second.artist, right.second.artist) else 0
            primary.takeUnless { it == 0 }
                ?: collator.compare(left.second.title, right.second.title).takeUnless { it == 0 }
                ?: left.first.compareTo(right.first)
        }
        currentCoroutineContext().ensureActive()
    }
    val rows = selected.map { it.second }
    val first = linkedMapOf<String, Int>()
    if (sort != LibrarySortOrder.Original) rows.forEachIndexed { index, item ->
        val initial = normalized(if (sort == LibrarySortOrder.Artist) item.artist else item.title).firstOrNull()?.uppercaseChar()
        val letter = initial?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
        first.putIfAbsent(letter, index)
    }
    val letters = first.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.key == "#" }.thenBy { it.key })
        .map { LibraryLetterPosition(it.key, it.value) }
    return LibraryIndexedResult(rows, letters)
}

/**
 * The existing pager owns cursors. A tools intent claims one page until its
 * loadedPages revision/loading cycle finishes. Completed indexes replace the
 * view atomically; incomplete work never pretends that the first page is all.
 */
internal class LibraryBrowseTools(
    private val scope: CoroutineScope,
    private val onLoadMore: () -> Unit,
    private val onRetry: () -> Unit,
    private val locale: Locale = Locale.getDefault(),
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pageDelayMillis: Long = 120L,
    private val maxPagesPerBatch: Int = 128,
    private val maxBatchMillis: Long = 60_000L,
) {
    private data class Intent(val sort: LibrarySortOrder, val filter: String)
    private data class Claim(val pages: Int, val count: Int, var sawLoading: Boolean = false)
    private val mutableState = MutableStateFlow(LibraryBrowseToolsState())
    val state = mutableState.asStateFlow()
    private var raw: List<Track> = emptyList()
    private var page = LibraryPagingSnapshot(false, true, false, 0)
    private var active = false
    private var intent: Intent? = null
    private var claim: Claim? = null
    private var generation = 0L
    private var sentPages = 0
    private var startedAt = 0L
    private var allowRetry = false
    private var autoRefreshIndex = false
    private var indexDirty = false
    private var pageJob: Job? = null
    private var indexJob: Job? = null
    private var timeoutJob: Job? = null

    init { require(maxPagesPerBatch > 0 && maxBatchMillis > 0 && pageDelayMillis >= 0) }

    private var contentRevision = 0L
    fun update(items: List<Track>, paging: LibraryPagingSnapshot, visible: Boolean, revision: Long = 0L) {
        val changed = items !== raw || revision != contentRevision
        contentRevision = revision
        raw = items; page = paging; active = visible
        if (changed && mutableState.value.indexedItems != null) indexDirty = true
        mutableState.value = mutableState.value.copy(loadedCount = items.size)
        if (!active) {
            // A covered window stops work, but does not cancel the applied
            // index's refresh policy or an explicit Paused/Failed intent.
            stopJobs(); generation++
            if (mutableState.value.preparing) {
                intent = null
                mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Idle)
            }
            return
        }
        claim?.let { pending ->
            if (page.loading) pending.sawLoading = true
            else if (page.failed || page.loadedPages > pending.pages || raw.size > pending.count || pending.sawLoading || !page.hasMore) claim = null
        }
        if (intent == null && indexDirty && mutableState.value.indexedItems != null) {
            if (autoRefreshIndex) request(mutableState.value.sort, mutableState.value.filter)
            else {
                // A cancelled intent cannot be resurrected by its final in-flight page.
                intent = Intent(mutableState.value.sort, mutableState.value.filter)
                mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Paused)
            }
            return
        }
        if (changed && indexJob?.isActive == true) {
            generation++; indexJob?.cancel(); indexJob = null
            mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Collecting)
        }
        if (mutableState.value.phase == LibraryToolsPhase.Collecting) drive()
    }

    fun request(sort: LibrarySortOrder, filter: String = mutableState.value.filter) {
        stopJobs(); generation++
        val query = filter.trim().take(200)
        if (sort == LibrarySortOrder.Original && query.isEmpty()) {
            intent = null; autoRefreshIndex = false; indexDirty = false
            mutableState.value = LibraryBrowseToolsState(loadedCount = raw.size, revision = mutableState.value.revision + 1)
            return
        }
        intent = Intent(sort, query)
        autoRefreshIndex = true
        sentPages = 0; startedAt = nowMillis(); allowRetry = true
        mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Collecting)
        startTimeout()
        drive()
    }

    fun resume() {
        if (intent == null || !active) return
        stopJobs(); generation++; sentPages = 0; startedAt = nowMillis(); allowRetry = true
        mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Collecting)
        startTimeout(); drive()
    }

    fun cancel() {
        stopJobs(); generation++; intent = null; autoRefreshIndex = false
        mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Idle)
    }

    private fun stopJobs() {
        pageJob?.cancel(); pageJob = null
        indexJob?.cancel(); indexJob = null
        timeoutJob?.cancel(); timeoutJob = null
        claim = null
    }

    private fun startTimeout() {
        val ticket = generation
        timeoutJob = scope.launch {
            delay(maxBatchMillis)
            if (ticket == generation && mutableState.value.phase == LibraryToolsPhase.Collecting) pause()
        }
    }

    private fun pause() {
        pageJob?.cancel(); pageJob = null; claim = null
        timeoutJob?.cancel(); timeoutJob = null
        mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Paused)
    }

    private fun drive() {
        val target = intent ?: return
        if (!active || mutableState.value.phase != LibraryToolsPhase.Collecting || claim != null || page.loading) return
        if (page.failed && !allowRetry) {
            timeoutJob?.cancel(); timeoutJob = null
            mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Failed)
            return
        }
        if (!page.hasMore && !page.failed) { index(target); return }
        if (sentPages >= maxPagesPerBatch || nowMillis() - startedAt >= maxBatchMillis) { pause(); return }
        if (pageJob?.isActive == true) return
        val ticket = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(pageDelayMillis)
                // The ordinary pager may finish or fail while this request is
                // waiting. Do not dispatch a stale continuation after indexing
                // has started, and retry the current page state.
                if (ticket != generation || !active || mutableState.value.phase != LibraryToolsPhase.Collecting ||
                    page.loading || claim != null || (!page.hasMore && !page.failed)) return@launch
                claim = Claim(page.loadedPages, raw.size)
                allowRetry = false
                sentPages++
                if (page.failed) onRetry() else onLoadMore()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == generation) {
                    claim = null
                    timeoutJob?.cancel(); timeoutJob = null
                    mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Failed)
                }
            } finally {
                if (pageJob === currentCoroutineContext()[Job]) {
                    pageJob = null
                    if (ticket == generation) drive()
                }
            }
        }
        pageJob = job
        job.start()
    }

    private fun index(target: Intent) {
        if (indexJob?.isActive == true) return
        timeoutJob?.cancel(); timeoutJob = null
        val ticket = generation
        val snapshot = raw
        mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Indexing)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(worker) { buildLibraryIndex(snapshot, target.sort, target.filter, locale) }
                currentCoroutineContext().ensureActive()
                if (ticket != generation || !active) return@launch
                intent = null; indexDirty = false
                mutableState.value = mutableState.value.copy(sort = target.sort, filter = target.filter,
                    indexedItems = result.items, letters = result.letters, phase = LibraryToolsPhase.Idle,
                    revision = mutableState.value.revision + 1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == generation) mutableState.value = mutableState.value.copy(phase = LibraryToolsPhase.Failed)
            } finally { if (indexJob === currentCoroutineContext()[Job]) indexJob = null }
        }
        indexJob = job
        job.start()
    }
}
