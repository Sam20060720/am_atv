package dev.amatv.integration

import dev.amatv.model.CatalogChartCategory
import dev.amatv.model.CatalogChartCursor
import dev.amatv.model.CatalogChartPage
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** One chart collection/page per read. City/country playlists use their returned IDs. */
class CatalogChartsApi internal constructor(
    private val storefront: suspend () -> String,
    private val request: suspend (String, Boolean) -> JSONObject,
    private val parseItem: (JSONObject) -> Track?,
    private val parseDispatcher: CoroutineDispatcher,
) {
    suspend fun page(category: CatalogChartCategory, nextPage: CatalogChartCursor? = null): CatalogChartPage = withContext(parseDispatcher) {
        val sf = storefront().also { require(it.matches(Regex("[a-z]{2}"))) }
        val endpoint = CollectionRequest("/v1/catalog/$sf/charts", false, 25, includeCatalog = false)
        val path = if (nextPage == null) {
            "${endpoint.endpoint}?types=${category.resourceType}&limit=25" +
                (category.modifier?.let { "&with=$it" } ?: "&chart=most-played")
        } else {
            require(nextPage.category == category && nextPage.storefront == sf)
            validate(endpoint, category, nextPage.path, nextPage.chart, nextPage.genre, nextPage.chartId)
        }
        currentCoroutineContext().ensureActive()
        val root = requestEditorialVideo(path, setOf(category.resourceType)) { request(it, false) }
        currentCoroutineContext().ensureActive()
        val groups = checkNotNull(root.optJSONObject("results")) { "Missing chart results" }.optJSONArray(category.resultKey)
        val expectedChart = nextPage?.chart ?: "most-played".takeIf { category.modifier == null }
        val group = (0 until (groups?.length() ?: 0)).mapNotNull { groups?.optJSONObject(it) }
            .firstOrNull { expectedChart == null || it.text("chart") == expectedChart }
        // A region may legitimately omit a non-standard collection.
        if (group == null) {
            check(nextPage == null || groups == null || groups.length() == 0) { "Chart identity changed" }
            return@withContext CatalogChartPage(emptyList(), null, path)
        }
        val chart = group.text("chart")
        // Apple returns numbered cursors for City/Daily Global collections:
        // chartId replaces types/chart/with, rather than repeating those fields.
        // Adopt the first identity from this group; every subsequent link must
        // retain it. Never construct or hardcode Apple's chart identifiers.
        val chartId = nextPage?.chartId ?: group.text("href")?.let(::chartIdentity)
            ?: group.text("next")?.let(::chartIdentity)
        val href = group.text("href")?.let { validate(endpoint, category, it, chart, nextPage?.genre, chartId) }
        val genre = href?.let(::query)?.get("genre")?.singleOrNull() ?: nextPage?.genre
        val data = checkNotNull(group.optJSONArray("data")) { "Missing chart data" }
        val items = (0 until data.length()).mapNotNull { index ->
            currentCoroutineContext().ensureActive()
            data.optJSONObject(index)?.takeIf { it.optString("type") == category.resourceType }?.let(parseItem)
        }
        val next = group.text("next")?.let { raw ->
            val normalized = validate(endpoint, category, raw, chart, genre, chartId)
            check(normalized != path)
            CatalogChartCursor(category, sf, chart, genre ?: query(normalized)["genre"]?.singleOrNull(), normalized, chartId)
        }
        CatalogChartPage(mergeLibraryPage(emptyList(), items), next, path,
            group.optJSONObject("meta")?.optInt("total", -1)?.takeIf { it >= 0 },
            group.text("name"), chart, group.text("orderId"), href)
    }

    private fun validate(
        endpoint: CollectionRequest, category: CatalogChartCategory, raw: String, chart: String?, genre: String?, chartId: String?,
    ): String {
        val path = endpoint.nextPage(raw)
        val parameters = query(path)
        if (chartId != null) {
            require(parameters["chartId"] == listOf(chartId))
            parameters["types"]?.let { require(it == listOf(category.resourceType)) }
        } else {
            require(parameters["chartId"] == null && parameters["types"] == listOf(category.resourceType))
        }
        parameters["chart"]?.let { require(it.size == 1 && (chart == null || it.single() == chart)) }
        if (chart != null && chartId == null) require(parameters["chart"] == listOf(chart) ||
            (category.modifier != null && parameters["with"]?.singleOrNull()?.split(',')?.contains(category.modifier) == true))
        if (genre != null) require(parameters["genre"] == listOf(genre))
        parameters["genre"]?.let { require(it.size == 1) }
        parameters["with"]?.let { values ->
            require(values.size == 1)
            if (category.modifier != null) require(category.modifier in values.single().split(','))
            else require(values.single().split(',').none { it in setOf("cityCharts", "dailyGlobalTopCharts") })
        }
        return path
    }

    private fun chartIdentity(path: String): String? = query(path)["chartId"]?.let {
        require(it.size == 1 && it.single().isNotBlank())
        it.single()
    }

    private fun query(path: String): Map<String, List<String>> = URI(path).rawQuery.orEmpty().split('&').filter(String::isNotEmpty)
        .map { it.split('=', limit = 2) }.map { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") }
        .groupBy({ it.first }, { it.second })
    private fun JSONObject.text(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() && it != "null" }
}
