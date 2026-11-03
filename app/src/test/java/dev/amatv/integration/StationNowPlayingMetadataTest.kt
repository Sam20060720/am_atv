package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StationNowPlayingMetadataTest {
    private val station = Track("station-anchor", "Station", "Station curator", "Radio", 0L,
        listOf(Color.Black, Color.Gray), emptyList(), catalogId = "ra.123", catalogKind = CatalogKind.Station,
        resourceId = "ra.123", resourceType = "stations", artworkUrl = "https://is1-ssl.mzstatic.com/image/station.jpg",
        motionArtworkUrl = "https://video.mzstatic.com/station.m3u8")

    @Test fun realSongPresentationRetainsStationIdentityForTransportAndQueue() {
        val current = StationNowPlayingMetadata.fromReport(JSONObject()
            .put("title", "Song").put("artist", "Performer").put("album", "Album")
            .put("artworkUrl", "https://is1-ssl.mzstatic.com/image/thumb/song/{w}x{h}bb.{f}"))
            .present(station, 0L)
        assertEquals("Song", current.title)
        assertEquals("Performer", current.artist)
        assertEquals("Album", current.album)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/song/600x600bb.jpg", current.artworkUrl)
        assertEquals(station.id, current.id)
        assertEquals(station.catalogId, current.catalogId)
        assertEquals(station.resourceId, current.resourceId)
        assertEquals(CatalogKind.Station, current.catalogKind)
        assertEquals(0L, current.durationMs)
        assertNull(current.motionArtworkUrl)
    }

    @Test fun textOnlyTimedMetadataUsesStationCoverAndDoesNotInventSongIdentity() {
        val current = StationNowPlayingMetadata.fromReport(JSONObject().put("title", "Live song").put("artist", "Artist"))
            .present(station, 0)
        assertEquals("Live song", current.title)
        assertEquals(station.artworkUrl, current.artworkUrl)
        assertEquals(station.catalogId, current.catalogId)
    }

    @Test fun absentOrEmptyProgrammeMetadataFallsBackToStation() {
        for (data in listOf(JSONObject(), JSONObject().put("title", JSONObject.NULL).put("artist", "  "))) {
            assertEquals(station, StationNowPlayingMetadata.fromReport(data).present(station, 0))
        }
    }

    @Test fun newMetadataWithoutArtworkCannotKeepThePreviousSongCover() {
        val first = StationNowPlayingMetadata.fromReport(JSONObject().put("title", "One")
            .put("artworkUrl", "https://is1-ssl.mzstatic.com/image/one.jpg")).present(station, 0)
        val second = StationNowPlayingMetadata.fromReport(JSONObject().put("title", "Two")).present(station, 0)
        assertNotEquals(first.artworkUrl, second.artworkUrl)
        assertEquals(station.artworkUrl, second.artworkUrl)
        assertEquals("Two", second.title)
    }

    @Test fun unsafeOrMalformedArtworkNeverLeavesTheStationCover() {
        for (url in listOf("javascript:alert(1)", "http://is1-ssl.mzstatic.com/image/x.jpg",
            "https://is1-ssl.mzstatic.com.evil.test/image/x.jpg", "https://user@is1-ssl.mzstatic.com/image/x.jpg",
            "https://is1-ssl.mzstatic.com:444/image/x.jpg", "https://is1-ssl.mzstatic.com/not-an-image/x.jpg")) {
            assertEquals(url, station.artworkUrl,
                StationNowPlayingMetadata.fromReport(JSONObject().put("artworkUrl", url)).present(station, 0).artworkUrl)
        }
    }

    @Test fun invalidTextDoesNotBecomeNullLiteralOrUnboundedUiContent() {
        val data = JSONObject().put("title", JSONObject.NULL).put("artist", "x".repeat(513)).put("album", "bad\u0000label")
        assertEquals(station, StationNowPlayingMetadata.fromReport(data).present(station, -1))
    }
}
