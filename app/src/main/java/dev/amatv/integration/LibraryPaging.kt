package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

/** One server page; callers retain the continuation until the user asks for more. */
data class LibraryPage(
    val items: List<Track>,
    val nextPage: String?,
    val requestedPage: String,
    /** Server total when supplied; the number loaded locally may be smaller. */
    val total: Int? = null,
    /** Tracks relationships use raw offsets, including videos/unsupported rows. */
    val rawItemCount: Int? = null,
    val itemRawOffsets: List<Int> = emptyList(),
)

enum class LibraryCollection(
    private val pathName: String,
    val resourceTypes: Set<String>,
) {
    RecentlyAdded("recently-added", setOf(
        "library-albums", "library-artists", "library-playlists", "library-songs", "library-music-videos",
    )),
    Albums("albums", setOf("library-albums")),
    Artists("artists", setOf("library-artists")),
    Playlists("playlists", setOf("library-playlists")),
    Songs("songs", setOf("library-songs"));

    internal val request: CollectionRequest
        get() = CollectionRequest(
            endpoint = "/v1/me/library/$pathName",
            needsUserToken = true,
            // Apple documents only localization for recently-added. Follow its
            // returned next cursor instead of inventing list query parameters.
            pageSize = if (this == RecentlyAdded) null else 100,
            includeCatalog = this != RecentlyAdded,
        )
}

internal fun Track.libraryArtistAlbumsRequest(): CollectionRequest {
    require(catalogKind == CatalogKind.Artist && resourceType == "library-artists") {
        AppText.text(R.string.services_collection_required)
    }
    // A linked catalog ID names a different resource. This relationship must
    // use the original account-library artist, even when include=catalog exists.
    val artistId = checkNotNull(resourceId) { AppText.text(R.string.services_collection_unrecognized) }
    require(artistId.matches(Regex("[A-Za-z0-9._-]+"))) {
        AppText.text(R.string.services_collection_unrecognized)
    }
    return CollectionRequest("/v1/me/library/artists/$artistId/albums", needsUserToken = true)
}

/** Library entries have durable resource identities, even when catalog releases are relinked. */
internal fun libraryResourceKey(track: Track): String =
    track.resourceId?.let { "${track.resourceType.orEmpty()}:$it" }
        ?: "${track.catalogKind}:${track.id}"

/** Retain distinct i.* entries that happen to point at the same catalog recording. */
fun mergeLibraryPage(existing: List<Track>, incoming: List<Track>): List<Track> {
    if (incoming.isEmpty()) return existing
    val known = existing.mapTo(HashSet(existing.size + incoming.size), ::libraryResourceKey)
    return existing + incoming.filter { known.add(libraryResourceKey(it)) }
}
