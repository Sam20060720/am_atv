package dev.amatv.ui

import dev.amatv.integration.AccountInsightsApi
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.catalogFailureReason
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ListeningInsightsSource(val history: ListeningHistorySection? = null) {
    RecentTracks(ListeningHistorySection.RecentTracks),
    RecentResources(ListeningHistorySection.RecentResources),
    HeavyRotation(ListeningHistorySection.HeavyRotation),
    Replay,
    Recommendations,
    RecentStations(ListeningHistorySection.RecentStations),
}

internal enum class InsightsFailure { Authorization, Unavailable, Request }
internal enum class InsightsReplayStatus { NoSummary, NotProvided, SummaryMissing }

internal data class ListeningInsightsVisit(
    val key: Long,
    val source: ListeningInsightsSource,
    val entryTitle: String? = null,
    val recommendation: ListeningRecommendation? = null,
    val items: List<Track> = emptyList(),
    val groups: List<ListeningRecommendation> = emptyList(),
    val next: ListeningPageCursor? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failure: InsightsFailure? = null,
    val summaries: List<ListeningReplaySummary> = emptyList(),
    val selectedSummary: ListeningReplaySummary? = null,
    val replayView: ReplayView = ReplayView.TopSongs,
    val replayStatus: InsightsReplayStatus? = null,
    val unresolvedCount: Int = 0,
)

/** A single visible account visit; no background history polling or offscreen recommendation crawl. */
internal class ListeningInsightsBrowser(private val scope: CoroutineScope, private val api: AccountInsightsApi) {
    private var sequence = 0L
    private val mutableVisits = MutableStateFlow(listOf(newVisit(ListeningInsightsSource.RecentTracks)))
    val visits = mutableVisits.asStateFlow()
    private var visible = false
    private var job: Job? = null
    private val seenPages = mutableMapOf<Long, MutableSet<String>>()
    private data class ReplayViewState(val visit: ListeningInsightsVisit, val pages: Set<String>)
    // At most the three views of the current summary. Immutable item lists are
    // referenced, not copied into another data store.
    private val replayViews = mutableMapOf<ReplayView, ReplayViewState>()
    private var replaySummaryId: String? = null

    fun selectSource(source: ListeningInsightsSource, recommendationId: String? = null, title: String? = null) {
        require(recommendationId == null || (source == ListeningInsightsSource.Recommendations && recommendationId.isNotBlank()))
        val current = mutableVisits.value.last()
        val entryTitle = title?.trim()?.takeIf(String::isNotEmpty)
        if (mutableVisits.value.size == 1 && current.source == source && current.recommendation?.id == recommendationId) {
            if (current.entryTitle != entryTitle) update(current.key) { it.copy(entryTitle = entryTitle) }
            return
        }
        cancelRequest()
        seenPages.clear()
        replayViews.clear(); replaySummaryId = null
        // A Home shelf is an entry point, not a visit through the recommendation
        // root. Back from this group must return straight to its Home heading.
        val group = recommendationId?.let { ListeningRecommendation(it, title = null, reason = null, resourceTypes = emptySet()) }
        mutableVisits.value = listOf(newVisit(source).copy(recommendation = group, entryTitle = entryTitle))
        ensure()
    }

    fun openRecommendation(group: ListeningRecommendation) {
        val visits = mutableVisits.value
        // Server groups can link back to a parent. Avoid an unbounded navigation cycle.
        val existing = visits.indexOfFirst { it.recommendation?.id == group.id }
        cancelRequest()
        mutableVisits.value = if (existing >= 0) visits.take(existing + 1) else visits +
            newVisit(ListeningInsightsSource.Recommendations).copy(recommendation = group)
        ensure()
    }

    fun back(): Boolean {
        if (mutableVisits.value.size < 2) return false
        cancelRequest()
        val key = mutableVisits.value.last().key
        seenPages.remove(key)
        mutableVisits.value = mutableVisits.value.dropLast(1)
        ensure()
        return true
    }

    fun selectReplay(summary: ListeningReplaySummary, view: ReplayView) {
        val visit = mutableVisits.value.last()
        if (summary !in visit.summaries || visit.source != ListeningInsightsSource.Replay) return
        if (visit.selectedSummary == summary && visit.replayView == view && (visit.loaded || visit.loading)) return
        cancelRequest()
        if (replaySummaryId != summary.id) {
            replayViews.clear()
            replaySummaryId = summary.id
        }
        if (visit.selectedSummary?.id == summary.id && (visit.loaded || visit.failure != null)) {
            replayViews[visit.replayView] = ReplayViewState(visit.copy(loading = false), seenPages[visit.key].orEmpty().toSet())
        }
        val retained = replayViews[view]
        if (retained != null) {
            seenPages[visit.key] = retained.pages.toMutableSet()
            update(visit.key) { retained.visit.copy(key = it.key, entryTitle = it.entryTitle,
                summaries = it.summaries, selectedSummary = summary, loading = false) }
        } else {
            seenPages.remove(visit.key)
            update(visit.key) { it.copy(selectedSummary = summary, replayView = view, items = emptyList(), groups = emptyList(),
                next = null, loaded = false, failure = null, replayStatus = null, unresolvedCount = 0) }
        }
        ensure()
    }

