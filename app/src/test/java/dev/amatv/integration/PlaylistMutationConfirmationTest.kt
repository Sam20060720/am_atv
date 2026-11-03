package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistMutationConfirmationTest {
    private fun song(id: String = "123", library: Boolean = false) = Track(
        "apple:$id", "Song", "Artist", "Album", 1, emptyList(), emptyList(),
        catalogId = if (library) null else id, catalogKind = CatalogKind.Song,
        resourceId = id, resourceType = if (library) "library-songs" else "songs",
    )
    private val playlist = Track("apple:p.test", "Playlist", "", "", 0, emptyList(), emptyList(),
        catalogKind = CatalogKind.Playlist, resourceId = "p.test", resourceType = "library-playlists")
    private fun entry(id: String, catalogId: String? = null): String =
        """{"id":"$id","type":"library-songs","relationships":{"catalog":{"data":[${catalogId?.let { """{"id":"$it","type":"songs"}""" }.orEmpty()}]}}}"""
    private fun page(vararg items: String) = JSONObject("""{"data":[${items.joinToString(",")}]}""")
    private fun created(id: String = "p.new", type: String = "library-playlists", name: String = "New") =
        JSONObject("""{"data":[{"id":"$id","type":"$type","attributes":{"name":"$name","description":{"standard":"Description"}}}]}""")

    @Test fun creationUsesReturnedIdAndConfirmsNameDescriptionAndRepeatedTracksWithoutScanningLibrary() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            calls += method to path
            when {
                method == "POST" -> created()
                path == "/v1/me/library/playlists/p.new" -> created()
                path == "/v1/me/library/playlists/p.new/parent" ->
                    created("p.playlistsroot", "library-playlist-folders")
                path == "/v1/me/library/playlists/p.new/tracks?limit=100&include=catalog" ->
                    page(entry("i.one", "123"), entry("i.one", "123"))
                else -> error("Unexpected request: $method $path")
            }
        }
        val pending = actions.preparePlaylistCreation(" New ", "Description", listOf(song(), song()))
        assertTrue(calls.isEmpty())
        val accepted = actions.submitPlaylistCreation(pending)
        assertEquals("p.new", actions.confirmPlaylistCreation(pending.copy(created = accepted))?.id)
        assertEquals(1, calls.count { it.first == "POST" })
        assertEquals(listOf(
            "POST" to "/v1/me/library/playlists",
            "GET" to "/v1/me/library/playlists/p.new",
            "GET" to "/v1/me/library/playlists/p.new/parent",
            "GET" to "/v1/me/library/playlists/p.new/tracks?limit=100&include=catalog",
        ), calls)
    }

    @Test fun acceptedCreationRemainsPendingUntilItsExactTracksAppearAndRefreshNeverPostsAgain() = runBlocking {
        var visible = false
        var posts = 0
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            when {
                method == "POST" -> { posts++; created() }
                path.endsWith("/p.new") -> created()
                path.endsWith("/p.new/parent") -> created("p.playlistsroot", "library-playlist-folders")
                path.contains("/p.new/tracks") -> if (visible) page(entry("i.one", "123")) else page()
                else -> error(path)
            }
        }
        val pending = actions.preparePlaylistCreation("New", tracks = listOf(song()))
        val known = pending.copy(created = actions.submitPlaylistCreation(pending))
        assertNull(actions.confirmPlaylistCreation(known))
        visible = true
        assertNotNull(actions.confirmPlaylistCreation(known))
        assertEquals(1, posts)
    }

    @Test fun missingCreationResponseIdIsNeverGuessedFromASameNamedPlaylistOrResubmitted() = runBlocking {
        val calls = mutableListOf<String>()
        val actions = CatalogActions({ "tw" }) { _, method, _ ->
            calls += method
            throw IOException("Response lost after POST")
        }
        val pending = actions.preparePlaylistCreation("New")
        assertTrue(runCatching { actions.submitPlaylistCreation(pending) }.exceptionOrNull() is IOException)
        assertNull(actions.confirmPlaylistCreation(pending))
        assertNull(actions.confirmPlaylistCreation(pending))
        assertEquals(listOf("POST"), calls)
    }

    @Test fun folderReadbackChecksExactIdentityNameAndParentRatherThanPostAcceptance() = runBlocking {
        var correctParent = false
        var posts = 0
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            when {
                method == "POST" -> { posts++; created("p.folder", "library-playlist-folders") }
                path.endsWith("/p.folder/parent") -> created(if (correctParent) "p.parent" else "p.other", "library-playlist-folders")
                path.endsWith("/p.folder") -> created("p.folder", "library-playlist-folders")
                else -> error(path)
            }
        }
        val pending = actions.preparePlaylistCreation("New", parentFolderId = "p.parent", folder = true)
        val known = pending.copy(created = actions.submitPlaylistCreation(pending))
        assertNull(actions.confirmPlaylistCreation(known))
        correctParent = true
        assertEquals("p.folder", actions.confirmPlaylistCreation(known)?.id)
        assertEquals(1, posts)
    }

    @Test fun wrongResourceOrNameCannotConfirmCreatedPlaylist() = runBlocking {
        for (body in listOf(created("p.other"), created(name = "Other"), created(type = "library-playlist-folders"))) {
            val actions = CatalogActions({ "tw" }) { _, _, _ -> body }
            val pending = actions.preparePlaylistCreation("New").copy(created = CreatedLibraryResource("p.new", "library-playlists", "New"))
            assertNull(actions.confirmPlaylistCreation(pending))
        }
    }

    @Test fun timedOutAppendIsRecoveredByOrderedOccurrenceReadbackWithoutAnotherPost() = runBlocking {
        var submitted = false
        var posts = 0
        val paths = mutableListOf<String>()
        val actions = CatalogActions({ "tw" }) { path, method, _ ->
            paths += path
            if (method == "POST") {
                posts++
                submitted = true
                throw IOException("Response lost")
            }
            if ("offset=2" in path) {
                if (submitted) page(entry("i.one", "123"), entry("i.two", "456"), entry("i.one", "123")) else page()
            } else JSONObject("""{"data":[${entry("i.one", "123")},${entry("i.one", "123")}],"next":"/v1/me/library/playlists/p.test/tracks?offset=2"}""")
        }
        val pending = actions.preparePlaylistAppend(playlist, listOf(song(), song("456"), song()))
        assertEquals(2, pending.previousTracks.size)
        assertTrue(runCatching { actions.submitPlaylistAppend(pending) }.exceptionOrNull() is IOException)
        assertTrue(actions.confirmPlaylistAppend(pending))
        assertTrue(actions.confirmPlaylistAppend(pending))
        assertEquals(1, posts)
        assertTrue(paths.filter { "offset=2" in it }.all { "include=catalog" in it })
    }

    @Test fun appendNeedsAdditionalOccurrencesAndTheirOriginalOrderAndKeepsExistingPrefix() = runBlocking {
        var observed = listOf(entry("i.one", "123"))
        val actions = CatalogActions({ "tw" }) { _, _, _ -> page(*observed.toTypedArray()) }
        val pending = actions.preparePlaylistAppend(playlist, listOf(song(), song("456"), song()))
        assertFalse(actions.confirmPlaylistAppend(pending))
        observed = listOf(entry("i.one", "123"), entry("i.one", "123"), entry("i.one", "123"), entry("i.two", "456"))
        assertFalse(actions.confirmPlaylistAppend(pending))
        observed = listOf(entry("i.other", "123"), entry("i.one", "123"), entry("i.two", "456"), entry("i.one", "123"))
        assertFalse(actions.confirmPlaylistAppend(pending))
        observed = listOf(entry("i.one", "123"), entry("i.one", "123"), entry("i.two", "456"), entry("i.one", "123"), entry("i.later", "789"))
        assertTrue(actions.confirmPlaylistAppend(pending))
    }

    @Test fun libraryAppendMatchesItsOriginalLibraryIdentityRatherThanAnotherEntryWithTheSameCatalogLink() = runBlocking {
        var observed = emptyList<String>()
        val actions = CatalogActions({ "tw" }) { _, _, _ -> page(*observed.toTypedArray()) }
        val pending = actions.preparePlaylistAppend(playlist, listOf(song("i.original", library = true)))
        observed = listOf(entry("i.other", "123"))
        assertFalse(actions.confirmPlaylistAppend(pending))
        observed = listOf(entry("i.original", "123"))
        assertTrue(actions.confirmPlaylistAppend(pending))
    }

    @Test fun explicitPlayParamsCatalogLinkCanConfirmACatalogAppendWhenOptionalRelationshipIsMissing() = runBlocking {
        var submitted = false
        val actions = CatalogActions({ "tw" }) { _, method, _ ->
            if (method == "POST") { submitted = true; JSONObject() }
            else if (!submitted) page()
            else page("""{"id":"i.saved","type":"library-songs","attributes":{"playParams":{"catalogId":"123"}}}""")
        }
        val pending = actions.preparePlaylistAppend(playlist, listOf(song()))
        actions.submitPlaylistAppend(pending)
        assertTrue(actions.confirmPlaylistAppend(pending))
    }

    @Test fun malformedOrRepeatingPaginationCannotProvideFalseConfirmation() = runBlocking {
        var calls = 0
        val actions = CatalogActions({ "tw" }) { _, _, _ ->
            calls++
            JSONObject("""{"data":[],"next":"/v1/me/library/playlists/p.test/tracks?offset=0"}""")
        }
        assertTrue(runCatching { actions.preparePlaylistAppend(playlist, listOf(song())) }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun cancellationRemainsCancellationAndDoesNotBecomeAnUnconfirmedSuccess() = runBlocking {
        val cancelled = CancellationException("Leave")
        val actions = CatalogActions({ "tw" }) { _, _, _ -> throw cancelled }
        val pending = actions.preparePlaylistCreation("New").copy(created = CreatedLibraryResource("p.new", "library-playlists", "New"))
        assertSame(cancelled, runCatching { actions.confirmPlaylistCreation(pending) }.exceptionOrNull())
    }

    @Test fun libraryConfirmationUsesOnlyExactLibraryRelationshipWithoutAnOptionalRatingRequest() = runBlocking {
        val calls = mutableListOf<String>()
        val actions = CatalogActions({ "tw" }) { path, _, _ ->
            calls += path
            JSONObject("""{"data":[{"id":"123","type":"songs","relationships":{"library":{"data":[{"id":"i.saved","type":"library-songs"}]}}}]}""")
        }
        assertEquals(true, actions.libraryState(song()))
        assertEquals(listOf("/v1/catalog/tw/songs/123?include=library"), calls)
    }
}
