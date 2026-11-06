package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.amatv.R

@Composable
internal fun AppExitDialog(
    backgroundPlayback: Boolean,
    inputGate: CatalogModalInputGate,
    onStay: () -> Unit,
    onExit: () -> Unit,
    onExitAndStop: () -> Unit,
) {
    val stay = remember { FocusRequester() }
    val leave = remember { FocusRequester() }
    val stop = remember { FocusRequester() }
    var stayFocused by remember { mutableStateOf(false) }
    var leaveFocused by remember { mutableStateOf(false) }
    var stopFocused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onStay, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false,
        windowTitle = "AMATV.Exit",
    )) {
        TvBrowseViewport {
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused, backgroundPlayback) {
                if (!windowFocused) return@LaunchedEffect
                repeat(4) {
                    withFrameNanos { }
                    if (runCatching { stay.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
            CompositionLocalProvider(LocalFocusTargetsEnabled provides true,
                LocalFocusFrameEnabled provides false, LocalContentFocusEntry provides null) {
                Box(Modifier.fillMaxSize().automationId("app-exit-dialog")
                    .onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        val activation = key.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            key.keyCode == KeyEvent.KEYCODE_ENTER || key.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        val back = key.keyCode == KeyEvent.KEYCODE_BACK
                        (activation || back) && !inputGate.dialog(stayFocused || leaveFocused || stopFocused, back, activation,
                            key.action == KeyEvent.ACTION_DOWN, key.action == KeyEvent.ACTION_UP, key.repeatCount)
                    }.background(Color.Black.copy(alpha = .55f)), Alignment.Center) {
                    val shape = RoundedCornerShape(28.dp)
                    Column(Modifier.width(440.dp).background(Color(0xFF242429), shape)
                        .border(.5.dp, Color.White.copy(alpha = .09f), shape).padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(stringResource(R.string.app_exit_title, stringResource(R.string.app_name)),
                            color = Color.White, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                        if (backgroundPlayback) Text(stringResource(R.string.app_exit_background_playback),
                            color = Color.White.copy(alpha = .70f), fontSize = 14.sp, lineHeight = 21.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvDialogAction(stringResource(R.string.app_exit_stay), onStay,
                                Modifier.fillMaxWidth().automationId("app-exit-stay").focusRequester(stay)
                                    .onFocusChanged { stayFocused = it.isFocused }.focusProperties {
                                        up = FocusRequester.Cancel; down = leave
                                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                    })
                            TvDialogAction(stringResource(if (backgroundPlayback) R.string.app_exit_keep_playing
                                else R.string.app_exit_confirm), onExit,
                                Modifier.fillMaxWidth().automationId("app-exit-confirm").focusRequester(leave)
                                    .onFocusChanged { leaveFocused = it.isFocused }.focusProperties {
                                        up = stay; down = if (backgroundPlayback) stop else FocusRequester.Cancel
                                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                    })
                            if (backgroundPlayback) TvDialogAction(stringResource(R.string.app_exit_stop_playback), onExitAndStop,
                                Modifier.fillMaxWidth().automationId("app-exit-stop").focusRequester(stop)
                                    .onFocusChanged { stopFocused = it.isFocused }.focusProperties {
                                        up = leave; down = FocusRequester.Cancel
                                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                    })
                        }
                    }
                }
            }
        }
    }
}
