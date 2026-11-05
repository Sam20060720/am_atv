package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI

/** The same bearer/Origin contract as lyrics, using the existing bounded HTTP transport. */
internal class AppleMusicWebRequest(
    private val userToken: () -> String?,
    private val webTokens: AppleWebPlaybackTokenClient = AppleWebPlaybackTokenClient(),
) {
    suspend fun request(path: String, method: String = "GET", body: String? = null,
        needsUserToken: Boolean = true, maxResponseBytes: Int? = null,
        language: String? = null, fresh: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val endpoint = URI("https://amp-api.music.apple.com$path")
        require(path.startsWith("/v1/") && endpoint.scheme == "https" && endpoint.host == "amp-api.music.apple.com" &&
            endpoint.port == -1 && endpoint.userInfo == null && endpoint.fragment == null)
        require(method in setOf("GET", "POST", "PATCH", "PUT", "DELETE"))
        val owner = currentCoroutineContext()[AppleAuthorizationScope]
        val account = scopedAppleToken(userToken)?.takeIf(String::isNotBlank)
        if (needsUserToken && account == null) throw CatalogRequestException(CatalogFailureReason.Authorization,
            AppText.text(R.string.services_sign_in_apple_music))
        fun checkAccount() {
            owner?.checkCurrent(userToken)
            if (needsUserToken && userToken() != account) throw CancellationException("Account changed")
        }
        suspend fun execute(refresh: Boolean): CatalogHttpReply {
            val bearer = webTokens.token(forceRefresh = refresh)
            currentCoroutineContext().ensureActive()
            checkAccount()
            val payload = (body ?: if (method in setOf("POST", "PUT", "PATCH")) "" else null)
                ?.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(endpoint.toString()).header("Authorization", "Bearer $bearer")
                .header("Origin", "https://music.apple.com").header("Accept", "application/json")
                .apply {
                    if (needsUserToken) header("media-user-token", checkNotNull(account))
                    language?.takeIf(String::isNotBlank)?.let { header("Accept-Language", it) }
                    if (fresh && method == "GET") { header("Cache-Control", "no-cache"); header("Pragma", "no-cache") }
                }
                .method(method, payload).build()
            return CatalogHttpTransport().execute(request, maxResponseBytes)
        }
        val reply = withCatalogRequestDeadline {
            val first = execute(false)
            // Only a read can refresh/retry automatically. Writes are sent once.
            if (method == "GET" && first.status == 401) execute(true) else first
        }
        checkAccount()
        if (reply.status !in 200..299) throw catalogRequestFailure(reply.status, reply.body,
            AppText.text(R.string.services_apple_api_http_error, reply.status))
        if (reply.body.isBlank()) JSONObject() else withContext(Dispatchers.Default) { JSONObject(reply.body) }
    }
}
