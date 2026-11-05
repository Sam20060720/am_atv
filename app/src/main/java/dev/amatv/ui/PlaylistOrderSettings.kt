package dev.amatv.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.PlaylistOrderPreferences

@Composable
internal fun PlaylistOrderSettings(preferences: PlaylistOrderPreferences, navigationFocus: FocusRequester) {
    val reversed by preferences.reversed.collectAsState()
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.playlist_order_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.playlist_order_hint), color = Color.White.copy(alpha = .62f), fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    SettingsBooleanToggle(stringResource(R.string.playlist_order_reverse), reversed,
        { preferences.setReversed(!reversed) }, navigationFocus, "settings-playlist-reverse")
}
