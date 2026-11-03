package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicMusicRadioPageTest {
    private val html = checkNotNull(javaClass.getResource("/apple-music-public-radio.html")).readText()
    private val marker = "<script type=\"application/json\" id=\"serialized-server-data\">"
    private val nativeSongs = NativeMediaCapabilities(songItemType = 1)
    private val webRadio = CatalogPresentationCapabilities(nativeSongs, stationWebPlaybackAvailable = true)
    private fun root() = JSONObject(html.substringAfter(marker).substringBefore("</script>"))
    private fun page(root: JSONObject) = marker + root.toString() + "</script>"

    @Test fun publicRadioRetainsRealLiveEpisodeAndBroadcastOrderWithoutUnsupportedInterviews() {
        val shelves = PublicMusicBrowsePage.parseRadio(html, "tw", webRadio)
        assertEquals(listOf("線上電台免費收聽", "立即訂閱，播放單集", "廣播電台"), shelves.map { it.title })
        assertEquals(listOf(6, 20, 18), shelves.map { it.items.size })
        assertEquals("Apple Music Club", shelves.first().items.first().title)
        assertEquals("ra.1740613859", shelves.first().items.first().catalogId)
        assertEquals("Fontaines D.C.", shelves[1].items.first().title)
        assertEquals("BBC Radio London", shelves[2].items.first().title)
        assertTrue(shelves.all { it.id.startsWith("public-radio:tw:") })
        assertTrue(shelves.flatMap { it.items }.all {
            it.catalogKind == CatalogKind.Station && it.resourceType == "stations" &&
                it.resourceId == it.catalogId && it.artworkUrl?.startsWith("https://") == true &&
                it.url?.startsWith("https://music.apple.com/tw/station/") == true
        })
        assertFalse(webRadio.native.catalogStations)
        assertTrue(webRadio.supports(Destination.Radio))
        assertTrue(shelves.flatMap { it.items }.all { it.durationMs == 0L && it.lyrics.isEmpty() })
    }

    @Test fun unsupportedStationsStayHiddenAndRadioCannotParseNewOrForeignIntent() {
        assertFalse(CatalogPresentationCapabilities(nativeSongs).supports(Destination.Radio))
        assertTrue(runCatching { PublicMusicBrowsePage.parseRadio(html, "tw", CatalogPresentationCapabilities(nativeSongs)) }.isFailure)
        assertTrue(runCatching { PublicMusicBrowsePage.parse(html, "tw", webRadio) }.isFailure)
        assertTrue(runCatching { PublicMusicBrowsePage.parseRadio(html, "us", webRadio) }.isFailure)
        val root = root()
        root.getJSONArray("data").getJSONObject(0).getJSONObject("intent").put("\$kind", "BrowsePageIntent")
        assertTrue(runCatching { PublicMusicBrowsePage.parseRadio(page(root), "tw", webRadio) }.isFailure)
    }

    @Test fun liveLabelsComeFromTheOfficialCardAndActionsCannotInjectResources() {
        val root = root()
        val live = root.getJSONArray("data").getJSONObject(0).getJSONObject("data")
            .getJSONArray("sections").getJSONObject(0).getJSONArray("items")
        live.getJSONObject(0).remove("accessibilityLabel")
        live.getJSONObject(0).put("playAction", JSONObject("""{"title":"Injected station","contentDescriptor":{"identifiers":{"storeAdamID":"ra.evil"}}}"""))
        live.getJSONObject(1).getJSONObject("contentDescriptor").getJSONObject("identifiers").put("storeAdamID", "../not-a-station")
        val items = PublicMusicBrowsePage.parseRadio(page(root), "tw", webRadio).first().items
        assertEquals(4, items.size)
        assertFalse(items.any { it.title == "Injected station" || it.catalogId == "ra.evil" })
    }

    @Test fun nonSquareStationCoversRetainTheirSourceAspectWithoutInventingSquareCrops() {
        val items = PublicMusicBrowsePage.parseRadio(html, "tw", webRadio).flatMap { it.items }
        val banner = items.single { it.title == "To Céline, with Love" }
        assertTrue(banner.artworkUrl!!.endsWith("/640x160bb.jpg"))
        val portrait = items.single { it.title == "UFO Radio" }
        assertTrue(portrait.artworkUrl!!.endsWith("/640x716bb.jpg"))
        assertEquals(44, items.size)
    }

    @Test fun newPublicEditorialIdentitiesAndLabelsComeFromResponseInsteadOfFixedStationCatalog() {
        val root = root()
        val sections = root.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONArray("sections")
        val live = sections.getJSONObject(0).getJSONArray("items")
        val station = live.getJSONObject(0)
        station.put("accessibilityLabel", "A Newly Published Live Station")
        station.getJSONObject("contentDescriptor").getJSONObject("identifiers").put("storeAdamID", "ra.newly-published")
        // Server removals also disappear; the app must not repopulate an old
        // fixed six-station list or treat a previous fixture count as a limit.
        live.remove(1)
        val items = PublicMusicBrowsePage.parseRadio(page(root), "tw", webRadio).first().items
        assertEquals(5, items.size)
        assertEquals("A Newly Published Live Station", items.first().title)
        assertEquals("ra.newly-published", items.first().catalogId)
        assertFalse(items.any { it.catalogId == "ra.1740613859" })
    }
}
