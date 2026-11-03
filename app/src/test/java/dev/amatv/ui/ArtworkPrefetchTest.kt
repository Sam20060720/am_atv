package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class ArtworkPrefetchTest {
    @Test fun visibleCoversWinBeforeBoundedNeighbors() {
        assertEquals(listOf(10, 11, 12, 13, 9, 14, 8), artworkPrefetchIndices(100, listOf(10, 11, 12)))
        assertEquals(listOf(10, 11), artworkPrefetchIndices(100, listOf(10, 11, 12), limit = 2))
    }

    @Test fun offsetMetadataAndFooterAreNeverConfusedWithSongIndices() {
        assertEquals(listOf(0, 1, 2, 3), artworkPrefetchIndices(100, listOf(2, 3), itemIndexOffset = 2))
        assertEquals(listOf(0, 1), artworkPrefetchIndices(100, listOf(0, 1), itemIndexOffset = 2))
        assertEquals(listOf(98, 99), artworkPrefetchIndices(100, listOf(102), itemIndexOffset = 2))
    }

    @Test fun largeCatalogsNeverIncreaseTheWorkAllowance() {
        val indices = artworkPrefetchIndices(100_000, (40..55).toList())
        assertEquals((40..47).toList(), indices)
        assertEquals(indices.distinct(), indices)
    }

    @Test fun nearEdgesAndEmptyContentRemainSafe() {
        assertEquals(listOf(0, 1), artworkPrefetchIndices(2, listOf(0, 1, 2)))
        assertTrue(artworkPrefetchIndices(0, listOf(0)).isEmpty())
        assertTrue(artworkPrefetchIndices(10, emptyList()).isEmpty())
        assertTrue(artworkPrefetchIndices(10, listOf(0), limit = 0).isEmpty())
    }
}
