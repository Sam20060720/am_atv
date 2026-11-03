package dev.amatv.model

enum class ListeningHistorySection { RecentTracks, RecentResources, HeavyRotation, RecentStations }

/** A server cursor bound to the account section that produced it. Query parameters stay opaque. */
data class ListeningPageCursor internal constructor(
    internal val scope: String,
    internal val endpoint: String,
    internal val path: String,
) {
    val key: String get() = path
}

data class ListeningPage<T>(
    val items: List<T>,
    val nextPage: ListeningPageCursor?,
    val requestedPage: String,
    val total: Int? = null,
)

data class ListeningRecommendation(
    val id: String,
    val title: String?,
    val reason: String?,
    val resourceTypes: Set<String>,
)

sealed interface RecommendationEntry {
    data class Item(val track: Track) : RecommendationEntry
    data class Group(val recommendation: ListeningRecommendation) : RecommendationEntry
    /** Keep identifiers even when no display metadata is available in this response. */
    data class Unresolved(val ref: CatalogResourceRef) : RecommendationEntry
}

enum class ReplayView(val id: String, val summaryType: String, val relationship: String, val resourceType: String) {
    TopArtists("top-artists", "artist-period-summaries", "artist", "artists"),
    TopAlbums("top-albums", "album-period-summaries", "album", "albums"),
    TopSongs("top-songs", "song-period-summaries", "song", "songs"),
}

/** Only server attributes are recorded. The public schema supplies no count or rank attributes. */
data class ListeningReplaySummary(val id: String, val period: String?, val year: Int?)

/** Empty is a successful response without a supplied summary, not an inferred eligibility verdict. */
data class ListeningReplay(val summaries: List<ListeningReplaySummary>)

data class ListeningReplayEntry(
    val id: String,
    val type: String,
    val items: List<Track>,
    val unresolvedResources: List<CatalogResourceRef>,
    val relationshipProvided: Boolean = true,
)

sealed interface ListeningReplayView {
    data class Page(val page: ListeningPage<ListeningReplayEntry>) : ListeningReplayView
    /** The summary still exists but Apple didn't provide this view. */
    data object NotProvided : ListeningReplayView
    /** The selected summary no longer occurs in the latest-eligible-year response. */
    data object SummaryMissing : ListeningReplayView
}
