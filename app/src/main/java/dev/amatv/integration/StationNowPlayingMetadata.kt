package dev.amatv.integration

import dev.amatv.model.Track
import org.json.JSONObject
import java.net.URI

/** Presentation from the backing station player; never changes the station queue identity. */
internal data class StationNowPlayingMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
) {
    fun present(source: Track, durationMs: Long): Track {
        val changedCover = artworkUrl != null && artworkUrl != source.artworkUrl
        return source.copy(
            title = title ?: source.title,
            artist = artist ?: source.artist,
            album = album ?: source.album,
            artworkUrl = artworkUrl ?: source.artworkUrl,
            // A station's decorative video must not cover the actual song cover.
            motionArtworkUrl = if (changedCover) null else source.motionArtworkUrl,
            motionPreviewUrl = if (changedCover) null else source.motionPreviewUrl,
            motionArtworkVariants = if (changedCover) emptyList() else source.motionArtworkVariants,
            durationMs = durationMs.coerceAtLeast(0L),
        )
    }

    companion object {
        fun fromReport(data: JSONObject): StationNowPlayingMetadata = StationNowPlayingMetadata(
            title = data.text("title"), artist = data.text("artist"), album = data.text("album"),
            artworkUrl = data.text("artworkUrl", 4096)?.let(::safeArtwork),
        )

        private fun JSONObject.text(key: String, limit: Int = 512): String? =
            (opt(key) as? String)?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= limit && it.none { char -> char < ' ' }
            }

        private fun safeArtwork(value: String): String? = runCatching {
            val formatted = value.replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg")
            val uri = URI(formatted)
            val host = uri.host?.lowercase().orEmpty()
            formatted.takeIf {
                uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
                    (host == "mzstatic.com" || host.endsWith(".mzstatic.com")) &&
                    uri.path.startsWith("/image/") && uri.fragment == null
            }
        }.getOrNull()
    }
}
