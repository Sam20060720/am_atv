package dev.amatv.integration.audio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssetPlaybackSessionTest {
    private fun params(id: String = "123") = JSONObject().put("id", id).put("kind", "song")
    private fun asset() = JSONObject()
        .put("url", "https://audio.itunes.apple.com/master.m3u8")
        .put("keyServerUrl", "https://play.itunes.apple.com/license")
        .put("widevineKeyCertificateUrl", "https://play.itunes.apple.com/cert")

    @Test fun bindsTheActualRequestAndResponseWithoutInventingAResponseSongId() {
        val session = (AssetPlaybackSession.fromPlayAssetsResponse("123", params(), asset())
            as AssetSessionResolution.Matched).session
        assertTrue(session.matchesLicenseContext("123", "https://play.itunes.apple.com/license"))
        assertFalse(session.matchesLicenseContext("456", session.licenseEndpoint))
        assertFalse(session.toString().contains("https"))
    }

    @Test fun rejectsAConflictingRequestOrResponseSongIdentity() {
        assertEquals(AssetSessionResolution.Rejected(AssetSessionRejection.IdentityMismatch),
            AssetPlaybackSession.fromPlayAssetsResponse("123", params("456"), asset()))
        assertEquals(AssetSessionResolution.Rejected(AssetSessionRejection.IdentityMismatch),
            AssetPlaybackSession.fromPlayAssetsResponse("123", params(), asset().put("songId", "456")))
    }

    @Test fun missingPlaybackContextCannotBorrowFromCatalogMetadata() {
        val raw = asset().apply { remove("keyServerUrl") }
        assertEquals(AssetSessionResolution.Rejected(AssetSessionRejection.MissingLicenseContext),
            AssetPlaybackSession.fromPlayAssetsResponse("123", params(), raw))
    }

    @Test fun rejectsUntrustedOrCredentialBearingAssetUrls() {
        for (url in listOf("http://audio.itunes.apple.com/master.m3u8", "https://apple.com.evil.test/master.m3u8",
            "https://user@audio.itunes.apple.com/master.m3u8")) {
            assertEquals(AssetSessionResolution.Rejected(AssetSessionRejection.InvalidManifest),
                AssetPlaybackSession.fromPlayAssetsResponse("123", params(), asset().put("url", url)))
        }
    }
}
