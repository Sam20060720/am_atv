package dev.amatv.model

/** Actual server-supplied alternatives; the view chooses a shape without another API request. */
data class EditorialMotionAsset(
    val flavor: String,
    val videoUrl: String,
    val previewUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    val aspectRatio: Float?
        get() = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height
        else when {
            flavor.contains("1x1") || flavor.contains("Square") -> 1f
            flavor.contains("21x9") -> 21f / 9f
            flavor.contains("16x9") -> 16f / 9f
            flavor.contains("3x4") -> 3f / 4f
            else -> null
        }
}
