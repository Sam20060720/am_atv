package dev.amatv.playback

import dev.amatv.integration.NativeQueueEntry
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind

/** Do not jump over an unresolved library entry or preload a false repeat cycle. */
internal fun nativeQueueLookahead(state: QueueState): List<NativeQueueEntry> {
    if (state.current?.track?.catalogKind != CatalogKind.Song || state.repeat == PlaybackRepeatMode.One) return emptyList()
    return state.playableFuture.asSequence().takeWhile { entry ->
        val song = entry.track
        val id = song.catalogId ?: song.id
        song.catalogKind == CatalogKind.Song && id.isNotEmpty() && id.all(Char::isDigit) && id.toLongOrNull()?.let { it > 0L } == true
    }.take(2).map { NativeQueueEntry(it.entryId, it.track) }.toList()
}
