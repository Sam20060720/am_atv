package dev.amatv.ui

import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.CatalogRequestException
import dev.amatv.integration.LibraryPage
import dev.amatv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CatalogFailureRecoveryTest {
    private fun item(id: String = "one", type: String = "songs") = Track(
        id, "Title", "Artist", "", 1, emptyList(), emptyList(),
        resourceId = id, resourceType = type, catalogId = id,
        catalogKind = if (type == "artists") CatalogKind.Artist else CatalogKind.Song,
    )
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun denied(reason: CatalogFailureReason) = CatalogRequestException(reason, "Do not show this raw server payload")

    @Test fun searchAppendFailureRetainsRowsAndExactCursorWithoutAutomaticRetry() {
        val scope = scope()
        var calls = 0
        val cursors = mutableListOf<String?>()
        val browser = SearchBrowser(scope, { _, _, cursor, _ ->
            cursors += cursor
            when (++calls) {
                1 -> LibraryPage(listOf(item()), "/same-next", "/first")
                2 -> throw denied(CatalogFailureReason.Authorization)
                else -> LibraryPage(listOf(item("two")), null, "/same-next")
            }
        }, { CatalogSearchSuggestions() })
        try {
            browser.setQuery("music"); browser.submit(); browser.loadMore()
            val failed = browser.state.value
            assertTrue(failed.loaded && failed.failed)
            assertEquals(CatalogFailureReason.Authorization, failed.failureReason)
            assertEquals(listOf("one"), failed.items.map { it.id })
            assertEquals("/same-next", failed.next)
            browser.setVisible(false); browser.setVisible(true); browser.loadMore()
            assertEquals(2, calls)
            browser.retry()
            assertEquals(listOf(null, "/same-next", "/same-next"), cursors)
            assertEquals(listOf("one", "two"), browser.state.value.items.map { it.id })
            assertNull(browser.state.value.failureReason)
            assertFalse(browser.state.value.failed)
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun searchFirstPageUnavailableIsNotMistakenForAnEmptyResultOrRetriedOnVisibility() {
        val scope = scope()
        var calls = 0
        val browser = SearchBrowser(scope, { _, _, _, _ ->
            calls++; throw denied(CatalogFailureReason.Unavailable)
        }, { CatalogSearchSuggestions() })
        try {
            browser.setQuery("missing"); browser.submit()
            assertFalse(browser.state.value.loaded)
            assertTrue(browser.state.value.failed)
            assertEquals(CatalogFailureReason.Unavailable, browser.state.value.failureReason)
            browser.setVisible(false); browser.setVisible(true)
            assertEquals(1, calls)
        } finally { browser.dispose(); scope.cancel() }
    }

    @Test fun artistSectionFailureKeepsExistingProfileAndRowsAndRetriesSameCursor() {
        val scope = scope()
        var sectionCalls = 0
        val cursors = mutableListOf<String?>()
        val artist = item("artist", "artists")
        val browser = ArtistBrowser(scope, { ArtistProfile(it, it) }, { _, _, cursor ->
            cursors += cursor
            when (++sectionCalls) {
                1 -> LibraryPage(listOf(item()), "/more", "/first")
                2 -> throw denied(CatalogFailureReason.Transient)
                else -> LibraryPage(listOf(item("two")), null, "/more")
            }
        })
        try {
            val key = browser.open(artist)
            browser.ensureSection(key, ArtistSection.TopSongs)
            browser.more(key, ArtistSection.TopSongs)
            val failed = browser.visits.value.single()
            assertNotNull(failed.profile)
            assertFalse(failed.failed)
            assertEquals(CatalogFailureReason.Transient, failed.sections.getValue(ArtistSection.TopSongs).failureReason)
            assertEquals(listOf("one"), failed.sections.getValue(ArtistSection.TopSongs).items.map { it.id })
            browser.setVisible(false); browser.setVisible(true)
            assertEquals(2, sectionCalls)
            browser.retrySection(key, ArtistSection.TopSongs)
            val recovered = browser.visits.value.single().sections.getValue(ArtistSection.TopSongs)
            assertEquals(listOf(null, "/more", "/more"), cursors)
            assertEquals(listOf("one", "two"), recovered.items.map { it.id })
            assertNull(recovered.failureReason)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun artistProfileFailureRetainsReasonUntilExplicitRetry() {
        val scope = scope()
        var calls = 0
        val browser = ArtistBrowser(scope, {
            if (++calls == 1) throw denied(CatalogFailureReason.Authorization)
            ArtistProfile(it, it)
        }, { _, _, _ -> error("No eager section reads") })
        try {
            browser.open(item("artist", "artists"))
            assertEquals(CatalogFailureReason.Authorization, browser.visits.value.single().failureReason)
            browser.setVisible(false); browser.setVisible(true)
            assertEquals(1, calls)
            browser.retryProfile()
            assertNotNull(browser.visits.value.single().profile)
            assertFalse(browser.visits.value.single().failed)
            assertNull(browser.visits.value.single().failureReason)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun resourceSectionAppendFailureDoesNotReplaceLoadedRowsOrRefreshTheProfile() {
        val scope = scope()
        val source = item("genre").copy(catalogKind = CatalogKind.Page, resourceType = "genres")
        val ref = checkNotNull(CatalogResourceRef.fromTrack(source))
        val section = CatalogResourceSection("albums", resourceTypes = setOf("albums"))
        var profiles = 0
        var reads = 0
        val cursors = mutableListOf<String?>()
        val browser = CatalogPageBrowser(scope, source, {
            profiles++
            CatalogResourceProfile(ref, source, "Genre", null, null, null, null, null,
                emptyList(), listOf(section))
        }, { _, _, next ->
            cursors += next
            when (++reads) {
                1 -> CatalogResourcePage(listOf(item()), "/next", "/first")
                2 -> throw denied(CatalogFailureReason.Unavailable)
                else -> CatalogResourcePage(listOf(item("two")), null, "/next")
            }
        })
        try {
            browser.ensure(section); browser.more(section)
            val failed = browser.state.value.sections.getValue(section.pageKey())
            assertEquals(CatalogFailureReason.Unavailable, failed.failureReason)
            assertEquals(listOf("one"), failed.items.map { it.id })
            assertTrue(failed.loaded)
            browser.setVisible(false); browser.setVisible(true); browser.ensure(section)
            assertEquals(2, reads)
            assertEquals(1, profiles)
            browser.retry(section)
            val recovered = browser.state.value.sections.getValue(section.pageKey())
            assertEquals(listOf(null, "/next", "/next"), cursors)
            assertEquals(listOf("one", "two"), recovered.items.map { it.id })
            assertNull(recovered.failureReason)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun missingResourceIdentityIsUnavailableWithoutSendingARequest() {
        val scope = scope()
        var requests = 0
        val source = item().copy(catalogKind = CatalogKind.Page, resourceId = null, resourceType = null)
        val browser = CatalogPageBrowser(scope, source, { requests++; error("No valid identity") },
            { _, _, _ -> requests++; error("No profile") })
        try {
            assertEquals(0, requests)
            assertTrue(browser.state.value.failed)
            assertEquals(CatalogFailureReason.Unavailable, browser.state.value.failureReason)
        } finally { browser.close(); scope.cancel() }
    }

}
