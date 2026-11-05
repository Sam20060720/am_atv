package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.amatv.model.Track
import dev.amatv.model.EditorialMotionAsset
import androidx.compose.ui.unit.dp

class HomeArtworkPrefetchTest {
    @Test fun prefetchUsesTheActualStyleCoverAndChosenPosterGeometry() {
        val square = EditorialMotionAsset("square", "https://example.test/square.m3u8", "https://example.test/square.jpg", 600, 600)
        val portrait = EditorialMotionAsset("portrait", "https://example.test/portrait.m3u8", "https://example.test/portrait.jpg", 600, 800)
        val track = Track("123", "Album", "Artist", "", 0L, emptyList(), emptyList(),
            motionArtworkVariants = listOf(square, portrait))
        assertEquals(HomeArtworkPrefetchGeometry(square.previewUrl!!, 168.dp, 168.dp),
            homeArtworkPrefetchGeometry(track, false, HomeShelfLayout(classic = true)))
        assertEquals(HomeArtworkPrefetchGeometry(square.previewUrl!!, 210.dp, 184.dp),
            homeArtworkPrefetchGeometry(track, true, HomeShelfLayout(classic = true)))
        assertEquals(HomeArtworkPrefetchGeometry(portrait.previewUrl!!, 200.dp, 278.dp),
            homeArtworkPrefetchGeometry(track, true, HomeShelfLayout(classic = false)))
    }

    @Test fun visibleCoversPrecedeSpeculationEvenWhenTheAllowanceIsSmall() {
        val window = homeArtworkPrefetchWindow(
            listOf(20, 20, 20),
            listOf(HomeArtworkViewportRow(0, listOf(3, 4, 5)), HomeArtworkViewportRow(1, listOf(7, 8))),
            nextRowFirstItem = 0, nextRowVisibleCount = 6, limit = 4,
        )
        assertEquals(listOf(p(0, 3), p(0, 4), p(0, 5), p(1, 7)), window)
    }

    @Test fun warmWindowIncludesOnlyTwoLateralNeighborsAndTheNextVerticalRow() {
        val window = homeArtworkPrefetchWindow(
            listOf(100, 100, 100, 100, 100), listOf(HomeArtworkViewportRow(2, listOf(20, 21, 22))),
            nextRowFirstItem = 40, nextRowVisibleCount = 5,
        )
        assertEquals(listOf(p(2, 20), p(2, 21), p(2, 22)), window.take(3))
        assertTrue(window.containsAll(listOf(p(2, 18), p(2, 19), p(2, 23), p(2, 24))))
        assertEquals((40..44).map { p(3, it) }, window.filter { it.shelf == 3 })
        assertTrue(window.all { it.shelf == 2 || it.shelf == 3 })
        assertFalse(window.contains(p(2, 17)))
        assertFalse(window.contains(p(2, 25)))
    }

    @Test fun speculativeWorkNeverExpandsWithCatalogSize() {
        val window = homeArtworkPrefetchWindow(
            List(2_000) { 10_000 },
            listOf(HomeArtworkViewportRow(500, (10..17).toList()), HomeArtworkViewportRow(501, (30..37).toList())),
            nextRowFirstItem = 100, nextRowVisibleCount = 8,
        )
        assertEquals(18, window.size)
        assertEquals(18, window.distinct().size)
        assertEquals(listOf(p(502, 100), p(502, 101)), window.takeLast(2))
    }

    @Test fun edgesExcludeNegativeIndicesPaginationButtonsAndBeyondTheLastShelf() {
        val window = homeArtworkPrefetchWindow(
            listOf(3), listOf(HomeArtworkViewportRow(0, listOf(0, 1, 2, 3))),
            nextRowFirstItem = 0, nextRowVisibleCount = 8,
        )
        assertEquals(listOf(p(0, 0), p(0, 1), p(0, 2)), window)
    }

    @Test fun revisitingARowUsesItsRetainedHorizontalPosition() {
        val window = homeArtworkPrefetchWindow(
            listOf(30, 30), listOf(HomeArtworkViewportRow(0, listOf(0, 1))),
            nextRowFirstItem = 17, nextRowVisibleCount = 4,
        )
        assertEquals((17..20).map { p(1, it) }, window.filter { it.shelf == 1 })
        assertFalse(window.contains(p(1, 0)))
    }

    @Test fun staleUnattachedRowsCannotScheduleTheWholeFeed() {
        assertTrue(homeArtworkPrefetchWindow(listOf(20), emptyList(), 0, 7).isEmpty())
        assertTrue(homeArtworkPrefetchWindow(listOf(20), listOf(HomeArtworkViewportRow(3, listOf(0))), 0, 7).isEmpty())
        assertTrue(homeArtworkPrefetchWindow(listOf(20), listOf(HomeArtworkViewportRow(0, emptyList())), 0, 7).isEmpty())
        assertTrue(homeArtworkPrefetchWindow(listOf(20), listOf(HomeArtworkViewportRow(0, listOf(0))), 0, 7, 0).isEmpty())
    }

    private fun p(shelf: Int, item: Int) = HomeArtworkPosition(shelf, item)
}
