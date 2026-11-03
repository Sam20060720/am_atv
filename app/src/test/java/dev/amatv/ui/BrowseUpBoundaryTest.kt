package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class BrowseUpBoundaryTest {
    @Test fun heldUpCannotCrossIntoMenuAfterReachingTheFirstRow() {
        val edge = BrowseUpBoundary()
        edge.upDown(0, false) // Press begins below the first row.
        assertFalse(edge.allowsMenuFocus())
        repeat(12) { edge.upDown(it + 1, true); assertFalse(edge.allowsMenuFocus()) }
        edge.release()
        edge.upDown(0, true) // A new press after the viewport has settled.
        assertTrue(edge.allowsMenuFocus())
    }

    @Test fun fastFreshPressStillWaitsForTheRealViewportToReachItsTop() {
        val edge = BrowseUpBoundary()
        edge.upDown(0, false)
        edge.release()
        edge.upDown(0, false) // Cursor is row zero, but reveal is still running.
        assertFalse(edge.allowsMenuFocus())
        edge.release()
        edge.upDown(0, true)
        assertTrue(edge.allowsMenuFocus())
        edge.upDown(1, true)
        assertFalse(edge.allowsMenuFocus())
        edge.release() // Left/Back also releases the Up-only guard.
        assertTrue(edge.allowsMenuFocus())
    }
}
