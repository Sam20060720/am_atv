package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class LyricsRequestTest {
    private val lines = listOf(LyricLine(listOf(LyricWord("hello", 0L, 1_000L))))

    @Test fun transientReadFailuresShareOneRetryBudgetWithoutCachingNoLyrics() = runBlocking {
        for (failures in listOf(
            listOf(java.io.IOException("offline"), java.io.IOException("offline")),
            listOf(failure(429), failure(503)),
            listOf(InvocationTargetException(failure(503)), IllegalStateException("not ready")),
        )) {
            var calls = 0
            val waits = mutableListOf<Long>()
            val result = requestLyricsWithRetry(
                fetch = { failures.getOrNull(calls++)?.let { throw it }; lines },
                wait = { waits += it },
            )
            assertEquals(3, calls)
            assertEquals(listOf(2_000L, 5_000L), waits)
            assertEquals(LyricsRequestResult.Available(lines), result)
        }
    }

    @Test fun exhaustedNetworkRetriesRemainRetryableForLaterReadinessOrRefresh() = runBlocking {
        var calls = 0
        val result = requestLyricsWithRetry(fetch = {
            calls++
            throw java.io.IOException("offline")
        }, wait = {})
        assertEquals(3, calls)
        assertEquals(LyricsRequestResult.RetryableFailure(LyricsFailureReason.Transient), result)
        // A later request is independent, rather than suppressed by a negative cache entry.
        assertEquals(LyricsRequestResult.Available(lines), requestLyricsWithRetry(fetch = { lines }, wait = {}))
    }

    @Test fun authorizationFailureWaitsForAuthenticationRatherThanBecomingNoLyrics() = runBlocking {
        for (failure in listOf(
            LyricsRequestException(LyricsFailureReason.Authorization, "sign in"),
            failure(401), failure(403), InvocationTargetException(failure(403)),
        )) {
            var calls = 0
            val result = requestLyricsWithRetry(fetch = { calls++; throw failure },
                wait = { fail("Authorization should not busy-retry") })
            assertEquals(1, calls)
            assertEquals(LyricsRequestResult.RetryableFailure(LyricsFailureReason.Authorization), result)
        }
    }

    @Test fun onlyDefinitiveMissingLyricsBecomesUnavailable() = runBlocking {
        for (failure in listOf(
            LyricsRequestException(LyricsFailureReason.Unavailable, "not found"),
            failure(404), failure(410), InvocationTargetException(failure(404)),
        )) {
            var calls = 0
            assertEquals(LyricsRequestResult.Unavailable, requestLyricsWithRetry(fetch = {
                calls++; throw failure
            }, wait = { fail("Unavailable lyrics should not busy-retry") }))
            assertEquals(1, calls)
        }
        assertEquals(LyricsRequestResult.Unavailable, requestLyricsWithRetry(fetch = { emptyList() }, wait = {}))
    }

    @Test fun navigatingAwayCancelsWithoutProducingAFailure() {
        val cancelled = CancellationException("changed song")
        for (failure in listOf(cancelled, InvocationTargetException(cancelled))) {
            var calls = 0
            val actual = assertThrows(CancellationException::class.java) {
                runBlocking {
                    requestLyricsWithRetry(fetch = { calls++; throw failure },
                        wait = { fail("Cancellation must not retry") })
                }
            }
            assertSame(cancelled, actual)
            assertEquals(1, calls)
        }
    }

    private fun failure(status: Int) = CatalogRequestException(catalogHttpFailure(status), "HTTP $status", status)
}
