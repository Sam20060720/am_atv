package dev.amatv.integration

import dev.amatv.model.LyricLine
import kotlinx.coroutines.delay

typealias LyricsFailureReason = CatalogFailureReason

class LyricsRequestException(val reason: LyricsFailureReason, message: String) : IllegalStateException(message)

internal fun lyricsHttpFailure(status: Int): LyricsFailureReason = catalogHttpFailure(status)

internal sealed interface LyricsRequestResult {
    data class Available(val lines: List<LyricLine>) : LyricsRequestResult
    data object Unavailable : LyricsRequestResult
    data class RetryableFailure(val reason: LyricsFailureReason) : LyricsRequestResult
}

/** Retry transport failures without turning them into a permanent "no lyrics" cache entry. */
internal suspend fun requestLyricsWithRetry(
    fetch: suspend () -> List<LyricLine>,
    wait: suspend (Long) -> Unit = { delay(it) },
): LyricsRequestResult {
    val lines = retryRequest(fetch, wait) { it.lyricsFailureReason() == LyricsFailureReason.Transient }
        .getOrElse { error ->
            return when (val reason = error.lyricsFailureReason()) {
                LyricsFailureReason.Unavailable -> LyricsRequestResult.Unavailable
                else -> LyricsRequestResult.RetryableFailure(reason)
            }
        }
    return if (lines.isEmpty()) LyricsRequestResult.Unavailable else LyricsRequestResult.Available(lines)
}

private fun Throwable.lyricsFailureReason(): LyricsFailureReason {
    // Inspect every cause first, so a wrapped cancellation always propagates.
    val catalogReason = catalogFailureReason()
    return (this as? LyricsRequestException)?.reason ?: catalogReason ?: LyricsFailureReason.Transient
}
