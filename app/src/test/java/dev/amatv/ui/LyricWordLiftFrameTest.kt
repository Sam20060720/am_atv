package dev.amatv.ui

import dev.amatv.model.LyricTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordLiftFrameTest {
    @Test fun cachedSpringMatchesOriginalThroughSingingExitSeekAndReplay() {
        for (damping in floatArrayOf(.6f, .93f, .99f)) for (stiffness in floatArrayOf(16f, 25f, 80f)) {
            val frame = LyricWordLiftFrame(1_000, 2_800, damping, stiffness)
            val times = (500L..5_000L step 17).toList()
            // Reverse time and replay cover seeks, including an identical held
            // vocal-end pose with changing presence. Compare exact Float bits.
            for (time in times + times.reversed() + times) for (presence in floatArrayOf(1f, .7f, .1f, 0f)) {
                val original = LyricTimeline.wordLiftFraction(time, 1_000, 2_800, presence, damping, stiffness)
                assertEquals(original.toRawBits(), frame.value(time, presence).toRawBits())
            }
        }
    }
}
