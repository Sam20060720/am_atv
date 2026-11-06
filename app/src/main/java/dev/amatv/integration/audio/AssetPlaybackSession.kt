package dev.amatv.integration.audio

import java.net.URI
import org.json.JSONObject

internal enum class AssetSessionRejection {
    InvalidExpectedSong,
    InvalidRequestKind,
    MissingIdentity,
    IdentityMismatch,
    MissingManifest,
    InvalidManifest,
    MissingLicenseContext,
    InvalidLicenseEndpoint,
    InvalidCertificateEndpoint,
}

internal sealed interface AssetSessionResolution {
    data class Matched(val session: AssetPlaybackSession) : AssetSessionResolution
    data class Rejected(val reason: AssetSessionRejection) : AssetSessionResolution
}

/**
 * Constructed only by reading an identified item from an actual asset response.
 * Catalog metadata + independently acquired playback context cannot call a string constructor.
 * URLs remain process-local and are deliberately omitted from toString/diagnostics.
 */
internal class AssetPlaybackSession private constructor(
    val songId: String,
    val manifestUrl: String,
    val licenseEndpoint: String,
    val certificateUrl: String?,
) {
    fun matchesLicenseContext(songId: String, endpoint: String): Boolean =
        this.songId == songId && licenseEndpoint == endpoint

    override fun toString(): String = "AssetPlaybackSession(playAssets)"

    companion object {
        /**
         * Correlates one GET /play/assets request with one unchanged response asset.
         * The request supplies song identity when the asset omits it; no identity is injected
         * into JSON and both manifest/license context must originate in this response asset.
         */
        fun fromPlayAssetsResponse(
            expectedSongId: String,
            rawRequestPlayParams: JSONObject,
            rawResponseAsset: JSONObject,
        ): AssetSessionResolution {
            fun reject(reason: AssetSessionRejection) = AssetSessionResolution.Rejected(reason)
            if (expectedSongId.isBlank()) return reject(AssetSessionRejection.InvalidExpectedSong)
            if (rawRequestPlayParams.opt("kind") != "song") {
                return reject(AssetSessionRejection.InvalidRequestKind)
            }
            val requestedId = scalarIdentity(rawRequestPlayParams.opt("id"))
                ?: return reject(AssetSessionRejection.MissingIdentity)
            if (requestedId != expectedSongId) return reject(AssetSessionRejection.IdentityMismatch)
            if (rawResponseAsset.has("songId")) {
                val responseSongId = scalarIdentity(rawResponseAsset.opt("songId"))
                    ?: return reject(AssetSessionRejection.MissingIdentity)
                if (responseSongId != expectedSongId) return reject(AssetSessionRejection.IdentityMismatch)
            }
            val manifest = rawResponseAsset.opt("url").nonBlankString()
                ?: return reject(AssetSessionRejection.MissingManifest)
            if (assetHttpsUri(manifest) == null) return reject(AssetSessionRejection.InvalidManifest)
            val endpoint = rawResponseAsset.opt("keyServerUrl").nonBlankString()
                ?: return reject(AssetSessionRejection.MissingLicenseContext)
            if (appleHttpsUri(endpoint) == null) return reject(AssetSessionRejection.InvalidLicenseEndpoint)
            val certificateValue = rawResponseAsset.opt("widevineKeyCertificateUrl")
                .takeUnless { it === JSONObject.NULL }
            val certificate = certificateValue.nonBlankString()
            if (certificateValue != null && (certificate == null || appleHttpsUri(certificate) == null)) {
                return reject(AssetSessionRejection.InvalidCertificateEndpoint)
            }
            return AssetSessionResolution.Matched(AssetPlaybackSession(
                expectedSongId, manifest, endpoint, certificate,
            ))
        }

        private fun scalarIdentity(value: Any?): String? = when (value) {
            is String -> value.takeIf { it.isNotBlank() }
            is Byte, is Short, is Int, is Long -> value.toString()
            else -> null
        }
    }
}

private fun Any?.nonBlankString(): String? = (this as? String)?.takeIf { it.isNotBlank() }

internal fun appleHttpsUri(value: String): URI? = strictHttpsUri(value)?.takeIf { uri ->
    uri.host.equals("apple.com", true) || uri.host.endsWith(".apple.com", true)
}

internal fun assetHttpsUri(value: String): URI? = strictHttpsUri(value)?.takeIf { uri ->
    uri.host.equals("apple.com", true) || uri.host.endsWith(".apple.com", true) ||
        uri.host.equals("mzstatic.com", true) || uri.host.endsWith(".mzstatic.com", true)
}

private fun strictHttpsUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
    it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null &&
        it.fragment == null && (it.port == -1 || it.port == 443)
}