    fun ensure() {
        val visit = mutableVisits.value.last()
        if (!visit.loaded && !visit.loading && visit.failure == null) request(false)
    }
    fun more() = request(true)
    fun retry() = request(mutableVisits.value.last().loaded)
    fun reload() {
        cancelRequest()
        val visit = mutableVisits.value.last()
        seenPages.remove(visit.key)
        if (visit.source == ListeningInsightsSource.Replay) { replayViews.clear(); replaySummaryId = null }
        update(visit.key) { it.copy(next = null, loaded = false, failure = null, replayStatus = null,
            summaries = if (it.source == ListeningInsightsSource.Replay) emptyList() else it.summaries,
            selectedSummary = if (it.source == ListeningInsightsSource.Replay) null else it.selectedSummary) }
        request(false)
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) ensure() else cancelRequest()
    }

    fun close() { visible = false; cancelRequest(); seenPages.clear(); replayViews.clear(); replaySummaryId = null }

    private fun request(append: Boolean) {
        val visit = mutableVisits.value.last()
        if (!visible || visit.loading || (append && visit.next == null)) return
        update(visit.key) { it.copy(loading = true, failure = null) }
        job = scope.launch {
            try {
                val next = if (append) visit.next else null
                when {
                    visit.source.history != null -> {
                        val page = api.history(checkNotNull(visit.source.history), next)
                        accept(visit.key, page)
                        update(visit.key) { it.copy(items = if (append) mergeLibraryPage(it.items, page.items) else page.items,
                            next = page.nextPage, loaded = true, loading = false) }
                    }
                    visit.source == ListeningInsightsSource.Recommendations && visit.recommendation == null -> {
                        val page = api.recommendations(next)
                        accept(visit.key, page)
                        update(visit.key) { it.copy(groups = if (append) mergeGroups(it.groups, page.items) else page.items,
                            next = page.nextPage, loaded = true, loading = false) }
                    }
                    visit.recommendation != null -> {
                        val group = if (!visit.loaded && visit.recommendation.title == null) api.recommendation(visit.recommendation.id)
                            else visit.recommendation
                        val page = api.recommendationContents(group.id, next)
                        accept(visit.key, page)
                        val items = page.items.filterIsInstance<RecommendationEntry.Item>().map { it.track }
                        val groups = page.items.filterIsInstance<RecommendationEntry.Group>().map { it.recommendation }
                        val unresolved = page.items.count { it is RecommendationEntry.Unresolved }
                        update(visit.key) { it.copy(recommendation = group,
                            items = if (append) mergeLibraryPage(it.items, items) else items,
                            groups = if (append) mergeGroups(it.groups, groups) else groups,
                            next = page.nextPage, loaded = true, loading = false,
                            unresolvedCount = if (append) it.unresolvedCount + unresolved else unresolved) }
                    }
                    visit.source == ListeningInsightsSource.Replay -> loadReplay(visit, next, append)
                }
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = when (error.catalogFailureReason()) {
                    CatalogFailureReason.Authorization -> InsightsFailure.Authorization
                    CatalogFailureReason.Unavailable -> InsightsFailure.Unavailable
                    else -> InsightsFailure.Request
                }
                update(visit.key) { it.copy(loading = false, failure = failure) }
            } finally { if (job === currentCoroutineContext()[Job]) job = null }
        }
    }

    private suspend fun loadReplay(visit: ListeningInsightsVisit, next: ListeningPageCursor?, append: Boolean) {
        val summaries = if (visit.summaries.isNotEmpty()) visit.summaries else api.replay().summaries
        currentCoroutineContext().ensureActive()
        val summary = visit.selectedSummary ?: summaries.firstOrNull()
        if (summary == null) {
            update(visit.key) { it.copy(summaries = summaries, loaded = true, loading = false, replayStatus = InsightsReplayStatus.NoSummary) }
            return
        }
        update(visit.key) { it.copy(summaries = summaries, selectedSummary = summary) }
        val result = api.replayView(summary, visit.replayView, next)
        currentCoroutineContext().ensureActive()
        when (result) {
            is ListeningReplayView.Page -> {
                accept(visit.key, result.page)
                val items = result.page.items.flatMap { it.items }
                val unresolved = result.page.items.sumOf { it.unresolvedResources.size + if (it.relationshipProvided) 0 else 1 }
                update(visit.key) { it.copy(items = if (append) mergeLibraryPage(it.items, items) else items,
                    next = result.page.nextPage, loaded = true, loading = false,
                    replayStatus = null, unresolvedCount = if (append) it.unresolvedCount + unresolved else unresolved) }
            }
            ListeningReplayView.NotProvided -> update(visit.key) { it.copy(loaded = true, loading = false,
                next = null, replayStatus = InsightsReplayStatus.NotProvided) }
            ListeningReplayView.SummaryMissing -> update(visit.key) { it.copy(loaded = true, loading = false,
                next = null, replayStatus = InsightsReplayStatus.SummaryMissing) }
        }
    }

    private suspend fun <T> accept(key: Long, page: ListeningPage<T>) {
        currentCoroutineContext().ensureActive()
        check(mutableVisits.value.last().key == key)
        val seen = seenPages.getOrPut(key) { mutableSetOf() }
        check(page.requestedPage !in seen && page.nextPage?.key != page.requestedPage && page.nextPage?.key !in seen)
        seen.add(page.requestedPage)
    }

    private fun cancelRequest() {
        job?.cancel(); job = null
        mutableVisits.value = mutableVisits.value.map { it.copy(loading = false) }
    }

    private fun newVisit(source: ListeningInsightsSource) = ListeningInsightsVisit(++sequence, source)
    private fun mergeGroups(old: List<ListeningRecommendation>, fresh: List<ListeningRecommendation>) =
        (old + fresh).distinctBy { it.id }
    private inline fun update(key: Long, change: (ListeningInsightsVisit) -> ListeningInsightsVisit) {
        val visits = mutableVisits.value
        if (visits.last().key == key) mutableVisits.value = visits.dropLast(1) + change(visits.last())
    }
}
