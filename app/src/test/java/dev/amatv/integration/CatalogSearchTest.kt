package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogSearchType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogSearchTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    @Test fun identicalSongLabelsKeepDistinctReleaseIdentityAlbumAndRatingWhileRepeatedIdsDeduplicate() = runBlocking {
        val page = api.searchPage("positions", CatalogSearchType.Songs, "tw") { _, user ->
            assertFalse(user)
            JSONObject("""{"results":{"songs":{"data":[
              {"id":"1537477069","type":"songs","attributes":{"name":"positions","artistName":"Ariana Grande","albumName":"Positions","contentRating":"explicit","durationInMillis":172325}},
              {"id":"1726087034","type":"songs","attributes":{"name":"positions","artistName":"Ariana Grande","albumName":"Positions (Deluxe Bonus Videos Edition)","contentRating":"explicit","durationInMillis":172325}},
              {"id":"1553944486","type":"songs","attributes":{"name":"positions","artistName":"Ariana Grande","albumName":"Positions (Deluxe)","contentRating":"explicit","durationInMillis":172325}},
              {"id":"1537486846","type":"songs","attributes":{"name":"positions","artistName":"Ariana Grande","albumName":"Positions","contentRating":"clean","durationInMillis":172325}},
              {"id":"1553953912","type":"songs","attributes":{"name":"positions","artistName":"Ariana Grande","albumName":"Positions (Deluxe Edition)","contentRating":"clean","durationInMillis":172325}},
              {"id":"1537477069","type":"songs","attributes":{"name":"positions","artistName":"Ariana Grande","albumName":"Positions","contentRating":"explicit","durationInMillis":172325}}
            ]}}}""")
        }
        assertEquals(listOf("1537477069", "1726087034", "1553944486", "1537486846", "1553953912"), page.items.map { it.resourceId })
        assertEquals(5, page.items.size)
        assertEquals(listOf("Positions", "Positions (Deluxe Bonus Videos Edition)", "Positions (Deluxe)", "Positions", "Positions (Deluxe Edition)"), page.items.map { it.album })
        assertEquals(listOf("explicit", "explicit", "explicit", "clean", "clean"), page.items.map { it.contentRating })
        assertTrue(page.items.all { it.title == "positions" && it.artist == "Ariana Grande" && it.durationMs == 172_325L })
    }

    @Test fun libraryRatingUsesSourceThenLinkedCatalogAndNeverInventsExplicitness() = runBlocking {
        val page = api.searchPage("Song", CatalogSearchType.Songs, "tw", library = true) { _, _ ->
            JSONObject("""{"results":{"library-songs":{"data":[
              {"id":"i.clean","type":"library-songs","attributes":{"name":"Song","artistName":"Artist","contentRating":"clean"},
                "relationships":{"catalog":{"data":[{"id":"123","type":"songs","attributes":{"contentRating":"explicit"}}]}}},
              {"id":"i.linked","type":"library-songs","attributes":{"name":"Song","artistName":"Artist"},
                "relationships":{"catalog":{"data":[{"id":"124","type":"songs","attributes":{"contentRating":"explicit"}}]}}},
              {"id":"i.unknown","type":"library-songs","attributes":{"name":"Song","artistName":"Artist","contentRating":"unexpected"}}
            ]}}}""")
        }
        assertEquals(listOf("clean", "explicit", null), page.items.map { it.contentRating })
    }

    @Test fun missingDurationStaysUnknownAndCatalogMappingCanSupplyActualDuration() = runBlocking {
        val page = api.searchPage("Duration", CatalogSearchType.Songs, "tw", library = true) { _, _ ->
            JSONObject("""{"results":{"library-songs":{"data":[
              {"id":"i.mapped","type":"library-songs","attributes":{"name":"Mapped","artistName":"Artist","durationInMillis":0},
                "relationships":{"catalog":{"data":[{"id":"123","type":"songs","attributes":{"name":"Mapped","artistName":"Artist","durationInMillis":122500}}]}}},
              {"id":"i.local","type":"library-songs","attributes":{"name":"Uploaded","artistName":"Artist"}}
            ]}}}""")
        }
        assertEquals(122_500L, page.items.first().durationMs)
        assertEquals(0L, page.items.last().durationMs)
        assertEquals("i.local", page.items.last().resourceId)
        assertEquals("library-songs", page.items.last().resourceType)
        assertNull(page.items.last().catalogId)
    }

    @Test fun serverDisplayTermsAndTopResultNamespacesRemainDistinct() = runBlocking {
        val suggestions = api.searchSuggestions("Search", "tw") { path ->
            assertTrue(path.contains("kinds=terms,topResults"))
            JSONObject("""{"results":{"suggestions":[
              {"kind":"terms","displayTerm":"S***","searchTerm":"Search exact"},
              {"kind":"terms","searchTerm":"Must not expose without server display"},
              {"kind":"topResults","content":{"id":"1710452798","type":"apple-curators","attributes":{"name":"Sing"}}},
              {"kind":"topResults","content":{"id":"123","type":"artists","attributes":{"name":"Artist"}}}
            ]}}""")
        }
        assertEquals("S***", suggestions.terms.single().displayTerm)
        assertEquals("Search exact", suggestions.terms.single().searchTerm)
        assertEquals(listOf("apple-curators", "artists"), suggestions.topResults.map { it.resourceType })
        assertEquals(CatalogKind.Page, suggestions.topResults.first().catalogKind)
    }

    @Test fun unsupportedSuggestionsFallBackToPublicHintsOnce() = runBlocking {
        val paths = mutableListOf<String>()
        val suggestions = api.searchSuggestions("Music", "tw") { path ->
            paths += path
            if ("/suggestions?" in path) throw CatalogRequestException(CatalogFailureReason.Unavailable, "Unavailable", 404)
            assertEquals("/v1/catalog/tw/search/hints?term=Music&limit=5", path)
            JSONObject("""{"results":{"terms":["Music","Music","Music videos"]}}""")
        }
        assertEquals(2, paths.size)
        assertEquals(listOf("Music", "Music videos"), suggestions.terms.map { it.displayTerm })
        assertTrue(suggestions.topResults.isEmpty())
    }

    @Test fun searchContinuationCannotChangeQueryCategoryOrAccountNamespace() = runBlocking {
        for (cursor in listOf(
            "/v1/catalog/tw/search?term=Other&types=artists&offset=25",
            "/v1/catalog/tw/search?term=Artist&types=albums&offset=25",
            "/v1/catalog/tw/search?term=Artist&term=Other&types=artists&offset=25",
            "/v1/me/library/search?term=Artist&types=library-artists&offset=25",
        )) {
            var fetched = false
            assertTrue(runCatching {
                api.searchPage("Artist", CatalogSearchType.Artists, "tw", cursor) { _, _ -> fetched = true; JSONObject() }
            }.isFailure)
            assertFalse(fetched)
        }
    }

    @Test fun savedArtistThumbnailsUseOneBatchAndKeepSearchIdentity() = runBlocking {
        val paths = mutableListOf<String>()
        val page = api.searchPage("Artist", CatalogSearchType.Artists, "tw", library = true) { path, user ->
            assertTrue(user)
            paths += path
            if ("/search?" in path) JSONObject("""{"results":{"library-artists":{"data":[
              {"id":"r.one","type":"library-artists","attributes":{"name":"Saved name"}},
              {"id":"r.two","type":"library-artists","attributes":{"name":"Local artist"}}
            ]}}}""")
            else {
                assertEquals("/v1/me/library/artists?ids=r.one%2Cr.two&include=catalog", path)
                JSONObject("""{"data":[{"id":"r.one","type":"library-artists","attributes":{"name":"Changed remote name"},
                  "relationships":{"catalog":{"data":[{"id":"1","type":"artists","attributes":{"name":"Catalog name",
                    "artwork":{"url":"https://example.test/{w}x{h}.jpg"}}}]}}}]}""")
            }
        }
        assertEquals(2, paths.size)
        assertEquals(listOf("r.one", "r.two"), page.items.map { it.resourceId })
        assertEquals("Saved name", page.items.first().title)
        assertEquals("library-artists", page.items.first().resourceType)
        assertEquals("1", page.items.first().catalogId)
        assertNotNull(page.items.first().artworkUrl)
        assertNull(page.items.last().artworkUrl)
    }
}
