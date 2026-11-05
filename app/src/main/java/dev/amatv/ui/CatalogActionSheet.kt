package dev.amatv.ui

import android.view.KeyEvent
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.amatv.R
import dev.amatv.integration.CatalogActionState
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.CatalogItemActions
import dev.amatv.integration.CatalogRequestException
import dev.amatv.integration.CreatedLibraryResource
import dev.amatv.integration.MusicServices
import dev.amatv.integration.PersonalRating
import dev.amatv.integration.FavoriteMutationUnconfirmedException
import dev.amatv.integration.LibraryManagementUnconfirmed
import dev.amatv.integration.PendingPlaylistCreation
import dev.amatv.integration.PendingPlaylistAppend
import dev.amatv.integration.appleMusicManagementUrl
import dev.amatv.integration.appleMusicShareUrl
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.integration.libraryResourceKey
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.Track
import dev.amatv.playback.PlaybackSleepTimerState
import dev.amatv.playback.SleepTimerOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalCatalogActionMenu = staticCompositionLocalOf<((Track) -> Unit)?> { null }
internal fun Track.hasCatalogMenu(): Boolean = catalogKind != CatalogKind.Page || resourceType == "library-playlist-folders"

@Composable
internal fun catalogMenuFor(item: Track): ((Track) -> Unit)? = LocalCatalogActionMenu.current?.takeIf { item.hasCatalogMenu() }

internal data class CatalogSheetAction(val id: String, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)
private enum class CatalogSheetPane { Actions, Playlists, Artists, Create, SleepTimer, LibraryManagement }
private enum class CatalogSheetCreation { Playlist, Folder }
private class PlaylistNotEditable : IllegalStateException()
private data class CatalogSheetLibraryAddition(val item: Track, val retryAllowed: Boolean)

/** A native MENU press is one intent; repeats and its release cannot open two sheets. */
@Composable
internal fun Modifier.catalogMenuKey(item: Track): Modifier {
    val open = catalogMenuFor(item) ?: return this
    val active = LocalFocusTargetsEnabled.current
    val held = remember(item.resourceType, item.resourceId, item.id) { booleanArrayOf(false) }
    return onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode != KeyEvent.KEYCODE_MENU) {
            if (native.action == KeyEvent.ACTION_DOWN) held[0] = false
            false
        } else when (native.action) {
            KeyEvent.ACTION_DOWN -> { if (native.repeatCount == 0) held[0] = active; true }
            KeyEvent.ACTION_UP -> {
                val invoke = held[0] && active
                held[0] = false
                if (invoke) open(item)
                true
            }
            else -> true
        }
    }
}

/** Reads begin only when opened; mutations only run from an explicit action click. */
@Composable
internal fun CatalogActionSheet(
    item: Track,
    services: MusicServices,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
    onLibraryChanged: () -> Unit,
    onViewArtist: (Track) -> Unit,
    onOpenResource: (Track) -> Unit,
    extraActions: List<CatalogSheetAction> = emptyList(),
    onQueue: (suspend (playNext: Boolean) -> Unit)? = null,
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
    val identity = "${item.resourceType}:${item.resourceId ?: item.id}"
    key(identity) {
        CatalogActionSheetContent(item, services, signedIn, onSignIn, onDismiss, onLibraryChanged, onViewArtist, onOpenResource,
            extraActions, onQueue, anchorBounds, backdropLayer, lowPerformanceMode, onMediaKey, modalInputGate,
            sleepTimerState, onSleepTimer, canSleepAtTrackEnd, canSleepAtContextEnd, onResourceDeleted)
    }
}

