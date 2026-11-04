package dev.amatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.amatv.R
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun InterfaceStyleSettings(
    style: InterfaceStyle,
    onStyleChange: (InterfaceStyle) -> Unit,
    navigationFocus: FocusRequester,
    restoreFocus: Boolean,
    focusRequestId: Int,
    onFocusRestored: () -> Unit,
    onFocusRestoreFailed: () -> Unit,
) {
    val requesters = remember { InterfaceStyle.entries.associateWith { FocusRequester() } }
    var focusedStyle by remember { mutableStateOf<InterfaceStyle?>(null) }
    val pending by rememberUpdatedState(restoreFocus)
    val focusRestored by rememberUpdatedState(onFocusRestored)
    val focusRestoreFailed by rememberUpdatedState(onFocusRestoreFailed)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val targetsEnabled = LocalFocusTargetsEnabled.current
    DisposableEffect(focusRequestId) {
        // Capture this request's release; disposal must not cancel a newer
        // style selection that already owns the root handoff.
        val release = onFocusRestoreFailed
        onDispose { release() }
    }
    // Only the user's style-change action owns this handoff. Opening Settings,
    // playback snapshots, and ordinary recomposition never request this focus.
    LaunchedEffect(style, restoreFocus, focusRequestId, windowFocused, targetsEnabled) {
        if (!restoreFocus || !windowFocused || !targetsEnabled) return@LaunchedEffect
        withFrameNanos { }
        if (focusedStyle == style) {
            focusRestored()
            return@LaunchedEffect
        }
        val completed = withTimeoutOrNull(1_000L) {
            while (pending && focusedStyle != style) {
                withFrameNanos { }
                if (pending) runCatching { requesters.getValue(style).requestFocus() }
            }
            true
        } == true
        // A failed handoff releases ownership so the host's normal content
        // entry can recover. It is never reported as confirmed option focus.
        if (!completed && pending) focusRestoreFailed()
    }

    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.interface_style_title), color = Color.White,
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.width(340.dp).automationId("settings-interface-style"),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InterfaceStyle.entries.forEachIndexed { index, option ->
            val label = stringResource(if (option == InterfaceStyle.Apple)
                R.string.interface_style_apple else R.string.interface_style_classic)
            FocusSurface(
                onClick = { onStyleChange(option) },
                modifier = Modifier.weight(1f).height(46.dp)
                    .automationId("settings-interface-${option.storedValue}")
                    .focusRequester(requesters.getValue(option))
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedStyle = option
                            if (restoreFocus && option == style) onFocusRestored()
                        } else if (focusedStyle == option) focusedStyle = null
                    }
                    .semantics { selected = option == style }
                    .focusProperties {
                        val previous = if (index == 0) navigationFocus else requesters.getValue(InterfaceStyle.entries[index - 1])
                        val next = InterfaceStyle.entries.getOrNull(index + 1)?.let(requesters::getValue) ?: FocusRequester.Cancel
                        if (isRtl) { right = previous; left = next }
                        else { left = previous; right = next }
                    },
                cornerRadius = 23.dp,
                background = Color.White.copy(alpha = if (option == style) .22f else .12f),
                focusedBackground = Color.White.copy(alpha = .9f),
                padding = PaddingValues(horizontal = 16.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(label, color = focusSurfaceContentColor(Color.White, Color(0xFF171717)),
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
