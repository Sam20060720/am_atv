package dev.amatv.integration

import kotlin.math.abs

enum class PlaybackFailureKind { TrackUnavailable, Authorization, Network, PositionRestore, Unexpected }

internal data class NativePlaybackProbe(
    val catalogId: String?,
    val hasCurrentItem: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val canSeek: Boolean,
    val paused: Boolean,
    val ended: Boolean,
    val nowMs: Long,
)

internal sealed interface NativePlaybackCommand {
    data object Play : NativePlaybackCommand
    data object Pause : NativePlaybackCommand
    data class Seek(val positionMs: Long) : NativePlaybackCommand
}

internal data class NativePlaybackDecision(
    val prepared: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val commands: List<NativePlaybackCommand> = emptyList(),
    val failure: PlaybackFailureKind? = null,
    val ended: Boolean = false,
)

/** One requested item and its intent; buffering never changes the user's play/pause choice. */
internal class NativePlaybackIntent {
    private enum class Phase { Loading, Seeking, Ready, Failed }
    private var phase = Phase.Loading
    private var catalogId: String? = null
    private var startPosition = 0L
    private var wantsPlay = false
    private var playRecoveryAllowed = false
    private var recoveryPlayingPosition: Long? = null
    private var startedAt = 0L
    private var seekAt = 0L
    private var seekAttempts = 0
    private var lastPlayAt = Long.MIN_VALUE
    private var playAttempts = 0
    private var idleSince: Long? = null
    private var bufferingSince: Long? = null
    private var failure: PlaybackFailureKind? = null
    private var completed = false
    private var lastPlayingPosition = 0L
    private var lastPlayingDuration = 0L
    private var completionCandidateAt: Long? = null

    fun begin(catalogId: String?, positionMs: Long, playWhenReady: Boolean, nowMs: Long) {
        this.catalogId = catalogId
        startPosition = positionMs.coerceAtLeast(0L)
        wantsPlay = playWhenReady
        playRecoveryAllowed = playWhenReady
        recoveryPlayingPosition = null
        startedAt = nowMs
        phase = Phase.Loading
        seekAt = 0L
        seekAttempts = 0
        lastPlayAt = Long.MIN_VALUE
        playAttempts = 0
        idleSince = null
        bufferingSince = null
        failure = null
        completed = false
        resetCompletionEvidence()
    }

    /** The same native controller has already adopted this queued occurrence. */
    fun adoptPrepared(catalogId: String?, positionMs: Long, playWhenReady: Boolean, nowMs: Long) {
        begin(catalogId, positionMs, playWhenReady, nowMs)
        phase = Phase.Ready
    }

    fun requestPlaying(value: Boolean) {
        wantsPlay = value
        playRecoveryAllowed = value
        recoveryPlayingPosition = null
        if (!value) {
            bufferingSince = null
            resetCompletionEvidence()
        }
        if (value) {
            playAttempts = 0
            lastPlayAt = Long.MIN_VALUE
            idleSince = null
        }
    }

    /** A user seek must never be mistaken for the SDK clearing a completed item. */
    fun requestSeek() {
        completed = false
        recoveryPlayingPosition = null
        resetCompletionEvidence()
    }

    private fun resetCompletionEvidence() {
        lastPlayingPosition = 0L
        lastPlayingDuration = 0L
        completionCandidateAt = null
    }

