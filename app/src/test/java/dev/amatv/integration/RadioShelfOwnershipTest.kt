package dev.amatv.integration

import dev.amatv.model.ContentShelf
import org.junit.Assert.*
import org.junit.Test

class RadioShelfOwnershipTest {
    @Test fun emptyRadioPageWithContinuationSurvivesPresentationFiltering() {
        val capabilities = CatalogPresentationCapabilities(NativeMediaCapabilities(), stationWebPlaybackAvailable = true)
        val shelf = ContentShelf("radio-personal", "For You", emptyList(), hasMore = true)
        assertEquals(listOf(shelf), capabilities.filterShelves(listOf(shelf)))
        assertTrue(capabilities.filterShelves(listOf(shelf.copy(hasMore = false))).isEmpty())
    }

    @Test fun publicRefreshReplacesBothHtmlAndRestFallbackRows() {
        assertTrue(RadioShelfSource.Public.ownsShelf("public-radio:tw:live"))
        assertTrue(RadioShelfSource.Public.ownsShelf("radio-live"))
        assertFalse(RadioShelfSource.Public.ownsShelf("radio-personal"))
        assertFalse(RadioShelfSource.Public.ownsShelf("radio-recent"))
        assertFalse(RadioShelfSource.Public.ownsShelf("library-songs"))
        assertTrue(RadioShelfSource.Personal.ownsShelf("radio-personal"))
        assertTrue(RadioShelfSource.Recent.ownsShelf("radio-recent"))
    }
}
