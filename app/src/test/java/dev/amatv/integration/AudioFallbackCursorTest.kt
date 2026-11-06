package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioFallbackCursorTest {
    @Test fun coldRestoreDoesNotFallBackToZero() {
        val cursor = AudioFallbackCursor(120_000L, false)
        cursor.observe(0L, false)
        cursor.observe(0L, true)
        assertEquals(120_000L, cursor.positionMs)
        assertFalse(cursor.wantsPlay)
    }

    @Test fun explicitSeekWinsOverOldRendererPosition() {
        val cursor = AudioFallbackCursor(0L, true)
        cursor.observe(15_000L, true)
        cursor.seekTo(180_000L)
        cursor.observe(15_000L, true)
        assertEquals(180_000L, cursor.positionMs)
        cursor.observe(180_100L, true)
        cursor.observe(182_000L, true)
        assertEquals(182_000L, cursor.positionMs)
    }

    @Test fun pauseDuringFallbackPreservesTheLatestIntent() {
        val cursor = AudioFallbackCursor(0L, true)
        cursor.observe(18_000L, true)
        cursor.wantsPlay = false
        cursor.observe(0L, false)
        assertEquals(18_000L, cursor.positionMs)
        assertFalse(cursor.wantsPlay)
    }
}
