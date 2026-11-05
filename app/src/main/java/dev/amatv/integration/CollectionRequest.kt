package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.net.URI

/** Null track retains an unverifiable draw for resolution against the complete population. */
internal data class CollectionShuffleSample(val track: Track?, val rawIndex: Int, val total: Int, val firstPage: String)

/** Original resource identity survives catalog relinking; raw index distinguishes repeats. */
internal fun collectionOccurrenceKey(track: Track): String =
    "${track.resourceType ?: "songs"}:${track.resourceId ?: track.catalogId ?: track.id}"

internal data class CollectionRequest(
    val endpoint: String,
    val needsUserToken: Boolean,
    val pageSize: Int? = 100,
    val includeCatalog: Boolean = needsUserToken,
) {
    val firstPage: String
        get() = buildList {
            pageSize?.let { add("limit=$it") }
            if (includeCatalog) add("include=catalog")
        }.joinToString("&").let { query -> endpoint + if (query.isNotEmpty()) "?$query" else "" }

    fun nextPage(href: String): String = nextPage(href, retainPageSize = false)

    fun singleOccurrence(rawIndex: Int): String {
        require(rawIndex >= 0)
        return "$endpoint?offset=$rawIndex&limit=1" + if (includeCatalog) "&include=catalog" else ""
    }

    fun range(offset: Int, limit: Int): String {
        require(offset >= 0 && limit > 0 && (pageSize == null || limit <= pageSize))
        return "$endpoint?offset=$offset&limit=$limit" + if (includeCatalog) "&include=catalog" else ""
    }

    fun nextPage(href: String, retainPageSize: Boolean): String {
        val uri = URI("https://api.music.apple.com").resolve(href)
        check(uri.scheme == "https" && uri.host == "api.music.apple.com" &&
            uri.port == -1 && uri.userInfo == null && uri.rawPath == endpoint && uri.fragment == null) {
            AppText.text(R.string.services_collection_next_page_failed)
        }
        val query = uri.rawQuery.orEmpty()
        val names = query.split('&').map { it.substringBefore('=') }.toSet()
        val additions = buildList {
            // Follow the server's offset/cursor verbatim. Reapplying an
            // omitted limit is supported by Apple's pagination contract.
            if (retainPageSize && pageSize != null && "limit" !in names) add("limit=$pageSize")
            if (includeCatalog && "include" !in names) add("include=catalog")
        }
        return listOf(query, additions.joinToString("&")).filter { it.isNotEmpty() }
            .joinToString("&").let { endpoint + if (it.isNotEmpty()) "?$it" else "" }
    }
}

internal fun Track.collectionRequest(storefront: String): CollectionRequest {
    val type = when (catalogKind) {
        CatalogKind.Album -> "albums"
        CatalogKind.Playlist -> "playlists"
        else -> error(AppText.text(R.string.services_collection_required))
    }
    val id = (resourceId ?: catalogId ?: id).removePrefix("apple:")
    require(id.matches(Regex("[A-Za-z0-9._-]+"))) { AppText.text(R.string.services_collection_unrecognized) }
    // Original resource type is authoritative, even if playParams points at a catalog playlist.
    // The fallback only covers older saved items that predate resource identity persistence.
    val library = resourceType?.startsWith("library-")
        ?: (id.startsWith("p.") || id.startsWith("l."))
    val base = if (library) "/v1/me/library" else "/v1/catalog/$storefront"
    // Catalog playlist tracks allow 300 per page; Library tracks cap at 100.
    val pageSize = if (!library && catalogKind == CatalogKind.Playlist) 300 else 100
    return CollectionRequest("$base/$type/$id/tracks", library, pageSize)
}