    fun observe(probe: NativePlaybackProbe): NativePlaybackDecision {
        fun result(commands: List<NativePlaybackCommand> = emptyList()) = NativePlaybackDecision(
            prepared = phase == Phase.Ready,
            playing = phase == Phase.Ready && wantsPlay && probe.playing && !probe.buffering,
            positionMs = if (phase == Phase.Ready) probe.positionMs else startPosition,
            commands = commands,
            failure = failure,
            ended = completed,
        )
        fun fail(kind: PlaybackFailureKind): NativePlaybackDecision {
            phase = Phase.Failed
            failure = kind
            return result(listOf(NativePlaybackCommand.Pause))
        }
        if (phase == Phase.Failed) return result()
        // A terminal SDK callback can precede clearing getCurrentItem()/position.
        // Preserve completion rather than treating that cleanup as a new load failure.
        if (phase == Phase.Ready && (completed || probe.ended)) {
            completed = true
            return result().copy(playing = false, positionMs = maxOf(probe.positionMs, lastPlayingPosition))
        }
        // Recover native cleanup when the end callback is missing or unusable.
        // Only an uninterrupted, confirmed final playback segment can complete here;
        // explicit pause, seek, buffering and another item all invalidate that evidence.
        if (phase == Phase.Ready && wantsPlay && !probe.buffering && !probe.playing &&
            (probe.catalogId == catalogId || !probe.hasCurrentItem) &&
            lastPlayingDuration > COMPLETION_TOLERANCE_MS &&
            lastPlayingPosition >= lastPlayingDuration - COMPLETION_TOLERANCE_MS &&
            (!probe.paused || !probe.hasCurrentItem || probe.positionMs >= lastPlayingDuration) &&
            (!probe.hasCurrentItem || probe.positionMs == 0L ||
                probe.positionMs >= lastPlayingDuration - COMPLETION_TOLERANCE_MS)
        ) {
            val since = completionCandidateAt ?: probe.nowMs.also { completionCandidateAt = it }
            val remaining = (lastPlayingDuration - lastPlayingPosition).coerceAtLeast(0L)
            val confirmation = maxOf(COMPLETION_CONFIRMATION_MS, remaining + COMPLETION_GRACE_MS)
            if (probe.nowMs - since >= confirmation) completed = true
            // Never replay the completed final segment while confirming native cleanup.
            return result().copy(playing = false, positionMs = maxOf(probe.positionMs, lastPlayingPosition))
        }
        completionCandidateAt = null
        if (!probe.hasCurrentItem || probe.catalogId != catalogId) {
            resetCompletionEvidence()
            return if (probe.nowMs - startedAt > 60_000L) fail(PlaybackFailureKind.Network) else result()
        }
        if (phase != Phase.Ready && probe.nowMs - startedAt > 60_000L) {
            return fail(if (phase == Phase.Seeking) PlaybackFailureKind.PositionRestore else PlaybackFailureKind.Network)
        }
        if (probe.buffering) {
            resetCompletionEvidence()
            idleSince = null
            if (wantsPlay) {
                val since = bufferingSince ?: probe.nowMs.also { bufferingSince = it }
                if (probe.nowMs - since >= 60_000L) return fail(PlaybackFailureKind.Network)
            } else bufferingSince = null
            return result(if (!wantsPlay && probe.playing) listOf(NativePlaybackCommand.Pause) else emptyList())
        }
        if (bufferingSince != null && wantsPlay) {
            // Attempts made before the SDK finished buffering cannot start the
            // newly ready stream. Give this readiness transition its own bounded
            // recovery budget, just as an explicit Play would do.
            playAttempts = 0
            lastPlayAt = Long.MIN_VALUE
            idleSince = null
            playRecoveryAllowed = true
            recoveryPlayingPosition = null
        }
        bufferingSince = null
        if (phase == Phase.Loading && (probe.canSeek || (startPosition <= POSITION_TOLERANCE_MS && !probe.playing && !probe.buffering))) {
            if (probe.durationMs > 0L) startPosition = startPosition.coerceAtMost((probe.durationMs - 500L).coerceAtLeast(0L))
            if (abs(probe.positionMs - startPosition) > POSITION_TOLERANCE_MS) {
                if (!probe.canSeek) return result()
                phase = Phase.Seeking
                seekAt = probe.nowMs
                seekAttempts = 1
                return result(listOf(NativePlaybackCommand.Seek(startPosition)))
            }
            phase = Phase.Ready
        }
        if (phase == Phase.Seeking) {
            if (abs(probe.positionMs - startPosition) <= POSITION_TOLERANCE_MS) {
                phase = Phase.Ready
            } else if (probe.nowMs - seekAt >= 3_000L && probe.canSeek && seekAttempts < 3) {
                seekAt = probe.nowMs
                seekAttempts++
                return result(listOf(NativePlaybackCommand.Seek(startPosition)))
            } else if (probe.nowMs - seekAt >= 10_000L) {
                return fail(PlaybackFailureKind.PositionRestore)
            } else return result()
        }
        if (phase != Phase.Ready) return result()
        if (!wantsPlay) {
            idleSince = null
            return result(if (probe.playing) listOf(NativePlaybackCommand.Pause) else emptyList())
        }
        if (probe.playing) {
            lastPlayingPosition = probe.positionMs
            lastPlayingDuration = probe.durationMs
            // A transient SDK "playing" flag can precede actual audio progress.
            // Keep the existing retry budget until this recovery really advances.
            val baseline = recoveryPlayingPosition
                ?: probe.positionMs.also { recoveryPlayingPosition = it }
            if (!playRecoveryAllowed || probe.positionMs - baseline >= STARTED_PROGRESS_MS) {
                playAttempts = 0
                idleSince = null
                playRecoveryAllowed = false
            }
            return result()
        }
        if (probe.ended) return result()
        // After playback starts, MusicKit owns focus-loss pauses and gain/resume.
        // Only a new user Play or a completed buffer can authorize our retries.
        if (!playRecoveryAllowed) {
            idleSince = null
            return result()
        }
        val idleStart = idleSince ?: probe.nowMs.also { idleSince = it }
        if (playAttempts < 3 && (lastPlayAt == Long.MIN_VALUE || probe.nowMs - lastPlayAt >= 3_000L)) {
            lastPlayAt = probe.nowMs
            playAttempts++
            return result(listOf(NativePlaybackCommand.Play))
        }
        if (probe.nowMs - idleStart >= 45_000L) return fail(PlaybackFailureKind.Network)
        return result()
    }

