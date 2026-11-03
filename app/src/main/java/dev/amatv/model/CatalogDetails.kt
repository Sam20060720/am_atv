package dev.amatv.model

/** Public artist relationships/views. Each is requested only when its section is visited. */
enum class ArtistSection(
    val apiName: String,
    val isView: Boolean,
    val pageSize: Int?,
    val resourceTypes: Set<String>,
) {
    LibraryAlbums("albums", false, 100, setOf("library-albums")),
    TopSongs("top-songs", true, 20, setOf("songs")),
    // This view is singular; the API rejects a limit greater than one (40005).
    LatestRelease("latest-release", true, 1, setOf("albums")),
    FeaturedAlbums("featured-albums", true, 20, setOf("albums")),
    FullAlbums("full-albums", true, 20, setOf("albums")),
    Singles("singles", true, 20, setOf("albums")),
    LiveAlbums("live-albums", true, 20, setOf("albums")),
    CompilationAlbums("compilation-albums", true, 20, setOf("albums")),
    AppearsOnAlbums("appears-on-albums", true, 20, setOf("albums")),
    FeaturedPlaylists("featured-playlists", true, 10, setOf("playlists")),
    Playlists("playlists", false, 10, setOf("playlists")),
    SimilarArtists("similar-artists", true, 20, setOf("artists")),
    FeaturedMusicVideos("featured-music-videos", true, 20, setOf("music-videos")),
    TopMusicVideos("top-music-videos", true, 20, setOf("music-videos")),
    MusicVideos("music-videos", false, 25, setOf("music-videos")),
    Albums("albums", false, 25, setOf("albums")),
    Station("station", false, null, setOf("stations")),
}

/** Library identity is never exchanged for a catalog artist merely to open a page. */
data class ArtistProfile(
    val artist: Track,
    val catalogArtist: Track?,
    /** Full artistBio when returned; otherwise Apple's optional editorial notes. */
    val biography: String? = null,
    val genreNames: List<String> = emptyList(),
    val url: String? = null,
    val inFavorites: Boolean? = null,
    /** Optional fields observed in the shipped web biography request, absent on many artists. */
    val origin: String? = null,
    val bornOrFormed: String? = null,
    val isGroup: Boolean? = null,
) {
    /** Documented sections, not proof each contains data for this particular artist. */
    val applicableSections: List<ArtistSection>
        get() = buildList {
            if (artist.resourceType == "library-artists") add(ArtistSection.LibraryAlbums)
            if (catalogArtist != null || artist.resourceType == "artists") {
                addAll(ArtistSection.entries.filterNot { it == ArtistSection.LibraryAlbums })
            }
        }
}

/** Server-paged search retains a category instead of flattening and truncating all results. */
enum class CatalogSearchType(val resourceType: String, val libraryResourceType: String?) {
    Artists("artists", "library-artists"),
    Songs("songs", "library-songs"),
    Albums("albums", "library-albums"),
    Playlists("playlists", "library-playlists"),
    MusicVideos("music-videos", "library-music-videos"),
    Stations("stations", null),
    Curators("curators", null),
    AppleCurators("apple-curators", null),
    RecordLabels("record-labels", null),
    Activities("activities", null),
}
