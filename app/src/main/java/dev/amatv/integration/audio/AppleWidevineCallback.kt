package dev.amatv.integration.audio

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID

/** Normal online exchange. Opaque response bytes go only to Android MediaDrm. */
@UnstableApi
internal class AppleWidevineCallback(
    private val endpoint: String,
    private val developerToken: String,
    private val userToken: String,
    private val songId: String,
    private val contentReferenceForRequest: (ByteArray) -> String,
    private val assetSession: AssetPlaybackSession? = null,
) : MediaDrmCallback {

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
        HttpMediaDrmCallback("", DefaultHttpDataSource.Factory()).executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
        val uri = Uri.parse(endpoint)
        var httpStatus: Int? = null
        var metadata: LicenseResponseMetadata? = null
        var result = LicenseRequestResult.LocalFailure
        var bytesLoaded = 0L
        try {
            val target = appleHttpsUri(endpoint) ?: throw IOException("Untrusted license endpoint")
            if (uuid != C.WIDEVINE_UUID) throw IOException("Unexpected DRM scheme")
            if (assetSession != null) {
                if (!assetSession.matchesLicenseContext(songId, endpoint)) {
                    throw IOException("Asset session context mismatch")
                }
            }
            val reference = contentReferenceForRequest(request.data)
            if (reference.isBlank()) throw IOException("Missing content reference")
            val payload = JSONObject()
                .put("challenge", Base64.encodeToString(request.data, Base64.NO_WRAP))
                .put("uri", reference)
                .put("key-system", "com.widevine.alpha")
                .put("adamId", songId)
                .put("isLibrary", false)
                .put("user-initiated", true)
                .toString().toByteArray()
            val connection = target.toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $developerToken")
                connection.setRequestProperty("X-Apple-Music-User-Token", userToken)
                // Preserve the existing contract; CDM requestType is recorded independently.
                connection.setRequestProperty("X-Apple-Renewal", "true")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Origin", "https://music.apple.com")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
                httpStatus = connection.responseCode
                if (httpStatus !in 200..299) {
                    result = LicenseRequestResult.HttpRejected
                    throw IOException("License HTTP rejected")
                }
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytesLoaded += read
                        if (bytesLoaded > MAX_RESPONSE_BYTES) {
                            throw IOException("Oversized license response")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val response = JSONObject(String(body, Charsets.UTF_8))
                metadata = LicenseResponseMetadata.fromJson(response)
                metadata.rejection?.let { rejection ->
                    result = rejection
                    throw IOException("License service rejected request")
                }
                if (metadata.serverStatus == null) throw IOException("Missing numeric license status")
                val opaqueLicense = (response.opt("license") as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Missing license")
                val decoded = Base64.decode(opaqueLicense, Base64.DEFAULT)
                if (decoded.isEmpty()) throw IOException("Empty license")
                result = LicenseRequestResult.LicenseReceived
                return MediaDrmCallback.Response(decoded)
            } finally { connection.disconnect() }
        } catch (_: Exception) {
            // Network exception messages/causes can include signed URLs; expose only classification.
            throw MediaDrmCallbackException(DataSpec(uri), uri, emptyMap(), bytesLoaded,
                IOException("License exchange failed: $result"))
        }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 }
}
