package dev.amatv.integration

import dev.amatv.model.ArtistSection
import dev.amatv.model.CatalogKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryPagingTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    private fun song(index: Int, catalogId: String = index.toString()): String = """
        {"id":"i.$index","type":"library-songs","attributes":{
          "name":"Song $index","artistName":"Artist","durationInMillis":180000,
          "playParams":{"catalogId":"$catalogId"}}}
    """.trimIndent()

    private fun album(id: String = "l.album"): String = """
        {"id":"$id","type":"library-albums","attributes":{
          "name":"Library album","artistName":"Artist",
          "artwork":{"url":"https://example.test/album/{w}x{h}.jpg"}},
          "relationships":{"catalog":{"data":[{"id":"12345","type":"albums",
            "attributes":{"name":"Catalog edition"}}]}}}
    """.trimIndent()

    @Test fun playlistDescriptionRetainsUserTextAndOriginalIdentity() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Playlists) {
            JSONObject("""{"data":[{"id":"p.user","type":"library-playlists","attributes":{
              "name":"Personal playlist","description":{"standard":"<p>My <b>own</b> playlist.</p>"}},
              "relationships":{"catalog":{"data":[{"id":"pl.catalog","type":"playlists",
                "attributes":{"editorialNotes":{"standard":"Catalog text"}}}]}}}]}""")
        }
        assertEquals("<p>My <b>own</b> playlist.</p>", page.items.single().description)
        assertEquals("p.user", page.items.single().resourceId)
    }

    @Test fun albumDescriptionCanUseLinkedCatalogEditorialNotes() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Albums) {
            JSONObject("""{"data":[{"id":"l.album","type":"library-albums","attributes":{"name":"Album"},
              "relationships":{"catalog":{"data":[{"id":"12345","type":"albums","attributes":{
                "editorialNotes":{"standard":"", "short":"An artist &amp; an album."}}}]}}}]}""")
        }
        assertEquals("An artist &amp; an album.", page.items.single().description)
        assertEquals("l.album", page.items.single().resourceId)
        assertEquals("12345", page.items.single().catalogId)
    }

    @Test fun albumsRetainLibraryIdentityAndUseTheAlbumsEndpoint() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Albums) { path ->
            assertEquals("/v1/me/library/albums?limit=100&include=catalog", path)
            JSONObject("""{"data":[${album()}],"meta":{"total":351}}""")
        }
        val item = page.items.single()
        assertEquals(CatalogKind.Album, item.catalogKind)
        assertEquals("l.album", item.resourceId)
        assertEquals("library-albums", item.resourceType)
        assertEquals("12345", item.catalogId)
        assertEquals("Library album", item.title)
        assertEquals(351, page.total)
    }

    @Test fun nameOnlyLibraryArtistsRemainBrowsableWithoutInventingArtwork() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Artists) { path ->
            assertEquals("/v1/me/library/artists?limit=100&include=catalog", path)
            JSONObject("""{"data":[{"id":"r.artist","type":"library-artists",
              "attributes":{"name":"Independent artist"}}]}""")
        }
        val artist = page.items.single()
        assertEquals(CatalogKind.Artist, artist.catalogKind)
        assertEquals("Independent artist", artist.title)
        assertEquals("Independent artist", artist.artist)
        assertEquals("r.artist", artist.resourceId)
        assertNull(artist.catalogId)
        assertNull(artist.artworkUrl)
        assertNull(page.total)
    }

    @Test fun artistCatalogRelationshipEnrichesCoverWithoutAddingAnotherItem() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Artists) {
            JSONObject("""{"data":[{"id":"r.artist","type":"library-artists",
              "attributes":{"name":"Saved artist"},"relationships":{"catalog":{"data":[{
                "id":"9876","type":"artists","attributes":{"name":"Catalog artist",
                "artwork":{"url":"https://example.test/artist/{w}x{h}.jpg"}}}]}}}]}""")
        }
        assertEquals(1, page.items.size)
        assertEquals("Saved artist", page.items.single().title)
        assertEquals("9876", page.items.single().catalogId)
        assertEquals("r.artist", page.items.single().resourceId)
        assertEquals("https://example.test/artist/600x600.jpg", page.items.single().artworkUrl)
    }

    @Test fun artistAlbumsFollowTheLibraryRelationshipAndRetainItsEntireCursor() = runBlocking {
        val artist = api.libraryPage(LibraryCollection.Artists) {
            JSONObject("""{"data":[{"id":"r.artist","type":"library-artists",
              "attributes":{"name":"Saved artist"},"relationships":{"catalog":{"data":[{
                "id":"9876","type":"artists"}]}}}]}""")
        }.items.single()
        val first = api.libraryArtistAlbumsPage(artist) { path ->
            assertEquals("/v1/me/library/artists/r.artist/albums?limit=100&include=catalog", path)
            JSONObject("""{"data":[${album()}],
              "next":"https://api.music.apple.com/v1/me/library/artists/r.artist/albums?offset=100&cursor=a%2Bb%3D&l=zh-Hant"}""")
        }
        assertEquals("/v1/me/library/artists/r.artist/albums?offset=100&cursor=a%2Bb%3D&l=zh-Hant&include=catalog", first.nextPage)
        val second = api.libraryArtistAlbumsPage(artist, first.nextPage) { path ->
            assertEquals(first.nextPage, path)
            JSONObject("""{"data":[${album("l.second")}]}""")
        }
        assertEquals(listOf("l.album", "l.second"), mergeLibraryPage(first.items, second.items).map { it.resourceId })
        assertNull(second.nextPage)
    }

    @Test fun artistContinuationCannotSwitchArtistOrEscapeToCatalog() = runBlocking {
        val artist = api.libraryPage(LibraryCollection.Artists) {
            JSONObject("""{"data":[{"id":"r.artist","type":"library-artists",
              "attributes":{"name":"Saved artist"}}]}""")
        }.items.single()
        for (next in listOf(
            "/v1/me/library/artists/r.other/albums?offset=100",
            "/v1/catalog/tw/artists/9876/albums?offset=100",
            "https://evil.test/v1/me/library/artists/r.artist/albums?offset=100",
        )) {
            var requested = false
            try {
                api.libraryArtistAlbumsPage(artist, next) {
                    requested = true
                    JSONObject("""{"data":[]}""")
                }
                fail("Artist continuation must remain bound to its library relationship")
            } catch (_: IllegalStateException) {
                assertFalse(requested)
            }
        }
    }

    @Test fun recentlyAddedKeepsMixedLibraryResourcesAndFollowsServerPagination() = runBlocking {
        val first = api.libraryPage(LibraryCollection.RecentlyAdded) { path ->
            assertEquals("/v1/me/library/recently-added", path)
            JSONObject("""{"data":[${album()},
              {"id":"p.saved","type":"library-playlists","attributes":{"name":"Saved playlist"}},
              ${song(1)}, {"id":"9876","type":"artists","attributes":{"name":"Not a library entry"}}],
              "next":"/v1/me/library/recently-added?offset=10&l=zh-Hant"}""")
        }
        assertEquals(listOf("l.album", "p.saved", "i.1"), first.items.map { it.resourceId })
        assertEquals("/v1/me/library/recently-added?offset=10&l=zh-Hant", first.nextPage)
        val next = api.libraryPage(LibraryCollection.RecentlyAdded, first.nextPage) { path ->
            assertEquals(first.nextPage, path)
            JSONObject("""{"data":[${song(2)}]}""")
        }
        assertEquals(4, mergeLibraryPage(first.items, next.items).size)
    }

    @Test fun uploadedAndUnmatchedLibraryEntriesRemainVisibleWithoutFalseCatalogMapping() = runBlocking {
        val page = api.libraryPage(LibraryCollection.RecentlyAdded) {
            JSONObject("""{"data":[
              {"id":"i.uploaded","type":"library-songs","attributes":{"name":"My recording"}},
              {"id":"l.z-local","type":"library-albums","attributes":{}},
              {"id":"i.matched","type":"library-songs","attributes":{"name":"Matched recording",
                "playParams":{"catalogId":"123"}}}]}""")
        }
        assertEquals(listOf("i.uploaded", "l.z-local", "i.matched"), page.items.map { it.resourceId })
        val uploaded = page.items[0]
        assertEquals("My recording", uploaded.title)
        assertEquals("", uploaded.artist)
        assertNull(uploaded.artworkUrl)
        assertNull(uploaded.catalogId)
        assertEquals("library-songs", uploaded.resourceType)
        val localAlbum = page.items[1]
        assertFalse(localAlbum.title.isBlank())
        assertNull(localAlbum.catalogId)
        assertEquals("library-albums", localAlbum.resourceType)
        assertEquals("123", page.items[2].catalogId)
    }

    @Test fun catalogArtistAlbumsUseTheStorefrontEndpointAndOnlyCatalogAlbumResources() = runBlocking {
        val artist = dev.amatv.model.Track(
            id = "apple:9876", title = "Catalog artist", artist = "Catalog artist", album = "",
            durationMs = 0L, palette = emptyList(), lyrics = emptyList(), catalogKind = CatalogKind.Artist,
            catalogId = "9876", resourceId = "9876", resourceType = "artists",
        )
        val page = api.artistSectionPage(artist, ArtistSection.Albums, "tw") { path, user ->
            assertFalse(user)
            assertEquals("/v1/catalog/tw/artists/9876/albums?limit=25", path)
            JSONObject("""{"data":[{"id":"111","type":"albums","attributes":{
              "name":"Catalog album","artistName":"Catalog artist"}},${album()}],
              "next":"/v1/catalog/tw/artists/9876/albums?offset=100"}""")
        }
        assertEquals(listOf("111"), page.items.map { it.catalogId })
        assertEquals("/v1/catalog/tw/artists/9876/albums?offset=100", page.nextPage)
    }

    @Test fun firstPageDoesNotEagerlyFetchTheContinuation() = runBlocking {
        val requested = mutableListOf<String>()
        val page = api.libraryPage(LibraryCollection.Songs) { path ->
            requested += path
            JSONObject("""{"data":[${(1..100).joinToString(",") { song(it) }}],
              "next":"/v1/me/library/songs?offset=100"}""")
        }
        assertEquals(listOf("/v1/me/library/songs?limit=100&include=catalog"), requested)
        assertEquals(100, page.items.size)
        assertEquals("/v1/me/library/songs?offset=100&include=catalog", page.nextPage)
        assertEquals(requested.single(), page.requestedPage)
    }

    @Test fun explicitPagesReachPastOldThreeHundredItemCap() = runBlocking {
        var items = emptyList<dev.amatv.model.Track>()
        var next: String? = null
        val requested = mutableListOf<String>()
        repeat(5) { pageIndex ->
            val page = api.libraryPage(LibraryCollection.Songs, next) { path ->
                requested += path
                val start = pageIndex * 100 + 1
                val root = JSONObject("""{"data":[${(start until start + 100).joinToString(",") { song(it) }}]}""")
                if (pageIndex < 4) root.put("next", "/v1/me/library/songs?offset=${start + 99}")
                root
            }
            items = mergeLibraryPage(items, page.items)
            next = page.nextPage
        }
        assertEquals(5, requested.size)
        assertEquals(500, items.size)
        assertEquals("i.500", items.last().resourceId)
        assertNull(next)
    }

    @Test fun resourceIdentityPreservesSeparateLibrarySongsSharingACatalogId() = runBlocking {
        val first = api.libraryPage(LibraryCollection.Songs) {
            JSONObject("""{"data":[${song(1, "same")},${song(2, "same")}]}""")
        }
        val second = api.libraryPage(LibraryCollection.Songs, "/v1/me/library/songs?offset=2") {
            JSONObject("""{"data":[${song(2, "same")},${song(3, "same")}]}""")
        }
        assertEquals(listOf("i.1", "i.2", "i.3"),
            mergeLibraryPage(first.items, second.items).map { it.resourceId })
    }

    @Test fun nestedCatalogAndOtherLibraryResourcesDoNotBecomeShelfEntries() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Playlists) {
            JSONObject("""{"data":[{"id":"p.one","type":"library-playlists","attributes":{"name":"One"},
              "relationships":{"catalog":{"data":[{"id":"pl.one","type":"playlists","attributes":{"name":"Catalog"}}]}}},
              ${song(1)}]}""")
        }
        assertEquals(listOf("p.one"), page.items.map { it.resourceId })
    }

    @Test fun aContinuationCannotSendAccountRequestsOutsideItsLibraryEndpoint() = runBlocking {
        for (next in listOf("https://evil.test/v1/me/library/songs?offset=100",
            "/v1/me/library/playlists?offset=100", "//evil.test/v1/me/library/songs")) {
            var requested = false
            try {
                api.libraryPage(LibraryCollection.Songs, next) {
                    requested = true
                    JSONObject("""{"data":[]}""")
                }
                fail("Unsafe continuation must be refused")
            } catch (_: IllegalStateException) {
                assertFalse(requested)
            }
        }
    }

    @Test fun immediatelyRepeatedContinuationIsAnErrorRatherThanSilentTruncation() = runBlocking {
        try {
            api.libraryPage(LibraryCollection.Songs, "/v1/me/library/songs?offset=100") {
                JSONObject("""{"data":[],"next":"/v1/me/library/songs?offset=100"}""")
            }
            fail("A repeated page must fail")
        } catch (_: IllegalStateException) { }
    }
}
