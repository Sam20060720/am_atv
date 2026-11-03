package dev.amatv.model

/** Public chart collections; the last two contain actual chart playlist resources. */
enum class CatalogChartCategory(val resourceType: String, val resultKey: String, val modifier: String? = null) {
    Songs("songs", "songs"),
    Albums("albums", "albums"),
    Playlists("playlists", "playlists"),
    City("playlists", "cityCharts", "cityCharts"),
    DailyGlobal("playlists", "dailyGlobalTopCharts", "dailyGlobalTopCharts"),
}

/** Only the API creates continuations; identity travels with the untouched server cursor. */
class CatalogChartCursor internal constructor(
    internal val category: CatalogChartCategory,
    internal val storefront: String,
    internal val chart: String?,
    internal val genre: String?,
    internal val path: String,
    /** Opaque chart identity adopted only from this response's own href/next. */
    internal val chartId: String? = null,
) {
    val key: String get() = path
}

data class CatalogChartPage(
    val items: List<Track>,
    val nextPage: CatalogChartCursor?,
    val requestedPage: String,
    val total: Int? = null,
    val title: String? = null,
    val chart: String? = null,
    val orderId: String? = null,
    val href: String? = null,
)
