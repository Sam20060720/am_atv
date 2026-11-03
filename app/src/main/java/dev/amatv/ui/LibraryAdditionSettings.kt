package dev.amatv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.amatv.integration.LibraryAdditionPreferences

@Composable
internal fun LibraryAdditionSettings(preferences: LibraryAdditionPreferences, navigationFocus: FocusRequester) {
    val settings by preferences.settings.collectAsState()
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.library_addition_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    LibraryAdditionToggle(stringResource(R.string.library_addition_playlists), settings.addPlaylistSongs,
        { preferences.setAddPlaylistSongs(!settings.addPlaylistSongs) }, navigationFocus, "settings-library-playlists")
    Spacer(Modifier.height(8.dp))
    LibraryAdditionToggle(stringResource(R.string.library_addition_favorites), settings.addFavoriteItems,
        { preferences.setAddFavoriteItems(!settings.addFavoriteItems) }, navigationFocus, "settings-library-favorites")
}

@Composable
private fun LibraryAdditionToggle(label: String, enabled: Boolean, onClick: () -> Unit,
    navigationFocus: FocusRequester, id: String) {
    val state = stringResource(if (enabled) R.string.library_addition_on else R.string.library_addition_off)
    FocusSurface(onClick = onClick,
        modifier = Modifier.width(614.dp).height(46.dp).automationId(id)
            .semantics { selected = enabled; stateDescription = state }
            .focusProperties { left = navigationFocus; right = FocusRequester.Cancel },
        cornerRadius = 23.dp, background = Color.White.copy(alpha = .12f), focusedBackground = Color.White.copy(alpha = .9f),
        padding = PaddingValues(horizontal = 18.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val color = focusSurfaceContentColor(Color.White, Color(0xFF171717))
            Text(label, color = color, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(18.dp))
            Text(state, color = color, fontSize = 15.sp)
        }
    }
}
