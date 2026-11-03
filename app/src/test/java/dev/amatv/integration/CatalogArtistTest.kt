package dev.amatv.integration

import dev.amatv.model.ArtistSection
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogSearchType
import dev.amatv.model.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogArtistTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
    private fun item(id: String, kind: CatalogKind, type: String) = Track(
        id = "apple:$id", title = "Saved name", artist = "Artist", album = "", durationMs = 1,
        palette = emptyList(), lyrics = emptyList(), catalogKind = kind, resourceId = id, resourceType = type,
    )

    @Test fun libraryProfileKeepsSavedIdentityAndSeparatesCatalogIdentity() = runBlocking {
        val requests = mutableListOf<Pair<String, Boolean>>()
        val profile = api.artistProfile(item("r.saved", CatalogKind.Artist, "library-artists"), "tw") { path, user ->
            requests += path to user
            if (user) JSONObject("""{"data":[{"id":"r.saved","type":"library-artists","attributes":{"name":"My artist"},
              "relationships":{"catalog":{"data":[{"id":"123","type":"artists"}]}}}]}""")
            else JSONObject("""{"data":[{"id":"123","type":"artists","attributes":{
              "name":"Catalog artist","genreNames":["Pop"],"editorialNotes":{"standard":"Editorial <b>notes</b>"},
              "url":"https://music.apple.com/tw/artist/catalog/123","artwork":{"url":"https://example.test/{w}x{h}.jpg"}}}]}""")
        }
        assertEquals("/v1/me/library/artists/r.saved?include=catalog" to true, requests.first())
        assertTrue(requests.last().first.startsWith("/v1/catalog/tw/artists/123?extend=artistBio,"))
        assertFalse(requests.last().second)
        assertEquals("r.saved", profile.artist.resourceId)
        assertEquals("library-artists", profile.artist.resourceType)
        assertEquals("My artist", profile.artist.title)
        assertEquals("123", profile.catalogArtist?.resourceId)
        assertEquals("artists", profile.catalogArtist?.resourceType)
        assertEquals("Editorial <b>notes</b>", profile.biography)
        assertEquals(listOf("Pop"), profile.genreNames)
        assertEquals(ArtistSection.LibraryAlbums, profile.applicableSections.first())
        assertTrue(profile.applicableSections.contains(ArtistSection.SimilarArtists))
    }

    @Test fun unsupportedWebExtensionsFallBackToThePublicArtistRequest() = runBlocking {
        val calls = mutableListOf<String>()
        val profile = api.artistProfile(item("123", CatalogKind.Artist, "artists"), "tw") { path, _ ->
            calls += path
            if ('?' in path) throw CatalogRequestException(CatalogFailureReason.Authorization, "Optional extension denied", 403)
            JSONObject("""{"data":[{"id":"123","type":"artists","attributes":{"name":"Artist","genreNames":["Pop"]}}]}""")
        }
        assertEquals(2, calls.size)
        assertEquals("/v1/catalog/tw/artists/123", calls.last())
        assertEquals("artists", profile.catalogArtist?.resourceType)
    }

    @Test fun unmatchedLibraryArtistDoesNotInventCatalogSectionsOrSendCatalogRequest() = runBlocking {
        val profile = api.artistProfile(item("r.local", CatalogKind.Artist, "library-artists"), "tw") { path, user ->
            assertTrue(user)
            assertEquals("/v1/me/library/artists/r.local?include=catalog", path)
            JSONObject("""{"data":[{"id":"r.local","type":"library-artists","attributes":{"name":"Local performer"}}]}""")
        }
        assertNull(profile.catalogArtist)
        assertNull(profile.biography)
        assertEquals(listOf(ArtistSection.LibraryAlbums), profile.applicableSections)
    }

    @Test fun artistViewsKeepEncodedCursorAndIgnoreUnrelatedNestedResources() = runBlocking {
        val artist = item("123", CatalogKind.Artist, "artists")
        val cursor = "/v1/catalog/tw/artists/123/view/similar-artists?offset=2&cursor=a%2Fb&with=attributes"
        val first = api.artistSectionPage(artist, ArtistSection.SimilarArtists, "tw") { path, user ->
            assertFalse(user)
            assertEquals("/v1/catalog/tw/artists/123/view/similar-artists?limit=20&with=attributes", path)
            JSONObject("""{"data":[{"id":"456","type":"artists","attributes":{"name":"Related"},
              "relationships":{"albums":{"data":[{"id":"789","type":"albums","attributes":{"name":"Not a second card"}}]}}}],
              "next":"$cursor"}""")
        }
        assertEquals(listOf("456"), first.items.map { it.resourceId })
        assertEquals(cursor, first.nextPage)
        val last = api.artistSectionPage(artist, ArtistSection.SimilarArtists, "tw", first.nextPage) { path, _ ->
            assertEquals(cursor, path)
            JSONObject("""{"data":[]}""")
        }
        assertNull(last.nextPage)
        var fetched = false
        assertTrue(runCatching {
            api.artistSectionPage(artist, ArtistSection.SimilarArtists, "tw",
                "/v1/catalog/tw/artists/999/view/similar-artists?offset=2") { _, _ -> fetched = true; JSONObject() }
        }.isFailure)
        assertFalse(fetched)
    }

    @Test fun latestReleaseRequestsOneAlbumAndParsesTheDirectViewResource() = runBlocking {
        val page = api.artistSectionPage(item("1635469693", CatalogKind.Artist, "artists"), ArtistSection.LatestRelease, "tw") { path, user ->
            assertFalse(user)
            assertEquals("/v1/catalog/tw/artists/1635469693/view/latest-release?limit=1&with=attributes", path)
            JSONObject("""{"data":[{"id":"release","type":"albums","attributes":{"name":"Actual latest release"}}]}""")
        }
        assertEquals(listOf("release"), page.items.map { it.resourceId })
        assertEquals(CatalogKind.Album, page.items.single().catalogKind)
        assertNull(page.nextPage)
    }

    @Test fun explicitNoRelatedResourcesIsAnEmptyViewButOtherFailuresRemainErrors() = runBlocking {
        val artist = item("1635469693", CatalogKind.Artist, "artists")
        val noRelated = catalogRequestFailure(404,
            """{"errors":[{"status":"404","code":"40403","title":"No related resources","detail":"No related resources found for latest-release"}]}""",
            "API HTTP 404")
        assertEquals("40403", noRelated.serviceCode)
        for (section in listOf(ArtistSection.LatestRelease, ArtistSection.FeaturedAlbums, ArtistSection.LiveAlbums, ArtistSection.CompilationAlbums)) {
            var calls = 0
            val page = api.artistSectionPage(artist, section, "tw") { _, _ -> calls++; throw noRelated }
            assertEquals(1, calls)
            assertTrue(page.items.isEmpty())
            assertNull(page.nextPage)
        }
        val failures = listOf(
            CatalogRequestException(CatalogFailureReason.Unavailable, "Missing artist", 404, "40400"),
            CatalogRequestException(CatalogFailureReason.Unavailable, "Unknown 404", 404),
            CatalogRequestException(CatalogFailureReason.Authorization, "Sign in", 403),
            CatalogRequestException(CatalogFailureReason.Transient, "Retry", 500),
            java.io.IOException("Disconnected"),
            catalogRequestFailure(404, "not JSON", "Unknown 404"),
            catalogRequestFailure(404, """{"errors":[{"code":"40403"},{"code":"40400"}]}""", "Mixed failures"),
        )
        for (failure in failures) {
            try {
                api.artistSectionPage(artist, ArtistSection.LatestRelease, "tw") { _, _ -> throw failure }
                fail("Only an explicit 40403 relation may be empty")
            } catch (actual: Exception) {
                // Coroutine stack recovery may copy an IOException. Preserve
                // its meaning, rather than relying on throwable identity.
                assertEquals(failure.javaClass, actual.javaClass)
                assertEquals(failure.message, actual.message)
                if (failure is CatalogRequestException) {
                    actual as CatalogRequestException
                    assertEquals(failure.httpStatus, actual.httpStatus)
                    assertEquals(failure.serviceCode, actual.serviceCode)
                }
            }
        }
    }

    @Test fun savedAlbumArtistRelationshipDoesNotGuessFromItsDisplayName() = runBlocking {
        val page = api.relatedArtistsPage(item("l.saved", CatalogKind.Album, "library-albums"), "tw") { path, user ->
            assertTrue(user)
            assertEquals("/v1/me/library/albums/l.saved/artists?limit=10&include=catalog", path)
            JSONObject("""{"data":[{"id":"r.real","type":"library-artists","attributes":{"name":"Actual performer"}}]}""")
        }
        assertEquals("r.real", page.items.single().resourceId)
        assertEquals("library-artists", page.items.single().resourceType)
    }

    @Test fun perCategorySearchPreservesPaginationAndNoResultIsNotAnError() = runBlocking {
        val cursor = "/v1/catalog/tw/search?term=Artist&types=artists&offset=25&limit=25"
        val page = api.searchPage("Artist", CatalogSearchType.Artists, "tw") { path, user ->
            assertFalse(user)
            assertEquals("/v1/catalog/tw/search?term=Artist&types=artists&limit=25", path)
            JSONObject("""{"results":{"artists":{"data":[{"id":"1","type":"artists","attributes":{"name":"Artist"}}],"next":"$cursor"},
              "songs":{"data":[{"id":"2","type":"songs","attributes":{"name":"Do not mix categories"}}]}}}""")
        }
        assertEquals("artists", page.items.single().resourceType)
        assertEquals(cursor, page.nextPage)
        val empty = api.searchPage("Nothing", CatalogSearchType.Artists, "tw") { _, _ -> JSONObject("""{"results":{}}""") }
        assertTrue(empty.items.isEmpty())
        assertNull(empty.nextPage)
    }
}
