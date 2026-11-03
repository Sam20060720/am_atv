package dev.amatv.ui

import dev.amatv.model.LyricTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricCharacterFrameTest {
    private fun timings(count: Int) = List(count) { index ->
        LyricTimeline.CharacterEmphasisTiming(2_000, 1_000L + index * 100L,
            2_000L + index * 100L, 1.14f)
    }

    @Test fun sharedFramePreservesOriginalSpreadShadowAndScaleAcrossSeeksAndDirections() {
        for (count in 1..7) for (rtl in listOf(false, true)) {
            val authored = timings(count + 1)
            val widths = List(count) { 13f + it * 2f }
            val frame = LyricCharacterFrame(authored, count + 1, count, 1, 3_000L, rtl) { it * it }
            widths.forEachIndexed { index, width -> frame.setWidth(index, width) }
            for (position in listOf(0L, 1_500L, 2_600L, 3_500L, 6_000L, 1_800L)) for (presence in listOf(0f, 0.35f, 1f)) {
                val amounts = List(count) { index ->
                    LyricTimeline.characterEmphasisAmountAt(authored[index + 1], position, 3_000L, presence) { it * it }
                }
                val scales = amounts.mapIndexed { index, amount -> 1f + (authored[index + 1].maximumScale - 1f) * amount }
                // Use the original implementation as the visual reference.
                val expected = LyricTimeline.symmetricExpansionOffsets(widths.indices.map {
                    (scales[it] - 1f) * widths[it] * 0.5f
                }, rtl)
                frame.update(position, presence)
                for (index in 0 until count) {
                    assertEquals(amounts[index], frame.amount(index), 0f)
                    assertEquals(scales[index], frame.scale(index), 0.000001f)
                    assertEquals(expected[index], frame.offset(index), 0.00001f)
                }
                assertEquals(0f, frame.offset(count), 0f) // trailing whitespace never expands
            }
        }
    }

    @Test fun glyphAndShadowReadsShareWorkButGeometryChangesInvalidateIt() {
        var easingCalls = 0
        val frame = LyricCharacterFrame(timings(4), 4, 4, 0, 3_000L, false) {
            easingCalls++; it
        }
        repeat(4) { frame.setWidth(it, 20f) }
        frame.update(1_600L, 1f)
        val firstCalls = easingCalls
        assertTrue(firstCalls > 0)
        repeat(8) { frame.update(1_600L, 1f) }
        frame.setWidth(0, 20f)
        frame.update(1_600L, 1f)
        assertEquals(firstCalls, easingCalls)
        frame.setWidth(0, 40f)
        frame.update(1_600L, 1f)
        assertTrue(easingCalls > firstCalls)
    }

    @Test fun completedWavesReuseTheirHeldPoseButStillRespondToPresenceAndSeek() {
        var easingCalls = 0
        val authored = timings(4)
        val easing: (Float) -> Float = { easingCalls++; it * it * 0.8f }
        val frame = LyricCharacterFrame(authored, 4, 4, 0, 3_000L, false, easing)
        repeat(4) { frame.setWidth(it, 20f) }
        frame.update(5_000L, 1f)
        val completedCalls = easingCalls
        for (position in 5_001L..6_000L) frame.update(position, 1f)
        assertEquals(completedCalls, easingCalls)

        // Changing presence or seeking back into the word still invalidates the
        // held pose and uses the original timeline formula.
        for ((position, presence) in listOf(6_000L to 0.35f, 1_800L to 1f)) {
            frame.update(position, presence)
            for (index in authored.indices) {
                assertEquals(
                    LyricTimeline.characterEmphasisAmountAt(
                        authored[index], position, 3_000L, presence,
                    ) { it * it * 0.8f },
                    frame.amount(index),
                    0f,
                )
            }
        }
        assertTrue(easingCalls > completedCalls)
    }
}
