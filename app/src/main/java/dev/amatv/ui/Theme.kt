package dev.amatv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TvColors = darkColorScheme(
    primary = Color(0xFFFA2D55),
    onPrimary = Color.White,
    background = Color(0xFF08070A),
    onBackground = Color.White,
    surface = Color(0xFF17151B),
    onSurface = Color.White,
)

@Composable
fun AmAtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColors) {
        TvTypography(content)
    }
}

