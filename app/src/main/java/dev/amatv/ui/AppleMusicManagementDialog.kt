package dev.amatv.ui

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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.common.BitMatrix
import dev.amatv.R
import dev.amatv.integration.appleMusicManagementUrl
import dev.amatv.integration.appleMusicShareUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens the actual resource on the phone; never hosts auth or sends account writes. */
@Composable
internal fun AppleMusicManagementDialog(
    url: String,
    itemTitle: String,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    sharing: Boolean = false,
) {
    val destination = remember(url, sharing) {
        if (sharing) appleMusicShareUrl(url) else appleMusicManagementUrl(url)
    } ?: return
    val done = remember { FocusRequester() }
    val inputGate = remember { CatalogModalInputGate() }
    var doneFocused by remember { mutableStateOf(false) }
    val matrix by produceState<Result<BitMatrix>?>(null, destination) {
        value = withContext(Dispatchers.Default) { runCatching { phoneSignInQrMatrix(destination) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false,
        windowTitle = if (sharing) "AMATV.MusicShare" else "AMATV.MusicManagement",
    )) {
        TvBrowseViewport {
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused) {
                if (!windowFocused) return@LaunchedEffect
                // A Dialog can attach before its new window owns input. A
                // one-shot failed request otherwise leaves its only action inert.
                repeat(4) {
                    withFrameNanos { }
                    if (runCatching { done.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
            CompositionLocalProvider(LocalFocusTargetsEnabled provides true, LocalFocusFrameEnabled provides false,
                LocalContentFocusEntry provides null) {
                Box(Modifier.fillMaxSize().automationId(if (sharing) "apple-music-share-dialog" else "apple-music-management-dialog")
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val activation = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == KeyEvent.KEYCODE_ENTER || native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        activation && !inputGate.dialog(doneFocused, false, true,
                            native.action == KeyEvent.ACTION_DOWN, native.action == KeyEvent.ACTION_UP,
                            native.repeatCount)
                    }
                    .background(Color.Black.copy(alpha = .55f)), Alignment.Center) {
                    val shape = RoundedCornerShape(28.dp)
                    Row(Modifier.width(690.dp).background(Color(0xFF242429), shape)
                        .border(.5.dp, Color.White.copy(alpha = .09f), shape).padding(30.dp),
                        horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(226.dp).background(Color.White, RoundedCornerShape(12.dp)), Alignment.Center) {
                            val code = matrix?.getOrNull()
                            if (matrix?.isFailure == true) Text(stringResource(R.string.apple_music_manage_qr_unavailable),
                                color = Color(0xFF555555), fontSize = 14.sp, lineHeight = 21.sp,
                                modifier = Modifier.padding(18.dp))
                            else if (code == null) AppleLoadingIndicator(Modifier.size(26.dp))
                            else QrCodeCanvas(code, Modifier.fillMaxSize().padding(8.dp).automationId(if (sharing) "apple-music-share-qr" else "apple-music-management-qr")
                                .semantics { contentDescription = destination })
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                            Text(stringResource(if (sharing) R.string.apple_music_share_title else R.string.apple_music_manage_title), color = Color.White,
                                fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                            Text(itemTitle, color = Color.White.copy(alpha = .9f), fontSize = 16.sp,
                                lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (sharing) R.string.apple_music_share_instructions else R.string.apple_music_manage_instructions), color = Color.White.copy(alpha = .70f),
                                fontSize = 14.sp, lineHeight = 21.sp)
                            Text("music.apple.com", color = Color.White.copy(alpha = .5f), fontSize = 12.sp)
                            TvDialogAction(stringResource(R.string.apple_music_manage_done), onClick = onDone, modifier = Modifier.fillMaxWidth()
                                .automationId(if (sharing) "apple-music-share-done" else "apple-music-management-done").focusRequester(done)
                                .onFocusChanged { doneFocused = it.isFocused }.focusProperties {
                                    up = FocusRequester.Cancel; down = FocusRequester.Cancel
                                    left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                })
                        }
                    }
                }
            }
        }
    }
}
