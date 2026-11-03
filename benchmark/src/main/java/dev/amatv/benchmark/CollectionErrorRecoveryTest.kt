package dev.amatv.benchmark

import android.content.pm.PackageManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Explicit emulator-only UID-scoped fault; never changes a proxy or starts media. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class CollectionErrorRecoveryTest {
    @Test fun networkFailureRetryPreservesPrimaryFocus() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_network_fault") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Network fault injection is prohibited on physical devices" }
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val expected = checkNotNull(args.getString("expected_apk_sha256")).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val folder = File(checkNotNull(inst.context.getExternalFilesDir(null)), "collection-error-recovery/$run")
        check(!folder.exists() && folder.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val report = JSONObject().put("run_id", run).put("hardware", Build.HARDWARE)
            .put("play_actions", false).put("provider_writes", false).put("account_changes", false)
        val context = inst.context
        val tester = context.packageName.also { check(it == "dev.amatv.benchmark") }
        val user = ui.device.executeShellCommand("am get-current-user").trim().toInt()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val service = Intent(context, CollectionFaultVpnService::class.java)
        fun vpnPresent(): Boolean = connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } || ui.device.executeShellCommand("dumpsys connectivity").lineSequence().any {
            it.trimStart().startsWith("NetworkAgentInfo{") &&
                it.substringAfter("Transports: ", "").substringBefore(" Capabilities:").split('|').contains("VPN")
        }
        fun appOpRaw() = ui.device.executeShellCommand("cmd appops get --user $user $tester ACTIVATE_VPN").trim()
        fun mode(raw: String): String = Regex("ACTIVATE_VPN: (allow|ignore|deny|default|foreground)\\b")
            .find(raw)?.groupValues?.get(1) ?: if (raw.contains("No operations.")) "default"
            else error("Cannot safely identify original ACTIVATE_VPN mode: $raw")
        val originalProxy = ui.device.executeShellCommand("settings get global http_proxy").trim()
        val originalOp = appOpRaw()
        val originalMode = mode(originalOp)
        var permissionMayHaveChanged = false
        var faultServiceRequested = false
        var failure: Throwable? = null
        fun stopFault() {
            if (faultServiceRequested) {
                // Close the descriptor through the live service before asking
                // Android to stop it; a system binding can postpone onDestroy.
                context.startService(Intent(service).setAction(CollectionFaultVpnService.ACTION_STOP))
            }
            ui.await("The emulator test VPN did not disconnect", 5_000) { !CollectionFaultVpnService.active && !vpnPresent() }
            context.stopService(service)
            faultServiceRequested = false
        }
        fun restorePermission() {
            if (permissionMayHaveChanged) ui.device.executeShellCommand("cmd appops set --user $user $tester ACTIVATE_VPN $originalMode")
            ui.await("Original ACTIVATE_VPN mode did not restore", 5_000) { mode(appOpRaw()) == originalMode }
        }
        fun record(name: String) {
            val value = ui.snapshot().json(detailed = true)
            if (ui.foreground() == TARGET) value.put("collection_public_text", ui.label("collection-screen").take(8_000))
            File(folder, "$name.json").writeText(value.toString(2))
        }
        try {
            val app = inst.context.packageManager.getApplicationInfo(TARGET, 0)
            check(app.splitSourceDirs.isNullOrEmpty())
            val actual = File(app.sourceDir).inputStream().buffered().use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            check(actual == expected) { "Installed APK differs from the authorized standalone" }
            check(inst.context.packageManager.getPackageInfo(TARGET, PackageManager.GET_RECEIVERS).receivers.orEmpty()
                .none { it.name.startsWith("dev.amatv.benchmark.") }) { "Target is a benchmark build" }
            val alwaysOn = ui.device.executeShellCommand("settings --user $user get secure always_on_vpn_app").trim()
            val lockdown = ui.device.executeShellCommand("settings --user $user get secure always_on_vpn_lockdown").trim()
            check(alwaysOn in setOf("null", "") && lockdown != "1") { "An always-on VPN is configured; refusing to replace it" }
            check(!vpnPresent()) { "An existing VPN is active; refusing to replace it" }
            report.put("actual_apk_sha256", actual).put("original_global_http_proxy", originalProxy)
                .put("original_activate_vpn_appop", originalOp).put("original_activate_vpn_mode", originalMode)
                .put("original_vpn_active", false).put("original_always_on_vpn", alwaysOn)
                .put("fault_scope", "only_dev.amatv_ipv4_ipv6_tun_blackhole")
            // Clear only in-memory detail visits before discovering metadata;
            // never open this album while the network is still available.
            ui.device.executeShellCommand("am force-stop $TARGET"); ui.launch()
            ui.navigate("library"); ui.focus("library-category-albums")
            ui.activate("library-category-albums", "library_category")
            val source = ui.firstLibraryCard("album")
            check(source == "library-item-0-album")
            report.put("source", source).put("source_label", ui.label(source))
            record("discovered-before-fault")

            // Repeat ownership immediately before mutation; never displace another VPN.
            check(!vpnPresent()) { "A VPN appeared before fault establishment" }
            permissionMayHaveChanged = true // Also restore if dispatch succeeds but its response fails.
            ui.device.executeShellCommand("cmd appops set --user $user $tester ACTIVATE_VPN allow")
            check(mode(appOpRaw()) == "allow")
            check(VpnService.prepare(context) == null) { "VPN authorization would require user consent; refusing to launch it" }
            faultServiceRequested = true
            context.startForegroundService(service)
            ui.await("UID-scoped VPN fault did not establish", 5_000) {
                check(CollectionFaultVpnService.failure == null) { CollectionFaultVpnService.failure.orEmpty() }
                CollectionFaultVpnService.active && vpnPresent()
            }
            report.put("fault_established", true)
            ui.activate(source, "collection"); ui.awaitId("collection-screen", 8_000)
            ui.await("UID-scoped network fault did not produce a real initial collection failure", 45_000) {
                check(!ui.has("collection-song-0")) {
                    "Fault not observed: tracks were already cached or bypassed the VPN; this is not a recovery pass"
                }
                ui.has("collection-error") && ui.has("collection-retry")
            }
            ui.await("Failed initial Play did not naturally retain Retry focus", 4_000) { "collection-retry" in ui.snapshot().focused }
            check(ui.device.findObject(androidx.test.uiautomator.By.res("collection-retry"))?.isEnabled == true)
            record("failed-primary-retry")
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            ui.await("Retry Right did not reach More", 3_000) { "collection-more" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 0)
            ui.await("More Left did not return to Retry", 3_000) { "collection-retry" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 0)
            ui.await("Retry Up did not reach navigation", 3_000) { "nav-menu" in ui.snapshot().focused }
            val up = ui.snapshot().json()
            ui.settle(350) // The legitimate pill dwell can open the sidebar.
            check(ui.snapshot().focused.any { it.startsWith("nav-") }) { "Error recovery stole focus back from navigation" }
            report.put("up_navigation_focus", up).put("navigation_retained_after_dwell", ui.snapshot().json())

            stopFault(); restorePermission()
            report.put("vpn_removed_before_retry", true).put("activate_vpn_restored_before_retry", true)
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            ui.await("Navigation Right did not return to the real Retry source", 5_000) { "collection-retry" in ui.snapshot().focused }
            check(ui.has("collection-retry") && !ui.has("collection-play")) { "Retry changed before activation; refusing a possible Play" }
            check(ui.device.pressDPadCenter()) // The proven Retry node, never Play.
            ui.await("Real Retry did not load the album's actual first song", 45_000) { ui.has("collection-song-0") && ui.has("collection-play") }
            ui.await("Retry-to-Play replacement did not retain primary focus", 5_000) { "collection-play" in ui.snapshot().focused }
            check(ui.device.findObject(androidx.test.uiautomator.By.res("collection-play"))?.isEnabled == true)
            record("recovered-primary-play")
            report.put("actual_first_song", ui.label("collection-song-0")).put("primary_focus", ui.snapshot().json())
                .put("real_remote_sequence", "Retry_Right_More_Left_Retry_Up_Navigation_Right_Retry_Center")
                .put("status", "passed_real_failure_and_retry_focus")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.stackTraceToString().take(12_000))
            runCatching { record("failure-current") }.onFailure { report.put("failure_snapshot_error", it.toString()) }
        } finally {
            try {
                // Both are attempted even if one cleanup step fails.
                var stopError: Throwable? = null
                try { stopFault() } catch (error: Throwable) { stopError = error }
                try { restorePermission() } catch (error: Throwable) {
                    if (stopError == null) stopError = error else stopError.addSuppressed(error)
                }
                stopError?.let { throw it }
                check(!vpnPresent())
                check(ui.device.executeShellCommand("settings get global http_proxy").trim() == originalProxy)
                report.put("original_proxy_unchanged", true).put("test_vpn_disconnected", true)
                    .put("activate_vpn_restored", true).put("restored_activate_vpn_appop", appOpRaw())
            } catch (error: Throwable) {
                report.put("status", "failed").put("network_restore_error", error.toString())
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally { File(folder, "result.json").writeText(report.toString(2)); ui.close() }
        }
        failure?.let { throw it }
    }
}
