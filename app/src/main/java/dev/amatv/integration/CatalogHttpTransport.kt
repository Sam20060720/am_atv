package dev.amatv.integration

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class CatalogHttpReply(val status: Int, val body: String, val location: String? = null)

/** Full-body async I/O. No response/socket ownership escapes the OkHttp callback. */
internal class CatalogHttpTransport(private val calls: Call.Factory = catalogHttpClient) {
    suspend fun execute(request: Request, maxResponseBytes: Int? = null): CatalogHttpReply = suspendCancellableCoroutine { continuation ->
        require(maxResponseBytes == null || maxResponseBytes > 0)
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        // A late callback still closes its body, without reading it.
                        if (!continuation.isActive) return
                        val body = it.body
                        val text = if (maxResponseBytes == null) body?.string().orEmpty() else {
                            if (body != null && body.contentLength() > maxResponseBytes) throw IOException("Response exceeds size limit")
                            body?.byteStream()?.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    if (!continuation.isActive) return
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > maxResponseBytes) throw IOException("Response exceeds size limit")
                                    output.write(buffer, 0, count)
                                }
                                output.toString(Charsets.UTF_8.name())
                            }.orEmpty()
                        }
                        CatalogHttpReply(it.code, text, it.header("Location"))
                    }
                    continuation.resumeWith(Result.success(reply))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

/** One budget for the first HTTP call AND the existing single 401 refresh/retry. */
internal suspend fun <T> withCatalogRequestDeadline(
    timeoutMs: Long = 25_000L,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMs) { block() }
} catch (_: TimeoutCancellationException) {
    // A navigation/owner deadline remains cancellation, never a network error.
    currentCoroutineContext().ensureActive()
    // Failure classifiers intentionally propagate ANY cancellation in a cause
    // chain. This deadline is now a network failure, so do not retain its
    // coroutine implementation detail as the SocketTimeoutException's cause.
    throw SocketTimeoutException("Catalog request timed out")
}

internal suspend fun catalogRequestWithTokenRefresh(
    execute: suspend (forceTokenRefresh: Boolean) -> CatalogHttpReply,
): CatalogHttpReply {
    val first = execute(false)
    return if (first.status == 401) execute(true) else first
}

// Android DNS is not always interruptible. Keep a hard thread/call bound even
// when timed-out platform lookups outlive their cancelled UI request.
internal val catalogHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dispatcher(catalogDispatcher(4, "AMATV-catalog-http"))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
}

// Song identity, storefront and credentials must not queue behind browse pages
// and decorative metadata. Share connections/TLS, but reserve two bounded workers
// for these small dependencies of an explicit playback request.
internal val playbackCatalogHttpClient: OkHttpClient by lazy {
    catalogHttpClient.newBuilder()
        .dispatcher(catalogDispatcher(2, "AMATV-playback-http"))
        .build()
}

private fun catalogDispatcher(limit: Int, name: String): Dispatcher {
    val executor = Executors.newFixedThreadPool(limit) { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
    return Dispatcher(executor).apply { maxRequests = limit; maxRequestsPerHost = limit }
}
