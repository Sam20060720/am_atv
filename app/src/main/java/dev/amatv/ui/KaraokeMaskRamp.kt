package dev.amatv.ui

/** Clamped transition coordinates of the existing four-stop lyric mask. */
internal data class KaraokeMaskRamp(val start: Float, val end: Float)

internal fun karaokeMaskRamp(progress: Float, feather: Float, isRtl: Boolean): KaraokeMaskRamp =
    if (isRtl) {
        val start = progress.coerceIn(0f, 1f)
        KaraokeMaskRamp(start, (progress + feather).coerceIn(start, 1f))
    } else {
        val end = progress.coerceIn(0f, 1f)
        KaraokeMaskRamp((progress - feather).coerceIn(0f, end), end)
    }
