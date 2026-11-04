package dev.amatv.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YtmRadioParsingTest {
    private val client = YtmInnertubeClient(country = { "tw" })

    @Test fun requestCountryTracksTheCurrentStorefrontInsteadOfAConstant() = kotlinx.coroutines.runBlocking {
        var storefront = "jp"
        val regional = YtmInnertubeClient(country = { storefront })
        assertEquals("JP", regional.requestContext().getJSONObject("client").getString("gl"))
        storefront = "us"
        assertEquals("US", regional.requestContext().getJSONObject("client").getString("gl"))
    }
    private fun song(id: String) = """{"playlistPanelVideoRenderer":{
      "videoId":"$id","title":{"simpleText":"Song $id"},
      "longBylineText":{"simpleText":"Artist"},"lengthText":{"simpleText":"3:00"}}}"""

    @Test fun upNextPanelExcludesRelatedTabSongsAndUnrelatedContinuation() {
        val root = JSONObject("""{
          "related":{"contents":[${song("related0001")}],"continuations":[{"nextContinuationData":{"continuation":"wrong"}}]},
          "contents":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{
            "contents":[${song("dQw4w9WgXcQ")},${song("queued00001")}],
            "continuations":[{"nextRadioContinuationData":{"continuation":"correct"}}]
          }}}}
        }""")
        val page = client.parseRadioResponse(root, "dQw4w9WgXcQ")
        assertEquals(listOf("queued00001"), page.songs.map { it.videoId })
        assertEquals("correct", page.continuation)
    }

    @Test fun continuationPageReadsOnlyItsQueueAndSupportsCommandCursors() {
        val root = JSONObject("""{"continuationContents":{"playlistPanelContinuation":{
          "contents":[${song("queued00002")},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-command"}}}}]
        }},"related":{"contents":[${song("related0002")}]}}""")
        val page = client.parseRadioResponse(root, "dQw4w9WgXcQ")
        assertEquals(listOf("queued00002"), page.songs.map { it.videoId })
        assertEquals("next-command", page.continuation)
    }

    @Test fun unrecognizedQueueCannotBecomeAnUnrelatedRadio() {
        val page = client.parseRadioResponse(JSONObject("""{"related":{"contents":[${song("related0003")}]}}"""), "dQw4w9WgXcQ")
        assertTrue(page.songs.isEmpty())
        assertNull(page.continuation)
    }
}
