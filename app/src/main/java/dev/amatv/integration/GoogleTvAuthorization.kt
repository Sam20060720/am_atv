package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import dev.amatv.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder

data class GoogleTvPairing(
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val expiresAtMs: Long,
    val pollIntervalSeconds: Int,
    internal val authorizationGeneration: Long = 0L,
)

data class GoogleTvAuthorizationState(val signedIn: Boolean, val scope: String)

/** Official limited-input OAuth flow; tokens are encrypted by SecureTokenStore. */
class GoogleTvAuthorization internal constructor(
    private val persistence: GoogleAuthorizationPersistence,
    private val post: suspend (String, Map<String, String>) -> GoogleOAuthResponse = ::postGoogleOAuth,
    private val oauthConfigured: () -> Boolean = { BuildConfig.YTM_OAUTH_CLIENT_ID.isNotBlank() &&
        BuildConfig.YTM_OAUTH_CLIENT_SECRET.isNotBlank() },
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
) {
    constructor(store: SecureTokenStore) : this(GoogleAuthorizationPersistence(
        store::readYtmSession, store::writeYtmSession, store::clearYtmSession,
    ))
    private val authorizationLock = Any()
    private var generation = 0L
    private val mutableState = MutableStateFlow(sessionState(persistence.read()))
    val state = mutableState.asStateFlow()
    private val refreshMutex = Mutex()
    val configured get() = oauthConfigured()
    val signedIn get() = state.value.signedIn

    suspend fun startPairing(): GoogleTvPairing {
        check(configured) { AppText.text(R.string.services_google_not_configured) }
        val attempt = synchronized(authorizationLock) { ++generation }
        val response = post(
            "https://oauth2.googleapis.com/device/code",
            mapOf(
                "client_id" to BuildConfig.YTM_OAUTH_CLIENT_ID,
                "scope" to "https://www.googleapis.com/auth/youtube.readonly",
            ),
        )
        check(response.status in 200..299) { AppText.text(R.string.services_google_pairing_start_failed, response.status) }
        currentCoroutineContext().ensureActive()
        synchronized(authorizationLock) { checkGeneration(attempt) }
        val json = response.body
        return GoogleTvPairing(
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url")
                .ifBlank { json.getString("verification_uri") },
            deviceCode = json.getString("device_code"),
            expiresAtMs = System.currentTimeMillis() + json.getLong("expires_in") * 1_000L,
            pollIntervalSeconds = json.optInt("interval", 5).coerceAtLeast(5),
            authorizationGeneration = attempt,
        )
    }

    suspend fun awaitPairing(pairing: GoogleTvPairing) {
        var interval = pairing.pollIntervalSeconds
        while (System.currentTimeMillis() < pairing.expiresAtMs) {
            synchronized(authorizationLock) { checkGeneration(pairing.authorizationGeneration) }
            pollDelay(interval * 1_000L)
            synchronized(authorizationLock) { checkGeneration(pairing.authorizationGeneration) }
            val response = post(
                "https://oauth2.googleapis.com/token",
                mapOf(
                    "client_id" to BuildConfig.YTM_OAUTH_CLIENT_ID,
                    "client_secret" to BuildConfig.YTM_OAUTH_CLIENT_SECRET,
                    "device_code" to pairing.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            currentCoroutineContext().ensureActive()
            synchronized(authorizationLock) { checkGeneration(pairing.authorizationGeneration) }
            val body = response.body
            if (response.status in 200..299) {
                val refresh = body.optString("refresh_token")
                check(refresh.isNotBlank()) { AppText.text(R.string.services_google_refresh_token_missing) }
                synchronized(authorizationLock) {
                    checkGeneration(pairing.authorizationGeneration)
                    saveTokens(body, refresh, UUID.randomUUID().toString())
                    generation++
                }
                return
            }
            when (body.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> error(AppText.text(R.string.services_google_access_denied))
                "expired_token" -> error(AppText.text(R.string.services_google_pairing_expired))
                else -> error(AppText.text(R.string.services_google_pairing_failed, response.status))
            }
        }
        error(AppText.text(R.string.services_google_pairing_expired))
    }

    suspend fun accessToken(): String? = refreshMutex.withLock {
        val (attempt, saved) = synchronized(authorizationLock) {
            generation to (persistence.read()?.let(::JSONObject) ?: return@withLock null)
        }
        if (saved.optLong("expires_at_ms") > System.currentTimeMillis() + 60_000L) {
            return@withLock synchronized(authorizationLock) {
                checkGeneration(attempt)
                saved.optString("access_token").takeIf(String::isNotBlank)
            }
        }
        if (!configured) return@withLock null
        val refresh = saved.optString("refresh_token").takeIf(String::isNotBlank)
            ?: return@withLock null
        val response = post(
            "https://oauth2.googleapis.com/token",
            mapOf(
                "client_id" to BuildConfig.YTM_OAUTH_CLIENT_ID,
                "client_secret" to BuildConfig.YTM_OAUTH_CLIENT_SECRET,
                "refresh_token" to refresh,
                "grant_type" to "refresh_token",
            ),
        )
        currentCoroutineContext().ensureActive()
        synchronized(authorizationLock) {
            checkGeneration(attempt)
            if (response.status !in 200..299) {
                if (response.body.optString("error") == "invalid_grant") {
                    generation++
                    persistence.clear()
                    mutableState.value = GoogleTvAuthorizationState(false, "guest")
                }
                return@withLock null
            }
            saveTokens(response.body, response.body.optString("refresh_token").ifBlank { refresh },
                sessionState(saved.toString()).scope)
            response.body.optString("access_token").takeIf(String::isNotBlank)
        }
    }

    /** Also invalidates in-flight refreshes and any previous device-code attempt. */
    fun cancelPairing() = synchronized(authorizationLock) { generation++; Unit }

    fun signOut() = synchronized(authorizationLock) {
        generation++
        persistence.clear()
        mutableState.value = GoogleTvAuthorizationState(false, "guest")
    }

    private fun checkGeneration(expected: Long) {
        if (generation != expected) throw CancellationException("Google authorization changed")
    }

    private fun saveTokens(body: JSONObject, refresh: String, scope: String) {
        persistence.write(JSONObject()
            .put("access_token", body.getString("access_token"))
            .put("refresh_token", refresh)
            .put("authorization_scope", scope)
            .put("expires_at_ms", System.currentTimeMillis() +
                body.optLong("expires_in", 3_600L) * 1_000L)
            .toString())
        mutableState.value = GoogleTvAuthorizationState(true, scope)
    }

}

internal data class GoogleAuthorizationPersistence(
    val read: () -> String?, val write: (String) -> Unit, val clear: () -> Unit,
)
internal data class GoogleOAuthResponse(val status: Int, val body: JSONObject)

private suspend fun postGoogleOAuth(url: String, form: Map<String, String>): GoogleOAuthResponse = withCatalogRequestDeadline {
    currentCoroutineContext().ensureActive()
    val encoded = form.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
    val reply = CatalogHttpTransport().execute(Request.Builder().url(url)
        .post(encoded.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .build(), maxResponseBytes = 128 * 1024)
    GoogleOAuthResponse(reply.status, JSONObject(reply.body))
}

/** Legacy sessions derive identity from the stable refresh credential, never the rotating access token. */
internal fun sessionState(session: String?): GoogleTvAuthorizationState {
    val saved = session?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: return GoogleTvAuthorizationState(false, "guest")
    val refresh = saved.optString("refresh_token").takeIf(String::isNotBlank)
        ?: return GoogleTvAuthorizationState(false, "guest")
    val scope = saved.optString("authorization_scope").takeIf(String::isNotBlank)
        ?: MessageDigest.getInstance("SHA-256").digest(refresh.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return GoogleTvAuthorizationState(true, scope)
}
