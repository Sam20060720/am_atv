package dev.amatv.playback

/** Keep an active engine through UI replacement; release unused paused engines after a grace period. */
internal class PlaybackRuntimeRetention(private val idleGraceMs: Long = 30_000L) {
    private var idleSince: Long? = null

    fun remainingIdleMs(hasUi: Boolean, playingOrPreparing: Boolean, nowMs: Long): Long? {
        if (hasUi || playingOrPreparing) {
            idleSince = null
            return null
        }
        val since = idleSince ?: nowMs.also { idleSince = it }
        return (idleGraceMs - (nowMs - since).coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
