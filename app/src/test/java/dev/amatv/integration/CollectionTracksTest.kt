package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Track
import dev.amatv.model.openAction
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollectionTracksTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
    private fun collection(kind: CatalogKind = CatalogKind.Playlist) = Track(
        "apple:pl.test", "A Radio Named Playlist", "Curator", "", 0, emptyList(), emptyList(),
        catalogId = "pl.test", catalogKind = kind,
    )
    private fun song(id: String) = """{"id":"$id","type":"songs","attributes":{"name":"Song $id","artistName":"A","durationInMillis":180000}}"""

    @Test fun firstPageDoesNotFetchTheRestAndContinuationPreservesRepeatedEntries() = runBlocking {
        val requested = mutableListOf<String>()
        val fetch: suspend (String, Boolean) -> JSONObject = { path, user ->
            assertFalse(user)
            requested += path
            if (requested.size == 1) JSONObject("""{"data":[${(1..100).joinToString(",") { song(it.toString()) }}],"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=100","meta":{"total":102}}""")
            else JSONObject("""{"data":[${song("1")},${song("101")}]}""")
        }
        val first = api.collectionTracksPage(collection(), fetchPage = fetch)
        assertEquals(1, requested.size)
        assertEquals(100, first.items.size)
        assertEquals(102, first.total)
        val second = api.collectionTracksPage(collection(), first.nextPage, fetch)
        val tracks = first.items + second.items
        assertEquals(102, tracks.size)
        assertEquals(listOf("100", "1", "101"), tracks.takeLast(3).map { it.catalogId })
        assertEquals(2, requested.size)
    }

    @Test fun libraryIdentityWinsOverDifferentCatalogPlaybackId() = runBlocking {
        val item = collection().copy(resourceId = "p.own", resourceType = "library-playlists")
        val requested = mutableListOf<String>()
        val tracks = api.collectionTracks(item) { path, user ->
            assertTrue(user)
            requested += path
            JSONObject("""{"data":[{"id":"i.library","type":"library-songs",
              "attributes":{"name":"Owned","artistName":"A","playParams":{"catalogId":"201"}},
              "relationships":{"catalog":{"data":[${song("201")}]}}}]}""")
        }
        assertTrue(requested.single().startsWith("/v1/me/library/playlists/p.own/tracks?"))
        assertEquals(listOf("201"), tracks.map { it.catalogId })
        assertEquals("i.library", tracks.single().resourceId)
        assertEquals("library-songs", tracks.single().resourceType)
    }

    @Test fun libraryAlbumUsesLibraryRelationshipEndpoint() {
        val request = collection(CatalogKind.Album).copy(
            catalogId = "123", resourceId = "l.own", resourceType = "library-albums",
        ).collectionRequest("tw")
        assertEquals("/v1/me/library/albums/l.own/tracks?limit=100&include=catalog", request.firstPage)
        assertEquals("/v1/me/library/albums/l.own/tracks?offset=100&include=catalog",
            request.nextPage("/v1/me/library/albums/l.own/tracks?offset=100"))
    }

    @Test fun catalogPlaylistUsesDocumentedPageLimitAndPreservesServerCursor() {
        val request = collection().collectionRequest("tw")
        assertEquals("/v1/catalog/tw/playlists/pl.test/tracks?limit=300", request.firstPage)
        assertEquals("/v1/catalog/tw/playlists/pl.test/tracks?offset=300&cursor=a%2Fb&l=zh-Hant&limit=300",
            request.nextPage("/v1/catalog/tw/playlists/pl.test/tracks?offset=300&cursor=a%2Fb&l=zh-Hant", retainPageSize = true))
        assertEquals("/v1/catalog/tw/playlists/pl.test/tracks?offset=300&limit=50",
            request.nextPage("/v1/catalog/tw/playlists/pl.test/tracks?offset=300&limit=50", retainPageSize = true))
        val library = collection().copy(resourceId = "p.own", resourceType = "library-playlists").collectionRequest("tw")
        assertEquals("/v1/me/library/playlists/p.own/tracks?limit=100&include=catalog", library.firstPage)
        assertEquals("/v1/me/library/playlists/p.own/tracks?offset=100&limit=100&include=catalog",
            library.nextPage("/v1/me/library/playlists/p.own/tracks?offset=100", retainPageSize = true))
    }

    @Test fun catalogContinuationReusesPageLimitWithoutChangingOffsetsOrOccurrences() = runBlocking {
        val paths = mutableListOf<String>()
        val tracks = api.collectionTracks(collection()) { path, _ ->
            paths += path
            when (paths.size) {
                1 -> JSONObject("""{"data":[${song("1")},${song("1")}],"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=2&cursor=a%2Bb"}""")
                else -> JSONObject("""{"data":[${song("1")},${song("2")}] }""")
            }
        }
        assertEquals(listOf("/v1/catalog/tw/playlists/pl.test/tracks?limit=300",
            "/v1/catalog/tw/playlists/pl.test/tracks?offset=2&cursor=a%2Bb&limit=300"), paths)
        assertEquals(listOf("1", "1", "1", "2"), tracks.map { it.catalogId })
    }

    @Test fun relationshipMetadataKeepsRawVideoOffsetsAndRepeatedSongOccurrences() = runBlocking {
        val page = api.collectionTracksPage(collection(), fetchPage = { _, _ ->
            JSONObject("""{"data":[${song("1")},{"id":"mv","type":"music-videos"},${song("1")}],"meta":{"total":3}}""")
        })
        assertEquals(3, page.rawItemCount)
        assertEquals(listOf(0, 2), page.itemRawOffsets)
        assertEquals(listOf("1", "1"), page.items.map { it.catalogId })
    }

    @Test fun shuffleSamplingRejectsVideoRawPositionsAndKeepsTheChosenDuplicateIndex() = runBlocking {
        val raw = listOf(song("1"), """{"id":"mv","type":"music-videos"}""", song("1"), song("2"))
        val draws = ArrayDeque(listOf(1, 2))
        val offsets = mutableListOf<Int>()
        val sample = api.sampleCollectionShuffleSong(collection(), 4, { path, _ ->
            assertTrue(path.contains("limit=1"))
            val index = java.net.URI(path).rawQuery.split('&').first { it.startsWith("offset=") }.substringAfter('=').toInt()
            offsets += index
            JSONObject().put("data", org.json.JSONArray("[${raw[index]}]")).put("meta", JSONObject().put("total", 4))
                .apply { if (index < 3) put("next", "/v1/catalog/tw/playlists/pl.test/tracks?offset=${index + 1}&limit=1") }
        }, randomIndex = { draws.removeFirst() })
        assertEquals(listOf(1, 2), offsets)
        assertEquals(2, sample?.rawIndex)
        assertEquals("1", sample?.track?.catalogId)
        assertEquals(4, sample?.total)
    }

    @Test fun shuffleSamplingFallsBackIfTotalOrReturnedOffsetCannotBeVerified() = runBlocking {
        for (root in listOf(
            """{"data":[${song("1")}],"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=3"}""",
            """{"data":[${song("1")}],"meta":{"total":5},"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=3"}""",
            """{"data":[${song("1")}],"meta":{"total":4},"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=1"}""",
        )) {
            var calls = 0
            val fallback = api.sampleCollectionShuffleSong(collection(), 4, { _, _ -> calls++; JSONObject(root) }, randomIndex = { 2 })
            assertNull(fallback?.track)
            assertEquals(2, fallback?.rawIndex)
            assertEquals(1, calls)
        }
    }

    @Test fun relinkedLibrarySongUsesCurrentCatalogInsteadOfRetiredReportingId() = runBlocking {
        val tracks = api.collectionTracks(collection().copy(
            resourceId = "p.own", resourceType = "library-playlists",
        )) { _, _ ->
            JSONObject("""{"data":[{"id":"i.queen","type":"library-songs",
              "attributes":{"name":"君はQueen","artistName":"Punipunidenki",
                "playParams":{"id":"i.queen","catalogId":"1465074689","reportingId":"1465074689"}},
              "relationships":{"catalog":{"data":[${song("6790045614")}]}}}]}""")
        }
        assertEquals("6790045614", tracks.single().catalogId)
        assertEquals("i.queen", tracks.single().resourceId)
        assertEquals("library-songs", tracks.single().resourceType)
    }

    @Test fun catalogSongNeverUsesItsAnalyticsReportingIdAsPlaybackId() {
        assertEquals("6790045614", catalogPlaybackId("6790045614", "songs",
            JSONObject("""{"id":"6790045614","reportingId":"1465074689","globalId":"retired"}"""), null))
        assertEquals("i.uploaded", catalogPlaybackId("i.uploaded", "library-songs",
            JSONObject("""{"id":"i.uploaded","reportingId":"1465074689"}"""), null))
    }

    @Test fun onlyDirectPlayableSongsEnterCollectionQueue() = runBlocking {
        val tracks = api.collectionTracks(collection()) { _, _ ->
            JSONObject("""{"data":[${song("1")},
              {"id":"ra.1","type":"stations","attributes":{"name":"Station"}},
              {"id":"pl.related","type":"playlists","attributes":{"name":"Related"},
                "relationships":{"tracks":{"data":[${song("2")}]}}},
              {"id":"mv.1","type":"music-videos","attributes":{"name":"Video"}}]}""")
        }
        assertEquals(listOf("1"), tracks.map { it.catalogId })
    }

    @Test fun stationAndPlaylistRouteByTypeRegardlessOfTitle() {
        assertEquals(CatalogOpenAction.Collection, collection().openAction)
        val station = collection(CatalogKind.Station).copy(title = "My Playlist")
        assertEquals(CatalogOpenAction.Station, station.openAction)
        assertThrows(IllegalStateException::class.java) { station.collectionRequest("tw") }
    }

    @Test fun refusesPaginationOutsideOriginalRelationship() = runBlocking {
        for (next in listOf("https://evil.test/tracks", "/v1/catalog/tw/stations/ra.1/next-tracks")) {
            var calls = 0
            try {
                api.collectionTracks(collection()) { _, _ ->
                    calls++
                    JSONObject().put("data", org.json.JSONArray()).put("next", next)
                }
                fail("Unsafe next page must be rejected")
            } catch (_: IllegalStateException) { assertEquals(1, calls) }
        }
    }

    @Test fun cyclicPaginationFailsRatherThanTruncatingSilently() = runBlocking {
        var calls = 0
        try {
            api.collectionTracks(collection()) { _, _ ->
                calls++
                JSONObject("""{"data":[],"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=100"}""")
            }
            fail("Repeated page must not be accepted")
        } catch (_: IllegalStateException) { assertEquals(2, calls) }
    }
}
