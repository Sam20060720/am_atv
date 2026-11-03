package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLDecoder

/** Catalog/live identities stay separate from handles returned by station generation. */
enum class RadioCatalogSection { Live, Personal, Recent }

/** Public station roots and authenticated personal/history roots, one server page at a time. */
class RadioCatalogApi internal constructor(
    private val storefront: suspend () -> String,
    private val request: suspend (String, Boolean) -> JSONObject,
    private val parseItem: (JSONObject) -> Track?,
    private val parseDispatcher: CoroutineDispatcher,
) {
    suspend fun page(section: RadioCatalogSection, nextPage: String? = null): LibraryPage = withContext(parseDispatcher) {
        val country = storefront().also { require(it.matches(Regex("[a-z]{2}"))) }
        val endpoint = when (section) {
            RadioCatalogSection.Live, RadioCatalogSection.Personal -> "/v1/catalog/$country/stations"
            RadioCatalogSection.Recent -> "/v1/me/recent/radio-stations"
        }
        val collection = CollectionRequest(endpoint, section != RadioCatalogSection.Live,
            pageSize = if (section == RadioCatalogSection.Recent) 25 else null, includeCatalog = false)
        val first = scopedPath(section, collection, collection.firstPage)
        val path = nextPage?.let { scopedPath(section, collection, it) } ?: first
        val enriched = append(path, "include=radio-show&extend=editorialArtwork,editorialVideo")
        currentCoroutineContext().ensureActive()
        val root = try { request(enriched, collection.needsUserToken) }
        catch (error: CatalogRequestException) {
            // The optional web artwork/relationship extension is not required
            // for the station itself. Preserve identity, filter and auth mode.
            if (error.httpStatus !in setOf(400, 403)) throw error
            request(path, collection.needsUserToken)
        }
        currentCoroutineContext().ensureActive()
        val data = root.optJSONArray("data") ?: error(AppText.text(R.string.services_track_unavailable))
        val items = (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.takeIf { it.optString("type") == "stations" }?.let(parseItem)
                ?.takeIf { it.catalogKind == CatalogKind.Station }
        }.distinctBy { it.resourceType to (it.resourceId ?: it.catalogId) }
        val next = (root.opt("next") as? String)?.takeIf(String::isNotBlank)
            ?.let { scopedPath(section, collection, it) }
        check(next == null || next != path) { AppText.text(R.string.services_collection_next_page_failed) }
        val total = root.optJSONObject("meta")?.opt("total") as? Number
        LibraryPage(items, next, path, total?.toInt()?.takeIf { it >= 0 })
    }

    private fun scopedPath(section: RadioCatalogSection, collection: CollectionRequest, href: String): String {
        val normalized = collection.nextPage(href)
        val required = when (section) {
            RadioCatalogSection.Live -> "filter[featured]" to "apple-music-live-radio"
            RadioCatalogSection.Personal -> "filter[identity]" to "personal"
            RadioCatalogSection.Recent -> null
        }
        val parameters = normalized.substringAfter('?', "").split('&').filter(String::isNotEmpty).map { value ->
            URLDecoder.decode(value.substringBefore('='), "UTF-8") to URLDecoder.decode(value.substringAfter('=', ""), "UTF-8")
        }
        val filters = parameters.filter { it.first.startsWith("filter[") }
        if (required == null) {
            check(filters.isEmpty()) { AppText.text(R.string.services_collection_next_page_failed) }
            return normalized
        }
        check(filters.isEmpty() || filters == listOf(required)) { AppText.text(R.string.services_collection_next_page_failed) }
        if (filters.isNotEmpty()) return normalized
        val encoded = required.first.replace("[", "%5B").replace("]", "%5D") + "=" + required.second
        return append(normalized, encoded)
    }

    private fun append(path: String, query: String) = path + (if ('?' in path) "&" else "?") + query
}
