package dev.amatv.ui

import dev.amatv.integration.PlaybackFailureKind

/** Discrete transport state, independent of the rapidly advancing playback clock. */
data class PlaybackFeedbackInput(
    val requestKey: Long,
    val playRequested: Boolean,
    val isPlaying: Boolean,
    val isPrepared: Boolean,
    val isBuffering: Boolean,
    val isReconnecting: Boolean = false,
    val waitingForNext: Boolean = false,
    val failureKind: PlaybackFailureKind? = null,
    val message: String? = null,
)

internal enum class PlaybackFeedbackPhase { Hidden, Preparing, Buffering, Reconnecting, Error, Notice }
internal enum class PlaybackRecoveryAction { None, Retry, SignIn }

internal data class PlaybackFeedbackState(
    val phase: PlaybackFeedbackPhase,
    val message: String? = null,
    val recoveryAction: PlaybackRecoveryAction = PlaybackRecoveryAction.None,
) {
    val visible: Boolean get() = phase != PlaybackFeedbackPhase.Hidden
    val busy: Boolean get() = phase == PlaybackFeedbackPhase.Preparing ||
        phase == PlaybackFeedbackPhase.Buffering || phase == PlaybackFeedbackPhase.Reconnecting

    companion object { val Hidden = PlaybackFeedbackState(PlaybackFeedbackPhase.Hidden) }
}

/** A single delayed reveal keeps ordinary short waits out of the presentation. */
internal class PlaybackFeedbackTracker(private val revealDelayMs: Long = 1_500L) {
    private var requestKey: Long? = null
    private var waitingSinceMs: Long? = null

    fun reset() {
        requestKey = null
        waitingSinceMs = null
    }

    fun update(input: PlaybackFeedbackInput, nowMs: Long): PlaybackFeedbackState {
        if (requestKey != input.requestKey) {
            requestKey = input.requestKey
            waitingSinceMs = null
        }
        if (input.playRequested && input.isReconnecting) {
            waitingSinceMs = null
            return PlaybackFeedbackState(PlaybackFeedbackPhase.Reconnecting, input.message)
        }
        // Recovery is actionable only for a terminal failure. An old failure
        // classification must not turn a new prepare into an error banner.
        if (!input.playRequested && input.message != null && input.failureKind != null) {
            waitingSinceMs = null
            val recovery = when (input.failureKind) {
                PlaybackFailureKind.Authorization -> PlaybackRecoveryAction.SignIn
                PlaybackFailureKind.TrackUnavailable -> PlaybackRecoveryAction.None
                else -> PlaybackRecoveryAction.Retry
            }
            return PlaybackFeedbackState(PlaybackFeedbackPhase.Error, input.message, recovery)
        }
        val waiting = input.playRequested && (input.isBuffering || input.waitingForNext || !input.isPlaying)
        if (waiting) {
            val since = waitingSinceMs ?: nowMs.also { waitingSinceMs = it }
            if ((nowMs - since).coerceAtLeast(0L) < revealDelayMs.coerceAtLeast(0L)) return PlaybackFeedbackState.Hidden
            val phase = if (input.isPrepared && !input.waitingForNext) PlaybackFeedbackPhase.Buffering
                else PlaybackFeedbackPhase.Preparing
            return PlaybackFeedbackState(phase)
        }
        waitingSinceMs = null
        return input.message?.let { PlaybackFeedbackState(PlaybackFeedbackPhase.Notice, it) }
            ?: PlaybackFeedbackState.Hidden
    }

    fun remainingRevealDelayMs(nowMs: Long): Long? = waitingSinceMs?.let {
        (revealDelayMs.coerceAtLeast(0L) - (nowMs - it).coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