@Composable
private fun CatalogActionSheetContent(
    item: Track, services: MusicServices, signedIn: Boolean,
    onSignIn: () -> Unit, onDismiss: () -> Unit, onLibraryChanged: () -> Unit,
    onViewArtist: (Track) -> Unit, onOpenResource: (Track) -> Unit, extraActions: List<CatalogSheetAction>,
    onQueue: (suspend (Boolean) -> Unit)?,
    anchorBounds: Rect?, backdropLayer: GraphicsLayer?, lowPerformanceMode: Boolean,
    onMediaKey: (Int) -> Boolean,
    modalInputGate: CatalogModalInputGate?,
    sleepTimerState: PlaybackSleepTimerState?, onSleepTimer: ((SleepTimerOption) -> Unit)?, canSleepAtTrackEnd: Boolean,
    canSleepAtContextEnd: Boolean,
    onResourceDeleted: ((Track) -> Unit)?,
) {
    val actions = services.catalogActions
    val available = remember(item) {
        runCatching { actions.availableActions(item) }.getOrDefault(CatalogItemActions(false, false, false, false))
    }
    val folderRef = remember(item) {
        CatalogResourceRef.fromTrack(item)?.takeIf { it.type == "library-playlist-folders" }
    }
    val isFolder = folderRef != null
    val artistRelationship = item.catalogKind in setOf(CatalogKind.Song, CatalogKind.Album, CatalogKind.MusicVideo)
    val scope = rememberCoroutineScope()
    val motion = rememberTvMenuMotion(lowPerformanceMode)
    val motionEnabled = motion.motionEnabled
    var menuLaidOut by remember { mutableStateOf(false) }
    var menuWindowFocused by remember { mutableStateOf(false) }
    var menuHasFocus by remember { mutableStateOf(false) }
    var initialStateResolved by remember { mutableStateOf(false) }
    var initialFolderResolved by remember { mutableStateOf(false) }
    val fallbackInputGate = remember { CatalogModalInputGate() }
    val inputGate = modalInputGate ?: fallbackInputGate
    var pane by remember { mutableStateOf(CatalogSheetPane.Actions) }
    var paneStack by remember { mutableStateOf(listOf(CatalogSheetPane.Actions)) }
    var state by remember { mutableStateOf<CatalogActionState?>(null) }
    val libraryManagementFirst = remember { FocusRequester() }
    val libraryManagementBack = remember { arrayOfNulls<() -> Boolean>(1) }
    val libraryManagementItem = remember(item, state?.libraryResource) {
        val ref = CatalogResourceRef.fromTrack(item)?.takeIf {
            it.type in setOf("library-playlists", "library-playlist-folders", "library-songs")
        } ?: state?.libraryResource?.takeIf { it.type in setOf("library-playlists", "library-songs") }
        val catalogSongId = (if (item.resourceType?.startsWith("library-") == true) item.catalogId
            else item.resourceId ?: item.catalogId ?: item.id.removePrefix("apple:"))
            ?.takeIf { it.matches(Regex("[1-9][0-9]*")) }
        ref?.takeIf {
            if (it.type == "library-songs") item.catalogKind == CatalogKind.Song && it.id.startsWith("i.") && catalogSongId != null
            else it.id.startsWith("p.") && it.id != "p.playlistsroot"
        }?.let {
            item.copy(id = "apple:${it.id}", resourceId = it.id, resourceType = it.type,
                catalogId = if (it.type == "library-songs") catalogSongId else item.catalogId)
        }
    }
    var stateVersion by remember { mutableIntStateOf(0) }
    val stateGeneration = remember { longArrayOf(0L) }
    val folderGeneration = remember { longArrayOf(0L) }
    val stateJob = remember { arrayOfNulls<Job>(1) }
    val folderJob = remember { arrayOfNulls<Job>(1) }
    val relatedGeneration = remember { longArrayOf(0L) }
    val relatedJob = remember { arrayOfNulls<Job>(1) }
    var relatedReady by remember { mutableStateOf(false) }
    var relatedFailed by remember { mutableStateOf(false) }
    var relatedLoading by remember { mutableStateOf(false) }
    var relatedNext by remember { mutableStateOf<String?>(null) }
    var stateLoading by remember { mutableStateOf(false) }
    var stateFailed by remember { mutableStateOf(false) }
    var resourceUnavailable by remember { mutableStateOf(false) }
    var folderLoading by remember { mutableStateOf(false) }
    var folderFailed by remember { mutableStateOf(false) }
    var folderPlaylistForbidden by remember { mutableStateOf(false) }
    var folderCreationForbidden by remember { mutableStateOf(false) }
    var creationKind by remember { mutableStateOf(CatalogSheetCreation.Playlist) }
    var focusedMainRow by remember { mutableStateOf<String?>(null) }
    var focusedMainIndex by remember { mutableIntStateOf(0) }
    val mainRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var authNeeded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val readActionJob = remember { arrayOfNulls<Job>(1) }
    var error by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var libraryRequested by remember { mutableStateOf(false) }
    var favoritePendingTarget by remember { mutableStateOf<Boolean?>(null) }
    var addLibraryAfterFavorite by remember { mutableStateOf(false) }
    var addLibraryAfterCreation by remember { mutableStateOf(false) }
    var addLibraryAfterAppends by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingAdditionalLibrary by remember { mutableStateOf<Map<String, CatalogSheetLibraryAddition>>(emptyMap()) }
    // Retain the request and append snapshot so Refresh can confirm a POST
    // with reads, without resubmitting duplicate playlists or occurrences.
    var pendingCreation by remember { mutableStateOf<PendingPlaylistCreation?>(null) }
    var pendingAppends by remember { mutableStateOf<Map<String, PendingPlaylistAppend>>(emptyMap()) }
    val creationUnconfirmed = pendingCreation != null
    val unconfirmedAppendTargets = pendingAppends.keys
    var recoveringMutations by remember { mutableStateOf(false) }
    var managementVisible by remember { mutableStateOf(false) }
    var shareVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shareUrl = remember(item.url) { appleMusicShareUrl(item.url) }
    var audioEffectsUnavailable by remember { mutableStateOf(false) }
    val audioEffectsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val effectsPlayback = services.playbackSnapshot()
    val audioEffectsAvailable = remember(item, effectsPlayback?.requestGeneration, effectsPlayback?.currentTrack,
        effectsPlayback?.isPrepared, effectsPlayback?.hasPlayerCurrentTrack, effectsPlayback?.isEnded, effectsPlayback?.errorMessage) {
        services.audioEffectsIntent(item) != null
    }
    val managementUrl = remember(item.url) { appleMusicManagementUrl(item.url) }
    var createdResource by remember { mutableStateOf<CreatedLibraryResource?>(null) }
    var uneditablePlaylists by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlists by remember { mutableStateOf<List<Track>>(emptyList()) }
    var artists by remember { mutableStateOf<List<Track>>(emptyList()) }
    var nextPage by remember { mutableStateOf<String?>(null) }
    var pageLoading by remember { mutableStateOf(false) }
    var pageFailed by remember { mutableStateOf(false) }
    val pageJob = remember { arrayOfNulls<Job>(1) }
    val pageGeneration = remember { longArrayOf(0L) }
    val visitedPages = remember { mutableMapOf<CatalogSheetPane, MutableSet<String>>() }
    var playlistName by remember { mutableStateOf("") }
    var playlistDescription by remember { mutableStateOf("") }
    val headerFocus = remember { FocusRequester() }
    val playlistCreateFocus = remember { FocusRequester() }
    val focusVisit = remember(pane) { CatalogMenuFocusVisit() }
    val nameFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val createFocus = remember { FocusRequester() }
    fun canActivateMenu() = menuLaidOut && menuWindowFocused && menuHasFocus && motion.entranceComplete &&
        !busy && !motion.closing && !motion.transitioning && !managementVisible && !shareVisible
    val authorized = signedIn && !authNeeded
    // Editing the folder itself and creating its children are different rights.
    // Only an unavailable folder or a rejected create POST rules out creation.
    val canCreateInFolder = !isFolder || !resourceUnavailable
    val canCreatePlaylist = canCreateInFolder && (!isFolder || !folderPlaylistForbidden) &&
        (item.catalogKind != CatalogKind.Page || isFolder)
    val canCreateFolder = isFolder && canCreateInFolder && !folderCreationForbidden
    val canCreateCurrent = if (creationKind == CatalogSheetCreation.Folder) canCreateFolder else canCreatePlaylist
    val canAppendSource = available.canAppendToPlaylist || item.catalogKind in setOf(CatalogKind.Album, CatalogKind.Playlist)
    val listState = rememberLazyListState()
    val targetRequesters = remember(pane) { mutableMapOf<String, FocusRequester>() }
    var pendingPageEntry by remember(pane) { mutableIntStateOf(-1) }
    val targets = if (pane == CatalogSheetPane.Playlists) playlists.filterNot {
        "${it.resourceType}:${it.resourceId ?: it.id}" in uneditablePlaylists
    } else artists

    suspend fun sourceTracks(): List<Track> {
        val tracks = if (item.catalogKind in setOf(CatalogKind.Album, CatalogKind.Playlist)) services.collectionTracks(item) else listOf(item)
        check(tracks.isNotEmpty())
        return tracks
    }

    suspend fun addOptionalLibraryItems(items: List<Track>, currentState: CatalogActionState? = state): CatalogActionState? {
        val sourceKey = libraryResourceKey(item)
        val fresh = items.filter { track ->
            val key = libraryResourceKey(track)
            key !in pendingAdditionalLibrary && !(key == sourceKey && currentState?.inLibrary == true)
        }
        val wasBusy = busy
        busy = true
        try {
            val result = actions.addEligibleItemsToLibraryAndConfirm(fresh)
            pendingAdditionalLibrary = pendingAdditionalLibrary + result.pendingItems.associate { track ->
                libraryResourceKey(track) to CatalogSheetLibraryAddition(track, result.retryAllowed)
            }
            return if (result.attemptedItems.any { libraryResourceKey(it) == sourceKey } &&
                result.pendingItems.none { libraryResourceKey(it) == sourceKey }) {
                (currentState ?: CatalogActionState(null, null, null)).copy(inLibrary = true)
            } else currentState
        } finally {
            busy = wasBusy
        }
    }

    fun dismiss(afterDismiss: (() -> Unit)? = null) {
        // Preserve an in-flight write, but freeze reads at the Back intent.
        // The shared visual owner marks motion.closing before this domain cleanup.
        if (busy || motion.closing) return
        motion.dismiss(beforeExit = {
            stateGeneration[0]++
            folderGeneration[0]++
            relatedGeneration[0]++
            pageGeneration[0]++
            stateJob[0]?.cancel()
            folderJob[0]?.cancel()
            relatedJob[0]?.cancel()
            pageJob[0]?.cancel()
        }, onClosed = {
            onDismiss()
            afterDismiss?.invoke()
        })
    }
    fun cancelRelatedRead() {
        relatedGeneration[0]++
        relatedJob[0]?.cancel()
        relatedJob[0] = null
        relatedLoading = false
    }
    fun navigate(target: CatalogSheetPane, replaceTransition: Boolean = false) {
        if (motion.closing || (motion.transitioning && !replaceTransition) ||
            (target == pane && !motion.transitioning)) return
        val goingBack = target == CatalogSheetPane.Actions || paneStack.getOrNull(paneStack.lastIndex - 1) == target
        motion.navigate(goingBack, replaceTransition, changeRequired = pane != target,
            beforeTransition = {
                // The target owns its reads/history from this synchronous intent.
                if (target == CatalogSheetPane.Artists) cancelRelatedRead()
                error = null
                paneStack = when {
                    target == CatalogSheetPane.Actions -> listOf(target)
                    paneStack.getOrNull(paneStack.lastIndex - 1) == target -> paneStack.dropLast(1)
                    else -> paneStack + target
                }
            }, onSwap = { pane = target })
    }
    fun back() {
        // Queue preparation and album lookup are cancellable reads. Account
        // mutations retain their existing single-write protection.
        readActionJob[0]?.let {
            readActionJob[0] = null
            it.cancel()
            busy = false
        }
        if (busy || motion.closing) return
        if (pane == CatalogSheetPane.LibraryManagement && libraryManagementBack[0]?.invoke() == true) return
        if (paneStack.size == 1) dismiss()
        else navigate(paneStack[paneStack.lastIndex - 1], replaceTransition = true)
    }
    fun openCreate(kind: CatalogSheetCreation) {
        if (creationKind != kind) { playlistName = ""; playlistDescription = "" }
        creationKind = kind
        navigate(CatalogSheetPane.Create)
    }
    fun loadPage(append: Boolean) {
        if (motion.closing || pageLoading || (append && nextPage == null)) return
        val target = pane
        if (target != CatalogSheetPane.Playlists && target != CatalogSheetPane.Artists) return
        val cursor = if (append) nextPage else null
        val generation = ++pageGeneration[0]
        pageJob[0]?.cancel()
        pageLoading = true
        pageFailed = false
        if (!append) {
            if (target == CatalogSheetPane.Playlists) playlists = emptyList() else artists = emptyList()
            visitedPages.remove(target)
            nextPage = null
        }
        pageJob[0] = scope.launch {
            try {
                val page = if (target == CatalogSheetPane.Playlists) services.libraryPlaylistsPage(cursor)
                    else services.relatedArtistsPage(item, cursor)
                if (generation != pageGeneration[0] || pane != target) return@launch
                val seen = visitedPages.getOrPut(target) { mutableSetOf() }
                check(page.requestedPage !in seen && (page.nextPage == null || page.nextPage !in seen))
                seen.add(page.requestedPage)
                if (target == CatalogSheetPane.Playlists) playlists = if (append) mergeLibraryPage(playlists, page.items) else page.items
                else { artists = if (append) mergeLibraryPage(artists, page.items) else page.items; relatedNext = page.nextPage }
                nextPage = page.nextPage
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == pageGeneration[0]) pageFailed = true }
            finally { if (generation == pageGeneration[0]) pageLoading = false }
        }
    }
    fun mutate(success: Int, folderCreation: CatalogSheetCreation? = null,
        successNotice: (() -> Int)? = null, operation: suspend () -> Unit) {
        if (!canActivateMenu() || busy || !authorized) return
        if (!initialStateResolved || stateLoading || (isFolder && (!initialFolderResolved || folderLoading))) return
        busy = true
        stateGeneration[0]++
        folderGeneration[0]++
        stateLoading = false
        error = null
        notice = null
        scope.launch {
            try {
                // Deliberately no retry wrapper: create/append timeouts may have
                // reached Apple. Their submit flags also prevent a second POST
                // from the same menu visit after an uncertain result.
                operation()
                notice = successNotice?.invoke() ?: success
                onLibraryChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val requestFailure = failure as? CatalogRequestException
                if (isFolder && folderCreation != null && requestFailure?.httpStatus == 403) {
                    // A forbidden result is definitive, unlike an uncertain
                    // timeout. Hide the rejected operation for this menu visit.
                    if (folderCreation == CatalogSheetCreation.Folder) folderCreationForbidden = true
                    else folderPlaylistForbidden = true
                    navigate(CatalogSheetPane.Actions)
                    error = R.string.actions_folder_read_only
                } else {
                    error = when (failure) {
                        is PlaylistNotEditable -> R.string.actions_playlist_read_only
                        is FavoriteMutationUnconfirmedException, is LibraryManagementUnconfirmed -> R.string.actions_favorite_unconfirmed
                        else -> R.string.actions_operation_failed
                    }
                    if (failure is PlaylistNotEditable) {
                        withFrameNanos { }
                        runCatching { headerFocus.requestFocus() }
                    }
                    if (requestFailure?.reason == CatalogFailureReason.Authorization) {
                        authNeeded = true
                        navigate(CatalogSheetPane.Actions)
                    }
                }
            } finally { busy = false }
        }
    }
    fun enqueue(playNext: Boolean) {
        val operation = onQueue ?: return
        if (!canActivateMenu() || busy || motion.closing || motion.transitioning) return
        busy = true
        error = null
        notice = null
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                operation(playNext)
                busy = false
                dismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.actions_queue_failed }
            finally {
                if (readActionJob[0] === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    readActionJob[0] = null
                    busy = false
                }
            }
        }
        readActionJob[0] = job
        job.start()
    }
    fun showAlbum() {
        if (!canActivateMenu()) return
        busy = true
        error = null
        notice = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val album = services.catalogPages.albumForSong(item)
                if (readActionJob[0] !== currentCoroutineContext()[Job] || motion.closing) return@launch
                busy = false
                if (album != null) dismiss { onOpenResource(album) }
                else error = R.string.actions_album_unavailable
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (readActionJob[0] === currentCoroutineContext()[Job] && !motion.closing) {
                    error = R.string.actions_album_unavailable
                    if ((failure as? CatalogRequestException)?.reason == CatalogFailureReason.Authorization) authNeeded = true
                }
            } finally {
                if (readActionJob[0] === currentCoroutineContext()[Job]) {
                    readActionJob[0] = null
                    busy = false
                }
            }
        }
        readActionJob[0] = job
        job.start()
    }
    fun refreshActionReads() {
        // Claim and cancel the previous read at intent time, before the effect
        // for the new version can start on a later frame.
        stateGeneration[0]++
        stateJob[0]?.cancel()
        folderGeneration[0]++
        recoveringMutations = signedIn && (pendingCreation != null || pendingAppends.isNotEmpty() ||
            pendingAdditionalLibrary.isNotEmpty() || libraryRequested || favoritePendingTarget != null || addLibraryAfterFavorite)
        stateLoading = signedIn && (recoveringMutations || item.catalogKind != CatalogKind.Page &&
            (available.canAddToLibrary || available.canFavorite || available.canRate))
        if (signedIn && folderRef != null) folderLoading = true
        stateVersion++
    }
    LaunchedEffect(signedIn, stateVersion) {
        if (motion.closing) return@LaunchedEffect
        val generation = ++stateGeneration[0]
        stateJob[0] = currentCoroutineContext()[Job]
        // One read owner loads the general snapshot first, then applies the
        // pending confirmations. Publish once so a slower rating/snapshot read
        // cannot overwrite a newer, confirmed favorite or Library result.
        val readSnapshot = signedIn && item.catalogKind != CatalogKind.Page &&
            (available.canAddToLibrary || available.canFavorite || available.canRate)
        val recoverPending = signedIn && stateVersion > 0 && (pendingCreation != null || pendingAppends.isNotEmpty() ||
            pendingAdditionalLibrary.isNotEmpty() || libraryRequested || favoritePendingTarget != null || addLibraryAfterFavorite)
        stateLoading = readSnapshot || recoverPending
        recoveringMutations = recoverPending
        var refreshedState = state
        var confirmedNotice: Int? = null
        var readFailed = false
        try {
            if (readSnapshot) {
                stateFailed = false
                try {
                    refreshedState = actions.readState(item)
                    if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
                    authNeeded = false
                    resourceUnavailable = false
                    if (libraryRequested && refreshedState?.inLibrary == true) confirmedNotice = R.string.actions_library_added
                    if (favoritePendingTarget != null && refreshedState?.inFavorites == favoritePendingTarget)
                        confirmedNotice = if (refreshedState?.inFavorites == true) R.string.actions_favorite_saved else R.string.library_management_favorite_removed
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
                    stateFailed = true
                    if ((failure as? CatalogRequestException)?.reason == CatalogFailureReason.Unavailable) resourceUnavailable = true
                    if ((failure as? CatalogRequestException)?.reason == CatalogFailureReason.Authorization) authNeeded = true
                }
            }
            if (recoverPending) {
                val additionalAtStart = pendingAdditionalLibrary
                pendingCreation?.let { pending ->
                    val confirmed = try { actions.confirmPlaylistCreation(pending) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { readFailed = true; null }
                    if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
                    confirmed?.let { resource ->
                        if (pendingCreation === pending) {
                            createdResource = resource
                            pendingCreation = null
                            confirmedNotice = if (pending.folder) R.string.actions_folder_created else R.string.actions_playlist_created
                            if (addLibraryAfterCreation) {
                                addLibraryAfterCreation = false
                                refreshedState = addOptionalLibraryItems(pending.tracks, refreshedState)
                            }
                        }
                    }
                }
                for ((key, pending) in pendingAppends.toMap()) {
                    val confirmed = try { actions.confirmPlaylistAppend(pending) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { readFailed = true; false }
                    if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
                    if (confirmed && pendingAppends[key] === pending) {
                        pendingAppends = pendingAppends - key
                        confirmedNotice = R.string.actions_playlist_added
                        if (key in addLibraryAfterAppends) {
                            addLibraryAfterAppends = addLibraryAfterAppends - key
                            refreshedState = addOptionalLibraryItems(pending.tracks, refreshedState)
                        }
                    }
                }
                val inLibrary = if (libraryRequested && refreshedState?.inLibrary != true) try { actions.libraryState(item) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { readFailed = true; null }
                    else null
                if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
                inLibrary?.let { confirmed ->
                    refreshedState = (refreshedState ?: CatalogActionState(null, null, null)).copy(inLibrary = confirmed)
                    if (confirmed) confirmedNotice = R.string.actions_library_added
                }
                val favoriteTarget = favoritePendingTarget
                val inFavorites = if (favoriteTarget != null) try {
                    if (favoriteTarget) actions.favoriteState(item) else services.libraryManagement.favoriteState(item)
                }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { readFailed = true; null }
                    else null
                if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
                inFavorites?.let { confirmed ->
                    refreshedState = (refreshedState ?: CatalogActionState(null, null, null)).copy(inFavorites = confirmed)
                    if (confirmed == favoriteTarget) {
                        favoritePendingTarget = null
                        confirmedNotice = if (confirmed) R.string.actions_favorite_saved else R.string.library_management_favorite_removed
                    }
                }
                if (refreshedState?.inFavorites == true && addLibraryAfterFavorite) {
                    addLibraryAfterFavorite = false
                    refreshedState = addOptionalLibraryItems(listOf(item), refreshedState)
                }
                if (additionalAtStart.isNotEmpty()) {
                    val pending = actions.libraryItemsAwaitingConfirmation(additionalAtStart.values.map { it.item })
                        .mapTo(HashSet(), ::libraryResourceKey)
                    if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
                    val confirmedKeys = additionalAtStart.keys - pending
                    pendingAdditionalLibrary = pendingAdditionalLibrary - confirmedKeys
                    if (libraryResourceKey(item) in confirmedKeys)
                        refreshedState = (refreshedState ?: CatalogActionState(null, null, null)).copy(inLibrary = true)
                    if (confirmedKeys.isNotEmpty()) confirmedNotice = R.string.actions_library_added
                }
            }
            if (generation != stateGeneration[0] || motion.closing) return@LaunchedEffect
            state = refreshedState
            confirmedNotice?.let {
                notice = it
                if (recoverPending) { error = null; onLibraryChanged() }
            }
            if (readFailed) error = R.string.actions_operation_failed
        } finally {
            if (generation == stateGeneration[0]) {
                stateLoading = false
                recoveringMutations = false
                initialStateResolved = true
                stateJob[0] = null
            }
        }
    }
    LaunchedEffect(signedIn, stateVersion, folderRef) {
        if (motion.closing) return@LaunchedEffect
        if (!signedIn || folderRef == null) { initialFolderResolved = true; return@LaunchedEffect }
        val generation = ++folderGeneration[0]
        folderJob[0] = currentCoroutineContext()[Job]
        folderLoading = true
        folderFailed = false
        try {
            services.catalogPages.profile(folderRef)
            if (generation != folderGeneration[0]) return@LaunchedEffect
            resourceUnavailable = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val requestFailure = failure as? CatalogRequestException
            if (generation == folderGeneration[0]) {
                when {
                    // Reading folder metadata and creating its children can have different grants.
                    requestFailure?.httpStatus == 403 -> Unit
                    requestFailure?.reason == CatalogFailureReason.Unavailable -> resourceUnavailable = true
                    requestFailure?.reason == CatalogFailureReason.Authorization -> authNeeded = true
                    else -> folderFailed = true
                }
            }
        } finally {
            if (generation == folderGeneration[0]) {
                folderLoading = false
                initialFolderResolved = true
                folderJob[0] = null
            }
        }
    }
    LaunchedEffect(pane, creationKind, canCreateCurrent, authorized, busy, motion.transitioning) {
        if (pane == CatalogSheetPane.Create && (!canCreateCurrent || !authorized) && !busy && !motion.transitioning) navigate(CatalogSheetPane.Actions)
    }
    LaunchedEffect(pane, onSleepTimer) {
        if (pane == CatalogSheetPane.SleepTimer && onSleepTimer == null) navigate(CatalogSheetPane.Actions, replaceTransition = true)
    }
    LaunchedEffect(pane, authorized, libraryManagementItem, busy) {
        if (pane == CatalogSheetPane.LibraryManagement && !busy && (!authorized || libraryManagementItem == null))
            navigate(CatalogSheetPane.Actions, replaceTransition = true)
    }
    LaunchedEffect(signedIn, stateVersion) {
        if (motion.closing) return@LaunchedEffect
        if (!artistRelationship || (!signedIn && item.resourceType?.startsWith("library-") == true) ||
            paneStack.lastOrNull() == CatalogSheetPane.Artists) {
            return@LaunchedEffect
        }
        val generation = ++relatedGeneration[0]
        relatedJob[0] = kotlinx.coroutines.currentCoroutineContext()[Job]
        relatedLoading = true
        relatedFailed = false
        try {
            val page = services.relatedArtistsPage(item)
            if (generation != relatedGeneration[0]) return@LaunchedEffect
            artists = page.items
            visitedPages[CatalogSheetPane.Artists] = mutableSetOf(page.requestedPage)
            relatedNext = page.nextPage
            relatedReady = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (generation == relatedGeneration[0]) relatedFailed = true }
        finally {
            if (generation == relatedGeneration[0]) {
                relatedLoading = false
                relatedJob[0] = null
            }
        }
    }
    DisposableEffect(pane) {
        onDispose { pageGeneration[0]++; pageJob[0]?.cancel(); pageLoading = false }
    }
    LaunchedEffect(pane, motion.closing) {
        if (motion.closing) return@LaunchedEffect
        listState.scrollToItem(0)
        if (pane == CatalogSheetPane.Playlists) loadPage(false)
        else if (pane == CatalogSheetPane.Artists && relatedReady) { nextPage = relatedNext; pageFailed = false }
        else if (pane == CatalogSheetPane.Artists) loadPage(false)
    }

    val stateReady = initialStateResolved && !stateLoading
    val folderReady = !isFolder || (initialFolderResolved && !folderLoading)
    val mainRows = buildList<CatalogSheetAction> {
        if (!authorized) add(CatalogSheetAction("signin", stringResource(R.string.actions_sign_in)) { dismiss(onSignIn) })
        createdResource?.let { resource ->
            add(CatalogSheetAction("open-created", stringResource(R.string.actions_open_created)) {
                dismiss {
                    onOpenResource(Track(id = "apple:${resource.id}", title = resource.name, artist = "", album = "",
                        durationMs = 0L, palette = item.palette, lyrics = emptyList(), resourceId = resource.id,
                        resourceType = resource.type,
                        catalogKind = if (resource.type == "library-playlists") CatalogKind.Playlist else CatalogKind.Page))
                }
            })
        }
        pendingCreation?.takeIf { it.created == null }?.let { pending ->
            val label = stringResource(R.string.actions_check_library)
            add(CatalogSheetAction("check-library", label) {
                dismiss {
                    onOpenResource(Track(id = "apple:${pending.parentFolderId}", title = label, artist = "", album = "",
                        durationMs = 0L, palette = item.palette, lyrics = emptyList(), resourceId = pending.parentFolderId,
                        resourceType = "library-playlist-folders", catalogKind = CatalogKind.Page))
                }
            })
        }
        pendingAppends.forEach { (key, pending) ->
            val label = stringResource(R.string.actions_check_playlist, pending.playlist.title)
            add(CatalogSheetAction("check-playlist-$key", label) { dismiss { onOpenResource(pending.playlist) } })
        }
        extraActions.forEach { action -> add(CatalogSheetAction(action.id, action.label, action.enabled) { dismiss(action.onClick) }) }
        if (onQueue != null) {
            add(CatalogSheetAction("play-next", stringResource(R.string.actions_play_next)) { enqueue(true) })
            add(CatalogSheetAction("play-last", stringResource(R.string.actions_play_last)) { enqueue(false) })
        }
        if (authorized && !resourceUnavailable) {
            if (available.canAddToLibrary) add(CatalogSheetAction("library", stringResource(when {
                state?.inLibrary == true -> R.string.actions_library_added
                libraryRequested -> R.string.actions_library_requested
                else -> R.string.actions_add_library
            }), enabled = stateReady && !libraryRequested && state?.inLibrary != true) {
                mutate(R.string.actions_library_requested, successNotice = {
                    if (state?.inLibrary == true) R.string.actions_library_added else R.string.actions_library_requested
                }) {
                    libraryRequested = true
                    try {
                        actions.addToLibrary(listOf(item))
                    } catch (failure: CatalogRequestException) {
                        if (failure.httpStatus in 400..499) libraryRequested = false
                        throw failure
                    }
                    val inLibrary = try { actions.libraryState(item) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    state = (state ?: CatalogActionState(null, null, null)).copy(inLibrary = inLibrary)
                }
            })
            if (available.canFavorite) {
                val removing = state?.inFavorites == true && services.libraryManagement.capabilities.removeFavorites
                add(CatalogSheetAction("favorite", stringResource(when {
                    favoritePendingTarget != null -> R.string.actions_favorite_requested
                    removing -> R.string.library_management_remove_favorite
                    state?.inFavorites == true -> R.string.actions_favorited
                    else -> R.string.actions_favorite
                }), enabled = stateReady && favoritePendingTarget == null && (state?.inFavorites != true || removing)) {
                    if (removing) mutate(R.string.library_management_favorite_removed, successNotice = {
                        if (state?.inFavorites == false) R.string.library_management_favorite_removed else R.string.library_management_pending
                    }) {
                        favoritePendingTarget = false
                        addLibraryAfterFavorite = false
                        try {
                            if (services.libraryManagement.removeFavorite(item)) {
                                state = (state ?: CatalogActionState(null, null, null)).copy(inFavorites = false)
                                favoritePendingTarget = null
                            }
                        } catch (failure: CatalogRequestException) {
                            if (failure.httpStatus in 400..499) favoritePendingTarget = null
                            throw failure
                        }
                    } else mutate(R.string.actions_favorite_requested, successNotice = {
                        if (state?.inFavorites == true) R.string.actions_favorite_saved else R.string.actions_favorite_requested
                    }) {
                        favoritePendingTarget = true
                        addLibraryAfterFavorite = services.libraryAdditionPreferences.settings.value.addFavoriteItems
                        try {
                            val result = actions.addToFavoritesAndConfirm(item)
                            state = (state ?: CatalogActionState(null, null, null)).copy(inFavorites = result.confirmedState)
                            if (result.confirmed) {
                                favoritePendingTarget = null
                                if (addLibraryAfterFavorite) {
                                    addLibraryAfterFavorite = false
                                    state = addOptionalLibraryItems(listOf(item))
                                }
                            }
                        } catch (failure: CatalogRequestException) {
                            if (failure.httpStatus in 400..499) { favoritePendingTarget = null; addLibraryAfterFavorite = false }
                            throw failure
                        }
                    }
                })
            }
            if (available.canRate) {
                add(CatalogSheetAction("like", stringResource(R.string.actions_like), enabled = stateReady) {
                    mutate(R.string.actions_rating_saved) { actions.setRating(item, PersonalRating.Like); state = (state ?: CatalogActionState(null, null, null)).copy(rating = PersonalRating.Like) }
                })
                add(CatalogSheetAction("dislike", stringResource(R.string.actions_dislike), enabled = stateReady) {
                    mutate(R.string.actions_rating_saved) { actions.setRating(item, PersonalRating.Dislike); state = (state ?: CatalogActionState(null, null, null)).copy(rating = PersonalRating.Dislike) }
                })
                if (state?.rating != null) add(CatalogSheetAction("clear-rating", stringResource(R.string.actions_clear_rating), enabled = stateReady) {
                    mutate(R.string.actions_rating_cleared) { actions.deleteRating(item); state = state?.copy(rating = null) }
                })
            }
            if (canAppendSource) add(CatalogSheetAction("playlist", stringResource(R.string.actions_add_playlist)) { navigate(CatalogSheetPane.Playlists) })
            if (canCreatePlaylist) add(CatalogSheetAction("create", stringResource(R.string.actions_create_playlist), enabled = folderReady && !creationUnconfirmed) { openCreate(CatalogSheetCreation.Playlist) })
            if (canCreateFolder) add(CatalogSheetAction("create-folder", stringResource(R.string.actions_create_folder), enabled = folderReady && !creationUnconfirmed) { openCreate(CatalogSheetCreation.Folder) })
        }
        if (authorized && pendingAdditionalLibrary.values.any { it.retryAllowed })
            add(CatalogSheetAction("retry-library", stringResource(R.string.actions_retry_library_addition), enabled = stateReady && !recoveringMutations) {
                mutate(R.string.actions_library_added, successNotice = {
                    if (pendingAdditionalLibrary.isEmpty()) R.string.actions_library_added else R.string.actions_library_addition_pending
                }) {
                    val retry = pendingAdditionalLibrary.filterValues { it.retryAllowed }
                    pendingAdditionalLibrary = pendingAdditionalLibrary - retry.keys
                    state = addOptionalLibraryItems(retry.values.map { it.item })
                }
            })
        libraryManagementItem?.let { managed ->
            val caps = services.libraryManagement.capabilities
            val folder = managed.resourceType == "library-playlist-folders"
            val song = managed.resourceType == "library-songs"
            val editable = state?.canEditPlaylist != false
            val supported = if (song) caps.removeLibrarySongs
                else if (folder) caps.updateFolderMetadata || caps.deleteFolder || caps.movePlaylistFolders
                else editable && (caps.updatePlaylistMetadata || caps.replaceOrReorderPlaylistTracks || caps.moveLibraryResources) || caps.deletePlaylist
            if (authorized && !resourceUnavailable && supported)
                add(CatalogSheetAction("library-management", stringResource(if (song) R.string.library_management_remove_song
                    else if (folder) R.string.library_management_edit_folder else R.string.library_management_edit_playlist),
                    enabled = stateReady && folderReady) { navigate(CatalogSheetPane.LibraryManagement) })
        }
        val canManageOnPhone = available.canFavorite || item.catalogKind == CatalogKind.Playlist
        if (managementUrl != null && canManageOnPhone) add(CatalogSheetAction("manage-in-music",
            stringResource(R.string.apple_music_manage_action)) { managementVisible = true })
        if (shareUrl != null) {
            add(CatalogSheetAction("share", stringResource(R.string.actions_share)) {
                if (!shareAppleMusicLink(context, shareUrl, item.title)) shareVisible = true
            })
            add(CatalogSheetAction("copy-link", stringResource(R.string.actions_copy_link)) {
                if (copyAppleMusicLink(context, shareUrl)) dismiss() else shareVisible = true
            })
        }
        if (!audioEffectsUnavailable && audioEffectsAvailable)
            add(CatalogSheetAction("audio-effects", stringResource(R.string.actions_audio_effects)) {
                val intent = services.audioEffectsIntent(item)
                if (intent == null) audioEffectsUnavailable = true
                else try { audioEffectsLauncher.launch(intent) }
                catch (_: ActivityNotFoundException) { audioEffectsUnavailable = true }
                catch (_: SecurityException) { audioEffectsUnavailable = true }
            })
        if (onSleepTimer != null) add(CatalogSheetAction("sleep-timer", stringResource(R.string.actions_sleep_timer)) {
            navigate(CatalogSheetPane.SleepTimer)
        })
        if (item.catalogKind == CatalogKind.Song && (authorized || item.resourceType?.startsWith("library-") != true))
            add(CatalogSheetAction("album", stringResource(R.string.actions_show_album), onClick = ::showAlbum))
        if (item.catalogKind == CatalogKind.Artist) add(CatalogSheetAction("artist", stringResource(R.string.actions_view_artist)) { dismiss { onViewArtist(item) } })
        else if (artistRelationship && relatedReady && (artists.isNotEmpty() || relatedNext != null)) add(CatalogSheetAction("artist", stringResource(R.string.actions_view_artist)) { navigate(CatalogSheetPane.Artists) })
        if (stateFailed || relatedFailed || folderFailed || error != null || creationUnconfirmed || unconfirmedAppendTargets.isNotEmpty() ||
            pendingAdditionalLibrary.isNotEmpty() || addLibraryAfterFavorite ||
            (libraryRequested && state?.inLibrary != true) ||
            favoritePendingTarget != null) add(CatalogSheetAction("refresh", stringResource(R.string.actions_refresh), enabled = !recoveringMutations) {
            if (error != null || creationUnconfirmed || unconfirmedAppendTargets.isNotEmpty() || pendingAdditionalLibrary.isNotEmpty()) onLibraryChanged()
            error = null
            refreshActionReads()
        })
    }.sortedBy { catalogActionGroup(it.id) }
    val mainRowIds = mainRows.map { it.id }
    fun canActivateFocusedAction(): Boolean = canActivateMenu() &&
        (pane != CatalogSheetPane.Actions || mainRows.firstOrNull { it.id == focusedMainRow }?.enabled == true || mainRows.isEmpty())

    LaunchedEffect(mainRowIds, pane, busy, motion.closing, motion.transitioning, motion.entranceComplete) {
        if (pane != CatalogSheetPane.Actions || busy || motion.closing || motion.transitioning || !motion.entranceComplete) return@LaunchedEffect
        val replacement = focusVisit.replacementIndex(mainRowIds, focusedMainRow, focusedMainIndex)
        if (replacement != null) {
            listState.scrollToItem(replacement)
            withFrameNanos { }
            val target = mainRequesters[mainRows[replacement].id]
            target?.let { runCatching { it.requestFocus() } }
        } else if (focusedMainRow != null && mainRows.isEmpty()) runCatching { headerFocus.requestFocus() }
    }

    LaunchedEffect(targets.size, pageLoading, pageFailed, nextPage, pendingPageEntry, motion.closing) {
        if (motion.closing) return@LaunchedEffect
        if (pageFailed) { pendingPageEntry = -1; return@LaunchedEffect }
        if (pendingPageEntry < 0 || pageLoading) return@LaunchedEffect
        if (targets.size <= pendingPageEntry && nextPage != null) return@LaunchedEffect
        val index = pendingPageEntry.coerceAtMost(targets.lastIndex)
        if (index >= 0) {
            listState.scrollToItem(index + if (pane == CatalogSheetPane.Playlists) 1 else 0)
            val target = targets[index]
            val key = "${target.resourceType}:${target.resourceId ?: target.id}"
            withFrameNanos { }
            targetRequesters[key]?.let { runCatching { it.requestFocus() } }
        } else runCatching { headerFocus.requestFocus() }
        pendingPageEntry = -1
    }

    Dialog(onDismissRequest = { inputGate.dialogBackDismissed(); back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false,
            windowTitle = "AMATV.ContextMenu")) {
        TvMenuWindowSetup()
        TvBrowseViewport {
            val density = LocalDensity.current
            val metrics = tvMenuMetrics()
            var viewportSize by remember { mutableStateOf(IntSize.Zero) }
            var menuSize by remember { mutableStateOf(IntSize.Zero) }
            val placement = remember(anchorBounds, menuSize, viewportSize, density) {
                with(density) {
                    catalogMenuPlacement(anchorBounds, menuSize, viewportSize, BrowseTokens.SafeHorizontal.toPx(), BrowseTokens.SafeTop.toPx(), CatalogMenuStyle.Gap.toPx())
                }
            }
            val material = tvMenuMaterialAvailable(backdropLayer, lowPerformanceMode)
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            SideEffect { menuWindowFocused = windowFocused }
            val active = menuLaidOut && windowFocused && motion.entranceComplete &&
                !motion.closing && !motion.transitioning && !managementVisible && !shareVisible
            val artistEntryIds = targets.map { "${it.resourceType}:${it.resourceId ?: it.id}" }
            val sleepOptions = SleepTimerOption.entries
            val lastEnabledSleepOption = sleepOptions.last { option ->
                (option != SleepTimerOption.EndOfTrack || canSleepAtTrackEnd) &&
                    (option != SleepTimerOption.EndOfContext || canSleepAtContextEnd)
            }
            val entryIds = when (pane) {
                CatalogSheetPane.Actions -> mainRowIds.ifEmpty { listOf("close") }
                CatalogSheetPane.Playlists -> listOf("create") + artistEntryIds
                CatalogSheetPane.Artists -> artistEntryIds.ifEmpty { if (pageLoading) emptyList() else listOf("back") }
                CatalogSheetPane.Create -> listOf("name")
                CatalogSheetPane.SleepTimer -> sleepOptions.map { "sleep-${it.name}" }
                CatalogSheetPane.LibraryManagement -> listOf("library-management-first")
            }
            LaunchedEffect(pane, active, entryIds) {
                val resuming = focusVisit.resumed(active)
                if (!active) return@LaunchedEffect
                val initialId = focusVisit.entryTarget(entryIds)
                val entryId = initialId ?: (if (resuming && !menuHasFocus) focusVisit.restoreTarget(entryIds) else null)
                    ?: return@LaunchedEffect
                if (pane != CatalogSheetPane.Create) {
                    val index = if (initialId != null) 0 else entryIds.indexOf(entryId).coerceAtLeast(0)
                    listState.scrollToItem(index)
                }
                // Each requester belongs to one actual row, never a moving
                // first-enabled alias shared with previously composed rows.
                repeat(4) {
                    withFrameNanos { }
                    val target = when (pane) {
                        CatalogSheetPane.Actions -> if (mainRows.isEmpty()) headerFocus else mainRequesters[entryId]
                        CatalogSheetPane.Playlists -> if (entryId == "create") playlistCreateFocus else targetRequesters[entryId]
                        CatalogSheetPane.Artists -> if (artistEntryIds.isEmpty()) headerFocus else targetRequesters[entryId]
                        CatalogSheetPane.Create -> nameFocus
                        CatalogSheetPane.SleepTimer -> mainRequesters[entryId]
                        CatalogSheetPane.LibraryManagement -> libraryManagementFirst
                    }
                    if (target?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) {
                        focusVisit.entrySucceeded(entryId)
                        return@LaunchedEffect
                    }
                }
                if (pane == CatalogSheetPane.LibraryManagement) runCatching { headerFocus.requestFocus() }
            }
            CompositionLocalProvider(LocalContentFocusEntry provides null,
                LocalFocusTargetsEnabled provides active, LocalBrowseMotionEnabled provides false,
                LocalCatalogMenuCanActivate provides ::canActivateMenu,
                LocalCatalogMenuAnchorReporter provides null,
                LocalTelevisionFocusTimings provides TelevisionFocusTimings(100, 200),
                LocalFocusMotionStyle provides if (motionEnabled) FocusMotionStyle.Television else FocusMotionStyle.Immediate) {
                TvMenuFocusScope(visibility = motion.visibility, active = active) {
                Box(Modifier.fillMaxSize().onSizeChanged { viewportSize = it }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                            if (native.action == KeyEvent.ACTION_UP) onMediaKey(native.keyCode)
                            true
                        } else !inputGate.dialog(canActivateMenu(), native.keyCode == KeyEvent.KEYCODE_BACK,
                            native.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE),
                            native.action == KeyEvent.ACTION_DOWN, native.action == KeyEvent.ACTION_UP,
                            repeat = native.repeatCount, activationReady = canActivateFocusedAction())
                    }) {
                    Box(Modifier.fillMaxSize().tvMenuScrim(motion).pointerInput(busy, motion.closing, motion.transitioning, pane) { detectTapGestures { back() } })
                    if (material) TvMenuBackdrop(checkNotNull(backdropLayer), placement, motion)
                    TvMenuSurface(modifier = Modifier
                        .offset { IntOffset(placement.bounds.left.roundToInt(), placement.bounds.top.roundToInt()) }
                        .width(if (pane == CatalogSheetPane.Create) 340.dp else metrics.width)
                        .heightIn(max = if (pane == CatalogSheetPane.Create) 440.dp else metrics.maxHeight)
                        .onSizeChanged { menuSize = it; menuLaidOut = it.width > 0 && it.height > 0 }
                        .onFocusChanged { menuHasFocus = it.hasFocus }.focusGroup()
                        .tvMenuEntrance(motion, placement)
                        , material = material, motionEnabled = motionEnabled) {
                        Column(Modifier.tvMenuPane(motion)) {
                            if (pane != CatalogSheetPane.Actions) {
                                val paneTitle = stringResource(when (pane) {
                                    CatalogSheetPane.Playlists -> R.string.actions_add_playlist
                                    CatalogSheetPane.Artists -> R.string.actions_view_artist
                                    CatalogSheetPane.SleepTimer -> R.string.actions_sleep_timer
                                    CatalogSheetPane.LibraryManagement -> if (libraryManagementItem?.resourceType == "library-playlist-folders")
                                        R.string.library_management_edit_folder else if (libraryManagementItem?.resourceType == "library-songs")
                                        R.string.library_management_remove_song else R.string.library_management_edit_playlist
                                    else -> if (creationKind == CatalogSheetCreation.Folder) R.string.actions_create_folder else R.string.actions_create_playlist
                                })
                                CatalogSheetRow(paneTitle, Modifier.automationId("catalog-actions-back").focusRequester(headerFocus)
                                    .onFocusChanged { if (it.isFocused) focusVisit.focused("back") },
                                    enabled = !busy, icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = ::back)
                                CatalogSheetSeparator()
                            }
                            if (busy && readActionJob[0] == null && pane != CatalogSheetPane.LibraryManagement) Row(Modifier.fillMaxWidth().padding(horizontal = 12.5.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                AppleLoadingIndicator(Modifier.size(12.dp))
                                Text(stringResource(R.string.actions_saving),
                                    color = Color.White.copy(alpha = 0.50f), fontSize = 11.sp)
                            }
                            (error ?: if (busy) null else if (creationUnconfirmed || unconfirmedAppendTargets.isNotEmpty()) R.string.actions_operation_failed
                                else if (pendingAdditionalLibrary.isNotEmpty()) R.string.actions_library_addition_pending else null)
                                ?.let { Text(stringResource(it), color = Color(0xFFFFB6B8), fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.5.dp, vertical = 5.dp)) }
                            notice?.let { Text(stringResource(it), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.5.dp, vertical = 5.dp)) }
                            if (pane == CatalogSheetPane.LibraryManagement && libraryManagementItem != null) {
                                CatalogLibraryManagementPane(libraryManagementItem, services, services.libraryManagement,
                                    firstRequester = libraryManagementFirst,
                                    onChanged = onLibraryChanged,
                                    onDeleted = {
                                        onResourceDeleted?.invoke(libraryManagementItem)
                                        busy = false
                                        dismiss()
                                    },
                                    onBusyChanged = { if (!motion.closing) busy = it },
                                    onBackHandlerChanged = { libraryManagementBack[0] = it },
                                    onCancel = ::back)
                            } else if (pane == CatalogSheetPane.Create) {
                                val keyboard = LocalSoftwareKeyboardController.current
                                TvTextField(playlistName, { playlistName = it }, Modifier.fillMaxWidth().focusRequester(nameFocus)
                                    .onFocusChanged { if (it.isFocused) focusVisit.focused("name") }
                                    .automationId(if (creationKind == CatalogSheetCreation.Folder) "catalog-folder-name" else "catalog-playlist-name")
                                    .focusProperties { up = headerFocus; down = if (creationKind == CatalogSheetCreation.Folder) createFocus else descriptionFocus },
                                    enabled = !busy && active, singleLine = true,
                                    label = stringResource(if (creationKind == CatalogSheetCreation.Folder) R.string.actions_folder_name else R.string.actions_playlist_name),
                                    keyboardOptions = KeyboardOptions(imeAction = if (creationKind == CatalogSheetCreation.Folder) ImeAction.Done else ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() },
                                        onDone = { keyboard?.hide(); createFocus.requestFocus() }))
                                if (creationKind == CatalogSheetCreation.Playlist) {
                                    Spacer(Modifier.height(8.dp))
                                    TvTextField(playlistDescription, { playlistDescription = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp)
                                        .focusRequester(descriptionFocus).automationId("catalog-playlist-description")
                                        .focusProperties { up = nameFocus; down = createFocus }, enabled = !busy && active,
                                        singleLine = false, maxLines = 3,
                                        label = stringResource(R.string.actions_playlist_description),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); createFocus.requestFocus() }))
                                }
                                Spacer(Modifier.height(8.dp))
                                CatalogSheetRow(stringResource(if (isFolder) R.string.actions_create else if (canAppendSource) R.string.actions_create_and_add else R.string.actions_create_playlist),
                                    Modifier.focusRequester(createFocus).automationId(if (creationKind == CatalogSheetCreation.Folder) "catalog-folder-create" else "catalog-playlist-create")
                                        .focusProperties { up = if (creationKind == CatalogSheetCreation.Folder) nameFocus else descriptionFocus; down = FocusRequester.Cancel },
                                    enabled = !busy && !creationUnconfirmed && canCreateCurrent && playlistName.isNotBlank(), icon = Icons.Rounded.Add) {
                                    keyboard?.hide()
                                    if (canCreateCurrent && !creationUnconfirmed) {
                                        val requestedCreation = creationKind
                                        val success = if (requestedCreation == CatalogSheetCreation.Folder) R.string.actions_folder_created else R.string.actions_playlist_created
                                        mutate(success, folderCreation = requestedCreation.takeIf { isFolder },
                                            successNotice = { if (pendingCreation == null) success else R.string.actions_operation_failed }) {
                                            val tracks = if (requestedCreation == CatalogSheetCreation.Playlist && !isFolder && canAppendSource)
                                                sourceTracks() else emptyList()
                                            val pending = actions.preparePlaylistCreation(playlistName, playlistDescription, tracks,
                                                folderRef?.id, requestedCreation == CatalogSheetCreation.Folder)
                                            addLibraryAfterCreation = tracks.isNotEmpty() &&
                                                services.libraryAdditionPreferences.settings.value.addPlaylistSongs
                                            pendingCreation = pending
                                            val created = try {
                                                actions.submitPlaylistCreation(pending)
                                            } catch (failure: CatalogRequestException) {
                                                if (failure.httpStatus in 400..499) { pendingCreation = null; addLibraryAfterCreation = false }
                                                throw failure
                                            }
                                            createdResource = created
                                            val known = pending.copy(created = created)
                                            pendingCreation = known
                                            val confirmed = try { actions.confirmPlaylistCreation(known) }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { null }
                                            if (confirmed != null) {
                                                createdResource = confirmed
                                                pendingCreation = null
                                                if (addLibraryAfterCreation) {
                                                    addLibraryAfterCreation = false
                                                    state = addOptionalLibraryItems(tracks)
                                                }
                                            }
                                            navigate(CatalogSheetPane.Actions)
                                        }
                                    }
                                }
                            } else LazyColumn(state = listState,
                                // The fade's offscreen buffer includes the menu
                                // insets. Keep the row at its original width but
                                // retain its focus expansion and shadow inside
                                // that buffer instead of cropping both sides.
                                modifier = Modifier.requiredWidth(metrics.width).weight(1f, fill = false).automationId("catalog-actions-list")
                                    .catalogMenuFades(listState.canScrollBackward, listState.canScrollForward)
                                    .onPreviewKeyEvent { event ->
                                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                            event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) pendingPageEntry = -1
                                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pane != CatalogSheetPane.Actions) {
                                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) back()
                                            true
                                        } else false
                                    },
                                verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing),
                                contentPadding = PaddingValues(horizontal = metrics.padding, vertical = metrics.viewportPadding)) {
                                if (pane == CatalogSheetPane.Actions) {
                                    itemsIndexed(mainRows, key = { _, row -> row.id }) { index, row ->
                                        val requester = mainRequesters.getOrPut(row.id) { FocusRequester() }
                                        val selected = (row.id == "like" && state?.rating == PersonalRating.Like) || (row.id == "dislike" && state?.rating == PersonalRating.Dislike)
                                        Column {
                                            if (index > 0 && catalogActionGroup(row.id) != catalogActionGroup(mainRows[index - 1].id)) CatalogSheetSeparator()
                                            CatalogSheetRow(row.label, Modifier.automationId("catalog-action-${row.id}").focusRequester(requester)
                                                .onFocusChanged { if (it.isFocused) {
                                                    focusedMainRow = row.id; focusedMainIndex = index; focusVisit.focused(row.id)
                                                } }
                                                .focusProperties {
                                                    if (index == 0) up = FocusRequester.Cancel
                                                    if (index == mainRows.lastIndex) down = FocusRequester.Cancel
                                                },
                                                // A pending/already-completed action remains a focusable
                                                // row. Availability controls activation, not initial position.
                                                enabled = !busy && row.enabled, retainFocusWhenDisabled = true,
                                                selected = selected || (row.id == "favorite" && state?.inFavorites == true) || (row.id == "library" && state?.inLibrary == true), icon = catalogActionIcon(row.id),
                                                submenu = row.id == "playlist" || row.id == "create" || row.id == "create-folder" || row.id == "sleep-timer" || row.id == "library-management" || (row.id == "artist" && item.catalogKind != CatalogKind.Artist),
                                                onClick = row.onClick)
                                        }
                                    }
                                    if (mainRows.isEmpty()) item(key = "close") {
                                        Text(stringResource(R.string.actions_none), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 12.5.dp, vertical = 8.dp))
                                        CatalogSheetRow(stringResource(R.string.actions_back), Modifier.focusRequester(headerFocus),
                                            icon = Icons.AutoMirrored.Rounded.ArrowBack, enabled = !busy, onClick = ::back)
                                    }
                                } else if (pane == CatalogSheetPane.SleepTimer) {
                                    itemsIndexed(sleepOptions, key = { _, option -> option.name }) { index, option ->
                                        val id = "sleep-${option.name}"
                                        val requester = mainRequesters.getOrPut(id) { FocusRequester() }
                                        val enabled = onSleepTimer != null &&
                                            (option != SleepTimerOption.EndOfTrack || canSleepAtTrackEnd) &&
                                            (option != SleepTimerOption.EndOfContext || canSleepAtContextEnd)
                                        val label = when (option) {
                                            SleepTimerOption.Off -> stringResource(R.string.actions_sleep_timer_off)
                                            SleepTimerOption.EndOfTrack -> stringResource(R.string.actions_sleep_timer_track_end)
                                            SleepTimerOption.EndOfContext -> stringResource(R.string.actions_sleep_timer_context_end)
                                            else -> stringResource(R.string.actions_sleep_timer_minutes, (checkNotNull(option.durationMs) / 60_000L).toInt())
                                        }
                                        CatalogSheetRow(label, Modifier.automationId("catalog-$id").focusRequester(requester)
                                            .onFocusChanged { if (it.isFocused) focusVisit.focused(id) }
                                            .focusProperties {
                                                if (index == 0) up = headerFocus
                                                if (option == lastEnabledSleepOption)
                                                    down = FocusRequester.Cancel
                                            }, enabled = enabled, retainFocusWhenDisabled = false,
                                            selected = (sleepTimerState?.option ?: SleepTimerOption.Off) == option) {
                                            if (enabled) { onSleepTimer?.invoke(option); dismiss() }
                                        }
                                    }
                                } else {
                                    if (pane == CatalogSheetPane.Playlists) item(key = "create") {
                                        CatalogSheetRow(stringResource(R.string.actions_create_playlist), Modifier.focusRequester(playlistCreateFocus)
                                            .onFocusChanged { if (it.isFocused) focusVisit.focused("create") }
                                            .automationId("catalog-action-new-playlist"), enabled = !busy, icon = Icons.Rounded.Add,
                                            submenu = true) { openCreate(CatalogSheetCreation.Playlist) }
                                        if (targets.isNotEmpty()) CatalogSheetSeparator()
                                    }
                                    val content = targets
                                    itemsIndexed(content, key = { _, target -> "${target.resourceType}:${target.resourceId ?: target.id}" }) { index, target ->
                                        val targetKey = "${target.resourceType}:${target.resourceId ?: target.id}"
                                        val requester = targetRequesters.getOrPut(targetKey) { FocusRequester() }
                                        CatalogSheetRow(target.title, Modifier.automationId("catalog-action-target-$index").focusRequester(requester)
                                            .onFocusChanged { if (it.isFocused) focusVisit.focused(targetKey) },
                                            enabled = !busy && targetKey !in unconfirmedAppendTargets, artwork = target) {
                                            if (pane == CatalogSheetPane.Artists) dismiss { onViewArtist(target) }
                                            else if (targetKey !in unconfirmedAppendTargets) mutate(R.string.actions_playlist_added,
                                                successNotice = { if (targetKey in pendingAppends) R.string.actions_operation_failed else R.string.actions_playlist_added }) {
                                                if (actions.readState(target).canEditPlaylist != true) {
                                                    uneditablePlaylists = uneditablePlaylists + targetKey
                                                    throw PlaylistNotEditable()
                                                }
                                                val tracks = sourceTracks()
                                                val pending = actions.preparePlaylistAppend(target, tracks)
                                                if (services.libraryAdditionPreferences.settings.value.addPlaylistSongs)
                                                    addLibraryAfterAppends = addLibraryAfterAppends + targetKey
                                                pendingAppends = pendingAppends + (targetKey to pending)
                                                try {
                                                    actions.submitPlaylistAppend(pending)
                                                } catch (failure: CatalogRequestException) {
                                                    if (failure.httpStatus in 400..499) {
                                                        pendingAppends = pendingAppends - targetKey
                                                        addLibraryAfterAppends = addLibraryAfterAppends - targetKey
                                                    }
                                                    throw failure
                                                }
                                                val confirmed = try { actions.confirmPlaylistAppend(pending) }
                                                catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { false }
                                                if (confirmed) {
                                                    pendingAppends = pendingAppends - targetKey
                                                    if (targetKey in addLibraryAfterAppends) {
                                                        addLibraryAfterAppends = addLibraryAfterAppends - targetKey
                                                        state = addOptionalLibraryItems(tracks)
                                                    }
                                                }
                                                navigate(CatalogSheetPane.Actions)
                                            }
                                        }
                                    }
                                    if (pageLoading) item(key = "loading") {
                                        Box(Modifier.fillMaxWidth().height(metrics.rowHeight), Alignment.Center) { AppleLoadingIndicator(Modifier.size(16.dp)) }
                                    }
                                    if (!pageLoading && content.isEmpty() && !pageFailed) item(key = "empty") {
                                        Text(stringResource(if (pane == CatalogSheetPane.Playlists) R.string.actions_no_playlists else R.string.actions_no_artists),
                                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.5.dp, vertical = 8.dp))
                                    }
                                    if (pageFailed || nextPage != null) item(key = "more") {
                                        CatalogSheetRow(stringResource(if (pageFailed) R.string.actions_retry else R.string.actions_load_more),
                                            Modifier.automationId("catalog-action-more"), enabled = !busy && !pageLoading,
                                            icon = if (pageFailed) Icons.Rounded.Refresh else Icons.Rounded.MoreHoriz) {
                                                pendingPageEntry = content.size
                                                loadPage(append = nextPage != null)
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
                } // Window-local focus overlay is outside the panel's clipping.
            }
        }
    }
    if (managementVisible && managementUrl != null) {
        AppleMusicManagementDialog(managementUrl, item.title,
            onDone = {
                managementVisible = false
                favoritePendingTarget = null
                addLibraryAfterFavorite = false
                notice = null
                error = null
                onLibraryChanged()
                refreshActionReads()
            },
            onDismiss = { managementVisible = false })
    }
    if (shareVisible && shareUrl != null) AppleMusicShareDialog(shareUrl, item.title) { shareVisible = false }
}

