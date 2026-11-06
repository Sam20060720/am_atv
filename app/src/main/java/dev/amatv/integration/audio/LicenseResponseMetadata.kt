package dev.amatv.integration.audio

import org.json.JSONObject

internal enum class LicenseRequestResult { LicenseReceived, HttpRejected, ServerRejected, ProviderRejected, LocalFailure }

/** Only nonsensitive protocol status metadata; opaque license bytes never enter this object. */
internal data class LicenseResponseMetadata(
    val serverStatus: Int?,
    val providerErrorCode: Int?,
    val renewAfter: Long?,
) {
    val rejection: LicenseRequestResult?
        get() = when {
            serverStatus != null && serverStatus != 0 -> LicenseRequestResult.ServerRejected
            providerErrorCode != null && providerErrorCode != 0 -> LicenseRequestResult.ProviderRejected
            else -> null
        }

    companion object {
        fun fromJson(response: JSONObject): LicenseResponseMetadata = LicenseResponseMetadata(
            response.exactLong("status")?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt(),
            response.exactLong("errorCode")?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt(),
            response.exactLong("renew-after"),
        )

        private fun JSONObject.exactLong(key: String): Long? = when (val value = opt(key)) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> null
        }
    }
}
