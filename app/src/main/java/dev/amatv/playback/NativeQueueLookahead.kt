package dev.amatv.playback

import dev.amatv.integration.NativeQueueEntry
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSnapshot
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

internal enum class NativeSnapshotAction { Keep, AdoptAdjacent, ReprepareCurrent, Ignore }

/** Evaluate against the live queue, before publishing any SDK occurrence. */
internal fun nativeSnapshotAction(state: QueueState, snapshot: PlaybackSnapshot): NativeSnapshotAction {
    if (snapshot.nativeQueueRejected) return NativeSnapshotAction.ReprepareCurrent
    val current = state.current ?: return NativeSnapshotAction.Ignore
    val track = snapshot.currentTrack
    val incoming = snapshot.appQueueEntryId
    if (snapshot.nativeQueueHandoff && incoming != null && incoming != current.entryId) {
        val adjacent = state.playableFuture.firstOrNull()
        return if (adjacent != null && adjacent.entryId == incoming && track != null && track.catalogKind == adjacent.track.catalogKind &&
            track.catalogId == adjacent.track.catalogId) NativeSnapshotAction.AdoptAdjacent
        else NativeSnapshotAction.ReprepareCurrent
    }
    val matching = (incoming == null || incoming == current.entryId) && track != null &&
        track.catalogKind == current.track.catalogKind && track.catalogId == current.track.catalogId
    return if (matching) NativeSnapshotAction.Keep else if (snapshot.nativeQueueHandoff)
        NativeSnapshotAction.ReprepareCurrent else NativeSnapshotAction.Ignore
}
