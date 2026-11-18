package dev.amatv.playback

import dev.amatv.integration.PlaybackSnapshot

/** One reconciled native occurrence completes once, even if its final polls fluctuate. */
internal class PlaybackCompletionTracker {
    private data class Completion(val generation: Long, val sessionId: Long, val entryId: Long)
    private var handled: Completion? = null

    /** The runtime rejects obsolete request generations before calling this tracker. */
    fun shouldHandle(snapshot: PlaybackSnapshot, state: QueueState): Boolean {
        val current = state.current ?: return false
        val actual = snapshot.currentTrack ?: return false
        if (snapshot.requestGeneration <= 0L || !snapshot.isPrepared || !snapshot.isEnded ||
            snapshot.errorMessage != null || actual.catalogKind != current.track.catalogKind ||
            actual.catalogId != current.track.catalogId ||
            actual.catalogId == null && actual.id != current.track.id ||
            snapshot.appQueueEntryId != null && snapshot.appQueueEntryId != current.entryId) return false
        val completion = Completion(snapshot.requestGeneration, state.sessionId, current.entryId)
        if (handled == completion) return false
        handled = completion
        return true
    }

    fun reset() { handled = null }
}
