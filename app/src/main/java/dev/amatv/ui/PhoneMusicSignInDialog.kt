package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.amatv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class PhoneSignInPhase { Starting, Waiting, Authorizing, Complete, Expired, Failed }

/** A TV-only status/focus surface. Authorization runs in the phone's browser. */
@Composable
internal fun PhoneMusicSignInDialog(
    url: String?,
    phase: PhoneSignInPhase,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val retryable = phase == PhoneSignInPhase.Expired || phase == PhoneSignInPhase.Failed
    val status = stringResource(when (phase) {
        PhoneSignInPhase.Starting -> R.string.phone_sign_in_starting
        PhoneSignInPhase.Waiting -> R.string.phone_sign_in_waiting
        PhoneSignInPhase.Authorizing -> R.string.phone_sign_in_authorizing
        PhoneSignInPhase.Complete -> R.string.phone_sign_in_complete
        PhoneSignInPhase.Expired -> R.string.phone_sign_in_expired
        PhoneSignInPhase.Failed -> R.string.phone_sign_in_failed
    })
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false, windowTitle = "AMATV.PhoneSignIn"),
    ) {
        TvBrowseViewport {
            LaunchedEffect(retryable) {
                withFrameNanos { }
                runCatching { (if (retryable) retryFocus else cancelFocus).requestFocus() }
            }
            CompositionLocalProvider(LocalFocusTargetsEnabled provides true,
                LocalFocusFrameEnabled provides false, LocalContentFocusEntry provides null) {
                Box(Modifier.fillMaxSize().automationId("phone-sign-in-dialog")
                    .background(Color.Black.copy(alpha = .55f)), Alignment.Center) {
                    val shape = RoundedCornerShape(28.dp)
                    Row(Modifier.width(710.dp).background(Color(0xFF242429), shape)
                        .border(.5.dp, Color.White.copy(alpha = .09f), shape).padding(30.dp),
                        horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhoneSignInQr(url.takeUnless { retryable || phase == PhoneSignInPhase.Complete }, phase, Modifier.size(226.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.phone_sign_in_title), color = Color.White,
                                fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.phone_sign_in_instructions), color = Color.White.copy(alpha = .70f),
                                fontSize = 15.sp, lineHeight = 22.sp)
                            Text(status, color = if (retryable) Color(0xFFFFB0BA) else Color.White,
                                fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.automationId("phone-sign-in-status"))
                            if (!url.isNullOrBlank() && !retryable && phase != PhoneSignInPhase.Complete) {
                                Text(url, color = Color.White.copy(alpha = .50f), fontSize = 12.sp,
                                    lineHeight = 17.sp, maxLines = 3,
                                    modifier = Modifier.automationId("phone-sign-in-url"))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (retryable) TvDialogAction(stringResource(R.string.phone_sign_in_retry), onRetry,
                                    Modifier.weight(1f).focusRequester(retryFocus).focusProperties {
                                        left = FocusRequester.Cancel; right = cancelFocus
                                        up = FocusRequester.Cancel; down = FocusRequester.Cancel
                                    })
                                TvDialogAction(stringResource(if (phase == PhoneSignInPhase.Complete)
                                    R.string.phone_sign_in_done else R.string.phone_sign_in_cancel), onDismiss,
                                    Modifier.weight(1f).focusRequester(cancelFocus).focusProperties {
                                        left = if (retryable) retryFocus else FocusRequester.Cancel
                                        right = FocusRequester.Cancel; up = FocusRequester.Cancel; down = FocusRequester.Cancel
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneSignInQr(url: String?, phase: PhoneSignInPhase, modifier: Modifier = Modifier) {
    val result by key(url) {
        produceState<Result<BitMatrix>?>(null, url) {
            value = if (url.isNullOrBlank()) null else withContext(Dispatchers.Default) {
                runCatching { phoneSignInQrMatrix(url) }
            }
        }
    }
    val description = stringResource(R.string.phone_sign_in_qr_description)
    val code = result?.getOrNull()
    Box(modifier.background(if (code != null) Color.White else Color.White.copy(alpha = .045f), RoundedCornerShape(12.dp))
        .semantics { contentDescription = description }, Alignment.Center) {
        if (code == null) {
            if (phase == PhoneSignInPhase.Complete) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(64.dp), Color.White)
            else if (phase == PhoneSignInPhase.Expired || phase == PhoneSignInPhase.Failed || result?.isFailure == true)
                Icon(Icons.Rounded.PhoneIphone, null, Modifier.size(64.dp), Color.White.copy(alpha = .35f))
            else AppleLoadingIndicator(Modifier.size(26.dp))
        } else QrCodeCanvas(code, Modifier.fillMaxSize().padding(8.dp).automationId("phone-sign-in-qr"))
    }
}

internal fun phoneSignInQrMatrix(url: String): BitMatrix {
    require(url.length in 1..2048)
    return QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"))
}