    private companion object {
        const val POSITION_TOLERANCE_MS = 750L
        const val STARTED_PROGRESS_MS = 250L
        const val COMPLETION_TOLERANCE_MS = 5_000L
        const val COMPLETION_CONFIRMATION_MS = 1_000L
        const val COMPLETION_GRACE_MS = 500L
    }
}

/** MusicKit component codes are not documented HTTP status codes or account-token verdicts. */
@Suppress("UNUSED_PARAMETER")
internal fun classifyPlaybackFailure(type: Int?, code: Int?, componentCode: Int?, error: Throwable? = null): PlaybackFailureKind {
    when (error?.catalogFailureReason()) {
        CatalogFailureReason.Unavailable -> return PlaybackFailureKind.TrackUnavailable
        CatalogFailureReason.Authorization -> return PlaybackFailureKind.Authorization
        CatalogFailureReason.Transient -> return PlaybackFailureKind.Network
        null -> Unit
    }
    // Public MusicKit 1.1.1 types: IO=1, DRM=2, queue=3, lease=4,
    // Chromecast=5, explicit-content age verification=6. None means expired login.
    if (type == 1) return PlaybackFailureKind.Network
    // The SDK's ErrorConditionException also extends IOException. An explicit
    // native type therefore takes precedence over that broad Java superclass.
    if (type == null && generateSequence(error) { it.cause }.take(8).any { it is java.io.IOException }) {
        return PlaybackFailureKind.Network
    }
    return PlaybackFailureKind.Unexpected
}

/** The callback's end position is authoritative even if the stopped controller has reset to zero. */
internal fun isCompletedPlaybackItem(
    eventGeneration: Long,
    requestGeneration: Long,
    prepared: Boolean,
    requestedCatalogId: String?,
    endedCatalogId: String?,
    expectedNativeQueueId: Long?,
    endedNativeQueueId: Long?,
    endPositionMs: Long,
    durationMs: Long,
): Boolean = eventGeneration == requestGeneration && prepared && requestedCatalogId != null &&
    requestedCatalogId == endedCatalogId &&
    (expectedNativeQueueId == null || endedNativeQueueId == null || expectedNativeQueueId == endedNativeQueueId) &&
    durationMs > 0L && endPositionMs > 0L &&
    endPositionMs >= (durationMs - 5_000L).coerceAtMost(kotlin.math.round(durationMs * 0.92f).toLong()).coerceAtLeast(0L)
