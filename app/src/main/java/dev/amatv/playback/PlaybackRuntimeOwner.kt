package dev.amatv.playback

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.amatv.integration.MusicServices
import dev.amatv.integration.NativeQueueEntry
import dev.amatv.integration.PlaybackFailureKind
import dev.amatv.integration.PlaybackMediaSession
import dev.amatv.integration.PlaybackPreferences
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.integration.StationReconnectPolicy
import dev.amatv.integration.classifyPlaybackFailure
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Application/service-owned playback state. A UI lease contains callbacks only
 * until detach. With no UI, transport, end events, queue and pending collection
 * pages continue in this owner's scope rather than a disposed Compose scope.
 */
class PlaybackRuntimeOwner(
    context: Context,
    private val onReleased: (PlaybackRuntimeOwner) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val services = MusicServices(context.applicationContext)
    val preferences = PlaybackPreferences(context.applicationContext)
    private val mutableSnapshot = MutableStateFlow<PlaybackSnapshot?>(null)
    val snapshot: StateFlow<PlaybackSnapshot?> = mutableSnapshot.asStateFlow()
    private val mutableDesiredPlay = MutableStateFlow(false)
    val desiredPlay: StateFlow<Boolean> = mutableDesiredPlay.asStateFlow()
    enum class PreparationPhase { Idle, Preparing, Retrying, Ready, Failed }
    data class PreparationRequest(
        val serial: Long,
        val sessionId: Long,
        val entryId: Long,
        val track: Track,
        val positionMs: Long,
        val isLiveStream: Boolean = false,
    ) {
        fun matches(serial: Long, sessionId: Long, entryId: Long?): Boolean =
            this.serial == serial && this.sessionId == sessionId && this.entryId == entryId
    }
    data class PreparationState(
        val phase: PreparationPhase = PreparationPhase.Idle,
        val request: PreparationRequest? = null,
        val resolvedTrack: Track? = null,
        val generation: Long = 0L,
        val retryAtElapsedRealtimeMs: Long? = null,
        val retryAttempt: Int = 0,
        val failureKind: PlaybackFailureKind? = null,
        val errorMessage: String? = null,
    ) {
        val isPending: Boolean get() = phase == PreparationPhase.Preparing || phase == PreparationPhase.Retrying
        internal fun cancelled(clearReady: Boolean): PreparationState = when {
            clearReady -> PreparationState()
            isPending -> PreparationState(request = request)
            else -> this
        }
    }
    private val mutablePreparation = MutableStateFlow(PreparationState())
    val preparation: StateFlow<PreparationState> = mutablePreparation.asStateFlow()
    val hasPendingPrepare: Boolean get() = preparation.value.isPending
    val playbackGeneration: Long get() = preparation.value.generation
    @Volatile var isReleased: Boolean = false
        private set

    data class UiCallbacks(
        val onTrack: (Track) -> Unit,
        val onSnapshot: (PlaybackSnapshot) -> Unit,
        val system: PlaybackMediaSession.Callbacks,
    )
    private var ui: Pair<Long, UiCallbacks>? = null
    private var sequence = 0L
    private val serviceLeases = mutableSetOf<Long>()
    private val retention = PlaybackRuntimeRetention()
    private var idleJob: Job? = null
    private var prepareJob: Job? = null
    private var prepareSerial = 0L
    private var retryAttempts = 0
    private val failureTracker = PlaybackFailureTracker()
    private val stationHealth = StationReconnectPolicy.PlaybackHealth()
    private var handledEnd: Pair<Long, Long?>? = null
    private var collection: CollectionPlaybackContinuation? = null
    private var collectionSessionId: Long? = null
    private var collectionJob: Job? = null
    private var collectionGeneration = 0L
    private val continuationResume = ContextContinuationResumeIntent()
    private val sleepTimerController = PlaybackSleepTimer(SystemClock::elapsedRealtime)
    val sleepTimer: StateFlow<PlaybackSleepTimerState> = sleepTimerController.state
    private var sleepTimerJob: Job? = null

    val queue = PlaybackQueueController(scope, services, preferences, onPlayTrack = { track ->
        stopSleepTimerOnEntryChange()
        cancelPreparation(clearReady = true)
        retryAttempts = 0
        stationHealth.reset()
        failureTracker.reset()
        val consumer = ui?.second
        mutableSnapshot.value = null
        if (consumer != null) consumer.onTrack(track) else prepareCurrent()
    })

    val hasRetainedSession: Boolean get() = queue.state.value.current != null || snapshot.value != null
    private val initialLocation = preferences.restoreCheckpointSession()
    private var uiDestination = initialLocation?.destination ?: preferences.activeTab
    private var uiPreviousDestination = initialLocation?.previousDestination ?: preferences.activeTab

    fun updateUiLocation(destination: Destination, previousDestination: Destination) = onMain {
        uiDestination = destination
        uiPreviousDestination = previousDestination
    }
    internal val pendingCollectionCheckpoint: CollectionPlaybackCheckpoint?
        get() = collection?.takeIf { collectionSessionId == queue.state.value.sessionId &&
            queue.state.value.contextContinuationPending }?.let {
                runCatching { it.checkpoint(services.authorizationCacheScope()) }.getOrNull()
            }

    init {
        services.setRuntimePlaybackListener { value -> onMain { receive(value) } }
        services.setRuntimeSystemPlaybackCallbacks(PlaybackMediaSession.Callbacks(
            play = { onMain { ui?.second?.system?.play?.invoke() ?: playDetached() } },
            pause = { onMain { ui?.second?.system?.pause?.invoke() ?: pauseDetached() } },
            next = { onMain { ui?.second?.system?.next?.invoke() ?: skipDetached(1) } },
            previous = { onMain { ui?.second?.system?.previous?.invoke() ?: skipDetached(-1) } },
            seekTo = { position -> onMain { ui?.second?.system?.seekTo?.invoke(position) ?: seekDetached(position) } },
        ))
        scope.launch {
            queue.state.collect { state ->
                stopSleepTimerOnEntryChange()
                if (collectionSessionId != null && (state.sessionId != collectionSessionId || !state.contextContinuationPending)) {
                    clearContinuation(cancelResume = state.sessionId != collectionSessionId)
                }
                if (ui == null) {
                    syncNativeRail()
                    saveSession()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(5_000)
                if (ui == null && mutableDesiredPlay.value && snapshot.value?.isPlaying == true) saveSession()
            }
        }
    }

    /** Must be called on Main, normally from a DisposableEffect. */
    fun attachUi(activity: Activity?, callbacks: UiCallbacks): Long {
        checkMain()
        check(!isReleased)
        val token = ++sequence
        ui = token to callbacks
        services.attachPlaybackHost(activity)
        idleJob?.cancel()
        snapshot.value?.takeIf { it.requestGeneration == services.playbackSnapshot()?.requestGeneration }
            ?.let { callbacks.onSnapshot(publishSnapshot(it)) }
        updateRetention()
        return token
    }

    fun detachUi(token: Long) {
        checkMain()
        if (ui?.first != token || isReleased) return
        ui = null
        services.setPlaybackListener(null)
        services.setSystemPlaybackCallbacks(null)
        services.attachPlaybackHost(null)
        services.setSpectrumActive(false)
        saveSession(synchronous = true)
        updateRetention()
    }

    fun setDesiredPlayIntent(playing: Boolean) = onMain {
        val wasPlaying = mutableDesiredPlay.value
        mutableDesiredPlay.value = playing
        if (!playing) {
            continuationResume.cancel()
            cancelPreparation()
            retryAttempts = 0
            stationHealth.reset()
        } else {
            if (!wasPlaying) { retryAttempts = 0; stationHealth.reset() }
            if (collection != null) ensureCollectionLoading()
        }
        updateRetention()
    }

    /** Minute timers include pauses; this selection survives UI/Activity replacement with the runtime. */
    fun setSleepTimer(option: SleepTimerOption): Boolean {
        checkMain()
        if (isReleased || option == SleepTimerOption.EndOfTrack && !canSleepAtTrackEnd ||
            option == SleepTimerOption.EndOfContext && !canSleepAtContextEnd) return false
        if (!sleepTimerController.select(option, queue.state.value.current?.entryId, queue.state.value)) return false
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimer.value.deadlineElapsedRealtimeMs?.let {
            sleepTimerJob = scope.launch {
                while (true) {
                    val remaining = sleepTimer.value.remainingMs(SystemClock.elapsedRealtime()) ?: return@launch
                    if (remaining == 0L) {
                        if (sleepTimerController.consumeDeadline()) pauseForSleepTimer()
                        return@launch
                    }
                    // Recheck elapsed time after wake instead of leaving a long
                    // relative Main/Handler delay outstanding across device sleep.
                    // This never publishes ticks or recomposes the root.
                    delay(minOf(remaining, 1_000L))
                }
            }
        }
        syncNativeRail()
        updateRetention()
        return true
    }

    val canSleepAtTrackEnd: Boolean
        get() {
            val current = queue.state.value.current ?: return false
            if (current.track.catalogKind != CatalogKind.Station) return true
            val value = snapshot.value ?: return false
            return value.currentTrack?.catalogId == current.track.catalogId &&
                value.isPrepared && !value.isLiveStream && value.durationMs > 0L
        }

    val canSleepAtContextEnd: Boolean get() = sleepTimerController.canSelectContextEnd(queue.state.value)

    /** The same gate is used by UI preparation and detached playback, including SDK handoff. */
    fun nativeUpcoming(state: QueueState = queue.state.value): List<NativeQueueEntry> =
        sleepTimerController.nativeUpcoming(state)

    private fun stopSleepTimerOnEntryChange() {
        if (sleepTimerController.consumeQueueChange(queue.state.value)) pauseForSleepTimer()
    }

    private fun pauseForSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        setDesiredPlayIntent(false)
        ui?.second?.system?.pause?.invoke() ?: pauseDetached()
        syncNativeRail()
        updateRetention()
    }

    private fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerController.cancel()
    }

    fun acquireService(): Long {
        checkMain()
        val token = ++sequence
        serviceLeases += token
        updateRetention()
        return token
    }

    fun releaseService(token: Long) = onMain {
        serviceLeases -= token
        updateRetention()
    }

    internal fun installContextContinuation(continuation: CollectionPlaybackContinuation?) {
        checkMain()
        clearContinuation()
        if (continuation == null || !queue.state.value.contextContinuationPending) return
        continuation.bindShuffleAnchor(checkNotNull(queue.state.value.current).entryId)
        collection = continuation
        collectionSessionId = queue.state.value.sessionId
        // This is an explicit new play context; do not wait for a later UI effect.
        ensureCollectionLoading()
        saveSession()
    }

    /** Explicit Play may continue past an ended anchor, never replay that anchor. */
    fun retryContextContinuation(advanceWhenAvailable: Boolean = false): Boolean {
        checkMain()
        val id = collectionSessionId ?: return false
        val live = queue.state.value
        val current = live.current ?: return false
        if (collection == null || live.sessionId != id || !live.contextContinuationPending) return false
        if (advanceWhenAvailable) {
            continuationResume.request(id, current.entryId)
            mutableDesiredPlay.value = true
        }
        if (live.contextContinuationFailed) queue.retryContextTail(id)
        ensureCollectionLoading()
        return true
    }

    /** After disk hydration only. Restore metadata/cursor without requesting playback. */
    fun restorePendingCollection(session: PlaybackSession): Boolean {
        checkMain()
        val saved = session.pendingCollection ?: return false
        val live = queue.state.value
        if (saved.authorizationScope != services.authorizationCacheScope() ||
            session.persistenceId != live.persistenceId || live.current == null) return false
        if (saved.shuffle?.let { selection -> live.contextEntries.none { it.entryId == selection.anchorEntryId } } == true) return false
        val kind = if (saved.collection.catalogKind == CatalogKind.Album) ContextKind.Album
            else if (saved.collection.resourceType == "library-playlists") ContextKind.LibraryPlaylist else ContextKind.Playlist
        if (!queue.markContextTailPending(live.sessionId, PlaybackContext(kind,
                saved.collection.resourceId ?: saved.collection.catalogId, saved.collection.title))) return false
        clearContinuation()
        collection = CollectionPlaybackContinuation.restore(saved, services::collectionTracksPage)
        collectionSessionId = live.sessionId
        if (mutableDesiredPlay.value) ensureCollectionLoading()
        return true
    }

    fun currentPositionMs(): Long {
        val current = queue.state.value.current ?: return 0L
        val value = snapshot.value ?: return preparation.value.request?.takeIf {
            it.sessionId == queue.state.value.sessionId && it.entryId == current.entryId
        }?.positionMs ?: 0L
        if (value.appQueueEntryId != null && value.appQueueEntryId != current.entryId) return 0L
        if (value.currentTrack?.catalogKind != current.track.catalogKind ||
            value.currentTrack?.catalogId != current.track.catalogId) return 0L
        val progress = if (value.isPlaying) (SystemClock.elapsedRealtime() - value.capturedAtElapsedRealtimeMs).coerceAtLeast(0) else 0L
        return (value.positionMs + progress).coerceAtLeast(0).let {
            if (value.durationMs > 0) it.coerceAtMost(value.durationMs) else it
        }
    }

    /** Explicit sign-out/stop releases pending work but leaves an attached UI owner usable. */
    fun resetSession() = onMain {
        clearSleepTimer()
        cancelPreparation(clearReady = true)
        clearContinuation()
        mutableDesiredPlay.value = false
        mutableSnapshot.value = null
        handledEnd = null
        retryAttempts = 0
        failureTracker.reset()
        stationHealth.reset()
        updateRetention()
    }

    private fun receive(value: PlaybackSnapshot) {
        if (isReleased || value.requestGeneration == 0L || value.requestGeneration != playbackGeneration) return
        val queued = queue.state.value.current
        if (!value.nativeQueueHandoff && queued != null &&
            ((value.appQueueEntryId != null && value.appQueueEntryId != queued.entryId) ||
                value.currentTrack?.catalogKind != queued.track.catalogKind ||
                value.currentTrack?.catalogId != queued.track.catalogId)) return
        val entryId = value.appQueueEntryId ?: queued?.entryId
        val sleepTimerEnded = value.isEnded && sleepTimerController.consumePlaybackEnd(queue.state.value, entryId) ||
            value.nativeQueueHandoff && sleepTimerController.consumeNativeHandoff(queue.state.value, entryId)
        if (sleepTimerEnded) {
            pauseForSleepTimer()
            val paused = value.copy(isPlaying = false, isBuffering = false)
            mutableSnapshot.value = paused
            publishSnapshot(paused)
            ui?.second?.onSnapshot?.invoke(paused)
            updateRetention()
            return
        }
        if (value.appQueueEntryId != null && value.appQueueEntryId != queued?.entryId && value.nativeQueueHandoff) {
            if (queue.adoptNativeCurrent(value.appQueueEntryId)) syncNativeRail()
        }
        mutableSnapshot.value = value
        val live = queue.state.value
        if (value.currentTrack?.catalogKind == CatalogKind.Station) {
            if (stationHealth.onSnapshot(value.positionMs, SystemClock.elapsedRealtime(), value.isPlaying,
                    value.isPrepared && value.errorMessage == null)) retryAttempts = 0
        } else if (value.isPrepared && value.isPlaying && value.errorMessage == null && value.positionMs > 250L) retryAttempts = 0
        if (value.isPrepared && value.isPlaying && value.errorMessage == null && value.positionMs > 250L) {
            live.current?.let { queue.markCurrentPlaybackStarted(it.entryId) }
        }
        if (failureTracker.shouldHandle(value.requestGeneration, value.errorMessage != null)) {
            failPreparation(value.failureKind ?: PlaybackFailureKind.Unexpected,
                checkNotNull(value.errorMessage), currentPositionMs(), value.isLiveStream, value.requestGeneration)
        }
        val published = publishSnapshot(value)
        val consumer = ui?.second
        if (consumer != null) {
            consumer.onSnapshot(published)
        } else if (value.errorMessage == null && value.isEnded) {
            val end = value.requestGeneration to live.current?.entryId
            if (handledEnd != end) {
                handledEnd = end
                if (live.current?.track?.catalogKind == CatalogKind.Station) pauseDetached()
                else if (mutableDesiredPlay.value) {
                    val next = queue.advanceNext(manual = false)
                    if (next == null && !queue.state.value.awaitingNext) pauseDetached()
                }
            }
        } else if (!value.isEnded) handledEnd = null
        updateRetention()
    }

    private fun publishSnapshot(value: PlaybackSnapshot): PlaybackSnapshot {
        val published = when {
            hasPendingPrepare && value.errorMessage != null ->
                value.copy(isPlaying = false, isBuffering = true, errorMessage = null)
            !mutableDesiredPlay.value && (value.isPlaying || value.isBuffering) ->
                value.copy(isPlaying = false, isBuffering = false)
            else -> value
        }
        val state = queue.state.value
        val station = value.currentTrack?.catalogKind == CatalogKind.Station
        services.publishSystemPlayback(published,
            if (station) value.canSkipNext else state.canSkipNext,
            if (station) value.canSkipPrevious else state.canSkipPrevious)
        return published
    }

    /** UI and background commands always prepare the currently selected occurrence. */
    fun prepareCurrent(positionMs: Long = 0L): Boolean {
        checkMain()
        return beginPreparation(positionMs)
    }

    private fun beginPreparation(positionMs: Long, retryDelayMs: Long = 0L): Boolean {
        if (isReleased || !mutableDesiredPlay.value) return false
        val state = queue.state.value
        val current = state.current ?: return false
        if (state.awaitingNext) return false
        val request = PreparationRequest(++prepareSerial, state.sessionId, current.entryId,
            current.track, positionMs.coerceAtLeast(0L),
            snapshot.value?.isLiveStream ?: preparation.value.request?.isLiveStream
                ?: (current.track.catalogKind == CatalogKind.Station))
        prepareJob?.cancel()
        mutablePreparation.value = PreparationState(
            phase = if (retryDelayMs > 0L) PreparationPhase.Retrying else PreparationPhase.Preparing,
            request = request,
            retryAtElapsedRealtimeMs = retryDelayMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() + it },
            retryAttempt = retryAttempts,
        )
        mutableSnapshot.value = null
        stationHealth.reset()
        services.pauseForReplacement()
        prepareJob = scope.launch {
            try {
                val prepared = prepareRuntimeSelection(request,
                    resolve = services::resolvePlayableTrack,
                    prepare = { playable, position, entry ->
                        queue.updateCatalogIdentity(request.track, playable)
                        services.preparePlayback(playable, position, playWhenReady = false,
                            currentAppEntryId = entry, upcoming = nativeUpcoming())
                    },
                    isCurrent = ::isCurrentPreparation,
                    awaitRetry = {
                        if (retryDelayMs > 0L) {
                            delay(retryDelayMs)
                            if (isCurrentPreparation(request)) mutablePreparation.value = preparation.value.copy(
                                phase = PreparationPhase.Preparing, retryAtElapsedRealtimeMs = null)
                        }
                    },
                ) ?: return@launch
                mutablePreparation.value = PreparationState(PreparationPhase.Ready, request,
                    resolvedTrack = prepared.track, generation = prepared.generation)
                // No UI callback participates in deciding the occurrence or play intent.
                if (mutableDesiredPlay.value) services.play() else services.pause()
                services.playbackSnapshot()?.takeIf { it.requestGeneration == prepared.generation }?.let(::receive)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (isCurrentPreparation(request)) {
                    Log.w("AMATV-runtime", "Playback prepare failed: ${failure.javaClass.simpleName}")
                    failPreparation(classifyPlaybackFailure(null, null, null, failure),
                        failure.message ?: dev.amatv.i18n.AppText.text(dev.amatv.R.string.app_song_load_failed),
                        request.positionMs, request.isLiveStream, 0L)
                }
            } finally {
                if (request.serial == prepareSerial && hasPendingPrepare) {
                    mutablePreparation.value = PreparationState(request = request)
                }
                updateRetention()
            }
        }
        updateRetention()
        return true
    }

    private fun isCurrentPreparation(request: PreparationRequest): Boolean {
        val state = queue.state.value
        return !isReleased && mutableDesiredPlay.value && !state.awaitingNext &&
            request.matches(prepareSerial, state.sessionId, state.current?.entryId)
    }

    /** A completed paused engine remains resumable; cancelled preparation has no usable generation. */
    private fun cancelPreparation(clearReady: Boolean = false) {
        ++prepareSerial
        prepareJob?.cancel()
        prepareJob = null
        if (clearReady || hasPendingPrepare) {
            mutablePreparation.value = preparation.value.cancelled(clearReady)
            mutableSnapshot.value = null
        }
    }

    private fun failPreparation(kind: PlaybackFailureKind, message: String, positionMs: Long,
        isLiveStream: Boolean, generation: Long) {
        val state = queue.state.value
        val current = state.current ?: return
        val station = current.track.catalogKind == CatalogKind.Station
        val stationPlan = if (station) StationReconnectPolicy.plan(current.track.catalogId,
            prepareSerial, retryAttempts, kind, mutableDesiredPlay.value, isLiveStream, positionMs) else null
        val retryDelay = stationPlan?.delayMs ?: 3_000L.takeIf {
            !station && kind == PlaybackFailureKind.Network && mutableDesiredPlay.value && retryAttempts < 2
        }
        if (retryDelay != null) {
            retryAttempts = stationPlan?.attempt ?: retryAttempts + 1
            beginPreparation(stationPlan?.resumePositionMs ?: positionMs, retryDelay)
        } else {
            mutablePreparation.value = PreparationState(PreparationPhase.Failed,
                PreparationRequest(prepareSerial, state.sessionId, current.entryId, current.track, positionMs, isLiveStream),
                generation = generation, failureKind = kind, errorMessage = message)
            mutableDesiredPlay.value = false
            services.pause()
            saveSession(synchronous = true)
        }
    }

    private fun playDetached() {
        setDesiredPlayIntent(true)
        val current = snapshot.value
        val live = queue.state.value
        val endedAnchor = current?.isEnded == true &&
            (current.appQueueEntryId == null || current.appQueueEntryId == live.current?.entryId) &&
            current.currentTrack?.catalogId == live.current?.track?.catalogId
        if (endedAnchor && live.contextContinuationPending &&
            retryContextContinuation(advanceWhenAvailable = true)) {
            updateRetention()
            return
        }
        if (current?.isPrepared == true && current.errorMessage == null && !current.isEnded) services.play()
        else if (!hasPendingPrepare) prepareCurrent(if (current?.isEnded == true) 0L else currentPositionMs())
        ensureCollectionLoading()
        updateRetention()
    }

    private fun pauseDetached() {
        setDesiredPlayIntent(false)
        services.pause()
        saveSession(synchronous = true)
        updateRetention()
    }

    private fun skipDetached(direction: Int) {
        if (queue.state.value.current?.track?.catalogKind == CatalogKind.Station) {
            val value = snapshot.value ?: return
            if (direction > 0 && value.canSkipNext) services.skipToNext()
            else if (direction < 0 && value.canSkipPrevious) services.skipToPrevious()
        } else if (direction > 0) queue.advanceNext() else queue.advancePrevious(currentPositionMs())
    }

    private fun seekDetached(position: Long) {
        if (snapshot.value?.canSeek == true) services.seekTo(position.coerceAtLeast(0))
    }

    private fun syncNativeRail() {
        val value = snapshot.value ?: return
        if (value.currentTrack?.catalogKind == CatalogKind.Station) return
        services.updateNativeUpcoming(value.requestGeneration,
            nativeUpcoming())
    }

    private fun ensureCollectionLoading() {
        if (collectionJob?.isActive == true) return
        val cursor = collection ?: return
        val session = collectionSessionId ?: return
        val epoch = collectionGeneration
        collectionJob = scope.launch {
            try {
                while (queue.state.value.sessionId == session && queue.state.value.contextContinuationPending) {
                    val page = cursor.loadNext() ?: break
                    ensureActive()
                    if (epoch != collectionGeneration || collection !== cursor || queue.state.value.sessionId != session) return@launch
                    val contextTimer = sleepTimer.value.option == SleepTimerOption.EndOfContext
                    val committed = page.shuffleSourceIndex?.let { sourceIndex ->
                        queue.appendBufferedShuffleTail(session, page.tracks, sourceIndex,
                            checkNotNull(page.shuffleAnchorEntryId), allowPlaybackAdvance = !contextTimer)
                    } ?: queue.appendContextTail(session, page.tracks, page.complete, allowPlaybackAdvance = !contextTimer)
                    if (!committed) return@launch
                    var live = queue.state.value
                    if (contextTimer) {
                        val ended = snapshot.value?.let { value -> value.isEnded &&
                            (value.appQueueEntryId == null || value.appQueueEntryId == live.current?.entryId) } == true
                        if ((ended || live.awaitingNext) && sleepTimerController.consumePlaybackEnd(live, live.current?.entryId)) {
                            pauseForSleepTimer()
                        } else if (live.awaitingNext && live.upNext.any { it.entryId in live.containerEntryIds }) {
                            // Mirror ordinary ready-page advancement, but only after
                            // the completed-container policy had a chance to stop it.
                            queue.advanceNext(manual = true)
                        }
                        live = queue.state.value
                    }
                    val hasUpcoming = live.upNext.isNotEmpty() || live.autoplay.isNotEmpty()
                    val wasWaiting = continuationResume.isPending
                    if (continuationResume.consumeIfReady(session, live.current?.entryId,
                            hasUpcoming, page.complete, mutableDesiredPlay.value)) {
                        queue.advanceNext(manual = false)
                    } else if (wasWaiting && page.complete && !hasUpcoming && !continuationResume.isPending) {
                        mutableDesiredPlay.value = false
                    }
                    saveSession()
                    if (page.complete) break
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == collectionGeneration && collection === cursor) {
                    val wasWaiting = continuationResume.isPending
                    continuationResume.cancel()
                    queue.failContextTail(session)
                    if (wasWaiting) mutableDesiredPlay.value = false
                }
            } finally {
                if (epoch == collectionGeneration) collectionJob = null
            }
        }
    }

    private fun clearContinuation(cancelResume: Boolean = true) {
        if (cancelResume) continuationResume.cancel()
        collectionGeneration++
        collectionJob?.cancel()
        collectionJob = null
        collection?.cancel()
        collection = null
        collectionSessionId = null
    }

    private fun saveSession(synchronous: Boolean = false) {
        val state = queue.state.value
        val current = state.current ?: return
        val entries = state.history + current + state.upNext + state.autoplay
        preferences.saveSession(PlaybackSession(
            queue = entries.map { it.track }, currentIndex = state.history.size,
            positionMs = currentPositionMs(), wasPlaying = mutableDesiredPlay.value,
            destination = uiDestination, previousDestination = uiPreviousDestination,
            radioActive = state.infinite, radioSeed = state.recommendationSeed,
            radioContinuation = state.ytmContinuation, autoplaySource = state.source,
            radioSeedVideoId = state.ytmSeedVideoId,
            autoplayEntryIndices = entries.indices.filter { entries[it].origin == EntryOrigin.Autoplay }.toSet(),
            queueEntryIds = entries.map { it.entryId }, playedEntryIds = entries.filter { it.wasPlayed }.map { it.entryId }.toSet(),
            queueSourceEntryIds = entries.map { it.sourceEntryId }, deferredLastEntryIds = state.deferredLastEntryIds,
            contextQueue = state.contextEntries.map { it.track }, contextEntryIds = state.contextEntries.map { it.entryId },
            unshuffledEntryIds = state.unshuffledUpNext.orEmpty().map { it.entryId },
            containerEntryIds = state.containerEntryIds, playbackContext = state.context,
            sessionId = state.sessionId, persistenceId = state.persistenceId,
            pendingCollection = pendingCollectionCheckpoint,
        ), synchronous)
    }

    private fun updateRetention() {
        if (isReleased) return
        idleJob?.cancel()
        val active = sleepTimer.value.option != SleepTimerOption.Off || mutableDesiredPlay.value || (serviceLeases.isNotEmpty() &&
            (snapshot.value?.isPlaying == true || snapshot.value?.isBuffering == true))
        val remaining = retention.remainingIdleMs(ui != null, active, SystemClock.elapsedRealtime()) ?: return
        idleJob = scope.launch { delay(remaining); releaseIfIdle() }
    }

    private fun releaseIfIdle() {
        val active = sleepTimer.value.option != SleepTimerOption.Off || mutableDesiredPlay.value || (serviceLeases.isNotEmpty() &&
            (snapshot.value?.isPlaying == true || snapshot.value?.isBuffering == true))
        if (ui != null || active || isReleased) return
        isReleased = true
        saveSession(synchronous = true)
        clearSleepTimer()
        clearContinuation()
        cancelPreparation(clearReady = true)
        services.setRuntimePlaybackListener(null)
        services.setRuntimeSystemPlaybackCallbacks(null)
        services.release()
        serviceLeases.clear()
        scope.cancel()
        onReleased(this)
    }

    private fun onMain(action: () -> Unit) {
        if (isReleased) return
        if (Looper.myLooper() == Looper.getMainLooper()) action() else scope.launch { if (!isReleased) action() }
    }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

/** The owner supplies identity and commits results; this I/O seam owns no jobs or playback state. */
internal data class PreparedRuntimeSelection(val track: Track, val generation: Long)

internal suspend fun prepareRuntimeSelection(
    request: PlaybackRuntimeOwner.PreparationRequest,
    resolve: suspend (Track) -> Track,
    prepare: suspend (Track, Long, Long) -> Long,
    isCurrent: (PlaybackRuntimeOwner.PreparationRequest) -> Boolean,
    awaitRetry: suspend () -> Unit = {},
): PreparedRuntimeSelection? {
    currentCoroutineContext().ensureActive()
    if (!isCurrent(request)) return null
    awaitRetry()
    currentCoroutineContext().ensureActive()
    if (!isCurrent(request)) return null
    val playable = resolve(request.track)
    currentCoroutineContext().ensureActive()
    if (!isCurrent(request)) return null
    val generation = prepare(playable, request.positionMs, request.entryId)
    currentCoroutineContext().ensureActive()
    if (!isCurrent(request)) return null
    return PreparedRuntimeSelection(playable, generation)
}
