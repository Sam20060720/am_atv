package dev.amatv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R

@Composable
internal fun SpectrumPermissionSettings(granted: Boolean, onRequestPermission: () -> Unit, navigationFocus: FocusRequester) {
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.spectrum_permission_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.spectrum_permission_hint), color = Color.White.copy(alpha = .62f), fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    FocusSurface(
        onClick = onRequestPermission,
        modifier = Modifier.width(340.dp).height(46.dp).automationId("settings-spectrum-permission")
            .focusProperties { left = navigationFocus; right = FocusRequester.Cancel },
        cornerRadius = 23.dp,
        background = Color.White.copy(alpha = .12f), focusedBackground = Color.White.copy(alpha = .9f),
        padding = PaddingValues(horizontal = 18.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(if (granted) R.string.spectrum_permission_manage else R.string.spectrum_permission_allow),
                color = focusSurfaceContentColor(Color.White, Color(0xFF171717)), fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
