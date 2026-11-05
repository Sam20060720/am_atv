package dev.amatv.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackMetadataCursorTest {
    @Test fun restoredPositionSurvivesAnUnpreparedEngineButNeverLeaksToAnotherOccurrence() {
        val cursor = PlaybackMetadataCursor()
        cursor.remember(1, 5, 67_000)
        assertEquals(67_000L, cursor.position(1, 5))
        assertNull(cursor.position(1, 6))
        assertNull(cursor.position(2, 5))
        cursor.remember(1, 5, 0) // Explicit restart/seek replaces the saved cursor.
        assertEquals(0L, cursor.position(1, 5))
        cursor.clear()
        assertNull(cursor.position(1, 5))
    }
}
