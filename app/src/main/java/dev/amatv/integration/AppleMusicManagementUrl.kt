package dev.amatv.integration

import java.net.URI
import java.net.URLDecoder

/**
 * Only use the service's existing canonical Apple Music URL. No account token,
 * private API endpoint, proxy, redirect target or reconstructed resource URL.
 */
internal fun appleMusicManagementUrl(raw: String?): String? = appleMusicResourceUrl(raw, sharing = false)

/** Sharing also supports the canonical station and music-video links returned by Apple Music. */
internal fun appleMusicShareUrl(raw: String?): String? = appleMusicResourceUrl(raw, sharing = true)

private fun appleMusicResourceUrl(raw: String?, sharing: Boolean): String? {
    if (raw == null || raw.length !in 1..2048 || raw != raw.trim() ||
        raw.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.rawAuthority != "music.apple.com" ||
        uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
    val rawPath = uri.rawPath ?: return null
    if ("//" in rawPath || rawPath.contains(Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE)) || '\\' in rawPath) return null
    val parts = uri.path?.split('/')?.filter { it.isNotEmpty() } ?: return null
    if (parts.any { it == "." || it == ".." || it.any(Char::isISOControl) }) return null
    val path = if (parts.firstOrNull()?.matches(Regex("[a-z]{2}(?:-[a-z]{2})?")) == true) parts.drop(1) else parts
    val resourceId = path.lastOrNull().orEmpty()
    val resourceKind = path.firstOrNull()
    val publicResource = path.size in 2..3 && when (resourceKind) {
        "playlist" -> resourceId.matches(Regex("pl\\.[A-Za-z0-9._-]+"))
        "song", "album", "artist" -> resourceId.matches(Regex("[1-9][0-9]*"))
        "music-video" -> sharing && resourceId.matches(Regex("[1-9][0-9]*"))
        "station" -> sharing && resourceId.matches(Regex("ra\\.[A-Za-z0-9._-]+"))
        else -> false
    }
    // This route is accepted only when the service actually returned it, not
    // generated from a p.* identifier. Phone Apple Music owns authorization.
    val libraryPlaylist = path.size == 3 && path[0] == "library" && path[1] == "playlist" &&
        path[2].matches(Regex("p\\.[A-Za-z0-9._-]+"))
    if (!publicResource && !libraryPlaylist) return null
    val query = uri.rawQuery
    if (query != null) {
        if (query.isBlank()) return null
        val seen = mutableSetOf<String>()
        for (part in query.split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) return null
            val name = runCatching { URLDecoder.decode(pair[0], "UTF-8") }.getOrNull() ?: return null
            val value = runCatching { URLDecoder.decode(pair[1], "UTF-8") }.getOrNull() ?: return null
            if (!seen.add(name)) return null
            if (name == "i") {
                if (path.firstOrNull() != "album" || !value.matches(Regex("[1-9][0-9]*"))) return null
            } else if (name == "l") {
                if (!value.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})*"))) return null
            } else return null
        }
    }
    return raw
}
