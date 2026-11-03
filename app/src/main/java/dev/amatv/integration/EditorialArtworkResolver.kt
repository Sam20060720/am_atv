package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Enrich the selected recording from its real album relationship, never its title. */
internal class EditorialArtworkResolver(
    private val scopeKey: suspend () -> String,
    private val albumForSong: suspend (Track) -> Track?,
    private val publicArtwork: suspend (Track) -> Track,
) {
    private val albums = EditorialLookupCache<Track?> { if (it == null) 15 * 60_000L else 6 * 60 * 60_000L }

    suspend fun artwork(item: Track): Track {
        if (item.motionArtworkUrl != null || item.motionArtworkVariants.isNotEmpty()) return item
        if (item.catalogKind != CatalogKind.Song) return publicArtwork(item)
        val id = item.catalogId?.takeIf { it.matches(Regex("[0-9]+")) } ?: return item
        return try {
            val key = "${scopeKey()}:$id"
            val album = albums.get(key) {
                albumForSong(item)?.takeIf { it.catalogKind == CatalogKind.Album }
            } ?: return item
            val enriched = publicArtwork(album)
            currentCoroutineContext().ensureActive()
            item.withEditorialArtwork(enriched)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            item
        }
    }
}

/** The recording/queue/library identity, captions, duration and lyrics stay intact. */
internal fun Track.withEditorialArtwork(source: Track): Track {
    if (source.motionArtworkUrl == null && source.motionArtworkVariants.isEmpty()) return this
    return copy(
        motionArtworkUrl = source.motionArtworkUrl,
        motionPreviewUrl = source.motionPreviewUrl,
        motionArtworkVariants = source.motionArtworkVariants,
    )
}
