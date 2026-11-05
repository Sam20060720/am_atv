package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorialArtworkResolverTest {
    private val song = Track("library:i.abc", "Recording", "Artist", "Album", 120_000L,
        emptyList(), emptyList(), catalogId = "123", resourceId = "i.abc", resourceType = "library-songs")
    private val album = song.copy(id = "album:456", catalogId = "456", catalogKind = CatalogKind.Album,
        resourceId = "456", resourceType = "albums", title = "Album", durationMs = 0L)

    @Test fun albumMotionDoesNotReplaceRecordingIdentityOrMetadata() = runBlocking {
        val resolver = EditorialArtworkResolver({ "tw" }, { album }, {
            it.copy(motionArtworkUrl = "https://example.test/album.m3u8")
        })
        val result = resolver.artwork(song)
        assertEquals(song.copy(motionArtworkUrl = "https://example.test/album.m3u8"), result)
    }

    @Test fun albumRelationshipIsCachedPerStorefrontScope() = runBlocking {
        var scope = "tw"
        var calls = 0
        val resolver = EditorialArtworkResolver({ scope }, { calls++; album }, { it })
        resolver.artwork(song)
        resolver.artwork(song)
        assertEquals(1, calls)
        scope = "us"
        resolver.artwork(song)
        assertEquals(2, calls)
    }

    @Test fun clearingCacheInvalidatesPositiveAndNegativeAlbumRelationships() = runBlocking {
        var generation = 0L
        var calls = 0
        var relation: Track? = null
        val resolver = EditorialArtworkResolver({ "tw" }, { calls++; relation }, { it }, { generation })
        resolver.artwork(song)
        relation = album
        resolver.artwork(song)
        assertEquals(1, calls) // The negative result still belongs to this generation.
        generation++
        resolver.artwork(song)
        assertEquals(2, calls)
        generation++
        resolver.artwork(song)
        assertEquals(3, calls) // Clear also invalidates a successful relation.
    }

    @Test fun unmatchedLibraryRecordingNeverGuessesAlbumByTitle() = runBlocking {
        val resolver = EditorialArtworkResolver({ "tw" }, { error("Must not fetch") }, { error("Must not fetch") })
        val unmatched = song.copy(catalogId = "i.abc")
        assertSame(unmatched, resolver.artwork(unmatched))
    }

    @Test fun optionalFailureKeepsStaticArtworkAndCancellationPropagates() = runBlocking {
        val failure = EditorialArtworkResolver({ "tw" }, { throw IllegalStateException() }, { it })
        assertSame(song, failure.artwork(song))
        val cancelled = EditorialArtworkResolver({ "tw" }, { throw CancellationException() }, { it })
        try { cancelled.artwork(song); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }
}
