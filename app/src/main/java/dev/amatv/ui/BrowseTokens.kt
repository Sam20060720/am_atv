package dev.amatv.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

/** Match the shipped area-based design scaling across Android TV density settings. */
@Composable
internal fun TvBrowseViewport(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = sqrt(constraints.maxWidth.toFloat() * constraints.maxHeight / (960f * 540f))
        CompositionLocalProvider(LocalDensity provides Density(scale, LocalDensity.current.fontScale)) {
            TvTypography { content() }
        }
    }
}

/** Apple Music television photo proportions on a 960dp-wide canvas. */
internal object BrowseTokens {
    val Background = Color(0xFF536172)
    val Surface = Color(0xFF464B5A)
    val SafeHorizontal = 40.dp
    val SafeTop = 30.dp
    // Keep the first content line clear of the 29.5dp + 30dp navigation pill.
    val ContentTop = 78.dp
    val ColumnGap = 18.dp
    val ShelfGap = 14.dp
    val HeadingSize = 18.sp
    val HeadingLineHeight = 23.sp
    val HeadingCardGap = 8.dp
    val RowHorizontalPadding = 14.dp
    val RowVerticalPadding = 20.dp

    val ShowcaseWidth = 200.dp
    val ShowcaseHeight = 278.dp
    val ShowcaseRadius = 10.dp
    val ShowcaseRowHeight = ShowcaseHeight + RowVerticalPadding * 2
    val CardWidth = 124.dp
    val CardHeight = 170.dp
    val CardRadius = 6.dp
    val CardRowHeight = CardHeight + RowVerticalPadding * 2

    const val ShowcaseFocusScale = 1.035f
    const val CardFocusScale = 1.04f
}
