package dev.amatv.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.concurrent.TimeoutException

/** Main-thread presentation state only. The decorative renderer never escapes its worker. */
internal class MotionPreviewLease(val owner: Any, val url: String, val mediaId: String) {
    var retainSurface: (() -> Unit)? = null
    var firstFrameReady by mutableStateOf(false)
        internal set
    var failed by mutableStateOf(false)
        internal set
    internal var videoAspect: Float? = null
    internal var bindingGeneration = 0L
}

/** Raw output identity; its release callback belongs to the main-thread TextureView. */
internal class MotionPreviewOutput internal constructor(
    internal val lease: MotionPreviewLease,
    internal val generation: Long,
    internal val surface: Surface,
    internal val onAspect: (Float?) -> Unit,
    internal val onCleared: () -> Unit,
) {
    // Worker-only. Every output is acknowledged exactly once, after native clear.
    internal val lifecycle = MotionOutputLifecycle()
    internal val acknowledged: Boolean get() = lifecycle.released
}

/**
 * Main owns candidate selection and Compose state. One dedicated application
 * looper owns ALL ExoPlayer operations, including blocking surface acknowledgments
 * and codec release. The player's separate internal playback looper stays Media3's.
 */
internal object MotionPreviewCoordinator {
    private data class Request(val context: Context, val url: String, val width: Int, val height: Int)
    private data class Selection(val mediaId: String, val request: Request)
    private data class ReleasingRenderer(val renderer: ExoPlayer, val playbackThread: Thread,
        val binding: MotionPreviewOutput?)
    private val requests = linkedMapOf<Any, Request>()
    private val main = Handler(Looper.getMainLooper())
    private val controlThread = HandlerThread("AMATV-motion-control").apply { start() }
    private val control = Handler(controlThread.looper)
    private var nextMediaId = 0L
    private var nextBindingGeneration = 0L
    @Volatile private var desiredMediaId: String? = null
    @Volatile private var desiredOutput: MotionPreviewOutput? = null
    @Volatile private var desiredSelection: Selection? = null

    // Accessed only on controlThread, never from TextureView or Compose.
    private var player: ExoPlayer? = null
    private var playingMediaId: String? = null
    private var output: MotionPreviewOutput? = null
    private var releasing: ReleasingRenderer? = null
    private var handlingFailure = false
    private val releaseIdle = Runnable {
        if (desiredMediaId == null && playingMediaId == null) {
            if (!clearOutputOnControl()) return@Runnable
            releasePlayerOnControl()
        }
    }
    private val checkRelease = object : Runnable {
        override fun run() {
            val pending = releasing ?: return
            if (pending.playbackThread.isAlive) { control.postDelayed(this, 250L); return }
            // A VOID release return is not proof. Actual playback-thread death
            // is the last-resort public witness after an exceptional detach.
            pending.binding?.let { binding ->
                binding.lifecycle.playbackThreadTerminated()
                if (output === binding) output = null
                acknowledgeOnControl(binding)
            }
            if (player === pending.renderer) { player = null; playingMediaId = null }
            releasing = null
            control.post(::drainSelectionOnControl)
        }
    }
    var lease by mutableStateOf<MotionPreviewLease?>(null)
        private set

    fun request(owner: Any, context: Context, url: String, width: Int, height: Int) {
        checkMain()
        val request = Request(context.applicationContext, url, width, height)
        if (requests[owner] == request) return
        requests[owner] = request
        selectOwner()
    }

    fun withdraw(owner: Any) {
        checkMain()
        if (requests.remove(owner) != null) selectOwner()
    }

    private fun selectOwner() {
        val next = requests.entries.lastOrNull()
        val current = lease
        control.removeCallbacks(releaseIdle)
        if (current != null && (next == null || current.owner !== next.key || current.url != next.value.url)) {
            // UI retains its mounted GPU buffer synchronously; only the native
            // disconnect is queued. No future stream can reach it before clear.
            current.retainSurface?.invoke()
        }
        if (next == null) {
            desiredMediaId = null
            desiredSelection = null
            desiredOutput = null
            lease = null
            control.post(::drainSelectionOnControl)
            return
        }
        val request = next.value
        if (current?.owner === next.key && current.url == request.url) {
            desiredSelection = Selection(current.mediaId, request)
            control.post(::drainSelectionOnControl)
            return
        }
        val mediaId = "amatv-motion:${++nextMediaId}"
        val incoming = MotionPreviewLease(next.key, request.url, mediaId)
        desiredMediaId = mediaId
        desiredSelection = Selection(mediaId, request)
        desiredOutput = null
        lease = incoming
        control.post(::drainSelectionOnControl)
    }

