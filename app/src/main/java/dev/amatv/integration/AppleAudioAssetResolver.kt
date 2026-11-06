package dev.amatv.integration

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import dev.amatv.integration.audio.AssetPlaybackSession
import dev.amatv.integration.audio.AssetSessionResolution
import dev.amatv.integration.audio.HlsAudioRendition
import dev.amatv.integration.audio.HlsAudioRenditions
import dev.amatv.integration.audio.assetHttpsUri
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Process-local playback metadata. Never serialize or log its signed URLs/certificate. */
internal class AppleAudioAsset(
    val renditionUrl: String,
    val licenseEndpoint: String,
    val widevineCertificate: ByteArray?,
    val songId: String,
    val contentReferences: List<String>,
    /** Null for enhanced catalog metadata obtained separately from the playback response. */
    val assetSession: AssetPlaybackSession?,
    val isAtmos: Boolean,
) {
    override fun toString(): String = "AppleAudioAsset(isAtmos=$isAtmos)"
}

/** Prefer optional variants without adding any work to the default SDK playback path. */
@UnstableApi
internal class AppleAudioAssetResolver(
    private val storefront: suspend () -> String,
    private val webToken: AppleWebPlaybackTokenClient,
) {
    private val transport = CatalogHttpTransport(playbackCatalogHttpClient)

    suspend fun resolve(
        track: Track,
        developer: String,
        user: String,
        selection: AudioQualitySelection,
    ): AppleAudioAsset? {
        if (track.catalogKind != CatalogKind.Song ||
            (!selection.atmosEnabled && selection.quality == AudioQuality.High)) return null
        val songId = track.catalogId?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        return try {
            // An optional quality preference must never hold up the normal SDK indefinitely.
            // withTimeoutOrNull catches its own deadline only; external cancellation propagates.
            withTimeoutOrNull(12_000L) {
                val region = storefront().takeIf { it.matches(Regex("[a-zA-Z]{2}")) }
                    ?: return@withTimeoutOrNull null
                val catalog = apiJson("https://api.music.apple.com/v1/catalog/$region/songs/$songId",
                    developer, user) ?: return@withTimeoutOrNull null
                val song = catalog.optJSONArray("data")?.optJSONObject(0)
                    ?.takeIf { it.optString("id") == songId } ?: return@withTimeoutOrNull null
                val playParams = song.optJSONObject("attributes")?.optJSONObject("playParams")
                    ?.takeIf { it.optString("id") == songId && it.optString("kind") == "song" }
                    ?: return@withTimeoutOrNull null
                val query = playParams.keys().asSequence().associateWith { key ->
                    val value = playParams.get(key)
                    require(value is String || value is Boolean || value is Number)
                    value.toString()
                } + mapOf("includeLicenseUrls" to "true", "hlsEncryption" to "CBC")
                val requestUrl = Uri.parse("https://api.music.apple.com/v1/play/assets").buildUpon()
                    .apply { query.forEach { (key, value) -> appendQueryParameter(key, value) } }
                    .build().toString()
                val response = apiJson(requestUrl, developer, user, playbackHeaders = true)
                    ?: return@withTimeoutOrNull null
                val assets = response.optJSONObject("results")?.optJSONArray("assets")
                    ?: return@withTimeoutOrNull null
                val sessions = (0 until assets.length().coerceAtMost(32)).mapNotNull { index ->
                    val raw = assets.optJSONObject(index) ?: return@mapNotNull null
                    (AssetPlaybackSession.fromPlayAssetsResponse(songId, playParams, raw)
                        as? AssetSessionResolution.Matched)?.session
                }
                val session = sessions.firstOrNull() ?: return@withTimeoutOrNull null
                val certificate = session.certificateUrl?.let { readCertificate(it) }
                if (selection.atmosEnabled) {
                    // The public web bearer is used for catalog metadata only. Licenses still
                    // use this app's developer credential and this account's user token.
                    val atmos = try {
                        // Reserve the rest of the total budget for the selected AAC preference.
                        // A failed enhanced playlist is as optional as missing enhanced metadata.
                        withTimeoutOrNull(5_000L) atmosAttempt@{
                            val enhanced = enhancedMaster(region, songId, user)
                                ?: return@atmosAttempt null
                            val rendition = renditions(enhanced)
                                ?.let { chooseAudioRendition(it, selection, atmosOnly = true) }
                                ?: return@atmosAttempt null
                            buildAsset(rendition, session, certificate, songId, sameResponse = false)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (atmos != null) return@withTimeoutOrNull atmos
                }
                // High already has a working SDK route; only Efficient needs a direct AAC asset.
                if (selection.quality != AudioQuality.Efficient) return@withTimeoutOrNull null
                for (sameResponse in sessions) {
                    val rendition = renditions(sameResponse.manifestUrl)
                        ?.let { chooseAudioRendition(it, selection, atmosOnly = false) } ?: continue
                    val ownCertificate = if (sameResponse === session) certificate
                        else sameResponse.certificateUrl?.let { readCertificate(it) }
                    buildAsset(rendition, sameResponse, ownCertificate, songId, sameResponse = true)
                        ?.let { return@withTimeoutOrNull it }
                }
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // URLs, account data and server bodies deliberately never reach diagnostics/UI.
            null
        }
    }

    private suspend fun apiJson(
        url: String,
        bearer: String,
        user: String,
        playbackHeaders: Boolean = false,
    ): JSONObject? {
        val parsed = java.net.URI(url)
        require(parsed.scheme == "https" && parsed.host in setOf("api.music.apple.com", "amp-api.music.apple.com") &&
            parsed.userInfo == null && parsed.fragment == null && parsed.port == -1)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer")
            .header(if (playbackHeaders) "X-Apple-Music-User-Token"
                else if (parsed.host == "amp-api.music.apple.com") "media-user-token" else "Music-User-Token", user)
            .header("Origin", "https://music.apple.com").header("Referer", "https://music.apple.com/")
            .header("User-Agent", PLAYBACK_USER_AGENT)
            .header("Accept", "application/json").build()
        val response = transport.execute(request, MAX_JSON_BYTES)
        return if (response.status == 200) JSONObject(response.body) else null
    }

    private suspend fun enhancedMaster(region: String, songId: String, user: String): String? {
        val response = apiJson(
            "https://amp-api.music.apple.com/v1/catalog/$region/songs/$songId?extend=audioVariants,extendedAssetUrls",
            webToken.token(), user) ?: return null
        val song = response.optJSONArray("data")?.optJSONObject(0)
            ?.takeIf { it.optString("id") == songId } ?: return null
        return song.optJSONObject("attributes")?.optJSONObject("extendedAssetUrls")
            ?.optString("enhancedHls")?.takeIf { assetHttpsUri(it) != null }
    }

    private suspend fun readAssetText(url: String): String? {
        require(assetHttpsUri(url) != null)
        // No account or bearer headers are ever sent to the asset CDN.
        val response = transport.execute(Request.Builder().url(url).build(), MAX_PLAYLIST_BYTES)
        return response.body.takeIf { response.status == 200 }
    }

    private suspend fun renditions(master: String): List<HlsAudioRendition>? =
        readAssetText(master)?.let { HlsAudioRenditions.parse(master, it) }

    private suspend fun buildAsset(
        rendition: HlsAudioRendition,
        session: AssetPlaybackSession,
        certificate: ByteArray?,
        songId: String,
        sameResponse: Boolean,
    ): AppleAudioAsset? {
        if (assetHttpsUri(rendition.url) == null) return null
        val playlist = readAssetText(rendition.url) ?: return null
        val references = playlist.lineSequence().map(String::trim)
            .filter { it.startsWith("#EXT-X-KEY:") }
            .map { it.substringAfter(':') }
            .filter { keyAttribute(it, "KEYFORMAT") == WIDEVINE_KEY_FORMAT }
            .mapNotNull { keyAttribute(it, "URI") }
            .filter { it.startsWith("data:") && ";base64," in it }
            .distinct().toList()
        if (references.isEmpty()) return null
        return AppleAudioAsset(rendition.url, session.licenseEndpoint, certificate, songId, references,
            session.takeIf { sameResponse }, rendition.isDolbyAtmos)
    }

    /** Binary certificate read uses the same bounded/cancellable client without text decoding. */
    private suspend fun readCertificate(url: String): ByteArray? {
        require(assetHttpsUri(url) != null)
        return suspendCancellableCoroutine { continuation ->
            val call = playbackCatalogHttpClient.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!continuation.isActive) return
                            if (it.code != 200) return@use null
                            val body = it.body ?: return@use null
                            require(body.contentLength() <= MAX_CERTIFICATE_BYTES)
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) return
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= MAX_CERTIFICATE_BYTES)
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray().takeIf { result -> result.isNotEmpty() }
                            }
                        }
                        continuation.resumeWith(Result.success(bytes))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
                }
            })
        }
    }

    companion object {
        private const val MAX_JSON_BYTES = 512 * 1024
        private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        private const val MAX_CERTIFICATE_BYTES = 64 * 1024L
        // Preserve the request contract used by the full-track playback research.
        private const val PLAYBACK_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142 Safari/537.36"
        private const val WIDEVINE_KEY_FORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
        private fun keyAttribute(value: String, name: String): String? =
            Regex("(?:^|,)\\s*$name\\s*=\\s*(?:\"([^\"]*)\"|([^,]*))").find(value)
                ?.let { it.groups[1]?.value ?: it.groups[2]?.value?.trim() }
    }
}

/** Selection policy never treats ordinary multichannel E-AC-3 or ALAC as Atmos. */
internal fun chooseAudioRendition(
    renditions: List<HlsAudioRendition>,
    selection: AudioQualitySelection,
    atmosOnly: Boolean,
): HlsAudioRendition? = if (atmosOnly) {
    if (!selection.atmosEnabled) null
    else renditions.filter { it.isDolbyAtmos }.minByOrNull { it.bandwidth }
} else {
    val aac = renditions.filter { it.isAac }
    val measured = aac.filter { it.bandwidth in 1 until Long.MAX_VALUE }.ifEmpty { aac }
    if (selection.quality == AudioQuality.Efficient) measured.minByOrNull { it.bandwidth }
    else measured.maxByOrNull { it.bandwidth }
}
