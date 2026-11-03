package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AppleWebPlaybackTokenClientTest {
    private val browse = "https://music.apple.com/us/browse"
    private val html = "<script src='/assets/player.js'></script>"

    private fun token(version: Int = 1): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":4102444800}""")}.fixture-$version"
    }

    @Test fun cancellingColdFetchReleasesTheLockForTheNextCaller() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val browseCalls = AtomicInteger()
        val client = AppleWebPlaybackTokenClient { url ->
            if (url == browse) {
                if (browseCalls.incrementAndGet() == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                } else html
            } else token()
        }
        val abandoned = async { client.token() }
        withTimeout(1_000L) { started.await() }
        val next = async(start = CoroutineStart.UNDISPATCHED) { client.token() }
        withTimeout(1_000L) { abandoned.cancelAndJoin(); cancelled.await() }
        assertEquals(token(), withTimeout(1_000L) { next.await() })
        assertEquals(2, browseCalls.get())
    }

    @Test fun cancellingAWaitingCallerDoesNotWaitForOrCancelTheOwner() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val browseCalls = AtomicInteger()
        val client = AppleWebPlaybackTokenClient { url ->
            if (url == browse) {
                browseCalls.incrementAndGet()
                started.complete(Unit)
                release.await()
                html
            } else token()
        }
        val owner = async { client.token() }
        withTimeout(1_000L) { started.await() }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { client.token() }
        try {
            withTimeout(1_000L) { waiting.cancelAndJoin() }
            assertFalse(owner.isCompleted)
        } finally { release.complete(Unit) }
        assertEquals(token(), withTimeout(1_000L) { owner.await() })
        assertEquals(1, browseCalls.get())
    }

    @Test fun pageDeadlineCancelsColdFetchAndLeavesTheNextCallUsable() = runBlocking {
        val browseCalls = AtomicInteger()
        val fetchCancelled = AtomicBoolean()
        val client = AppleWebPlaybackTokenClient { url ->
            if (url == browse) {
                if (browseCalls.incrementAndGet() == 1) {
                    try { awaitCancellation() } finally { fetchCancelled.set(true) }
                } else html
            } else token()
        }
        val failure = runCatching { withCatalogRequestDeadline(1_000L) { client.token() } }.exceptionOrNull()
        assertTrue(failure is SocketTimeoutException)
        assertTrue(fetchCancelled.get())
        assertEquals(token(), withTimeout(1_000L) { client.token() })
        assertEquals(2, browseCalls.get())
    }

    @Test fun assetCancellationPropagatesWithoutTryingTheNextAssetOrCaching() = runBlocking {
        val firstAssetCalls = AtomicInteger()
        val secondAssetCalls = AtomicInteger()
        val client = AppleWebPlaybackTokenClient { url ->
            when (url) {
                browse -> "<script src='/assets/first.js'></script><script src='/assets/second.js'></script>"
                "https://music.apple.com/assets/first.js" -> {
                    if (firstAssetCalls.incrementAndGet() == 1) throw CancellationException("Asset fixture")
                    token()
                }
                else -> { secondAssetCalls.incrementAndGet(); token(2) }
            }
        }
        val failure = runCatching { client.token() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, secondAssetCalls.get())
        assertEquals(token(), client.token())
        assertEquals(2, firstAssetCalls.get())
        assertEquals(0, secondAssetCalls.get())
    }

    @Test fun cachedTokenIsReusedAndForcedRefreshFetchesExactlyOnce() = runBlocking {
        val browseCalls = AtomicInteger()
        val assetCalls = AtomicInteger()
        val client = AppleWebPlaybackTokenClient { url ->
            if (url == browse) {
                browseCalls.incrementAndGet()
                html
            } else {
                assetCalls.incrementAndGet()
                token(browseCalls.get())
            }
        }
        assertEquals(token(1), client.token())
        assertEquals(token(1), client.token())
        assertEquals(1, browseCalls.get())
        assertEquals(1, assetCalls.get())
        assertEquals(token(2), client.token(forceRefresh = true))
        assertEquals(token(2), client.token())
        assertEquals(2, browseCalls.get())
        assertEquals(2, assetCalls.get())
    }
}
