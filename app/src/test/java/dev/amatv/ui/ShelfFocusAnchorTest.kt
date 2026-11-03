package dev.amatv.ui

import androidx.compose.ui.graphics.Color
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

class ShelfFocusAnchorTest {
    private fun track(id: String) = Track(id, id, "artist", "album", 1, listOf(Color.Black), emptyList())
    @Test fun earlierNetworkResultDoesNotMoveSelectionToAnotherShelf() {
        val selected = track("selected")
        val shelves = listOf(ContentShelf("slow", "slow", listOf(track("first"))), ContentShelf("recent", "recent", listOf(track("a"), selected)))
        assertEquals(ShelfFocusPosition(1, 1), resolveShelfFocusAnchor(ShelfFocusAnchor("recent", selected.shelfFocusKey(), 1), shelves, listOf(1, 2)))
    }
    @Test fun refreshKeepsTheResourceWhenItemsMoveWithinItsShelf() {
        val selected = track("selected")
        val shelves = listOf(ContentShelf("recent", "recent", listOf(selected, track("a"))))
        assertEquals(ShelfFocusPosition(0, 0), resolveShelfFocusAnchor(ShelfFocusAnchor("recent", selected.shelfFocusKey(), 1), shelves, listOf(2)))
    }
    @Test fun removedResourceUsesNearbyValidItemAndMissingShelfHasNoFalseMatch() {
        val shelves = listOf(ContentShelf("recent", "recent", listOf(track("a"))))
        assertEquals(ShelfFocusPosition(0, 0), resolveShelfFocusAnchor(ShelfFocusAnchor("recent", "removed", 7), shelves, listOf(1)))
        assertNull(resolveShelfFocusAnchor(ShelfFocusAnchor("removed-shelf", null, 0), shelves, listOf(1)))
    }

    @Test fun rootEntryCannotFinishOnTheRetainedOldRowWithTheSameColumn() {
        val first = track("first")
        val shelves = listOf(ContentShelf("home", "Home", listOf(first)), ContentShelf("quick-picks", "Quick Picks", listOf(track("old"))))
        val target = firstShelfEntry(shelves, listOf(1, 1))!!
        assertFalse(shelfEntryHasFocus(target, shelves, ShelfFocusAnchor("quick-picks", track("old").shelfFocusKey(), 0)))
        assertTrue(shelfEntryHasFocus(target, shelves, ShelfFocusAnchor("home", first.shelfFocusKey(), 0)))
    }

    @Test fun progressiveInsertionRequiresTheNewActualFirstResourceBeforeCompleting() {
        val old = track("old")
        val before = listOf(ContentShelf("recent", "Recent", listOf(old)))
        val observed = ShelfFocusAnchor("recent", old.shelfFocusKey(), 0)
        assertTrue(shelfEntryHasFocus(firstShelfEntry(before, listOf(1))!!, before, observed))
        val after = listOf(ContentShelf("recommendations", "For You", listOf(track("new")))) + before
        assertFalse(shelfEntryHasFocus(firstShelfEntry(after, listOf(1, 1))!!, after, observed))
        assertFalse(shelfEntryHasFocus(ShelfFocusPosition(0, 0), after, ShelfFocusAnchor("recommendations", old.shelfFocusKey(), 0)))
        assertTrue(shelfEntryHasFocus(ShelfFocusPosition(0, 0), after, ShelfFocusAnchor("recommendations", track("new").shelfFocusKey(), 0)))
    }
}
