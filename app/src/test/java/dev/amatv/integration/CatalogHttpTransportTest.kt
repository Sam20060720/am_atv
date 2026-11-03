package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CatalogHttpTransportTest {
    private val request = Request.Builder().url("https://api.music.apple.com/v1/me/library/albums/l.test/tracks")
        .header("Authorization", "Bearer fixture-developer").header("Music-User-Token", "fixture-user").build()

    private class FakeCall(private val value: Request) : Call {
        lateinit var callback: Callback
        var cancelled = false
        var executed = false
        override fun request() = value
        override fun execute(): Response = error("Synchronous execution forbidden")
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(value)
    }

    private class TrackedBody(value: String) : ResponseBody() {
        var closed = false
        var read = false
        private val bytes = Buffer().writeUtf8(value)
        private val source = object : ForwardingSource(bytes) {
            override fun read(sink: Buffer, byteCount: Long): Long { read = true; return super.read(sink, byteCount) }
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = source
    }

    private fun response(body: ResponseBody, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body).build()

    @Test fun optionalPublicBodyLimitRejectsAnUnknownLengthStreamAndStillClosesIt() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { transport.execute(request, maxResponseBytes = 32) } }
        val body = TrackedBody("音".repeat(100))
        call.callback.onResponse(call, response(body))
        assertTrue(pending.await().exceptionOrNull() is IOException)
        assertTrue(body.closed)
    }

    @Test fun responseKeepsRelativeLocationAvailableToItsCaller() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { transport.execute(request) }
        val body = TrackedBody("")
        call.callback.onResponse(call, response(body, status = 302).newBuilder()
            .header("Location", "../assets/player.js").build())
        val reply = pending.await()
        assertEquals(302, reply.status)
        assertEquals("../assets/player.js", reply.location)
        assertTrue(body.closed)
    }

    @Test fun totalDeadlineCancelsCallEvenWhenDnsNeverCallsBack() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val failure = runCatching { withCatalogRequestDeadline(40L) { transport.execute(request) } }.exceptionOrNull()
        assertTrue(failure is SocketTimeoutException)
        assertTrue(call.cancelled)
        // Exercise the real consumers: a request deadline must reach Retry as
        // Network, rather than rethrowing an implementation-detail cancellation.
        assertNull(checkNotNull(failure).catalogFailureReason())
        assertEquals(PlaybackFailureKind.Network, classifyPlaybackFailure(null, null, null, failure))
    }

    @Test fun navigationCancellationStaysCancellationInsteadOfRetryableNetworkFailure() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            withCatalogRequestDeadline(5_000L) { transport.execute(request) }
        }
        pending.cancel(CancellationException("Navigation fixture"))
        val failure = runCatching { pending.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(failure is SocketTimeoutException)
        assertTrue(call.cancelled)
        val classified = runCatching { classifyPlaybackFailure(null, null, null, failure) }.exceptionOrNull()
        assertTrue(classified is CancellationException)
    }

    @Test fun shorterParentDeadlineIsNotTranslatedToCatalogNetworkTimeout() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val failure = runCatching { withTimeout(40L) {
            withCatalogRequestDeadline(5_000L) { transport.execute(request) }
        } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(call.cancelled)
    }

    @Test fun responseArrivingAfterCancellationIsClosedWithoutReadingBody() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { transport.execute(request) }
        pending.cancel()
        val body = TrackedBody("late fixture")
        call.callback.onResponse(call, response(body))
        assertTrue(body.closed)
        assertFalse(body.read)
    }

    @Test fun completeBodyIsReadAndClosedBeforeReturningStatusAndImmutableText() = runBlocking {
        lateinit var call: FakeCall
        val transport = CatalogHttpTransport(Call.Factory { sent ->
            assertEquals("Bearer fixture-developer", sent.header("Authorization"))
            assertEquals("fixture-user", sent.header("Music-User-Token"))
            FakeCall(sent).also { call = it }
        })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { transport.execute(request) }
        val body = TrackedBody("{\"errors\":[{\"code\":\"40403\"}]}")
        call.callback.onResponse(call, response(body, 404))
        assertTrue(body.closed)
        val reply = pending.await()
        assertEquals(404, reply.status)
        assertEquals("{\"errors\":[{\"code\":\"40403\"}]}", reply.body)
    }

    @Test fun bodyReadFailureClosesResponseAndPreservesIoFailure() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val body = object : ResponseBody() {
            var closed = false
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("Read fixture")
                override fun close() { closed = true; super.close() }
            }.buffer()
        }
        // Handle the failure inside the child so it cannot cancel the parent test scope.
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { transport.execute(request) } }
        call.callback.onResponse(call, response(body))
        assertTrue(pending.await().exceptionOrNull() is IOException)
        assertTrue(body.closed)
    }

    @Test fun onlyOneDeveloperRefreshIsAllowedAndFinalHttpFailureIsPreserved() = runBlocking {
        val refreshes = mutableListOf<Boolean>()
        val reply = withCatalogRequestDeadline { catalogRequestWithTokenRefresh { force ->
            refreshes += force
            CatalogHttpReply(401, if (force) "second unauthorized" else "first unauthorized")
        } }
        assertEquals(listOf(false, true), refreshes)
        assertEquals(CatalogHttpReply(401, "second unauthorized"), reply)
    }

    @Test fun overallDeadlineAlsoCancelsTheSecondCallAfter401Refresh() = runBlocking {
        val call = FakeCall(request)
        val transport = CatalogHttpTransport(Call.Factory { call })
        val refreshes = mutableListOf<Boolean>()
        val failure = runCatching { withCatalogRequestDeadline(40L) {
            catalogRequestWithTokenRefresh { force ->
                refreshes += force
                if (!force) CatalogHttpReply(401, "fixture") else transport.execute(request)
            }
        } }.exceptionOrNull()
        assertTrue(failure is SocketTimeoutException)
        assertEquals(listOf(false, true), refreshes)
        assertTrue(call.cancelled)
    }

    @Test fun non401HttpFailureDoesNotRefreshOrAutomaticallyRetryMutation() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        val reply = catalogRequestWithTokenRefresh { refresh ->
            attempts += refresh
            CatalogHttpReply(503, "temporary service failure")
        }
        assertEquals(listOf(false), attempts)
        assertEquals(503, reply.status)
    }
}
