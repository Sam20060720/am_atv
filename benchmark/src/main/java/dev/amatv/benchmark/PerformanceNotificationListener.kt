package dev.amatv.benchmark

import android.service.notification.NotificationListenerService

/** Test APK only. No notification payload is read or retained. */
class PerformanceNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    companion object {
        @Volatile var connected = false
            private set
    }
}
