package dev.amatv.integration

import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.net.URI

/** Anonymous reads share cancellation, bounded workers and one redirect/full-body deadline. */
internal suspend fun fetchPublicMusicHtml(
    address: String,
    language: String,
    maxResponseBytes: Int = 4 * 1024 * 1024,
    maxRequests: Int = 4,
    calls: Call.Factory = catalogHttpClient,
): String = withCatalogRequestDeadline {
    var uri = URI(address)
    val country = uri.path.split('/').getOrNull(1)?.takeIf { it.matches(Regex("[a-z]{2}")) }
        ?: throw IOException("Invalid public storefront")
    repeat(maxRequests) {
        require(uri.scheme == "https" && uri.host == "music.apple.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.fragment == null && uri.path.startsWith("/$country/"))
        val reply = CatalogHttpTransport(calls).execute(Request.Builder().url(uri.toString())
            .header("Accept", "text/html").header("Accept-Language", language).get().build(), maxResponseBytes)
        if (reply.status in setOf(301, 302, 303, 307, 308)) {
            uri = uri.resolve(reply.location ?: throw IOException("Missing public redirect"))
        } else {
            if (reply.status != 200) throw IOException("Public page HTTP ${reply.status}")
            return@withCatalogRequestDeadline reply.body
        }
    }
    throw IOException("Too many public redirects")
}
