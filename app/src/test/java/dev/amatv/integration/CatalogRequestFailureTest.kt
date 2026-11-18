package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogRequestFailureTest {
    private val lines = listOf(LyricLine(listOf(LyricWord("hello", 0L, 1_000L))))

    private fun failure(status: Int) =
        CatalogRequestException(catalogHttpFailure(status), "HTTP $status", status)

    private fun unavailableEquivalent() = JSONObject("""{
      "data":[],"meta":{"filters":{"equivalents":{"retired":[]}}}
    }""")

    private fun resolve(root: JSONObject): String = requireEquivalentCatalogSongId(
        root, "retired", unavailableMessage = "Unavailable", transientMessage = "Try again",
    )

    @Test fun catalogHttpStatusControlsPlaybackRecoveryWithoutInterpretingNativeCodes() {
        for (status in listOf(404, 410)) {
            assertEquals(PlaybackFailureKind.TrackUnavailable,
                classifyPlaybackFailure(null, null, null, failure(status)))
        }
        for (status in listOf(401, 403)) {
            assertEquals(PlaybackFailureKind.Authorization,
                classifyPlaybackFailure(null, null, null, failure(status)))
        }
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals(PlaybackFailureKind.Network,
                classifyPlaybackFailure(null, null, null, failure(status)))
        }
        // Reflection wrappers keep the service's verdict; bare SDK codes still
        // have no HTTP meaning (covered separately by NativePlaybackIntentTest).
        assertEquals(PlaybackFailureKind.Authorization, classifyPlaybackFailure(null, null, null,
            java.lang.reflect.InvocationTargetException(failure(403))))
    }

    @Test fun explicitEmptyEquivalenceIsUnavailableForPlaybackAndLyrics() = runBlocking {
        var calls = 0
        val result = requestLyricsWithRetry(fetch = {
            calls++
            resolve(unavailableEquivalent())
            lines
        }, wait = { fail("A confirmed unavailable song must not busy-retry") })
        assertEquals(1, calls)
        assertEquals(LyricsRequestResult.Unavailable, result)
        try {
            resolve(unavailableEquivalent())
            fail("Empty equivalence must throw its unavailable verdict")
        } catch (error: CatalogRequestException) {
            assertEquals(CatalogFailureReason.Unavailable, error.reason)
            assertEquals(PlaybackFailureKind.TrackUnavailable,
                classifyPlaybackFailure(null, null, null, error))
        }
    }

    @Test fun incompleteEquivalenceIsRetryableRatherThanSkippingAnUnknownRecording() {
        val malformed = listOf(
            JSONObject("""{"data":[]}"""),
            JSONObject("""{"data":[],"meta":{"filters":{"equivalents":{
              "retired":[{"id":"current","type":"songs"}]
            }}}}"""),
            JSONObject("""{"data":[],"meta":{"filters":{"equivalents":{
              "retired":[{"id":"a","type":"songs"},{"id":"b","type":"songs"}]
            }}}}"""),
        )
        for (root in malformed) {
            try {
                resolve(root)
                fail("Incomplete or conflicting equivalence must not choose a song")
            } catch (error: CatalogRequestException) {
                assertEquals(CatalogFailureReason.Transient, error.reason)
                assertEquals(PlaybackFailureKind.Network,
                    classifyPlaybackFailure(null, null, null, error))
            }
        }
    }
}
