package dev.amatv.integration

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class PublicMusicHttpTest {
    private class PendingCall(private val value: Request) : Call {
        lateinit var callback: Callback
        var cancelled = false
        override fun request() = value
        override fun execute(): Response = error("Blocking I/O forbidden")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall(value)
        fun reply(status: Int, location: String? = null) {
            callback.onResponse(this, Response.Builder().request(value).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture").body("page".toResponseBody())
                .apply { location?.let { header("Location", it) } }.build())
        }
    }

    @Test fun cancelAfterRedirectClosesTheCurrentAnonymousCall() = runBlocking {
        val calls = mutableListOf<PendingCall>()
        val factory = Call.Factory { request ->
            assertNull(request.header("Music-User-Token"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            PendingCall(request).also { calls += it }
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fetchPublicMusicHtml("https://music.apple.com/tw/new", "zh-Hant-TW", calls = factory)
        }
        calls.first().reply(302, "/tw/radio")
        // The callback resumes the same undispatched coroutine on the test loop.
        kotlinx.coroutines.yield()
        assertEquals(2, calls.size)
        pending.cancelAndJoin()
        assertTrue(calls.last().cancelled)
    }

    @Test fun redirectCannotChangeStorefrontOrHost() = runBlocking {
        for (destination in listOf("/cn/new", "https://example.test/tw/new")) {
            var call: PendingCall? = null
            var count = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
                fetchPublicMusicHtml("https://music.apple.com/tw/new", "en", calls = Call.Factory {
                    count++; PendingCall(it).also { created -> call = created }
                })
            } }
            call!!.reply(302, destination)
            assertTrue(pending.await().isFailure)
            assertEquals(1, count)
        }
    }
}
