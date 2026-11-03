package dev.amatv.playback

/** One explicit Play request, tied to an exact queue occurrence rather than song ID. */
internal class ContextContinuationResumeIntent {
    private var anchor: Pair<Long, Long>? = null
    val isPending: Boolean get() = anchor != null
    fun request(sessionId: Long, entryId: Long) { anchor = sessionId to entryId }
    fun cancel() { anchor = null }

    fun consumeIfReady(sessionId: Long, entryId: Long?, hasUpcoming: Boolean,
        complete: Boolean, desiredPlaying: Boolean): Boolean {
        val expected = anchor ?: return false
        if (!desiredPlaying || expected.first != sessionId || expected.second != entryId) {
            cancel()
            return false
        }
        if (!hasUpcoming && !complete) return false
        cancel()
        // An empty terminal page must not repeat the completed anchor (even Repeat All).
        return hasUpcoming
    }
}
