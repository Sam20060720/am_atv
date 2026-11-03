package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class IdlePrefetchDeadlineTest {
    @Test fun nestedRequestsConsumeOneSharedThreeMillisecondAllowance() {
        val budget = IdlePrefetchDeadline()
        assertEquals(3_000_000L, budget.cap(Long.MAX_VALUE, 10_000_000L))
        assertEquals(2_000_000L, budget.cap(Long.MAX_VALUE, 11_000_000L))
        assertEquals(500_000L, budget.cap(Long.MAX_VALUE, 12_500_000L))
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 13_000_000L))
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 40_000_000L))
    }

    @Test fun onlyANewFrameRearmsAnExpiredAllowance() {
        val budget = IdlePrefetchDeadline()
        budget.onFrame(1L)
        budget.cap(Long.MAX_VALUE, 10_000_000L)
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 14_000_000L))
        budget.onFrame(1L)
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 15_000_000L))
        budget.onFrame(0L)
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 16_000_000L))
        budget.onFrame(2L)
        assertEquals(3_000_000L, budget.cap(Long.MAX_VALUE, 30_000_000L))
        assertEquals(1_000_000L, budget.cap(Long.MAX_VALUE, 32_000_000L))
    }

    @Test fun finiteFrameworkDeadlinesPassThroughWithoutStartingAnIdleAllowance() {
        val budget = IdlePrefetchDeadline()
        assertEquals(500_000L, budget.cap(500_000L, 1_000_000L))
        assertEquals(0L, budget.cap(0L, 2_000_000L))
        assertEquals(7_000_000L, budget.cap(7_000_000L, 8_000_000L))
        assertEquals(3_000_000L, budget.cap(Long.MAX_VALUE, 100_000_000L))
        assertEquals(19L, budget.cap(19L, 200_000_000L))
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 200_000_000L))
    }

    @Test fun workStartsAfterDrawingButNeverGetsAnotherAllowanceWithinTheFrame() {
        val budget = IdlePrefetchDeadline()
        budget.onFrame(100L)
        assertEquals(3_000_000L, budget.cap(Long.MAX_VALUE, 50_000_000L))
        assertEquals(1L, budget.cap(Long.MAX_VALUE, 52_999_999L))
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 53_000_000L))
        assertEquals(0L, budget.cap(Long.MAX_VALUE, 70_000_000L))
    }
}
