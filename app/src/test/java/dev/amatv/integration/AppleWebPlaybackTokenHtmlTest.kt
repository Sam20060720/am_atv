package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class AppleWebPlaybackTokenHtmlTest {
    @Test fun assetPathsKeepSourceOrderDecodedDuplicatesAndOriginalMatchingRules() {
        val html = """
            <script src='/assets/first.js'></script>
            <script src="https://music.apple.com/assets/second&amp;extra.js"></script>
            <script src='https://music.apple.com/assets/second&extra.js'></script>
            <script src='/assets/first.js'></script>
            <script SRC='/assets/not-previously-supported.js'></script>
            <script src='/assets/not-a-complete.js?query=1'></script>
            <script src='/other/not-an-asset.js'></script>
        """.trimIndent()
        assertEquals(listOf("/assets/first.js", "https://music.apple.com/assets/second&extra.js"),
            AppleWebPlaybackTokenClient().extractAssetPaths(html))
    }

    @Test fun multiMiBNetworkHtmlWithThousandsOfAssetsUsesTheSameBoundedOutputContract() {
        val html = buildString {
            append("<html><!--").append("x".repeat(4 * 1024 * 1024)).append("-->")
            repeat(2_048) { index -> append("<script src='/assets/").append(index).append(".js'></script>") }
            append("<script src='/assets/0.js'></script><script src='unterminated/assets/no.js")
            append("</html>")
        }
        assertEquals((0 until 2_048).map { "/assets/$it.js" }, AppleWebPlaybackTokenClient().extractAssetPaths(html))
    }
}
