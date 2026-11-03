package dev.amatv.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

/** Emulator-only black hole for the target UID. Never reads, records or exports packets. */
class CollectionFaultVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "VPN faults are prohibited on physical devices" }
            if (intent?.action == ACTION_STOP) {
                // The platform binds an active VpnService. stopService alone
                // can leave that bound instance and its TUN alive indefinitely.
                closeTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            check(VpnService.prepare(this) == null) { "VPN was not pre-authorized; no consent activity will be opened" }
            check(tun == null) { "Fault VPN is already running" }
            failure = null
            val descriptor = checkNotNull(Builder()
                .setSession("AMATV emulator-only network fault")
                .addAllowedApplication("dev.amatv")
                .addAddress("198.18.0.1", 32)
                .addAddress("fd7a:115c:a1e0::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setMtu(1500)
                .setBlocking(false)
                .establish()) { "VPN establishment was not authorized" }
            tun = descriptor
            // Establish first: API 34's systemExempted type requires an active VPN.
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Emulator network test", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("AMATV emulator network test")
                .setContentText("Only AMATV is temporarily offline")
                .setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(NOTIFICATION_ID, notification)
            active = true
        } catch (error: Throwable) {
            failure = "${error.javaClass.simpleName}: ${error.message}"
            closeTunnel()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun closeTunnel() {
        val descriptor = tun
        tun = null
        active = false
        runCatching { descriptor?.close() }.onFailure {
            if (failure == null) failure = "Tunnel close failed: ${it.javaClass.simpleName}"
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() { closeTunnel(); stopSelf() }
    override fun onDestroy() { closeTunnel(); super.onDestroy() }

    companion object {
        internal const val ACTION_STOP = "dev.amatv.benchmark.STOP_COLLECTION_FAULT_VPN"
        private const val CHANNEL = "collection-network-fault"
        private const val NOTIFICATION_ID = 9401
        @Volatile internal var active = false
            private set
        @Volatile internal var failure: String? = null
            private set
    }
}
