package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class AppleMusicShareUrlTest {
    @Test fun canonicalItemLinksPreserveTheirExactDestination() {
        for (url in listOf(
            "https://music.apple.com/tw/album/positions/1553944254?i=1553944486",
            "https://music.apple.com/tw/song/歌名/123?l=zh-Hant-TW",
            "https://music.apple.com/us/artist/name/123",
            "https://music.apple.com/tw/playlist/name/pl.abc123",
            "https://music.apple.com/library/playlist/p.owned",
            "https://music.apple.com/tw/station/episode-752/ra.6825060493",
            "https://music.apple.com/tw/music-video/name/123",
        )) assertEquals(url, appleMusicShareUrl(url))
    }

    @Test fun unsafeFakeOrMissingDestinationsCannotBeSharedOrCopied() {
        for (url in listOf(
            "javascript:alert(1)",
            "http://music.apple.com/tw/song/name/123",
            "https://music.apple.com.evil.test/tw/song/name/123",
            "https://evil.test@music.apple.com/tw/song/name/123",
            "https://music.apple.com:443/tw/song/name/123",
            "https://music.apple.com./tw/song/name/123",
            "https://music.apple.com/tw/song/name/123?token=secret",
            "https://music.apple.com/tw/song/name/123?redirect=https%3A%2F%2Fevil.test",
            "https://music.apple.com/tw/album/name/123?i=123&i=456",
            "https://music.apple.com/tw/song/name/123#music-user-token=secret",
            "https://music.apple.com/tw/station/name/pl.fake",
            "https://music.apple.com/tw/music-video/name/ra.fake",
            "https://music.apple.com/tw/../song/name/123",
            "https://music.apple.com/tw/song/name%2Fother/123",
            "https://music.apple.com/tw/song/name%0Aother/123",
            "https://music.apple.com/v1/me/favorites",
            "https://music.apple.com//tw/song/name/123",
            "https://music.apple.com/tw/song/name/123 ",
            "p.owned", "123", "",
        )) assertNull("Unsafe sharing destination: $url", appleMusicShareUrl(url))
        assertNull(appleMusicShareUrl(null))
    }

    @Test fun sharingRoutesDoNotExpandManagementRoutes() {
        assertNull(appleMusicManagementUrl("https://music.apple.com/tw/station/episode-752/ra.6825060493"))
        assertNull(appleMusicManagementUrl("https://music.apple.com/tw/music-video/name/123"))
    }
}