private fun catalogActionGroup(id: String): Int = when (id) {
    "signin" -> 0
    "play-next", "play-last" -> 6
    "library", "favorite", "retry-library" -> 1
    "like", "dislike", "clear-rating" -> 2
    "playlist", "create", "create-folder", "check-library", "library-management" -> 3
    "artist", "album", "details" -> 4
    "manage-in-music" -> 7
    "share", "copy-link" -> 8
    "audio-effects" -> 9
    "sleep-timer" -> 9
    "refresh" -> 5
    else -> if (id.startsWith("check-playlist-")) 3 else 0
}

private fun catalogActionIcon(id: String): ImageVector = when (id) {
    "signin" -> Icons.Rounded.Login
    "play-next" -> Icons.Rounded.PlaylistPlay
    "play-last" -> Icons.Rounded.QueueMusic
    "library" -> Icons.Rounded.LibraryAdd
    "retry-library" -> Icons.Rounded.LibraryAdd
    "favorite" -> Icons.Rounded.Star
    "like" -> Icons.Rounded.ThumbUp
    "dislike" -> Icons.Rounded.ThumbDown
    "clear-rating" -> Icons.Rounded.RemoveCircleOutline
    "playlist" -> Icons.Rounded.PlaylistAdd
    "create" -> Icons.Rounded.Add
    "create-folder" -> Icons.Rounded.CreateNewFolder
    "check-library" -> Icons.Rounded.FolderOpen
    "library-management" -> Icons.Rounded.Edit
    "artist" -> Icons.Rounded.Person
    "album" -> Icons.Rounded.Album
    "queue-remove" -> Icons.Rounded.RemoveCircleOutline
    "queue-move-up" -> Icons.Rounded.ArrowUpward
    "queue-move-down" -> Icons.Rounded.ArrowDownward
    "details" -> Icons.Rounded.Info
    "manage-in-music" -> Icons.Rounded.PhoneIphone
    "share" -> Icons.Rounded.Share
    "copy-link" -> Icons.Rounded.Link
    "audio-effects" -> Icons.Rounded.Equalizer
    "sleep-timer" -> Icons.Rounded.Bedtime
    "refresh" -> Icons.Rounded.Refresh
    else -> if (id.startsWith("check-playlist-")) Icons.Rounded.PlaylistPlay else Icons.Rounded.QueueMusic
}
