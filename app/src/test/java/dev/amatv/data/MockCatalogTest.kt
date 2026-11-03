package dev.amatv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockCatalogTest {
    @Test fun missingCatalogIdDoesNotMakeLibraryOrUnknownContentADemo() {
        val demo = MockCatalog.tracks.first()
        assertTrue(MockCatalog.isDemoTrack(demo))
        assertFalse(MockCatalog.isDemoTrack(demo.copy(id = "apple:i.upload", resourceId = "i.upload", resourceType = "library-songs")))
        assertFalse(MockCatalog.isDemoTrack(demo.copy(resourceId = "123", resourceType = "songs")))
        assertFalse(MockCatalog.isDemoTrack(demo.copy(catalogId = "123")))
        assertFalse(MockCatalog.isDemoTrack(demo.copy(id = "unresolved-recording")))
    }
}
