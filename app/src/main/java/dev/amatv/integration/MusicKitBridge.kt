package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.content.Context
import android.view.Surface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class PlaybackRepeatMode {
    Off,
    One,
    All,
}

data class PlaybackSnapshot(
    val currentTrack: Track?,
    /** True only when the backing player, rather than a requested-track fallback, reports this item. */
    val hasPlayerCurrentTrack: Boolean = false,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val errorMessage: String? = null,
    val capturedAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    val isEnded: Boolean = false,
    val requestGeneration: Long = 0L,
    val isPrepared: Boolean = true,
    val failureKind: PlaybackFailureKind? = null,
    val isLiveStream: Boolean = false,
    val canSeek: Boolean = true,
    /** Stable app occurrence, bound through the observed native queue ID. */
    val appQueueEntryId: Long? = null,
    /** This request has naturally advanced within its bounded native rail. */
    val nativeQueueHandoff: Boolean = false,
    /** Same-generation terminal rejection; must reconcile before occurrence filtering. */
    val nativeQueueRejected: Boolean = false,
)

/**
 * Thin clean-room adapter around Apple-provided MusicKit AARs.
 * Reflection isolates version-specific controller methods. Developers supply
 * the licensed SDK in app/libs; no Apple binary is redistributed here.
 */
class MusicKitBridge(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var controller: Any? = null
    @Volatile private var controllerListener: Any? = null
    private var playbackThread: HandlerThread? = null
    private var playbackHandler: Handler? = null
    private var playbackListener: ((PlaybackSnapshot) -> Unit)? = null
    @Volatile
    private var requestedTrack: Track? = null
    @Volatile
    private var snapshotScheduled = false
    @Volatile private var pendingPlaybackError: String? = null
    @Volatile private var pendingFailureKind: PlaybackFailureKind? = null
    @Volatile private var desiredPlaying = false
    @Volatile private var activeGeneration = 0L
    @Volatile private var issuedPrepareGeneration = 0L
    private val playbackIntent = NativePlaybackIntent()
    private var lastLoggedPlaybackState: Int? = null
    private var lastLoggedTrackId: String? = null
    private var playbackDeveloperToken: String? = null
    private var playbackUserToken: String? = null
    private val prepareGeneration = AtomicLong(0L)
    @Volatile private var preparing = false
    @Volatile private var endedCatalogId: String? = null
    @Volatile private var preparedNativeQueueId: Long? = null
    private val nativeRail = NativeQueueRail()
    private var nativeEndWaitSince: Long? = null
    private var nativeEndWaitQueueId: Long? = null
    private var nativeEndWaitPositionMs = 0L
    private var railRetryAfterMs = 0L
    private var loggedRailEligibilityGeneration = Long.MIN_VALUE
    private val snapshotPoll = object : Runnable {
        override fun run() {
            if (playbackListener == null) return
            if (controller != null) dispatchSnapshot()
            mainHandler.postDelayed(this, 250L)
        }
    }

    val isInstalled: Boolean
        get() = classExists(AUTH_FACTORY) && classExists(PLAYER_FACTORY)

    val mediaCapabilities: NativeMediaCapabilities by lazy { NativeMediaCapabilities.inspect(context.classLoader) }
    @Volatile private var videoOutputSurface: Surface? = null

    fun playbackRoute(track: Track): NativeMediaRoute =
        mediaCapabilities.route(track.catalogKind, videoOutputSurface?.isValid == true)

    /** The caller owns the Surface and must detach it before destruction. */
    @Synchronized
    fun setVideoOutputSurface(surface: Surface?): Boolean {
        if (!mediaCapabilities.hasVideoOutputSurface) return false
        videoOutputSurface = surface?.takeIf { it.isValid }
        val current = controller ?: return true
        val handler = playbackHandler ?: return false
        // Surface destruction must not be discarded by a playback-generation
        // guard. Read the latest binding on the controller thread instead.
        return handler.post {
            if (controller === current) runCatching {
                current.javaClass.getMethod("setVideoOutputSurface", Surface::class.java)
                    .invoke(current, videoOutputSurface?.takeIf { it.isValid })
            }.onFailure { Log.w(TAG, "Video surface binding failed (${it.javaClass.simpleName})") }
        }
    }

    @Synchronized
    fun preparePlayback(
        track: Track,
        developerToken: String,
        userToken: String,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
        currentAppEntryId: Long? = null,
        upcoming: List<NativeQueueEntry> = emptyList(),
    ): Long {
        val route = playbackRoute(track)
        check(route !is NativeMediaRoute.Unavailable) {
            AppText.text(when (track.catalogKind) {
                CatalogKind.MusicVideo -> R.string.services_video_unsupported
                CatalogKind.Station -> R.string.services_station_unsupported
                else -> R.string.services_container_unsupported
            })
        }
        if (controller != null && (playbackDeveloperToken != developerToken || playbackUserToken != userToken)) {
            release()
        }
        val generation = prepareGeneration.incrementAndGet()
        ensurePlaybackRuntime()
        requestedTrack = track
        preparing = true
        endedCatalogId = null
        preparedNativeQueueId = null
        pendingPlaybackError = null
        pendingFailureKind = null
        desiredPlaying = playWhenReady
        playbackDeveloperToken = developerToken
        playbackUserToken = userToken
        val currentController = controller ?: createController(developerToken, userToken)
            .also { controller = it }
        val storeId = checkNotNull(track.catalogId) { AppText.text(R.string.services_catalog_id_missing) }
        if (playbackListener != null) {
            mainHandler.removeCallbacks(snapshotPoll)
            mainHandler.post(snapshotPoll)
        }
        postPlaybackAction("prepare") {
            if (generation != prepareGeneration.get()) return@postPlaybackAction
            activeGeneration = generation
            nativeRail.begin(generation,
                currentAppEntryId?.takeIf { it > 0L && track.catalogKind == CatalogKind.Song &&
                    storeId.matches(Regex("[1-9][0-9]*")) }?.let { NativeQueueEntry(it, track) }, upcoming)
            nativeEndWaitSince = null
            nativeEndWaitQueueId = null
            nativeEndWaitPositionMs = 0L
            railRetryAfterMs = 0L
            installRequestListener(currentController, generation)
            playbackIntent.begin(track.catalogId, positionMs, desiredPlaying, SystemClock.elapsedRealtime())
            val builderClass = Class.forName(QUEUE_BUILDER)
            val builder = builderClass.getConstructor().newInstance()
            when (route) {
                is NativeMediaRoute.Item -> {
                    if (route.requiresVideoSurface) {
                        val surface = checkNotNull(videoOutputSurface).also { check(it.isValid) }
                        currentController.javaClass.getMethod("setVideoOutputSurface", Surface::class.java)
                            .invoke(currentController, surface)
                    }
                    builderClass.getMethod("items", Int::class.javaPrimitiveType, Array<String>::class.java)
                        .invoke(builder, route.itemType, arrayOf(storeId))
                }
                is NativeMediaRoute.Container -> builderClass
                    .getMethod("containers", Int::class.javaPrimitiveType, Array<String>::class.java)
                    .invoke(builder, route.containerType, arrayOf(storeId))
                is NativeMediaRoute.Unavailable -> error(AppText.text(R.string.services_container_unsupported))
            }
            val provider = builderClass.getMethod("build").invoke(builder)
            // Never emit the beginning of a restored song. Readiness and any
            // resume seek are confirmed before the intent machine issues play().
            issuedPrepareGeneration = generation
            Log.i(TAG, "Prepare generation=$generation catalog=$storeId kind=${track.catalogKind} " +
                "source=${track.resourceType}:${track.resourceId} positionMs=$positionMs playWhenReady=$desiredPlaying " +
                "railEntry=${nativeRail.current?.entryId} railRequestedUpcoming=${upcoming.size} railEligibleUpcoming=${nativeRail.desiredCount}")
            // This is a new app context, including a replacement while paused.
            // The SDK's two-argument overload uses REPLACE(1), which rejects a
            // static provider when a native lookahead provider remains after
            // the current item. CLEAR_AND_REPLACE(5) explicitly replaces that
            // native rail; app Queue/history ownership remains unchanged.
            prepareNewNativeContext(currentController, provider)
        }
        return generation
    }

    /** Reuse a verified adjacent occurrence instead of discarding its native provider. */
    internal suspend fun prepareQueuedSuccessor(track: Track, developerToken: String, userToken: String,
        positionMs: Long, playWhenReady: Boolean, entryId: Long?): Long? {
        if (track.catalogKind != CatalogKind.Song || entryId == null || positionMs != 0L ||
            playbackDeveloperToken != developerToken || playbackUserToken != userToken) return null
        val handler = playbackHandler ?: return null
        val currentController = controller ?: return null
        val generation = prepareGeneration.get()
        return suspendCancellableCoroutine { result ->
            var issued = false
            var deadline = 0L
            lateinit var confirm: Runnable
            confirm = Runnable {
                if (!result.isActive) return@Runnable
                synchronized(this) {
                    if (!result.isActive) return@synchronized
                    if (generation != prepareGeneration.get() || generation != activeGeneration ||
                        currentController !== controller || pendingPlaybackError != null ||
                        playbackDeveloperToken != developerToken || playbackUserToken != userToken) {
                        result.resume(null)
                        return@synchronized
                    }
                    try {
                        if (!issued) {
                            val nextId = nativeRail.preparedSuccessorId(entryId, track.catalogId)
                            val nativeCurrent = currentController.callOrNull("getCurrentItem")
                                ?.callOrNull("getPlaybackQueueId") as? Number
                            @Suppress("UNCHECKED_CAST")
                            val following = currentController.callOrNull("getQueueItems") as? List<Any?>
                            val actualNext = following?.firstOrNull()?.callOrNull("getPlaybackQueueId") as? Number
                            val confirmedCurrent = currentController.callOrNull("getCurrentItem")
                                ?.callOrNull("getPlaybackQueueId") as? Number
                            if (nextId == null || actualNext?.toLong() != nextId ||
                                confirmedCurrent?.toLong() != nativeCurrent?.toLong() ||
                                nativeRail.entryForNativeId(nativeCurrent?.toLong())?.entryId != nativeRail.current?.entryId ||
                                currentController.callOrNull("canSkipToNextItem") != true) {
                                result.resume(null)
                                return@synchronized
                            }
                            desiredPlaying = playWhenReady
                            playbackIntent.requestPlaying(playWhenReady)
                            currentController.callVoid("pause")
                            currentController.callVoid("skipToNextItem")
                            issued = true
                            deadline = SystemClock.elapsedRealtime() + 1_000L
                        }
                        // Bind the actual handoff before acknowledging it. The new
                        // app future must not retire this pending occurrence early.
                        val value = readSnapshot(currentController, generation)
                        if (value.appQueueEntryId == entryId && value.errorMessage == null) {
                            Log.i(TAG, "Reused native successor generation=$generation entry=$entryId")
                            result.resume(generation)
                            dispatchSnapshot()
                        } else if (value.errorMessage != null || SystemClock.elapsedRealtime() >= deadline) {
                            result.resume(null) // Full replacement still targets this same app occurrence.
                        } else handler.postDelayed(confirm, 50L)
                    } catch (error: Exception) {
                        Log.w(TAG, "Native successor reuse unavailable (${error.javaClass.simpleName})")
                        result.resume(null)
                    }
                }
            }
            result.invokeOnCancellation { handler.removeCallbacks(confirm) }
            if (!handler.post(confirm)) result.resume(null)
        }
    }

    /**
     * Sync only this active request's next two known catalog occurrences. Actual
     * edits run on the existing controller handler, never on a second player.
     */
    @Synchronized
    fun updateUpcoming(requestGeneration: Long, entries: List<NativeQueueEntry>): Boolean {
        val handler = playbackHandler ?: return false
        if (requestGeneration != prepareGeneration.get() || controller == null) return false
        val snapshot = entries.toList()
        return handler.post {
            if (requestGeneration == prepareGeneration.get() && requestGeneration == activeGeneration &&
                nativeRail.updateUpcoming(requestGeneration, snapshot)) {
                railRetryAfterMs = 0L
                dispatchSnapshot()
            }
        }
    }

    fun play() {
        desiredPlaying = true
        val generation = prepareGeneration.get()
        playbackHandler?.post {
            if (generation != prepareGeneration.get()) return@post
            playbackIntent.requestPlaying(true)
            dispatchSnapshot()
        }
    }
    fun pause() {
        desiredPlaying = false
        val generation = prepareGeneration.get()
        playbackHandler?.post {
            if (generation != prepareGeneration.get()) return@post
            playbackIntent.requestPlaying(false)
            controller?.let { runCatching { it.callVoid("pause") } }
            dispatchSnapshot()
        }
    }
    fun seekTo(positionMs: Long) {
        val value = checkNotNull(controller) { AppText.text(R.string.services_playback_not_prepared) }
        postPlaybackAction("seekToPosition") {
            playbackIntent.requestSeek(positionMs)
            endedCatalogId = null
            nativeEndWaitSince = null
            nativeEndWaitQueueId = null
            nativeEndWaitPositionMs = 0L
            value.javaClass.getMethod("seekToPosition", Long::class.javaPrimitiveType).invoke(value, positionMs)
        }
    }
    fun skipToNext() = invokeController("skipToNextItem")
    fun skipToPrevious() = invokeController("skipToPreviousItem")
    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        playbackListener = listener
        mainHandler.removeCallbacks(snapshotPoll)
        if (listener != null) mainHandler.post(snapshotPoll)
        val currentController = controller
        if (listener != null && currentController != null && controllerListener == null) {
            val generation = prepareGeneration.get()
            playbackHandler?.post {
                if (currentController === controller && generation == prepareGeneration.get() &&
                    activeGeneration == generation
                ) {
                    installRequestListener(currentController, generation)
                    dispatchSnapshot()
                }
            }
        }
    }

    /** Includes SDK-restored queues; querying never prepares or constructs a renderer. */
    fun audioSessionId(): Int = if (controller != null) MusicKitAudioSession.currentId() else 0

    @Synchronized
    fun release() {
        val releasedGeneration = prepareGeneration.incrementAndGet()
        mainHandler.removeCallbacks(snapshotPoll)
        snapshotScheduled = false
        val currentController = controller
        val listener = controllerListener
        val oldHandler = playbackHandler
        val oldThread = playbackThread
        val cleanup = {
            if (currentController != null && listener != null) {
                runCatching {
                    currentController.javaClass.methods.first {
                        it.name == "removeListener" && it.parameterTypes.size == 1
                    }.invoke(currentController, listener)
                }
            }
            if (mediaCapabilities.hasVideoOutputSurface && currentController != null) {
                runCatching { currentController.javaClass.getMethod("setVideoOutputSurface", Surface::class.java).invoke(currentController, null) }
            }
            runCatching { currentController?.callVoid("release") }
            synchronized(this) {
                if (prepareGeneration.get() == releasedGeneration) {
                    nativeRail.reset()
                    nativeEndWaitSince = null
                    nativeEndWaitQueueId = null
                    nativeEndWaitPositionMs = 0L
                }
            }
            oldThread?.quitSafely()
            Unit
        }
        if (oldHandler == null || !oldHandler.post(cleanup)) cleanup()
        controllerListener = null
        controller = null
        pendingPlaybackError = null
        pendingFailureKind = null
        activeGeneration = 0L
        issuedPrepareGeneration = 0L
        desiredPlaying = false
        preparing = false
        endedCatalogId = null
        preparedNativeQueueId = null
        playbackDeveloperToken = null
        playbackUserToken = null
        playbackHandler = null
        playbackThread = null
    }

    private fun createController(developerToken: String, userToken: String): Any {
        check(isInstalled) { AppText.text(R.string.services_sdk_unavailable) }
        val providerClass = Class.forName(TOKEN_PROVIDER)
        val tokenProvider = MusicKitTokenProvider(developerToken, userToken)
        val factory = Class.forName(PLAYER_FACTORY)
        val createdController = runCatching {
            // Apple's bundled SDK test app creates its controller on a
            // dedicated HandlerThread. Keep callbacks and the legacy native
            // player off Compose's main thread as the reference app does.
            val worker = HandlerThread(
                "AMATV:MusicKit",
                Process.THREAD_PRIORITY_BACKGROUND,
            ).also {
                it.start()
                playbackThread = it
            }
            val handler = Handler(worker.looper).also { playbackHandler = it }
            factory.getMethod(
                "createLocalController",
                Context::class.java,
                Handler::class.java,
                providerClass,
            ).invoke(null, context, handler, tokenProvider).also {
                Log.i(TAG, "Created controller with the explicit-Handler overload.")
            }
        }.recoverCatching { handlerOverloadError ->
            playbackThread?.quitSafely()
            playbackThread = null
            playbackHandler = null
            Log.w(
                TAG,
                "Explicit-Handler controller overload failed; using main thread.",
                handlerOverloadError,
            )
            factory.getMethod(
                "createLocalController",
                Context::class.java,
                providerClass,
            ).invoke(null, context, tokenProvider).also {
                playbackHandler = mainHandler
                Log.i(TAG, "Created controller with the main-thread callback overload.")
            }
        }.getOrElse { error ->
            playbackThread?.quitSafely()
            playbackThread = null
            playbackHandler = null
            throw error
        }
        return checkNotNull(createdController) {
            AppText.text(R.string.services_controller_unavailable)
        }
    }

    /** Install on the controller's handler before prepare, retaining a request-specific epoch. */
    private fun installRequestListener(currentController: Any, generation: Long) {
        if (generation != prepareGeneration.get() || currentController !== controller) return
        controllerListener?.let { old ->
            currentController.javaClass.methods.first {
                it.name == "removeListener" && it.parameterTypes.size == 1
            }.invoke(currentController, old)
        }
        controllerListener = null
        if (generation != prepareGeneration.get()) return
        val listenerClass = Class.forName(CONTROLLER_LISTENER)
        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "AMATVPlaybackListener($generation)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> synchronized(this@MusicKitBridge) {
                    // The epoch belongs to this registration, not the mutable controller.
                    // Synchronize validation+writes with preparePlayback/release so an old
                    // callback cannot write an error after a new request has cleared it.
                    if (generation != prepareGeneration.get() || activeGeneration != generation ||
                        issuedPrepareGeneration != generation || currentController !== controller
                    ) return@synchronized null
                    when (method.name) {
                        "onPlaybackError" -> {
                            val nativeCurrent = currentController.callOrNull("getCurrentItem")?.let(::trackFromQueueItem)
                            if (nativeCurrent == null || nativeCurrent.catalogId == requestedTrack?.catalogId) {
                                val error = args?.lastOrNull()
                                val firstFailure = pendingPlaybackError == null
                                pendingPlaybackError = describePlaybackError(error)
                                pendingFailureKind = classifyNativeFailure(error)
                                if (firstFailure) {
                                    fun errorNumber(name: String): Int? = runCatching {
                                        (error?.javaClass?.getMethod(name)?.invoke(error) as? Number)?.toInt()
                                    }.getOrNull()
                                    val errorType = errorNumber("getType")
                                    val errorCode = errorNumber("getErrorCode")
                                    val componentCode = errorNumber("getComponentErrorCode")
                                    val causes = generateSequence(error as? Throwable) { it.cause }.take(6).toList()
                                    val causeTypes = causes.joinToString(">") { it.javaClass.simpleName }
                                    val origin = causes.lastOrNull()?.stackTrace?.firstOrNull {
                                        it.className.startsWith("com.apple.android.music.")
                                    }?.let { "${it.className}.${it.methodName}" }
                                    val explicitAllowed = context.getSharedPreferences(
                                        "com.apple.android.music.playback_preferences", Context.MODE_PRIVATE)
                                        .getBoolean("explicit_content_allowed", true)
                                    Log.w(TAG, "Native playback failure generation=$generation " +
                                        "type=$errorType code=$errorCode component=$componentCode kind=$pendingFailureKind " +
                                        "causes=$causeTypes origin=$origin explicitAllowed=$explicitAllowed")
                                }
                                dispatchSnapshot()
                            }
                        }
                        "onItemEnded" -> {
                            val endedTrack = args?.getOrNull(1)?.let(::trackFromQueueItem)
                            val endPosition = args?.getOrNull(2) as? Long ?: 0L
                            if (isCompletedPlaybackItem(
                                eventGeneration = generation,
                                requestGeneration = prepareGeneration.get(),
                                prepared = !preparing,
                                requestedCatalogId = requestedTrack?.catalogId,
                                endedCatalogId = endedTrack?.catalogId,
                                expectedNativeQueueId = preparedNativeQueueId,
                                endedNativeQueueId = endedTrack?.playbackQueueId,
                                endPositionMs = endPosition,
                                durationMs = endedTrack?.durationMs ?: 0L,
                            )) {
                                if (desiredPlaying && nativeRail.hasReadySuccessor()) {
                                    nativeEndWaitSince = SystemClock.elapsedRealtime()
                                    nativeEndWaitQueueId = endedTrack?.playbackQueueId
                                    nativeEndWaitPositionMs = endPosition.coerceAtLeast(0L)
                                } else endedCatalogId = endedTrack?.catalogId
                            }
                            dispatchSnapshot()
                        }
                        else -> if (method.name.startsWith("on")) {
                            Log.d(TAG, method.name)
                            dispatchSnapshot()
                        }
                    }
                    null
                }
            }
        }
        currentController.javaClass.methods.first {
            it.name == "addListener" && it.parameterTypes.size == 1
        }.invoke(currentController, listener)
        synchronized(this) {
            if (generation == prepareGeneration.get() && currentController === controller) {
                controllerListener = listener
            } else {
                runCatching {
                    currentController.javaClass.methods.first {
                        it.name == "removeListener" && it.parameterTypes.size == 1
                    }.invoke(currentController, listener)
                }
            }
        }
    }

    private fun dispatchSnapshot() {
        if (playbackListener == null || snapshotScheduled) return
        snapshotScheduled = true
        val generation = prepareGeneration.get()
        val readAndDeliver: () -> Unit = readAndDeliver@{
            snapshotScheduled = false
            val snapshot = synchronized(this) {
                if (generation != prepareGeneration.get() || generation != activeGeneration) null
                else controller?.let { readSnapshot(it, generation) }
            } ?: return@readAndDeliver
            mainHandler.post {
                if (generation == prepareGeneration.get()) playbackListener?.invoke(snapshot)
            }
            Unit
        }
        val handler = playbackHandler
        if (handler == null) mainHandler.postDelayed(readAndDeliver, SNAPSHOT_DELAY_MS)
        else handler.postDelayed(readAndDeliver, SNAPSHOT_DELAY_MS)
    }

    private fun readSnapshot(currentController: Any, generation: Long): PlaybackSnapshot {
        val currentBeforeWindow = currentController.callOrNull("getCurrentItem")
        @Suppress("UNCHECKED_CAST")
        val followingQueueItems = currentController.callOrNull("getQueueItems") as? List<Any?>
        val currentQueueItem = currentController.callOrNull("getCurrentItem")
        fun observation(item: Any?): NativeQueueObservation? {
            item ?: return null
            val id = (item.callOrNull("getPlaybackQueueId") as? Number)?.toLong() ?: return null
            val catalogId = item.callOrNull("getItem")?.callOrNull("getSubscriptionStoreId") as? String
            return NativeQueueObservation(id, catalogId)
        }
        // SDK getQueueItems() is a bounded FUTURE-only window. An empty result
        // after a single-item prepare is correct, not an absent current anchor.
        val currentObservation = observation(currentQueueItem)
        val stableCurrent = observation(currentBeforeWindow)?.nativeId == currentObservation?.nativeId
        // SDK queue/current getters are separate reads. If a handoff happened
        // between them, combining the two windows could misbind duplicates.
        val followingObservations = if (stableCurrent) followingQueueItems.orEmpty().map(::observation) else emptyList()
        val completeObservationWindow = stableCurrent && followingQueueItems != null && followingObservations.all { it != null }
        // Do not skip an unreadable occurrence and accidentally bind a later
        // duplicate as the expected next item. Incomplete windows cannot edit.
        val observations = nativeQueueObservationWindow(currentObservation,
            followingObservations.takeWhile { it != null }.filterNotNull())
        val nativeId = currentObservation?.nativeId
        val position = (currentController.callOrNull("getCurrentPosition") as? Long)
            ?.coerceAtLeast(0L) ?: 0L
        val adoption = nativeRail.observe(observations, nativeId)
        if (adoption?.rejected == true) {
            // A stale async provider must never make a removed/reordered item
            // silently become the app's current selection.
            runCatching { currentController.callVoid("pause") }
            pendingFailureKind = PlaybackFailureKind.Unexpected
            pendingPlaybackError = AppText.text(R.string.services_playback_failed)
        } else if (adoption?.changed == true) {
            requestedTrack = adoption.entry.track
            endedCatalogId = null
            nativeEndWaitSince = null
            nativeEndWaitQueueId = null
            nativeEndWaitPositionMs = 0L
            preparedNativeQueueId = nativeId
            // The SDK has already adopted/started this occurrence. Seed its
            // observed position rather than seeking the new song back to zero.
            playbackIntent.adoptPrepared(adoption.entry.track.catalogId, position, desiredPlaying, SystemClock.elapsedRealtime())
            Log.i(TAG, "Native rail handoff generation=$generation entry=${adoption.entry.entryId} native=$nativeId")
        }
        val currentTrack = currentQueueItem?.let(::trackFromQueueItem) ?: requestedTrack
        val controllerDuration = (currentController.callOrNull("getDuration") as? Long)
            ?.takeIf { it > 0L }
        val playbackState = currentController.callOrNull("getPlaybackState") as? Int ?: 0
        val playbackRate = currentController.callOrNull("getPlaybackRate") as? Float ?: 0f
        val buffering = currentController.callOrNull("isBuffering") as? Boolean ?: false
        val waitingSince = nativeEndWaitSince
        val waitingForNativeNext = waitingSince != null &&
            (nativeId == nativeEndWaitQueueId || currentQueueItem == null) &&
            SystemClock.elapsedRealtime() - waitingSince < NATIVE_HANDOFF_TIMEOUT_MS
        if (waitingSince != null && !waitingForNativeNext && adoption?.changed != true) {
            // If this SDK route does not advance its prepared queue, restore
            // the existing app-driven end path rather than waiting forever.
            endedCatalogId = requestedTrack?.catalogId
            nativeEndWaitSince = null
            nativeEndWaitQueueId = null
            nativeEndWaitPositionMs = 0L
        }
        val nativeEnded = !preparing && endedCatalogId != null && endedCatalogId == currentTrack?.catalogId
        val decision = if (waitingForNativeNext) NativePlaybackDecision(
            prepared = true, playing = false, positionMs = maxOf(position, nativeEndWaitPositionMs),
        ) else playbackIntent.observe(NativePlaybackProbe(
            catalogId = currentTrack?.catalogId,
            hasCurrentItem = currentQueueItem != null,
            positionMs = position,
            durationMs = controllerDuration ?: currentTrack?.durationMs ?: 0L,
            playing = playbackState == PLAYBACK_STATE_PLAYING && playbackRate > 0f,
            buffering = buffering,
            canSeek = currentController.callOrNull("canSeek") as? Boolean ?: false,
            paused = playbackState == 2,
            ended = nativeEnded,
            nowMs = SystemClock.elapsedRealtime(),
        ))
        if (pendingPlaybackError == null) {
            for (command in decision.commands) {
                runCatching {
                    when (command) {
                        NativePlaybackCommand.Play -> currentController.callVoid("play")
                        NativePlaybackCommand.Pause -> currentController.callVoid("pause")
                        is NativePlaybackCommand.Seek -> currentController.javaClass.getMethod(
                            "seekToPosition", Long::class.javaPrimitiveType,
                        ).invoke(currentController, command.positionMs)
                    }
                }.onFailure {
                    pendingPlaybackError = describePlaybackError(it)
                    pendingFailureKind = PlaybackFailureKind.Unexpected
                }
            }
            if (decision.failure != null) {
                // Log once when the native queue/readiness watchdog fails. The
                // UI message alone cannot distinguish an unadopted queue from
                // buffering, a mismatched item, or a failed position restore.
                Log.w(TAG, "Native readiness failure generation=$generation requested=${requestedTrack?.catalogId} " +
                    "native=${currentTrack?.catalogId} hasItem=${currentQueueItem != null} " +
                    "state=$playbackState rate=$playbackRate buffering=$buffering " +
                    "positionMs=$position queue=${observations.take(5).map { it.catalogId }} reason=${decision.failure}")
                pendingFailureKind = decision.failure
                pendingPlaybackError = when (decision.failure) {
                    PlaybackFailureKind.PositionRestore -> AppText.text(R.string.services_playback_restore_failed)
                    else -> AppText.text(R.string.services_song_connection_failed)
                }
            }
        }
        preparing = !decision.prepared
        if (decision.prepared && currentQueueItem != null && currentTrack?.catalogId == requestedTrack?.catalogId) {
            preparedNativeQueueId = currentTrack?.playbackQueueId
        }
        if (decision.playing && playbackIntent.hasStartedPlayback &&
            pendingPlaybackError == null && !waitingForNativeNext) {
            synchronizeNativeRail(currentController, observations, nativeId, completeObservationWindow)
        }
        if (lastLoggedPlaybackState != playbackState || lastLoggedTrackId != currentTrack?.id) {
            Log.i(
                TAG,
                "Snapshot state=$playbackState rate=$playbackRate " +
                    "buffering=$buffering positionMs=$position track=${currentTrack?.id}",
            )
            lastLoggedPlaybackState = playbackState
            if (lastLoggedTrackId != currentTrack?.id) {
                logAvailableTimedTextTracks(currentController)
            }
            lastLoggedTrackId = currentTrack?.id
        }
        return PlaybackSnapshot(
            currentTrack = currentTrack,
            hasPlayerCurrentTrack = currentQueueItem != null,
            positionMs = decision.positionMs,
            durationMs = controllerDuration ?: currentTrack?.durationMs ?: 1L,
            isPlaying = decision.playing && pendingPlaybackError == null,
            isBuffering = buffering || waitingForNativeNext,
            canSkipNext = currentController.callOrNull("canSkipToNextItem") as? Boolean ?: false,
            canSkipPrevious = currentController.callOrNull("canSkipToPreviousItem") as? Boolean ?: false,
            errorMessage = pendingPlaybackError,
            isEnded = decision.ended,
            requestGeneration = generation,
            isPrepared = decision.prepared,
            failureKind = pendingFailureKind,
            isLiveStream = currentController.callOrNull("isLiveStream") as? Boolean ?: false,
            canSeek = currentController.callOrNull("canSeek") as? Boolean ?: false,
            appQueueEntryId = nativeRail.entryForNativeId(nativeId)?.entryId,
            nativeQueueHandoff = nativeRail.handedOff,
            nativeQueueRejected = adoption?.rejected == true,
        )
    }

    private fun synchronizeNativeRail(currentController: Any, queue: List<NativeQueueObservation>, nativeId: Long?, completeWindow: Boolean) {
        if (SystemClock.elapsedRealtime() < railRetryAfterMs) return
        val canEdit = currentController.callOrNull("canEditPlaybackQueue") as? Boolean
        val canAppend = currentController.callOrNull("canAppendToPlaybackQueue") as? Boolean
        if (loggedRailEligibilityGeneration != nativeRail.generation) {
            loggedRailEligibilityGeneration = nativeRail.generation
            val currentEntry = nativeRail.current?.entryId
            val boundEntry = nativeRail.entryForNativeId(nativeId)?.entryId
            // One prepared sample per request. null distinguishes an unavailable
            // SDK read from false; a false first sample is not a capability verdict.
            // Only occurrence IDs/counts are logged, never tokens or provider URLs.
            Log.i(TAG, "Native rail eligibility generation=${nativeRail.generation} " +
                "entry=$currentEntry upcoming=${nativeRail.desiredCount} observed=${queue.size} " +
                "hasNativeCurrent=${nativeId != null} boundCurrent=${currentEntry != null && currentEntry == boundEntry} " +
                "completeWindow=$completeWindow canEdit=$canEdit canAppend=$canAppend")
        }
        if (!completeWindow || canEdit != true || canAppend != true) return
        val edit = nativeRail.edit(queue, nativeId) ?: return
        try {
            for (removed in edit.removeIds) {
                val actualCurrent = (currentController.callOrNull("getCurrentItem")?.callOrNull("getPlaybackQueueId") as? Number)?.toLong()
                if (actualCurrent != nativeId || removed == actualCurrent) return
                currentController.javaClass.getMethod("removeQueueItemWithId", Long::class.javaPrimitiveType)
                    .invoke(currentController, removed)
            }
            if (edit.append.isNotEmpty()) {
                val builderClass = Class.forName(QUEUE_BUILDER)
                val builder = builderClass.getConstructor().newInstance()
                builderClass.getMethod("items", Int::class.javaPrimitiveType, Array<String>::class.java)
                    .invoke(builder, checkNotNull(mediaCapabilities.songItemType), edit.append.map { checkNotNull(it.track.catalogId) }.toTypedArray())
                val provider = builderClass.getMethod("build").invoke(builder)
                currentController.javaClass.methods.first { it.name == "addQueueItems" && it.parameterTypes.size == 2 }
                    .invoke(currentController, provider, INSERTION_TYPE_AT_END)
            }
            nativeRail.committed(edit)
            Log.i(TAG, "Native rail sync generation=${nativeRail.generation} current=${nativeRail.current?.entryId} " +
                "remove=${edit.removeIds.size} append=${edit.append.map { it.entryId }}")
        } catch (error: Exception) {
            // Prefetch is optional. Do not interrupt valid current audio just
            // because this route cannot append; the legacy end path remains.
            railRetryAfterMs = SystemClock.elapsedRealtime() + 5_000L
            Log.w(TAG, "Native queue preparation unavailable (${error.javaClass.simpleName})")
        }
    }

    private fun trackFromQueueItem(queueItem: Any): Track? {
        val item = queueItem.callOrNull("getItem") ?: return null
        val storeId = item.callOrNull("getSubscriptionStoreId") as? String
        val title = (item.callOrNull("getTitle") as? String).orEmpty().ifBlank { "Apple Music" }
        val artist = (item.callOrNull("getArtistName") as? String).orEmpty()
            .ifBlank { "Apple Music" }
        val album = (item.callOrNull("getAlbumTitle") as? String).orEmpty().ifBlank { AppText.text(R.string.services_kind_song) }
        val artwork = item.javaClass.methods.firstOrNull {
            it.name == "getArtworkUrl" && it.parameterTypes.size == 2
        }?.invoke(item, 600, 600) as? String
        val duration = (item.callOrNull("getDuration") as? Long)?.takeIf { it > 0L }
            ?: requestedTrack?.durationMs ?: 1L
        val nativeId = (queueItem.callOrNull("getPlaybackQueueId") as? Number)?.toLong()
        val fallback = nativeRail.entryForNativeId(nativeId)?.track
            ?: nativeRail.plannedTracks.firstOrNull { it.catalogId == storeId }
            ?: requestedTrack?.takeIf { requested -> requested.catalogId == storeId || requested.title == title }
        val identity = storeId?.takeIf(String::isNotBlank)
            ?: "queue:${queueItem.callOrNull("getPlaybackQueueId") ?: title.hashCode()}"
        return Track(
            id = "apple:$identity",
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            palette = fallback?.palette ?: playbackPalette(identity),
            lyrics = fallback?.lyrics.orEmpty(),
            catalogId = storeId,
            catalogKind = if (mediaCapabilities.musicVideoItemType != null &&
                item.callOrNull("getType") == mediaCapabilities.musicVideoItemType) CatalogKind.MusicVideo else CatalogKind.Song,
            resourceId = fallback?.resourceId,
            resourceType = fallback?.resourceType,
            artworkUrl = artwork?.takeIf { it.startsWith("https://") } ?: fallback?.artworkUrl,
            hasLyricsAvailable = item.callOrNull("hasLyricsAvailable") as? Boolean
                ?: fallback?.hasLyricsAvailable
                ?: false,
            playbackQueueId = queueItem.callOrNull("getPlaybackQueueId") as? Long,
        )
    }

    private fun playbackPalette(seed: String): List<Color> {
        val palettes = listOf(
            listOf(Color(0xFF361044), Color(0xFF9D205D), Color(0xFFE06F66)),
            listOf(Color(0xFF102B46), Color(0xFF176E80), Color(0xFFC99A58)),
            listOf(Color(0xFF24203E), Color(0xFF56529C), Color(0xFFE2746B)),
            listOf(Color(0xFF12382F), Color(0xFF348563), Color(0xFFD0C348)),
        )
        return palettes[(seed.hashCode() and Int.MAX_VALUE) % palettes.size]
    }

    private fun Any.callOrNull(name: String): Any? = runCatching {
        javaClass.methods.first { it.name == name && it.parameterTypes.isEmpty() }.invoke(this)
    }.getOrNull()

    /**
     * MusicKit's hosted Javadoc documents timed-text selection, but the
     * currently downloadable 1.1.1 playback AAR omits those public methods.
     * The bundled player still keeps a track-info set internally. Reading its
     * metadata lets us distinguish a real text track from a catalog-only
     * `hasLyricsAvailable` flag without requesting any private service API.
     */
    private fun logAvailableTimedTextTracks(currentController: Any) {
        runCatching {
            val coreField = currentController.javaClass.getDeclaredField("b").apply {
                isAccessible = true
            }
            val core = coreField.get(currentController) ?: return@runCatching
            val tracksField = core.javaClass.getDeclaredField("t").apply {
                isAccessible = true
            }
            val tracks = tracksField.get(core) as? Set<*> ?: emptySet<Any>()
            val summary = tracks.joinToString(prefix = "[", postfix = "]") { track ->
                if (track == null) return@joinToString "null"
                val values = listOf("a", "b", "c").map { name ->
                    runCatching {
                        track.javaClass.getDeclaredMethod(name).invoke(track)
                    }.getOrNull()
                }
                "type=${values[0]}, id=${values[1]}, language=${values[2]}"
            }
            Log.i(TAG, "MusicKit available media tracks: $summary")
        }.onFailure {
            Log.d(TAG, "MusicKit track metadata is unavailable in this SDK build.")
        }
    }

    private fun invokeController(name: String, vararg args: Any) {
        val value = checkNotNull(controller) { AppText.text(R.string.services_playback_not_prepared) }
        postPlaybackAction(name) {
            val method = value.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            } ?: error(AppText.text(R.string.services_controller_command_unavailable, name))
            Log.i(TAG, "Controller command: $name")
            method.invoke(value, *args)
        }
    }

    private fun postPlaybackAction(name: String, action: () -> Unit) {
        val handler = checkNotNull(playbackHandler) {
            AppText.text(R.string.services_playback_thread_unavailable)
        }
        val generation = prepareGeneration.get()
        handler.post {
            if (generation != prepareGeneration.get()) return@post
            try {
                action()
                dispatchSnapshot()
            } catch (error: Throwable) {
                synchronized(this) {
                    if (generation != prepareGeneration.get()) return@synchronized
                    val cause = (error as? InvocationTargetException)?.targetException ?: error
                    pendingPlaybackError = describePlaybackError(cause)
                    pendingFailureKind = if (requestedTrack?.catalogKind in setOf(CatalogKind.MusicVideo, CatalogKind.Station)) {
                        PlaybackFailureKind.TrackUnavailable
                    } else classifyNativeFailure(cause)
                    Log.e(TAG, "MusicKit $name failed (${cause.javaClass.simpleName})")
                    dispatchSnapshot()
                }
            }
        }
    }

    private fun Any.callVoid(name: String) {
        javaClass.methods.first {
            it.name == name && it.parameterTypes.isEmpty()
        }.invoke(this)
    }

    private fun ensurePlaybackRuntime() {
        if (runtimeLoaded) return
        synchronized(MusicKitBridge::class.java) {
            if (runtimeLoaded) return
            // Apple's official sample requires this initialization before the
            // first MediaPlayerController is created.
            System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "0")
            System.setProperty("org.bytedeco.javacpp.maxbytes", "0")
            System.loadLibrary("c++_shared")
            System.loadLibrary("appleMusicSDK")
            runtimeLoaded = true
        }
    }

    private fun classifyNativeFailure(error: Any?): PlaybackFailureKind {
        fun number(name: String): Int? = runCatching {
            error?.javaClass?.getMethod(name)?.invoke(error) as? Int
        }.getOrNull()
        return classifyPlaybackFailure(number("getType"), number("getErrorCode"),
            number("getComponentErrorCode"), error as? Throwable)
    }

    private fun describePlaybackError(error: Any?): String = when (classifyNativeFailure(error)) {
        PlaybackFailureKind.TrackUnavailable -> AppText.text(R.string.services_track_unavailable)
        PlaybackFailureKind.Authorization -> AppText.text(R.string.services_playback_auth_required)
        PlaybackFailureKind.Network -> AppText.text(if (playbackCertificateFailure(error as? Throwable))
            R.string.services_connection_verification_failed else R.string.services_apple_connection_failed)
        PlaybackFailureKind.PositionRestore -> AppText.text(R.string.services_playback_restore_failed)
        PlaybackFailureKind.Unexpected -> AppText.text(R.string.services_playback_failed)
    }

    private fun classExists(name: String): Boolean = runCatching {
        Class.forName(name)
    }.isSuccess

    private companion object Constants {
        const val AUTH_FACTORY = "com.apple.android.sdk.authentication.AuthenticationFactory"
        const val TOKEN_PROVIDER = "com.apple.android.sdk.authentication.TokenProvider"
        const val PLAYER_FACTORY =
            "com.apple.android.music.playback.controller.MediaPlayerControllerFactory"
        const val CONTROLLER_LISTENER =
            "com.apple.android.music.playback.controller.MediaPlayerController\$Listener"
        const val QUEUE_BUILDER =
            "com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider\$Builder"
        const val PLAYBACK_STATE_PLAYING = 1
        const val SNAPSHOT_DELAY_MS = 80L
        const val INSERTION_TYPE_AT_END = 2 // Public PlaybackQueueInsertionType in the bundled AAR/Javadoc.
        const val NATIVE_HANDOFF_TIMEOUT_MS = 5_000L
        const val TAG = "AMATV-MusicKit"
        @Volatile
        var runtimeLoaded = false
    }

}
