package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicResourceUrlTest {
    @Test fun commonCanonicalResourcesPreserveTheirExactDestination() {
        for (url in listOf(
            "https://music.apple.com/tw/album/positions/1553944254?i=1553944486",
            "https://music.apple.com/us/artist/name/123",
            "https://music.apple.com/tw/song/歌名/123?l=zh-Hant-TW",
            "https://music.apple.com/tw/playlist/name/pl.abc123",
            "https://music.apple.com/library/playlist/p.owned",
        )) {
            assertEquals("Management destination: $url", url, appleMusicManagementUrl(url))
            assertEquals("Sharing destination: $url", url, appleMusicShareUrl(url))
        }
    }

    @Test fun unsafeAndMissingDestinationsAreRejectedByBothRoutes() {
        for (url in listOf(
            null, "", "123", "p.owned",
            "javascript:alert(1)",
            "http://music.apple.com/tw/song/name/123",
            "https://music.apple.com.evil.test/tw/song/name/123",
            "https://evil.test@music.apple.com/tw/song/name/123",
            "https://music.apple.com:443/tw/song/name/123",
            "https://music.apple.com./tw/song/name/123",
            "https://music.apple.com/tw/song/name/123?token=secret",
            "https://music.apple.com/tw/song/name/123?redirect=https%3A%2F%2Fevil.test",
            "https://music.apple.com/tw/album/name/123?i=123&i=456",
            "https://music.apple.com/tw/album/name/123?%74oken=secret",
            "https://music.apple.com/tw/album/name/123?i=secret",
            "https://music.apple.com/tw/song/name/123#music-user-token=secret",
            "https://music.apple.com/tw/search?term=name",
            "https://music.apple.com/v1/me/favorites",
            "https://music.apple.com/tw/../album/name/123",
            "https://music.apple.com/tw/../song/name/123",
            "https://music.apple.com/tw/%2e%2e/album/name/123",
            "https://music.apple.com/tw/song/name%2Fother/123",
            "https://music.apple.com/tw/song/name%0Aother/123",
            "https://music.apple.com//tw/album/name/123",
            "https://music.apple.com//tw/song/name/123",
            "https://music.apple.com/tw/artist/name/pl.fake",
            "https://music.apple.com/tw/station/name/pl.fake",
            "https://music.apple.com/tw/music-video/name/ra.fake",
            "https://music.apple.com/tw/album/name/123 ",
            "https://music.apple.com/tw/song/name/123 ",
        )) {
            assertNull("Unsafe management destination: $url", appleMusicManagementUrl(url))
            assertNull("Unsafe sharing destination: $url", appleMusicShareUrl(url))
        }
    }

    @Test fun sharingOnlyResourcesNeverBecomeManagementDestinations() {
        for (url in listOf(
            "https://music.apple.com/tw/station/episode-752/ra.6825060493",
            "https://music.apple.com/tw/music-video/name/123",
        )) {
            assertEquals(url, appleMusicShareUrl(url))
            assertNull(appleMusicManagementUrl(url))
        }
    }
}
