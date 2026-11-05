package dev.amatv.ui

import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BrowseInlineBrowserTest {
    private fun track(id: String) = Track(id, id, "", "", 0, emptyList(), emptyList())

    @Test fun refreshRetainsFallbackAndReplacesItFromFirstPageAfterLocaleChange() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refreshed = CompletableDeferred<Unit>()
        var refresh = false
        val cursors = mutableListOf<BrowseInlineCursor?>()
        val browser = BrowseInlineBrowser(scope) { section, cursor ->
            if (section == BrowseInlineSection.Genres) {
                cursors += cursor
                if (refresh) refreshed.await()
                val next = BrowseInlineCursor.Genres("/old-more")
                BrowseInlinePage(listOf(track(if (refresh) "localized" else "fallback")), "/first",
                    if (refresh) null else next, if (refresh) null else next.path)
            } else BrowseInlinePage(emptyList(), section.name)
        }
        try {
            browser.invalidate("0:en-US"); browser.setVisible(true)
            refresh = true; browser.invalidate("1:zh-TW")
            val loading = browser.state.value.pages.getValue(BrowseInlineSection.Genres)
            assertEquals("fallback", loading.items.single().id)
            assertTrue(loading.loading)
            assertNull(loading.next)
            refreshed.complete(Unit)
            assertEquals("localized", browser.state.value.pages.getValue(BrowseInlineSection.Genres).items.single().id)
            assertEquals(listOf(null, null), cursors)
            browser.setVisible(false); browser.setVisible(true)
            assertEquals(2, cursors.size)
        } finally { refreshed.complete(Unit); browser.close(); scope.cancel() }
    }

    @Test fun firstPagesHaveTwoReadLimitAndAnIndependentFailureDoesNotEraseOtherRows() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = BrowseInlineSection.entries.associateWith { CompletableDeferred<Unit>() }
        var active = 0
        var maximum = 0
        val started = mutableListOf<BrowseInlineSection>()
        val browser = BrowseInlineBrowser(scope) { section, _ ->
            active++; maximum = maxOf(maximum, active); started += section
            try {
                gates.getValue(section).await()
                if (section == BrowseInlineSection.Albums) error("Independent API failure")
                BrowseInlinePage(listOf(track(section.name)), section.name)
            } finally { active-- }
        }
        browser.setVisible(true)
        assertEquals(listOf(BrowseInlineSection.Songs, BrowseInlineSection.Albums), started)
        gates.getValue(BrowseInlineSection.Songs).complete(Unit)
        assertEquals(BrowseInlineSection.Playlists, started.last())
        gates.getValue(BrowseInlineSection.Albums).complete(Unit)
        BrowseInlineSection.entries.forEach { gates.getValue(it).complete(Unit) }
        assertEquals(2, maximum)
        assertTrue(browser.state.value.pages.getValue(BrowseInlineSection.Albums).failed)
        assertEquals("Songs", browser.state.value.pages.getValue(BrowseInlineSection.Songs).items.single().id)
        assertEquals("Genres", browser.state.value.pages.getValue(BrowseInlineSection.Genres).items.single().id)
        browser.close(); scope.cancel()
    }

    @Test fun aCancelledLateReadCannotReplaceAResumedPage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<Unit>()
        var songsCalls = 0
        val browser = BrowseInlineBrowser(scope) { section, _ ->
            if (section == BrowseInlineSection.Songs) {
                val call = ++songsCalls
                if (call == 1) withContext(NonCancellable) { old.await() }
                BrowseInlinePage(listOf(track(if (call == 1) "stale" else "fresh")), "songs-$call")
            } else BrowseInlinePage(emptyList(), section.name)
        }
        browser.setVisible(true)
        browser.setVisible(false)
        browser.setVisible(true)
        assertEquals("fresh", browser.state.value.pages.getValue(BrowseInlineSection.Songs).items.single().id)
        old.complete(Unit)
        assertEquals("fresh", browser.state.value.pages.getValue(BrowseInlineSection.Songs).items.single().id)
        browser.close(); scope.cancel()
    }

    @Test fun aFailedContinuationKeepsDataAndRetriesTheActualServerCursor() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var nextCalls = 0
        val used = mutableListOf<BrowseInlineCursor?>()
        val next = BrowseInlineCursor.Genres("/real-next")
        val browser = BrowseInlineBrowser(scope) { section, cursor ->
            if (section != BrowseInlineSection.Genres) return@BrowseInlineBrowser BrowseInlinePage(emptyList(), section.name)
            used += cursor
            if (cursor == null) BrowseInlinePage(listOf(track("a")), "first", next, next.path)
            else {
                if (++nextCalls == 1) error("Temporary read failure")
                BrowseInlinePage(listOf(track("a"), track("b")), next.path)
            }
        }
        browser.setVisible(true)
        browser.more(BrowseInlineSection.Genres.shelfId)
        assertEquals(listOf("a"), browser.state.value.pages.getValue(BrowseInlineSection.Genres).items.map { it.id })
        assertTrue(browser.state.value.pages.getValue(BrowseInlineSection.Genres).failed)
        browser.more(BrowseInlineSection.Genres.shelfId)
        assertEquals(listOf(null, next, next), used)
        assertEquals(listOf("a", "b"), browser.state.value.pages.getValue(BrowseInlineSection.Genres).items.map { it.id })
        assertNull(browser.state.value.pages.getValue(BrowseInlineSection.Genres).next)
        browser.setVisible(false); browser.setVisible(true)
        assertEquals(2, nextCalls)
        browser.close(); scope.cancel()
    }
}
