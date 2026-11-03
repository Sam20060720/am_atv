package dev.amatv.benchmark

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.view.KeyEvent
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit playback suite: existing paused track only; every window ends paused. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class PlayingRenderingSurveyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi
    private lateinit var directory: File
    private lateinit var manifest: JSONObject
    private val scenes = JSONArray()
    private var trialCount = 3
    private var identity = ""
    private var ownedPlayback = false
    private var everResumed = false
    private var originalQueue = false
    private var originalLyrics = false
    private var originalControls = false
    private var baselineCaptured = false
    private var initialElapsedMs = 0L
    private var cleanupPositionMs = 0L
    private var explicitCleanupPosition = false
    private var totalDurationMs = 0L
    private var anchorMs = 0L
    private var seekUsed = false
    private var pendingScrub = false
    private var measuring = false
    private var cleanupInProgress = false
    private val progressReadRecoveries = JSONArray()
    private val seekOperations = JSONArray()
    private val progressOwnerProbes = JSONArray()
    private val progressOwnerRejections = JSONArray()
    private val allowed = setOf("player-play-pause", "player-lyrics", "player-queue")

    @Test fun surveyInstalledPlayback() {
        val args = InstrumentationRegistry.getArguments()
        val cleanupOverrideMs = args.getString("cleanup_position_ms")?.let {
            requireNotNull(it.toLongOrNull()) { "cleanup_position_ms must be an integer millisecond position" }
        }
        trialCount = if (args.getString("initial_pilot") == "1") 1 else 3
        val ids = listOf("playing_lyrics", "playing_controls", "playing_queue_cursor")
        val selected = args.getString("scenes")?.split(',')?.filter(String::isNotBlank)?.toSet()
        require(selected == null || selected.all { it in ids })
        val requested = ids.filter { selected == null || it in selected }
        val runId = args.getString("run_id") ?: "playing-" + System.currentTimeMillis()
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        val sha = args.getString("candidate_sha256").orEmpty()
        require(sha.matches(Regex("[a-fA-F0-9]{64}")))
        val base = requireNotNull(instrumentation.context.getExternalFilesDir(null)).canonicalFile
        directory = File(base, args.getString("run_dir") ?: "rendering-survey/" + runId).canonicalFile
        require(directory.path.startsWith(base.path + File.separator) && !File(directory, "manifest.json").exists())
        directory.mkdirs()
        manifest = JSONObject().put("schema_version", 1).put("run_id", runId).put("package", TARGET)
            .put("test_package", instrumentation.context.packageName).put("candidate_sha256", sha)
            .put("suite", "existing_track_playback").put("clock", "monotonic").put("trace_clock", "boottime")
            .put("cancel_request_file", "cancel-request").put("cancelled", false)
            .put("start_wall_ms", System.currentTimeMillis()).put("trial_count", trialCount).put("warmup_count", 1)
            .put("measurement_notes", JSONArray(listOf("Only the existing paused track may resume",
                "Warmup 2s; measured windows 6s; no hierarchy polling inside BEGIN/END",
                "Pause and seek the same existing track to a verified public scrubber anchor between windows",
                "No track selection, queue modes, favorites or account mutations",
                "GPU completion/fence proxies are not pure GPU execution; gfxinfo ring can truncate")))
            .put("scenes", scenes).put("seek_operations", seekOperations).put("progress_owner_probes", progressOwnerProbes)
            .put("progress_owner_rejections", progressOwnerRejections).put("progress_read_recoveries", progressReadRecoveries)
        ui = RenderingSurveyUi(instrumentation)
        var primaryFailure: Throwable? = null
        try {
            checkCancellation()
            ui.launch()
            if (!ui.has("player-screen")) {
                ui.navigate("home"); ui.openNavigation()
                ui.activate("nav-nowplaying", "navigation"); awaitId("player-screen")
            }
            check(state() == "paused") { "Existing track must already be paused; no playback seed is permitted" }
            originalControls = ui.has("player-controls"); originalQueue = ui.has("queue-panel")
            wake(); if (originalQueue) click("player-queue")
            awaitGone("queue-panel"); ui.settle(420)
            originalLyrics = lyricsLayout()
            identity = artworkIdentity()
            check(identity.isNotBlank()) { "Existing track identity is not publicly visible" }
            val progress = progress()
            initialElapsedMs = progress.first; totalDurationMs = progress.first + progress.second
            cleanupPositionMs = initialElapsedMs
            check(totalDurationMs >= 30_000L) { "Existing track is too short for a protected same-track window" }
            val latestAnchor = ((totalDurationMs - 25_000L) / 5_000L) * 5_000L
            anchorMs = if (totalDurationMs >= 85_000L) 60_000L else nearestGrid(initialElapsedMs).coerceAtMost(latestAnchor)
            baselineCaptured = true
            if (cleanupOverrideMs != null) {
                require(cleanupOverrideMs in 0L..totalDurationMs) { "cleanup_position_ms lies outside the existing track duration" }
                cleanupPositionMs = cleanupOverrideMs
                explicitCleanupPosition = true
            }
            manifest.put("original", observation()).put("original_queue", originalQueue)
                .put("original_lyrics", originalLyrics).put("original_controls", originalControls)
                .put("initial_remaining_ms", progress.second).put("track_identity", identity)
                .put("initial_elapsed_ms", initialElapsedMs).put("total_duration_ms", totalDurationMs)
                .put("cleanup_position_requested_ms", cleanupPositionMs).put("cleanup_position_override", explicitCleanupPosition)
                .put("fixed_anchor_ms", anchorMs).put("public_seek_step_ms", 5_000)
            persist()
            primeSeekCapability()
            for (id in requested) runScene(id)
            checkCancellation()
            manifest.put("status", "finished")
        } catch (error: Throwable) {
            primaryFailure = error
            manifest.put("status", "failed").put("failure_reason", error.message)
            throw error
        } finally {
            // Host aborts must still reach Pause and restore, even while the request file remains.
            cleanupInProgress = true
            val cleanup = JSONObject().put("suite_initial_elapsed_ms", initialElapsedMs)
                .put("position_restore_requested_ms", cleanupPositionMs).put("position_restore_override", explicitCleanupPosition)
            val positionRestoreRequired = seekUsed || explicitCleanupPosition
            try { pauseOwned(); cleanup.put("paused", state() == "paused") }
            catch (error: Throwable) { cleanup.put("pause_error", error.message) }
            if (baselineCaptured && stateSafely() == "paused") {
                try {
                    wake(); if (ui.has("queue-panel")) { click("player-queue"); awaitGone("queue-panel") }
                    ui.settle(420)
                    if (positionRestoreRequired) {
                        val restored = nearestGrid(cleanupPositionMs).coerceAtMost(((totalDurationMs - 5_000L) / 5_000L) * 5_000L)
                        seekPaused(restored, "restore_original_cursor")
                        val observed = progress().first
                        cleanup.put("position_restore_grid_ms", restored)
                            .put("position_restored_observed_ms", observed)
                            .put("position_restore_delta_ms", observed - cleanupPositionMs)
                            .put("position_restore_resolution_ms", 5_000)
                    }
                    if (lyricsLayout() != originalLyrics) { click("player-lyrics"); ui.settle(500) }
                    assertIdentity()
                    cleanup.put("identity_verified", true).put("lyrics_restored", lyricsLayout() == originalLyrics)
                    if (originalQueue) { click("player-queue"); awaitId("queue-panel") }
                    if (!originalControls && ui.has("player-controls")) {
                        if (originalQueue) {
                            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 120); ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 120)
                            awaitGone("player-controls")
                        } else { ui.key(KeyEvent.KEYCODE_BACK, 400); awaitGone("player-controls") }
                    } else if (originalControls) {
                        progress() // Refresh visible chrome's inactivity timer outside measurement.
                        ui.settle(320)
                    }
                    cleanup.put("view_restored", ui.has("queue-panel") == originalQueue && cleanup.optBoolean("lyrics_restored"))
                        .put("controls_restored", ui.has("player-controls") == originalControls).put("final", observation())
                } catch (error: Throwable) { cleanup.put("restore_error", error.message) }
            }
            val restoredPosition = !positionRestoreRequired || (cleanup.has("position_restore_delta_ms") &&
                kotlin.math.abs(cleanup.getLong("position_restore_delta_ms")) <= 5_000L)
            val cleanupOk = (!everResumed || cleanup.optBoolean("paused")) && (!baselineCaptured ||
                (cleanup.optBoolean("identity_verified") && cleanup.optBoolean("view_restored") &&
                    cleanup.optBoolean("controls_restored") && restoredPosition))
            cleanup.put("status", if (cleanupOk) "ok" else "failed").put("position_restore_within_5s", restoredPosition)
            if (!cleanupOk) manifest.put("status", "failed").put("cleanup_failed", true)
            manifest.put("cleanup", cleanup).put("end_wall_ms", System.currentTimeMillis()); persist()
            ui.close()
            instrumentation.sendStatus(2, Bundle().apply {
                putString("rendering_survey_manifest", File(directory, "manifest.json").absolutePath)
            })
            if (!cleanupOk) {
                val cleanupFailure = SurveyUnsafe("Playback cleanup failed: " + cleanup)
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun runScene(id: String) {
        checkCancellation()
        status("SURVEY_BEGIN " + id)
        val trials = JSONArray()
        val scene = JSONObject().put("scene_id", id).put("status", "pending").put("workload", "interaction").put("trials", trials)
        scenes.put(scene); persist()
        try {
            val warmup = window(id, 0, 2_000L, "warmup")
            scene.put("warmup", warmup); persist()
            check(warmup.optString("status") == "ok") { "Warmup: " + warmup.optString("skip_reason") }
            for (trial in 1..trialCount) {
                val result = window(id, trial, 6_000L, "measure")
                trials.put(result); persist()
                check(result.optString("status") == "ok") { result.optString("skip_reason") }
            }
            checkCancellation()
            scene.put("status", "ok")
        } catch (error: Throwable) {
            scene.put("status", "failed").put("skip_reason", error.message)
            if (error is PlaybackSurveyCancelled) { scene.put("cancelled", true); throw error }
            while (trials.length() < trialCount) trials.put(JSONObject().put("scene_id", id).put("trial", trials.length() + 1)
                .put("phase", "measure").put("status", "skip").put("skip_reason", error.message))
            if (error is SurveyUnsafe) throw error
        } finally { pauseOwned(); persist() }
    }

    private fun window(id: String, trial: Int, durationMs: Long, phase: String): JSONObject {
        val prefix = id + "-" + phase + "-" + trial
        val record = JSONObject().put("scene_id", id).put("trial", trial).put("phase", phase)
            .put("workload", "interaction").put("clock", "monotonic").put("trace_clock", "boottime")
            .put("duration_ms", durationMs).put("status", "pending")
        try {
            checkCancellation()
            seekPaused(anchorMs, id + ":" + phase + ":" + trial)
            prepare(id)
            val initial = progress()
            check(kotlin.math.abs(initial.first - anchorMs) <= 1_000L) { "Seek anchor was not adopted before resume" }
            check(initial.second >= durationMs + 18_000L) { "Insufficient remaining duration for a no-next-track window" }
            resume()
            awaitSetup("Actual playback position did not advance", 6_000) { progress().first >= initial.first + 1_000L }
            val liveRemaining = progress().second
            check(liveRemaining >= durationMs + 18_000L) { "Remaining duration became unsafe after resume" }
            if (id == "playing_lyrics") {
                val lyricWait = minOf(25_000L, (liveRemaining - durationMs - 18_000L).coerceAtLeast(1_000L))
                awaitSetup("No real lyric rows loaded for the existing track", lyricWait) {
                    ui.hasPattern(Regex("lyrics-row-\\d+")) && lyricsLayout()
                }
            }
            wake() // Native preparation or TTML loading may outlast chrome's 5s auto-hide.
            val startProgress = progress()
            check(startProgress.first in (anchorMs + 1_000L)..(anchorMs + 18_000L)) { "Resumed progress did not adopt same-track anchor" }
            check(startProgress.second >= durationMs + 15_000L)
            if (id == "playing_queue_cursor") {
                ui.key(KeyEvent.KEYCODE_DPAD_UP, 120); ui.key(KeyEvent.KEYCODE_DPAD_UP, 120)
                awaitSetup("Queue item cursor not entered", 4_000) { selectedQueueIndex() != null }
                repeat((selectedQueueIndex() ?: 0).coerceAtMost(100)) { checkCancellation(); ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 100) }
                check(selectedQueueIndex() == 0 && ui.has("player-queue-item-1")) { "Need existing queue cards zero and one" }
            } else if (id == "playing_lyrics") {
                ui.key(KeyEvent.KEYCODE_BACK, 420); awaitGone("player-controls")
            }
            val before = ui.snapshot()
            if (id == "playing_queue_cursor") assertQueueFocus(before)
            if (id == "playing_controls") check(before.selected.any { it.startsWith("player-") && it != "player-progress" })
            capturePng(prefix + "-setup.png", record, "setup_png")
            record.put("start", before.json()).put("progress_start_ms", startProgress.first)
                .put("remaining_start_ms", startProgress.second).put("proof", JSONObject().put("track_identity", identity)
                    .put("real_playing_state", state()).put("queue_cursor_only", id == "playing_queue_cursor"))
                .put("app_pids", JSONArray(ui.device.executeShellCommand("pidof " + TARGET).trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)))
            check(state() == "playing")
            checkCancellation()
            val reset = prefix + "-reset.txt"
            File(directory, reset).writeText(ui.device.executeShellCommand("dumpsys gfxinfo " + TARGET + " reset"))
            record.put("gfxinfo_reset", reset)
            val inputs = if (id == "playing_controls") List(12) { if (it % 2 == 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT }
                else if (id == "playing_queue_cursor") listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
                else emptyList()
            record.put("input_plan", JSONArray(inputs.map(KeyEvent::keyCodeToString)))
            val actual = JSONArray()
            val start = System.nanoTime(); record.put("start_ns", start).put("trace_start_ns", SystemClock.elapsedRealtimeNanos())
                .put("start_wall_ms", System.currentTimeMillis())
            Trace.beginSection("survey:" + id + ":" + trial)
            measuring = true
            try {
                for ((index, code) in inputs.withIndex()) {
                    val scheduled = start + durationMs * 1_000_000L * index / inputs.size
                    until(scheduled); val begin = System.nanoTime(); ui.inject(code)
                    actual.put(JSONObject().put("index", index).put("input", KeyEvent.keyCodeToString(code))
                        .put("scheduled_ns", scheduled).put("actual_ns", begin).put("injection_duration_ns", System.nanoTime() - begin)
                        .put("late_ns", (begin - scheduled).coerceAtLeast(0L)))
                }
                until(start + durationMs * 1_000_000L)
            } finally {
                record.put("end_ns", System.nanoTime()).put("trace_end_ns", SystemClock.elapsedRealtimeNanos())
                    .put("end_wall_ms", System.currentTimeMillis()).put("input_actual", actual)
                    .put("duration_ns", record.getLong("end_ns") - start)
                Trace.endSection()
                measuring = false
            }
            // Finish the bounded measured body before observing host cancellation.
            checkCancellation()
            val gfx = ui.device.executeShellCommand("dumpsys gfxinfo " + TARGET + " framestats")
            val gfxFile = prefix + "-gfxinfo.txt"; File(directory, gfxFile).writeText(gfx); record.put("gfxinfo", gfxFile)
            val end = ui.snapshot(); val endState = state()
            record.put("end", end.json()).put("endpoint_verified_ns", System.nanoTime()).put("state_at_end", endState)
            check(endState == "playing") { "Playback did not stay playing throughout the window" }
            if (id == "playing_lyrics") check(lyricsLayout() && end.ids.any { it.startsWith("lyrics-row-") })
            if (id == "playing_controls") check("player-controls" in end.ids)
            if (id == "playing_queue_cursor") {
                assertQueueFocus(end)
                check(selectedQueueIndex() == 1) { "Queue cursor did not reach card one" }
            }
            capturePng(prefix + "-end.png", record, "end_png")
            wake(); val finalProgress = progress(); record.put("progress_end_ms", finalProgress.first)
                .put("remaining_end_ms", finalProgress.second).put("progress_advance_ms", finalProgress.first - startProgress.first)
            check(finalProgress.first - startProgress.first >= durationMs - 1_500L) { "Playback position did not advance with the measured window" }
            pauseOwned()
            if (ui.has("queue-panel")) { click("player-queue"); awaitGone("queue-panel"); ui.settle(420) }
            assertIdentity()
            val count = frameCount(gfx, start, record.getLong("end_ns")); record.put("in_window_frame_rows", count)
            check(count > 0) { "No measured rendering frames" }
            checkCancellation()
            record.put("status", "ok")
        } catch (error: Throwable) {
            record.put("status", "failed").put("skip_reason", error.message)
            if (error is PlaybackSurveyCancelled) record.put("cancelled", true)
            capturePng(prefix + "-failure.png", record, "failure_png")
            if (error is SurveyUnsafe || error is PlaybackSurveyCancelled) throw error
        } finally {
            try { pauseOwned() } catch (error: Throwable) { record.put("cleanup_error", error.message) }
            val paused = stateSafely() == "paused"
            record.put("paused_after_window", paused)
            if (!paused) record.put("status", "failed").put("skip_reason", "cleanup_not_paused")
            File(directory, prefix + ".json").writeText(record.toString(2))
            status("SURVEY_RESULT " + id + " " + phase + " " + trial + " " + record.optString("status"))
            if (!paused && everResumed) throw SurveyUnsafe("Existing playback could not be paused after window")
        }
        return record
    }

    private fun prepare(id: String) {
        checkCancellation()
        check(state() == "paused") { "Every setup must start paused" }
        wake(); if (ui.has("queue-panel")) { click("player-queue"); awaitGone("queue-panel"); ui.settle(420) }
        assertIdentity()
        if (id == "playing_queue_cursor") { click("player-queue"); awaitId("queue-panel"); ui.settle(420) }
        else if (!lyricsLayout()) { click("player-lyrics"); ui.settle(500) }
        check(state() == "paused")
    }

    private fun capturePng(name: String, record: JSONObject, field: String) {
        val proof = JSONObject().put("captured_ns", System.nanoTime()).put("captured_wall_ms", System.currentTimeMillis())
        try {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: throw SurveyUnavailable("No active window for screenshot")
            val owned = try { root.packageName?.toString() == TARGET } finally { root.recycle() }
            if (!owned || !ui.has("player-screen")) throw SurveyUnavailable("Player does not own the screenshot window")
            check(ui.device.takeScreenshot(File(directory, name))) { "Screenshot provider returned false" }
            proof.put("file", name).put("status", "ok")
        } catch (error: Throwable) { proof.put("status", "unavailable").put("error", error.message) }
        record.put(field, proof)
    }

    private fun nearestGrid(value: Long): Long = ((value + 2_500L) / 5_000L) * 5_000L

    /** Public remote scrubber only; setup/restore never runs inside a measured window. */
    private fun seekPaused(targetMs: Long, purpose: String) {
        checkCancellation()
        check(state() == "paused") { "Same-track seek requires a paused player" }
        check(targetMs >= 0L && targetMs % 5_000L == 0L && targetMs <= totalDurationMs - 5_000L)
        wake()
        if (ui.has("queue-panel")) { click("player-queue"); awaitGone("queue-panel"); ui.settle(420) }
        assertIdentity()
        if (!enterProgress(4_000, "seek:" + purpose))
            throw SurveyUnavailable("Public progress never adopted selected ownership before seek")
        requireProgressOwner()
        val before = progress()
        val leftSteps = minOf(120, (totalDurationMs / 5_000L).toInt() + 2)
        val rightSteps = (targetMs / 5_000L).toInt()
        check(rightSteps <= 120) { "Restore cursor exceeds bounded public scrubber steps" }
        val operation = JSONObject().put("purpose", purpose).put("status", "prepared").put("started_ns", System.nanoTime())
            .put("identity", identity).put("before_elapsed_ms", before.first).put("target_ms", targetMs)
            .put("maximum_left_steps", leftSteps).put("right_steps", rightSteps)
        seekOperations.put(operation); persist()
        try {
            // First Left always establishes isScrubbing, including when already at zero.
            pendingScrub = true
            repeat(leftSteps) { requireProgressOwner(); ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 35) }
            check(progress().first == 0L) { "Public scrubber did not prove the zero clamp" }
            // A 0→5s→0 preview proves Left/Right reached the scrubber even if
            // accessibility still held an older selected flag. Nothing is committed here.
            requireProgressOwner(); ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 120)
            awaitSetup("Public scrubber preview did not advance to five seconds", 3_000) { progress().first == 5_000L }
            requireProgressOwner(); ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 120)
            awaitSetup("Public scrubber preview did not return to zero", 3_000) { progress().first == 0L }
            operation.put("zero_five_zero_preview_verified", true)
            repeat(rightSteps) { requireProgressOwner(); ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 35) }
            check(progress().first == targetMs) { "Public pending scrubber label did not reach the requested grid" }
            requireProgressOwner(); assertIdentity()
            check(state() == "paused")
            operation.put("status", "pending_label_verified").put("pending_elapsed_ms", progress().first); persist()
            // ACTION_CLICK/SET_PROGRESS do not exist on this remote-only scrubber.
            // Source routes this paired CENTER to seek only after a Left established scrubbing.
            seekUsed = true
            commitProvenScrub()
            awaitSetup("Committed same-track cursor was not adopted", 8_000) {
                state() == "paused" && kotlin.math.abs(progress().first - targetMs) <= 1_000L
            }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 180)
            assertIdentity()
            operation.put("status", "ok").put("observed_elapsed_ms", progress().first)
        } catch (error: Throwable) {
            operation.put("status", "failed").put("error", error.message)
            // Back cancels an uncommitted scrub instead of ever committing an unproved owner.
            if (stateSafely() == "paused" && ui.has("player-screen")) {
                try { ui.key(KeyEvent.KEYCODE_BACK, 180); wake() } catch (_: Throwable) { }
            }
            throw error
        } finally { pendingScrub = false; operation.put("finished_ns", System.nanoTime()); persist() }
    }

    private fun enterProgress(timeoutMs: Long, purpose: String): Boolean {
        checkCancellation()
        wake()
        val before = ui.snapshot()
        val probe = JSONObject().put("purpose", purpose).put("start_ns", System.nanoTime())
            .put("before", before.json()).put("state_before", state()).put("observations", JSONArray())
        progressOwnerProbes.put(probe); persist()
        val controls = setOf("player-previous", "player-play-pause", "player-next", "player-lyrics-display",
            "player-lyrics", "player-queue", "player-more", "player-radio")
        val freshProgressSelected = selectedNow("player-progress")
        val freshControlsSelected = controls.filter(::selectedNow)
        probe.put("fresh_before_progress_selected", freshProgressSelected)
            .put("fresh_before_controls_selected", JSONArray(freshControlsSelected))
        if (!freshProgressSelected) {
            if (freshControlsSelected.isEmpty()) {
                click("player-queue"); awaitId("queue-panel")
                click("player-queue"); awaitGone("queue-panel"); ui.settle(420)
            }
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 0)
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var selectedSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancellation()
            val selected = try { withNode("player-progress") { it.isSelected || it.isChecked } }
                catch (_: SurveyUnavailable) { false }
            val now = SystemClock.uptimeMillis()
            probe.getJSONArray("observations").put(JSONObject().put("observed_ns", System.nanoTime()).put("progress_selected", selected))
            if (selected) {
                if (selectedSince == 0L) selectedSince = now
                if (now - selectedSince >= 320L) {
                    probe.put("adopted", true).put("end", ui.snapshot().json()).put("end_ns", System.nanoTime()); persist(); return true
                }
            } else selectedSince = 0L
            ui.settle(80)
        }
        probe.put("adopted", false).put("end", ui.snapshot().json()).put("state_end", state()).put("end_ns", System.nanoTime())
        capturePng("progress-owner-" + progressOwnerProbes.length() + "-failure.png", probe, "failure_png")
        persist(); return false
    }
    private fun primeSeekCapability() {
        // A restored paused session has nativeCanSeek=false until native preparation.
        if (enterProgress(1_200, "bootstrap_paused_initial")) {
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 180)
            manifest.put("native_seek_bootstrap", JSONObject().put("initially_prepared", true)); persist(); return
        }
        val before = progress()
        check(before.second >= 15_000L) { "Unprepared native session needs at least 15s remaining to prime safely" }
        val bootstrap = JSONObject().put("initially_prepared", false).put("before_elapsed_ms", before.first)
        manifest.put("native_seek_bootstrap", bootstrap); persist()
        try {
            resume()
            awaitSetup("Native same-track preparation did not advance", 6_000) { progress().first >= before.first + 1_000L }
            val waitBudget = minOf(8_000L, (progress().second - 8_000L).coerceAtLeast(1_000L))
            val deadline = SystemClock.uptimeMillis() + waitBudget
            var ready = false
            while (SystemClock.uptimeMillis() < deadline && progress().second >= 8_000L) {
                if (enterProgress(1_200, "bootstrap_playing_capability")) { ready = true; break }
            }
            bootstrap.put("playing_progress_owner_ready", ready)
            check(ready) { "Native session never exposed a selectable progress owner while playing" }
        } finally {
            withoutCancellation {
                pauseOwned(); assertIdentity()
                bootstrap.put("after_elapsed_ms", progress().first).put("paused", state() == "paused"); persist()
            }
        }
        val pausedReady = enterProgress(4_000, "bootstrap_paused_confirm")
        bootstrap.put("paused_progress_owner_ready", pausedReady); persist()
        check(pausedReady) { "Native session exposed no selected progress owner after pausing" }
        ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 180)
    }

    private fun requireProgressOwner() {
        checkCancellation()
        ui.requireForeground()
        val proof = JSONObject().put("checked_ns", System.nanoTime())
        try {
            withNode("player-progress") { node ->
                proof.put("target", ownerNodeProof(node))
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: throw SurveyUnsafe("Progress owner absent")
                try {
                    val refreshed = root.refresh()
                    proof.put("active_root_refreshed", refreshed).put("active_root", ownerNodeProof(root))
                    if (!refreshed || root.packageName?.toString() != TARGET || node.windowId != root.windowId ||
                        node.packageName?.toString() != TARGET || !node.isVisibleToUser || !node.isEnabled ||
                        !(node.isSelected || node.isChecked)) throw SurveyUnsafe("Public progress is not the selected active-window owner")
                } finally { root.recycle() }
            }
        } catch (error: Throwable) {
            proof.put("error", error.message)
            val roots = JSONArray()
            try {
                val windows = instrumentation.uiAutomation.windows
                try {
                    for (window in windows) {
                        val entry = JSONObject().put("id", window.id).put("type", window.type)
                            .put("active", window.isActive).put("focused", window.isFocused)
                        window.root?.let { root ->
                            try { entry.put("refreshed", root.refresh()).put("root", ownerNodeProof(root)) }
                            finally { root.recycle() }
                        }
                        roots.put(entry)
                    }
                } finally { windows.forEach { it.recycle() } }
            } catch (rootError: Throwable) { proof.put("current_roots_error", rootError.message) }
            proof.put("current_roots", roots)
            progressOwnerRejections.put(proof)
            capturePng("progress-owner-rejection-" + progressOwnerRejections.length() + ".png", proof, "failure_png")
            persist()
            throw error
        }
    }
    private fun ownerNodeProof(node: AccessibilityNodeInfo): JSONObject = JSONObject()
        .put("id", node.viewIdResourceName).put("package", node.packageName?.toString())
        .put("window_id", node.windowId).put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
        .put("selected", node.isSelected).put("checked", node.isChecked).put("focused", node.isFocused)
        .put("focusable", node.isFocusable).put("clickable", node.isClickable)

    private fun commitProvenScrub() {
        requireProgressOwner()
        check(state() == "paused"); assertIdentity()
        val automation = instrumentation.uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        if (!automation.injectInputEvent(down, true)) throw SurveyUnsafe("Proven scrub CENTER Down was rejected")
        val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        if (!automation.injectInputEvent(up, true)) throw SurveyUnsafe("Proven scrub CENTER Up was rejected")
    }

    private fun resume() {
        checkCancellation()
        check(state() == "paused"); wake(); ownedPlayback = true; everResumed = true; click("player-play-pause")
        awaitSetup("Existing track did not start playing", 30_000) { state() == "playing" }
        wake()
    }
    private fun pauseOwned() = withoutCancellation {
        if (!ownedPlayback) return@withoutCancellation
        wake()
        if (state() != "paused") {
            val label = withNode("player-play-pause") { it.stateDescription?.toString().orEmpty() }
            check(label in ui.stringLabels("player_pause")) { "Cannot prove Pause action before cleanup: " + label }
            click("player-play-pause")
        }
        awaitSetup("Existing track did not pause", 10_000) { state() == "paused" }
        ownedPlayback = false
    }
    private fun wake() {
        checkCancellation()
        ui.requireForeground()
        if (!ui.has("player-controls")) { ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 250); awaitId("player-controls") }
    }
    private fun state(): String {
        val label = withNode("player-screen") { it.stateDescription?.toString().orEmpty() }
        return listOf("playing", "paused", "preparing").firstOrNull { label in ui.stringLabels("player_state_" + it) }
            ?: throw SurveyUnsafe("Unknown public playback state: " + label)
    }
    private fun stateSafely() = withoutCancellation { try { state() } catch (_: Throwable) { "unknown" } }
    private fun assertIdentity() {
        if (artworkIdentity() != identity) throw SurveyUnsafe("Existing track identity changed; no new track was authorized")
    }
    private fun artworkIdentity(): String = ui.label("player-artwork").replace(Regex("\\s+"), " ").trim()
    private fun lyricsLayout(): Boolean = ui.has("lyrics-panel") && withNode("player-artwork") {
        val rect = Rect(); it.getBoundsInScreen(rect); rect.centerX() < ui.device.displayWidth * .4f
    }
    private fun selectedQueueIndex(): Int? {
        checkCancellation()
        check(!measuring) { "Queue cursor proof must remain outside measurement" }
        ui.requireForeground()
        val pattern = Regex("player-queue-item-(\\d+)")
        // The snapshot discovers IDs only; its cached selected flags never prove the cursor.
        val candidates = ui.snapshot().ids.filter { pattern.matches(it) }
        val selected = mutableListOf<Int>()
        val nodes = JSONArray()
        val proof = JSONObject().put("checked_ns", System.nanoTime()).put("nodes", nodes)
        val proofs = manifest.optJSONArray("queue_cursor_probes") ?: JSONArray().also {
            manifest.put("queue_cursor_probes", it)
        }
        try {
            for (id in candidates) {
                try {
                    withNode(id) { node ->
                        val root = instrumentation.uiAutomation.rootInActiveWindow
                            ?: throw SurveyUnsafe("Queue cursor active owner absent")
                        try {
                            val refreshed = root.refresh()
                            nodes.put(ownerNodeProof(node).put("active_window_id", root.windowId)
                                .put("active_root_refreshed", refreshed))
                            if (!refreshed || root.packageName?.toString() != TARGET || node.windowId != root.windowId)
                                throw SurveyUnsafe("Queue cursor does not belong to the active player window")
                            if (node.isSelected || node.isChecked)
                                selected.add(pattern.matchEntire(id)!!.groupValues[1].toInt())
                        } finally { root.recycle() }
                    }
                } catch (obsolete: SurveyUnavailable) {
                    nodes.put(JSONObject().put("id", id).put("obsolete_or_absent", obsolete.message))
                }
            }
            proof.put("selected_indices", JSONArray(selected))
            if (selected.size > 1) throw SurveyUnsafe("Multiple fresh queue cursors are selected: " + selected)
            return selected.singleOrNull()
        } catch (error: Throwable) {
            proof.put("error", error.message)
            throw error
        } finally { proofs.put(proof); persist() }
    }
    private fun assertQueueFocus(snapshot: SurveySnapshot) {
        check(snapshot.focused == listOf("player-screen") && snapshot.nodes.none {
            it.focused && (it.id.startsWith("player-queue-item-") || it.resolvedId.startsWith("player-queue-item-"))
        }) { "Queue cursor stole Android focus; expected player-screen only: " + snapshot.focused }
    }
    /** Setup and END only: chrome can disappear while native preparation waits. */
    private fun progress(): Pair<Long, Long> {
        checkCancellation()
        check(!measuring) { "No progress hierarchy reads are allowed inside the measured body" }
        if (pendingScrub) return rawProgress()
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            wake()
            val controls = setOf("player-previous", "player-play-pause", "player-next", "player-lyrics-display",
                "player-lyrics", "player-queue", "player-more", "player-radio")
            if (!selectedNow("player-progress") && controls.any(::selectedNow)) {
                // Focus-only movement refreshes controlsActivity; no transport activates.
                ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 35); ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 35)
            }
            try { return rawProgress() }
            catch (error: SurveyUnavailable) {
                lastFailure = error
                progressReadRecoveries.put(JSONObject().put("attempt", attempt).put("observed_ns", System.nanoTime())
                    .put("error", error.message).put("snapshot", ui.snapshot().json()))
                // A hidden controller consumes this first directional wake without seeking.
                ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 250)
                awaitId("player-progress", 3_000)
            }
        }
        throw SurveyUnavailable("Public progress remained unavailable after setup-only wake retries: " + lastFailure?.message)
    }
    private fun rawProgress(): Pair<Long, Long> {
        val texts = withNode("player-progress") { node ->
            val values = mutableListOf<String>()
            fun visit(n: AccessibilityNodeInfo) {
                if (!n.refresh()) throw SurveyUnavailable("Progress label became obsolete")
                n.text?.toString()?.let(values::add)
                for (i in 0 until n.childCount) n.getChild(i)?.let { try { visit(it) } finally { it.recycle() } }
            }
            visit(node); values
        }
        val elapsed = texts.firstOrNull { it.matches(Regex("\\d+:\\d{2}(?::\\d{2})?")) }
        val remaining = texts.firstOrNull { it.startsWith("−") || it.startsWith("-") }
        fun time(value: String?): Long {
            check(value != null) { "No public elapsed/remaining progress labels: " + texts }
            return value.removePrefix("−").removePrefix("-").split(':').fold(0L) { a, b -> a * 60 + b.toLong() } * 1_000L
        }
        return time(elapsed) to time(remaining)
    }
    private fun click(id: String) {
        checkCancellation()
        check(id in allowed)
        ui.requireForeground()
        withNode(id) { node ->
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: throw SurveyUnsafe("No active player window")
            try {
                if (root.packageName?.toString() != TARGET || node.windowId != root.windowId || !node.isVisibleToUser ||
                    !node.isEnabled || !node.isClickable || node.packageName?.toString() != TARGET ||
                    node.className?.toString() !in setOf("android.view.View", "android.widget.Button", "android.widget.ImageButton"))
                    throw SurveyUnsafe("Playback activation ownership rejected: " + id)
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw SurveyUnsafe("Playback action failed: " + id)
            } finally { root.recycle() }
        }
        ui.settle(180)
    }
    private fun <T> withNode(id: String, action: (AccessibilityNodeInfo) -> T): T {
        checkCancellation()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: throw SurveyUnavailable("No active window")
        if (!root.refresh()) { root.recycle(); throw SurveyUnavailable("Active player root became obsolete") }
        if (root.packageName?.toString() != TARGET) { root.recycle(); throw SurveyUnsafe("Player lost foreground ownership") }
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.viewIdResourceName.orEmpty().substringAfter(":id/") == id) {
                // Compose custom cursors are selected but deliberately not Android focusable.
                // Their cached selected flag must be refreshed just like focused nodes.
                if (!node.refresh()) throw SurveyUnavailable("Player target became obsolete: " + id)
                if (node.isVisibleToUser && node.packageName?.toString() == TARGET &&
                    node.viewIdResourceName.orEmpty().substringAfter(":id/") == id)
                    return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                val result = try { find(child) } finally { child.recycle() }
                if (result != null) return result
            }
            return null
        }
        val node = try { find(root) } finally { root.recycle() } ?: throw SurveyUnavailable("Missing " + id)
        return try { action(node) } finally { node.recycle() }
    }
    private fun selectedNow(id: String): Boolean = try { withNode(id) { it.isSelected || it.isChecked } }
        catch (_: SurveyUnavailable) { false }

    private fun observation(): JSONObject = ui.snapshot().json().put("state", stateSafely())
        .put("progress", try { JSONArray(rawProgress().toList()) } catch (_: Throwable) { JSONObject.NULL })
    private fun frameCount(raw: String, lo: Long, hi: Long): Int {
        var intended = -1; var flags = -1; var count = 0
        for (line in raw.lineSequence()) {
            val row = line.trim().split(',')
            if (row.firstOrNull() == "Flags") { intended = row.indexOf("IntendedVsync"); flags = row.indexOf("Flags") }
            else if (intended >= 0 && flags >= 0 && row.getOrNull(flags) == "0" &&
                row.getOrNull(intended)?.toLongOrNull()?.let { it >= lo && it < hi } == true) count++
        }
        return count
    }
    private class PlaybackSurveyCancelled(message: String) : RuntimeException(message)

    /** Never called within BEGIN/END; cancellation is a failed run with ordinary verified cleanup. */
    private fun checkCancellation() {
        if (cleanupInProgress || !::directory.isInitialized) return
        check(!measuring) { "Cancellation filesystem polling is forbidden inside a measured window" }
        val request = File(directory, "cancel-request")
        if (!request.exists()) return
        val reason = runCatching { request.readText().trim().take(1_024) }.getOrDefault("")
            .ifBlank { "Host requested cooperative cancellation" }
        manifest.put("cancelled", true).put("cancel_reason", reason).put("cancel_observed_ns", System.nanoTime())
        persist()
        throw PlaybackSurveyCancelled(reason)
    }

    private inline fun <T> withoutCancellation(block: () -> T): T {
        val prior = cleanupInProgress
        cleanupInProgress = true
        return try { block() } finally { cleanupInProgress = prior }
    }

    private fun awaitSetup(message: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        checkCancellation()
        ui.await(message, timeout) { checkCancellation(); predicate() }
        checkCancellation()
    }
    private fun awaitId(id: String, timeout: Long = 20_000) = awaitSetup("Missing " + id, timeout) { ui.has(id) }
    private fun awaitGone(id: String) = awaitSetup("Still visible " + id, 8_000) { !ui.has(id) }

    private fun until(deadline: Long) { while (System.nanoTime() < deadline) SystemClock.sleep(((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)) }
    private fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", value + "\n") })
    private fun persist() { File(directory, "manifest.json").writeText(manifest.toString(2)) }
}
