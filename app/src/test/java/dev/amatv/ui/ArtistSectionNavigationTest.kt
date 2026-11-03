package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class ArtistSectionNavigationTest {
    private val all = listOf(ArtistSectionNavigation.Profile, "top", "albums", "singles", "compilations", "appears")
    private fun navigation() = ArtistSectionNavigation(all).apply {
        updateVisible(all)
        focused("top")
    }

    @Test fun fastDownAndReversalPreserveIntentBeforeLazyRowsGainFocus() {
        val cursor = navigation()
        val first = cursor.requestNext("top", 1)
        cursor.requestNext("top", 1)
        cursor.requestNext("top", 1)
        assertEquals("compilations", cursor.requestedKey)
        assertEquals("albums", cursor.nextStep())
        cursor.focused("albums")
        val reverse = cursor.requestNext("albums", -1)
        cursor.requestNext("albums", -1)
        assertEquals("albums", cursor.requestedKey)
        assertFalse(cursor.isCurrent(first))
        assertFalse(cursor.isCurrent(reverse))
        cursor.focused("albums")
        assertFalse(cursor.pending)
    }

    @Test fun anEmptySectionDisappearingDoesNotSendPendingFocusToNavigation() {
        val cursor = navigation()
        cursor.requestNext("top", 1)
        cursor.requestNext("top", 1)
        cursor.requestNext("top", 1)
        cursor.updateVisible(all - "compilations")
        assertEquals("appears", cursor.requestedKey)
        cursor.requestNext("top", -1)
        cursor.requestNext("top", -1)
        assertEquals("albums", cursor.requestedKey)
        assertEquals("albums", cursor.nextStep())
    }

    @Test fun disappearingFocusedStatusChoosesNeighbourRatherThanDetachedHero() {
        val cursor = navigation()
        cursor.focused("compilations")
        cursor.updateVisible(all - "compilations")
        cursor.repairRemoved("compilations")
        assertEquals("appears", cursor.requestedKey)
        assertEquals("appears", cursor.nextStep())
    }

    @Test fun lateralNavigationInvalidatesAnUnplacedVerticalHandoff() {
        val cursor = navigation()
        val ticket = cursor.requestNext("top", 1)
        cursor.cancel("top")
        assertFalse(cursor.isCurrent(ticket))
        assertFalse(cursor.pending)
        assertEquals("top", cursor.requestedKey)
    }

    @Test fun movingAboveFirstSectionReturnsToProfileBeforeAnyNavigationExit() {
        val cursor = navigation()
        cursor.requestNext("top", -1)
        assertEquals(ArtistSectionNavigation.Profile, cursor.requestedKey)
        assertEquals(ArtistSectionNavigation.Profile, cursor.nextStep())
        cursor.focused(ArtistSectionNavigation.Profile)
        assertFalse(cursor.pending)
    }

    @Test fun lastSectionDownIsBoundedAndIntermediateFocusDoesNotReplaceDestination() {
        val cursor = navigation()
        cursor.focused("singles")
        cursor.requestNext("singles", 1)
        cursor.requestNext("singles", 1)
        cursor.requestNext("singles", 1)
        assertEquals("appears", cursor.requestedKey)
        cursor.focused("compilations")
        assertTrue(cursor.pending)
        assertEquals("appears", cursor.nextStep())
        cursor.focused("appears")
        assertFalse(cursor.pending)
    }
}
