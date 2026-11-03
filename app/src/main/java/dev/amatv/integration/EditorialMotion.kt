package dev.amatv.integration

import dev.amatv.model.EditorialMotionAsset
import org.json.JSONObject
import kotlin.math.roundToInt

/** Parse every known editorial shape once, retaining the existing default ordering. */
internal fun JSONObject.editorialMotionVariants(artist: Boolean = false): List<EditorialMotionAsset> {
    val flavors = (if (artist) listOf("motionArtistFullscreen16x9", "motionArtistWide16x9", "motionArtistSquare1x1") else emptyList()) +
        listOf("motionSquareVideo1x1", "motionDetailSquare", "backgroundMotion1x1", "motionArtistSquare1x1",
            "motionMediumVideo16x9", "motionArtistWide16x9", "motionWideVideo21x9", "motionDetailTall", "motionTallVideo3x4")
    // REST resources and the anonymous web detail model use different envelopes
    // for the same editorial assets. Never crawl related cards for a hero asset.
    val sources = listOfNotNull(optJSONObject("editorialVideo"), optJSONObject("editorialCard")?.optJSONObject("editorialVideo")) +
        listOf("videoArtwork", "tallVideoArtwork", "wideVideoArtwork").mapNotNull { optJSONObject(it)?.optJSONObject("dictionary") }
    return sources.flatMap { videos -> flavors.distinct().mapNotNull { flavor ->
        val value = videos.optJSONObject(flavor) ?: return@mapNotNull null
        val video = value.optString("video").takeIf { it.startsWith("https://") }
            ?: value.optJSONArray("videoFile")?.let { files ->
                (files.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
                    files.optJSONObject(index)?.optString("assetUrl")?.takeIf { it.startsWith("https://") }
                }
            } ?: return@mapNotNull null
        val preview = value.optJSONObject("previewFrame")
        val width = preview?.optInt("width", 0)?.takeIf { it > 0 }
        val height = preview?.optInt("height", 0)?.takeIf { it > 0 }
        val previewHeight = if (width != null && height != null) (600.0 * height / width).roundToInt().coerceIn(1, 1200) else 600
        EditorialMotionAsset(flavor, video, preview?.optString("url")?.editorialArtworkUrl(height = previewHeight),
            width, height)
    } }.distinctBy { it.videoUrl }
}

internal fun String.editorialArtworkUrl(height: Int = 600): String? = replace("{w}", "600").replace("{h}", height.toString())
    .replace("{c}", "bb").replace("{f}", "jpg")
    .takeIf { it.startsWith("https://") && !it.contains('{') && !it.contains('}') }
