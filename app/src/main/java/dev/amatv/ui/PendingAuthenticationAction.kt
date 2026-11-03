package dev.amatv.ui

import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.playback.CollectionPlaybackContinuation
import dev.amatv.playback.CollectionPlaybackRequest

/** An explicit playback intent, never a callback retaining a disposed screen. */
internal sealed interface PendingAuthenticationAction {
    val origin: Destination

    data class PlayTrack(
        val track: Track,
        val contextQueue: List<Track> = emptyList(),
        override val origin: Destination,
        val startStation: Boolean = false,
    ) : PendingAuthenticationAction

    /** A resume must address this exact queue occurrence, never recreate a context. */
    data class ResumeCurrent(
        val track: Track,
        val sessionId: Long,
        val entryId: Long?,
        val positionMs: Long,
        override val origin: Destination,
    ) : PendingAuthenticationAction

    data class PlayCollection(
        val request: CollectionPlaybackRequest,
        override val origin: Destination,
    ) : PendingAuthenticationAction
}

/**
 * A bounded, one-shot handoff from an authentication screen back to playback.
 * No failed/cancelled authentication can itself start music. Callers consume
 * only after validated authentication and after startup queue hydration.
 *
 * Main-thread owned, like the host playback selection state. Collection cursor
 * ownership is transferred on take; replacing/clearing an intent cancels only
 * its discarded cursor, never the cursor transferred to playback.
 */
internal class PendingAuthenticationActions {
    internal data class Attempt(val id: Long, val actionGeneration: Long?)
    private data class Pending(val generation: Long, val action: PendingAuthenticationAction)

    private var nextGeneration = 0L
    private var nextAttempt = 0L
    private var pending: Pending? = null
    private var attempt: Attempt? = null

    val generation: Long? get() = pending?.generation
    val hasPending: Boolean get() = pending != null

    fun peek(): PendingAuthenticationAction? = pending?.action

    /** Latest explicit selection wins, including repeated occurrences of a song. */
    fun remember(action: PendingAuthenticationAction): Long {
        val snapshot = when (action) {
            is PendingAuthenticationAction.PlayTrack -> action.copy(contextQueue = action.contextQueue.toList())
            is PendingAuthenticationAction.ResumeCurrent -> action.copy(positionMs = action.positionMs.coerceAtLeast(0L))
            is PendingAuthenticationAction.PlayCollection -> action.copy(request = action.request.copy(tracks = action.request.tracks.toList()))
        }
        val replacementCursor = snapshot.continuation()
        pending?.action?.continuation()?.takeUnless { it === replacementCursor }?.cancel()
        val generation = ++nextGeneration
        pending = Pending(generation, snapshot)
        return generation
    }

    /** Capture when starting a real phone/TV auth attempt, after remembering its intent. */
    fun beginAttempt(): Attempt = Attempt(++nextAttempt, pending?.generation).also { attempt = it }

    /** Failed authorization keeps the selected intent available for an explicit retry. */
    fun failAttempt(ticket: Attempt) {
        if (attempt == ticket) attempt = null
    }

    /** Old attempts and attempts begun without a playback intent cannot play a later choice. */
    fun completeAttempt(ticket: Attempt): PendingAuthenticationAction? {
        if (attempt != ticket) return null
        attempt = null
        val expected = ticket.actionGeneration ?: return null
        return take(expected)
    }

    /**
     * Hosts that already guard auth callbacks may consume by the captured intent
     * generation directly. Consumption is atomic with respect to UI callbacks;
     * late duplicate completions find no pending intent.
     */
    fun take(expectedGeneration: Long? = null): PendingAuthenticationAction? {
        val current = pending ?: return null
        if (expectedGeneration != null && current.generation != expectedGeneration) return null
        pending = null
        attempt = null
        return current.action
    }

    /** Explicit auth dismissal, sign-out, or a superseding signed-in play clears old work. */
    fun clear() {
        val discarded = pending
        pending = null
        attempt = null
        discarded?.action?.continuation()?.cancel()
    }

    private fun PendingAuthenticationAction.continuation(): CollectionPlaybackContinuation? =
        (this as? PendingAuthenticationAction.PlayCollection)?.request?.continuation
}

/** Metadata can refresh while authenticating; only stable session/occurrence identity matters. */
internal fun PendingAuthenticationAction.ResumeCurrent.matchesCurrent(
    currentTrack: Track?,
    currentSessionId: Long,
    currentEntryId: Long?,
): Boolean = sessionId == currentSessionId && entryId == currentEntryId && currentTrack != null &&
    track.id == currentTrack.id && track.catalogKind == currentTrack.catalogKind &&
    track.catalogId == currentTrack.catalogId && track.resourceType == currentTrack.resourceType &&
    track.resourceId == currentTrack.resourceId
