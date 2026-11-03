package dev.amatv.ui

/** Keep focus in an oversized reading row only while there is unread text in that direction. */
internal fun readingPageScrollAmount(
    offset: Float, size: Float, viewportStart: Float, viewportEnd: Float,
    margin: Float, direction: Int,
    canScrollBackward: Boolean = true, canScrollForward: Boolean = true,
): Float {
    val height = viewportEnd - viewportStart
    if (height <= 0f || size <= height - margin * 2f || direction == 0) return 0f
    if (direction < 0 && !canScrollBackward || direction > 0 && !canScrollForward) return 0f
    return if (direction > 0) (offset + size - viewportEnd + margin).coerceIn(0f, height * 0.65f)
        else (offset - viewportStart - margin).coerceIn(-height * 0.65f, 0f)
}
