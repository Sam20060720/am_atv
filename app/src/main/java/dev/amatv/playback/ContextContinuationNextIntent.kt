package dev.amatv.playback

/** A manual Next must not become Play(anchor) after its page request fails. */
internal class ContextContinuationNextIntent {
    private var anchor: Pair<Long, Long>? = null
    var failed: Boolean = false
        private set

    fun request(sessionId: Long, entryId: Long) { anchor = sessionId to entryId; failed = false }
    fun matches(sessionId: Long, entryId: Long?): Boolean = anchor == (sessionId to entryId)
    fun fail(sessionId: Long, entryId: Long?) { if (matches(sessionId, entryId)) failed = true }
    fun cancel() { anchor = null; failed = false }
    fun cancelIfReplaced(sessionId: Long, entryId: Long?) {
        if (!matches(sessionId, entryId)) cancel()
    }
    fun consumeIfReady(sessionId: Long, entryId: Long?, hasUpcoming: Boolean, complete: Boolean): Boolean {
        if (!matches(sessionId, entryId)) { cancel(); return false }
        if (!hasUpcoming && !complete) return false
        cancel()
        return hasUpcoming
    }
}
