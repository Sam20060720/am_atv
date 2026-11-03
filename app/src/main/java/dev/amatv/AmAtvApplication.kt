package dev.amatv

import android.app.Application
import dev.amatv.i18n.AppText
import dev.amatv.playback.PlaybackRuntimeOwner

class AmAtvApplication : Application() {
    @Volatile private var retainedPlayback: PlaybackRuntimeOwner? = null

    /** Main-thread UI/service entry; an idle released owner is never reused. */
    fun playbackRuntime(): PlaybackRuntimeOwner = synchronized(this) {
        retainedPlayback?.takeUnless { it.isReleased } ?: PlaybackRuntimeOwner(this) { released ->
            synchronized(this) { if (retainedPlayback === released) retainedPlayback = null }
        }.also { retainedPlayback = it }
    }

    val existingPlaybackRuntime: PlaybackRuntimeOwner?
        get() = retainedPlayback?.takeUnless { it.isReleased }

    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
    }
}
