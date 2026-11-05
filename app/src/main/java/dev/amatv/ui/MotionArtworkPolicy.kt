package dev.amatv.ui

import dev.amatv.model.EditorialMotionAsset
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal fun Track.canLoadEditorialArtwork(): Boolean =
    (catalogKind == CatalogKind.Album || catalogKind == CatalogKind.Artist || catalogKind == CatalogKind.Playlist) &&
    motionArtworkUrl == null && motionArtworkVariants.isEmpty()

/** Async artwork is presentation only; it cannot replace identity, labels or lyrics. */
internal fun Track.withEditorialMotionFrom(loaded: Track): Track {
    if (id != loaded.id || catalogKind != loaded.catalogKind || catalogId != loaded.catalogId ||
        resourceType != loaded.resourceType || resourceId != loaded.resourceId) return this
    if (loaded.motionArtworkUrl == null && loaded.motionArtworkVariants.isEmpty()) return this
    return copy(motionArtworkUrl = loaded.motionArtworkUrl, motionPreviewUrl = loaded.motionPreviewUrl,
        motionArtworkVariants = loaded.motionArtworkVariants)
}

internal fun Track.motionForAspect(aspectRatio: Float): EditorialMotionAsset? {
    val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    return motionArtworkVariants.minByOrNull { asset ->
        asset.aspectRatio?.let { abs(ln(it / aspect)) } ?: Float.MAX_VALUE
    } ?: motionArtworkUrl?.let { EditorialMotionAsset("legacy", it, motionPreviewUrl) }
}

/** The still belongs to the chosen video flavor, including its baked-in typography. */
internal fun Track.posterForAspect(aspectRatio: Float = 1f): String? =
    motionForAspect(aspectRatio)?.previewUrl?.takeIf { it.startsWith("https://") }
        ?: artworkUrl?.takeIf { it.startsWith("https://") }
        ?: motionPreviewUrl?.takeIf { it.startsWith("https://") }

/** Playlist tall assets may be text-free backgrounds, not complete cover art. */
internal fun Track.forHomePlaylistArtwork(): Track {
    if (catalogKind != CatalogKind.Playlist || motionArtworkVariants.isEmpty()) return this
    val covers = motionArtworkVariants.filter {
        it.aspectRatio?.let { aspect -> abs(aspect - 1f) < 0.05f } == true &&
            !it.flavor.startsWith("background", ignoreCase = true)
    }
    val first = covers.firstOrNull()
    return copy(motionArtworkUrl = first?.videoUrl, motionPreviewUrl = first?.previewUrl, motionArtworkVariants = covers)
}

internal data class MotionDecodeBounds(val width: Int, val height: Int)

/** The official ladder starts at 360 square / 480x270 wide, even for a small TV tile. */
internal fun motionDecodeBounds(width: Float, height: Float, sourceAspect: Float?): MotionDecodeBounds {
    val aspect = sourceAspect?.takeIf { it.isFinite() && it in 0.5f..2.5f }
    if (aspect == null) return MotionDecodeBounds(
        ceil(width.coerceAtLeast(640f).coerceAtMost(960f)).toInt(),
        ceil(height.coerceAtLeast(640f).coerceAtMost(960f)).toInt(),
    )
    val minimumWidth = 360f * max(1f, aspect)
    val minimumHeight = 360f * max(1f, 1f / aspect)
    val requestedWidth = max(max(width, height * aspect), minimumWidth)
    val requestedHeight = max(max(height, width / aspect), minimumHeight)
    val scale = min(1f, 960f / max(requestedWidth, requestedHeight))
    return MotionDecodeBounds(ceil(requestedWidth * scale).toInt().coerceIn(1, 960),
        ceil(requestedHeight * scale).toInt().coerceIn(1, 960))
}
