package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class CollectionSongNavigationTest {
    @Test fun repeatedKeysKeepNewestIntentWhenIntermediateFocusArrives() {
        val cursor = CollectionSongNavigation(4)
        val old = cursor.request(5)
        assertEquals(6, cursor.nextIndex(4, 1, 40))
        val newest = cursor.request(6)
        assertFalse(cursor.isCurrent(old))
        assertTrue(cursor.isCurrent(newest))
        assertEquals(5, cursor.nextStep())
        cursor.focused(5)
        assertEquals(6, cursor.requestedIndex)
        assertTrue(cursor.pending)
        assertEquals(6, cursor.nextStep())
        cursor.focused(6)
        assertFalse(cursor.pending)
    }

    @Test fun reversalAndLeavingPageInvalidateOldHandoff() {
        val cursor = CollectionSongNavigation(8)
        val down = cursor.request(10)
        val up = cursor.request(cursor.nextIndex(8, -1, 40))
        assertEquals(9, cursor.requestedIndex)
        assertFalse(cursor.isCurrent(down))
        cursor.cancel(8)
        assertFalse(cursor.isCurrent(up))
        assertFalse(cursor.pending)
        assertEquals(7, cursor.nextIndex(8, -1, 40))
        assertEquals(0, cursor.nextIndex(0, -1, 40))
        assertEquals(40, cursor.nextIndex(40, 1, 40))
    }

    @Test fun highlightUsesLiveScrollOffsetAndVariableMetadataAnchor() {
        // At density 2 a 58dp focus surface plus 1dp divider has a 118px stride.
        val before = collectionSongHighlightTop(7.5f, 9, 450, -20, 118f)
        val duringScroll = collectionSongHighlightTop(7.5f, 9, 418, -20, 118f)
        assertEquals(529f, before, .001f)
        assertEquals(before - 32f, duringScroll, .001f)
        // Virtualization replaces the anchor, but not the highlighted screen position.
        assertEquals(duringScroll, collectionSongHighlightTop(7.5f, 10, 536, -20, 118f), .001f)
    }

    @Test fun classicSongOnlyListDoesNotReserveMetadataRows() {
        val top = collectionSongHighlightTop(3.5f, 3, 90, -10, 65f, headerCount = 0)
        assertEquals(132.5f, top, .001f)
        assertEquals(top, collectionSongHighlightTop(3.5f, 4, 155, -10, 65f, headerCount = 0), .001f)
        assertEquals(top, collectionSongHighlightTop(3.5f, 5, 90, -10, 65f), .001f)
    }

    @Test fun returningToRetainedSongStillRequiresAPlacedFocusNode() {
        val cursor = CollectionSongNavigation(14)
        cursor.request(14)
        assertTrue(cursor.pending)
        assertEquals(14, cursor.nextStep())
        cursor.focused(14)
        assertFalse(cursor.pending)
    }
}
