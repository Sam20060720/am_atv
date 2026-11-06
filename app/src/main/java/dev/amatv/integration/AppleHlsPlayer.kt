package dev.amatv.integration

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.integration.audio.AppleWidevineCallback
import dev.amatv.integration.audio.ContentIdExtractorFactory
import dev.amatv.integration.audio.HlsContentIdResolver
import dev.amatv.model.Track
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One app-owned song at a time. Queue identity and AAC fallback stay in MusicServices. */
@UnstableApi
internal class AppleHlsPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    // Player, request and listener access is serialized on the main looper.
    private var player: ExoPlayer? = null
    private var request: Request? = null
    private var listener: ((PlaybackSnapshot) -> Unit)? = null
    @Volatile private var rendererSessionId = 0
    @Volatile var isAtmosPlayback: Boolean = false
        private set

    private class Request(val generation: Long, val track: Track, val entryId: Long?) {
        var ready = false
        var error: PlaybackFailureKind? = null
        var bufferingSince: Long? = null
        var failureWantsPlay: Boolean? = null
    }

    private val poll = object : Runnable {
        override fun run() {
            val owner = request ?: return
            val current = player ?: return
            if (!owns(owner, current)) return
            val buffering = current.playWhenReady && current.playbackState == Player.STATE_BUFFERING
            val now = SystemClock.elapsedRealtime()
            if (!buffering) owner.bufferingSince = null
            else if (owner.bufferingSince == null) owner.bufferingSince = now
            else if (now - checkNotNull(owner.bufferingSince) >= BUFFER_TIMEOUT_MS) {
                owner.failureWantsPlay = current.playWhenReady
                owner.error = PlaybackFailureKind.Network
                current.pause()
            }
            emit(owner, current)
            if (owns(owner, current) && owner.error == null) main.postDelayed(this, POLL_MS)
        }
    }

    /** A current-route capability hint; the renderer remains authoritative at preparation. */
    fun canPlayAtmos(): Boolean = runCatching {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(6).setSampleRate(48_000).build()
        AudioCapabilities.getCapabilities(appContext, MUSIC_ATTRIBUTES, null)
            .isPassthroughPlaybackSupported(format, MUSIC_ATTRIBUTES) ||
            MediaCodecUtil.getDecoderInfos(MimeTypes.AUDIO_E_AC3_JOC, false, false).isNotEmpty()
    }.getOrDefault(false)

    suspend fun preparePlayback(
        track: Track,
        asset: AppleAudioAsset,
        developerToken: String,
        userToken: String,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
        currentAppEntryId: Long? = null,
    ): Long {
        val generation = sequence.incrementAndGet()
        return withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            if (generation != sequence.get()) throw CancellationException("Playback replaced")
            closePlayer()
            val owner = Request(generation, track, currentAppEntryId)
            request = owner
            isAtmosPlayback = asset.isAtmos
            try {
                val sourceFactory = mediaSourceFactory(asset, developerToken, userToken)
                val current = ExoPlayer.Builder(appContext)
                    .setLooper(Looper.getMainLooper())
                    .setAudioAttributes(MUSIC_ATTRIBUTES, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .setMediaSourceFactory(sourceFactory)
                    .build()
                player = current
                current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                    .setAudioOffloadPreferences(AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(if (asset.isAtmos) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                            else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                        .build())
                    .build()
                current.addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (owns(owner, current)) rendererSessionId = audioSessionId.coerceAtLeast(0)
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (!owns(owner, current)) return
                        if (current.playbackState == Player.STATE_READY) owner.ready = true
                        emit(owner, current)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!owns(owner, current)) return
                        if (owner.error == null) owner.failureWantsPlay = current.playWhenReady
                        // Exception messages and causes may include signed asset URLs.
                        owner.error = when (error.errorCode) {
                            in 2000..2999 -> PlaybackFailureKind.Network
                            in 6000..6999 -> PlaybackFailureKind.Authorization
                            in 4000..5999 -> PlaybackFailureKind.TrackUnavailable
                            else -> PlaybackFailureKind.Unexpected
                        }
                        emit(owner, current)
                    }
                })
                val item = MediaItem.Builder().setMediaId("apple-hls-$generation")
                    .setUri(asset.renditionUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
                    .build()
                current.setMediaItem(item, positionMs.coerceAtLeast(0L))
                current.playWhenReady = playWhenReady
                current.prepare()
                main.post(poll)
                emit(owner, current)
                generation
            } catch (error: Throwable) {
                if (request === owner) closePlayer()
                throw error
            }
        }
    }

    fun setPlaybackListener(value: ((PlaybackSnapshot) -> Unit)?) = onMain {
        listener = value
        val owner = request
        val current = player
        if (value != null && owner != null && current != null && owns(owner, current)) emit(owner, current)
    }

    fun play() = command { it.play() }
    fun pause() = command { it.pause() }
    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs.coerceAtLeast(0L)) }

    fun audioSessionId(): Int = rendererSessionId

    /** Main-looper callers can preserve focus/noisy pauses through an AAC fallback. */
    fun desiredPlayWhenReady(): Boolean? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = request ?: return null
        val current = player ?: return null
        if (!owns(owner, current)) return null
        return owner.failureWantsPlay ?: current.playWhenReady
    }

    fun release() {
        val released = sequence.incrementAndGet()
        rendererSessionId = 0
        isAtmosPlayback = false
        onMain { if (sequence.get() == released) closePlayer() }
    }

    private fun command(action: (ExoPlayer) -> Unit) {
        val generation = sequence.get()
        onMain {
            val owner = request ?: return@onMain
            val current = player ?: return@onMain
            if (owner.generation == generation && owns(owner, current) && owner.error == null) {
                owner.bufferingSince = null
                action(current)
                emit(owner, current)
            }
        }
    }

    private fun owns(owner: Request, current: ExoPlayer): Boolean =
        request === owner && player === current && owner.generation == sequence.get()

    private fun emit(owner: Request, current: ExoPlayer) {
        if (!owns(owner, current)) return
        val failure = owner.error
        listener?.invoke(PlaybackSnapshot(
            currentTrack = owner.track,
            hasPlayerCurrentTrack = current.currentMediaItem?.mediaId == "apple-hls-${owner.generation}",
            positionMs = current.currentPosition.coerceAtLeast(0L),
            durationMs = current.duration.takeIf { it > 0L } ?: owner.track.durationMs,
            isPlaying = failure == null && current.isPlaying,
            isBuffering = failure == null && current.playbackState == Player.STATE_BUFFERING,
            canSkipNext = false,
            canSkipPrevious = false,
            errorMessage = failure?.let { AppText.text(R.string.services_playback_failed) },
            isEnded = failure == null && current.playbackState == Player.STATE_ENDED,
            requestGeneration = owner.generation,
            isPrepared = owner.ready && failure == null,
            failureKind = failure,
            canSeek = current.isCurrentMediaItemSeekable,
            appQueueEntryId = owner.entryId,
        ))
    }

    private fun closePlayer() {
        request = null
        rendererSessionId = 0
        isAtmosPlayback = false
        main.removeCallbacks(poll)
        val previous = player
        player = null
        previous?.release()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun mediaSourceFactory(asset: AppleAudioAsset, developer: String, user: String): HlsMediaSource.Factory {
        val routes = ConcurrentHashMap<String, String>()
        val sessions = ConcurrentHashMap<String, String>()
        val references = asset.contentReferences.distinct().map { reference ->
            reference to Base64.decode(reference.substringAfter(','), Base64.DEFAULT)
        }
        require(references.isNotEmpty()) { "Missing content reference" }
        val callback = AppleWidevineCallback(asset.licenseEndpoint, developer, user, asset.songId,
            contentReferenceForRequest = { challenge ->
                checkNotNull(routes.remove(digest(challenge))) { "Unmatched DRM request" }
            }, assetSession = asset.assetSession)
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                val framework = FrameworkMediaDrm.newInstance(uuid)
                try {
                    asset.widevineCertificate?.let { certificate ->
                        framework.setPropertyByteArray("serviceCertificate", certificate)
                        framework.setPropertyString("privacyMode", "enable")
                    }
                } catch (error: Throwable) {
                    // A failed provider is never handed to the session manager to release.
                    framework.release()
                    throw error
                }
                object : ExoMediaDrm by framework {
                    override fun getKeyRequest(scope: ByteArray, schemeDatas: List<DrmInitData.SchemeData>?,
                        keyType: Int, optionalParameters: HashMap<String, String>?): ExoMediaDrm.KeyRequest {
                        val response = framework.getKeyRequest(scope, schemeDatas, keyType, optionalParameters)
                        val matches = schemeDatas.orEmpty().mapNotNull { scheme ->
                            references.singleOrNull { (_, init) -> init.contentEquals(scheme.data) }?.first
                        }.distinct()
                        val scopeKey = digest(scope)
                        // Only a previously-bound session can resolve an init-data-free renewal.
                        val reference = matches.singleOrNull()
                            ?: sessions[scopeKey].takeIf { schemeDatas.isNullOrEmpty() }
                        if (reference != null) {
                            sessions[scopeKey] = reference
                            routes[digest(response.data)] = reference
                        }
                        return response
                    }

                    override fun closeSession(sessionId: ByteArray) {
                        sessions.remove(digest(sessionId))
                        framework.closeSession(sessionId)
                    }
                }
            }
            .setMultiSession(true)
            // MusicServices owns the single fallback; never loop on rejected authorization.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            .build(callback)
        val identifiers = HlsContentIdResolver()
        return HlsMediaSource.Factory(identifiers.dataSourceFactory(DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000)))
            .setPlaylistParserFactory(identifiers.playlistFactory())
            .setExtractorFactory(ContentIdExtractorFactory(identifiers::currentId) { _, _ -> })
            .setDrmSessionManagerProvider { manager }
    }

    private companion object {
        const val POLL_MS = 250L
        const val BUFFER_TIMEOUT_MS = 30_000L
        val MUSIC_ATTRIBUTES = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        fun digest(data: ByteArray): String = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(data), Base64.NO_WRAP)
    }
}
