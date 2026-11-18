package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** HTTP catalog failures carry service meaning, unlike MusicKit's native component codes. */
enum class CatalogFailureReason { Unavailable, Authorization, Transient }

internal class CatalogRequestException(
    val reason: CatalogFailureReason,
    message: String,
    val httpStatus: Int? = null,
    /** Apple service code, distinct from HTTP status and native MusicKit error codes. */
    val serviceCode: String? = null,
) : IllegalStateException(message)

/** Retain a uniform API error code without exposing the response body or credentials to UI. */
internal fun catalogRequestFailure(status: Int, body: String, message: String): CatalogRequestException {
    val serviceCode = runCatching {
        val errors = JSONObject(body).optJSONArray("errors") ?: return@runCatching null
        val codes = (0 until errors.length()).map { index ->
            errors.optJSONObject(index)?.optString("code")?.takeIf { it.isNotBlank() && it != "null" }
        }
        if (codes.isEmpty() || codes.any { it == null }) null else codes.distinct().singleOrNull()
    }.getOrNull()
    return CatalogRequestException(catalogHttpFailure(status), message, status, serviceCode)
}

/** A missing optional relation is not a missing artist or a failed connection. */
internal suspend fun requestRelationshipData(fetch: suspend () -> JSONObject): JSONObject = try {
    fetch()
} catch (error: CatalogRequestException) {
    // Only use inside an already validated relationship/view request. Other
    // 404s (missing resources), authorization and network failures remain errors.
    if (error.httpStatus != 404 || error.serviceCode != "40403") throw error
    JSONObject().put("data", JSONArray())
}

internal fun catalogHttpFailure(status: Int): CatalogFailureReason = when (status) {
    404, 410 -> CatalogFailureReason.Unavailable
    401, 403 -> CatalogFailureReason.Authorization
    else -> CatalogFailureReason.Transient
}

/** A reflected/suspended request may wrap its original service error or cancellation. */
internal fun Throwable.catalogFailureReason(): CatalogFailureReason? {
    val causes = generateSequence(this) { it.cause }.take(8).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    return causes.filterIsInstance<CatalogRequestException>().firstOrNull()?.reason
}

/** Resolve metadata with bounded transport retries; auth and unavailable are final verdicts. */
internal suspend fun <T> retryCatalogRequest(
    fetch: suspend () -> T,
    wait: suspend (Long) -> Unit = { delay(it) },
): T = retryRequest(fetch, wait) { error ->
    classifyPlaybackFailure(null, null, null, error) == PlaybackFailureKind.Network
}.getOrThrow()

/** Share a bounded retry budget while callers retain their service semantics. */
internal suspend fun <T> retryRequest(
    fetch: suspend () -> T,
    wait: suspend (Long) -> Unit,
    isTransient: (Exception) -> Boolean,
): Result<T> {
    val delays = longArrayOf(2_000L, 5_000L)
    var attempt = 0
    while (true) {
        try {
            return Result.success(fetch())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isTransient(error) || attempt == delays.size) return Result.failure(error)
        }
        wait(delays[attempt++])
    }
}

internal fun requireEquivalentCatalogSongId(
    root: JSONObject,
    originalId: String,
    unavailableMessage: String,
    transientMessage: String,
): String {
    equivalentCatalogSongId(root, originalId)?.let { return it }
    val mapping = root.optJSONObject("meta")?.optJSONObject("filters")
        ?.optJSONObject("equivalents")?.optJSONArray(originalId)
    // An explicit empty mapping is Apple's unavailable verdict. Missing or
    // inconsistent metadata is not proof that the recording should be skipped.
    val unavailable = mapping != null && mapping.length() == 0
    throw CatalogRequestException(
        if (unavailable) CatalogFailureReason.Unavailable else CatalogFailureReason.Transient,
        if (unavailable) unavailableMessage else transientMessage,
    )
}
