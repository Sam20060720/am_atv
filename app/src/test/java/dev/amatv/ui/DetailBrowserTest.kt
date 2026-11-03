package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.model.ArtistProfile
import dev.amatv.model.CatalogResourceProfile
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DetailBrowserTest {
    private fun item(id: String, kind: CatalogKind) = Track(id, id, "Artist", "", 0,
        emptyList(), emptyList(), catalogId = id, catalogKind = kind,
        resourceId = id, resourceType = if (kind == CatalogKind.Artist) "artists" else "albums")

    @Test fun albumArtistAlbumBackStackRetainsTheOriginalAlbumWithoutReloading() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var albumRequests = 0
        val browser = DetailBrowser(scope,
            loadCollection = { _, _ -> albumRequests++; LibraryPage(emptyList(), null, "album") },
            loadRelatedArtists = { LibraryPage(emptyList(), null, "related") },
            loadProfile = { ArtistProfile(it, it) },
            loadArtistSection = { _, _, _ -> LibraryPage(emptyList(), null, "section") },
            loadResourceProfile = { ref -> CatalogResourceProfile(ref, item(ref.id, CatalogKind.Album), ref.id,
                null, null, null, null, null, emptyList(), emptyList()) },
            loadResourceSection = { _, _, _ -> CatalogResourcePage(emptyList(), null, "section") })
        val album = item("album-a", CatalogKind.Album)
        browser.open(album)
        val original = browser.visits.value.single()
        browser.open(item("artist-a", CatalogKind.Artist))
        browser.open(item("album-b", CatalogKind.Album))
        assertEquals(3, browser.visits.value.size)
        browser.pop()
        assertEquals("artist-a", browser.visits.value.last().source.id)
        browser.pop()
        assertSame(original, browser.visits.value.single())
        assertEquals(album, original.collection?.state?.value?.collection)
        assertEquals(2, albumRequests)
        browser.close()
        assertTrue(browser.visits.value.isEmpty())
        scope.cancel()
    }

    @Test fun hiddenArtistLateProfileCannotReplaceAResumedRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<ArtistProfile>()
        val fresh = CompletableDeferred<ArtistProfile>()
        var calls = 0
        val browser = ArtistBrowser(scope,
            loadProfile = { withContext(NonCancellable) { if (++calls == 1) old.await() else fresh.await() } },
            loadSection = { _, _, _ -> LibraryPage(emptyList(), null, "section") })
        val artist = item("artist", CatalogKind.Artist)
        browser.open(artist)
        browser.setVisible(false)
        browser.setVisible(true)
        old.complete(ArtistProfile(artist.copy(title = "Stale"), artist))
        assertNull(browser.visits.value.single().profile)
        assertTrue(browser.visits.value.single().loading)
        fresh.complete(ArtistProfile(artist.copy(title = "Fresh"), artist))
        assertEquals("Fresh", browser.visits.value.single().profile?.artist?.title)
        browser.close()
        scope.cancel()
    }

    @Test fun openingAnotherArtistDoesNotResumeTheCoveredArtistsCancelledProfile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<String>()
        val browser = DetailBrowser(scope,
            loadCollection = { _, _ -> LibraryPage(emptyList(), null, "album") },
            loadRelatedArtists = { LibraryPage(emptyList(), null, "related") },
            loadProfile = {
                requested += it.id
                if (it.id == "artist-a") awaitCancellation() else ArtistProfile(it, it)
            },
            loadArtistSection = { _, _, _ -> LibraryPage(emptyList(), null, "section") },
            loadResourceProfile = { error("No resource page requested") },
            loadResourceSection = { _, _, _ -> error("No resource section requested") })
        try {
            browser.open(item("artist-a", CatalogKind.Artist))
            browser.open(item("album", CatalogKind.Album))
            browser.open(item("artist-b", CatalogKind.Artist))
            assertEquals(listOf("artist-a", "artist-b"), requested)
            assertEquals("artist-b", browser.artists.visits.value.last().profile?.artist?.id)
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun playerReturnBelongsOnlyToItsMetadataVisitBelowNestedDetails() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = DetailBrowser(scope,
            loadCollection = { _, _ -> LibraryPage(emptyList(), null, "album") },
            loadRelatedArtists = { LibraryPage(emptyList(), null, "related") },
            loadProfile = { ArtistProfile(it, it) },
            loadArtistSection = { _, _, _ -> LibraryPage(emptyList(), null, "artist") },
            loadResourceProfile = { ref -> CatalogResourceProfile(ref, item(ref.id, CatalogKind.Album), ref.id,
                null, null, null, null, null, emptyList(), emptyList()) },
            loadResourceSection = { _, _, _ -> CatalogResourcePage(emptyList(), null, "section") })
        try {
            browser.open(item("existing-browse", CatalogKind.Album))
            val browse = browser.visits.value.single()
            browser.openPage(item("queue-song", CatalogKind.Song).copy(resourceType = "songs"), returnToPlayer = true)
            browser.open(item("nested-album", CatalogKind.Album))
            browser.open(item("nested-artist", CatalogKind.Artist))
            assertFalse(checkNotNull(browser.pop()).returnToPlayer)
            assertFalse(checkNotNull(browser.pop()).returnToPlayer)
            val playerRoot = checkNotNull(browser.pop())
            assertEquals("queue-song", playerRoot.source.id)
            assertTrue(playerRoot.returnToPlayer)
            assertSame(browse, browser.visits.value.single())
            assertFalse(checkNotNull(browser.pop()).returnToPlayer)
            assertNull(browser.pop())
        } finally { browser.close(); scope.cancel() }
    }

    @Test fun deletingAnExactNamespacedSourceRemovesItsBranchWithoutTouchingOtherTabs() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fun make() = DetailBrowser(scope,
            loadCollection = { _, _ -> LibraryPage(emptyList(), null, "album") },
            loadRelatedArtists = { LibraryPage(emptyList(), null, "related") },
            loadProfile = { ArtistProfile(it, it) },
            loadArtistSection = { _, _, _ -> LibraryPage(emptyList(), null, "artist") },
            loadResourceProfile = { ref -> CatalogResourceProfile(ref, item(ref.id, CatalogKind.Album), ref.id,
                null, null, null, null, null, emptyList(), emptyList()) },
            loadResourceSection = { _, _, _ -> CatalogResourcePage(emptyList(), null, "section") })
        val browser = make()
        val otherTab = make()
        val deleted = item("p.deleted", CatalogKind.Playlist).copy(resourceType = "library-playlists")
        try {
            browser.open(item("original", CatalogKind.Album))
            val original = browser.visits.value.single()
            browser.openPage(deleted, returnToPlayer = true)
            browser.open(item("child-album", CatalogKind.Album))
            browser.open(item("child-artist", CatalogKind.Artist))
            otherTab.openPage(deleted.copy(resourceType = "playlists"))
            val other = otherTab.visits.value.single()
            val ref = checkNotNull(dev.amatv.model.CatalogResourceRef.fromTrack(deleted))
            assertTrue(otherTab.removeResource(ref).isEmpty())
            assertSame(other, otherTab.visits.value.single())
            val removed = browser.removeResource(ref)
            assertEquals(listOf("p.deleted", "child-album", "child-artist"), removed.map { it.source.id })
            assertTrue(removed.single { it.source.id == "p.deleted" }.returnToPlayer)
            assertSame(original, browser.visits.value.single())
            assertTrue(browser.artists.visits.value.isEmpty())
            assertTrue(browser.removeResource(ref).isEmpty())
            assertSame(original, browser.visits.value.single())
        } finally { browser.close(); otherTab.close(); scope.cancel() }
    }
}
