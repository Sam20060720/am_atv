package dev.amatv.ui

import dev.amatv.R
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.CatalogRequestException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CatalogFailurePresentationTest {
    @Test fun causesPreserveServiceMeaningAndUnknownTransportFailuresRemainTransient() {
        for (reason in CatalogFailureReason.entries) {
            val failure = IllegalStateException("wrapped sensitive details",
                CatalogRequestException(reason, "opaque resource and server details"))
            assertEquals(reason, failure.catalogUiFailureReason())
        }
        assertEquals(CatalogFailureReason.Transient, IOException("offline").catalogUiFailureReason())
    }

    @Test fun wrappedCancellationNeverBecomesAnErrorState() {
        val cancellation = CancellationException("leaving page")
        try {
            IllegalStateException("transport wrapper", cancellation).catalogUiFailureReason()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }

    @Test fun firstAndContinuationFailuresHaveDifferentSafeMessages() {
        for (reason in CatalogFailureReason.entries) {
            val first = catalogFailurePresentation(reason)
            val more = catalogFailurePresentation(reason, continuation = true)
            assertNotEquals(first.message, more.message)
            assertEquals(first.action, more.action)
            assertEquals(reason == CatalogFailureReason.Authorization, first.requiresSignIn)
        }
        assertEquals(R.string.recovery_content_unavailable,
            catalogFailurePresentation(CatalogFailureReason.Unavailable).message)
        assertEquals(R.string.recovery_more_connection_failed,
            catalogFailurePresentation(null, continuation = true).message)
    }

    @Test fun authorizationRecoveryRoutesToAccountWithoutRepeatingCatalogRequest() {
        var retries = 0
        var signIns = 0
        performCatalogRecovery(CatalogFailureReason.Authorization, { retries++ }, { signIns++ })
        assertEquals(0, retries)
        assertEquals(1, signIns)
        performCatalogRecovery(CatalogFailureReason.Transient, { retries++ }, { signIns++ })
        performCatalogRecovery(CatalogFailureReason.Unavailable, { retries++ }, { signIns++ })
        assertEquals(2, retries)
        assertEquals(1, signIns)
    }
}
