package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogLibraryManagementTest {
    private val item = Track("apple:p.test", "Playlist", "", "", 0L, emptyList(), emptyList(),
        catalogKind = CatalogKind.Playlist, resourceId = "p.test", resourceType = "library-playlists")
    private fun metadata(name: String = "Playlist") = JSONObject().put("data", JSONArray().put(
        JSONObject().put("id", "p.test").put("type", "library-playlists")
            .put("attributes", JSONObject().put("name", name).put("canEdit", true))))
    private fun page(rows: List<Pair<String, String>>, next: String? = null): JSONObject =
        JSONObject().put("data", JSONArray(rows.map { JSONObject().put("id", it.first).put("type", it.second) }))
            .also { if (next != null) it.put("next", next) }

    @Test fun reorderPreservesRepeatedAndUnknownOccurrencesAcrossPages() = runBlocking {
        var order = listOf("i.a" to "library-songs", "i.b" to "library-songs", "i.a" to "library-songs", "u.c" to "unavailable-future-track")
        var writes = 0
        val api = CatalogLibraryManagement({ "tw" }, { path, method, body ->
            when {
                method == "PUT" -> {
                    writes++
                    val data = JSONObject(body!!).getJSONArray("data")
                    order = (0 until data.length()).map { data.getJSONObject(it).let { row -> row.getString("id") to row.getString("type") } }
                    JSONObject()
                }
                path.endsWith("p.test") -> metadata()
                path.endsWith("offset=2") -> page(order.drop(2))
                else -> page(order.take(2), "/v1/me/library/playlists/p.test/tracks?offset=2")
            }
        })
        val original = api.playlist(item)
        assertTrue(api.reorder(item, original, listOf(2, 0, 3, 1)))
        assertEquals(listOf("i.a", "i.a", "u.c", "i.b"), order.map { it.first })
        assertEquals("unavailable-future-track", order[2].second)
        assertEquals(1, writes)
    }

    @Test fun externalChangePreventsReplacement() = runBlocking {
        var changed = false
        var writes = 0
        val api = CatalogLibraryManagement({ "tw" }, { path, method, _ ->
            if (method != "GET") writes++
            if (path.endsWith("p.test")) metadata()
            else page(if (changed) listOf("i.other" to "library-songs") else listOf("i.a" to "library-songs", "i.b" to "library-songs"))
        })
        val original = api.playlist(item)
        changed = true
        assertTrue(runCatching { api.reorder(item, original, listOf(1, 0)) }.exceptionOrNull() is LibraryPlaylistConflict)
        assertEquals(0, writes)
    }

    @Test fun renameOnlyChangesNameAndReadsTheExactResource() = runBlocking {
        var name = "Playlist"
        val api = CatalogLibraryManagement({ "tw" }, { _, method, body ->
            if (method == "PATCH") {
                val attributes = JSONObject(body!!).getJSONObject("attributes")
                assertEquals(1, attributes.length())
                name = attributes.getString("name")
                JSONObject()
            } else metadata(name)
        })
        assertTrue(api.rename(item, "Renamed"))
    }

    @Test fun incompleteTotalAndForeignContinuationCannotAuthorizeReplacement() = runBlocking {
        for (foreign in listOf(false, true)) {
            val api = CatalogLibraryManagement({ "tw" }, { path, _, _ ->
                if (path.endsWith("p.test")) metadata()
                else if (foreign) page(listOf("i.a" to "library-songs"), "https://other.invalid/v1/me/library/playlists/p.test/tracks?offset=1")
                else page(listOf("i.a" to "library-songs")).put("meta", JSONObject().put("total", 100))
            })
            assertTrue(runCatching { api.playlist(item) }.isFailure)
        }
    }

    @Test fun uncertainWriteIsNotResubmitted() = runBlocking {
        var writes = 0
        val api = CatalogLibraryManagement({ "tw" }, { _, method, _ ->
            if (method == "PATCH") { writes++; throw java.io.IOException("timeout") }
            metadata()
        })
        assertTrue(runCatching { api.rename(item, "New") }.exceptionOrNull() is LibraryManagementUnconfirmed)
        assertEquals(1, writes)
    }

    @Test fun libraryFavoriteUsesCanonicalCatalogRelationAndConfirmsFalse() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val api = CatalogLibraryManagement({ "tw" }, { path, method, _ ->
            calls += path to method
            if (method == "GET") JSONObject("""{"data":[{"id":"pl.catalog","type":"playlists","attributes":{"inFavorites":false}}]}""")
            else JSONObject()
        })
        assertTrue(api.removeFavorite(item.copy(catalogId = "pl.catalog")))
        assertEquals("/v1/me/favorites?ids%5Bplaylists%5D=pl.catalog" to "DELETE", calls.first())
        assertTrue(calls.none { "ratings" in it.first })
    }

    @Test fun omittedFolderEditFlagRemainsUnknownRatherThanBecomingFalse() = runBlocking {
        val folder = item.copy(resourceType = "library-playlist-folders", catalogKind = CatalogKind.Page)
        var name = "Folder"
        val api = CatalogLibraryManagement({ "tw" }, { path, method, body ->
            if (method == "PATCH") {
                assertEquals("/v1/me/library/playlists/p.test", path)
                name = JSONObject(body!!).getJSONObject("attributes").getString("name"); JSONObject()
            } else {
                assertEquals("/v1/me/library/playlist-folders/p.test", path)
                JSONObject().put("data", JSONArray().put(JSONObject().put("id", "p.test")
                .put("type", "library-playlist-folders").put("attributes", JSONObject().put("name", name))))
            }
        })
        assertNull(api.resource(folder).canEdit)
        assertTrue(api.rename(folder, "Renamed"))
    }

    @Test fun deletionWaitsForTombstoneAndOriginalContainerRemovalWithoutRepeatingDelete() = runBlocking {
        var deleted = false
        var stillListed = true
        var writes = 0
        val api = CatalogLibraryManagement({ "tw" }, { path, method, _ ->
            when {
                method == "DELETE" -> { writes++; deleted = true; JSONObject() }
                path.endsWith("/parent") -> JSONObject().put("data", JSONArray().put(
                    JSONObject().put("id", "p.playlistsroot").put("type", "library-playlist-folders")))
                "/children?" in path -> page(if (stillListed) listOf("p.test" to "library-playlists") else emptyList())
                deleted -> JSONObject().put("data", JSONArray().put(JSONObject().put("id", "p.test")
                    .put("type", "library-playlists").put("attributes", JSONObject().put("canEdit", false).put("canDelete", false))))
                else -> metadata()
            }
        })
        val pending = api.prepareDeletion(item)
        api.submitDeletion(pending)
        assertFalse(api.confirmDeletion(pending))
        stillListed = false
        assertTrue(api.confirmDeletion(pending))
        assertEquals(1, writes)
    }

    @Test fun populatedFolderIsNotDeletedByAnEmptyFolderCapability() = runBlocking {
        var writes = 0
        val folder = item.copy(resourceType = "library-playlist-folders", catalogKind = CatalogKind.Page)
        val api = CatalogLibraryManagement({ "tw" }, { path, method, _ ->
            if (method != "GET") writes++
            if ("/children?" in path) page(listOf("p.other" to "library-playlists"))
            else JSONObject().put("data", JSONArray().put(JSONObject().put("id", "p.test")
                .put("type", "library-playlist-folders").put("attributes", JSONObject().put("name", "Folder").put("canDelete", true))))
        })
        assertTrue(runCatching { api.prepareDeletion(folder) }.exceptionOrNull() is LibraryFolderNotEmpty)
        assertEquals(0, writes)
    }

    @Test fun songRemovalUsesTheExactLibraryIdAndUnknownMappingCannotConfirmIt() = runBlocking {
        val song = item.copy(id = "apple:i.song", resourceId = "i.song", resourceType = "library-songs",
            catalogKind = CatalogKind.Song, catalogId = "123")
        var removed = false; var known = true; var writes = 0
        val api = CatalogLibraryManagement({ "tw" }, { path, method, _ ->
            when {
                method == "DELETE" -> { assertEquals("/v1/me/library/songs/i.song", path); writes++; removed = true; JSONObject() }
                path.startsWith("/v1/catalog/") -> {
                    val relation = JSONObject()
                    if (known) relation.put("data", if (removed) JSONArray() else JSONArray().put(JSONObject().put("id", "i.song").put("type", "library-songs")))
                    JSONObject().put("data", JSONArray().put(JSONObject().put("id", "123").put("type", "songs")
                        .put("relationships", JSONObject().put("library", relation))))
                }
                else -> JSONObject().put("data", JSONArray().put(JSONObject().put("id", "i.song").put("type", "library-songs")
                    .put("attributes", JSONObject().put("name", "Song"))))
            }
        })
        val pending = api.prepareDeletion(song)
        api.submitDeletion(pending)
        known = false
        assertFalse(api.confirmDeletion(pending))
        known = true
        assertTrue(api.confirmDeletion(pending))
        assertEquals(1, writes)
    }
}
