package dev.amatv.integration

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Optional scoped artwork metadata for one batch/page; rejection keeps its ordinary data usable. */
internal suspend fun requestEditorialVideo(
    path: String,
    resourceTypes: Set<String>,
    fetch: suspend (String) -> JSONObject,
): JSONObject {
    val keys = URI(path).rawQuery.orEmpty().split('&').filter(String::isNotEmpty)
        .map { URLDecoder.decode(it.substringBefore('='), "UTF-8") }.toSet()
    val types = resourceTypes.map { it.removePrefix("library-") }
        .filter { it in setOf("songs", "albums", "artists", "playlists", "music-videos") }.distinct().sorted()
    val parameters = types.filter { "extend[$it]" !in keys }.joinToString("&") { "extend%5B$it%5D=editorialVideo" }
    if (parameters.isEmpty() || "extend" in keys) return fetch(path)
    return try { fetch(path + (if ('?' in path) "&" else "?") + parameters) }
    catch (error: CatalogRequestException) {
        if (error.httpStatus !in setOf(400, 403)) throw error
        fetch(path)
    }
}
