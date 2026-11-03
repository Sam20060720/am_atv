package dev.amatv.ui

internal data class ArtworkMemoryPolicy(val decodedCacheBytes: Long, val constrained: Boolean) {
    val allowHardware: Boolean get() = !constrained
    val allowEarlyGpuUpload: Boolean get() = !constrained
}

/** Java heap size does not describe the separate graphics budget of a 32-bit TV. */
internal fun artworkMemoryPolicy(maxHeapBytes: Long, is64Bit: Boolean, lowRam: Boolean): ArtworkMemoryPolicy {
    val mib = 1024L * 1024L
    val constrained = !is64Bit || lowRam
    val bytes = if (constrained) (maxHeapBytes / 16).coerceIn(8L * mib, 16L * mib)
        else (maxHeapBytes / 10).coerceIn(12L * mib, 48L * mib)
    return ArtworkMemoryPolicy(bytes, constrained)
}
