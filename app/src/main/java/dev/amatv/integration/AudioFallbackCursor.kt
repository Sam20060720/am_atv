package dev.amatv.integration

/** Keeps explicit transport intent while an optional audio renderer falls back to the SDK. */
internal class AudioFallbackCursor(positionMs: Long, var wantsPlay: Boolean) {
    var positionMs: Long = positionMs.coerceAtLeast(0L)
        private set
    private var pendingSeek: Long? = this.positionMs.takeIf { it > 0L }

    fun seekTo(value: Long) {
        positionMs = value.coerceAtLeast(0L)
        pendingSeek = positionMs
    }

    fun observe(position: Long, prepared: Boolean) {
        val pending = pendingSeek
        if (pending != null) {
            // Cold/buffering snapshots commonly report zero before restoring the requested seek.
            if (!prepared || kotlin.math.abs(position - pending) > 1_000L) return
            pendingSeek = null
        }
        if (prepared) positionMs = position.coerceAtLeast(0L)
    }
}
