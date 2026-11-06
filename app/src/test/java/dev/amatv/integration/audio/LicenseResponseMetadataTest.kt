package dev.amatv.integration.audio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LicenseResponseMetadataTest {
    @Test fun serviceRejectionWithoutErrorCodeDoesNotInventProviderSuccess() {
        val metadata = LicenseResponseMetadata.fromJson(JSONObject().put("status", -1002))
        assertEquals(-1002, metadata.serverStatus)
        assertNull(metadata.providerErrorCode)
        assertEquals(LicenseRequestResult.ServerRejected, metadata.rejection)
    }

    @Test fun missingOrNullStatusRemainsUnknown() {
        for (response in listOf(JSONObject(), JSONObject().put("status", JSONObject.NULL))) {
            assertNull(LicenseResponseMetadata.fromJson(response).serverStatus)
        }
    }

    @Test fun malformedStatusNeverCoercesIntoSuccess() {
        for (value in listOf("0", false, 0.5, Long.MAX_VALUE)) {
            assertNull(LicenseResponseMetadata.fromJson(JSONObject().put("status", value)).serverStatus)
        }
    }

    @Test fun explicitProviderRejectionIsIndependentOfZeroServiceStatus() {
        val metadata = LicenseResponseMetadata.fromJson(JSONObject().put("status", 0).put("errorCode", 42))
        assertEquals(0, metadata.serverStatus)
        assertEquals(42, metadata.providerErrorCode)
        assertEquals(LicenseRequestResult.ProviderRejected, metadata.rejection)
    }

    @Test fun serviceRejectionIsNotOverriddenByProviderZero() {
        val metadata = LicenseResponseMetadata.fromJson(JSONObject().put("status", -1002).put("errorCode", 0))
        assertEquals(LicenseRequestResult.ServerRejected, metadata.rejection)
    }

    @Test fun keepsRenewalMetadataWithoutAssumingUnitsOrPersistingLicense() {
        val metadata = LicenseResponseMetadata.fromJson(JSONObject().put("status", 0)
            .put("renew-after", 1234567890123L).put("license", "opaque-not-for-logging"))
        assertEquals(1234567890123L, metadata.renewAfter)
        assertNull(metadata.rejection)
        assertEquals("LicenseResponseMetadata(serverStatus=0, providerErrorCode=null, renewAfter=1234567890123)", metadata.toString())
    }
}
