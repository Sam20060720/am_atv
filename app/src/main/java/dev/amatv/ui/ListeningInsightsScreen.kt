package dev.amatv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.model.ListeningReplaySummary
import dev.amatv.model.ReplayView
import dev.amatv.model.Track

/** Home's selected collection, with Replay controls only when they belong to that collection. */
@Composable
internal fun ListeningInsightsScreen(
    browser: ListeningInsightsBrowser,
    active: Boolean,
    firstFocus: FocusRequester,
    menuFocus: FocusRequester,
    capabilities: CatalogPresentationCapabilities,
    onBack: () -> Unit,
    onOpen: (Track, List<Track>) -> Unit,
    onSignIn: () -> Unit = {},
) {
    val contentActive = active && LocalFocusTargetsEnabled.current
    val latestActive by rememberUpdatedState(contentActive)
    BackHandler(enabled = contentActive) { if (!browser.back()) onBack() }
    val visits by browser.visits.collectAsState()
    val visit = visits.last()
    val latestVisitKey by rememberUpdatedState(visit.key)
    val entry = LocalContentFocusEntry.current
    val stateHolder = rememberSaveableStateHolder()
    var savedContentKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val contentKey = insightsContentKey(visit)
    val retainedContentKeys = visits.flatMap { parent ->
        if (parent.source == ListeningInsightsSource.Replay && parent.selectedSummary != null)
            ReplayView.entries.map { insightsContentKey(parent.copy(replayView = it)) }
        else listOf(insightsContentKey(parent))
    }.toSet()
    SideEffect { if (contentKey !in savedContentKeys) savedContentKeys += contentKey }
    LaunchedEffect(retainedContentKeys) {
        val obsolete = savedContentKeys - retainedContentKeys
        obsolete.forEach(stateHolder::removeState)
        if (obsolete.isNotEmpty()) savedContentKeys = savedContentKeys - obsolete.toSet()
    }
    var pendingVisitEntry by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(visit.key) {
        pendingVisitEntry = visit.key
        repeat(3) {
            withFrameNanos { }
            if (!latestActive || latestVisitKey != visit.key || pendingVisitEntry != visit.key) return@LaunchedEffect
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) {
                pendingVisitEntry = null
                return@LaunchedEffect
            }
        }
        pendingVisitEntry = null
    }
    val items = remember(visit.items, capabilities) { capabilities.filterTracks(visit.items) }
    val groups = remember(visit.groups, capabilities) { visit.groups.filter { group ->
        group.resourceTypes.isEmpty() || group.resourceTypes.any(capabilities::canOpenResourceType)
    } }
    val backdrop = LocalBrowseBackdropController.current
    LaunchedEffect(visit.key, items.firstOrNull()?.artworkUrl) { items.firstOrNull()?.let { backdrop?.select(it) } }
    val summaryFocus = remember(visit.key) { mutableMapOf<String, FocusRequester>() }
    val viewFocus = remember(visit.key) { ReplayView.entries.associateWith { FocusRequester() } }
    val selectedSummaryFocus = visit.selectedSummary?.let { summaryFocus.retainedFocusRequester(it.id) }
    val selectedViewFocus = viewFocus.getValue(visit.replayView)
    val replay = visit.source == ListeningInsightsSource.Replay && visit.summaries.isNotEmpty()
    val title = visit.entryTitle?.takeIf(String::isNotBlank)
        ?: visit.recommendation?.title?.takeIf(String::isNotBlank)
        ?: stringResource(visit.source.label())
    val cancelEntry = Modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK))
            pendingVisitEntry = null
        false
    }
    Column(Modifier.fillMaxSize().automationId("listening-insights").then(cancelEntry)) {
        Text(title, color = Color.White.copy(alpha = 0.95f), fontSize = 29.sp, lineHeight = 35.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).automationId("insights-title"))
        if (replay) {
            FocusTrackingRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                itemsIndexed(visit.summaries, key = { _, summary -> summary.id }) { index, summary ->
                    val requester = summaryFocus.retainedFocusRequester(summary.id)
                    ArtistTextAction(summaryLabel(summary), { browser.selectReplay(summary, visit.replayView) },
                        Modifier.background(if (summary == visit.selectedSummary) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(18.dp))
                            .focusRequester(requester).automationId("insights-summary-$index")
                            .semantics { selected = summary == visit.selectedSummary }
                            .onFocusChanged { if (it.isFocused) entry?.requester = requester }
                            .focusProperties {
                                if (index == 0) left = menuFocus
                                up = menuFocus
                                down = selectedViewFocus
                            })
                }
            }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ReplayView.entries.forEachIndexed { index, view ->
                    val requester = viewFocus.getValue(view)
                    ArtistTextAction(stringResource(view.label()), { visit.selectedSummary?.let { browser.selectReplay(it, view) } },
                        Modifier.background(if (view == visit.replayView) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(18.dp))
                            .focusRequester(requester).automationId("insights-view-${view.id}")
                            .semantics { selected = view == visit.replayView }
                            .onFocusChanged { if (it.isFocused) entry?.requester = requester }
                            .focusProperties {
                                if (index == 0) left = menuFocus
                                if (index == ReplayView.entries.lastIndex) right = FocusRequester.Cancel
                                up = selectedSummaryFocus ?: menuFocus
                                down = firstFocus
                            })
                }
            }
        }
        visit.recommendation?.reason?.takeIf(String::isNotBlank)?.let { reason ->
            Text(reason, color = Color.White.copy(alpha = 0.56f), fontSize = 13.sp, lineHeight = 19.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        }
        if (visit.replayStatus != null && !visit.loading && visit.failure == null && items.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(visit.message()), color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
                ArtistTextAction(stringResource(R.string.browse_reload), browser::reload,
                    Modifier.focusRequester(firstFocus).automationId("insights-replay-reload")
                        .onFocusChanged { if (it.isFocused) entry?.requester = firstFocus }
                        .focusProperties { left = menuFocus; up = if (replay) selectedViewFocus else menuFocus })
            }
        } else stateHolder.SaveableStateProvider(contentKey) {
            CatalogCollectionBody(contentKey = contentKey, items = items, active = contentActive,
                loaded = visit.loaded, loading = visit.loading, nextPage = visit.next?.key, failed = visit.failure != null,
                firstFocus = firstFocus, menuFocus = menuFocus, onOpen = onOpen, onMore = browser::more,
                onRetry = browser::retry, modifier = Modifier.fillMaxWidth().weight(1f),
                failureReason = when (visit.failure) {
                    InsightsFailure.Authorization -> CatalogFailureReason.Authorization
                    InsightsFailure.Unavailable -> CatalogFailureReason.Unavailable
                    InsightsFailure.Request -> CatalogFailureReason.Transient
                    null -> null
                }, onSignIn = onSignIn, topFocus = selectedViewFocus.takeIf { replay },
                groups = groups, onOpenGroup = browser::openRecommendation)
        }
        if (visit.unresolvedCount > 0) Text(stringResource(R.string.insights_partial),
            color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

private fun insightsContentKey(visit: ListeningInsightsVisit): String =
    if (visit.source == ListeningInsightsSource.Replay)
        "insights:${visit.key}:${visit.selectedSummary?.id.orEmpty()}:${visit.replayView.id}"
    else "insights:${visit.key}"

@Composable
private fun summaryLabel(summary: ListeningReplaySummary): String {
    val period = when (summary.period) {
        "year" -> stringResource(R.string.insights_period_year)
        "month" -> stringResource(R.string.insights_period_month)
        else -> summary.period
    }
    return when {
        summary.year != null && period != null -> stringResource(R.string.insights_period_label, summary.year.toString(), period)
        summary.year != null -> summary.year.toString()
        period != null -> period
        else -> stringResource(R.string.insights_replay)
    }
}

@StringRes
private fun ListeningInsightsVisit.message(): Int = when {
    failure == InsightsFailure.Authorization -> R.string.insights_sign_in
    failure == InsightsFailure.Unavailable -> R.string.insights_unavailable
    failure != null -> R.string.insights_request_failed
    replayStatus == InsightsReplayStatus.NoSummary -> R.string.insights_no_replay
    replayStatus == InsightsReplayStatus.NotProvided -> R.string.insights_replay_view_missing
    replayStatus == InsightsReplayStatus.SummaryMissing -> R.string.insights_replay_summary_missing
    else -> R.string.insights_empty
}

@StringRes
private fun ListeningInsightsSource.label(): Int = when (this) {
    ListeningInsightsSource.RecentTracks -> R.string.insights_recent_tracks
    ListeningInsightsSource.RecentResources -> R.string.insights_recent_resources
    ListeningInsightsSource.HeavyRotation -> R.string.insights_heavy_rotation
    ListeningInsightsSource.Replay -> R.string.insights_replay
    ListeningInsightsSource.Recommendations -> R.string.insights_recommendations
    ListeningInsightsSource.RecentStations -> R.string.insights_recent_stations
}

@StringRes
private fun ReplayView.label(): Int = when (this) {
    ReplayView.TopSongs -> R.string.insights_top_songs
    ReplayView.TopAlbums -> R.string.insights_top_albums
    ReplayView.TopArtists -> R.string.insights_top_artists
}
