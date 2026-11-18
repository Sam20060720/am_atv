package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeMaskRampTest {
    private data class Case(
        val progress: Float,
        val feather: Float,
        val ltrStart: Float,
        val ltrEnd: Float,
        val rtlStart: Float,
        val rtlEnd: Float,
    )

    /** Test the coordinates passed to the renderer; Android tests cover actual mask pixels. */
    @Test fun coordinatesRetainTheFeatherUntilItLeavesTheViewport() {
        val cases = listOf(
            Case(-0.25f, 0.125f, 0f, 0f, 0f, 0f),
            Case(-0.0625f, 0.125f, 0f, 0f, 0f, 0.0625f),
            Case(0f, 0.125f, 0f, 0f, 0f, 0.125f),
            Case(0.0625f, 0.125f, 0f, 0.0625f, 0.0625f, 0.1875f),
            Case(0.5f, 0.125f, 0.375f, 0.5f, 0.5f, 0.625f),
            Case(0.9375f, 0.125f, 0.8125f, 0.9375f, 0.9375f, 1f),
            Case(1f, 0.125f, 0.875f, 1f, 1f, 1f),
            Case(1.0625f, 0.125f, 0.9375f, 1f, 1f, 1f),
            Case(1.25f, 0.125f, 1f, 1f, 1f, 1f),
            Case(0.5f, 0f, 0.5f, 0.5f, 0.5f, 0.5f),
            Case(0.5f, -0.125f, 0.5f, 0.5f, 0.5f, 0.5f),
            Case(0.5f, 1.5f, 0f, 0.5f, 0.5f, 1f),
        )
        for (case in cases) {
            val ltr = karaokeMaskRamp(case.progress, case.feather, false)
            val rtl = karaokeMaskRamp(case.progress, case.feather, true)
            assertEquals("LTR start: $case", case.ltrStart, ltr.start, 0f)
            assertEquals("LTR end: $case", case.ltrEnd, ltr.end, 0f)
            assertEquals("RTL start: $case", case.rtlStart, rtl.start, 0f)
            assertEquals("RTL end: $case", case.rtlEnd, rtl.end, 0f)
        }
    }

    @Test fun mirroredCoordinatesRemainOrderedBoundedAndNoWiderThanTheFeather() {
        for (progress in listOf(-1f, -0.01f, 0f, 0.001f, 0.2f, 0.5f, 0.99f, 1f, 1.01f, 2f)) {
            for (feather in listOf(-0.125f, 0f, 0.001f, 0.125f, 0.5f, 1f, 2f)) {
                val ltr = karaokeMaskRamp(progress, feather, false)
                val mirroredRtl = karaokeMaskRamp(1f - progress, feather, true)
                assertEquals(ltr.start, 1f - mirroredRtl.end, 0.000001f)
                assertEquals(ltr.end, 1f - mirroredRtl.start, 0.000001f)
                for (ramp in listOf(ltr, mirroredRtl)) {
                    assertTrue("Invalid ramp: $ramp", ramp.start in 0f..ramp.end && ramp.end <= 1f)
                    assertTrue(ramp.end - ramp.start <= feather.coerceAtLeast(0f) + 0.000001f)
                }
            }
        }
    }
}
