package dev.amatv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original TV17 paths in their complete 64×64 canvas. See apple-tv-ui-evidence.md. */
internal object TvPlaybackIcons {
    val Play: ImageVector by lazy {
        officialVector("TV17.Play", "M26.68,42.12 C27.22,42.12 27.68,41.94 28.31,41.58 L41.11,34.17 C42.16,33.57 42.67,33.01 42.67,32.14 C42.67,31.26 42.16,30.70 41.11,30.10 L28.31,22.69 C27.68,22.33 27.22,22.16 26.68,22.16 C25.63,22.16 24.77,22.98 24.77,24.32 L24.77,39.96 C24.77,41.30 25.63,42.12 26.68,42.12 Z")
    }

    val Pause: ImageVector by lazy {
        officialVector("TV17.Pause", "M28.90,41.43 C30.09,41.43 30.69,40.82 30.69,39.64 L30.69,24.62 C30.69,23.44 30.09,22.85 28.90,22.83 L26.23,22.83 C25.06,22.83 24.44,23.44 24.44,24.62 L24.44,39.64 C24.43,40.82 25.03,41.43 26.23,41.43 L28.90,41.43 Z M37.76,41.43 C38.93,41.43 39.55,40.82 39.55,39.64 L39.55,24.62 C39.55,23.44 38.93,22.83 37.76,22.83 L35.08,22.83 C33.90,22.83 33.30,23.44 33.30,24.62 L33.30,39.64 C33.30,40.82 33.89,41.43 35.08,41.43 L37.76,41.43 Z")
    }

    private fun officialVector(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            // The shipped image is 64 design pixels: 32dp at the 960×540 viewport.
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 64f,
            viewportHeight = 64f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.White),
        ).build()
}
