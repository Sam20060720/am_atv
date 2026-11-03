package dev.amatv.data

import androidx.compose.ui.graphics.Color
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import dev.amatv.model.Track

object MockCatalog {
    /** Missing catalog metadata never turns a real Library resource into a demo. */
    fun isDemoTrack(item: Track): Boolean = item.catalogId == null && item.resourceId == null &&
        item.resourceType == null && tracks.any { it.id == item.id }

    val tracks = listOf(
        track(
            id = "aurora",
            title = "After the Aurora",
            artist = "Northbound",
            album = "Glass Horizons",
            colors = listOf(Color(0xFF28135B), Color(0xFFB12C68), Color(0xFFEF6A62)),
            lines = listOf(
                "City lights dissolve into the rain",
                "We keep moving like a midnight train",
                "Hold the silence close and let it glow",
                "After the aurora we will know",
                "Every color finds another name",
                "Nothing in the dark remains the same",
            ),
        ),
        track(
            id = "coast",
            title = "Violet Coast",
            artist = "Mira Vale",
            album = "Open Water",
            colors = listOf(Color(0xFF0A345C), Color(0xFF146E82), Color(0xFFE4A765)),
            lines = listOf(
                "Blue on blue where the long waves turn",
                "Salt in the air and the skyline burns",
                "Follow the tide to the violet coast",
                "Stay for the moment we wanted most",
            ),
        ),
        track(
            id = "echo",
            title = "Soft Echoes",
            artist = "Paper Satellites",
            album = "Night Rooms",
            colors = listOf(Color(0xFF251B2E), Color(0xFF765270), Color(0xFFC79A98)),
            lines = listOf(
                "Soft echoes moving through the hall",
                "A little light is climbing up the wall",
                "Turn the record over one more time",
                "Let the quiet settle into rhyme",
            ),
        ),
        track(
            id = "orbit",
            title = "Out of Orbit",
            artist = "June Assembly",
            album = "Low Gravity",
            colors = listOf(Color(0xFF181C45), Color(0xFF4E53A6), Color(0xFFFF7F67)),
            lines = listOf(
                "We are out of orbit for the night",
                "Drawing little circles in the light",
                "If the morning asks us where we went",
                "Tell it every second was well spent",
            ),
        ),
        track(
            id = "garden",
            title = "Electric Garden",
            artist = "The High Lines",
            album = "Signal Bloom",
            colors = listOf(Color(0xFF12352E), Color(0xFF2E896B), Color(0xFFD6D452)),
            lines = listOf(
                "Meet me in the electric garden",
                "Where the neon leaves are turning green",
                "Every pulse becomes a new beginning",
                "Every beat becomes a brighter scene",
            ),
        ),
    )

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String,
        colors: List<Color>,
        lines: List<String>,
    ): Track {
        var cursor = 2_000L
        val timedLines = lines.map { line ->
            val words = line.split(" ").map { word ->
                val start = cursor
                cursor += 520L
                LyricWord(
                    text = word,
                    startMs = start,
                    endMs = cursor,
                    // These synthetic words have no source syllable-group metadata.
                    // Inventing a whole-line group made every glyph expand and
                    // translate against its stationary dim copy.
                    emphasisGroupId = null,
                )
            }
            cursor += 1_100L
            LyricLine(words)
        }
        return Track(id, title, artist, album, 48_000L, colors, timedLines)
    }
}
