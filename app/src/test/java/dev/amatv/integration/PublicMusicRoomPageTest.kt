package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.CatalogResourceProfile
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.CatalogResourceSection
import dev.amatv.ui.CatalogPageBrowser
import dev.amatv.ui.pageKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicMusicRoomPageTest {
    private val capabilities = CatalogPresentationCapabilities(NativeMediaCapabilities(
        songItemType = 1, albumContainerType = 2, playlistContainerType = 3,
    ), stationWebPlaybackAvailable = true)
    private val marker = "<script type=\"application/json\" id=\"serialized-server-data\">"
    private fun fixture(name: String) = checkNotNull(javaClass.getResource(name)).readText()
    private fun root(html: String) = JSONObject(html.substringAfter(marker).substringBefore("</script>"))
    private fun page(root: JSONObject) = "$marker$root</script>"
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected invalid public route/model to be rejected") }
        catch (_: PublicMusicBrowseParseException) { }
    }

    @Test fun browseHeaderRetainsOnlyAnExactRoomIntentAndCanonicalUrl() {
        val html = fixture("/apple-music-public-new.html")
        val expected = PublicMusicBrowsePage.parse(html, "tw", capabilities).first().seeAll!!
        assertEquals(CatalogKind.Page, expected.catalogKind)
        assertEquals("public-rooms", expected.resourceType)
        assertEquals("https://music.apple.com/tw/room/${expected.resourceId}", expected.url)

        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("url", "https://music.apple.com.evil.example/tw/room/${expected.resourceId}") },
            { it.put("url", "https://music.apple.com/tw/room/1") },
            { it.getJSONObject("segue").getJSONObject("destination").getJSONObject("intent").put("storefront", "us") },
            { it.getJSONObject("segue").getJSONObject("destination").getJSONObject("intent").put("\$kind", "UnknownIntent") },
        )) {
            val data = root(html)
            val section = data.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONArray("sections")
                .let { sections -> (0 until sections.length()).map(sections::getJSONObject).first {
                    it.optJSONObject("header")?.optJSONObject("item")?.optJSONObject("titleLink")?.optString("title") == expected.title
                } }
            change(section.getJSONObject("header").getJSONObject("item").getJSONObject("titleLink"))
            assertNull(PublicMusicBrowsePage.parse(page(data), "tw", capabilities).first().seeAll)
        }
    }

    @Test fun actualRoomContainsMoreThanThePreviewAndKeepsTheExactServerContinuation() {
        val html = fixture("/apple-music-public-room.html")
        val result = PublicMusicBrowsePage.collection(html, "tw", capabilities, "public-rooms", "6828493100")
        assertEquals("本週新發行", result.item.title)
        assertEquals("public-rooms", result.item.resourceType)
        assertEquals(100, result.shelves.single().items.size)
        assertEquals("6797444451", result.shelves.single().items.first().catalogId)
        assertEquals("/v1/editorial/tw/rooms/6828493100/contents?l=zh-Hant-TW&offset=100", result.nextPage)
        assertNull(result.shelves.single().seeAll) // The full grid must not link back to itself.
    }

    @Test fun legitimateEmptyOrFullyFilteredRoomKeepsItsHeaderWithoutWeakeningIdentityOrSchemaChecks() {
        val raw = root(fixture("/apple-music-public-room.html"))
        val model = raw.getJSONArray("data").getJSONObject(0)
        val data = model.getJSONObject("data")
        val section = data.getJSONArray("sections").getJSONObject(0)
        val video = JSONObject(section.getJSONArray("items").getJSONObject(0).toString()).also {
            it.getJSONObject("contentDescriptor").put("kind", "musicVideo")
        }
        data.remove("nextIntent")
        for (items in listOf(JSONArray(), JSONArray().put(video))) {
            section.put("items", items)
            val parsed = PublicMusicBrowsePage.collection(page(raw), "tw", capabilities, "public-rooms", "6828493100")
            assertEquals("本週新發行", parsed.item.title)
            assertTrue(parsed.shelves.isEmpty())
            assertEquals(List(2) { androidx.compose.ui.graphics.Color(0xFF24242A) }, parsed.item.palette)
            assertNull(parsed.nextPage)
            reject { PublicMusicBrowsePage.collection(page(raw), "tw", capabilities, "public-rooms", "1") }
            reject { PublicMusicBrowsePage.collection(page(raw), "us", capabilities, "public-rooms", "6828493100") }
        }
        section.getJSONObject("presentation").put("layout", "unknown-layout")
        reject { PublicMusicBrowsePage.collection(page(raw), "tw", capabilities, "public-rooms", "6828493100") }
        model.getJSONObject("intent").put("\$kind", "BrowsePageIntent")
        reject { PublicMusicBrowsePage.collection(page(raw), "tw", capabilities, "public-rooms", "6828493100") }
    }

    @Test fun emptyNewAndRadioFeedsStillFailInsteadOfPretendingTheSourceLoaded() {
        for (name in listOf("/apple-music-public-new.html", "/apple-music-public-radio.html")) {
            val raw = root(fixture(name))
            val sections = raw.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONArray("sections")
            for (index in 0 until sections.length()) sections.getJSONObject(index).put("items", JSONArray())
            if (name.contains("-new")) reject { PublicMusicBrowsePage.parse(page(raw), "tw", capabilities) }
            else reject { PublicMusicBrowsePage.parseRadio(page(raw), "tw", capabilities) }
        }
    }

    @Test fun emptyOrFilteredFirstRoomBatchRetainsTheSectionAndValidatedServerCursor() {
        val raw = root(fixture("/apple-music-public-room.html"))
        val data = raw.getJSONArray("data").getJSONObject(0).getJSONObject("data")
        val section = data.getJSONArray("sections").getJSONObject(0)
        val video = JSONObject(section.getJSONArray("items").getJSONObject(0).toString()).also {
            it.getJSONObject("contentDescriptor").put("kind", "musicVideo")
        }
        val intent = data.getJSONObject("nextIntent")
        val expectedCursor = intent.getString("url")
        for (items in listOf(JSONArray(), JSONArray().put(video))) {
            section.put("items", items)
            val parsed = PublicMusicBrowsePage.collection(page(raw), "tw", capabilities, "public-rooms", "6828493100")
            val retained = parsed.shelves.single()
            assertEquals("public-room:tw:${section.getString("id")}", retained.id)
            assertEquals("本週新發行", retained.title)
            assertTrue(retained.items.isEmpty())
            assertEquals(expectedCursor, parsed.nextPage)
            assertEquals(List(2) { androidx.compose.ui.graphics.Color(0xFF24242A) }, parsed.item.palette)
        }
        intent.put("url", "/v1/editorial/us/rooms/6828493100/contents?offset=100")
        reject { PublicMusicBrowsePage.collection(page(raw), "tw", capabilities, "public-rooms", "6828493100") }
    }

    @Test fun actualRadioShowPreservesStationEpisodesAndTheirFullRoomDestination() {
        val html = fixture("/apple-music-public-radio-show.html")
        val result = PublicMusicBrowsePage.collection(html, "tw", capabilities, "public-curators", "993271379")
        assertEquals("SOULECTION", result.item.title)
        val shelf = result.shelves.single()
        assertEquals(20, shelf.items.size)
        assertEquals(CatalogKind.Station, shelf.items.first().catalogKind)
        assertEquals("ra.6825060493", shelf.items.first().catalogId)
        assertEquals("6503380994", shelf.seeAll?.resourceId)
        assertEquals("https://music.apple.com/tw/curator/soulection/993271379", result.item.url)
    }

    @Test fun roomIdentityCanonicalUrlAndContinuationCannotCrossResourcesOrStorefronts() {
        val html = fixture("/apple-music-public-room.html")
        reject { PublicMusicBrowsePage.collection(html, "tw", capabilities, "public-rooms", "1") }
        reject { PublicMusicBrowsePage.collection(html, "us", capabilities, "public-rooms", "6828493100") }
        for (cursor in listOf("https://evil.example/v1/editorial/tw/rooms/6828493100/contents",
                "//evil.example/v1/editorial/tw/rooms/6828493100/contents",
                "/v1/editorial/us/rooms/6828493100/contents?offset=100",
                "/v1/editorial/tw/rooms/1/contents?offset=100",
                "/v1/editorial/tw/rooms/6828493100/contents#fragment")) {
            reject { PublicMusicBrowsePage.roomCursor(cursor, "tw", "6828493100") }
        }
        val data = root(html)
        data.getJSONArray("data").getJSONObject(0).getJSONObject("data").put("canonicalURL", "https://music.apple.com/tw/room/1")
        reject { PublicMusicBrowsePage.collection(page(data), "tw", capabilities, "public-rooms", "6828493100") }
    }

    @Test fun continuationResolvesOnlyDeclaredResourcesInServerOrderAndKeepsNextCursor() {
        fun identifier(id: String) = JSONObject().put("id", id).put("type", "songs")
        fun resource(id: String) = identifier(id).put("attributes", JSONObject().put("name", "Song $id"))
        val root = JSONObject().put("data", JSONArray().put(identifier("2")).put(identifier("1")))
            .put("resources", JSONObject().put("songs", JSONObject().put("1", resource("1")).put("2", resource("2"))
                .put("3", resource("3"))))
            .put("next", "/v1/editorial/tw/rooms/99/contents?offset=200")
        val request = "/v1/editorial/tw/rooms/99/contents?offset=100"
        val result = PublicMusicBrowsePage.roomContinuation(root, "tw", "99", request, { item ->
            Track(item.getString("id"), item.getJSONObject("attributes").getString("name"), "", "", 0,
                emptyList(), emptyList(), catalogId = item.getString("id"), resourceId = item.getString("id"), resourceType = "songs")
        }, capabilities)
        assertEquals(listOf("2", "1"), result.items.map { it.resourceId })
        assertEquals(root.getString("next"), result.nextPage)
        assertEquals(request, result.requestedPage)
        root.getJSONObject("resources").getJSONObject("songs").getJSONObject("1").put("id", "9")
        reject { PublicMusicBrowsePage.roomContinuation(root, "tw", "99", request, { null }, capabilities) }
    }

    @Test fun rejectedEditorialContinuationKeepsTheLoadedRoomAndRetryUsesTheSameCursor() = runBlocking {
        fun song(id: String) = Track(id, "Song $id", "", "", 0L, emptyList(), emptyList(),
            catalogId = id, resourceId = id, resourceType = "songs")
        val source = song("99").copy(catalogKind = CatalogKind.Page, resourceType = "public-rooms")
        val next = "/v1/editorial/tw/rooms/99/contents?offset=100"
        val initial = CatalogResourcePage((1..100).map { song(it.toString()) }, next, "https://music.apple.com/tw/room/99")
        val section = CatalogResourceSection("public:room:99", resourceTypes = setOf("songs"), initialPage = initial)
        val profile = CatalogResourceProfile(CatalogResourceRef("99", "public-rooms"), source, "Room", null, null,
            null, null, initial.requestedPage, emptyList(), listOf(section))
        val requests = mutableListOf<String?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CatalogPageBrowser(scope, source, { profile }, { _, value, cursor ->
            requests += cursor
            if (cursor == null) checkNotNull(value.initialPage)
            else if (requests.size == 2) throw CatalogRequestException(CatalogFailureReason.Unavailable, "Editorial unavailable", 401)
            else CatalogResourcePage(listOf(song("101")), null, cursor)
        })
        browser.ensure(section)
        browser.more(section)
        val failed = browser.state.value.sections.getValue(section.pageKey())
        assertEquals(100, failed.items.size)
        assertEquals(next, failed.next)
        assertTrue(failed.failed)
        browser.retry(section)
        val ready = browser.state.value.sections.getValue(section.pageKey())
        assertEquals(101, ready.items.size)
        assertNull(ready.next)
        assertFalse(ready.failed)
        assertEquals(listOf(null, next, next), requests)
        browser.close()
        scope.cancel()
    }

    @Test fun liveEditorialRestShapeDoesNotRequireTheWebApplicationsResourceMap() {
        val first = JSONObject().put("id", "123").put("type", "albums")
            .put("attributes", JSONObject().put("name", "Real REST album"))
        val request = "/v1/editorial/tw/rooms/99/contents?offset=100"
        val next = "/v1/editorial/tw/rooms/99/contents?offset=200"
        val root = JSONObject().put("data", JSONArray().put(first)).put("next", next)
        val result = PublicMusicBrowsePage.roomContinuation(root, "tw", "99", request, { item ->
            Track(item.getString("id"), item.getJSONObject("attributes").getString("name"), "", "", 0,
                emptyList(), emptyList(), catalogId = item.getString("id"), catalogKind = CatalogKind.Album,
                resourceId = item.getString("id"), resourceType = item.getString("type"))
        }, capabilities)
        assertEquals("Real REST album", result.items.single().title)
        assertEquals("123", result.items.single().resourceId)
        assertEquals(next, result.nextPage)
    }

    @Test fun exploreMoreKeepsTheFourRealRoomActionsWithoutInventingOtherPageKinds() {
        val root = root(fixture("/apple-music-public-new.html"))
        val sections = root.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONArray("sections")
        val explore = JSONObject(fixture("/apple-music-public-explore.json"))
        val index = (0 until sections.length()).first { sections.getJSONObject(it).optString("itemKind") == "linkComponentModel" }
        sections.put(index, explore)
        val shelf = PublicMusicBrowsePage.parse(page(root), "tw", capabilities).single { it.title == "探索更多" }
        assertEquals(listOf("6456176470", "6456176471", "6456176472", "6456176473"), shelf.items.map { it.resourceId })
        assertEquals(listOf("按類別瀏覽", "年代之聲", "心情與活動", "來自全球"), shelf.items.map { it.title })
        assertTrue(shelf.items.all { it.catalogKind == CatalogKind.Page && it.resourceType == "public-rooms" &&
            it.url == "https://music.apple.com/tw/room/${it.resourceId}" && it.palette.size == 2 && it.palette[0] == it.palette[1] })
        val first = explore.getJSONArray("items").getJSONObject(1).getJSONObject("segue").getJSONObject("destination")
            .getJSONObject("intent").getJSONObject("contentDescriptor")
        first.put("url", "/tw/room/1")
        val checked = PublicMusicBrowsePage.parse(page(root), "tw", capabilities).single { it.title == "探索更多" }
        assertEquals(3, checked.items.size)
        assertFalse(checked.items.any { it.resourceId == "6456176470" })
    }

    @Test fun theFourActualExploreRoomsOpenTheirRealCuratorsThroughTheExistingRestPageRoute() {
        val pages = JSONArray(fixture("/apple-music-public-explore-rooms.json"))
        val expected = mapOf("6456176470" to 43, "6456176471" to 9, "6456176472" to 27, "6456176473" to 56)
        for (index in 0 until pages.length()) {
            val source = pages.getJSONObject(index)
            val id = source.getJSONObject("intent").getString("id")
            val parsed = PublicMusicBrowsePage.collection(page(JSONObject().put("data", JSONArray().put(source))),
                "tw", capabilities, "public-rooms", id)
            assertEquals(expected.getValue(id), parsed.shelves.sumOf { it.items.size })
            assertTrue(parsed.shelves.flatMap { it.items }.all { item ->
                item.catalogKind == CatalogKind.Page && item.resourceType == "apple-curators" &&
                    item.resourceId == item.catalogId && item.title.isNotBlank() && item.artworkUrl != null &&
                    item.palette.size >= 2 && item.url?.startsWith("https://music.apple.com/tw/curator/") == true
            })
        }
    }
}
