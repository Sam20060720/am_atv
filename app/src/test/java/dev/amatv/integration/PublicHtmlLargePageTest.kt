package dev.amatv.integration

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Same compact base fixtures and stress dimensions as the benchmark's on-device ICU probe. */
class PublicHtmlLargePageTest {
    private val fixture = JSONObject(sequenceOf(
        File("src/benchmark/assets/public-html-stress-cases.json"),
        File("app/src/benchmark/assets/public-html-stress-cases.json"),
    ).first { it.isFile }.readText())
    private val capabilities = CatalogPresentationCapabilities(
        NativeMediaCapabilities(songItemType = 1, albumContainerType = 2, playlistContainerType = 3),
        stationWebPlaybackAvailable = true,
    )

    private fun parse(kind: String, html: String): Any = when (kind) {
        "new" -> PublicMusicBrowsePage.parse(html, "tw", capabilities)
        "radio" -> PublicMusicBrowsePage.parseRadio(html, "tw", capabilities)
        "editorial" -> PublicEditorialMotionPage.parse(html, "tw", "album", "1553944254")
        else -> error("Unknown fixture kind")
    }

    private fun large(html: String): String {
        val noise = buildString {
            repeat(fixture.getInt("noise_count")) { index ->
                append("<script data-noise='").append(index).append("' src='ignored.js'></script>")
                append("<img src='https://is1-ssl.mzstatic.com/image/thumb/noise/")
                    .append(index).append("/632x632cc.webp'>")
            }
        }
        val target = fixture.getInt("target_html_bytes")
        val padding = target - html.toByteArray(Charsets.UTF_8).size - noise.length - 7
        require(padding > 0)
        val at = html.lastIndexOf("</body>")
        return html.substring(0, at) + noise + "<!--" + "x".repeat(padding) + "-->" + html.substring(at)
    }

    @Test fun manyScriptAndImageMatchesNearFourMiBPreserveNewRadioAndEditorialResults() {
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val kind = case.getString("kind")
            val base = case.getString("html")
            val expanded = large(base)
            assertEquals(fixture.getInt("target_html_bytes"), expanded.toByteArray(Charsets.UTF_8).size)
            val baseline = parse(kind, base)
            assertTrue("Empty $kind baseline would not exercise a valid parser", (baseline as List<*>).isNotEmpty())
            assertEquals("$kind output changed while iterating unrelated large-page matches", baseline, parse(kind, expanded))
        }
    }

    @Test fun largePagesStillRejectDuplicateServerPayloadsMissingCloseAndUnchangedSizeLimits() {
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val kind = case.getString("kind")
            val base = case.getString("html")
            val opening = base.indexOf("<script TYPE=")
            val closing = base.indexOf("</script>", opening)
            val duplicate = base.substring(opening, closing + "</script>".length)
            val missingClose = base.substring(opening, closing)
            fun rejects(input: String) {
                try { parse(kind, input); fail("$kind accepted malformed or oversized input") }
                catch (_: IllegalArgumentException) { }
            }
            rejects(large(base.replace("</body>", duplicate + "</body>")))
            rejects(missingClose)
            rejects(base + "x".repeat(PublicMusicBrowsePage.MAX_HTML_BYTES))
            val excessiveJson = base.substring(0, opening) +
                "<script type='application/json' id='serialized-server-data'>" +
                " ".repeat(PublicMusicBrowsePage.MAX_JSON_BYTES + 1) + "</script></body></html>"
            rejects(excessiveJson)
        }
    }

    @Test fun reusedImageMatcherRetainsNearestSquareAndFirstEqualDistanceInDocumentOrder() {
        val base = fixture.getJSONArray("cases").getJSONObject(0).getString("html")
            .replace("\"width\":640,\"height\":640", "\"width\":4320,\"height\":1080")
        val prefix = "https://is1-ssl.mzstatic.com/image/thumb/primary/"
        val urls = "<img src='${prefix}632x632first.webp'><img src='${prefix}648x648second.webp'>"
        val result = PublicMusicBrowsePage.parse(large(base.replace("</body>", urls + "</body>")), "tw", capabilities)
        assertEquals(prefix + "632x632first.webp", result.single().items.single().artworkUrl)
    }

    @Test fun oneNearFourMiBOpeningTagWithThousandsOfAttributesPreservesAllThreeParserOutputs() {
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val kind = case.getString("kind")
            val base = case.getString("html")
            val extra = buildString {
                repeat(2_048) { append("data-noise-").append(it).append("='ignored' ") }
                append("data-padding='")
            }
            val padding = fixture.getInt("target_html_bytes") - base.toByteArray(Charsets.UTF_8).size - extra.length - 2
            val giantTag = base.replace("<script TYPE=", "<script " + extra + "x".repeat(padding) + "' TYPE=")
            assertEquals(fixture.getInt("target_html_bytes"), giantTag.toByteArray(Charsets.UTF_8).size)
            assertEquals(parse(kind, base), parse(kind, giantTag))
        }
    }
}
