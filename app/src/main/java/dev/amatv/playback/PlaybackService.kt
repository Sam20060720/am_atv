package dev.amatv.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.amatv.MainActivity
import dev.amatv.AmAtvApplication
import dev.amatv.R

/**
 * Foreground service ensuring the OS does not terminate audio playback
 * when the Activity is backgrounded on Android TV.
 */
class PlaybackService : Service() {
    private var runtime: PlaybackRuntimeOwner? = null
    private var runtimeLease: Long? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // The process runtime owns the actual player/queue. A service lease
        // keeps that owner discoverable without binding it to an Activity.
        runtime = (application as? AmAtvApplication)?.existingPlaybackRuntime
        runtimeLease = runtime?.acquireService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START, ACTION_UPDATE -> {
                // A late metadata update must not restart a service stopped by Pause.
                if (action == ACTION_UPDATE && !foregroundStarted) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                val notification = buildNotification(title, artist)
                // The same ID updates the required foreground notification. This path
                // remains valid without the optional POST_NOTIFICATIONS permission.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foregroundStarted = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtimeLease?.let { runtime?.releaseService(it) }
        runtimeLease = null
        runtime = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.services_playback_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.services_playback_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, artist: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "amatv_media_playback"
        const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "dev.amatv.action.PLAYBACK_START"
        private const val ACTION_UPDATE = "dev.amatv.action.PLAYBACK_UPDATE"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_ARTIST = "extra_artist"

        fun start(context: Context, title: String, artist: String): Boolean {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse {
                Log.w("AMATV-playback", "Could not start playback foreground service", it)
                false
            }
        }

        fun update(context: Context, title: String, artist: String) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w("AMATV-playback", "Could not update playback notification", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
