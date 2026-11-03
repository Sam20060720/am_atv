package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogMenuFocusVisitTest {
    private val rows = listOf("library", "favorite", "like", "playlist", "create")

    @Test fun backgroundStateReadsDoNotChooseTheFirstEnabledActionInTheMiddle() {
        val visit = CatalogMenuFocusVisit()
        // Permission/rating reads may still be pending for the first three
        // rows. Native auto-focus must not replace the deliberate top entry.
        visit.focused("playlist")
        assertEquals("library", visit.entryTarget(rows))
    }

    @Test fun aFailedWindowHandoffCanRetryTheSameTopRow() {
        val visit = CatalogMenuFocusVisit()
        assertEquals("library", visit.entryTarget(rows))
        // No success was reported while the Dialog lacked its focus target.
        assertEquals("library", visit.entryTarget(rows))
        visit.entrySucceeded("library")
        assertNull(visit.entryTarget(rows))
    }

    @Test fun lateReadsDoNotResetUserNavigationOrStealFocusForAnInsertedAction() {
        val visit = CatalogMenuFocusVisit()
        visit.entrySucceeded("library")
        visit.focused("playlist")
        val withArtist = rows + "artist"
        assertNull(visit.entryTarget(withArtist))
        assertNull(visit.replacementIndex(withArtist, "playlist", 3))
        assertEquals("playlist", visit.restoreTarget(withArtist))
    }

    @Test fun anActionThatBecomesDisabledRetainsItsIdentityAndPosition() {
        val visit = CatalogMenuFocusVisit()
        visit.entrySucceeded("library")
        // Availability is intentionally absent from row identity: an API
        // response saying 'already added' must not send focus to another row.
        assertNull(visit.replacementIndex(rows, "library", 0))
        assertEquals("library", visit.restoreTarget(rows))
    }

    @Test fun removalRepairsTheNearestRowWithoutWrappingOrChoosingByEnabledState() {
        val visit = CatalogMenuFocusVisit()
        assertEquals(2, visit.replacementIndex(listOf("library", "favorite", "playlist"), "create", 4))
        assertNull(visit.replacementIndex(emptyList(), "create", 4))
    }

    @Test fun lateRowsCannotRaceWindowRestorationAndRemovedRowRepair() {
        val visit = CatalogMenuFocusVisit()
        assertEquals(true, visit.resumed(true))
        visit.entrySucceeded("library")
        visit.focused("playlist")
        // A new row/read result must leave repair to the retained-focus path.
        assertEquals(false, visit.resumed(true))
        assertNull(visit.entryTarget(rows + "artist"))
        assertEquals(false, visit.resumed(false))
        assertEquals(true, visit.resumed(true))
        assertEquals("playlist", visit.restoreTarget(rows))
        assertEquals(false, visit.resumed(true))
    }
}
