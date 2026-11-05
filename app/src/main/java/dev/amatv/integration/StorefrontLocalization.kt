package dev.amatv.integration

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

internal data class StorefrontLocalization(val supported: List<String>, val default: String?)

internal fun parseStorefrontLocalization(root: JSONObject): StorefrontLocalization? {
    val attributes = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: return null
    val supported = attributes.optJSONArray("supportedLanguageTags") ?: return null
    val tags = (0 until supported.length()).mapNotNull { supported.optString(it).takeIf(String::isNotBlank) }
    if (tags.isEmpty()) return null
    return StorefrontLocalization(tags, attributes.optString("defaultLanguageTag").takeIf(String::isNotBlank))
}

/** Returns an actual supported tag; Chinese script differences never count as language matches. */
internal fun selectCatalogLanguage(requested: String, supported: List<String>, default: String?): String? {
    supported.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
    val target = Locale.forLanguageTag(requested)
    fun script(locale: Locale): String = locale.script.ifBlank {
        if (locale.language == "zh") when (locale.country) {
            "CN", "SG" -> "Hans"
            "TW", "HK", "MO" -> "Hant"
            else -> ""
        } else ""
    }
    supported.firstOrNull { tag ->
        val candidate = Locale.forLanguageTag(tag)
        candidate.language == target.language && (target.language != "zh" ||
            script(target).isNotBlank() && script(candidate) == script(target))
    }?.let { return it }
    return supported.firstOrNull { it.equals(default, ignoreCase = true) } ?: supported.firstOrNull()
}

/** Replace only l; preserve the exact encoding of provider continuation parameters. */
internal fun withCatalogLanguage(path: String, language: String): String {
    val query = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() &&
        URLDecoder.decode(it.substringBefore('='), "UTF-8") != "l" }
    return path.substringBefore('?') + "?" + (query + "l=${URLEncoder.encode(language, "UTF-8")}").joinToString("&")
}
