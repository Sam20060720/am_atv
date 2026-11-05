package dev.amatv.integration

import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.amatv.playback.PlaybackService

/** Publishes the app queue to Android. MusicKit remains the sole audio-focus owner. */
class PlaybackMediaSession(private val context: Context) {
    data class Callbacks(
        val play: () -> Unit,
        val pause: () -> Unit,
        val next: () -> Unit,
        val previous: () -> Unit,
        val seekTo: (Long) -> Unit,
        val seekRelative: (Long) -> Unit = {},
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var callbacks: Callbacks? = null
    private var released = false
    private var activatedByPlayback = false
    private var acceptPlayingActivation = true
    private var durationMs = 0L
    private var metadataKey: MetadataKey? = null
    private var lastPublishedState = PlaybackState.STATE_NONE
    private var lastPublishedActions = 0L
    private var lastPublishedPosition = -1L
    private var lastPublishedUpdateTime = 0L
    private var lastPublishedErrorMessage: String? = null
    private var foregroundRequested = false
    private var foregroundStartAttempted = false
    private var foregroundServiceStarted = false
    private var foregroundMetadata: Pair<String, String>? = null
    private val session = MediaSession(context.applicationContext, "AM ATV").apply {
        @Suppress("DEPRECATION")
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setRatingType(Rating.RATING_NONE)
        setPlaybackToLocal(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            setSessionActivity(PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        }
        setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_NONE, 0L, 0f)
            .build())
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { callbacks?.play?.invoke() }
            override fun onPause() { callbacks?.pause?.invoke() }
            override fun onStop() {
                // Stop cancels playback intent through the same application entry
                // as Pause. A delayed pre-stop snapshot must not reactivate us.
                acceptPlayingActivation = false
                activatedByPlayback = false
                isActive = false
                callbacks?.pause?.invoke()
            }
            override fun onSkipToNext() { callbacks?.next?.invoke() }
            override fun onSkipToPrevious() { callbacks?.previous?.invoke() }
            override fun onRewind() { callbacks?.seekRelative?.invoke(-10_000L) }
            override fun onFastForward() { callbacks?.seekRelative?.invoke(10_000L) }
            override fun onSeekTo(pos: Long) {
                val target = pos.coerceAtLeast(0L).let {
                    if (durationMs > 0L) it.coerceAtMost(durationMs) else it
                }
                callbacks?.seekTo?.invoke(target)
            }
        }, mainHandler)
    }

    fun setCallbacks(value: Callbacks?) = onMain {
        callbacks = value
        session.isActive = value != null && activatedByPlayback
    }

    /** Preparing a restored, paused song deliberately never calls this method. */
    fun playbackRequested() = onMain {
        acceptPlayingActivation = true
        activatedByPlayback = true
        session.isActive = callbacks != null
        if (!foregroundRequested) foregroundStartAttempted = false
        foregroundRequested = true
        updateForegroundService(
            metadataKey?.title ?: context.getString(dev.amatv.R.string.app_name),
            metadataKey?.artist.orEmpty(),
        )
    }

    fun playbackPaused() = onMain {
        foregroundRequested = false
        stopForegroundService()
    }

    fun publish(snapshot: PlaybackSnapshot, canSkipNext: Boolean, canSkipPrevious: Boolean) = onMain {
        val track = snapshot.currentTrack
        if (track == null) {
            clearOnMain()
            return@onMain
        }
        durationMs = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs.coerceAtLeast(0L)
        val nextMetadata = MetadataKey(
            id = track.catalogId ?: track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            durationMs = durationMs,
        )
        if (metadataKey != nextMetadata) {
            metadataKey = nextMetadata
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, nextMetadata.id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, nextMetadata.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, nextMetadata.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, nextMetadata.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, nextMetadata.durationMs)
                .apply {
                    nextMetadata.artworkUrl?.let {
                        // Let system clients fetch their preferred image size;
                        // no artwork decode or extra network work on this path.
                        putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it)
                        putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, it)
                    }
                }
                .build())
        }
        val state = when {
            snapshot.errorMessage != null -> PlaybackState.STATE_ERROR
            snapshot.isBuffering -> PlaybackState.STATE_BUFFERING
            snapshot.isPlaying -> PlaybackState.STATE_PLAYING
            snapshot.isEnded -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (snapshot.canSeek && durationMs > 0L) actions = actions or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
        if (canSkipNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (canSkipPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val position = snapshot.positionMs.coerceAtLeast(0L).let {
            if (durationMs > 0L) it.coerceAtMost(durationMs) else it
        }
        val updateTime = snapshot.capturedAtElapsedRealtimeMs
        val isSignificantStateChange = state != lastPublishedState ||
            actions != lastPublishedActions ||
            snapshot.errorMessage != lastPublishedErrorMessage
        val shouldUpdatePosition = if (isSignificantStateChange) {
            true
        } else if (state == PlaybackState.STATE_PLAYING) {
            val expectedPosition = lastPublishedPosition + (updateTime - lastPublishedUpdateTime)
            val drift = kotlin.math.abs(position - expectedPosition)
            drift > 2_000L || (updateTime - lastPublishedUpdateTime) >= 10_000L
        } else {
            kotlin.math.abs(position - lastPublishedPosition) > 500L
        }
        if (shouldUpdatePosition) {
            lastPublishedState = state
            lastPublishedActions = actions
            lastPublishedPosition = position
            lastPublishedUpdateTime = updateTime
            lastPublishedErrorMessage = snapshot.errorMessage
            session.setPlaybackState(PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
                    updateTime)
                .apply { snapshot.errorMessage?.let { setErrorMessage(it) } }
                .build())
        }
        if (snapshot.isPlaying && acceptPlayingActivation) activatedByPlayback = true
        session.isActive = callbacks != null && activatedByPlayback
        when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> {
                if (foregroundRequested) updateForegroundService(track.title, track.artist)
            }
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> {
                foregroundRequested = false
                stopForegroundService()
            }
        }
    }

    fun clear() = onMain { clearOnMain() }

    fun release() = onMain {
        callbacks = null
        activatedByPlayback = false
        session.isActive = false
        session.setCallback(null)
        session.release()
        released = true
        stopForegroundService()
    }

    private fun clearOnMain() {
        activatedByPlayback = false
        acceptPlayingActivation = true
        metadataKey = null
        durationMs = 0L
        lastPublishedState = PlaybackState.STATE_NONE
        lastPublishedActions = 0L
        lastPublishedPosition = -1L
        lastPublishedUpdateTime = 0L
        lastPublishedErrorMessage = null
        session.isActive = false
        session.setMetadata(null)
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_NONE, 0L, 0f)
            .build())
        foregroundRequested = false
        stopForegroundService()
    }

    private fun updateForegroundService(title: String, artist: String) {
        val nextMetadata = title to artist
        if (!foregroundServiceStarted) {
            if (foregroundStartAttempted) return
            foregroundStartAttempted = true
            foregroundServiceStarted = PlaybackService.start(context, title, artist)
            if (foregroundServiceStarted) foregroundMetadata = nextMetadata
        } else if (foregroundMetadata != nextMetadata) {
            PlaybackService.update(context, title, artist)
            foregroundMetadata = nextMetadata
        }
    }

    private fun stopForegroundService() {
        if (foregroundServiceStarted) PlaybackService.stop(context)
        foregroundServiceStarted = false
        foregroundStartAttempted = false
        foregroundMetadata = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            if (!released) block()
        } else {
            mainHandler.post { if (!released) block() }
        }
    }

    private data class MetadataKey(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val durationMs: Long,
    )
}
