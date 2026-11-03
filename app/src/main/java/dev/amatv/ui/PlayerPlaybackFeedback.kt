package dev.amatv.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import kotlinx.coroutines.delay

@Composable
internal fun rememberPlaybackFeedback(input: PlaybackFeedbackInput, active: Boolean = true): PlaybackFeedbackState {
    val tracker = remember { PlaybackFeedbackTracker() }
    var state by remember { mutableStateOf(PlaybackFeedbackState.Hidden) }
    LaunchedEffect(input, active) {
        if (!active) {
            tracker.reset()
            state = PlaybackFeedbackState.Hidden
            return@LaunchedEffect
        }
        state = tracker.update(input, SystemClock.elapsedRealtime())
        val remaining = tracker.remainingRevealDelayMs(SystemClock.elapsedRealtime())
        if (!state.visible && remaining != null) {
            if (remaining > 0L) delay(remaining)
            state = tracker.update(input, SystemClock.elapsedRealtime())
        }
    }
    return state
}

/**
 * A passive status surface: the player's existing Play/Pause and Back retain
 * remote ownership. Recovery hints appear only if the host wires that action.
 */
@Composable
internal fun PlayerPlaybackFeedback(
    state: PlaybackFeedbackState,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    recoveryAvailable: Boolean = false,
) {
    var lastVisible by remember { mutableStateOf(state) }
    val presented = if (state.visible) state else lastVisible
    SideEffect { if (state.visible) lastVisible = state }
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        enter = fadeIn(tween(if (animate) 150 else 0)),
        exit = fadeOut(tween(if (animate) 180 else 0)),
    ) {
        val message = presented.message ?: stringResource(when (presented.phase) {
            PlaybackFeedbackPhase.Preparing -> R.string.playback_feedback_preparing
            PlaybackFeedbackPhase.Buffering -> R.string.playback_feedback_buffering
            PlaybackFeedbackPhase.Reconnecting -> R.string.playback_feedback_reconnecting
            else -> R.string.playback_feedback_failed
        })
        val hint = if (!recoveryAvailable) null else when (presented.recoveryAction) {
            PlaybackRecoveryAction.SignIn -> stringResource(R.string.playback_feedback_sign_in_hint)
            PlaybackRecoveryAction.Retry -> stringResource(R.string.playback_feedback_retry_hint)
            PlaybackRecoveryAction.None -> null
        }
        Column(
            modifier = Modifier.widthIn(max = 380.dp).automationId("player-playback-feedback")
                .then(if (presented.busy) Modifier else Modifier
                    .background(Color(0xFF626269).copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp))
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(message, color = Color.White.copy(alpha = if (presented.busy) 0.5f else 0.95f),
                fontSize = if (presented.busy) 11.sp else 13.sp,
                lineHeight = if (presented.busy) 15.sp else 17.sp,
                fontWeight = if (presented.busy) FontWeight.Normal else FontWeight.Medium)
            if (hint != null) Text(hint, color = Color.White.copy(alpha = 0.68f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
