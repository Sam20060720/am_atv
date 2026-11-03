package dev.amatv.benchmark

import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit one-station experiment; never included as an implicit playback survey. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class StationPlaybackExperimentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi
    private lateinit var report: JSONObject
    private lateinit var directory: File
    private val samples = JSONArray()

    /** Diagnose VPN authorization only: no playback, service or tunnel is started. */
    @Test fun networkFaultPreconditionsOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_network_fault") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = requireNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val context = instrumentation.context
        val tester = context.packageName.also { check(it == "dev.amatv.benchmark") }
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val user = device.executeShellCommand("am get-current-user").trim().toInt()
        val out = File(requireNotNull(context.getExternalFilesDir(null)), "station-playback/$run/vpn-preconditions.json")
        check(!out.exists() && requireNotNull(out.parentFile).mkdirs())
        fun raw() = device.executeShellCommand("cmd appops get --user $user $tester ACTIVATE_VPN").trim()
        fun mode(value: String) = Regex("ACTIVATE_VPN: (allow|ignore|deny|default|foreground)\\b").find(value)?.groupValues?.get(1)
            ?: if (value.contains("No operations.")) "default" else error("Unknown VPN app-op: $value")
        val originalRaw = raw()
        val originalMode = mode(originalRaw)
        val result = JSONObject().put("run_id", run).put("playback_started", false).put("vpn_fault_started", false)
            .put("process_uid", android.os.Process.myUid()).put("context_uid", context.applicationInfo.uid)
            .put("package_uid", context.packageManager.getApplicationInfo(tester, 0).uid)
            .put("target_package", instrumentation.targetContext.packageName).put("original_appop", originalRaw)
        var failure: Throwable? = null
        try {
            device.executeShellCommand("cmd appops set --user $user $tester ACTIVATE_VPN allow")
            val allowedRaw = raw(); result.put("allowed_appop", allowedRaw)
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
            val adopted = try { VpnService.prepare(context) } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            result.put("adopted_prepare_null", adopted == null).put("adopted_prepare_component", adopted?.component?.flattenToShortString())
            val native = VpnService.prepare(context)
            result.put("native_prepare_null", native == null).put("native_prepare_component", native?.component?.flattenToShortString())
            check(mode(allowedRaw) == "allow") { "ACTIVATE_VPN did not read back allow: $allowedRaw" }
            check(native == null) { "Original tester UID still requires VPN confirmation; see recorded identity and prepare result" }
            result.put("status", "observed_authorized_preconditions")
        } catch (error: Throwable) { failure = error; result.put("status", "failed").put("error", error.message?.take(500)) }
        finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            runCatching {
                device.executeShellCommand("cmd appops set --user $user $tester ACTIVATE_VPN $originalMode")
                val restored = raw(); result.put("restored_appop", restored).put("appop_restored", mode(restored) == originalMode)
                check(mode(restored) == originalMode)
            }.onFailure { if (failure == null) failure = it }
            if (failure != null) result.put("status", "failed").put("error", failure?.message?.take(500))
            out.writeText(result.toString(2))
        }
        failure?.let { throw it }
    }

    /** A real UID-scoped network failure of the retained selected stream. */
    @Test fun retainedStationRecoversFromNetworkFailure() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_station_playback") == "1" && args.getString("allow_network_fault") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = requireNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val stationId = requireNotNull(args.getString("station_id")).also { require(it.matches(Regex("ra\\.[A-Za-z0-9._-]+"))) }
        directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "station-playback/$run")
        check(!directory.exists() && directory.mkdirs())
        ui = RenderingSurveyUi(instrumentation)
        report = JSONObject().put("run_id", run).put("station_id", stationId).put("provider_writes", false)
        val context = instrumentation.context
        val tester = context.packageName.also { check(it == "dev.amatv.benchmark") }
        val service = Intent(context, CollectionFaultVpnService::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val user = ui.device.executeShellCommand("am get-current-user").trim().toInt()
        fun vpnPresent() = connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        fun mode(): String {
            val raw = ui.device.executeShellCommand("cmd appops get --user $user $tester ACTIVATE_VPN").trim()
            return Regex("ACTIVATE_VPN: (allow|ignore|deny|default|foreground)\\b").find(raw)?.groupValues?.get(1)
                ?: if (raw.contains("No operations.")) "default" else error("Unknown original VPN app-op")
        }
        val originalMode = mode()
        var permissionChanged = false
        var faultRequested = false
        var failure: Throwable? = null
        fun session(): android.media.session.MediaController {
            // Only session discovery needs this permission. AOSP delegates
            // permission-less app-ops (including ACTIVATE_VPN) to shell while
            // adoption is active, so VPN calls must keep the tester identity.
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
            return try {
                checkNotNull(context.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(null).firstOrNull { it.packageName == TARGET })
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
        fun stopFault() {
            if (faultRequested) {
                context.startService(Intent(service).setAction(CollectionFaultVpnService.ACTION_STOP))
                ui.await("Station fault VPN did not disconnect", 5_000L) { !CollectionFaultVpnService.active && !vpnPresent() }
                context.stopService(service); faultRequested = false
            }
        }
        try {
            check(ui.device.executeShellCommand("settings --user $user get secure always_on_vpn_app").trim() in setOf("null", ""))
            check(ui.device.executeShellCommand("settings --user $user get secure always_on_vpn_lockdown").trim() != "1")
            check(!vpnPresent()) { "An existing VPN must not be replaced" }
            ui.launch()
            if (!ui.has("player-screen")) {
                ui.openNavigation(); ui.activate("nav-nowplaying", "navigation"); ui.awaitId("player-screen")
            }
            check(session().metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty().contains(stationId))
            session().transportControls.play()
            ui.await("Retained station did not play before network fault", 35_000L) { state() == "playing" }
            val finite = (session().metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L
            val beforePosition = checkNotNull(session().playbackState).position
            val pid = ui.device.executeShellCommand("pidof $TARGET").trim().also { check(it.matches(Regex("[0-9]+"))) }
            fun engineFailures() = ui.device.executeShellCommand("logcat -d -v epoch --pid=$pid -t 200 -s AMATV-Station:W '*:S'")
                .lineSequence().filter { "Station failure" in it && "kind=Network" in it }.toSet()
            val oldFailures = engineFailures()
            permissionChanged = true
            ui.device.executeShellCommand("cmd appops set --user $user $tester ACTIVATE_VPN allow")
            check(mode() == "allow" && VpnService.prepare(context) == null)
            faultRequested = true; context.startForegroundService(service)
            ui.await("Station fault VPN did not establish", 5_000L) {
                check(CollectionFaultVpnService.failure == null) { CollectionFaultVpnService.failure.orEmpty() }
                CollectionFaultVpnService.active && vpnPresent()
            }
            var observedFailure = emptySet<String>()
            ui.await("No actual station network failure observed; buffered playback is not a recovery pass", 75_000L) {
                observedFailure = engineFailures() - oldFailures
                observedFailure.isNotEmpty()
            }
            report.put("actual_network_failures", JSONArray(observedFailure.toList())).put("before_position_ms", beforePosition)
            stopFault()
            ui.await("Same station did not recover after actual network failure", 40_000L) {
                check(statusMessages().isEmpty()) { "Station failed: ${statusMessages()}" }
                state() == "playing" && session().metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty().contains(stationId)
            }
            val afterPosition = checkNotNull(session().playbackState).position
            if (finite) check(afterPosition >= beforePosition - 1_000L) { "Finite episode restarted after network recovery" }
            report.put("after_position_ms", afterPosition).put("finite", finite).put("status", "passed_actual_network_failure_and_same_station_recovery")
        } catch (error: Throwable) {
            failure = error; report.put("status", "failed").put("error", error.message?.take(500))
        } finally {
            runCatching { stopFault() }.onFailure { if (failure == null) failure = it }
            if (permissionChanged) runCatching {
                ui.device.executeShellCommand("cmd appops set --user $user $tester ACTIVATE_VPN $originalMode")
                check(mode() == originalMode)
            }.onFailure { if (failure == null) failure = it }
            runCatching {
                session().transportControls.pause()
                ui.await("Cleanup station Pause did not settle", 8_000L) {
                    session().playbackState?.state !in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
                }
            }.onFailure { if (failure == null) failure = it }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if (failure != null) report.put("status", "failed").put("error", failure?.message?.take(500))
            report.put("fault_stopped", !CollectionFaultVpnService.active).put("vpn_permission_restored", mode() == originalMode)
            File(directory, "result.json").writeText(report.toString(2)); ui.close()
        }
        failure?.let { throw it }
    }

    /** One explicitly named current radio entry, using public selection/transport. */
    @Test fun selectedStationTransportAndRecreation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_station_playback") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = requireNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val label = requireNotNull(args.getString("station_label"))
        val stationId = requireNotNull(args.getString("station_id")).also { require(it.matches(Regex("ra\\.[A-Za-z0-9._-]+"))) }
        val expectLive = requireNotNull(args.getString("station_live")).also { require(it in setOf("0", "1")) } == "1"
        directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "station-playback/$run")
        check(!directory.exists() && directory.mkdirs())
        ui = RenderingSurveyUi(instrumentation)
        report = JSONObject().put("run_id", run).put("station_label", label).put("station_id", stationId)
            .put("expected_live", expectLive).put("provider_writes", false)
        var failure: Throwable? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        fun session() = checkNotNull(instrumentation.context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(null).firstOrNull { it.packageName == TARGET })
        try {
            ui.launch(); openRadio()
            val station = findStation(label)
            report.put("selected_label", ui.label(station)); click(station)
            ui.awaitId("player-screen", 10_000L)
            ui.await("Selected station must actually play", 40_000L) {
                check(statusMessages().isEmpty()) { "Station failed: ${statusMessages()}" }
                state() == "playing"
            }
            val metadata = checkNotNull(session().metadata)
            check(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty().contains(stationId)) {
                "The selected public card did not play the requested station identity"
            }
            val actions = checkNotNull(session().playbackState).actions
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            report.put("before", readSystemMetadata()).put("actions", actions).put("duration_ms", duration)
            if (expectLive) {
                check(duration == 0L && actions and PlaybackState.ACTION_SEEK_TO == 0L) { "Live station advertised a seekable buffer" }
            } else {
                check(duration > 35_000L && actions and PlaybackState.ACTION_SEEK_TO != 0L) { "Finite programme did not expose its actual duration/Seek" }
                session().transportControls.seekTo(30_000L)
                ui.await("Finite Seek did not settle into actual advancing playback", 10_000L) {
                    // Seek can publish its new cursor before the backing stream
                    // finishes buffering. FFT sampling belongs to real playback.
                    session().playbackState?.let {
                        it.state == PlaybackState.STATE_PLAYING && it.position in 31_000L..40_000L
                    } == true
                }
                report.put("after_seek", readSystemMetadata())
            }
            val playing = collectSpectrum("$run-playing", 1_500)
            report.put("playing_spectrum", playing)
            check((0 until playing.length()).count { playing.getJSONObject(it).optBoolean("captured") } >= 5) {
                "The selected stream did not provide actual FFT samples"
            }
            session().transportControls.pause()
            ui.await("Selected station Pause did not stop playback", 8_000L) { state() == "paused" }
            val paused = collectSpectrum("$run-paused", 500)
            report.put("paused_spectrum", paused)
            check((0 until paused.length()).all { !paused.getJSONObject(it).optBoolean("captured") })
            val pausedPosition = session().playbackState?.position ?: -1L
            session().transportControls.play()
            ui.await("Selected station did not resume", 30_000L) { state() == "playing" }
            if (!expectLive) check((session().playbackState?.position ?: -1L) >= pausedPosition - 1_000L) { "Episode resume lost its cursor" }
            report.put("after_resume", readSystemMetadata())

            val recreateRun = "$run-recreate"
            ui.device.pressHome()
            ui.device.executeShellCommand("am broadcast -n dev.amatv/dev.amatv.benchmark.RuntimeContinuityProbeReceiver --es run_id $recreateRun")
            ui.launch()
            var continuity: JSONObject? = null
            ui.await("Station Activity recreation did not complete", 20_000L) {
                continuity = runCatching { JSONObject(ui.device.executeShellCommand("cat /sdcard/Android/data/dev.amatv/files/runtime-probe/$recreateRun/result.json")) }.getOrNull()
                continuity != null
            }
            report.put("recreation", continuity)
            check(continuity?.optString("status") == "passed") { "Station recreation failed: $continuity" }
            ui.awaitId("player-screen", 8_000L)
            val recreated = collectSpectrum("$run-recreated", 1_000)
            report.put("recreated_spectrum", recreated)
            check((0 until recreated.length()).count { recreated.getJSONObject(it).optBoolean("captured") } >= 3) { "FFT did not reattach after recreation" }
            ui.leavePlayerWithBack()
            val background = collectSpectrum("$run-background", 1_000)
            report.put("background", background)
            check((0 until background.length()).all { background.getJSONObject(it).let { sample ->
                sample.optBoolean("playing") && !sample.optBoolean("captured")
            } }) { "Leaving Now Playing did not disable only FFT" }
            report.put("status", "passed_selected_station_transport_recreation_and_fft")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            runCatching {
                session().transportControls.pause()
                ui.await("Cleanup station Pause did not settle", 8_000L) {
                    session().playbackState?.state !in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
                }
            }.onFailure { if (failure == null) failure = it }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if (failure != null) report.put("status", "failed").put("error", failure?.message?.take(500))
            File(directory, "result.json").writeText(report.toString(2)); ui.close()
        }
        failure?.let { throw it }
    }

    @Test fun appleMusicOnePlaysAndAdvances() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit station playback experiment only", args.getString("allow_station_playback") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Station experiment is emulator-only" }
        val runId = args.getString("run_id") ?: "station-" + System.currentTimeMillis()
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "station-playback/$runId")
        check(!directory.exists()) { "Do not overwrite an earlier station experiment" }
        check(directory.mkdirs())
        ui = RenderingSurveyUi(instrumentation)
        report = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("package", TARGET).put("station_label", "Apple Music 1")
            .put("start_wall_ms", System.currentTimeMillis()).put("samples", samples)
            .put("queue_restore", "Host must restore the exact backed-up playback checkpoint; no guessed queue restoration")
        var failure: Throwable? = null
        try {
            ui.launch()
            // The host snapshots the protected playback files before execution.
            // Public initial metadata is retained here only as a restoration check.
            if (!ui.has("player-screen")) {
                ui.navigate("home")
                ui.openNavigation()
            }
            if (ui.has("player-screen") || ui.has("nav-nowplaying")) {
                if (!ui.has("player-screen")) ui.activate("nav-nowplaying", "navigation")
                ui.awaitId("player-screen")
                val originalControls = ui.has("player-controls")
                wakeControls()
                report.put("original", observe().put("controls_originally_visible", originalControls)
                    .put("queue_visible", ui.has("queue-panel")))
                pausePlayer()
            } else report.put("original", JSONObject().put("has_player", false))
            openRadio()
            val station = findAppleMusicOne()
            report.put("selected_card", station).put("selected_label", ui.label(station))
            click(station)
            ui.awaitId("player-screen", 10_000)
            ui.await("Station never entered actual public Playing state", 45_000) {
                val errors = statusMessages()
                check(errors.isEmpty()) { "Station reported: ${errors.joinToString()}" }
                state() == "playing"
            }
            wakeControls()
            val begin = observe()
            report.put("playing_start", begin)
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < 6_000L) {
                ui.settle(1_000)
                wakeControls()
                val sample = observe().put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                samples.put(sample)
                check(sample.optString("state") == "playing") { "Station stopped or buffered during the six-second observation" }
                check(statusMessages().isEmpty()) { "Station displayed a playback failure" }
            }
            val end = observe()
            report.put("playing_end", end)
            val firstPosition = begin.optLong("elapsed_ms", -1L)
            val lastPosition = end.optLong("elapsed_ms", -1L)
            val advanced = lastPosition - firstPosition
            report.put("public_position_delta_ms", advanced)
            check(firstPosition >= 0L && lastPosition >= 0L && advanced >= 4_000L) {
                "Playing state alone is insufficient: public position did not advance by at least four seconds"
            }
            pausePlayer()
            report.put("live_stop_observed", observe())
            wakeControls()
            click("player-play-pause")
            ui.await("The same live station must reconnect after Stop", 30_000) { state() == "playing" }
            ui.settle(1_500L)
            check(state() == "playing") { "Resumed station did not remain playing" }
            report.put("live_resume_observed", observe())
            ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ui.await("Remote Media Pause must stop the live stream", 10_000L) { state() == "paused" }
            report.put("remote_pause_observed", observe())
            report.put("status", "passed_public_state_and_position")
            report.put("audio_evidence", "Host must independently inspect AudioFlinger/engine logs; UI cannot prove audible PCM or DRM continuity")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            report.put("public_errors", JSONArray(runCatching { statusMessages() }.getOrDefault(emptyList())))
            ui.device.takeScreenshot(File(directory, "failure.png"))
        } finally {
            try {
                if (ui.has("player-screen")) pausePlayer()
                openRadio()
                report.put("cleanup", "paused_and_returned_to_radio")
            } catch (cleanup: Throwable) {
                report.put("cleanup", "failed").put("cleanup_error", cleanup.message?.take(500))
                if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
            }
            report.put("end_wall_ms", System.currentTimeMillis())
            File(directory, "result.json").writeText(report.toString(2))
            ui.device.takeScreenshot(File(directory, "final.png"))
            ui.close()
        }
        failure?.let { throw it }
    }

    /** Observe only real live metadata/artwork. No resume/seek/queue or provider-write journey. */
    @Test fun liveSongMetadataAndArtworkOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit station metadata experiment only", args.getString("allow_station_playback") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 29)
        val runId = requireNotNull(args.getString("run_id"))
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "station-playback/$runId")
        check(!directory.exists() && directory.mkdirs())
        ui = RenderingSurveyUi(instrumentation)
        val metadataSamples = JSONArray()
        report = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("experiment", "live_song_metadata_and_artwork_only").put("station_label", "Apple Music 1")
            .put("start_wall_ms", System.currentTimeMillis()).put("samples", metadataSamples)
            .put("provider_writes", false).put("queue_restore", "Host must restore the exact cold AVD checkpoint")
        var failure: Throwable? = null
        var played = false
        var songObserved = false
        var coverObserved = false
        var changed = false
        var stationArtwork: String? = null
        var previousSong: String? = null
        var previousArt: String? = null
        var stationIdentity: String? = null
        var screenshots = 0
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            ui.launch()
            openRadio()
            val station = findAppleMusicOne()
            report.put("selected_card", station).put("selected_label", ui.label(station))
            val started = SystemClock.elapsedRealtime()
            click(station)
            ui.awaitId("player-screen", 10_000)
            val observeMs = (args.getString("metadata_observe_ms")?.toLongOrNull() ?: 180_000L).coerceIn(15_000L, 180_000L)
            while (SystemClock.elapsedRealtime() - started < observeMs) {
                check(statusMessages().isEmpty()) { "Station reported: ${statusMessages().joinToString()}" }
                val sample = observe().put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                val system = readSystemMetadata()
                sample.put("system_metadata", system)
                val title = system.optString("title")
                val artist = system.optString("artist")
                val artwork = system.optString("artwork_uri")
                val identity = system.optString("media_id")
                if (title.contains("Apple Music 1", true) && artwork.isNotBlank() && stationArtwork == null) {
                    stationArtwork = artwork
                    report.put("station_artwork_uri", artwork)
                }
                if (state() == "playing") {
                    played = true
                    if (stationIdentity == null && identity.isNotBlank()) stationIdentity = identity
                    if (identity.isNotBlank() && stationIdentity != null) check(identity == stationIdentity) {
                        "Live song presentation must retain the selected station MediaSession identity"
                    }
                    val visibleTexts = withNode("player-screen") { texts(it) }
                    val actualSong = title.isNotBlank() && !title.contains("Apple Music 1", true) && artist.isNotBlank() &&
                        title in visibleTexts && artist in visibleTexts
                    val artworkReady = runCatching { withNode("player-artwork") {
                        it.stateDescription?.toString().orEmpty() in ui.stringLabels("player_artwork_loaded")
                    } }.getOrDefault(false)
                    sample.put("visible_song_identity", actualSong).put("artwork_ready", artworkReady)
                    if (actualSong) {
                        songObserved = true
                        val key = "$title|$artist"
                        val realCoverUri = artwork.startsWith("https://") && artwork.contains(".mzstatic.com/image/")
                        val artChanged = (stationArtwork != null && artwork != stationArtwork) ||
                            (previousArt != null && artwork != previousArt)
                        if (previousSong != null && previousSong != key) changed = true
                        if (realCoverUri && artChanged && artworkReady && screenshots < 6) {
                            val crop = captureArtworkCrop("metadata-${screenshots++}")
                            sample.put("artwork_crop", crop)
                            if (crop != null) { coverObserved = true; changed = true }
                        }
                        if (realCoverUri) previousArt = artwork
                        previousSong = key
                    }
                }
                metadataSamples.put(sample)
                if (songObserved && coverObserved && changed) break
                if (!played && SystemClock.elapsedRealtime() - started > 45_000L) error("Station never reached Playing")
                // No corrective focus or transport action while observing the broadcaster.
                ui.settle(1_500L)
            }
            report.put("actual_playing_observed", played).put("nonstation_song_observed", songObserved)
                .put("changed_song_artwork_observed", coverObserved).put("metadata_change_observed", changed)
                .put("station_media_id", stationIdentity)
                .put("status", if (played && songObserved && coverObserved && changed) "passed_public_song_and_artwork_transition"
                    else "partial_broadcaster_song_or_artwork_not_observed")
                .put("artwork_evidence", "Exact public MediaSession artwork URI changed; app artwork ready; full screenshot and crop retained for host visual confirmation")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            try {
                // Cleanup only: a live stream's Pause is implemented as real Stop.
                ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
                if (ui.has("player-screen")) {
                    val stopped = runCatching {
                        ui.await("Cleanup Media Pause must stop station audio", 8_000L) { state() == "paused" }
                    }.isSuccess
                    if (!stopped) pausePlayer()
                }
                report.put("cleanup", "audio_paused")
            } catch (cleanup: Throwable) {
                report.put("cleanup", "failed").put("cleanup_error", cleanup.message?.take(500))
                if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
            } finally {
                runCatching {
                    val pid = ui.device.executeShellCommand("pidof $TARGET").trim()
                    if (pid.matches(Regex("[0-9]+"))) File(directory, "station-engine.log").writeText(
                        ui.device.executeShellCommand("logcat -d -v epoch --pid=$pid -t 500 -s AMATV-Station:I '*:S'"))
                }
                instrumentation.uiAutomation.dropShellPermissionIdentity()
                report.put("end_wall_ms", System.currentTimeMillis())
                File(directory, "result.json").writeText(report.toString(2))
                ui.close()
            }
        }
        failure?.let { throw it }
    }

    /** Exercise the real spectrum consumer, then prove pause invalidates its samples. */
    @Test fun liveSpectrumFollowsPlaybackAndStopsOnPause() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_station_playback") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || args.getString("allow_physical_spectrum") == "1")
        val run = requireNotNull(args.getString("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "station-playback/$run")
        check(!directory.exists() && directory.mkdirs())
        ui = RenderingSurveyUi(instrumentation)
        report = JSONObject().put("run_id", run).put("provider_writes", false)
        var failure: Throwable? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            ui.launch()
            openRadio()
            val station = findAppleMusicOne()
            report.put("station", ui.label(station))
            click(station)
            ui.awaitId("player-screen", 10_000L)
            ui.await("Real station must play before FFT observation", 35_000L) {
                check(statusMessages().isEmpty()) { "Station failed: ${statusMessages()}" }
                state() == "playing"
            }
            val playing = collectSpectrum("$run-playing", 5_000)
            report.put("playing_samples", playing)
            val signal = (0 until playing.length()).map { playing.getJSONObject(it) }
                .filter { it.optBoolean("captured") && it.optBoolean("available") }
            check(signal.size >= 10) { "No sustained real station spectrum (${signal.size} signal samples)" }
            val spread = listOf("bass", "mid", "treble").maxOf { band ->
                signal.maxOf { it.getDouble(band) } - signal.minOf { it.getDouble(band) }
            }
            check(spread > .01) { "Station spectrum must vary with the actual audio" }
            report.put("signal_samples", signal.size).put("maximum_band_range", spread)
            check(state() == "playing") { "Observing FFT must preserve playback" }
            ui.device.takeScreenshot(File(directory, "spectrum-playing.png"))
            File(directory, "audioflinger.txt").writeText(ui.device.executeShellCommand("dumpsys media.audio_flinger"))
            ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ui.await("Pause must stop the actual station", 8_000L) { state() == "paused" }
            val paused = collectSpectrum("$run-paused", 700)
            report.put("paused_samples", paused)
            check((0 until paused.length()).all { index -> paused.getJSONObject(index).let {
                !it.optBoolean("captured") && !it.optBoolean("available") &&
                    it.optDouble("bass") == 0.0 && it.optDouble("mid") == 0.0 && it.optDouble("treble") == 0.0
            } }) { "Paused radio retained stale FFT energy" }
            ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY)
            ui.await("The same station must resume", 30_000L) { state() == "playing" }
            val resumed = collectSpectrum("$run-resumed", 2_000)
            report.put("resumed_samples", resumed)
            check((0 until resumed.length()).count { resumed.getJSONObject(it).optBoolean("available") } >= 3) {
                "Station resume did not restore actual FFT"
            }
            report.put("back_steps", ui.leavePlayerWithBack())
            val browsing = collectSpectrum("$run-browsing", 1_000)
            report.put("browsing_samples", browsing)
            check((0 until browsing.length()).all { index -> browsing.getJSONObject(index).let {
                it.optBoolean("playing") && !it.optBoolean("captured") && !it.optBoolean("available")
            } }) { "Leaving the player must stop FFT while retaining background playback" }
            check(browsing.getJSONObject(browsing.length() - 1).getLong("position_ms") >
                browsing.getJSONObject(0).getLong("position_ms")) { "Background audio clock stopped when FFT was disabled" }
            report.put("status", "passed_real_spectrum_pause_resume_and_background")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            runCatching {
                // A wake/HDMI transition may have taken the foreground. Never
                // send a global cleanup key to the television's other app.
                instrumentation.context.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(null).firstOrNull { it.packageName == TARGET }?.transportControls?.pause()
                if (ui.has("player-screen")) ui.await("Cleanup audio pause", 8_000L) { state() == "paused" }
                else {
                    val stopped = collectSpectrum("$run-cleanup", 1_500)
                    check(!stopped.getJSONObject(stopped.length() - 1).optBoolean("playing")) { "Cleanup must stop background audio" }
                }
            }
                .onFailure { if (failure == null) failure = it }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun collectSpectrum(run: String, duration: Int): JSONArray {
        val physical = if (InstrumentationRegistry.getArguments().getString("allow_physical_spectrum") == "1") " --ez allow_physical true" else ""
        ui.device.executeShellCommand("am broadcast -n dev.amatv/dev.amatv.benchmark.RuntimeContinuityProbeReceiver --ez inspect_only true --ei spectrum_samples_ms $duration --es run_id $run$physical")
        val path = "/sdcard/Android/data/dev.amatv/files/runtime-probe/$run/result.json"
        var result: JSONArray? = null
        ui.await("Real spectrum probe did not complete", duration + 5_000L) {
            result = runCatching { JSONObject(ui.device.executeShellCommand("cat $path")).getJSONArray("samples") }.getOrNull()
            result != null
        }
        return requireNotNull(result)
    }

    private fun readSystemMetadata(): JSONObject {
        val session = instrumentation.context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(null).firstOrNull { it.packageName == TARGET }
        val metadata = session?.metadata ?: return JSONObject().put("available", false)
        return JSONObject().put("available", true)
            .put("media_id", metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty())
            .put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            .put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            .put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            .put("artwork_uri", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty())
            .put("state", session.playbackState?.state ?: -1)
            .put("position_ms", session.playbackState?.position ?: -1L)
            .put("duration_ms", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }

    private fun captureArtworkCrop(name: String): String? {
        val full = File(directory, "$name.png")
        if (!ui.device.takeScreenshot(full)) return null
        val bitmap = BitmapFactory.decodeFile(full.absolutePath) ?: return null
        try {
            val bounds = withNode("player-artwork") { Rect().also(it::getBoundsInScreen) }
            if (!bounds.intersect(0, 0, bitmap.width, bitmap.height) || bounds.width() < 30 || bounds.height() < 30) return null
            val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
            try { File(directory, "$name-cover.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { if (cropped !== bitmap) cropped.recycle() }
            return "$name-cover.png"
        } finally { bitmap.recycle() }
    }

    private fun openRadio() {
        ui.navigate("home")
        ui.openNavigation()
        ui.awaitId("nav-radio", 8_000)
        click("nav-radio")
        ui.awaitGone("nav-panel")
        ui.awaitId("browse-radio", 12_000)
        ui.await("Radio did not expose any actual station", 45_000) {
            ui.snapshot().ids.any { it.matches(STATION) }
        }
        // Radio can add a personal shelf independently; settle its first pages
        // before resolving a card by its public label, never by guessed index.
        ui.settle(1_000)
        ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 300)
    }

    private fun findAppleMusicOne(): String = findStation("Apple Music 1")

    private fun findStation(label: String): String {
        repeat(24) {
            val snapshot = ui.snapshot()
            val match = snapshot.ids.firstOrNull { id -> id.matches(STATION) &&
                ui.label(id).contains(label) && snapshot.nodes.any { node ->
                    node.id == id && Rect.unflattenFromString(node.bounds)?.let { bounds ->
                        bounds.top >= 0 && bounds.bottom <= ui.device.displayHeight && bounds.width() > 30 && bounds.height() > 30
                    } == true
                }
            }
            if (match != null) return match
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 260)
        }
        throw AssertionError("No fully visible, genuinely labeled $label station was found; no other station may substitute")
    }

    private fun pausePlayer() {
        if (state() != "paused") {
            wakeControls()
            click("player-play-pause")
        }
        ui.await("Cleanup must leave station audio paused", 8_000) { state() == "paused" }
    }

    /** Complete only the remaining transport check; do not repeat the stream survey. */
    @Test fun remotePauseStopsTheExistingStation() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("allow_station_playback") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val runId = requireNotNull(args.getString("run_id"))
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "station-playback/$runId")
        check(!directory.exists() && directory.mkdirs())
        ui = RenderingSurveyUi(instrumentation)
        report = JSONObject().put("run_id", runId).put("station_label", "Apple Music 1")
        try {
            ui.launch()
            if (!ui.has("player-screen")) {
                ui.openNavigation()
                ui.activate("nav-nowplaying", "navigation")
                ui.awaitId("player-screen")
            }
            check(ui.label("player-artwork").contains("Apple Music 1")) { "Only the retained, already-tested live station may resume" }
            wakeControls()
            if (state() == "paused") click("player-play-pause")
            ui.await("Retained station must resume before remote Pause", 30_000) { state() == "playing" }
            report.put("before_remote", observe())
            ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ui.await("Media Pause must stop actual station playback", 10_000) { state() == "paused" }
            ui.settle(1_500L)
            check(state() == "paused") { "Station resumed after remote Pause" }
            report.put("after_remote", observe()).put("status", "passed_remote_pause")
        } finally {
            try { if (ui.has("player-screen")) pausePlayer() }
            finally {
                File(directory, "result.json").writeText(report.toString(2))
                ui.close()
            }
        }
    }

    private fun wakeControls() {
        // A visible controls snapshot can expire before the next node lookup.
        // A direction refreshes its inactivity timer without activating media.
        ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 200)
        ui.awaitId("player-play-pause", 3_000)
    }

    private fun state(): String = withNode("player-screen") { node ->
        val label = node.stateDescription?.toString().orEmpty()
        listOf("playing", "paused", "preparing").firstOrNull { label in ui.stringLabels("player_state_$it") } ?: "unknown"
    }

    private fun observe(): JSONObject {
        val result = JSONObject().put("has_player", ui.has("player-screen"))
        if (!ui.has("player-screen")) return result
        result.put("state", state()).put("artwork_label", ui.label("player-artwork"))
        val elapsed = runCatching {
            withNode("player-progress") { node ->
                texts(node).firstOrNull { it.matches(Regex("\\d+:\\d{2}(?::\\d{2})?")) }
                    ?.split(':')?.fold(0L) { sum, component -> sum * 60L + component.toLong() }?.times(1_000L)
            }
        }.getOrNull()
        result.put("elapsed_ms", elapsed ?: -1L)
        return result
    }

    private fun statusMessages(): List<String> {
        val expected = listOf("services_playback_failed", "services_playback_auth_required", "services_apple_connection_failed",
            "services_track_unavailable", "services_station_unsupported").flatMap(ui::stringLabels).toSet()
        return runCatching { withNode("player-screen") { node -> texts(node).filter { it in expected }.distinct() } }
            .getOrDefault(emptyList())
    }

    private fun texts(node: AccessibilityNodeInfo): List<String> {
        check(node.refresh())
        val output = mutableListOf<String>()
        node.text?.toString()?.let(output::add)
        node.contentDescription?.toString()?.let(output::add)
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            try { output += texts(child) } finally { child.recycle() }
        }
        return output
    }

    private fun click(id: String) {
        check(id == "nav-radio" || id == "player-play-pause" || id.matches(STATION))
        withNode(id) { node ->
            check(node.packageName?.toString() == TARGET && node.isVisibleToUser && node.isEnabled && node.isClickable)
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Public activation failed: $id" }
        }
        ui.settle(180)
    }

    private fun <T> withNode(id: String, read: (AccessibilityNodeInfo) -> T): T {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.viewIdResourceName?.substringAfter(":id/") == id && node.isVisibleToUser) return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { find(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: throw AssertionError("No active app window")
        val found = try {
            check(root.packageName?.toString() == TARGET) { "Another window owns input" }
            find(root)
        } finally { root.recycle() } ?: throw AssertionError("Public node unavailable: $id")
        return try { read(found) } finally { found.recycle() }
    }

    companion object { private val STATION = Regex("shelf-\\d+-item-\\d+-station") }
}
