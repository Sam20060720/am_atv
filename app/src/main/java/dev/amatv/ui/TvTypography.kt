package dev.amatv.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.amatv.R

private val TvFontFamily = FontFamily(
    Font(R.font.tv_sfhello_regular, FontWeight.Normal),
    Font(R.font.tv_sfhello_medium, FontWeight.Medium),
    Font(R.font.tv_sfhello_semibold, FontWeight.SemiBold),
    Font(R.font.tv_sfhello_bold, FontWeight.Bold),
)

/** Apply only within the browse/control viewport; existing type sizes and weights stay local. */
@Composable
internal fun TvTypography(content: @Composable () -> Unit) {
    val inheritedTypography = MaterialTheme.typography
    val inheritedTextStyle = LocalTextStyle.current
    val typography = remember(inheritedTypography) {
        inheritedTypography.copy(
            displayLarge = inheritedTypography.displayLarge.copy(fontFamily = TvFontFamily),
            displayMedium = inheritedTypography.displayMedium.copy(fontFamily = TvFontFamily),
            displaySmall = inheritedTypography.displaySmall.copy(fontFamily = TvFontFamily),
            headlineLarge = inheritedTypography.headlineLarge.copy(fontFamily = TvFontFamily),
            headlineMedium = inheritedTypography.headlineMedium.copy(fontFamily = TvFontFamily),
            headlineSmall = inheritedTypography.headlineSmall.copy(fontFamily = TvFontFamily),
            titleLarge = inheritedTypography.titleLarge.copy(fontFamily = TvFontFamily),
            titleMedium = inheritedTypography.titleMedium.copy(fontFamily = TvFontFamily),
            titleSmall = inheritedTypography.titleSmall.copy(fontFamily = TvFontFamily),
            bodyLarge = inheritedTypography.bodyLarge.copy(fontFamily = TvFontFamily),
            bodyMedium = inheritedTypography.bodyMedium.copy(fontFamily = TvFontFamily),
            bodySmall = inheritedTypography.bodySmall.copy(fontFamily = TvFontFamily),
            labelLarge = inheritedTypography.labelLarge.copy(fontFamily = TvFontFamily),
            labelMedium = inheritedTypography.labelMedium.copy(fontFamily = TvFontFamily),
            labelSmall = inheritedTypography.labelSmall.copy(fontFamily = TvFontFamily),
        )
    }
    val textStyle = remember(inheritedTextStyle) { inheritedTextStyle.copy(fontFamily = TvFontFamily) }
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(LocalTextStyle provides textStyle, content = content)
    }
}
