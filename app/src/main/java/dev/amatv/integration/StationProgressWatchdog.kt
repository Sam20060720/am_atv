package dev.amatv.integration

/** Only observed buffering/playing can stall; ordinary audio-focus pauses do not retry. */
internal class StationProgressWatchdog(private val stallMs: Long = 30_000L) {
    private var epoch: Long? = null
    private var position: Long? = null
    private var progressedAt: Long? = null
    fun observe(generation: Long, prepared: Boolean, desired: Boolean, playing: Boolean, buffering: Boolean,
        positionMs: Long, nowMs: Long): Boolean {
        if (epoch != generation || !prepared || !desired || (!playing && !buffering)) {
            epoch = generation; position = positionMs; progressedAt = nowMs
            return false
        }
        val previous = position
        if (previous == null || positionMs != previous || progressedAt == null || nowMs < checkNotNull(progressedAt)) {
            position = positionMs; progressedAt = nowMs
            return false
        }
        return nowMs - checkNotNull(progressedAt) >= stallMs
    }
}
