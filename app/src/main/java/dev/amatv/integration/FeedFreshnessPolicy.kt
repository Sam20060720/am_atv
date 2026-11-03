package dev.amatv.integration

internal enum class CatalogFeedKind { Home, Browse }

/** Persist wall time only; elapsed time belongs to the current running process. */
internal object FeedFreshnessPolicy {
    fun ttlMillis(kind: CatalogFeedKind): Long = when (kind) {
        CatalogFeedKind.Home -> 60_000L
        CatalogFeedKind.Browse -> 5 * 60_000L
    }

    fun shouldRefresh(
        kind: CatalogFeedKind,
        visible: Boolean,
        hasContent: Boolean,
        snapshotWrittenAtEpochMs: Long? = null,
        lastSuccessElapsedMs: Long? = null,
        lastAttemptElapsedMs: Long? = null,
        inFlight: Boolean = false,
        force: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long,
    ): Boolean {
        if (!visible || inFlight) return false
        if (force) return true
        val ttl = ttlMillis(kind)
        // A failed refresh must not become a recomposition-triggered request loop.
        elapsedAge(nowElapsedMs, lastAttemptElapsedMs)?.let { if (it < ttl) return false }
        if (!hasContent) return true
        val age = elapsedAge(nowElapsedMs, lastSuccessElapsedMs)
            ?: wallAge(nowEpochMs, snapshotWrittenAtEpochMs)
        // A backward wall-clock adjustment cannot make a snapshot fresh forever.
        return age == null || age >= ttl
    }

    /** For a visible-page timer; null means the host must not schedule background polling. */
    fun nextCheckDelayMillis(
        kind: CatalogFeedKind,
        visible: Boolean,
        hasContent: Boolean,
        snapshotWrittenAtEpochMs: Long? = null,
        lastSuccessElapsedMs: Long? = null,
        lastAttemptElapsedMs: Long? = null,
        inFlight: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long,
    ): Long? {
        if (!visible || inFlight) return null
        val ttl = ttlMillis(kind)
        val sinceAttempt = elapsedAge(nowElapsedMs, lastAttemptElapsedMs)
        val attemptWait = sinceAttempt?.let { (ttl - it).coerceAtLeast(0L) } ?: 0L
        val age = elapsedAge(nowElapsedMs, lastSuccessElapsedMs)
            ?: wallAge(nowEpochMs, snapshotWrittenAtEpochMs)
        val freshnessWait = if (!hasContent || age == null) 0L else (ttl - age).coerceAtLeast(0L)
        return maxOf(attemptWait, freshnessWait)
    }

    private fun elapsedAge(now: Long, previous: Long?): Long? =
        previous?.takeIf { it >= 0L && now >= it }?.let { now - it }
    private fun wallAge(now: Long, previous: Long?): Long? =
        previous?.takeIf { it > 0L && now >= it }?.let { now - it }
}
