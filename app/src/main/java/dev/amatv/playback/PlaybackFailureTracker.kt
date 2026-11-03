package dev.amatv.playback

/** A polling snapshot may repeat one failure hundreds of times during a delayed retry. */
internal class PlaybackFailureTracker {
    private var handledGeneration: Long? = null
    fun shouldHandle(generation: Long, hasError: Boolean): Boolean {
        if (!hasError) { handledGeneration = null; return false }
        if (handledGeneration == generation) return false
        handledGeneration = generation
        return true
    }
    fun reset() { handledGeneration = null }
}
