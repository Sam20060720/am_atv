package dev.amatv.playback

/** A metadata-only restored or last-known cursor survives an unprepared engine. */
internal class PlaybackMetadataCursor {
    private var owner: Pair<Long, Long>? = null
    private var positionMs = 0L

    fun remember(sessionId: Long, entryId: Long, positionMs: Long) {
        owner = sessionId to entryId
        this.positionMs = positionMs.coerceAtLeast(0L)
    }

    fun position(sessionId: Long, entryId: Long): Long? =
        positionMs.takeIf { owner == (sessionId to entryId) }

    fun clear() { owner = null; positionMs = 0L }
}
