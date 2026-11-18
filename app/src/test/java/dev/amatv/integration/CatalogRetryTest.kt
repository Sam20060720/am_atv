package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogRetryTest {
    @Test fun metadataTransportFailureRetriesWithoutChangingItsResolvedValue() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val result = retryCatalogRequest(fetch = {
            if (++attempts < 3) throw CatalogRequestException(CatalogFailureReason.Transient, "busy", 503)
            "6790045614"
        }, wait = { waits += it })
        assertEquals("6790045614", result)
        assertEquals(3, attempts)
        assertEquals(listOf(2_000L, 5_000L), waits)
    }

    @Test fun unavailableAuthorizationAndCancellationDoNotWait() = runBlocking {
        val cancellation = CancellationException("changed song")
        for (error in listOf(
            CatalogRequestException(CatalogFailureReason.Unavailable, "unavailable"),
            CatalogRequestException(CatalogFailureReason.Authorization, "sign in"),
            IllegalStateException("unexpected"), cancellation,
            java.lang.reflect.InvocationTargetException(cancellation),
        )) {
            var attempts = 0
            try {
                retryCatalogRequest<String>({ attempts++; throw error }, { fail("Must not retry $error") })
                fail("Failure must propagate")
            } catch (actual: Exception) {
                assertSame(if (error is java.lang.reflect.InvocationTargetException) cancellation else error, actual)
            }
            assertEquals(1, attempts)
        }
    }

    @Test fun exhaustedReadsAndCancelledWaitsNeverGetAnAdditionalAttempt() = runBlocking {
        val failure = java.io.IOException("offline")
        var attempts = 0
        val waits = mutableListOf<Long>()
        try {
            retryCatalogRequest<String>({ attempts++; throw failure }, { waits += it })
            fail("An exhausted read must preserve its failure")
        } catch (actual: Exception) { assertSame(failure, actual) }
        assertEquals(3, attempts)
        assertEquals(listOf(2_000L, 5_000L), waits)

        val cancelled = CancellationException("leaving while waiting")
        for (lyrics in listOf(false, true)) {
            attempts = 0
            try {
                if (lyrics) requestLyricsWithRetry({ attempts++; throw failure }, { throw cancelled })
                else retryCatalogRequest<String>({ attempts++; throw failure }, { throw cancelled })
                fail("The caller owns cancellation during a retry wait")
            } catch (actual: Exception) { assertSame(cancelled, actual) }
            assertEquals(1, attempts)
        }
    }
}