    /** One latest selection, never one queued player per delayed owner. */
    private fun drainSelectionOnControl() {
        if (releasing != null) return
        val selected = desiredSelection
        if (selected == null) {
            if (desiredMediaId != null || !clearOutputOnControl()) return
            runCatching { player?.apply { pause(); stop(); clearMediaItems() } }
            playingMediaId = null
            control.removeCallbacks(releaseIdle)
            control.postDelayed(releaseIdle, 350L)
            return
        }
        if (desiredMediaId != selected.mediaId) return
        try {
            val request = selected.request
            if (playingMediaId != selected.mediaId) {
                if (!clearOutputOnControl() || releasing != null) return
                if (desiredMediaId != selected.mediaId) return
                player?.apply { pause(); stop(); clearMediaItems() }
                playingMediaId = null
                val renderer = player ?: createPlayer(request.context).also { player = it }
                playingMediaId = selected.mediaId
                constrainVideo(renderer, request)
                renderer.setMediaSource(MotionVideoCache.mediaSource(request.context,
                    MediaItem.Builder().setUri(request.url).setMediaId(selected.mediaId).build()))
                renderer.prepare()
                renderer.playWhenReady = true
            } else player?.let { constrainVideo(it, request) }
            desiredOutput?.takeIf { wanted(it) && output !== it }?.let(::attachOutputOnControl)
        } catch (failure: Exception) { failOnControl(selected.mediaId, failure) }
    }

    /** Called only by the UI's SurfaceTexture listener; no View is passed to Media3. */
    fun bindOutput(current: MotionPreviewLease, surface: Surface,
        onAspect: (Float?) -> Unit, onCleared: () -> Unit): MotionPreviewOutput {
        checkMain()
        val binding = MotionPreviewOutput(current, ++nextBindingGeneration, surface, onAspect, onCleared)
        if (lease === current && desiredMediaId == current.mediaId) {
            current.bindingGeneration = binding.generation
            current.firstFrameReady = false
            desiredOutput = binding
        }
        control.post { attachOutputOnControl(binding) }
        return binding
    }

    private fun attachOutputOnControl(binding: MotionPreviewOutput) {
        if (output === binding) return
        if (!wanted(binding)) { acknowledgeOnControl(binding); return }
        // Keep this single desired, never-attached output while the prior codec
        // acknowledges detach. A late ACK drains the newest owner and binds it.
        if (releasing != null || playingMediaId != binding.lease.mediaId || player == null) return
        try {
            if (!clearOutputOnControl()) return
            if (!wanted(binding)) { acknowledgeOnControl(binding); return }
            binding.lifecycle.attached()
            output = binding
            player?.setVideoSurface(binding.surface)
        } catch (failure: Exception) {
            failOnControl(binding.lease.mediaId, failure)
            if (output !== binding) acknowledgeOnControl(binding)
        }
    }

    /** Exact output identity prevents a late old View release from clearing a new owner. */
    fun detachOutput(binding: MotionPreviewOutput) {
        checkMain()
        if (desiredOutput === binding) desiredOutput = null
        control.post {
            if (output === binding) clearOutputOnControl()
            else acknowledgeOnControl(binding)
        }
    }

    private fun wanted(binding: MotionPreviewOutput) = desiredOutput === binding &&
        desiredMediaId == binding.lease.mediaId && !binding.acknowledged

    private fun clearOutputOnControl(): Boolean {
        val previous = output ?: return true
        if (!previous.lifecycle.beginClear()) return false
        val renderer = player ?: return false
        try {
            // Media3 may report a detach timeout via onPlayerError and return
            // normally. Its application-visible videoOutput is then already
            // null, so a reentrant second clear would be a misleading no-op.
            renderer.clearVideoSurface(previous.surface)
            val marker = renderer.createMessage { _, _ ->
                control.post { finishDetachOnControl(previous) }
            }.send() // Default target is the public playback looper, not control.
            if (marker.blockUntilDelivered(1_000L)) finishDetachOnControl(previous)
            else Log.w("AMATV-Motion", "Editorial detach marker was not delivered")
        } catch (_: TimeoutException) {
            Log.w("AMATV-Motion", "Editorial detach awaits a late playback acknowledgment")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w("AMATV-Motion", "Editorial detach acknowledgment interrupted")
        } catch (failure: Exception) {
            Log.w("AMATV-Motion", "Editorial output clear failed: ${failure.javaClass.simpleName}")
            releasePlayerOnControl()
        }
        return previous.acknowledged
    }

    private fun finishDetachOnControl(binding: MotionPreviewOutput) {
        if (!binding.lifecycle.playbackMarkerDelivered()) return
        if (output === binding) output = null
        acknowledgeOnControl(binding)
        control.post(::drainSelectionOnControl)
    }

    private fun releasePlayerOnControl() {
        if (releasing != null) return
        val renderer = player ?: return
        val thread = renderer.playbackLooper.thread
        releasing = ReleasingRenderer(renderer, thread, output)
        runCatching { renderer.release() }.onFailure {
            Log.w("AMATV-Motion", "Editorial release failed: ${it.javaClass.simpleName}")
        }
        // This worker may briefly wait; the UI never joins a codec thread.
        if (thread !== Thread.currentThread()) runCatching { thread.join(25L) }
        checkRelease.run()
    }

