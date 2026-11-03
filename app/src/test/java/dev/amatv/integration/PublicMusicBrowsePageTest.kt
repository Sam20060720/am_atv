package dev.amatv.integration

import dev.amatv.model.CatalogKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicMusicBrowsePageTest {
    private val html = checkNotNull(javaClass.getResource("/apple-music-public-new.html")).readText()
    private val capabilities = CatalogPresentationCapabilities(NativeMediaCapabilities(
        songItemType = 1, albumContainerType = 2, playlistContainerType = 3,
    ))
    private val marker = "<script type=\"application/json\" id=\"serialized-server-data\">"
    private fun root() = JSONObject(html.substringAfter(marker).substringBefore("</script>"))
    private fun page(data: JSONObject) = html.substringBefore(marker) + marker + data.toString() + "</script></body></html>"
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected a public Browse parse failure") }
        catch (expected: PublicMusicBrowseParseException) { assertTrue(expected.message!!.isNotBlank()) }
    }

    @Test fun actualPublicModelsRetainSectionOrderingCanonicalItemsAndOnlyProvenSquareRenditions() {
        val shelves = PublicMusicBrowsePage.parse(html, "tw", capabilities)
        assertEquals(listOf("新歌精選", "本週新發行", "近期推出", "歌單已更新", "心驚膽跳的季節：必聽精選",
            "現正流行", "樂迷熱播", "每日 Top 100", "城市排行榜", "即將推出"), shelves.map { it.title })
        assertEquals(listOf(17, 20, 20, 20, 19, 20, 20, 7, 7, 20), shelves.map { it.items.size })
        assertEquals(170, shelves.sumOf { it.items.size })
        assertTrue(shelves.none { it.featured })
        // Exercise the artwork contract even before remote thumbnails load.
        assertTrue(shelves.flatMap { it.items }.all { it.palette.size >= 2 && it.palette[0] == it.palette[1] })
        val song = shelves.first().items.first()
        assertEquals("6827357438", song.catalogId)
        assertEquals("songs", song.resourceType)
        assertEquals("Here You Come, There You Go", song.title)
        assertEquals("Gracie Abrams", song.artist)
        assertEquals(0L, song.durationMs)
        assertTrue(song.lyrics.isEmpty())
        // Compact web track rows render 48/96px thumbnails; use their real
        // square source template for TV instead of upscaling that small srcset.
        assertTrue(song.artworkUrl!!.endsWith("/640x640bb.jpg"))
        val album = shelves[1].items.first()
        assertEquals("Sessions", album.title)
        assertEquals("Zara Larsson", album.artist)
        assertEquals(CatalogKind.Album, album.catalogKind)
        assertEquals("6826021911", album.resourceId)
        assertEquals("pl.3de89e62aa3340038e08fa325c3f3f01", shelves[3].items.first().resourceId)
        // The dictionary is 4320x1080; this exact square URL was rendered by Apple.
        assertTrue(shelves[7].items.first().artworkUrl!!.endsWith("/632x632cc.webp"))
    }

    @Test fun selectsOnlyRequestedBrowseIntentAndIgnoresActionsWhileCapabilitiesHideUnusableContent() {
        val data = root()
        val selected = data.getJSONArray("data").getJSONObject(0)
        val foreign = JSONObject(selected.toString())
        foreign.getJSONObject("intent").put("storefront", "us")
        foreign.getJSONObject("data").getJSONArray("sections").getJSONObject(1)
            .getJSONObject("header").getJSONObject("item").getJSONObject("titleLink").put("title", "Wrong storefront")
        val song = selected.getJSONObject("data").getJSONArray("sections").getJSONObject(1).getJSONArray("items").getJSONObject(0)
        song.put("id", "not-a-catalog-id").put("playAction", JSONObject("""{"items":[{"contentDescriptor":{"kind":"song","identifiers":{"storeAdamID":"999"}}}]}"""))
        data.getJSONArray("data").put(foreign)
        val shelves = PublicMusicBrowsePage.parse(page(data), "tw", capabilities)
        assertEquals("新歌精選", shelves.first().title)
        assertEquals("6827357438", shelves.first().items.first().catalogId)
        assertFalse(shelves.flatMap { it.items }.any { it.catalogKind in setOf(CatalogKind.Station, CatalogKind.MusicVideo) })
        val stationCapable = capabilities.copy(native = capabilities.native.copy(stationContainerType = 4))
        val withRadio = PublicMusicBrowsePage.parse(page(data), "tw", stationCapable)
        assertEquals(11, withRadio.size)
        assertEquals(20, withRadio.single { it.title == "最新電台節目" }.items.size)
        assertEquals("ra.6823318279", withRadio.single { it.title == "最新電台節目" }.items.first().resourceId)
    }

    @Test fun rejectsSchemaStorefrontAndByteLimitsRatherThanReturningAnUnrelatedOrEmptyFeed() {
        reject { PublicMusicBrowsePage.parse(html, "us", capabilities) }
        reject { PublicMusicBrowsePage.parse("<script>not server models</script>", "tw", capabilities) }
        val data = root()
        data.getJSONArray("data").getJSONObject(0).getJSONObject("data").put("sections", "not an array")
        reject { PublicMusicBrowsePage.parse(page(data), "tw", capabilities) }
        reject { PublicMusicBrowsePage.parse("音".repeat(PublicMusicBrowsePage.MAX_HTML_BYTES / 3 + 1), "tw", capabilities) }
        reject { PublicMusicBrowsePage.parse(marker + " ".repeat(PublicMusicBrowsePage.MAX_JSON_BYTES + 1) + "</script>", "tw", capabilities) }
    }

    @Test fun doesNotFetchForeignArtworkOrTurnAnUnrenderedBannerIntoASquareCard() {
        val data = root()
        val tracks = data.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONArray("sections")
            .getJSONObject(1).getJSONArray("items")
        tracks.getJSONObject(0).getJSONObject("artwork").getJSONObject("dictionary")
            .put("url", "https://is1-ssl.mzstatic.com.evil.example/image/thumb/{w}x{h}{c}.{f}")
        val omittedId = tracks.getJSONObject(1).getJSONObject("contentDescriptor").getJSONObject("identifiers").getString("storeAdamID")
        tracks.getJSONObject(1).getJSONObject("artwork").getJSONObject("dictionary")
            .put("width", 4320).put("height", 1080)
            .put("url", "https://is1-ssl.mzstatic.com/image/thumb/unrendered/banner/{w}x{h}{c}.{f}")
        val items = PublicMusicBrowsePage.parse(page(data), "tw", capabilities).first().items
        assertNull(items.first().artworkUrl)
        assertFalse(items.any { it.resourceId == omittedId })
        assertTrue(items.drop(1).all { it.artworkUrl?.startsWith("https://") == true })
    }
}
