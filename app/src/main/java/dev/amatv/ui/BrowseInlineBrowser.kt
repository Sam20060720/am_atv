package dev.amatv.ui

import dev.amatv.R
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.model.CatalogChartCategory
import dev.amatv.model.CatalogChartCursor
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** These are real catalog collections, displayed after the editorial New feed. */
internal enum class BrowseInlineSection(val shelfId: String, val chart: CatalogChartCategory? = null) {
    Songs("browse:charts:songs", CatalogChartCategory.Songs),
    Albums("browse:charts:albums", CatalogChartCategory.Albums),
    Playlists("browse:charts:playlists", CatalogChartCategory.Playlists),
    City("browse:charts:city", CatalogChartCategory.City),
    DailyGlobal("browse:charts:global", CatalogChartCategory.DailyGlobal),
    Genres("browse:genres"),
}

internal fun BrowseInlineSection.labelResource(): Int = when (this) {
    BrowseInlineSection.Songs -> R.string.catalog_charts_songs
    BrowseInlineSection.Albums -> R.string.catalog_charts_albums
    BrowseInlineSection.Playlists -> R.string.catalog_charts_playlists
    BrowseInlineSection.City -> R.string.catalog_charts_city
    BrowseInlineSection.DailyGlobal -> R.string.catalog_charts_global
    BrowseInlineSection.Genres -> R.string.catalog_all_genres
}

internal sealed interface BrowseInlineCursor {
    data class Genres(val path: String) : BrowseInlineCursor
    data class Chart(val cursor: CatalogChartCursor) : BrowseInlineCursor
}

internal data class BrowseInlinePage(
    val items: List<Track>,
    val requestedPage: String,
    val next: BrowseInlineCursor? = null,
    val nextKey: String? = null,
    val total: Int? = null,
    val title: String? = null,
)

internal data class BrowseInlineState(
    val pages: Map<BrowseInlineSection, ArtistSectionState> = emptyMap(),
    val titles: Map<BrowseInlineSection, String> = emptyMap(),
)

/**
 * Independent first pages never hold up the editorial feed or replace it on
 * failure. Two reads at most run concurrently; covering/leaving New cancels
 * pending work while retaining every settled page and its opaque cursor.
 */
internal class BrowseInlineBrowser(
    private val scope: CoroutineScope,
    private val load: suspend (BrowseInlineSection, BrowseInlineCursor?) -> BrowseInlinePage,
) {
    private val mutableState = MutableStateFlow(BrowseInlineState())
    val state = mutableState.asStateFlow()
    private val permits = Semaphore(2)
    private val jobs = mutableMapOf<BrowseInlineSection, Job>()
    private val cursors = mutableMapOf<BrowseInlineSection, BrowseInlineCursor>()
    private val visited = mutableMapOf<BrowseInlineSection, MutableSet<String>>()
    private var generation = 0L
    private var visible = false

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) BrowseInlineSection.entries.forEach { section ->
            val page = mutableState.value.pages[section]
            if (page?.loaded != true && page?.failed != true) request(section, append = false)
        } else cancel()
    }

    fun more(shelfId: String) {
        val section = BrowseInlineSection.entries.firstOrNull { it.shelfId == shelfId } ?: return
        val page = mutableState.value.pages[section]
        request(section, append = page?.loaded == true && cursors[section] != null)
    }

    fun close() { visible = false; cancel() }

    private fun request(section: BrowseInlineSection, append: Boolean) {
        if (!visible || jobs[section] != null) return
        val cursor = if (append) cursors[section] ?: return else null
        val epoch = generation
        update(section) { it.copy(loading = true, failed = false) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = permits.withPermit { load(section, cursor) }
                currentCoroutineContext().ensureActive()
                if (!visible || epoch != generation) return@launch
                check((result.next == null) == (result.nextKey == null))
                val pages = visited.getOrPut(section) { mutableSetOf() }
                if (!append) pages.clear()
                check(result.requestedPage !in pages && result.nextKey != result.requestedPage && result.nextKey !in pages)
                pages += result.requestedPage
                result.next?.let { cursors[section] = it } ?: cursors.remove(section)
                result.title?.let { title -> mutableState.value = mutableState.value.copy(titles = mutableState.value.titles + (section to title)) }
                update(section) { before -> before.copy(
                    items = if (append) mergeLibraryPage(before.items, result.items) else result.items,
                    next = result.nextKey, total = result.total ?: before.total,
                    loaded = true, loading = false, failed = false,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (visible && epoch == generation) update(section) { it.copy(loading = false, failed = true) }
            } finally {
                if (jobs[section] === currentCoroutineContext()[Job]) jobs.remove(section)
            }
        }
        jobs[section] = job
        job.start()
    }

    private fun cancel() {
        generation++
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        mutableState.value = mutableState.value.copy(pages = mutableState.value.pages.mapValues { it.value.copy(loading = false) })
    }

    private inline fun update(section: BrowseInlineSection, transform: (ArtistSectionState) -> ArtistSectionState) {
        mutableState.value = mutableState.value.copy(pages = mutableState.value.pages +
            (section to transform(mutableState.value.pages[section] ?: ArtistSectionState())))
    }
}
