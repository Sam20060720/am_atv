package dev.amatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.res.stringResource
import dev.amatv.R
import dev.amatv.integration.MusicServices
import dev.amatv.model.Track
import dev.amatv.model.CatalogResourceRef
import dev.amatv.playback.QueueEntry
import dev.amatv.playback.QueueState
import dev.amatv.playback.PlaybackSleepTimerState
import dev.amatv.playback.SleepTimerOption

/** Local queue edits reuse the existing Apple-style menu, including its dismissal animation. */
@Composable
internal fun QueueEntryActionSheet(
    entry: QueueEntry,
    queue: QueueState,
    services: MusicServices,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
    onLibraryChanged: () -> Unit,
    onViewArtist: (Track) -> Unit,
    onOpenResource: (Track) -> Unit,
    onShowDetails: (Track) -> Unit,
    onCreateStation: (Track) -> Unit,
    onRemove: (entryId: Long) -> Unit,
    onMoveBy: (entryId: Long, offset: Int) -> Unit,
    anchorBounds: Rect? = null,
    backdropLayer: GraphicsLayer? = null,
    lowPerformanceMode: Boolean = false,
    onMediaKey: (Int) -> Boolean = { false },
    modalInputGate: CatalogModalInputGate? = null,
    sleepTimerState: PlaybackSleepTimerState? = null,
    onSleepTimer: ((SleepTimerOption) -> Unit)? = null,
    canSleepAtTrackEnd: Boolean = false,
    canSleepAtContextEnd: Boolean = false,
    onResourceDeleted: ((Track) -> Unit)? = null,
) {
    val future = queue.upNext + queue.autoplay
    val index = future.indexOfFirst { it.entryId == entry.entryId }
    val actions = buildList {
        if (entry.track.catalogKind == dev.amatv.model.CatalogKind.Song && entry.track.catalogId != null) {
            add(CatalogSheetAction("create-station", stringResource(R.string.actions_create_station)) {
                onCreateStation(entry.track)
            })
        }
        if (CatalogResourceRef.fromTrack(entry.track) != null) {
            add(CatalogSheetAction("details", stringResource(R.string.catalog_details)) {
                onShowDetails(entry.track)
            })
        }
        if (index >= 0) {
            add(CatalogSheetAction("queue-remove", stringResource(R.string.actions_queue_remove)) {
                onRemove(entry.entryId)
            })
            if (index > 0) add(CatalogSheetAction("queue-move-up", stringResource(R.string.actions_queue_move_up)) {
                onMoveBy(entry.entryId, -1)
            })
            if (index < future.lastIndex) add(CatalogSheetAction("queue-move-down", stringResource(R.string.actions_queue_move_down)) {
                onMoveBy(entry.entryId, 1)
            })
        }
    }
    // Equal recordings are still separate occurrences with their own menu visit.
    key(entry.entryId) {
        CatalogActionSheet(
            item = entry.track,
            services = services,
            signedIn = signedIn,
            onSignIn = onSignIn,
            onDismiss = onDismiss,
            onLibraryChanged = onLibraryChanged,
            onViewArtist = onViewArtist,
            onOpenResource = onOpenResource,
            extraActions = actions,
            anchorBounds = anchorBounds,
            backdropLayer = backdropLayer,
            lowPerformanceMode = lowPerformanceMode,
            onMediaKey = onMediaKey,
            modalInputGate = modalInputGate,
            sleepTimerState = sleepTimerState,
            onSleepTimer = onSleepTimer,
            canSleepAtTrackEnd = canSleepAtTrackEnd,
            canSleepAtContextEnd = canSleepAtContextEnd,
            onResourceDeleted = onResourceDeleted,
        )
    }
}
