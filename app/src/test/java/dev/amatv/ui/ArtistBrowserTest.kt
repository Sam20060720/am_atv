package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.model.ArtistProfile
import dev.amatv.model.ArtistSection
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ArtistBrowserTest {
    private val artist = Track("artist", "Artist", "Artist", "", 0, emptyList(), emptyList(),
        catalogId = "artist", catalogKind = CatalogKind.Artist, resourceId = "artist", resourceType = "artists")

    @Test fun profileAndSectionsAreUsableWhileOptionalMotionIsStillLoading() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val artwork = CompletableDeferred<Track>()
        var sectionReads = 0
        val browser = ArtistBrowser(scope, { ArtistProfile(it, it, biography = "Biography") }, { _, _, _ ->
            sectionReads++
            LibraryPage(emptyList(), null, "latest")
        }, enrichArtwork = { artwork.await() })
        try {
            val key = browser.open(artist)
            assertEquals("Biography", browser.visits.value.single().profile?.biography)
            assertFalse(browser.visits.value.single().loading)
            browser.ensureSection(key, ArtistSection.LatestRelease)
            assertEquals(1, sectionReads)
            artwork.complete(artist.copy(motionArtworkUrl = "https://example.test/motion.m3u8"))
            assertNotNull(browser.visits.value.single().profile?.catalogArtist?.motionArtworkUrl)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun cancelledMotionResumesWithoutReloadingProfileAndCannotPublishStaleAsset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stale = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<Track>()
        var profileReads = 0
        var motionReads = 0
        val browser = ArtistBrowser(scope, { profileReads++; ArtistProfile(it, it) },
            { _, _, _ -> error("No section read") }, enrichArtwork = { source ->
                if (++motionReads == 1) withContext(NonCancellable) {
                    stale.await()
                    source.copy(motionArtworkUrl = "https://example.test/stale.m3u8")
                } else fresh.await()
            })
        try {
            browser.open(artist)
            browser.setVisible(false)
            browser.setVisible(true)
            assertEquals(1, profileReads)
            assertEquals(2, motionReads)
            stale.complete(Unit)
            assertNull(browser.visits.value.single().profile?.catalogArtist?.motionArtworkUrl)
            assertFalse(browser.visits.value.single().failed)
            fresh.complete(artist.copy(motionArtworkUrl = "https://example.test/fresh.m3u8"))
            assertEquals("https://example.test/fresh.m3u8", browser.visits.value.single().profile?.catalogArtist?.motionArtworkUrl)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun cancelledProfileLateFailureDoesNotFailItsReplacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stale = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<ArtistProfile>()
        var calls = 0
        val browser = ArtistBrowser(scope, loadProfile = {
            if (++calls == 1) withContext(NonCancellable) {
                stale.await()
                error("Old request failed after cancellation")
            } else fresh.await()
        }, loadSection = { _, _, _ -> error("No section requested") })
        try {
            browser.open(artist)
            browser.setVisible(false)
            browser.setVisible(true)
            stale.complete(Unit)
            assertTrue(browser.visits.value.single().loading)
            assertFalse(browser.visits.value.single().failed)
            fresh.complete(ArtistProfile(artist, artist))
            assertEquals(artist, browser.visits.value.single().profile?.artist)
            assertFalse(browser.visits.value.single().failed)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun cancelledSectionLateFailureDoesNotPoisonViewportResume() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stale = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<LibraryPage>()
        var calls = 0
        val browser = ArtistBrowser(scope, { ArtistProfile(it, it) }, { _, _, _ ->
            if (++calls == 1) withContext(NonCancellable) {
                stale.await()
                error("Old section failed after cancellation")
            } else fresh.await()
        })
        try {
            val key = browser.open(artist)
            browser.ensureSection(key, ArtistSection.LatestRelease)
            browser.setVisible(false)
            browser.setVisible(true)
            // Returning to the page does not eagerly read offscreen sections.
            assertEquals(1, calls)
            browser.ensureSection(key, ArtistSection.LatestRelease)
            stale.complete(Unit)
            val pending = browser.visits.value.single().sections.getValue(ArtistSection.LatestRelease)
            assertTrue(pending.loading)
            assertFalse(pending.failed)
            fresh.complete(LibraryPage(emptyList(), null, "latest"))
            val result = browser.visits.value.single().sections.getValue(ArtistSection.LatestRelease)
            assertTrue(result.loaded)
            assertFalse(result.failed)
            browser.ensureSection(key, ArtistSection.LatestRelease)
            assertEquals(2, calls)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun realFailureRequiresExplicitRetryAndTerminalEmptyIsNotRetried() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val browser = ArtistBrowser(scope, { ArtistProfile(it, it) }, { _, _, _ ->
            if (++calls == 1) error("Network failed")
            LibraryPage(emptyList(), null, "latest")
        })
        try {
            val key = browser.open(artist)
            browser.ensureSection(key, ArtistSection.LatestRelease)
            assertTrue(browser.visits.value.single().sections.getValue(ArtistSection.LatestRelease).failed)
            browser.ensureSection(key, ArtistSection.LatestRelease)
            assertEquals(1, calls)
            browser.retrySection(key, ArtistSection.LatestRelease)
            assertTrue(browser.visits.value.single().sections.getValue(ArtistSection.LatestRelease).loaded)
            assertFalse(browser.visits.value.single().sections.getValue(ArtistSection.LatestRelease).failed)
            browser.ensureSection(key, ArtistSection.LatestRelease)
            browser.more(key, ArtistSection.LatestRelease)
            assertEquals(2, calls)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun emptyFirstPageRetainsContinuationUntilActualDataOrTerminalPage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cursors = mutableListOf<String?>()
        val browser = ArtistBrowser(scope, { ArtistProfile(it, it) }, { _, _, next ->
            cursors += next
            if (next == null) LibraryPage(emptyList(), "next", "first")
            else LibraryPage(listOf(artist.copy(id = "album")), null, "next")
        })
        try {
            val key = browser.open(artist)
            browser.ensureSection(key, ArtistSection.Albums)
            val first = browser.visits.value.single().sections.getValue(ArtistSection.Albums)
            assertTrue(first.loaded)
            assertTrue(first.items.isEmpty())
            assertEquals("next", first.next)
            browser.more(key, ArtistSection.Albums)
            assertEquals(listOf(null, "next"), cursors)
            assertEquals("album", browser.visits.value.single().sections.getValue(ArtistSection.Albums).items.single().id)
            browser.more(key, ArtistSection.Albums)
            assertEquals(2, cursors.size)
        } finally { browser.close(); scope.cancel() }
    }
}
