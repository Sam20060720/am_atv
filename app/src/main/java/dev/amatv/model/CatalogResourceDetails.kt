package dev.amatv.model

/** Namespace is part of identity: a saved album and its catalog mapping are different resources. */
data class CatalogResourceRef(val id: String, val type: String) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]+")))
        require(type.matches(Regex("[a-z][a-z0-9-]*")))
    }
    val key: String get() = "$type:$id"

    companion object {
        fun fromTrack(item: Track): CatalogResourceRef? {
            val type = item.resourceType ?: when (item.catalogKind) {
                CatalogKind.Album -> "albums"
                CatalogKind.Artist -> "artists"
                CatalogKind.Playlist -> "playlists"
                CatalogKind.Song -> "songs"
                CatalogKind.MusicVideo -> "music-videos"
                CatalogKind.Station -> "stations"
                CatalogKind.Page -> return null
            }
            val source = item.id.removePrefix("apple:")
            val id = item.resourceId ?: if (type.startsWith("library-")) {
                source.takeIf { it.startsWith("i.") || it.startsWith("l.") || it.startsWith("p.") || it.startsWith("r.") }
                    ?: return null
            } else item.catalogId ?: source
            return runCatching { CatalogResourceRef(id, type) }.getOrNull()
        }
    }
}

enum class CatalogMetadataFormat { Text, Date, Number, Duration, Boolean, Genres, AudioVariants, Identifier }

data class CatalogMetadataField(
    val key: String,
    val values: List<String>,
    val format: CatalogMetadataFormat = CatalogMetadataFormat.Text,
    /** A structured service group may supply its localized product title. */
    val title: String? = null,
) {
    val value: String get() = values.joinToString(" · ")
}

data class CatalogResourceLink(val ref: CatalogResourceRef, val title: String? = null)

/** Server title is optional; the native page localizes the stable id when one isn't returned. */
data class CatalogResourceSection(
    val id: String,
    val isView: Boolean = false,
    val resourceTypes: Set<String>,
    val pageSize: Int? = 25,
    val title: String? = null,
    /** Genre pages use the documented charts endpoint rather than inventing genre relationships. */
    val chartType: String? = null,
    val requiresUserToken: Boolean = false,
    /** Anonymous page models can already contain this section's first page. */
    val initialPage: CatalogResourcePage? = null,
)

data class CatalogResourceProfile(
    val ref: CatalogResourceRef,
    val item: Track,
    val name: String,
    val description: String?,
    val artworkUrl: String?,
    val motionArtworkUrl: String?,
    val motionPreviewUrl: String?,
    val url: String?,
    val metadata: List<CatalogMetadataField>,
    val sections: List<CatalogResourceSection>,
    val links: List<CatalogResourceLink> = emptyList(),
    val inFavorites: Boolean? = null,
    val inLibrary: Boolean? = null,
    val canEdit: Boolean? = null,
)

data class CatalogResourcePage(
    val items: List<Track>,
    val nextPage: String?,
    val requestedPage: String,
    val total: Int? = null,
)
