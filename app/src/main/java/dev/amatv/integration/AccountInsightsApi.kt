package dev.amatv.integration

import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.ListeningHistorySection
import dev.amatv.model.ListeningPage
import dev.amatv.model.ListeningPageCursor
import dev.amatv.model.ListeningRecommendation
import dev.amatv.model.ListeningReplay
import dev.amatv.model.ListeningReplayEntry
import dev.amatv.model.ListeningReplaySummary
import dev.amatv.model.ListeningReplayView
import dev.amatv.model.RecommendationEntry
import dev.amatv.model.ReplayView
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Authenticated, demand-paged account data. No private Replay website endpoint or account writes. */
class AccountInsightsApi internal constructor(
    private val storefront: suspend () -> String,
    private val request: suspend (String, Boolean) -> JSONObject,
    private val parseItem: (JSONObject) -> Track?,
    private val parseDispatcher: CoroutineDispatcher,
) {
    suspend fun history(
        section: ListeningHistorySection,
        nextPage: ListeningPageCursor? = null,
    ): ListeningPage<Track> = withContext(parseDispatcher) {
        val endpoint = when (section) {
            ListeningHistorySection.RecentTracks -> "/v1/me/recent/played/tracks"
            ListeningHistorySection.RecentResources -> "/v1/me/recent/played"
            ListeningHistorySection.HeavyRotation -> "/v1/me/history/heavy-rotation"
            ListeningHistorySection.RecentStations -> "/v1/me/recent/radio-stations"
        }
        val parameters = when (section) {
            ListeningHistorySection.RecentTracks -> mapOf("types" to "songs,library-songs,music-videos,library-music-videos",
                "include[library-songs]" to "catalog", "include[library-music-videos]" to "catalog")
            ListeningHistorySection.RecentResources -> mapOf("types" to "artists,curators,albums,library-albums,playlists,library-playlists,stations",
                "include[library-albums]" to "catalog", "include[library-playlists]" to "catalog")
            else -> emptyMap()
        }
        val limit = when (section) { ListeningHistorySection.RecentTracks -> 30; ListeningHistorySection.RecentResources -> 10; else -> 25 }
        val first = withParameters("$endpoint?limit=$limit", parameters)
        val scope = "history:${section.name}"
        val path = nextPath(nextPage, scope, endpoint) ?: first
        val root = fetch(path)
        val data = root.requireData()
        val resources = hydrate(data.objects())
        ListeningPage(resources.mapNotNull { parseItem(it) }, cursor(root, scope, endpoint, path, parameters), path, root.total())
    }

    suspend fun recommendations(
        nextPage: ListeningPageCursor? = null,
    ): ListeningPage<ListeningRecommendation> = withContext(parseDispatcher) {
        val scope = "recommendations"
        val path = nextPath(nextPage, scope, recommendationsEndpoint) ?: "$recommendationsEndpoint?limit=10"
        val root = fetch(path)
        val groups = root.requireData().objects().filter { it.optString("type") == recommendationType }
            .map(::recommendation)
        ListeningPage(groups, cursor(root, scope, recommendationsEndpoint, path), path, root.total())
    }

    /** Identifier-only groups are resolved when opened, never through eager per-card requests. */
    suspend fun recommendation(id: String): ListeningRecommendation = withContext(parseDispatcher) {
        val root = fetch("$recommendationsEndpoint/${identifier(id)}")
        val resource = root.requireData().objects().firstOrNull {
            it.optString("id") == id && it.optString("type") == recommendationType
        } ?: throw CatalogRequestException(CatalogFailureReason.Unavailable, "Recommendation unavailable")
        recommendation(resource)
    }

    suspend fun recommendationContents(
        id: String,
        nextPage: ListeningPageCursor? = null,
    ): ListeningPage<RecommendationEntry> = withContext(parseDispatcher) {
        val endpoint = "$recommendationsEndpoint/${identifier(id)}/contents"
        val scope = "recommendation:$id"
        val path = nextPath(nextPage, scope, endpoint) ?: "$endpoint?limit=10"
        val root = fetch(path)
        val data = root.requireData().objects()
        val resources = data.filter { it.optString("type") != recommendationType }
        val resolved = hydrate(resources).associateBy(::resourceKey)
        val entries = data.mapNotNull { resource ->
            if (resource.optString("type") == recommendationType) {
                RecommendationEntry.Group(recommendation(resource))
            } else {
                val item = resolved[resourceKey(resource)]?.let(parseItem)
                item?.let { RecommendationEntry.Item(it) } ?: ref(resource)?.let { RecommendationEntry.Unresolved(it) }
            }
        }
        ListeningPage(entries, cursor(root, scope, endpoint, path), path, root.total())
    }

    suspend fun replay(): ListeningReplay = withContext(parseDispatcher) {
        val root = fetch(replayRequest)
        ListeningReplay(root.requireData().objects().filter { it.optString("type") == "music-summaries" }
            .map(::summary))
    }

    /** Request one selected view. A summary's ID, period, year and ordering all come from Apple. */
    suspend fun replayView(
        selected: ListeningReplaySummary,
        view: ReplayView,
        nextPage: ListeningPageCursor? = null,
    ): ListeningReplayView = withContext(parseDispatcher) {
        val scope = "replay:${selected.id}:${view.id}"
        val path = nextPage?.let {
            require(it.scope == scope)
            require(isReplayPath(it.endpoint))
            nextPath(it, scope, it.endpoint)
        } ?: "$replayRequest&views=${view.id}&include%5B${view.summaryType}%5D=${view.relationship}"
        val root = fetch(checkNotNull(path))
        val data: JSONObject
        if (root.optJSONArray("data")?.objects()?.any { it.optString("type") == "music-summaries" } == true ||
            nextPage == null
        ) {
            val resource = root.requireData().objects().firstOrNull {
                it.optString("id") == selected.id && it.optString("type") == "music-summaries"
            } ?: return@withContext ListeningReplayView.SummaryMissing
            data = resource.optJSONObject("views")?.optJSONObject(view.id)
                ?: return@withContext ListeningReplayView.NotProvided
        } else data = root
        val wrappers = data.requireData().objects().filter { it.optString("type") == view.summaryType }
        val references = wrappers.flatMap { related(it, view).objects() }
        val resolved = hydrate(references).associateBy(::resourceKey)
        val entries = wrappers.map { wrapper ->
            val resources = related(wrapper, view).objects()
            val parsed = resources.map { resolved[resourceKey(it)]?.let(parseItem) }
            val items = parsed.filterNotNull()
            val missing = resources.filterIndexed { index, _ -> parsed[index] == null }.mapNotNull(::ref)
            ListeningReplayEntry(wrapper.optString("id"), wrapper.optString("type"), items, missing,
                wrapper.optJSONObject("relationships")?.optJSONObject(view.relationship)?.optJSONArray("data") != null)
        }
        // An embedded view may have its own location rather than the root request's location.
        val viewEndpoint = data.text("href")?.let { safePath(it, relativeEndpoint = replayEndpoint).substringBefore('?') }
            ?.also { require(isReplayPath(it)) } ?: replayEndpoint
        val next = data.text("next")?.let { href ->
            val normalized = withParameters(safePath(href, relativeEndpoint = viewEndpoint),
                mapOf("include[${view.summaryType}]" to view.relationship))
            val target = normalized.substringBefore('?')
            require(isReplayPath(target))
            if (data.text("href") != null) require(target == viewEndpoint || target == replayEndpoint)
            ListeningPageCursor(scope, target, normalized).also { require(normalized != path) }
        }
        ListeningReplayView.Page(ListeningPage(entries, next, path, data.total()))
    }

    private fun recommendation(resource: JSONObject): ListeningRecommendation {
        val id = resourceId(resource.optString("id"))
        val attributes = resource.optJSONObject("attributes")
        return ListeningRecommendation(
            id = id,
            title = attributes?.displayText("title"),
            reason = attributes?.displayText("reason"),
            resourceTypes = attributes?.optJSONArray("resourceTypes")?.strings()?.toSet().orEmpty(),
        )
    }

    /** Resolve only identifiers on this requested page, grouped into bounded catalog requests. */
    private suspend fun hydrate(resources: List<JSONObject>): List<JSONObject> {
        val resolved = resources.associateBy(::resourceKey).toMutableMap()
        val missing = resources.filter { it.optJSONObject("attributes") == null }.mapNotNull(::ref)
            .filter { it.type in hydratableTypes }.distinctBy { it.key }.groupBy { it.type }
        if (missing.isEmpty()) return resources
        val sf = storefront().also { require(it.matches(Regex("[a-z]{2}"))) }
        for ((type, refs) in missing) for (batch in refs.chunked(25)) {
            currentCoroutineContext().ensureActive()
            val ids = batch.joinToString(",") { encode(it.id) }
            val library = type.startsWith("library-")
            val path = if (library) "/v1/me/library/${type.removePrefix("library-")}?ids=$ids&include=catalog"
                else "/v1/catalog/$sf/$type?ids=$ids"
            val root = try { fetch(path, user = library) }
            catch (error: CatalogRequestException) {
                if (error.reason != CatalogFailureReason.Unavailable) throw error
                continue
            }
            root.requireData().objects().forEach { item ->
                if (item.optString("type") == type && batch.any { it.id == item.optString("id") }) resolved[resourceKey(item)] = item
            }
        }
        return resources.map { resolved[resourceKey(it)] ?: it }
    }

    private suspend fun fetch(path: String, user: Boolean = true): JSONObject {
        currentCoroutineContext().ensureActive()
        return request(path, user).also { currentCoroutineContext().ensureActive() }
    }

    private fun summary(resource: JSONObject): ListeningReplaySummary {
        val attributes = resource.optJSONObject("attributes")
        return ListeningReplaySummary(resource.optString("id"), attributes?.text("period"),
            (attributes?.opt("year") as? Number)?.toInt())
    }

    private fun cursor(root: JSONObject, scope: String, endpoint: String, requested: String, parameters: Map<String, String> = emptyMap()): ListeningPageCursor? =
        root.text("next")?.let { href ->
            val normalized = withParameters(safePath(href, endpoint), parameters)
            check(normalized != requested) { "Repeated account page" }
            ListeningPageCursor(scope, endpoint, normalized)
        }

    private fun nextPath(cursor: ListeningPageCursor?, scope: String, endpoint: String): String? = cursor?.let {
        require(it.scope == scope && it.endpoint == endpoint) { "Cursor belongs to another account section" }
        safePath(it.path, endpoint)
    }

    private fun safePath(href: String, endpoint: String? = null, relativeEndpoint: String = endpoint ?: "/"): String {
        val uri = apiOrigin.resolve(if (href.startsWith('?')) relativeEndpoint + href else href)
        require(uri.scheme == "https" && uri.host == "api.music.apple.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.rawPath == uri.normalize().rawPath)
        require(endpoint == null || uri.rawPath == endpoint) { "Cursor belongs to another endpoint" }
        return uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun isReplayPath(path: String) = path == replayEndpoint ||
        path.startsWith("$replayEndpoint/") && !path.contains('%') && !path.contains("..")

    /** Keep Apple's opaque query and restore required/scoped parameters only when omitted. */
    private fun withParameters(path: String, parameters: Map<String, String>): String {
        val present = URI(path).rawQuery.orEmpty().split('&').map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8")
        }.toSet()
        val additions = parameters.filterKeys { it !in present }.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value).replace("%2C", ",")}"
        }
        return path + if (additions.isEmpty()) "" else (if ('?' in path) "&" else "?") + additions
    }

    private fun related(resource: JSONObject, view: ReplayView): JSONArray =
        resource.optJSONObject("relationships")?.optJSONObject(view.relationship)?.optJSONArray("data") ?: JSONArray()

    private fun ref(resource: JSONObject): CatalogResourceRef? = runCatching {
        CatalogResourceRef(resource.getString("id"), resource.getString("type"))
    }.getOrNull()

    private fun resourceKey(resource: JSONObject) = "${resource.optString("type")}:${resource.optString("id")}"
    private fun resourceId(id: String): String = id.also {
        require(it.isNotBlank() && it.length <= 512 && it != "." && it != ".." && it.none(Char::isISOControl))
    }
    private fun identifier(id: String): String = encode(resourceId(id)).replace("+", "%20")
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun JSONObject.text(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
    private fun JSONObject.displayText(key: String): String? = (opt(key) as? String)?.takeIf(String::isNotBlank)
        ?: optJSONObject(key)?.text("stringForDisplay")
    private fun JSONObject.requireData(): JSONArray = checkNotNull(optJSONArray("data")) { "Account response missing data" }
    private fun JSONObject.total(): Int? = (optJSONObject("meta")?.opt("total") as? Number)?.toInt()?.takeIf { it >= 0 }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }

    private companion object {
        val apiOrigin: URI = URI("https://api.music.apple.com")
        const val recommendationsEndpoint = "/v1/me/recommendations"
        const val recommendationType = "personal-recommendation"
        const val replayEndpoint = "/v1/me/music-summaries"
        const val replayRequest = "$replayEndpoint?filter%5Byear%5D=latest"
        val hydratableTypes = setOf("songs", "albums", "artists", "playlists", "music-videos", "stations", "curators", "apple-curators",
            "library-songs", "library-albums", "library-artists", "library-playlists", "library-music-videos")
    }
}
