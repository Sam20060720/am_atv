package dev.amatv.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.amatv.integration.NativeQueueEntry

enum class SleepTimerOption(val durationMs: Long? = null) {
    Off,
    Minutes15(15 * 60_000L),
    Minutes30(30 * 60_000L),
    Minutes45(45 * 60_000L),
    Minutes60(60 * 60_000L),
    EndOfTrack,
    EndOfContext,
}

data class PlaybackSleepTimerState(
    val option: SleepTimerOption = SleepTimerOption.Off,
    val deadlineElapsedRealtimeMs: Long? = null,
) {
    /** The UI may sample this while its menu is visible; the player needs no ticking flow. */
    fun remainingMs(nowElapsedRealtimeMs: Long): Long? =
        deadlineElapsedRealtimeMs?.let { (it - nowElapsedRealtimeMs).coerceAtLeast(0L) }
}

/** One runtime-owned timer. Durations follow elapsed time; track mode follows one occurrence. */
internal class PlaybackSleepTimer(private val nowMs: () -> Long) {
    private val mutableState = MutableStateFlow(PlaybackSleepTimerState())
    val state: StateFlow<PlaybackSleepTimerState> = mutableState.asStateFlow()
    private var endEntryId: Long? = null
    private var contextSessionId: Long? = null
    private var contextKind: ContextKind? = null
    private var contextId: String? = null
    private var contextBoundaryEntryId: Long? = null

    fun select(option: SleepTimerOption, currentEntryId: Long?, queue: QueueState? = null): Boolean {
        if (option != SleepTimerOption.Off && currentEntryId == null) return false
        if (option == SleepTimerOption.EndOfContext && (queue == null || !canSelectContextEnd(queue))) return false
        val duration = option.durationMs
        val now = nowMs()
        val deadline = duration?.let { if (now > Long.MAX_VALUE - it) Long.MAX_VALUE else now + it }
        endEntryId = currentEntryId.takeIf { option == SleepTimerOption.EndOfTrack }
        contextSessionId = queue?.sessionId.takeIf { option == SleepTimerOption.EndOfContext }
        contextKind = queue?.context?.kind.takeIf { option == SleepTimerOption.EndOfContext }
        contextId = queue?.context?.id.takeIf { option == SleepTimerOption.EndOfContext }
        contextBoundaryEntryId = queue?.current?.entryId.takeIf {
            option == SleepTimerOption.EndOfContext && queue != null && isContextBoundary(queue)
        }
        mutableState.value = PlaybackSleepTimerState(option, deadline)
        return true
    }

    fun consumeDeadline(): Boolean {
        val deadline = state.value.deadlineElapsedRealtimeMs ?: return false
        if (nowMs() < deadline) return false
        cancel()
        return true
    }

    fun consumeTrackEnd(entryId: Long?): Boolean {
        if (state.value.option != SleepTimerOption.EndOfTrack || entryId != endEntryId) return false
        cancel()
        return true
    }

    /** Manual selection and native handoff must not let a different occurrence play on. */
    fun consumeEntryChange(entryId: Long?): Boolean {
        if (state.value.option != SleepTimerOption.EndOfTrack || entryId == endEntryId) return false
        cancel()
        return true
    }

    fun canSelectContextEnd(queue: QueueState): Boolean {
        if (queue.current == null || queue.context?.kind !in setOf(ContextKind.Album, ContextKind.Playlist, ContextKind.LibraryPlaylist) ||
            queue.context?.id.isNullOrBlank() || queue.containerEntryIds.isEmpty()) return false
        val known = (queue.contextEntries.asSequence() + queue.sessionEntries.asSequence()).map { it.entryId }.toSet()
        if (!known.containsAll(queue.containerEntryIds)) return false
        return queue.current.entryId in queue.containerEntryIds || queue.upNext.any { it.entryId in queue.containerEntryIds }
    }

    fun consumeQueueChange(queue: QueueState): Boolean {
        if (state.value.option != SleepTimerOption.EndOfContext) return consumeEntryChange(queue.current?.entryId)
        if (!matchesContext(queue) || queue.current == null) {
            // An explicit new album/playlist is a new choice, not the old timer's ending.
            cancel()
            return false
        }
        val previousBoundary = contextBoundaryEntryId
        if (previousBoundary != null && queue.current.entryId != previousBoundary &&
            queue.upNext.none { it.entryId == previousBoundary }) {
            cancel()
            return true
        }
        if (!queue.contextContinuationPending && queue.current.entryId !in queue.containerEntryIds &&
            queue.upNext.none { it.entryId in queue.containerEntryIds }) {
            cancel()
            return true
        }
        contextBoundaryEntryId = queue.current.entryId.takeIf { isContextBoundary(queue) }
        return false
    }

    fun consumePlaybackEnd(queue: QueueState, entryId: Long?): Boolean {
        if (consumeTrackEnd(entryId)) return true
        if (state.value.option != SleepTimerOption.EndOfContext || !matchesContext(queue) ||
            queue.current?.entryId != entryId || !isContextBoundary(queue)) return false
        cancel()
        return true
    }

    fun consumeNativeHandoff(queue: QueueState, nextEntryId: Long?): Boolean {
        if (state.value.option == SleepTimerOption.EndOfTrack) return consumeEntryChange(nextEntryId)
        if (state.value.option != SleepTimerOption.EndOfContext || !matchesContext(queue) ||
            !isContextBoundary(queue) || nextEntryId == queue.current?.entryId) return false
        cancel()
        return true
    }

    fun nativeUpcoming(queue: QueueState): List<NativeQueueEntry> {
        if (state.value.option == SleepTimerOption.EndOfTrack) return emptyList()
        if (state.value.option != SleepTimerOption.EndOfContext || !matchesContext(queue)) return nativeQueueLookahead(queue)
        // Keep ordinary transitions inside the container. Neither a deferred
        // explicit-last item nor an autoplay reserve can cross its last occurrence.
        val last = queue.upNext.indexOfLast { it.entryId in queue.containerEntryIds }
        return nativeQueueLookahead(queue.copy(
            upNext = if (last >= 0) queue.upNext.take(last + 1) else emptyList(), autoplay = emptyList(),
        ))
    }

    private fun matchesContext(queue: QueueState): Boolean = queue.sessionId == contextSessionId &&
        queue.context?.kind == contextKind && queue.context?.id == contextId

    private fun isContextBoundary(queue: QueueState): Boolean = !queue.contextContinuationPending &&
        queue.current?.entryId in queue.containerEntryIds && queue.upNext.none { it.entryId in queue.containerEntryIds }

    fun cancel() {
        endEntryId = null
        contextSessionId = null
        contextKind = null
        contextId = null
        contextBoundaryEntryId = null
        mutableState.value = PlaybackSleepTimerState()
    }
}
