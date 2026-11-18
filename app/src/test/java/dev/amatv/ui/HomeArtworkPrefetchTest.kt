package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.amatv.model.Track
import dev.amatv.model.EditorialMotionAsset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

class HomeArtworkPrefetchTest {
    @Test fun playlistPrefetchDoesNotWarmATextFreePortraitInsteadOfTheRenderedCover() {
        val square = EditorialMotionAsset("motionDetailSquare", "https://example.test/square.m3u8",
            "https://example.test/square.jpg", 3840, 3840)
        val background = EditorialMotionAsset("motionDetailTall", "https://example.test/background.m3u8",
            "https://example.test/background.jpg", 2048, 2732)
        val playlist = Track("playlist", "Playlist", "Artist", "", 0L, emptyList(), emptyList(),
            catalogKind = dev.amatv.model.CatalogKind.Playlist, motionArtworkVariants = listOf(background, square))
        for (classic in listOf(false, true)) {
            val artwork = assertCover(playlist, true, classic, square.previewUrl,
                if (classic) 210.dp else 200.dp, if (classic) 184.dp else 200.dp)
            assertFalse(artwork.portrait)
            assertEquals(listOf(square), artwork.track.motionArtworkVariants)
            assertEquals(playlist.id, artwork.track.id)
            assertEquals(listOf(background, square), playlist.motionArtworkVariants)
        }
    }

    @Test fun prefetchUsesTheActualStyleCoverAndChosenPosterGeometry() {
        val square = EditorialMotionAsset("square", "https://example.test/square.m3u8", "https://example.test/square.jpg", 600, 600)
        val portrait = EditorialMotionAsset("portrait", "https://example.test/portrait.m3u8", "https://example.test/portrait.jpg", 600, 800)
        val track = Track("123", "Album", "Artist", "", 0L, emptyList(), emptyList(),
            motionArtworkVariants = listOf(square, portrait))
        assertFalse(assertCover(track, false, true, square.previewUrl, 168.dp, 168.dp).portrait)
        assertFalse(assertCover(track, false, false, square.previewUrl, 124.dp, 124.dp).portrait)
        assertFalse(assertCover(track, true, true, square.previewUrl, 210.dp, 184.dp).portrait)
        assertTrue(assertCover(track, true, false, portrait.previewUrl, 200.dp, 278.dp).portrait)
    }

    @Test fun playlistWithoutASquareMotionCoverKeepsItsStaticCoverAndNeverUsesTheBackground() {
        val background = EditorialMotionAsset("backgroundTall", "https://example.test/background.m3u8",
            "https://example.test/background.jpg", 600, 800)
        val playlist = Track("playlist", "Playlist", "Artist", "", 0L, emptyList(), emptyList(),
            catalogKind = dev.amatv.model.CatalogKind.Playlist, artworkUrl = "https://example.test/static.jpg",
            motionArtworkUrl = background.videoUrl, motionPreviewUrl = background.previewUrl,
            motionArtworkVariants = listOf(background))
        val artwork = assertCover(playlist, true, false, playlist.artworkUrl, 200.dp, 200.dp)
        assertFalse(artwork.portrait)
        assertTrue(artwork.track.motionArtworkVariants.isEmpty())
        assertEquals(null, artwork.track.motionArtworkUrl)
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

    private fun assertCover(track: Track, featured: Boolean, classic: Boolean, posterUrl: String?, width: Dp, height: Dp): HomeArtworkPresentation =
        homeArtworkPresentation(track, featured, HomeShelfLayout(classic)).also {
            assertEquals(posterUrl, it.posterUrl)
            assertEquals(width, it.width)
            assertEquals(height, it.height)
        }
}
