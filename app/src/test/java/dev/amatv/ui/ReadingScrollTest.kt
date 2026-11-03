package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingScrollTest {
    @Test fun oversizedTextPagesWithoutSkippingTheRemainingEnd() {
        assertEquals(390f, readingPageScrollAmount(100f, 1600f, 0f, 600f, 16f, 1), 0f)
        assertEquals(116f, readingPageScrollAmount(-900f, 1600f, 0f, 600f, 16f, 1), 0f)
        assertEquals(0f, readingPageScrollAmount(-1016f, 1600f, 0f, 600f, 16f, 1), 0f)
    }

    @Test fun reversePagingStopsAtTheBeginningAndAllowsFocusToLeave() {
        assertEquals(-390f, readingPageScrollAmount(-800f, 1600f, 0f, 600f, 16f, -1), 0f)
        assertEquals(-116f, readingPageScrollAmount(-100f, 1600f, 0f, 600f, 16f, -1), 0f)
        assertEquals(0f, readingPageScrollAmount(16f, 1600f, 0f, 600f, 16f, -1), 0f)
    }

    @Test fun actualLazyListLimitsTakePriorityOverPaddingAndOversizedGeometry() {
        // The first lazy item can still have a negative calculated reading
        // delta at list start. Consuming that Up forever would trap the remote.
        assertEquals(0f, readingPageScrollAmount(0f, 1600f, -12f, 600f, 16f, -1,
            canScrollBackward = false), 0f)
        assertEquals(0f, readingPageScrollAmount(-1000f, 1600f, 0f, 600f, 16f, 1,
            canScrollForward = false), 0f)
    }

    @Test fun shortTextNeverConsumesSectionNavigation() {
        assertEquals(0f, readingPageScrollAmount(100f, 200f, 0f, 600f, 16f, 1), 0f)
        assertEquals(0f, readingPageScrollAmount(100f, 200f, 0f, 600f, 16f, -1), 0f)
    }
}
