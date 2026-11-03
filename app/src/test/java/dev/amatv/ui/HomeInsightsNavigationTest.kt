package dev.amatv.ui

import dev.amatv.integration.AccountInsightsApi
import dev.amatv.model.ListeningRecommendation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class HomeInsightsNavigationTest {
    @Test fun namespacedHomeShelvesOpenTheirActualSourceRatherThanMatchingDisplayText() {
        assertEquals(HomeInsightsLink(ListeningInsightsSource.RecentTracks), homeShelfInsightsLink("recent-tracks:recent-tracks"))
        assertEquals(HomeInsightsLink(ListeningInsightsSource.RecentResources), homeShelfInsightsLink("recent:recent"))
        assertEquals(HomeInsightsLink(ListeningInsightsSource.HeavyRotation), homeShelfInsightsLink("heavy-rotation:heavy-rotation"))
        assertEquals(HomeInsightsLink(ListeningInsightsSource.Recommendations, "group:one"),
            homeShelfInsightsLink("recommendations:recommendation:group:one"))
        assertNull(homeShelfInsightsLink("ytm:recommendation:group:one"))
        assertNull(homeShelfInsightsLink("recommendations:recommendation:"))
        assertNull(homeShelfInsightsLink("Replay"))
        assertNull(homeShelfInsightsLink("top-songs:top-songs"))
    }

    @Test fun directHomeRecommendationReturnsHomeWhileAChildStillReturnsToItsParent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = AccountInsightsApi({ "tw" }, { _, _ -> error("Hidden entry must not request account data") }, { null }, Dispatchers.Unconfined)
        val browser = ListeningInsightsBrowser(scope, api)
        val link = homeShelfInsightsLink("recommendations:recommendation:home-group", "首頁選中的推薦")!!
        browser.selectSource(link.source, link.recommendationId, link.title)
        assertEquals("home-group", browser.visits.value.single().recommendation?.id)
        assertEquals("首頁選中的推薦", browser.visits.value.single().entryTitle)
        assertFalse(browser.back())
        browser.openRecommendation(ListeningRecommendation("child", "Child", null, emptySet()))
        assertTrue(browser.back())
        assertEquals("home-group", browser.visits.value.single().recommendation?.id)
        assertEquals("首頁選中的推薦", browser.visits.value.single().entryTitle)
        assertFalse(browser.back())
        browser.selectSource(ListeningInsightsSource.Replay)
        assertEquals(ListeningInsightsSource.Replay, browser.visits.value.single().source)
        assertNull(browser.visits.value.single().recommendation)
        browser.close()
        scope.cancel()
    }
}
