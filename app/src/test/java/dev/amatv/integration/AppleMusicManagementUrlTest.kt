package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class AppleMusicManagementUrlTest {
    @Test fun actualCanonicalResourcesAndTheirSongSelectorArePreservedExactly() {
        for (url in listOf(
            "https://music.apple.com/tw/album/positions/1553944254?i=1553944486",
            "https://music.apple.com/us/artist/name/123",
            "https://music.apple.com/tw/song/歌名/123?l=zh-Hant-TW",
            "https://music.apple.com/tw/playlist/name/pl.abc123",
            "https://music.apple.com/library/playlist/p.owned",
        )) assertEquals(url, appleMusicManagementUrl(url))
    }

    @Test fun credentialsTokensExternalOriginsAndNonResourceLinksNeverBecomeQrDestinations() {
        for (url in listOf(
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
            "https://music.apple.com/tw/%2e%2e/album/name/123",
            "https://music.apple.com/tw/song/name%2Fother/123",
            "https://music.apple.com//tw/album/name/123",
            "https://music.apple.com/tw/artist/name/pl.fake",
            "https://music.apple.com/tw/album/name/123 ",
        )) assertNull("Unsafe destination: $url", appleMusicManagementUrl(url))
        assertNull(appleMusicManagementUrl(null))
    }

    @Test fun aMissingCanonicalLinkIsNeverReconstructedFromAResourceId() {
        assertNull(appleMusicManagementUrl("p.owned"))
        assertNull(appleMusicManagementUrl("123"))
        assertNull(appleMusicManagementUrl(""))
    }
}
