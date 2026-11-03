package dev.amatv.integration

/** Retry the same station; only finite episodes retain a playback cursor. */
internal data class StationReconnectPlan(
    val stationId: String,
    val selectionGeneration: Long,
    val attempt: Int,
    val delayMs: Long,
    val resumePositionMs: Long = 0L,
) {
    /** Check before every delay step and immediately before reconnecting. */
    fun matches(currentStationId: String?, currentSelectionGeneration: Long, playWhenReady: Boolean): Boolean =
        playWhenReady && stationId == currentStationId && selectionGeneration == currentSelectionGeneration
}

/** The caller owns the coroutine/job; Pause, a new selection and release cancel that job. */
internal object StationReconnectPolicy {
    /** Playing alone cannot restore retries while the progress watchdog is still waiting. */
    class PlaybackHealth {
        private var position: Long? = null
        private var sampledAt: Long? = null
        private var progressedAt: Long? = null
        private var healthySince: Long? = null

        fun reset() {
            position = null
            sampledAt = null
            progressedAt = null
            healthySince = null
        }

        fun onSnapshot(positionMs: Long, nowMs: Long, isPlaying: Boolean, isPrepared: Boolean): Boolean {
            if (!isPlaying || !isPrepared || positionMs < 0L || nowMs < 0L) {
                reset()
                return false
            }
            val previousPosition = position
            val previousSampleAt = sampledAt
            val previousProgressAt = progressedAt
            position = positionMs
            sampledAt = nowMs
            if (previousPosition == null || previousSampleAt == null || nowMs < previousSampleAt ||
                positionMs < previousPosition) {
                // A radio song change can restart its clock; it does not restore retries.
                progressedAt = nowMs
                healthySince = null
                return false
            }
            val recentProgress = previousProgressAt != null && nowMs - previousProgressAt <= 1_000L
            if (!recentProgress) healthySince = null
            if (positionMs == previousPosition) return false
            // Duplicate state callbacks may share a cursor. Only advancing samples
            // can finish the window, with no gap in observed progress over one second.
            val since = healthySince ?: if (recentProgress) checkNotNull(previousProgressAt) else nowMs
            healthySince = since
            progressedAt = nowMs
            return nowMs - since >= 10_000L
        }
    }

    fun plan(
        stationId: String?,
        selectionGeneration: Long,
        consecutiveFailures: Int,
        failureKind: PlaybackFailureKind,
        playWhenReady: Boolean,
        isLiveStream: Boolean = true,
        positionMs: Long = 0L,
    ): StationReconnectPlan? {
        if (!playWhenReady || failureKind != PlaybackFailureKind.Network ||
            stationId.isNullOrBlank() || consecutiveFailures !in 0..1) return null
        return StationReconnectPlan(stationId, selectionGeneration, consecutiveFailures + 1,
            if (consecutiveFailures == 0) 3_000L else 6_000L,
            if (isLiveStream) 0L else positionMs.coerceAtLeast(0L))
    }
}
