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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R

@Composable
internal fun ArtworkMotionSettings(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, navigationFocus: FocusRequester) {
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.artwork_motion_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.artwork_motion_hint), color = Color.White.copy(alpha = .62f), fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    val label = stringResource(if (enabled) R.string.artwork_motion_on else R.string.artwork_motion_off)
    FocusSurface(
        onClick = { onEnabledChange(!enabled) },
        modifier = Modifier.width(340.dp).height(46.dp).automationId("settings-artwork-motion")
            .semantics { selected = enabled; stateDescription = label }
            .focusProperties { left = navigationFocus; right = FocusRequester.Cancel },
        cornerRadius = 23.dp,
        background = Color.White.copy(alpha = .12f), focusedBackground = Color.White.copy(alpha = .9f),
        padding = PaddingValues(horizontal = 18.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = focusSurfaceContentColor(Color.White, Color(0xFF171717)), fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
