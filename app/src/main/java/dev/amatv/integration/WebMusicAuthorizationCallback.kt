package dev.amatv.integration

import org.json.JSONObject
import java.util.Base64

/** Select the token field once; a missing alias must not reset a previously selected value. */
internal fun webMusicAuthorizationCallback(fragment: String): String? {
    val fields = fragment.split('&')
    val selected = fields.firstOrNull {
        it.substringBefore('=') in setOf("music-user-token", "musicUserToken")
    }?.substringAfter('=') ?: fragment.takeIf { fields.size == 1 } ?: return null
    val value = selected.trim()
    val token = runCatching {
        val decoded = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        JSONObject(decoded).optString("musicUserToken").takeIf { it.isNotBlank() } ?: value
    }.getOrDefault(value)
    return token.takeIf { it.length in 21..8_192 && it.none(Char::isWhitespace) }
}
