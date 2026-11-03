package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.Track
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogActionsTest {
    private fun item(id: String, kind: CatalogKind, type: String, catalogId: String? = null) = Track(
        "apple:${catalogId ?: id}", "Name", "Artist", "", 1, emptyList(), emptyList(),
        catalogId = catalogId, catalogKind = kind, resourceId = id, resourceType = type,
    )

    @Test fun favoritesUseTheVerifiedCatalogLinkWhileRatingsKeepLibraryIdentity() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val actions = CatalogActions({ "tw" }) { path, method, body -> calls += Triple(path, method, body); JSONObject() }
        val source = item("i.saved", CatalogKind.Song, "library-songs", "123")
        actions.addToFavorites(listOf(source))
        actions.setRating(source, PersonalRating.Dislike)
        actions.deleteRating(source)
        assertEquals("/v1/me/favorites?ids%5Bsongs%5D=123", calls[0].first)
        assertEquals("POST", calls[0].second)
        assertEquals("/v1/me/ratings/library-songs/i.saved", calls[1].first)
        assertEquals("PUT", calls[1].second)
        assertEquals(-1, JSONObject(calls[1].third!!).getJSONObject("attributes").getInt("value"))
        assertEquals("DELETE", calls[2].second)
        assertFalse(actions.availableActions(source).canAddToLibrary)
        assertTrue(actions.availableActions(source).canFavorite)
        assertFalse(actions.availableActions(source.copy(catalogId = null)).canFavorite)
        assertEquals(CatalogItemActions(false, false, false, false),
            actions.availableActions(item("20", CatalogKind.Page, "genres")))
        val unknown = actions.readState(source)
        assertNull(unknown.inFavorites)
        assertNull(unknown.inLibrary)
    }

    @Test fun playlistAppendKeepsOriginalTypesAndRepeatedUploadedEntries() = runBlocking {
        var payload: JSONObject? = null
        var requests = 0
        val actions = CatalogActions({ "tw" }) { path, method, body ->
            requests++
            assertEquals("/v1/me/library/playlists/p.owned/tracks", path)
            assertEquals("POST", method)
            payload = JSONObject(body!!)
            JSONObject()
        }
        val playlist = item("p.owned", CatalogKind.Playlist, "library-playlists")
        val uploaded = item("i.uploaded", CatalogKind.Song, "library-songs")
        val catalog = item("123", CatalogKind.Song, "songs", "123")
        actions.appendPlaylistTracks(playlist, listOf(uploaded, catalog, uploaded))
        val data = payload!!.getJSONArray("data")
        assertEquals(3, data.length())
        assertEquals("i.uploaded", data.getJSONObject(0).getString("id"))
        assertEquals("library-songs", data.getJSONObject(0).getString("type"))
        assertEquals("songs", data.getJSONObject(1).getString("type"))
        assertEquals("i.uploaded", data.getJSONObject(2).getString("id"))
        assertTrue(actions.availableActions(uploaded).canAppendToPlaylist)
        assertTrue(runCatching { actions.appendPlaylistTracks(playlist,
            listOf(item("999", CatalogKind.Album, "albums"))) }.isFailure)
        assertEquals(1, requests)
    }

    @Test fun playlistAndFolderCreationUseStructuredBodiesAndActualReturnedIds() = runBlocking {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val actions = CatalogActions({ "tw" }) { path, method, body ->
            assertEquals("POST", method)
            calls += path to JSONObject(body!!)
            if (path.endsWith("playlist-folders")) JSONObject("""{"data":[{"id":"f.created","type":"library-playlist-folders"}]}""")
            else JSONObject("""{"data":[{"id":"p.created","type":"library-playlists"}]}""")
        }
        val folder = actions.createPlaylistFolder("Folder")
        val playlist = actions.createPlaylist("Playlist", "Description",
            listOf(item("123", CatalogKind.Song, "songs")), folder.id)
        assertEquals("p.created", playlist.id)
        assertEquals("f.created", calls[1].second.getJSONObject("relationships").getJSONObject("parent")
            .getJSONArray("data").getJSONObject(0).getString("id"))
        assertEquals("Description", calls[1].second.getJSONObject("attributes").getString("description"))
        assertFalse(calls[1].second.getJSONObject("attributes").has("isPublic"))
        assertTrue(runCatching { actions.createPlaylistFolder("Unsafe", "../other") }.isFailure)
        assertEquals(2, calls.size)
    }

    @Test fun catalogOwnedPlaylistManagementUsesOnlyItsExplicitLibraryMappingAndPermissions() = runBlocking {
        for ((libraryAttributes, expectedEditable) in listOf(
            """"attributes":{"canEdit":true},""" to true,
            "" to null,
        )) {
            val paths = mutableListOf<String>()
            val actions = CatalogActions({ "tw" }) { path, _, _ ->
                paths += path
                if (path.startsWith("/v1/me/ratings/")) JSONObject()
                else JSONObject("""{"data":[{"id":"pl.shared","type":"playlists","attributes":{"canEdit":false},
                  "relationships":{"library":{"data":[{$libraryAttributes"id":"p.owned","type":"library-playlists"}]}}}]}""")
            }
            val state = actions.readState(item("pl.shared", CatalogKind.Playlist, "playlists"))
            assertEquals(CatalogResourceRef("p.owned", "library-playlists"), state.libraryResource)
            assertEquals(true, state.inLibrary)
            assertEquals(expectedEditable, state.canEditPlaylist)
            assertTrue(paths.contains("/v1/catalog/tw/playlists/pl.shared?include=library&extend=inFavorites"))
            assertFalse(paths.any { it == "/v1/me/library/playlists" })
        }
    }

    @Test fun malformedOrAmbiguousLibraryMappingCannotChooseAPlaylistForManagement() = runBlocking {
        for (mapping in listOf(
            """{"id":"p.one","type":"library-playlists"},{"id":"p.two","type":"library-playlists"}""",
            """{"id":"i.song","type":"library-songs"}""",
            """{"id":"../other","type":"library-playlists"}""",
            """{"id":"p.one","type":"library-playlists"},{"id":"../other","type":"library-playlists"}""",
            """{"id":"p.one","type":"library-playlists"},null""",
        )) {
            val actions = CatalogActions({ "tw" }) { path, method, _ ->
                assertEquals("GET", method)
                if (path.startsWith("/v1/me/ratings/")) JSONObject()
                else JSONObject("""{"data":[{"id":"pl.shared","type":"playlists","relationships":{"library":{"data":[$mapping]}}}]}""")
            }
            val source = item("pl.shared", CatalogKind.Playlist, "playlists")
            val state = actions.readState(source)
            assertNull(state.libraryResource)
            assertNull(state.inLibrary)
            assertNull(actions.libraryState(source))
            assertEquals(listOf(source), actions.libraryItemsAwaitingConfirmation(listOf(source)))
        }
    }

    @Test fun libraryMembershipDistinguishesMissingEmptyAndUniqueValidRelationships() = runBlocking {
        val mapping = """{"id":"i.saved","type":"library-songs"}"""
        val cases: List<Pair<String?, Boolean?>> = listOf(
            null to null,
            "" to false,
            mapping to true,
            "$mapping,$mapping" to true,
            """{"id":"123","type":"library-songs"}""" to null,
        )
        for ((data, expected) in cases) {
            val relationship = data?.let { ""","relationships":{"library":{"data":[$it]}}""" }.orEmpty()
            val actions = CatalogActions({ "tw" }) { path, method, _ ->
                assertEquals("GET", method)
                if (path.startsWith("/v1/me/ratings/")) JSONObject()
                else JSONObject("""{"data":[{"id":"123","type":"songs"$relationship}]}""")
            }
            val source = item("123", CatalogKind.Song, "songs")
            assertEquals(expected, actions.readState(source).inLibrary)
            assertEquals(expected, actions.libraryState(source))
        }
    }

    @Test fun invalidLibraryReadbackStaysPendingUntilAValidMappingAppearsWithoutAnotherPost() = runBlocking {
        var posts = 0
        var mapping = """{"id":"p.other","type":"library-playlists"}"""
        val actions = CatalogActions({ "tw" }) { _, method, _ ->
            if (method == "POST") { posts++; JSONObject() }
            else JSONObject("""{"data":[{"id":"123","type":"songs","relationships":{"library":{"data":[$mapping]}}}]}""")
        }
        val source = item("123", CatalogKind.Song, "songs")
        val result = actions.addEligibleItemsToLibraryAndConfirm(listOf(source))
        assertEquals(listOf(source), result.pendingItems)
        assertFalse(result.retryAllowed)
        assertEquals(listOf(source), actions.libraryItemsAwaitingConfirmation(result.pendingItems))
        mapping = """{"id":"i.saved","type":"library-songs"}"""
        assertTrue(actions.libraryItemsAwaitingConfirmation(result.pendingItems).isEmpty())
        assertEquals(1, posts)
    }

    @Test fun createdPlaylistsAndFoldersWaitForTheirExactParentAndRecoverByReadingOnly() = runBlocking {
        for (folder in listOf(false, true)) {
            val type = if (folder) "library-playlist-folders" else "library-playlists"
            val base = "/v1/me/library/${type.removePrefix("library-")}"
            var posts = 0
            var parentData = """{"id":"p.other","type":"library-playlist-folders"}"""
            var parentFailure = false
            var trackReads = 0
            val resource = JSONObject("""{"data":[{"id":"p.created","type":"$type","attributes":{"name":"New"}}]}""")
            val actions = CatalogActions({ "tw" }) { path, method, _ ->
                when {
                    method == "POST" -> { assertEquals(base, path); posts++; resource }
                    path == "$base/p.created" -> resource
                    path == "$base/p.created/parent" -> {
                        if (parentFailure) throw IOException("Parent read unavailable")
                        JSONObject("""{"data":[$parentData]}""")
                    }
                    path.startsWith("$base/p.created/tracks?") -> { trackReads++; JSONObject("""{"data":[]}""") }
                    else -> error("Unexpected request: $method $path")
                }
            }
            val prepared = actions.preparePlaylistCreation("New", parentFolderId = "p.target", folder = folder)
            val pending = prepared.copy(created = actions.submitPlaylistCreation(prepared))
            assertNull(actions.confirmPlaylistCreation(pending))
            parentData = """{"id":"p.target","type":"library-playlists"}"""
            assertNull(actions.confirmPlaylistCreation(pending))
            parentData = ""
            assertNull(actions.confirmPlaylistCreation(pending))
            parentFailure = true
            assertTrue(runCatching { actions.confirmPlaylistCreation(pending) }.exceptionOrNull() is IOException)
            parentFailure = false
            parentData = """{"id":"p.target","type":"library-playlist-folders"}"""
            assertEquals("p.created", actions.confirmPlaylistCreation(pending)?.id)
            assertEquals(if (folder) 0 else 1, trackReads)
            assertEquals(1, posts)
        }
    }

    @Test fun sourceLibraryPlaylistIdentityIsNotReplacedByItsCatalogLink() = runBlocking {
        val actions = CatalogActions({ "tw" }) { path, _, _ ->
            if (path.startsWith("/v1/me/ratings/")) JSONObject()
            else JSONObject("""{"data":[{"id":"p.owned","type":"library-playlists","attributes":{"canEdit":true}}]}""")
        }
        val state = actions.readState(item("p.owned", CatalogKind.Playlist, "library-playlists"))
        assertEquals(CatalogResourceRef("p.owned", "library-playlists"), state.libraryResource)
        assertEquals(true, state.canEditPlaylist)
    }
}
