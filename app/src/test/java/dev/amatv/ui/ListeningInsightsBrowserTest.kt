package dev.amatv.ui

import androidx.compose.ui.graphics.Color
import dev.amatv.integration.AccountInsightsApi
import dev.amatv.model.ReplayView
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListeningInsightsBrowserTest {
    @Test fun replayViewReturnKeepsItsLoadedPopulationAndOpaqueCursorButChangingSummaryStartsFresh() {
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fun resource(id: String, type: String) = JSONObject().put("id", id).put("type", type)
            .put("attributes", JSONObject().put("name", id))
        fun wrapper(id: String, view: ReplayView) = JSONObject().put("id", "period.$id").put("type", view.summaryType)
            .put("relationships", JSONObject().put(view.relationship, JSONObject().put("data", JSONArray().put(resource(id, view.resourceType)))))
        fun page(id: String, view: ReplayView, next: String? = null) = JSONObject().put("data", JSONArray().put(wrapper(id, view)))
            .also { if (next != null) it.put("next", next) }
        fun summaries(view: ReplayView? = null) = JSONObject().put("data", JSONArray(listOf(2024, 2025).map { year ->
            JSONObject().put("id", "summary.$year").put("type", "music-summaries")
                .put("attributes", JSONObject().put("year", year).put("period", "year")).also { summary ->
                    if (view != null) summary.put("views", JSONObject().put(view.id,
                        page(if (view == ReplayView.TopSongs) "11" else "21", view,
                            "/v1/me/music-summaries/summary.$year/view/${view.id}?cursor=tail".takeIf { view == ReplayView.TopSongs })))
                }
        }))
        val api = AccountInsightsApi({ "tw" }, { path, _ ->
            calls += path
            when {
                path.contains("cursor=last") -> page("13", ReplayView.TopSongs)
                path.contains("cursor=tail") -> page("12", ReplayView.TopSongs,
                    "/v1/me/music-summaries/summary.2024/view/top-songs?cursor=last")
                path.contains("views=top-songs") -> summaries(ReplayView.TopSongs)
                path.contains("views=top-albums") -> summaries(ReplayView.TopAlbums)
                else -> summaries()
            }
        }, { row -> Track(row.getString("id"), row.getJSONObject("attributes").getString("name"), "", "", 0,
            List(2) { Color(0xFF24242A) }, emptyList()) }, Dispatchers.Unconfined)
        val browser = ListeningInsightsBrowser(scope, api)
        try {
            browser.selectSource(ListeningInsightsSource.Replay, title = "My Replay")
            browser.setVisible(true)
            browser.more()
            val songs = browser.visits.value.single()
            assertEquals(listOf("11", "12"), songs.items.map { it.id })
            val next = songs.next
            val callsBeforeSwitch = calls.size
            browser.selectReplay(checkNotNull(songs.selectedSummary), ReplayView.TopAlbums)
            assertEquals(listOf("21"), browser.visits.value.single().items.map { it.id })
            browser.selectReplay(checkNotNull(songs.selectedSummary), ReplayView.TopSongs)
            val restored = browser.visits.value.single()
            assertSame(songs.items, restored.items)
            assertEquals(next, restored.next)
            assertEquals("My Replay", restored.entryTitle)
            assertEquals(callsBeforeSwitch + 1, calls.size)
            browser.more()
            assertEquals(listOf("11", "12", "13"), browser.visits.value.single().items.map { it.id })
            assertTrue(calls.last().contains("cursor=last"))
            val otherSummary = restored.summaries.single { it.year == 2025 }
            browser.selectReplay(otherSummary, ReplayView.TopSongs)
            assertEquals(listOf("11"), browser.visits.value.single().items.map { it.id })
            browser.selectReplay(checkNotNull(songs.selectedSummary), ReplayView.TopSongs)
            assertEquals(listOf("11"), browser.visits.value.single().items.map { it.id })
            assertEquals(3, calls.count { it.contains("views=top-songs") })
        } finally {
            browser.close()
            scope.cancel()
        }
    }
}
