package dev.amatv.integration

/** One failed explicit resume may reprepare the same paused native item, never select another item. */
internal class NativeResumeRecovery {
    private data class Pending(val selection: Long, val catalogId: String, val generation: Long, val positionMs: Long)
    private var pending: Pending? = null

    /** [paused] means the prior user intent was paused, not a buffering stream with Play still requested. */
    fun arm(selection: Long, catalogId: String?, nativeGeneration: Long, positionMs: Long,
        prepared: Boolean, paused: Boolean) {
        pending = if (prepared && paused && !catalogId.isNullOrBlank() && nativeGeneration > 0L && positionMs >= 0L)
            Pending(selection, catalogId, nativeGeneration, positionMs) else null
    }

    /** A new prepare, explicit Pause/Seek, selection change, or lost resume intent cancels the opportunity. */
    fun cancel() { pending = null }

    fun observePlaying(selection: Long, catalogId: String?, nativeGeneration: Long, positionMs: Long,
        isPlaying: Boolean) {
        val request = matching(selection, catalogId, nativeGeneration) ?: return
        if (isPlaying && positionMs >= request.positionMs && positionMs - request.positionMs >= 250L) cancel()
    }

    /** Consumes once before a caller starts a new generation. Other failure policies retain their ownership. */
    fun consumeFailure(selection: Long, catalogId: String?, nativeGeneration: Long,
        kind: PlaybackFailureKind, playWhenReady: Boolean): Long? {
        if (!playWhenReady) { cancel(); return null }
        val request = matching(selection, catalogId, nativeGeneration) ?: return null
        cancel()
        return request.positionMs.takeIf { kind == PlaybackFailureKind.Unexpected }
    }

    private fun matching(selection: Long, catalogId: String?, generation: Long): Pending? = pending?.takeIf {
        it.selection == selection && it.catalogId == catalogId && it.generation == generation
    }
}
