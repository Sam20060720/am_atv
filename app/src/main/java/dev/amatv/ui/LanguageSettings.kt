package dev.amatv.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.core.os.LocaleListCompat
import dev.amatv.R

/** Language changes update configuration in place, preserving the active player. */
@Composable
internal fun LanguageSettings(navigationFocus: FocusRequester? = null) {
    val configuration = LocalConfiguration.current
    var selectedLanguage by remember(configuration) {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().get(0)?.let { locale ->
            if (locale.language == "zh") {
                if (locale.script == "Hant" || locale.country in listOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            } else locale.language
        }.orEmpty())
    }
    val choices = listOf(
        "" to R.string.language_system,
        "en" to R.string.language_english,
        "zh-TW" to R.string.language_traditional_chinese,
        "zh-CN" to R.string.language_simplified_chinese,
    )
    val focus = remember { List(choices.size) { FocusRequester() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.language_title), color = Color.White,
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            choices.forEachIndexed { index, (tag, label) ->
                val selected = tag == selectedLanguage
                FocusSurface(
                    onClick = {
                        if (!selected) {
                            // Choosing the system's current language may produce no
                            // configuration change; selection must still update.
                            selectedLanguage = tag
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        }
                    },
                    modifier = Modifier
                        .weight(1f).height(46.dp)
                        .automationId("language-${tag.ifEmpty { "system" }}")
                        .semantics { this.selected = selected }
                        .focusRequester(focus[index])
                        .focusProperties {
                            left = focus.getOrNull(index - 1) ?: navigationFocus ?: FocusRequester.Cancel
                            right = focus.getOrNull(index + 1) ?: FocusRequester.Cancel
                        },
                    cornerRadius = 23.dp,
                    background = Color.White.copy(alpha = if (selected) .26f else .12f),
                    focusedBackground = Color.White.copy(alpha = .38f),
                    padding = PaddingValues(horizontal = 12.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(label), color = Color.White, fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
