package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.AudioQuality
import dev.amatv.integration.AudioQualityPreferences

@Composable
internal fun AudioQualitySettings(preferences: AudioQualityPreferences, navigationFocus: FocusRequester) {
    val selection by preferences.selection.collectAsState()
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.audio_quality_title), color = Color.White,
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.audio_quality_hint), color = Color.White.copy(alpha = .62f), fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    AudioQuality.entries.forEach { quality ->
        val isSelected = quality == selection.quality
        FocusSurface(
            onClick = { preferences.setQuality(quality) },
            modifier = Modifier.width(614.dp).height(46.dp)
                .automationId("settings-audio-quality-${quality.name.lowercase()}")
                .semantics { selected = isSelected }
                .focusProperties { left = navigationFocus; right = FocusRequester.Cancel },
            cornerRadius = 23.dp,
            background = Color.White.copy(alpha = if (isSelected) .22f else .12f),
            focusedBackground = Color.White.copy(alpha = .9f),
            padding = PaddingValues(horizontal = 18.dp),
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                val color = focusSurfaceContentColor(Color.White, Color(0xFF171717))
                Text(stringResource(if (quality == AudioQuality.Efficient) R.string.audio_quality_efficient
                    else R.string.audio_quality_high), color = color, fontSize = 15.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isSelected) Text("✓", color = color, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    UnavailableAudioQualityRow(stringResource(R.string.audio_quality_lossless))
    Spacer(Modifier.height(8.dp))
    UnavailableAudioQualityRow(stringResource(R.string.audio_quality_hires))
    Spacer(Modifier.height(12.dp))
    SettingsBooleanToggle(stringResource(R.string.audio_quality_atmos), selection.atmosEnabled,
        { preferences.setAtmosEnabled(!selection.atmosEnabled) }, navigationFocus, "settings-dolby-atmos")
    Text(stringResource(R.string.audio_quality_atmos_hint), color = Color.White.copy(alpha = .62f),
        fontSize = 14.sp, modifier = Modifier.width(614.dp).padding(top = 6.dp))
}

/** Informational until full-track authorization is available; never a dead remote target. */
@Composable
private fun UnavailableAudioQualityRow(label: String) {
    Row(Modifier.width(614.dp).height(46.dp)
        .background(Color.White.copy(alpha = .05f), RoundedCornerShape(23.dp))
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = .4f), fontSize = 15.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.audio_quality_unavailable), color = Color.White.copy(alpha = .4f), fontSize = 14.sp)
    }
}
