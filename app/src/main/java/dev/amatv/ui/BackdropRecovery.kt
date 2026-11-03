package dev.amatv.ui

/** A lost EGL producer may recover without a new TextureView availability callback. */
internal class BackdropRecovery {
    private var failures = 0

    fun retryDelayMs(): Long? = when (failures++) {
        0 -> 250L
        1 -> 1_000L
        2 -> 4_000L
        else -> null
    }

    fun succeeded() { failures = 0 }
}
