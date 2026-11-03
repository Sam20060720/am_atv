package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogMetadataFormat
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CatalogPagesTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
    private fun pages(user: Boolean = false, fetch: suspend (String, Boolean) -> JSONObject) =
        CatalogPagesApi({ "tw" }, { user }, fetch, api::parseResourceItem, Dispatchers.Unconfined)

    @Test fun playlistRootUsesDocumentedFilterIdentityWithoutRequiringOrdinaryFolderMetadata() = runBlocking {
        val paths = mutableListOf<String>()
        val pages = pages(user = true) { path, user ->
            assertTrue(user)
            paths += path
            JSONObject("""{"data":[],"meta":{"filters":{"identity":{"playlistsroot":[
              {"id":"p.playlistsroot","type":"library-playlist-folders","href":"/v1/me/library/playlist-folders/p.playlistsroot"}
            ]}}}}""")
        }
        val profile = pages.profile(CatalogResourceRef("p.playlistsroot", "library-playlist-folders"))
        assertEquals(listOf("/v1/me/library/playlist-folders?filter%5Bidentity%5D=playlistsroot"), paths)
        assertEquals(CatalogResourceRef("p.playlistsroot", "library-playlist-folders"), profile.ref)
        assertEquals(profile.ref.id, profile.item.resourceId)
        assertEquals(listOf("children"), profile.sections.map { it.id })
        assertNull(profile.canEdit)
    }

    @Test fun playlistRootChildrenStillUseTheRealRootIdRelationshipEndpoint() = runBlocking {
        val pages = pages(user = true) { path, user ->
            assertTrue(user)
            assertEquals("/v1/me/library/playlist-folders/p.playlistsroot/children?limit=100&include=catalog&extend%5Bplaylists%5D=editorialVideo", path)
            JSONObject("""{"data":[]}""")
        }
        val result = pages.sectionPage(CatalogResourceRef("p.playlistsroot", "library-playlist-folders"),
            dev.amatv.model.CatalogResourceSection("children", resourceTypes = setOf("library-playlist-folders", "library-playlists"), pageSize = 100))
        assertTrue(result.items.isEmpty())
    }

    @Test fun playlistRootAuthenticationErrorsAndOrdinaryFolderNotFoundKeepTheirMeaning() = runBlocking {
        for ((ref, failure) in listOf(
            CatalogResourceRef("p.playlistsroot", "library-playlist-folders") to CatalogRequestException(CatalogFailureReason.Authorization, "Sign in", 401),
            CatalogResourceRef("p.deleted", "library-playlist-folders") to CatalogRequestException(CatalogFailureReason.Unavailable, "Missing", 404),
        )) {
            var requested: String? = null
            val pages = pages(user = true) { path, _ -> requested = path; throw failure }
            assertSame(failure, runCatching { pages.profile(ref) }.exceptionOrNull())
            if (ref.id == "p.deleted") assertEquals("/v1/me/library/playlist-folders/p.deleted", requested)
        }
    }

    @Test fun playlistRootLookupRejectsAnUnrelatedOrWrongNamespaceIdentity() = runBlocking {
        for (identity in listOf(
            """{"id":"p.other","type":"library-playlist-folders"}""",
            """{"id":"p.playlistsroot","type":"library-playlists"}""",
        )) {
            val pages = pages(user = true) { _, _ -> JSONObject("""{"data":[],"meta":{"filters":{"identity":{"playlistsroot":[$identity]}}}}""") }
            assertTrue(runCatching { pages.profile(CatalogResourceRef("p.playlistsroot", "library-playlist-folders")) }.isFailure)
        }
    }

    @Test fun albumMetadataKeepsActualCatalogVariantsAndCanonicalUrlWithoutClaimingPlaybackFormat() = runBlocking {
        var calls = 0
        val pages = pages { path, user ->
            calls++
            assertFalse(user)
            assertTrue(path.startsWith("/v1/catalog/tw/albums/123?extend="))
            JSONObject("""{"data":[{"id":"123","type":"albums","attributes":{
              "name":"Actual album","artistName":"Artist","releaseDate":"2025-07-01","copyright":"℗ Label",
              "recordLabel":"Label","trackCount":14,"contentRating":"explicit","audioVariants":["lossless","dolby-atmos"],
              "genreNames":["Pop"],"isSingle":false,"upc":"0012345","url":"https://music.apple.com/tw/album/actual/123",
              "unknownStructuredField":{"keep":"value"}},"views":{"other-versions":{"attributes":{"title":"其他版本"}}}}]}""")
        }
        val profile = pages.profile(CatalogResourceRef("123", "albums"))
        assertEquals(1, calls)
        assertEquals("https://music.apple.com/tw/album/actual/123", profile.url)
        assertEquals(profile.url, profile.item.url)
        assertEquals("2025-07-01", profile.metadata.single { it.key == "releaseDate" }.value)
        assertEquals("14", profile.metadata.single { it.key == "trackCount" }.value)
        val variants = profile.metadata.single { it.key == "audioVariants" }
        assertEquals(listOf("lossless", "dolby-atmos"), variants.values)
        assertEquals(CatalogMetadataFormat.AudioVariants, variants.format)
        assertEquals("其他版本", profile.sections.single { it.id == "other-versions" }.title)
        assertEquals(setOf("playlists"), profile.sections.single { it.id == "appears-on" }.resourceTypes)
        assertNull(profile.inLibrary)
    }

    @Test fun savedPlaylistProfileRetainsSourceAndUserDescriptionWhileEnrichingCanonicalUrl() = runBlocking {
        val profile = pages(user = true) { path, user ->
            assertTrue(user)
            assertEquals("/v1/me/library/playlists/p.saved?include=catalog", path)
            JSONObject("""{"data":[{"id":"p.saved","type":"library-playlists","attributes":{
              "name":"My title","description":{"standard":"My description"},"canEdit":true,"isPublic":false},
              "relationships":{"catalog":{"data":[{"id":"pl.mapped","type":"playlists","attributes":{
                "name":"Different public name","url":"https://music.apple.com/tw/playlist/public/pl.mapped"}}]}}}]}""")
        }.profile(CatalogResourceRef("p.saved", "library-playlists"))
        assertEquals("p.saved", profile.item.resourceId)
        assertEquals("library-playlists", profile.item.resourceType)
        assertEquals("My title", profile.name)
        assertEquals("My description", profile.description)
        assertEquals(true, profile.canEdit)
        assertEquals(true, profile.inLibrary)
        assertEquals("https://music.apple.com/tw/playlist/public/pl.mapped", profile.url)
        assertEquals("false", profile.metadata.single { it.key == "isPublic" }.value)
    }

    @Test fun curatorPageUsesActualAppleCuratorNamespaceAndItsOwnCursor() = runBlocking {
        val ref = CatalogResourceRef("1710452798", "apple-curators")
        var calls = 0
        val pages = pages { path, _ ->
            calls++
            if (path.endsWith("/1710452798")) JSONObject("""{"data":[{"id":"1710452798","type":"apple-curators",
              "attributes":{"name":"Sing","url":"https://music.apple.com/tw/curator/sing/1710452798"}}]}""")
            else JSONObject("""{"data":[{"id":"pl.one","type":"playlists","attributes":{"name":"Real playlist"},
              "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"Not another card"}}]}}}],
              "next":"/v1/catalog/tw/apple-curators/1710452798/playlists?offset=10&cursor=a%2Fb"}""")
        }
        val profile = pages.profile(ref)
        assertEquals(CatalogKind.Page, profile.item.catalogKind)
        val section = profile.sections.single()
        val page = pages.sectionPage(ref, section)
        assertEquals(listOf("pl.one"), page.items.map { it.resourceId })
        assertEquals("/v1/catalog/tw/apple-curators/1710452798/playlists?offset=10&cursor=a%2Fb", page.nextPage)
        assertTrue(runCatching { pages.sectionPage(ref, section,
            "/v1/catalog/tw/curators/1710452798/playlists?offset=10") }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun genreChartsUseReturnedGenreIdentityAndRejectAnotherGenresCursor() = runBlocking {
        var calls = 0
        val pages = pages { path, _ ->
            calls++
            if (path.endsWith("/genres/20")) JSONObject("""{"data":[{"id":"20","type":"genres","attributes":{
              "name":"Alternative","parentId":"34","parentName":"Music"}}]}""")
            else {
                assertEquals("/v1/catalog/tw/charts?types=songs&genre=20&chart=most-played&limit=25&extend%5Bsongs%5D=editorialVideo", path)
                JSONObject("""{"results":{"songs":[{"data":[{"id":"1","type":"songs","attributes":{"name":"Song"}}]}]}}""")
            }
        }
        val ref = CatalogResourceRef("20", "genres")
        val profile = pages.profile(ref)
        assertEquals(CatalogResourceRef("34", "genres"), profile.links.single().ref)
        assertEquals("Music", profile.links.single().title)
        val section = profile.sections.single { it.id == "charts-songs" }
        assertEquals("1", pages.sectionPage(ref, section).items.single().resourceId)
        assertTrue(runCatching { pages.sectionPage(ref, section,
            "/v1/catalog/tw/charts?types=songs&genre=21&chart=most-played&offset=25") }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun artistMetadataPageUsesTheSameLatestReleaseLimitAndEmptyRelationVerdict() = runBlocking {
        val requests = mutableListOf<String>()
        val pages = pages { path, _ ->
            requests += path
            if ("/view/" !in path) JSONObject("""{"data":[{"id":"1635469693","type":"artists","attributes":{"name":"NewJeans"}}]}""")
            else {
                assertTrue(path.startsWith("/v1/catalog/tw/artists/1635469693/view/latest-release?limit=1&with=attributes"))
                throw catalogRequestFailure(404, """{"errors":[{"code":"40403","title":"No related resources"}]}""", "API HTTP 404")
            }
        }
        val ref = CatalogResourceRef("1635469693", "artists")
        val latest = pages.profile(ref).sections.single { it.id == "latest-release" }
        assertEquals(1, latest.pageSize)
        val result = pages.sectionPage(ref, latest)
        assertTrue(result.items.isEmpty())
        assertNull(result.nextPage)
        assertEquals(2, requests.size)
    }

    @Test fun showAlbumFollowsTheSongsActualRelationshipRatherThanItsDisplayName() = runBlocking {
        val song = Track("apple:1", "Song", "Artist", "Different display album", 1_000L,
            emptyList(), emptyList(), catalogId = "1", catalogKind = CatalogKind.Song)
        val album = pages { path, user ->
            assertFalse(user)
            assertTrue(path.startsWith("/v1/catalog/tw/songs/1/albums?limit=10"))
            JSONObject("""{"data":[{"id":"2","type":"albums","attributes":{
                "name":"Actual relationship album","artistName":"Artist"}}]}""")
        }.albumForSong(song)
        assertNotNull(album)
        assertEquals("2", album!!.catalogId)
        assertEquals("Actual relationship album", album.title)
    }

    @Test fun showAlbumKeepsAnUnmatchedLibrarySongInItsLibraryNamespace() = runBlocking {
        val song = Track("apple:i.unmatched", "Upload", "My artist", "My album", 1_000L,
            emptyList(), emptyList(), catalogKind = CatalogKind.Song,
            resourceId = "i.unmatched", resourceType = "library-songs")
        val album = pages(user = true) { path, user ->
            assertTrue(user)
            assertTrue(path.startsWith("/v1/me/library/songs/i.unmatched/albums?limit=10&include=catalog"))
            JSONObject("""{"data":[{"id":"l.saved","type":"library-albums","attributes":{
                "name":"My saved album","artistName":"My artist"}}]}""")
        }.albumForSong(song)
        assertEquals("l.saved", album!!.resourceId)
        assertEquals("library-albums", album.resourceType)
        assertNull(album.catalogId)
    }

    @Test fun showAlbumReturnsNoInventedDestinationForMissingOrNonSongRelationships() = runBlocking {
        var requests = 0
        val pages = pages { _, _ ->
            requests++
            JSONObject("""{"data":[]}""")
        }
        val song = Track("apple:1", "Song", "Artist", "Display album", 1_000L,
            emptyList(), emptyList(), catalogId = "1", catalogKind = CatalogKind.Song)
        assertNull(pages.albumForSong(song))
        assertNull(pages.albumForSong(song.copy(catalogKind = CatalogKind.Album)))
        assertEquals(1, requests)
    }

    @Test fun songCreditsKeepTheReturnedGroupAndPeoplesActualRoles() = runBlocking {
        val profile = pages { path, _ ->
            assertTrue(path.startsWith("/v1/catalog/tw/songs/1?include=credits&extend="))
            JSONObject("""{"data":[{"id":"1","type":"songs","attributes":{
                "name":"Song","artistName":"Singer","composerName":"Legacy composer"},
                "relationships":{"credits":{"data":[{"id":"song:1:production","type":"role-categories",
                "attributes":{"kind":"production","title":"Production & Engineering"},"relationships":{
                "credit-artists":{"data":[{"id":"p1","type":"credit-artists","attributes":{
                "name":"Actual person","roleNames":["Producer","Mixing Engineer"]}},
                {"id":"p2","type":"credit-artists","attributes":{"name":"Another person","roleNames":[]}}]}}}]}}}]}""")
        }.profile(CatalogResourceRef("1", "songs"))
        val group = profile.metadata.single { it.key == "credits:song:1:production" }
        assertEquals("Production & Engineering", group.title)
        assertEquals(listOf("Actual person · Producer, Mixing Engineer", "Another person"), group.values)
        assertEquals("Legacy composer", profile.metadata.single { it.key == "composerName" }.value)
    }

    @Test fun songCreditsResolveShippedFlatResourceReferencesWithoutTakingAnotherSongsGroups() = runBlocking {
        val profile = pages { _, _ ->
            JSONObject("""{"data":[{"id":"1","type":"songs","attributes":{"name":"Song"},
                "relationships":{"credits":{"data":[{"id":"writing","type":"role-categories"}]}}}],
                "resources":{"role-categories":{"writing":{"id":"writing","type":"role-categories",
                "attributes":{"title":"Songwriting","kind":"writing"},"relationships":{
                "credit-artists":{"data":[{"id":"p1","type":"credit-artists"},{"id":"missing","type":"credit-artists"}]}}},
                "foreign":{"id":"foreign","type":"role-categories","attributes":{"title":"Wrong song"}}},
                "credit-artists":{"p1":{"id":"p1","type":"credit-artists","attributes":{
                "name":"Writer","roleNames":["Composer"]}}}}}""")
        }.profile(CatalogResourceRef("1", "songs"))
        val groups = profile.metadata.filter { it.key.startsWith("credits:") }
        assertEquals(1, groups.size)
        assertEquals("Songwriting", groups.single().title)
        assertEquals(listOf("Writer · Composer"), groups.single().values)
    }

    @Test fun aCreditHrefOrComposerNameAloneDoesNotProduceAnEmptyOrInventedCreditGroup() = runBlocking {
        val profile = pages { _, _ ->
            JSONObject("""{"data":[{"id":"1","type":"songs","attributes":{
                "name":"Song","composerName":"Composer only"},"relationships":{"credits":{
                "href":"/v1/catalog/tw/songs/1/credits","data":[]}}}]}""")
        }.profile(CatalogResourceRef("1", "songs"))
        assertTrue(profile.metadata.none { it.key.startsWith("credits:") })
        assertEquals("Composer only", profile.metadata.single { it.key == "composerName" }.value)
    }

    @Test fun optionalCreditAndArtworkDenialsDoNotRemoveTheOrdinarySongDetails() = runBlocking {
        val requests = mutableListOf<String>()
        val profile = pages(user = true) { path, _ ->
            requests += path
            if ("credits" in path || "extend=" in path) throw CatalogRequestException(
                CatalogFailureReason.Authorization, "Optional data unavailable", 403)
            JSONObject("""{"data":[{"id":"1","type":"songs","attributes":{"name":"Song"}}]}""")
        }.profile(CatalogResourceRef("1", "songs"))
        assertEquals("Song", profile.name)
        assertEquals(3, requests.size)
        assertEquals("/v1/catalog/tw/songs/1?include=library", requests.last())
    }

    @Test fun optionalWebCreditsMatchTheExactSongAndKeepTheLibraryProfileIdentity() = runBlocking {
        val pages = pages(user = true) { _, _ ->
            JSONObject("""{"data":[{"id":"i.saved","type":"library-songs","attributes":{
                "name":"My saved title","artistName":"My artist"},"relationships":{"catalog":{"data":[{
                "id":"1","type":"songs","attributes":{"name":"Catalog title"}}]}}}]}""")
        }
        val profile = pages.profile(CatalogResourceRef("i.saved", "library-songs"))
        val credits = JSONObject("""{"data":[{"id":"1","type":"songs","relationships":{"credits":{"data":[{
            "id":"performing","type":"role-categories","attributes":{"title":"Performing Artists"},"relationships":{
            "credit-artists":{"data":[{"id":"p1","type":"credit-artists","attributes":{
            "name":"Singer","roleNames":["Vocals"]}}]}}}]}}}]}""")
        // The shipped web request asks for format[resources]=map, so the root
        // identifiers and their expanded resources arrive separately.
        val song = credits.getJSONArray("data").getJSONObject(0)
        credits.put("resources", JSONObject().put("songs", JSONObject().put("1", song)))
        credits.put("data", JSONArray().put(JSONObject().put("id", "1").put("type", "songs")))
        val enriched = pages.attachSongCredits(profile, credits)
        assertEquals(profile.ref, enriched.ref)
        assertEquals(profile.item, enriched.item)
        assertEquals("My saved title", enriched.name)
        assertEquals(listOf("Singer · Vocals"), enriched.metadata.single { it.key == "credits:performing" }.values)
        assertSame(profile, pages.attachSongCredits(profile,
            JSONObject(credits.toString().replace("\"id\":\"1\"", "\"id\":\"2\""))))
    }

    @Test fun capturedPublicWebCreditsRetainAllRealRoleCategoriesAndLocalizedPeopleRoles() = runBlocking {
        val pages = pages { _, _ ->
            JSONObject("""{"data":[{"id":"1553944257","type":"songs","attributes":{
                "name":"Original song metadata","artistName":"Ariana Grande"}}]}""")
        }
        val profile = pages.profile(CatalogResourceRef("1553944257", "songs"))
        val fixture = checkNotNull(javaClass.getResource("/catalog/song-credits-1553944257.json")).readText()
        val enriched = pages.attachSongCredits(profile, JSONObject(fixture))
        val groups = enriched.metadata.filter { it.key.startsWith("credits:") }
        assertEquals(listOf("演出藝人", "詞曲", "製作與工程團隊"), groups.map { it.title })
        assertEquals(listOf(2, 9, 7), groups.map { it.values.size })
        assertEquals(listOf("Ariana Grande · 演唱, 和聲", "Peter Lee Johnson · 弦樂器"), groups.first().values)
        assertTrue(groups.last().values.contains("Serban Ghenea · 混音師"))
        assertTrue(groups.last().values.contains("Randy Merrill · 母帶工程師"))
        assertEquals(profile.item, enriched.item)
        assertEquals(profile.ref, enriched.ref)
        assertEquals("Original song metadata", enriched.name)
    }
}
