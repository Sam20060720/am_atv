package dev.amatv.benchmark

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
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
import java.security.MessageDigest

/** Self-instrumented against the exact installed standalone; no target test receiver or SDK preparation. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ProductionPlaybackJourneyTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi
    private lateinit var out: File
    private lateinit var report: JSONObject
    private lateinit var listener: ComponentName
    private lateinit var mediaManager: MediaSessionManager
    private val stages = JSONArray()
    private var expectedPid = ""
    private var expectedProcessWitness: JSONObject? = null
    private val processConfirmations = JSONArray()
    private var originalIdentity = ""
    private var playDispatched = false
    private var lastSnapshot: JSONObject? = null
    private var fullJourney = false
    private var sampleStageResources = false
    private var continueSafe = false
    private var collectCpuBackdrop = false
    private var deadlineElapsedMs = Long.MAX_VALUE

    @Test fun exactStandaloneColdPlay() = runJourney()

    /** Independent of Library/collection loading; reports only the production controls section. */
    @Test fun productionControlsJourney() = runJourney(controlsOnly = true)

    /** Actual entry paths, without repeating browse or retained-song controls acceptance. */
    @Test fun productionEntryJourney() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_production_journey") == "1")
        check(args.getString("experience_checkpoint_ready") == "1")
        runJourney(entriesOnly = true)
    }

    /** Scoped live radio, native handoff and reversible settings; no passed collection journeys repeated. */
    @Test fun productionRadioJourney() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_production_journey") == "1")
        check(args.getString("experience_checkpoint_ready") == "1")
        runJourney(radioOnly = true)
    }

    /** Remaining browse/menu/artist checks without repeating playback or changing the retained Queue. */
    @Test fun productionBrowseJourney() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_production_journey") == "1")
        check(args.getString("experience_checkpoint_ready") == "1")
        runJourney(browseOnly = true)
    }

    /** Isolate Settings GPU/resource use without any playback activation. */
    @Test fun productionSettingsJourney() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_production_journey") == "1")
        check(args.getString("experience_checkpoint_ready") == "1")
        runJourney(settingsOnly = true)
    }

    /** Explicit opt-in: real end-of-track auto advancement, never a manual Next or seeded Queue. */
    @Test fun productionNaturalHandoffJourney() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_production_journey") == "1")
        check(args.getString("experience_checkpoint_ready") == "1")
        runJourney(naturalOnly = true)
    }

    /** Read the already-owned app window only; no launch, navigation, focus or media action. */
    @Test fun publicSnapshotOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_production_journey") == "1")
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val folder = File(checkNotNull(inst.context.getExternalFilesDir(null)), "production-journey/$run")
        check(!folder.exists() && folder.mkdirs())
        val reader = RenderingSurveyUi(inst)
        try {
            val state = reader.snapshot()
            val result = JSONObject().put("run_id", run).put("hardware", Build.HARDWARE)
                .put("ui_actions", false).put("foreground", state.foreground)
                .put("observed_elapsed_ms", SystemClock.elapsedRealtime()).put("snapshot", state.json(detailed = true))
            if (state.foreground == TARGET) runCatching { currentPublicTexts() }
                .onSuccess { result.put("public_texts", it) }.onFailure { result.put("public_text_error", it.toString()) }
            result.put("status", if (state.foreground == TARGET) "observed_owned_window" else "target_not_foreground")
            File(folder, "snapshot.json").writeText(result.toString(2))
        } finally { reader.close() }
    }

    @Test fun fullOrderedProductionJourney() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allow_production_full_journey") == "1")
        check(InstrumentationRegistry.getArguments().getString("experience_checkpoint_ready") == "1") {
            "Full journey requires root's additional history/artwork/content-cache preference checkpoint"
        }
        runJourney(full = true)
    }

    private fun runJourney(full: Boolean = false, controlsOnly: Boolean = false, entriesOnly: Boolean = false, radioOnly: Boolean = false, browseOnly: Boolean = false, settingsOnly: Boolean = false, naturalOnly: Boolean = false) {
        fullJourney = full || controlsOnly || entriesOnly || radioOnly || browseOnly || settingsOnly || naturalOnly
        sampleStageResources = full || browseOnly || radioOnly || entriesOnly || settingsOnly
        deadlineElapsedMs = SystemClock.elapsedRealtime() + when {
            full -> 12 * 60_000L
            naturalOnly -> 75_000L
            settingsOnly -> 3 * 60_000L
            browseOnly -> 10 * 60_000L
            radioOnly -> 5 * 60_000L
            entriesOnly -> 8 * 60_000L
            controlsOnly -> 4 * 60_000L
            else -> 120_000L
        }
        val args = InstrumentationRegistry.getArguments()
        continueSafe = args.getString("continue_safe") == "1"
        collectCpuBackdrop = args.getString("collect_cpu_backdrop") == "1"
        check(!continueSafe || full || entriesOnly || radioOnly) { "Safe continuation is supported only for full/entries/radio independent scenarios" }
        assumeTrue(args.getString("allow_production_journey") == "1")
        check(args.getString("expected_hardware") == "m7632" && Build.HARDWARE == "m7632")
        check(args.getString("playback_checkpoint_ready") == "1") { "Root-owned exact playback checkpoint is required" }
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val expectedHash = checkNotNull(args.getString("expected_apk_sha256")).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val expectedTitle = if (browseOnly || settingsOnly || naturalOnly) args.getString("expected_current_title").orEmpty().trim()
            else checkNotNull(args.getString("expected_current_title")).trim().also { require(it.isNotEmpty()) }
        out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "production-journey/$run")
        check(!out.exists() && out.mkdirs())
        ui = RenderingSurveyUi(inst)
        report = JSONObject().put("run_id", run).put("hardware", Build.HARDWARE).put("expected_apk_sha256", expectedHash)
            .put("expected_current_title", expectedTitle).put("provider_writes", false).put("stages", stages).put("continue_safe", continueSafe).put("collect_cpu_backdrop", collectCpuBackdrop)
            .put("recovery", "Root restores the original private checkpoint; no Queue reconstruction")
        val planned = listOf("verify_exact_standalone", "grant_test_only_session_readback", "wake_tv_for_foreground") +
            (if (naturalOnly) listOf("natural_paths_launch_without_playback_seed") +
                (if (args.getString("preferred_album_card") != null || args.getString("preferred_album_title") != null) listOf("natural_public_preferred_album_fixture") else emptyList()) +
                listOf("natural_fixture_public_repeat_off", "natural_public_paused_seek", "natural_transition_without_manual_next", "natural_new_song_progress_and_ui_pause") else if (settingsOnly) listOf("settings_paths_launch_without_playback_seed") else if (browseOnly) listOf("browse_paths_launch_without_playback_seed") else emptyList()) +
            (if (full || browseOnly) listOf("home_real_dpad_round_trip", "new_actual_content_and_return", "search_history_ime_and_remote_suggestions",
                "library_actual_categories", "album_detail_menu_back", "playlist_detail_menu_back", "artist_actual_content_back") else emptyList()) +
            (if (browseOnly || settingsOnly || naturalOnly) emptyList() else if (entriesOnly || radioOnly) listOf("entry_paths_launch_without_playback_seed") else listOf("force_stop_and_cold_launch", "guard_retained_paused_song", "first_production_play",
                "same_song_twelve_second_continuity", "production_ui_pause_is_responsive")) +
            (if (full || controlsOnly) listOf("paused_clock_resume_and_real_seek", "real_lyrics_and_queue_idle_direction", "background_foreground_without_restart") else emptyList()) +
            (if (full || entriesOnly) listOf("home_song_card_actual_play_back", "album_production_play_next_previous", "playlist_production_play") else emptyList()) +
            (if (full || entriesOnly || radioOnly) listOf("radio_real_progress_and_metadata", "radio_to_song_handoff") else emptyList()) +
            (if (full || entriesOnly || radioOnly || settingsOnly) listOf("settings_reads_and_reversible_motion_toggle") else emptyList())
        report.put("planned_stages", JSONArray(planned)).put("full_journey", full)
            .put("section", if (naturalOnly) "natural_handoff_only" else if (settingsOnly) "settings_ui_only" else if (browseOnly) "browse_ui_only" else if (radioOnly) "radio_song_settings_only" else if (entriesOnly) "entry_paths_only" else if (controlsOnly) "cold_play_and_production_controls_only" else if (full) "full_ordered_journey" else "exact_standalone_cold_play")
        listener = ComponentName(inst.context, PerformanceNotificationListener::class.java)
        mediaManager = inst.context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val notificationManager = inst.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val accessBefore = notificationManager.isNotificationListenerAccessGranted(listener)
        val user = ui.device.executeShellCommand("am get-current-user").trim().also { require(it.matches(Regex("\\d+"))) }
        fun listeners() = ui.device.executeShellCommand("settings --user $user get secure enabled_notification_listeners")
            .trim().split(':').mapNotNull(ComponentName::unflattenFromString).toSet()
        val listenersBefore = listeners()
        var granted = false
        var failure: Throwable? = null
        try {
            stage("verify_exact_standalone") {
                val app = inst.context.packageManager.getApplicationInfo(TARGET, 0)
                check(app.splitSourceDirs.isNullOrEmpty()) { "This gate expects the exact universal standalone APK" }
                val actual = File(app.sourceDir).inputStream().buffered().use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                }
                check(actual == expectedHash) { "Installed APK does not match the authorized standalone digest" }
                val receivers = inst.context.packageManager.getPackageInfo(TARGET, PackageManager.GET_RECEIVERS).receivers.orEmpty()
                check(receivers.none { it.name.startsWith("dev.amatv.benchmark.") }) { "Target contains benchmark receivers" }
                JSONObject().put("actual_apk_sha256", actual).put("benchmark_receivers_absent", true)
            }
            stage("grant_test_only_session_readback") {
                if (!accessBefore) {
                    val help = ui.device.executeShellCommand("cmd notification help")
                    check("allow_listener" in help && "disallow_listener" in help)
                    granted = true
                    ui.device.executeShellCommand("cmd notification allow_listener ${listener.flattenToString()} $user")
                }
                ui.await("Test notification listener was not granted", 5_000) { notificationManager.isNotificationListenerAccessGranted(listener) }
                if (!PerformanceNotificationListener.connected) NotificationListenerService.requestRebind(listener)
                ui.await("Test notification listener did not connect", 8_000) { PerformanceNotificationListener.connected }
                JSONObject().put("access_before", accessBefore).put("granted_by_test", granted)
            }
            stage("wake_tv_for_foreground") {
                fun wakefulness() = ui.device.executeShellCommand("dumpsys power").lineSequence()
                    .firstOrNull { "mWakefulness=" in it }?.trim().orEmpty()
                val before = wakefulness()
                // A dreaming TV can report its panel as on. UiDevice.wakeUp()
                // alone may then do nothing; this explicit wake key exits dream.
                val wakeRequired = !ui.device.isScreenOn || "mWakefulness=Awake" !in before
                if (wakeRequired) ui.device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
                ui.await("TV remained asleep/dreaming; no app launch or Play attempted", 6_000) {
                    "mWakefulness=Awake" in wakefulness() && ui.device.isScreenOn
                }
                // This vendor posts a delayed launcher/Home after wake. Let it
                // settle before starting AMATV; never keep waking an awake TV.
                if (wakeRequired) ui.settle(1_200)
                JSONObject().put("before", before).put("after", wakefulness()).put("screen_on", ui.device.isScreenOn)
                    .put("action", if (wakeRequired) "KEYCODE_WAKEUP_then_1200ms_settle" else "none_already_awake").put("playback_action", false)
            }
            if (naturalOnly) {
                stage("natural_paths_launch_without_playback_seed") {
                    val before = pid()
                    check(before.matches(Regex("\\d+"))) { "Natural fixture requires the existing warm target process" }
                    ui.launch(); expectedPid = pid()
                    check(expectedPid == before) { "Natural fixture warm launch replaced the original process" }
                    captureExpectedProcess()
                    JSONObject().put("pid_before", before).put("pid_after", expectedPid).put("same_pid", true)
                        .put("sdk_seed_or_prepare", false).put("manual_next_calls", 0)
                }
                naturalHandoffStages()
                report.put("status", "passed")
            } else if (settingsOnly) {
                stage("settings_paths_launch_without_playback_seed") {
                    ui.launch(); expectedPid = pid().also { check(it.matches(Regex("\\d+"))) }
                    captureExpectedProcess()
                    check(ownSession()?.playbackState?.state != PlaybackState.STATE_PLAYING) { "Settings fixture must already be paused" }
                    JSONObject().put("pid", expectedPid).put("sdk_seed_or_prepare", false).put("playback_actions", false)
                }
                stage("settings_reads_and_reversible_motion_toggle") { settingsReadAndToggle() }
                report.put("status", "passed")
            } else if (browseOnly) {
                stage("browse_paths_launch_without_playback_seed") {
                    ui.launch(); expectedPid = pid().also { check(it.matches(Regex("\\d+"))) }
                    captureExpectedProcess()
                    check(ownSession()?.playbackState?.state != PlaybackState.STATE_PLAYING) { "Browse fixture must already be paused" }
                    JSONObject().put("pid", expectedPid).put("sdk_seed_or_prepare", false).put("playback_actions", false)
                }
                browseStages()
                report.put("status", "passed")
                check(ui.device.takeScreenshot(File(out, "browse-journey-end.png")))
            } else if (entriesOnly || radioOnly) {
                stage("entry_paths_launch_without_playback_seed") {
                    ui.launch(); expectedPid = pid().also { check(it.matches(Regex("\\d+"))) }
                    captureExpectedProcess()
                    check(ownSession()?.playbackState?.state != PlaybackState.STATE_PLAYING) { "Entry fixture must already be paused" }
                    JSONObject().put("pid", expectedPid).put("sdk_seed_or_prepare", false)
                }
                playbackAndEntryStages(entriesOnly = true, radioOnly = radioOnly)
                report.put("status", "passed")
                check(ui.device.takeScreenshot(File(out, if (radioOnly) "radio-journey-end.png" else "entry-journey-end.png")))
            } else {
            if (full) browseStages()
            stage("force_stop_and_cold_launch") {
                val old = pid()
                ui.device.executeShellCommand("am force-stop $TARGET")
                check(pid().isEmpty()) { "Target did not stop; refusing a warm Play test" }
                ui.launch()
                expectedPid = pid().also { check(it.matches(Regex("\\d+")) && it != old) }
                captureExpectedProcess()
                JSONObject().put("old_pid", old).put("cold_pid", expectedPid).put("seed_or_prepare_actions", false)
            }
            stage("guard_retained_paused_song") {
                alive()
                if (!ui.has("player-screen")) {
                    ui.openNavigation()
                    check(ui.has("nav-nowplaying")) { "No retained player; seeding is prohibited" }
                    ui.activate("nav-nowplaying", "navigation")
                    ui.awaitId("player-screen", 8_000)
                }
                wake()
                check(ui.playerPaused()) { "Retained production player is not paused" }
                val label = ui.label("player-artwork")
                check(label.contains(expectedTitle, ignoreCase = true)) { "Retained artwork does not match the authorized title" }
                val remaining = clockMs(ui.label("player-progress-remaining"), remaining = true)
                check(remaining >= 20_000) { "Insufficient safe remaining time for cold Play" }
                val existing = ownSession()
                check(existing?.playbackState?.state != PlaybackState.STATE_PLAYING) { "Cold launch auto-started playback" }
                rememberSnapshot()
                JSONObject().put("artwork_label", label).put("remaining_ms", remaining).put("ui_paused", true)
                    .put("session_present_before_play", existing != null)
            }
            stage("first_production_play") {
                check(!playDispatched)
                transport("player_play") { playDispatched = true }
                awaitHealthy("First public Play did not establish the expected real song", 20_000) {
                    val media = ownSession()
                    media?.playbackState?.state == PlaybackState.STATE_PLAYING &&
                        media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().equals(expectedTitle, ignoreCase = true)
                }
                val media = checkNotNull(ownSession())
                originalIdentity = identity(media)
                JSONObject().put("media", mediaState(media)).put("seed_or_sdk_hook_used", false)
            }
            stage("same_song_twelve_second_continuity") {
                val initial = mediaState(checkNotNull(ownSession()))
                val samples = JSONArray().put(initial)
                val deadline = SystemClock.elapsedRealtime() + 12_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(500)
                    alive()
                    val media = checkNotNull(ownSession()) { "Owned session disappeared during first playback" }
                    check(identity(media) == originalIdentity && media.playbackState?.state == PlaybackState.STATE_PLAYING)
                    samples.put(mediaState(media))
                    File(out, "cold-play-samples.json").writeText(samples.toString(2))
                }
                val last = samples.getJSONObject(samples.length() - 1)
                check(last.getLong("projected_position_ms") - initial.getLong("projected_position_ms") >= 10_000)
                // The production MediaSession refreshes its raw anchor at least
                // every 10s. Projection alone could advance while the UI is frozen.
                check(last.getLong("position_updated_elapsed_ms") > initial.getLong("position_updated_elapsed_ms") &&
                    last.getLong("position_ms") - initial.getLong("position_ms") >= 5_000) {
                    "Only a projected clock advanced; no fresh production position publication"
                }
                JSONObject().put("samples", samples).put("same_pid", true).put("same_identity", true)
                    .put("fresh_native_position_publication", true)
            }
            stage("production_ui_pause_is_responsive") {
                transport("player_pause")
                awaitHealthy("Production Pause did not respond after first playback", 5_000) {
                    ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED && ui.playerPaused()
                }
                rememberSnapshot()
                JSONObject().put("media", mediaState(checkNotNull(ownSession()))).put("public_ui_pause", true)
            }
            if (full || controlsOnly) playbackAndEntryStages(controlsOnly)
            report.put("status", "passed")
            check(ui.device.takeScreenshot(File(out, if (controlsOnly) "controls-journey-end.png" else if (full) "full-journey-end.png" else "cold-play-paused.png")))
            }
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.stackTraceToString().take(12_000))
                .put("observed_pid_at_failure", runCatching(::pid).getOrDefault("unavailable"))
            lastSnapshot?.let { report.put("last_safe_public_snapshot", it) }
            captureCurrentFailureSnapshot()
            if (fullJourney && expectedPid.isNotBlank()) runCatching { captureResourceUsage("failure") }
                .onSuccess { report.put("failure_resources", it) }.onFailure { report.put("failure_resources_error", it.toString()) }
        } finally {
            val cleanup = JSONObject()
            try {
                // Only our package's actual session; never a device-global media key.
                if (playDispatched) ownSession()?.let { media ->
                    media.transportControls.pause()
                    ui.await("Owned cleanup Pause did not respond", 5_000) {
                        ownSession()?.playbackState?.state?.let { it != PlaybackState.STATE_PLAYING && it != PlaybackState.STATE_BUFFERING } != false
                    }
                    cleanup.put("owned_session_paused", true)
                }
                cleanup.put("status", "passed").put("checkpoint_restore_required", true)
            } catch (error: Throwable) {
                cleanup.put("status", "failed").put("error", error.toString())
                // A frozen owned process must not keep making sound after a
                // failed Pause. Its crash/last-state marker was saved above.
                ui.device.executeShellCommand("am force-stop $TARGET")
                cleanup.put("owned_target_force_stopped", pid().isEmpty())
                report.put("status", "failed")
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally {
                try {
                    if (granted) ui.device.executeShellCommand("cmd notification disallow_listener ${listener.flattenToString()} $user")
                    ui.await("Listener access did not restore", 5_000) { notificationManager.isNotificationListenerAccessGranted(listener) == accessBefore }
                    check(listeners() == listenersBefore) { "Other notification-listener access changed" }
                    cleanup.put("listener_access_restored", true)
                } catch (error: Throwable) {
                    cleanup.put("listener_error", error.toString()).put("status", "failed")
                    report.put("status", "failed")
                    if (failure == null) failure = error else failure.addSuppressed(error)
                } finally {
                    report.put("cleanup", cleanup)
                    val outcomes = JSONArray()
                    planned.forEach { name ->
                        val actual = (0 until stages.length()).map(stages::getJSONObject).firstOrNull { it.optString("name") == name }
                        outcomes.put(JSONObject().put("stage", name).put("status", actual?.optString("status") ?: "not_run_after_prior_failure"))
                    }
                    report.put("stage_outcomes", outcomes)
                    val incomplete = (0 until outcomes.length()).map(outcomes::getJSONObject)
                        .filter { it.optString("status") != "passed" }
                    if (incomplete.isNotEmpty()) {
                        report.put("status", "failed").put("all_planned_stages_passed", false)
                        if (failure == null) failure = IllegalStateException("Journey has failed or missing planned stages: " + incomplete.joinToString { it.getString("stage") })
                    } else report.put("all_planned_stages_passed", true)
                    File(out, "result.json").writeText(report.toString(2))
                    ui.close()
                }
            }
        }
        failure?.let { throw it }
    }

    private fun captureResourceUsage(label: String): JSONObject {
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(out, "resources").also { check(it.exists() || it.mkdirs()) }
        val sample = JSONObject().put("observed_elapsed_ms", SystemClock.elapsedRealtime()).put("expected_pid", expectedPid)
        for (service in listOf("meminfo", "gfxinfo")) {
            val started = SystemClock.elapsedRealtime()
            runCatching { ui.device.executeShellCommand("dumpsys -t 3 $service $TARGET") }
                .onSuccess { raw ->
                    val text = raw.take(96 * 1024)
                    File(directory, "$label-$service.txt").writeText(text)
                    sample.put(service, JSONObject().put("file", "resources/$label-$service.txt")
                        .put("characters", raw.length).put("truncated", raw.length > text.length)
                        .put("timeout_seconds", 3).put("elapsed_ms", SystemClock.elapsedRealtime() - started))
                }.onFailure { sample.put(service, JSONObject().put("error", it.toString())
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started)) }
        }
        if (expectedPid.matches(Regex("\\d+"))) runCatching {
            ui.device.executeShellCommand("cat /proc/$expectedPid/status /proc/meminfo")
        }.onSuccess { raw ->
            val text = raw.take(32 * 1024)
            File(directory, "$label-proc-memory.txt").writeText(text)
            val counters = JSONObject().put("file", "resources/$label-proc-memory.txt").put("truncated", raw.length > text.length)
                .put("target_status_present", Regex("(?m)^Pid:\\s+$expectedPid$").containsMatchIn(raw))
            for (field in listOf("VmPeak", "VmSize", "VmRSS", "VmData", "VmSwap", "MemTotal", "MemFree", "MemAvailable", "Cached", "SwapFree")) {
                Regex("(?m)^$field:\\s+(\\d+)\\s+kB").find(raw)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let { counters.put("${field}_kb", it) }
            }
            sample.put("proc_memory", counters)
        }.onFailure { sample.put("proc_memory_error", it.toString()) }
        if (collectCpuBackdrop && expectedPid.matches(Regex("\\d+"))) runCatching {
            ui.device.executeShellCommand("logcat -b main -d --pid $expectedPid -v threadtime -s AMATV-lava-cpu:I '*:S'")
        }.onSuccess { raw ->
            val text = raw.take(16 * 1024)
            File(directory, "$label-cpu-backdrop.txt").writeText(text)
            sample.put("cpu_backdrop_worker_log", JSONObject().put("file", "resources/$label-cpu-backdrop.txt")
                .put("pid", expectedPid).put("characters", raw.length).put("truncated", raw.length > text.length)
                .put("source", "finite_main_logcat_dump_filtered_by_current_pid_and_AMATV-lava-cpu_tag")
                .put("timing_scope", "worker_render_only_not_ui_or_gpu_frame_timing")
                .put("filtered_tag_lines", raw.lineSequence().count { "AMATV-lava-cpu" in it }))
        }.onFailure { sample.put("cpu_backdrop_log_error", it.toString()) }
        File(directory, "$label.json").writeText(sample.toString(2))
        return sample
    }

    private fun stage(name: String, body: () -> JSONObject) {
        val value = JSONObject().put("name", name).put("start_elapsed_ms", SystemClock.elapsedRealtime())
        stages.put(value)
        File(out, "stage-current.json").writeText(value.toString(2))
        Log.i("AMATV-ProductionJourney", "${report.getString("run_id")}:$name:begin")
        inst.sendStatus(0, Bundle().apply {
            putString("amatv_stage", "$name:begin"); putString("amatv_run", report.getString("run_id"))
        })
        try {
            if (sampleStageResources && expectedPid.isNotBlank()) value.put("resources_before", captureResourceUsage("$name-before"))
            value.put("proof", body()).put("status", "passed")
            if (fullJourney && expectedPid.isNotBlank()) {
                alive(); rememberSnapshot()
                File(out, "$name-snapshot.json").writeText(checkNotNull(lastSnapshot).toString(2))
            }
        }
        catch (error: Throwable) {
            value.put("status", "failed").put("error", error.toString()).put("original_error_stack", error.stackTraceToString().take(12_000))
            if (continueSafe && error is SurveyUnavailable && isIndependentScenario(name)) {
                try { recoverIndependentScenario(name, value) }
                catch (unsafe: Throwable) {
                    value.put("continuation_aborted", unsafe.stackTraceToString().take(8_000))
                    unsafe.addSuppressed(error)
                    throw unsafe
                }
            } else throw error
        }
        finally {
            if (sampleStageResources && expectedPid.isNotBlank()) runCatching { captureResourceUsage("$name-after") }
                .onSuccess { value.put("resources_after", it) }.onFailure { value.put("resources_after_error", it.toString()) }
            value.put("end_elapsed_ms", SystemClock.elapsedRealtime())
            File(out, "stage-current.json").writeText(value.toString(2))
            File(out, "stages.json").writeText(stages.toString(2))
            Log.i("AMATV-ProductionJourney", "${report.getString("run_id")}:$name:${value.optString("status")}")
            inst.sendStatus(0, Bundle().apply {
                putString("amatv_stage", "$name:${value.optString("status")}"); putString("amatv_run", report.getString("run_id"))
            })
        }
    }

    private fun isIndependentScenario(name: String): Boolean = name in setOf(
        "home_real_dpad_round_trip", "new_actual_content_and_return", "search_history_ime_and_remote_suggestions",
        "library_actual_categories", "album_detail_menu_back", "playlist_detail_menu_back", "artist_actual_content_back",
        "home_song_card_actual_play_back", "album_production_play_next_previous", "playlist_production_play",
        "radio_real_progress_and_metadata", "radio_to_song_handoff", "settings_reads_and_reversible_motion_toggle",
    )

    /** Failures remain failures. Only establish a safe paused owner for the next independent public scenario. */
    private fun recoverIndependentScenario(name: String, failedStage: JSONObject) {
        val recovery = JSONObject().put("play_retry", false).put("selection_retry", false).put("relaunch", false)
        failedStage.put("safe_continuation", recovery)
        fun persist() { File(out, "$name-failed-continuation.json").writeText(failedStage.toString(2)) }
        fun confirmIncarnation(label: String) {
            val actual = processWitness(expectedPid)
            recovery.put(label, actual); persist()
            val baseline = checkNotNull(expectedProcessWitness) { "No initial process incarnation for safe continuation" }
            check(actual.getBoolean("identity_valid") && actual.getString("start_time_ticks") == baseline.getString("start_time_ticks") &&
                actual.getString("uid") == baseline.getString("uid") && actual.getString("cmdline") == baseline.getString("cmdline")) {
                "Unsafe continuation: target process incarnation vanished or changed"
            }
        }
        confirmIncarnation("process_before")
        check(ui.foreground() == TARGET) { "Unsafe continuation: target no longer owns foreground" }
        failedStage.put("failure_snapshot", ui.snapshot().json(detailed = true)).put("failure_public_texts", currentPublicTexts())
        persist()
        val media = ownSession()
        val state = media?.playbackState?.state
        recovery.put("playback_state_before", state ?: JSONObject.NULL)
        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
            checkNotNull(media).transportControls.pause()
            recovery.put("pause_action", "owned_MediaController_public_pause"); persist()
            awaitHealthy("Unsafe continuation: owned playback did not become paused", 5_000) {
                ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED
            }
        } else {
            check(state == null || state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE) {
                "Unsafe continuation: owned playback has unresolved state $state"
            }
            recovery.put("pause_action", "not_needed_no_active_playback")
        }
        confirmIncarnation("process_after")
        check(ui.foreground() == TARGET) { "Unsafe continuation: target lost foreground while pausing" }
        val afterState = ownSession()?.playbackState?.state
        recovery.put("playback_state_after", afterState ?: JSONObject.NULL); persist()
        check(afterState == null || afterState == PlaybackState.STATE_PAUSED || afterState == PlaybackState.STATE_STOPPED || afterState == PlaybackState.STATE_NONE) {
            "Unsafe continuation: playback resumed or became unresolved during recovery ($afterState)"
        }
        recovery.put("after_snapshot", ui.snapshot().json(detailed = true)).put("status", "safe_for_next_independent_scenario")
        persist()
        report.put("continued_failed_stage_count", report.optInt("continued_failed_stage_count") + 1)
    }

    private fun skippedDependentStage(name: String, dependency: String) {
        val value = JSONObject().put("name", name).put("status", "not_run_dependency_failed")
            .put("dependency", dependency).put("play_action", false).put("end_elapsed_ms", SystemClock.elapsedRealtime())
        stages.put(value)
        File(out, "stages.json").writeText(stages.toString(2))
        inst.sendStatus(0, Bundle().apply {
            putString("amatv_stage", "$name:not_run_dependency_failed"); putString("amatv_run", report.getString("run_id"))
        })
    }

    private fun captureCurrentFailureSnapshot() {
        runCatching {
            if (ui.foreground() == TARGET) {
                val current = JSONObject().put("observed_elapsed_ms", SystemClock.elapsedRealtime())
                    .put("snapshot", ui.snapshot().json(detailed = true))
                runCatching { currentPublicTexts() }.onSuccess { current.put("public_texts", it) }
                    .onFailure { current.put("public_text_error", it.toString()) }
                report.put("failure_snapshot", current)
                File(out, "failure-snapshot.json").writeText(current.toString(2))
            } else report.put("failure_snapshot_status", "target_not_foreground")
        }.onFailure { report.put("failure_snapshot_error", it.toString()) }
    }

    private fun currentPublicTexts(): JSONArray {
        val root = checkNotNull(inst.uiAutomation.rootInActiveWindow)
        val values = JSONArray()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (++visited > 2_500 || values.length() >= 300) return
            if (node.packageName?.toString() == TARGET && node.isVisibleToUser) {
                val text = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                val state = node.stateDescription?.toString().orEmpty()
                if (text.isNotBlank() || description.isNotBlank() || state.isNotBlank()) values.put(JSONObject()
                    .put("id", node.viewIdResourceName.orEmpty().substringAfter(":id/"))
                    .put("text", text.take(2_000)).put("description", description.take(2_000)).put("state", state.take(2_000)))
            }
            for (index in 0 until node.childCount) {
                if (visited >= 2_500 || values.length() >= 300) break
                node.getChild(index)?.let { child -> try { visit(child) } finally { child.recycle() } }
            }
        }
        try { check(root.packageName?.toString() == TARGET); visit(root) } finally { root.recycle() }
        return values
    }

    /** Browsing before cold Play never selects a song or primes a player. */
    private fun browseStages() {
        stage("home_real_dpad_round_trip") {
            ui.launch(); expectedPid = pid().also { check(it.matches(Regex("\\d+"))) }
            captureExpectedProcess()
            val source = ui.firstShelf("home")
            check(source in ui.snapshot().focused)
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 150)
            ui.await("Home Right did not move to the adjacent real card", 4_000) {
                ui.snapshot().focused.any { it.startsWith("shelf-0-item-1-") }
            }
            ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 150)
            ui.await("Home Left lost the original card", 4_000) { source in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 300)
            ui.await("Home Down did not enter another actual shelf", 4_000) { ui.snapshot().focused.any { it.startsWith("shelf-1-item-") } }
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 300)
            ui.await("Home Up lost its source", 4_000) { source in ui.snapshot().focused }
            JSONObject().put("source", source).put("acceptance_input", "real_right_left_down_up")
        }
        stage("new_actual_content_and_return") {
            val source = ui.firstShelf("browse")
            val initial = ui.shelfInfo()
            if (initial.cardCount > 1) {
                ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 160)
                check(source !in ui.snapshot().focused)
                ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 160)
                ui.await("New Left did not return naturally", 4_000) { source in ui.snapshot().focused }
            }
            JSONObject().put("source", source).put("row_count", initial.rowCount).put("first_row_items", initial.cardCount)
        }
        stage("search_history_ime_and_remote_suggestions") {
            val query = "Ariana Grande"
            ui.search("songs", query) // Real editor + actual IME Back dismissal.
            ui.awaitId("search-suggestion-0", 8_000)
            val hint = ui.label("search-suggestion-0"); check(hint.isNotBlank())
            ui.focus("search-field") // Setup only. Acceptance movement below is DPAD.
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            ui.await("Field Right did not reach scope", 3_000) { "search-scope-catalog" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Scope Down did not reach suggestions", 3_000) { "search-suggestion-0" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Suggestions Down did not reach Songs", 3_000) { "search-category-songs" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Songs Down did not reach actual result", 5_000) { "search-result-0" in ui.snapshot().focused }
            check(ui.label("search-suggestion-0") == hint)
            val resultLabel = ui.label("search-result-0")
            ui.clearSearchQueryForSetup(); ui.awaitId("search-history-0")
            val exact = (0 until 3).map { "search-history-$it" }.firstOrNull {
                ui.has(it) && ui.label(it).equals(query, ignoreCase = true)
            } ?: error("Exact submitted term is absent from Search history")
            ui.focus(exact); ui.activate(exact, "search_history_query")
            ui.await("History did not restore cached result and focus", 8_000) {
                "search-result-0" in ui.snapshot().focused && ui.label("search-result-0") == resultLabel
            }
            JSONObject().put("query", query).put("persistent_hint", hint).put("restored_result", resultLabel)
                .put("history_cleared", false).put("acceptance_input", "field_right_scope_down_hint_down_category_down_result")
        }
        stage("library_actual_categories") {
            val cases = JSONArray()
            fun sample(kind: String, phase: String): SurveySnapshot {
                val current = ui.snapshot()
                File(out, "library-$kind-$phase.json").writeText(current.json(detailed = true).toString(2))
                return current
            }
            for (kind in listOf("albums", "playlists", "songs")) {
                ui.navigate("library")
                val id = "library-category-$kind"
                ui.focus(id); ui.activate(id, "library_category")
                sample(kind, "after-click")
                val expectedItem = "library-item-0-${kind.removeSuffix("s")}"
                ui.await("Library has neither real content nor a true empty state", 30_000) {
                    ui.has(expectedItem) || ui.has("library-empty")
                }
                val beforeRight = sample(kind, "before-right")
                check(id in beforeRight.focused) { "Clicked $id lost natural category focus before Right: ${beforeRight.focused}" }
                if (ui.has("library-empty")) {
                    sample(kind, "after-right-not-sent-empty")
                    cases.put(JSONObject().put("category", kind).put("status", "empty_actual_account_data"))
                }
                else {
                    ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                    try { ui.await("Library Right did not enter its exact $expectedItem", 4_000) { expectedItem in ui.snapshot().focused } }
                    finally { sample(kind, "after-right") }
                    ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 0)
                    ui.await("Library Left did not return to its category", 4_000) { id in ui.snapshot().focused }
                    cases.put(JSONObject().put("category", kind).put("status", "passed_real_right_left"))
                }
            }
            JSONObject().put("categories", cases)
        }
        for (kind in listOf("album", "playlist")) stage("${kind}_detail_menu_back") {
            val source = openActualCollection(kind)
            val title = ui.label("collection-title")
            ui.focus("collection-more"); ui.activate("collection-more", "context_menu")
            ui.assertNaturalFirstMenuFocus()
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100); ui.key(KeyEvent.KEYCODE_DPAD_UP, 100)
            ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("catalog-actions-list")
            ui.await("Collection menu Back lost its source", 4_000) { "collection-more" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("collection-screen")
            ui.await("Collection Back lost the originating card", 5_000) { source in ui.snapshot().focused }
            if (kind == "album") {
                ui.verifiedAlbumLongPress(source).execute(); ui.assertNaturalFirstMenuFocus()
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 75); ui.key(KeyEvent.KEYCODE_DPAD_UP, 75)
                ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("catalog-actions-list")
                ui.await("Long press Back lost exact card focus", 4_000) { source in ui.snapshot().focused }
            }
            JSONObject().put("source", source).put("title", title).put("provider_action_activated", false)
        }
        stage("artist_actual_content_back") {
            ui.openNewJeans()
            val title = ui.label("artist-title"); check(title.equals("NewJeans", true))
            val before = ui.snapshot().focused
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 240)
            ui.await("Artist Down did not reach another real section", 8_000) {
                ui.snapshot().focused.any { it.startsWith("artist-") } && ui.snapshot().focused != before
            }
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 240)
            ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("artist-screen")
            check(ui.has("catalog-search"))
            JSONObject().put("artist", title).put("actual_dpad_section_navigation", true)
        }
    }

    private fun openActualCollection(kind: String): String {
        require(kind in setOf("album", "playlist"))
        val source = ui.typedCard(kind)
        ui.focus(source); ui.activate(source, "collection")
        ui.awaitId("collection-screen", 12_000); ui.awaitId("collection-song-0", 45_000)
        check(ui.label("collection-title").isNotBlank())
        return source
    }

    private fun playbackAndEntryStages(controlsOnly: Boolean = false, entriesOnly: Boolean = false, radioOnly: Boolean = false) {
        if (!entriesOnly) {
        stage("paused_clock_resume_and_real_seek") {
            val paused = mediaState(checkNotNull(ownSession()))
            SystemClock.sleep(700)
            check(ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED)
            check(mediaState(checkNotNull(ownSession())).getLong("position_ms") == paused.getLong("position_ms"))
            transport("player_play")
            awaitHealthy("Resume did not enter Playing", 10_000) { ownSession()?.playbackState?.state == PlaybackState.STATE_PLAYING }
            val first = mediaState(checkNotNull(ownSession()))
            SystemClock.sleep(1_700)
            check(mediaState(checkNotNull(ownSession())).getLong("projected_position_ms") - first.getLong("projected_position_ms") >= 1_300)
            transport("player_pause")
            awaitHealthy("Pause before seek did not respond", 5_000) { ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED }
            val duration = checkNotNull(ownSession()?.metadata).getLong(MediaMetadata.METADATA_KEY_DURATION)
            check(duration in 90_000..900_000)
            val seek = scrubTo(minOf(60_000, duration - 45_000))
            check(identity(checkNotNull(ownSession())) == originalIdentity)
            transport("player_play")
            awaitHealthy("Post-seek resume did not play", 10_000) { ownSession()?.playbackState?.state == PlaybackState.STATE_PLAYING }
            JSONObject().put("paused_clock_stable", true).put("resume_advance", true).put("actual_remote_seek", seek)
        }
        stage("real_lyrics_and_queue_idle_direction") {
            if (!ui.has("lyrics-panel")) pressControl("player-lyrics")
            ui.await("No actual lyric rows loaded", 15_000) { ui.has("lyrics-panel") && ui.hasPattern(Regex("lyrics-row-\\d+")) }
            check(identity(checkNotNull(ownSession())) == originalIdentity)
            pressControl("player-queue"); ui.awaitId("queue-panel")
            // Production Queue activation already enters QueueItems. Up here
            // enters QueueModes, whose selected controls are not track cards.
            ui.await("Queue has no actual selected entry", 4_000) { ui.snapshot().selected.any { it.startsWith("player-queue-item-") } }
            val source = ui.snapshot().selected.single { it.startsWith("player-queue-item-") }
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 120)
            ui.await("Queue Right did not move to an existing next entry", 4_000) {
                ui.snapshot().selected.any { it.startsWith("player-queue-item-") && it != source }
            }
            val moved = ui.snapshot().selected.first { it.startsWith("player-queue-item-") }
            ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 120)
            ui.await("Queue Left lost the original entry", 4_000) { source in ui.snapshot().selected }
            ui.awaitGone("player-controls")
            check(ui.has("queue-panel"))
            check(ui.snapshot().selected.none { it.startsWith("player-queue-item-") }) { "Idle Queue retained a browsing focus cursor" }
            check(identity(checkNotNull(ownSession())) == originalIdentity && ownSession()?.playbackState?.state == PlaybackState.STATE_PLAYING)
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0); ui.awaitId("player-controls")
            ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("queue-panel")
            JSONObject().put("queue_source", source).put("right_selection", moved).put("idle_cursor_hidden", true)
        }
        stage("background_foreground_without_restart") {
            val before = mediaState(checkNotNull(ownSession()))
            check(ui.device.pressHome()); SystemClock.sleep(1_500); alive()
            val background = mediaState(checkNotNull(ownSession()))
            check(background.getInt("state") == PlaybackState.STATE_PLAYING)
            ui.launch(); ui.awaitId("player-screen", 8_000); alive()
            check(identity(checkNotNull(ownSession())) == originalIdentity)
            check(mediaState(checkNotNull(ownSession())).getLong("projected_position_ms") > before.getLong("projected_position_ms"))
            transport("player_pause")
            awaitHealthy("Pause after foreground failed", 5_000) { ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED }
            JSONObject().put("before", before).put("background", background).put("same_pid", true).put("manual_play_on_return", false)
        }
        }
        if (controlsOnly) return
        if (!radioOnly) {
        stage("home_song_card_actual_play_back") { playActualHomeSongAndBack() }
        for (kind in listOf("album", "playlist")) stage("${kind}_production_play${if (kind == "album") "_next_previous" else ""}") {
            val albumSelection = if (kind == "album") {
                // A valid one-track Single belongs in the detail case above,
                // but cannot prove manual successor/predecessor transport.
                val args = InstrumentationRegistry.getArguments()
                val preferredCard = args.getString("preferred_album_card")
                val preferredTitle = args.getString("preferred_album_title")
                check((preferredCard == null) == (preferredTitle == null)) { "Preferred album card and title must be provided together" }
                if (preferredCard != null && preferredTitle != null) {
                    try { ui.openPreferredAlbum(preferredCard, preferredTitle) }
                    finally {
                        report.put("preferred_album_fixture", ui.collectionSelectionProof)
                        File(out, "preferred-album-fixture.json").writeText(ui.collectionSelectionProof.toString(2))
                    }
                } else ui.openCollection("album")
                JSONObject(ui.collectionSelectionProof.toString()).also {
                    check(it.getInt("minimum_proven_tracks") >= 2)
                    check(it.getString("card_id").matches(Regex("(?:shelf-\\d+-item-\\d+|library-item-\\d+)-album")))
                }
            } else { openActualCollection(kind); null }
            val title = ui.label("collection-title")
            val firstSong = ui.label("collection-song-0")
            val secondSong = ui.label("collection-song-1")
            ui.focus("collection-play")
            clickExact("collection-play") { check(ui.has("collection-screen") && firstSong.isNotBlank()) }
            playDispatched = true
            val proof = awaitSelectedTrack(firstSong).put("collection", title).put("kind", kind)
            albumSelection?.let { proof.put("source", it.getString("card_id")).put("selection_proof", it) }
            if (kind == "album") {
                check(secondSong.isNotBlank()) { "Manual transport needs a real second album song" }
                val firstIdentity = identity(checkNotNull(ownSession()))
                pressControl("player-next")
                awaitHealthy("Next did not reach the actual second album track", 20_000) {
                    ownSession()?.let { media -> media.playbackState?.state == PlaybackState.STATE_PLAYING && identity(media) != firstIdentity &&
                        media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { secondSong.contains(it, true) } == true } == true
                }
                val next = mediaState(checkNotNull(ownSession()))
                // Source contract: Previous beyond3s restarts the current item;
                // another press at the start selects the prior Queue occurrence.
                if (next.getLong("projected_position_ms") > 3_000) {
                    pressControl("player-previous")
                    awaitHealthy("Previous restart did not reach the start", 10_000) {
                        ownSession()?.let { identity(it) == next.getString("identity") && mediaState(it).getLong("projected_position_ms") < 2_000 } == true
                    }
                }
                pressControl("player-previous")
                awaitHealthy("Previous did not restore the real prior album item", 20_000) {
                    ownSession()?.let { identity(it) == firstIdentity && it.playbackState?.state == PlaybackState.STATE_PLAYING } == true
                }
                proof.put("next", next).put("previous", mediaState(checkNotNull(ownSession())))
            }
            transport("player_pause")
            awaitHealthy("Collection Pause did not respond", 5_000) { ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED }
            ui.leavePlayerWithBack()
            ui.await("Collection playback Back lost its original Play control", 5_000) { "collection-play" in ui.snapshot().focused }
            proof.put("back_to_play_control", true)
        }
        }
        stage("radio_real_progress_and_metadata") {
            val sourceSetup = JSONObject().put("initial_viewport", ui.snapshot().json(detailed = true))
            fun persistSource() {
                report.put("radio_source_setup", sourceSetup)
                stages.getJSONObject(stages.length() - 1).put("source_setup", sourceSetup)
                File(out, "radio-source-setup.json").writeText(sourceSetup.toString(2))
            }
            persistSource()
            val topAnchor = ui.firstShelf("radio")
            sourceSetup.put("top_anchor", topAnchor).put("top_shelf", ui.shelfInfo().json())
                .put("top_viewport", ui.snapshot().json(detailed = true)); persistSource()
            var card: String? = null
            for (step in 0 until 14) {
                val row = ui.shelfInfo()
                card = ui.snapshot().ids.firstOrNull {
                    it.matches(Regex("shelf-${row.row}-item-\\d+-station")) && ui.label(it).contains("Apple Music 1")
                }
                if (card != null || row.row >= row.rowCount - 1) break
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 250)
                ui.await("Radio Down did not reach the next real station row", 5_000) {
                    ui.snapshot().focused.any { it.startsWith("shelf-${row.row + 1}-item-") }
                }
            }
            val id = checkNotNull(card) { "No actual labeled Apple Music 1; another station must not substitute" }
            val label = ui.label(id)
            sourceSetup.put("station_card_id", id).put("station_label", label)
                .put("before_station_focus", ui.snapshot().json(detailed = true)); persistSource()
            ui.focus(id)
            sourceSetup.put("after_station_focus", ui.snapshot().json(detailed = true)); persistSource()
            clickExact(id) {
                check(id.matches(Regex("shelf-\\d+-item-\\d+-station")) && label.contains("Apple Music 1"))
                playDispatched = true
            }
            ui.awaitId("player-screen", 10_000)
            awaitHealthy("Radio did not actually play its own station session", 35_000) {
                ownSession()?.let { it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    it.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.startsWith("ra.") == true } == true
            }
            val startMedia = checkNotNull(ownSession())
            val stationId = checkNotNull(startMedia.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            check(stationId.startsWith("ra."))
            val start = radioMediaProof(startMedia)
            val samples = JSONArray().put(start)
            val proof = JSONObject().put("source", id).put("station", label).put("source_setup", sourceSetup).put("before", start)
                .put("station_media_id_before", stationId).put("samples", samples)
                .put("clock_assertion", "not_applicable_to_unseekable_live_stream")
                .put("acceptance", "two_current_owned_audio_renderers_started_observations_plus_same_station_playing_and_visible_metadata_cover")
            // Persist observations before any assertion so failure cannot erase the actual live contract.
            fun persistRadio() {
                stages.getJSONObject(stages.length() - 1).put("proof", proof)
                report.put("radio_observation", proof)
                File(out, "radio-observation.json").writeText(proof.toString(2))
            }
            persistRadio()
            var firstRenderer = JSONObject()
            awaitHealthy("Live station has no current started audio renderer for the exact target UID/PID", 10_000) {
                firstRenderer = currentOwnedAudioRenderers()
                proof.put("renderer_before", firstRenderer); persistRadio()
                firstRenderer.getJSONArray("started_players").length() > 0
            }
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(500); alive()
                samples.put(radioMediaProof(checkNotNull(ownSession())))
                persistRadio()
            }
            val endMedia = checkNotNull(ownSession()); val end = radioMediaProof(endMedia)
            val metadata = checkNotNull(endMedia.metadata)
            val endStationId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            val artwork = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty()
            val clockDelta = end.getLong("projected_position_ms") - start.getLong("projected_position_ms")
            val lastRenderer = currentOwnedAudioRenderers()
            proof.put("renderer_after", lastRenderer)
            proof.put("after", end).put("station_media_id_after", endStationId).put("projected_clock_delta_ms", clockDelta)
                .put("public_metadata_title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).put("artwork_uri", artwork)
                .put("song_transition_observed", start.getString("identity") != end.getString("identity"))
            persistRadio()
            check(end.getInt("state") == PlaybackState.STATE_PLAYING) { "Live station no longer Playing; see radio-observation.json" }
            check(lastRenderer.getJSONArray("started_players").length() > 0) { "Live renderer not currently started at second observation; see radio-observation.json" }
            check(lastRenderer.getLong("observed_elapsed_ms") - firstRenderer.getLong("observed_elapsed_ms") >= 2_000) { "Live renderer observations were not separated by two seconds" }
            check(endStationId?.startsWith("ra.") == true && endStationId == stationId) { "Radio changed or reused a non-station media identity" }
            check(metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().isNotBlank()) { "Live station title missing; see radio-observation.json" }
            check(artwork.startsWith("https://")) { "Live station artwork URI missing; see radio-observation.json" }
            val readyLabels = ui.stringLabels("player_artwork_loaded")
            var visible = JSONArray()
            awaitHealthy("Actual live metadata title/artist or loaded cover missing from the player UI", 8_000) {
                visible = currentPublicTexts()
                val texts = (0 until visible.length()).map { visible.getJSONObject(it).optString("text") }.toSet()
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                proof.put("public_texts", visible)
                val loaded = node("player-artwork") { it.stateDescription?.toString().orEmpty() in readyLabels }
                proof.put("artwork_ready", loaded); persistRadio()
                title in texts && (artist.isBlank() || artist in texts) && loaded
            }
            // Protected physical-TV screenshots can be stale: endpoint artifact only.
            // Fresh UI text/artwork semantics, same station identity and current audio are acceptance.
            proof.put("screenshot_role", "endpoint_only_not_visual_metadata_acceptance")
            check(ui.device.takeScreenshot(File(out, "radio-current-metadata.png")))
            transport("player_pause")
            awaitHealthy("Radio Pause did not stop owned playback", 8_000) { ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED }
            proof.put("public_pause_responded", true)
        }
        if (continueSafe && (0 until stages.length()).map(stages::getJSONObject)
                .firstOrNull { it.optString("name") == "radio_real_progress_and_metadata" }?.optString("status") != "passed") {
            skippedDependentStage("radio_to_song_handoff", "radio_real_progress_and_metadata")
        } else stage("radio_to_song_handoff") {
            val station = checkNotNull(ownSession())
            check(station.playbackState?.state == PlaybackState.STATE_PAUSED) { "Radio must be paused before the native handoff" }
            val stationId = checkNotNull(station.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            check(stationId.startsWith("ra.")) { "Handoff must begin from the actual station, not an old song" }
            playActualHomeSongAndBack(previousStationId = stationId)
                .put("station_media_id_before", stationId).put("web_radio_to_native_song", true)
        }
        stage("settings_reads_and_reversible_motion_toggle") { settingsReadAndToggle() }
    }

    private fun naturalHandoffStages() {
        val proof = JSONObject().put("manual_next_calls", 0).put("sdk_seed_or_prepare", false)
        report.put("natural_handoff", proof)
        fun persist() { File(out, "natural-handoff.json").writeText(proof.toString(2)) }
        fun fixture(condition: Boolean, message: String) {
            if (!condition) {
                report.put("failure_classification", "fixture_precondition_unmet")
                proof.put("fixture_precondition_error", message); persist()
                error("Fixture precondition unmet (not an app crash): $message")
            }
        }
        val args = InstrumentationRegistry.getArguments()
        val preferredCard = args.getString("preferred_album_card")
        val preferredTitle = args.getString("preferred_album_title")
        check((preferredCard == null) == (preferredTitle == null)) { "Natural preferred album card and title must be provided together" }
        var expectedSuccessorLabel: String? = null
        if (preferredCard != null && preferredTitle != null) stage("natural_public_preferred_album_fixture") {
            fixture(ownSession()?.playbackState?.state != PlaybackState.STATE_PLAYING, "Explicit public album fixture must begin without active playback")
            try { ui.openPreferredAlbum(preferredCard, preferredTitle) }
            finally {
                proof.put("preferred_album_fixture", ui.collectionSelectionProof); persist()
            }
            val firstSong = ui.label("collection-song-0")
            val secondSong = ui.label("collection-song-1")
            check(firstSong.isNotBlank() && secondSong.isNotBlank()) { "Explicit album fixture lost its actual first or second song label" }
            expectedSuccessorLabel = secondSong
            proof.put("public_fixture_play", true).put("direct_sdk_seed", false)
                .put("expected_first_song_label", firstSong).put("expected_successor_label", secondSong)
            persist()
            ui.focus("collection-play")
            clickExact("collection-play") {
                check(ui.label("collection-title") == preferredTitle) { "Explicit album fixture title changed before public Play" }
                playDispatched = true
            }
            val playing = awaitSelectedTrack(firstSong)
            proof.put("public_fixture_play_proof", playing); persist()
            transport("player_pause")
            awaitHealthy("Public album fixture Pause did not respond", 5_000) {
                ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED && ui.playerPaused()
            }
            proof.put("public_fixture_paused", true); persist()
            JSONObject().put("selection", ui.collectionSelectionProof).put("public_fixture_play", true)
                .put("direct_sdk_seed", false).put("actual_play", playing).put("expected_successor_label", secondSong)
                .put("public_pause_responded", true)
        }
        var oldMediaId = ""
        var seekTarget = 0L
        stage("natural_fixture_public_repeat_off") {
            if (!ui.has("player-screen")) {
                ui.openNavigation(); ui.awaitId("nav-nowplaying", 8_000)
                ui.activate("nav-nowplaying", "navigation"); ui.awaitId("player-screen", 8_000)
            }
            val media = ownSession()
            fixture(media != null, "No existing owned MediaSession; seeding is prohibited")
            val actual = checkNotNull(media)
            val state = actual.playbackState
            val metadata = actual.metadata
            fixture(state?.state == PlaybackState.STATE_PAUSED, "Retained real song must be paused")
            oldMediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val actions = state?.actions ?: 0L
            proof.put("initial", mediaState(actual)).put("initial_media_id", oldMediaId).put("duration_ms", duration).put("actions", actions)
            persist()
            fixture(oldMediaId.isNotBlank() && !oldMediaId.startsWith("ra."), "Natural handoff requires an existing native song")
            fixture(duration >= 30_000, "Existing song is shorter than the safe 30-second fixture")
            fixture(actions and PlaybackState.ACTION_SEEK_TO != 0L, "Owned session does not advertise real Seek")
            fixture(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L, "Owned session has no available Next action")
            originalIdentity = identity(actual)
            seekTarget = duration - 7_000
            // Merely expose public Queue labels; never activate a mode/track.
            pressControl("player-queue"); ui.awaitId("queue-mode-controls", 4_000)
            val labels = ui.stringLabels("player_repeat_off")
            val texts = currentPublicTexts()
            val repeatOff = (0 until texts.length()).any {
                val value = texts.getJSONObject(it)
                value.optString("text") in labels || value.optString("description") in labels
            }
            proof.put("repeat_off_public_labels", JSONArray(labels.toList())).put("repeat_ui", texts).put("repeat_off_observed", repeatOff)
            persist()
            fixture(repeatOff, "Repeat Off could not be observed in public Queue mode labels; mode was not changed")
            ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("queue-panel")
            fixture(ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED && identity(checkNotNull(ownSession())) == originalIdentity,
                "Read-only Queue inspection changed the paused song")
            JSONObject().put("initial", proof.getJSONObject("initial")).put("repeat_off_observed", true).put("manual_mode_changes", 0)
        }
        stage("natural_public_paused_seek") {
            proof.put("seek_target_ms", seekTarget).put("seek_transport", "owned_MediaController_public_seekTo")
            persist()
            checkNotNull(ownSession()).transportControls.seekTo(seekTarget)
            awaitHealthy("Public seek did not reach the actual paused end-of-track position", 8_000) {
                ownSession()?.let { media -> media.playbackState?.state == PlaybackState.STATE_PAUSED && identity(media) == originalIdentity &&
                    kotlin.math.abs(checkNotNull(media.playbackState).position - seekTarget) <= 1_500 } == true
            }
            val actual = mediaState(checkNotNull(ownSession()))
            proof.put("paused_seek_readback", actual); persist()
            actual
        }
        var newMediaId = ""
        var newTitle = ""
        var firstNew = JSONObject()
        stage("natural_transition_without_manual_next") {
            val samples = JSONArray()
            proof.put("transition_samples", samples); persist()
            transport("player_play") { playDispatched = true }
            val until = SystemClock.elapsedRealtime() + 25_000
            while (SystemClock.elapsedRealtime() < until) {
                alive()
                val media = checkNotNull(ownSession())
                val sample = mediaState(media)
                samples.put(sample); persist()
                val id = media.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
                if (media.playbackState?.state == PlaybackState.STATE_PLAYING && id.isNotBlank() && id != oldMediaId && !id.startsWith("ra.")) {
                    newMediaId = id
                    newTitle = media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                    firstNew = sample
                    break
                }
                SystemClock.sleep(400)
            }
            check(newMediaId.isNotBlank() && newTitle.isNotBlank()) {
                "No natural Playing successor after the real end position; no manual Next was sent; see natural-handoff.json"
            }
            proof.put("natural_successor_media_id", newMediaId).put("natural_successor_title", newTitle).put("first_new_song", firstNew)
            persist()
            expectedSuccessorLabel?.let { expected ->
                check(expected.contains(newTitle, ignoreCase = true)) { "Natural successor did not match the actual second album song: expected=$expected actual=$newTitle" }
                proof.put("actual_second_album_song_matched", true); persist()
            }
            JSONObject().put("source_media_id", oldMediaId).put("successor_media_id", newMediaId)
                .put("manual_next_calls", 0).put("first_new_song", firstNew).put("same_pid", true)
        }
        stage("natural_new_song_progress_and_ui_pause") {
            val samples = JSONArray().put(firstNew)
            proof.put("new_song_samples", samples); persist()
            awaitHealthy("Natural successor has no fresh advancing production position anchor", 14_000) {
                val media = checkNotNull(ownSession())
                val sample = mediaState(media)
                samples.put(sample); persist()
                check(media.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == newMediaId) { "Natural successor changed again before continuity proof" }
                check(media.playbackState?.state == PlaybackState.STATE_PLAYING) { "Natural successor stopped before continuity proof: $sample" }
                sample.getLong("position_updated_elapsed_ms") > firstNew.getLong("position_updated_elapsed_ms") &&
                    sample.getLong("position_ms") - firstNew.getLong("position_ms") >= 1_000
            }
            val loaded = ui.stringLabels("player_artwork_loaded")
            awaitHealthy("Natural successor title/loaded artwork missing from the real player", 5_000) {
                ui.label("player-artwork").contains(newTitle, ignoreCase = true) &&
                    node("player-artwork") { it.stateDescription?.toString().orEmpty() in loaded }
            }
            proof.put("public_artwork_label", ui.label("player-artwork")).put("artwork_loaded", true).put("fresh_native_position", true)
            persist()
            transport("player_pause")
            awaitHealthy("Public Pause did not respond after the natural successor", 5_000) {
                ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED && ui.playerPaused()
            }
            proof.put("responsive_public_pause", true).put("same_pid", true); persist()
            JSONObject().put("fresh_production_position", true).put("public_artwork_label", proof.getString("public_artwork_label"))
                .put("public_pause_responded", true).put("same_pid", true).put("manual_next_calls", 0)
        }
    }

    private fun settingsReadAndToggle(): JSONObject {
        val resources = JSONObject()
        report.put("settings_resources", resources)
        resources.put("before_settings", captureResourceUsage("settings-before-navigation"))
        ui.navigate("account")
        resources.put("after_account_navigation", captureResourceUsage("settings-after-account-navigation"))
        ui.focusSettingsControl()
        repeat(40) { if (!ui.has("settings-artwork-motion")) ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100) }
        ui.awaitId("settings-artwork-motion", 3_000); ui.focus("settings-artwork-motion")
        resources.put("after_scrolling", captureResourceUsage("settings-after-scrolling"))
        val on = ui.stringLabels("artwork_motion_on"); val off = ui.stringLabels("artwork_motion_off")
        val initial = ui.label("settings-artwork-motion"); check((on + off).any(initial::contains))
        val wasOn = on.any(initial::contains)
        try {
            pressFocusedMotionToggle()
            ui.await("Motion setting did not change", 3_000) { (if (wasOn) off else on).any(ui.label("settings-artwork-motion")::contains) }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100); ui.key(KeyEvent.KEYCODE_DPAD_UP, 100)
            ui.await("Settings Down/Up lost its real toggle", 3_000) { "settings-artwork-motion" in ui.snapshot().focused }
        } finally {
            val current = ui.label("settings-artwork-motion")
            if (on.any(current::contains) != wasOn) {
                ui.focus("settings-artwork-motion")
                pressFocusedMotionToggle()
            }
            ui.await("Motion setting did not restore", 3_000) { (if (wasOn) on else off).any(ui.label("settings-artwork-motion")::contains) }
        }
        return JSONObject().put("resources", resources).put("initial", initial).put("restored", ui.label("settings-artwork-motion")).put("provider_writes", false)
    }

    private fun currentOwnedAudioRenderers(): JSONObject {
        alive()
        val uid = inst.context.packageManager.getApplicationInfo(TARGET, 0).uid
        val current = ui.device.executeShellCommand("dumpsys audio")
            .substringBefore("Audio event log: playback activity")
        // Only CURRENT configurations, never historical PlayerBase started events.
        val ownership = Regex("\\bu/pid:$uid/$expectedPid(?=\\s|$)")
        val players = current.lineSequence().map(String::trim).filter {
            it.startsWith("AudioPlaybackConfiguration ") && ownership.containsMatchIn(it)
        }.map { it.take(2_000) }.toList()
        return JSONObject().put("observed_elapsed_ms", SystemClock.elapsedRealtime()).put("uid", uid).put("pid", expectedPid)
            .put("source", "dumpsys_audio_current_configurations_before_event_history")
            .put("owned_current_players", JSONArray(players))
            .put("started_players", JSONArray(players.filter { Regex("\\bstate:started(?=\\s|$)").containsMatchIn(it) }))
    }

    private fun radioMediaProof(media: MediaController): JSONObject {
        val metadata = checkNotNull(media.metadata)
        val state = checkNotNull(media.playbackState)
        return mediaState(media).put("media_id", metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            .put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
            .put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .put("duration_ms", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
            .put("artwork_uri", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
            .put("playback_speed", state.playbackSpeed.toDouble()).put("actions", state.actions)
            .put("seek_action_available", state.actions and PlaybackState.ACTION_SEEK_TO != 0L)
            .put("playback_error", state.errorMessage?.toString().orEmpty())
    }

    private fun playActualHomeSongAndBack(previousStationId: String? = null): JSONObject {
        ui.firstShelf("home")
        var source: String? = null
        for (step in 0 until 20) {
            val row = ui.shelfInfo()
            source = ui.snapshot().ids.firstOrNull { it.matches(Regex("shelf-${row.row}-item-\\d+-song")) }
            if (source != null || row.row >= row.rowCount - 1) break
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 300)
            ui.await("Home Down did not reach the next real row", 5_000) { ui.snapshot().focused.any { it.startsWith("shelf-${row.row + 1}-item-") } }
        }
        val id = checkNotNull(source) { "No real Home song card in the bounded feed" }
        ui.focus(id)
        val label = ui.label(id)
        clickExact(id) {
            check(id.matches(Regex("shelf-\\d+-item-\\d+-song")) && id in ui.snapshot().focused)
            playDispatched = true
        }
        if (previousStationId != null) {
            awaitHealthy("Radio did not hand off to the selected native song", 25_000) {
                ownSession()?.let { media ->
                    val mediaId = media.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
                    media.playbackState?.state == PlaybackState.STATE_PLAYING && mediaId.isNotBlank() &&
                        !mediaId.startsWith("ra.") && mediaId != previousStationId &&
                        media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { it.isNotBlank() && label.contains(it, true) } == true
                } == true
            }
        }
        val proof = awaitSelectedTrack(label)
        val songId = ownSession()?.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        if (previousStationId != null) check(songId.isNotBlank() && !songId.startsWith("ra.") && songId != previousStationId)
        transport("player_pause")
        awaitHealthy("Home song Pause did not respond", 5_000) { ownSession()?.playbackState?.state == PlaybackState.STATE_PAUSED }
        ui.leavePlayerWithBack()
        ui.await("Player Back did not naturally restore Home song", 5_000) { id in ui.snapshot().focused }
        val anchor = ui.shelfInfo()
        check(id.matches(Regex("shelf-${anchor.row}-item-${anchor.column}-[a-z]+"))) { "Back restored conflicting shelf ownership: source=$id anchor=$anchor" }
        val navigation = JSONObject().put("before", anchor.json()).put("source", id)
            .put("action_focus_during_acceptance", false).put("right_at_edge", anchor.column + 1 >= anchor.cardCount)
        proof.put("source", id).put("source_label", label).put("song_media_id_after", songId)
            .put("back_focus_restored", true).put("post_back_real_dpad", navigation)
        val stageName = stages.getJSONObject(stages.length() - 1).getString("name")
        stages.getJSONObject(stages.length() - 1).put("proof", proof)
        fun persistReturn() { File(out, "$stageName-return-dpad.json").writeText(proof.toString(2)) }
        persistReturn()
        ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 120)
        if (anchor.column + 1 < anchor.cardCount) {
            val adjacent = Regex("shelf-${anchor.row}-item-${anchor.column + 1}-[a-z]+")
            ui.await("Restored Home song did not respond to real Right into its exact adjacent card", 4_000) {
                ui.snapshot().focused.any(adjacent::matches)
            }
            navigation.put("after_right", ui.snapshot().json()); persistReturn()
            ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 120)
            ui.await("Post-Back real Left did not restore the original Home song", 4_000) { id in ui.snapshot().focused }
            navigation.put("right_left_round_trip", true)
        } else {
            ui.await("Post-Back real Right at the shelf edge lost the original Home song", 4_000) { id in ui.snapshot().focused }
            navigation.put("right_edge_source_retained", true)
        }
        navigation.put("after", ui.snapshot().json()).put("passed", true); persistReturn()
        return proof
    }

    private fun pressFocusedMotionToggle() {
        alive(); check(ui.has("browse-account") && "settings-artwork-motion" in ui.snapshot().focused)
        node("settings-artwork-motion") { check(it.isEnabled && it.isClickable) }
        check(ui.device.pressDPadCenter())
    }

    private fun awaitSelectedTrack(sourceLabel: String): JSONObject {
        ui.awaitId("player-screen", 12_000)
        awaitHealthy("Selected public song did not actually play", 25_000) {
            ownSession()?.let { media -> media.playbackState?.state == PlaybackState.STATE_PLAYING &&
                media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { it.isNotBlank() && sourceLabel.contains(it, true) } == true } == true
        }
        val start = mediaState(checkNotNull(ownSession()))
        SystemClock.sleep(1_300); alive()
        val end = mediaState(checkNotNull(ownSession()))
        check(end.getString("identity") == start.getString("identity")) { "Selected native song changed during continuity observation: before=$start after=$end" }
        check(end.getLong("projected_position_ms") - start.getLong("projected_position_ms") >= 900) { "Selected native song clock did not advance: before=$start after=$end" }
        return JSONObject().put("actual_start", start).put("actual_advance", end)
    }

    private fun pressControl(id: String) {
        require(id in setOf("player-previous", "player-next", "player-lyrics", "player-queue"))
        wake(); node(id) { check(it.isEnabled) { "Production control is disabled: $id" } }
        fun selected() = node(id) { it.isSelected || it.isChecked }
        repeat(8) { if (!selected()) ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 30) }
        repeat(8) { if (!selected()) ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 30) }
        check(selected()) { "Remote could not select actual control $id" }
        check(ui.device.pressDPadCenter())
    }

    private fun scrubTo(targetMs: Long): JSONObject {
        wake(); PlayerUi(TvUi(ui.device)).focusControl("player-play-pause")
        ui.key(KeyEvent.KEYCODE_DPAD_UP, 70)
        ui.await("Remote Up did not enter real progress", 3_000) { node("player-progress") { it.isSelected || it.isChecked } }
        for (step in 0 until 100) {
            val preview = clockMs(ui.label("player-progress-elapsed"))
            if (kotlin.math.abs(preview - targetMs) <= 2_500) break
            ui.key(if (preview > targetMs) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, 25)
        }
        val preview = clockMs(ui.label("player-progress-elapsed"))
        check(kotlin.math.abs(preview - targetMs) <= 5_000) { "Bounded actual scrubber did not reach target" }
        check(ui.device.pressDPadCenter()); ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 70)
        awaitHealthy("Real seek did not update the production media clock", 6_000) {
            ownSession()?.playbackState?.let { kotlin.math.abs(it.position - preview) <= 2_000 } == true
        }
        return JSONObject().put("requested_ms", targetMs).put("actual_ui_preview_ms", preview).put("media", mediaState(checkNotNull(ownSession())))
    }

    private fun clickExact(id: String, proof: () -> Unit) {
        alive(); proof()
        node(id) { target ->
            check(target.isEnabled && target.isClickable && target.isVisibleToUser)
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Actual public action rejected: $id" }
        }
    }

    private fun parsePid(raw: String): String {
        val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val numeric = lines.filter { it.matches(Regex("\\d+")) }
        val unexpected = lines.filterNot { it.matches(Regex("\\d+")) || it.startsWith("WARNING: linker:") }
        return numeric.singleOrNull()?.takeIf { unexpected.isEmpty() }.orEmpty()
    }
    private fun pid() = parsePid(ui.device.executeShellCommand("pidof $TARGET"))

    /** One shell read, bracketed by stat twice, identifies a process incarnation without polling/relaunch. */
    private fun processWitness(candidate: String): JSONObject {
        require(candidate.matches(Regex("\\d+")))
        val raw = ui.device.executeShellCommand("cat /proc/$candidate/stat /proc/$candidate/status /proc/$candidate/cmdline /proc/$candidate/stat")
        val statLines = raw.replace('\u0000', '\n').lineSequence().filter { it.startsWith("$candidate (") }.toList()
        fun startTime(line: String): String = line.substringAfterLast(") ").split(Regex("\\s+"))
            .getOrNull(19)?.takeIf { it.matches(Regex("\\d+")) }.orEmpty()
        val starts = statLines.map(::startTime)
        val uid = Regex("(?m)^Uid:\\s+(\\d+)").find(raw)?.groupValues?.get(1).orEmpty()
        val cmdline = if (statLines.size == 2) raw.substringBeforeLast("$candidate (")
            .substringAfterLast('\n').substringBefore('\u0000') else ""
        val expectedUid = inst.context.packageManager.getApplicationInfo(TARGET, 0).uid.toString()
        return JSONObject().put("pid", candidate).put("start_time_ticks", starts.firstOrNull().orEmpty())
            .put("bracketed_stat_matches", starts.size == 2 && starts.all { it.isNotEmpty() && it == starts.first() })
            .put("uid", uid).put("cmdline", cmdline).put("expected_uid", expectedUid)
            .put("identity_valid", starts.size == 2 && starts.all { it.isNotEmpty() && it == starts.first() } && uid == expectedUid && cmdline == TARGET)
            .put("raw_proc_read", raw.take(8_192)).put("observed_elapsed_ms", SystemClock.elapsedRealtime())
    }
    private fun captureExpectedProcess() {
        val witness = processWitness(expectedPid)
        report.put("expected_process_witness", witness)
        check(witness.getBoolean("identity_valid")) { "Could not capture authoritative initial target PID/starttime/cmdline/UID: $witness" }
        expectedProcessWitness = witness
    }
    private fun alive() {
        check(SystemClock.elapsedRealtime() < deadlineElapsedMs) { "Bounded production journey deadline expired" }
        check(expectedPid.isNotBlank()) { "No target process incarnation was established" }
        val raw = ui.device.executeShellCommand("pidof $TARGET")
        val observed = parsePid(raw)
        if (observed == expectedPid) return
        // Vendor pidof can transiently print nothing/noise. Confirm the SAME
        // incarnation once; never retry Play, relaunch, or adopt a replacement.
        val event = JSONObject().put("expected_pid", expectedPid).put("raw_pidof", raw.take(4_096)).put("parsed_pid", observed)
        val confirmation = runCatching { processWitness(expectedPid) }
        confirmation.onSuccess { event.put("proc_confirmation", it) }
            .onFailure { event.put("proc_confirmation_error", it.toString()) }
        val baseline = expectedProcessWitness
        val actual = confirmation.getOrNull()
        val same = baseline != null && actual != null && actual.getBoolean("identity_valid") &&
            actual.getString("start_time_ticks") == baseline.getString("start_time_ticks") &&
            actual.getString("cmdline") == baseline.getString("cmdline") && actual.getString("uid") == baseline.getString("uid")
        event.put("same_process_confirmed", same)
        processConfirmations.put(event)
        report.put("process_confirmation_observations", processConfirmations)
        File(out, "process-confirmations.json").writeText(processConfirmations.toString(2))
        check(same) { "Target process missing/replaced, or incarnation could not be confirmed; see process-confirmations.json" }
    }
    private fun ownSession(): MediaController? = mediaManager.getActiveSessions(listener).filter { it.packageName == TARGET }
        .also { check(it.size <= 1) { "Ambiguous target session ownership" } }.singleOrNull()
    private fun awaitHealthy(message: String, timeout: Long, predicate: () -> Boolean) = ui.await(message, timeout) { alive(); predicate() }
    private fun rememberSnapshot() { lastSnapshot = ui.snapshot().json(detailed = true) }
    private fun wake() {
        alive(); ui.requireForeground()
        val selected = ui.snapshot().selected
        check("player-progress" !in selected && selected.none { it.startsWith("player-queue-item-") }) {
            "Refusing to refresh Controls with Right while progress/QueueItems owns input: $selected"
        }
        ui.focus("player-screen")
        // The parent can still exist while its5s idle deadline removes its
        // children. A real direction renews activity even when it looks visible.
        ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.awaitId("player-play-pause", 4_000)
    }
    private fun transport(label: String, beforeClick: () -> Unit = {}) {
        require(label in setOf("player_play", "player_pause"))
        val expectedLabels = ui.stringLabels(label)
        wake()
        node("player-play-pause") { target ->
            val actual = setOf(target.contentDescription?.toString(), target.stateDescription?.toString())
            check(target.isEnabled && target.isClickable && actual.any { it in expectedLabels }) {
                "Actual production transport is not the requested $label action"
            }
            beforeClick()
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Production transport rejected the actual click" }
        }
    }
    private fun identity(media: MediaController): String {
        val data = checkNotNull(media.metadata)
        val id = checkNotNull(data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)).also { check(it.isNotBlank()) }
        return listOf(id, data.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), data.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            data.getLong(MediaMetadata.METADATA_KEY_DURATION).toString()).joinToString("|")
    }
    private fun mediaState(media: MediaController): JSONObject {
        val state = checkNotNull(media.playbackState)
        val projected = state.position + if (state.state == PlaybackState.STATE_PLAYING)
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed).toLong() else 0
        return JSONObject().put("identity", identity(media)).put("state", state.state).put("position_ms", state.position)
            .put("position_updated_elapsed_ms", state.lastPositionUpdateTime).put("projected_position_ms", projected)
            .put("observed_elapsed_ms", SystemClock.elapsedRealtime())
    }
    private fun clockMs(text: String, remaining: Boolean = false): Long {
        val expression = if (remaining) Regex("[−-](\\d+:\\d{2}(?::\\d{2})?)") else Regex("(?<![\\d:])(\\d+:\\d{2}(?::\\d{2})?)(?![\\d:])")
        val time = checkNotNull(expression.find(text)?.groupValues?.get(1)) { "No real public playback clock: $text" }
        return time.split(':').fold(0L) { total, part -> total * 60 + part.toLong() } * 1_000
    }
    private fun <T> node(id: String, action: (AccessibilityNodeInfo) -> T): T {
        ui.requireForeground()
        val root = checkNotNull(inst.uiAutomation.rootInActiveWindow)
        check(root.packageName?.toString() == TARGET)
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.viewIdResourceName.orEmpty().substringAfter(":id/") == id && current.refresh() &&
                current.packageName?.toString() == TARGET && current.isVisibleToUser) return AccessibilityNodeInfo.obtain(current)
            for (index in 0 until current.childCount) current.getChild(index)?.let { child ->
                val result = try { find(child) } finally { child.recycle() }
                if (result != null) return result
            }
            return null
        }
        val target = try { find(root) } finally { root.recycle() }
        checkNotNull(target) { "Missing actual production target $id" }
        return try { action(target) } finally { target.recycle() }
    }
}