    private fun acknowledgeOnControl(binding: MotionPreviewOutput) {
        if (!binding.lifecycle.releaseOnce()) return
        // Surface destruction can also wait for GraphicBuffer work. Keep both
        // the renderer acknowledgment AND native wrapper cleanup off the UI.
        runCatching { binding.surface.release() }
        main.post { binding.onCleared() }
    }

    /** The TextureView returned false from destroy and transferred ownership. */
    fun releaseTexture(texture: SurfaceTexture) {
        checkMain()
        control.post { runCatching { texture.release() } }
    }

    private fun createPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setLooper(control.looper)
        .setTrackSelector(DefaultTrackSelector(context).apply {
            parameters = buildUponParameters().setExceedVideoConstraintsIfNecessary(false).build()
        })
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 5_000, 100, 250)
            .setTargetBufferBytes(4 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
        .build().also { created ->
            created.volume = 0f
            created.repeatMode = Player.REPEAT_MODE_ONE
            created.addAnalyticsListener(object : AnalyticsListener {
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, renderedOutput: Any, renderTimeMs: Long) {
                    val binding = matchingOutput(created, eventTime) ?: return
                    if (renderedOutput !== binding.surface) return
                    val aspect = created.videoSize.displayAspect()
                    main.post {
                        if (isCurrentOnMain(binding)) {
                            val active = binding.lease
                            if (aspect != null) {
                                active.videoAspect = aspect
                                binding.onAspect(aspect)
                            }
                            if (!active.firstFrameReady) {
                                active.firstFrameReady = true
                                Log.i("AMATV-Motion", "First editorial frame mediaId=${active.mediaId}")
                            }
                        }
                    }
                }
                override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                    val binding = matchingOutput(created, eventTime) ?: return
                    val aspect = videoSize.displayAspect() ?: return
                    main.post {
                        if (isCurrentOnMain(binding)) {
                            binding.lease.videoAspect = aspect
                            binding.onAspect(aspect)
                        }
                    }
                }
            })
            created.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (releasing?.renderer === created) {
                        Log.w("AMATV-Motion", "Editorial release awaits playback-thread termination")
                        return
                    }
                    val mediaId = playingMediaId ?: return
                    if (player !== created || desiredMediaId != mediaId || created.playerError !== error) return
                    failOnControl(mediaId, error)
                }
            })
        }

    private fun matchingOutput(renderer: ExoPlayer, eventTime: AnalyticsListener.EventTime): MotionPreviewOutput? {
        val binding = output ?: return null
        if (player !== renderer || !wanted(binding) || playingMediaId != binding.lease.mediaId) return null
        val timeline = eventTime.timeline
        if (eventTime.windowIndex !in 0 until timeline.windowCount) return null
        val eventId = timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
        return binding.takeIf { eventId == it.lease.mediaId && renderer.currentMediaItem?.mediaId == eventId }
    }

    private fun isCurrentOnMain(binding: MotionPreviewOutput): Boolean = desiredOutput === binding &&
        lease === binding.lease && desiredMediaId == binding.lease.mediaId &&
        binding.lease.bindingGeneration == binding.generation

    private fun failOnControl(mediaId: String, failure: Exception) {
        if (handlingFailure) return
        handlingFailure = true
        try {
            clearOutputOnControl()
            if (releasing == null) runCatching { player?.apply { playWhenReady = false; stop(); clearMediaItems() } }
        } finally { handlingFailure = false }
        val causes = generateSequence(failure as Throwable) { it.cause }.take(8).toList()
        // Record transport identity without URLs, query strings, headers or error messages.
        val host = runCatching {
            causes.filterIsInstance<HttpDataSource.HttpDataSourceException>()
                .firstOrNull()?.dataSpec?.uri?.host
        }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9.:-]{1,253}")) } ?: "unknown"
        val chain = causes.joinToString(" -> ") { it.javaClass.simpleName.ifBlank { "Throwable" } }
        Log.w("AMATV-Motion", "Editorial renderer failed host=$host causes=$chain")
        main.post {
            lease?.takeIf { it.mediaId == mediaId && desiredMediaId == mediaId }?.let { active ->
                active.retainSurface?.invoke()
                active.failed = true
            }
        }
    }

    private fun constrainVideo(renderer: ExoPlayer, request: Request) {
        renderer.trackSelectionParameters = renderer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setMaxVideoSize(request.width, request.height)
            .setMaxVideoBitrate(2_500_000)
            .setMaxVideoFrameRate(30)
            .build()
    }

    private fun VideoSize.displayAspect(): Float? =
        if (width > 0 && height > 0 && pixelWidthHeightRatio.isFinite() && pixelWidthHeightRatio > 0f)
            (width.toFloat() * pixelWidthHeightRatio / height).takeIf { it.isFinite() && it > 0f }
        else null

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper())
}
