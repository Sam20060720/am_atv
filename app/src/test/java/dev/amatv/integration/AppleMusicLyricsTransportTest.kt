package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertPathValidatorException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

class AppleMusicLyricsTransportTest {
    private class FakeCall(private val value: Request, private val start: (FakeCall, Callback) -> Unit) : Call {
        var cancelled = false
        private var executed = false
        override fun request() = value
        override fun execute(): Response = error("Lyrics must use cancellable async transport")
        override fun enqueue(responseCallback: Callback) { executed = true; start(this, responseCallback) }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(value, start)
    }

    private class BearerFixture {
        val refreshes = AtomicInteger()
        val client = AppleWebPlaybackTokenClient { url ->
            if (url.endsWith("/browse")) {
                refreshes.incrementAndGet()
                "<script src='/assets/player.js'></script>"
            } else token(refreshes.get())
        }
    }

    private fun response(call: FakeCall, status: Int, body: String = document()) = Response.Builder()
        .request(call.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
        .body(body.toResponseBody()).build()

    @Test fun lyricsUseSharedAsyncTransportWithCapturedStorefrontAndWebCredentials() = runBlocking {
        val requests = mutableListOf<Request>()
        val bearer = BearerFixture()
        val client = AppleMusicLyricsClient({ "JP" }, { "fixture-user" }, bearer.client,
            httpCalls = Call.Factory { request ->
                requests += request
                FakeCall(request) { call, callback -> callback.onResponse(call, response(call, 200)) }
            })
        val lines = client.lyricsFor("123", LyricsLocalizationRequest(
            languageTag = "zh-TW", pronunciationLanguageTag = "ja-JP",
            includeTranslations = true, includePronunciations = true))
        assertEquals("Fixture lyric", lines.single().words.single().text)
        val request = requests.single()
        assertEquals("https", request.url.scheme)
        assertEquals("amp-api.music.apple.com", request.url.host)
        assertEquals("/v1/catalog/jp/songs/123/syllable-lyrics", request.url.encodedPath)
        assertEquals("Bearer ${token(1)}", request.header("Authorization"))
        assertEquals("fixture-user", request.header("media-user-token"))
        assertEquals("https://music.apple.com", request.header("Origin"))
        assertEquals("https://music.apple.com/", request.header("Referer"))
        assertEquals("zh-Hant", request.url.queryParameter("l[lyrics]"))
        assertEquals("ttmlLocalizations", request.url.queryParameter("extend"))
        assertTrue(request.url.queryParameter("l[script]").orEmpty().contains("ja-"))
        assertEquals(1, bearer.refreshes.get())
    }

    @Test fun unauthorizedLyricsRefreshBearerOnceThenKeepTheSuccessfulDocument() = runBlocking {
        val requests = mutableListOf<Request>()
        val bearer = BearerFixture()
        val client = AppleMusicLyricsClient({ "tw" }, { "fixture-user" }, bearer.client,
            httpCalls = Call.Factory { request ->
                requests += request
                FakeCall(request) { call, callback ->
                    callback.onResponse(call, response(call, if (requests.size == 1) 401 else 200))
                }
            })
        assertEquals(1, client.lyricsFor("123").size)
        assertEquals(2, requests.size)
        assertEquals(listOf("Bearer ${token(1)}", "Bearer ${token(2)}"), requests.map { it.header("Authorization") })
        assertTrue(requests.all { it.header("media-user-token") == "fixture-user" })
        assertEquals(2, bearer.refreshes.get())
    }

    @Test fun repeatedUnauthorizedLyricsRemainAuthorizationFailureWithoutAThirdRequest() = runBlocking {
        val count = AtomicInteger()
        val bearer = BearerFixture()
        val client = AppleMusicLyricsClient({ "tw" }, { "fixture-user" }, bearer.client,
            httpCalls = Call.Factory { request ->
                count.incrementAndGet()
                FakeCall(request) { call, callback -> callback.onResponse(call, response(call, 401)) }
            })
        val failure = runCatching { client.lyricsFor("123") }.exceptionOrNull()
        assertTrue(failure is LyricsRequestException)
        assertEquals(LyricsFailureReason.Authorization, (failure as LyricsRequestException).reason)
        assertEquals(2, count.get())
        assertEquals(2, bearer.refreshes.get())
    }

    @Test fun switchingSongOrLanguageCancelsTheUnderlyingLyricsCall() = runBlocking {
        val started = CompletableDeferred<FakeCall>()
        val client = AppleMusicLyricsClient({ "tw" }, { "fixture-user" }, BearerFixture().client,
            httpCalls = Call.Factory { request -> FakeCall(request) { call, _ -> started.complete(call) } })
        val pending = async { client.lyricsFor("123") }
        val call = withTimeout(1_000L) { started.await() }
        pending.cancelAndJoin()
        assertTrue(call.cancelled)
        assertTrue(pending.isCancelled)
    }

    @Test fun tlsFailurePreservesItsCertificateCauseAndDoesNotRefreshCredentials() = runBlocking {
        val bearer = BearerFixture()
        val certificate = CertPathValidatorException("Private fixture detail")
        val handshake = SSLHandshakeException("Private fixture endpoint").apply { initCause(certificate) }
        val client = AppleMusicLyricsClient({ "tw" }, { "fixture-user" }, bearer.client,
            httpCalls = Call.Factory { request ->
                FakeCall(request) { call, callback -> callback.onFailure(call, handshake) }
            })
        val failure = runCatching { client.lyricsFor("123") }.exceptionOrNull()
        assertTrue(failure is SSLHandshakeException)
        assertTrue(generateSequence(failure) { it.cause }.take(5).any { it === certificate })
        assertEquals(1, bearer.refreshes.get())
    }

    @Test fun accountChangeDuringBearerBootstrapCannotSendTheOldUserCredential() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var account = "fixture-original-user"
        val bearer = AppleWebPlaybackTokenClient { url ->
            if (url.endsWith("/browse")) {
                started.complete(Unit)
                release.await()
                "<script src='/assets/player.js'></script>"
            } else token(1)
        }
        val client = AppleMusicLyricsClient({ "tw" }, { account }, bearer,
            httpCalls = Call.Factory { error("An old account must never send a lyrics request") })
        val pending = async { runCatching { client.lyricsFor("123") } }
        withTimeout(1_000L) { started.await() }
        account = "fixture-new-user"
        release.complete(Unit)
        assertTrue(withTimeout(1_000L) { pending.await() }.exceptionOrNull() is CancellationException)
    }

    companion object {
        private fun token(version: Int): String {
            fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
            return "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":4102444800}""")}.fixture-$version"
        }

        private fun document(): String {
            val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                <p begin="1.000" end="3.000">Fixture lyric</p>
                </div></body></tt>"""
            return JSONObject().put("data", JSONArray().put(JSONObject().put("attributes",
                JSONObject().put("ttml", ttml)))).toString()
        }
    }
}
