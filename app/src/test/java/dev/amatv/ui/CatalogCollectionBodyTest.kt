package dev.amatv.ui

import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

class CatalogCollectionBodyTest {
    private fun song(id: String, type: String = "songs") = Track("apple:$id", "Song", "Artist", "Album",
        1000, emptyList(), emptyList(), resourceId = id, resourceType = type)

    @Test fun repeatedResourcesRetainIndependentKeysThroughEnrichmentAndAppend() {
        val initial = listOf(song("a"), song("b"), song("a"), song("a", "library-songs"))
        val keys = catalogCollectionKeys(initial)
        assertEquals(4, keys.toSet().size)
        val expanded = initial.map { it.copy(artworkUrl = "https://example.com/cover.jpg", title = "Updated") } + song("a")
        assertEquals(keys, catalogCollectionKeys(expanded).take(initial.size))
        assertFalse(catalogCollectionKeys(expanded).last() in keys)
    }

    @Test fun paginationRespectsFullWidthGroupsAndEmptyIntermediatePages() {
        // Thirty group rows and eighteen covers occupy thirty-three actual rows.
        assertFalse(catalogCollectionNearEnd(29, 30, 18, 6))
        assertTrue(catalogCollectionNearEnd(30, 30, 18, 6))
        assertFalse(catalogCollectionNearEnd(26, 30, 0, 6))
        assertTrue(catalogCollectionNearEnd(27, 30, 0, 6))
        assertFalse(catalogCollectionNearEnd(-1, 0, 100, 6))
        assertTrue(catalogCollectionNearEnd(-1, 0, 0, 6))
        assertFalse(catalogCollectionNearEnd(96, 0, 100, 1))
        assertTrue(catalogCollectionNearEnd(97, 0, 100, 1))
    }
}
