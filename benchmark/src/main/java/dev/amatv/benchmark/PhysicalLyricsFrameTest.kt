package dev.amatv.benchmark

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** One paused current song; cold preparation uses only its validated production Play button. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class PhysicalLyricsFrameTest {
    @Test fun existingSongLyrics() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_physical_lyrics") == "1" && Build.VERSION.SDK_INT >= 29)
        check(Build.HARDWARE == args.getString("expected_hardware"))
        val run = checkNotNull(args.getString("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "physical-lyrics/$run")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val report = JSONObject().put("run_id", run).put("hardware", Build.HARDWARE)
            .put("new_track_or_queue_actions", false).put("measurement_ms", 6_000)
        var controller: MediaController? = null
        var original: PlaybackState? = null
        var identity: String? = null
        var originalLyrics = false
        var originalQueue = false
        var originalControls = false
        var capturedUi = false
        var transportChanged = false
        var bootstrapClicked = false
        var failure: Throwable? = null
        val listener = ComponentName(inst.context, PerformanceNotificationListener::class.java)
        val listenerName = listener.flattenToString()
        val notificationManager = inst.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val listenerAccessBefore = notificationManager.isNotificationListenerAccessGranted(listener)
        val user = ui.device.executeShellCommand("am get-current-user").trim().also { require(it.matches(Regex("\\d+"))) }
        fun listenerSettings() = ui.device.executeShellCommand(
            "settings --user $user get secure enabled_notification_listeners").trim()
        fun listenerComponents(raw: String): Set<ComponentName> = raw.split(':').mapNotNull {
            ComponentName.unflattenFromString(it)
        }.toSet()
        val listenersBefore = listenerSettings()
        var listenerGrantedByTest = false
        val manager = inst.context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        fun ownSessions() = manager.getActiveSessions(listener).filter { it.packageName == TARGET }
        report.put("notification_listener", JSONObject().put("component", listenerName).put("user", user)
            .put("access_before", listenerAccessBefore).put("secure_setting_before", listenersBefore))
        try {
            if (!listenerAccessBefore) {
                val capabilities = ui.device.executeShellCommand("cmd notification help")
                check(capabilities.contains("allow_listener") && capabilities.contains("disallow_listener")) {
                    "Platform does not support reversible notification-listener access"
                }
                listenerGrantedByTest = true // Revoke even if dispatch succeeds but its response fails.
                report.getJSONObject("notification_listener").put("grant_response",
                    ui.device.executeShellCommand("cmd notification allow_listener $listenerName $user"))
            }
            ui.await("Test notification listener was not granted", 5_000) {
                notificationManager.isNotificationListenerAccessGranted(listener)
            }
            if (!PerformanceNotificationListener.connected) NotificationListenerService.requestRebind(listener)
            ui.await("Test notification listener did not connect", 8_000) { PerformanceNotificationListener.connected }
            ui.launch()
            if (!ui.has("player-screen")) {
                ui.openNavigation()
                ui.activate("nav-nowplaying", "navigation")
                ui.awaitId("player-screen", 8_000)
            }
            originalControls = ui.has("player-controls")
            originalQueue = ui.has("queue-panel")
            originalLyrics = lyricsLayout(ui)
            capturedUi = true
            report.put("original_controls", originalControls).put("original_queue", originalQueue)
                .put("original_lyrics", originalLyrics)
            wake(ui)
            if (originalQueue) { ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("queue-panel") }
            val sessions = ownSessions()
            check(sessions.size <= 1) { "Ambiguous AMATV session ownership" }
            controller = sessions.singleOrNull()
            val initialState = controller?.playbackState
            val bootstrapRequired = controller == null || initialState == null ||
                initialState.state == PlaybackState.STATE_NONE || controller?.metadata == null
            if (bootstrapRequired) {
                val expectedTitle = checkNotNull(args.getString("expected_current_title")) {
                    "Cold current-song bootstrap requires expected_current_title"
                }.trim().also { require(it.isNotEmpty()) }
                val artworkLabel = ui.label("player-artwork")
                check(artworkLabel.contains(expectedTitle, ignoreCase = true)) { "Current artwork does not match the authorized song" }
                check(ui.playerPaused()) { "Current UI is not paused; no bootstrap is allowed" }
                val remaining = publicRemainingMs(ui)
                check(remaining >= 20_000L) { "Current song has insufficient safe remaining time" }
                val bootstrap = JSONObject().put("kind", "production_current_song_play_button")
                    .put("expected_title", expectedTitle).put("artwork_label", artworkLabel)
                    .put("public_remaining_ms", remaining).put("initial_session_count", sessions.size)
                    .put("initial_session_state", initialState?.state)
                report.put("bootstrap", bootstrap)
                clickOwnTransport(ui, "player_play") { bootstrapClicked = true; bootstrap.put("play_dispatched", true) }
                var firstPlayingPosition: Long? = null
                ui.await("Current song did not establish a playing media session", minOf(15_000L, remaining - 5_000L)) {
                    val owned = ownSessions()
                    check(owned.size <= 1) { "Ambiguous AMATV bootstrap session ownership" }
                    val current = owned.singleOrNull()
                    if (current != null) controller = current
                    val state = current?.playbackState
                    if (state?.state == PlaybackState.STATE_PLAYING &&
                        current?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().equals(expectedTitle, ignoreCase = true)) {
                        val position = projectedPosition(state)
                        if (firstPlayingPosition == null) firstPlayingPosition = position
                        position >= checkNotNull(firstPlayingPosition) + 500L
                    } else false
                }
                val prepared = checkNotNull(controller)
                bootstrap.put("playing", mediaState(prepared))
                prepared.transportControls.pause()
                ui.await("Current-song bootstrap could not pause", 5_000) {
                    prepared.playbackState?.state == PlaybackState.STATE_PAUSED
                }
                bootstrap.put("paused", mediaState(prepared)).put("status", "passed")
            }
            val media = checkNotNull(controller)
            original = checkNotNull(media.playbackState)
            check(original.state == PlaybackState.STATE_PAUSED)
            check(original.actions and PlaybackState.ACTION_SEEK_TO != 0L) { "Existing session is not seekable" }
            val metadata = checkNotNull(media.metadata)
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            check(duration > 90_000L && original.position in 0..duration)
            identity = identity(media)
            report.put("original", mediaState(media)).put("duration_ms", duration)
            if (!lyricsLayout(ui)) {
                activateToggle(ui, "player-lyrics")
                ui.await("Lyrics layout did not open", 5_000) { lyricsLayout(ui) }
            }
            check(identity(media) == identity)
            transportChanged = true // Cleanup owns transport even if a command fails after dispatch.
            media.transportControls.seekTo(60_000L)
            ui.await("Same-track seek was not adopted", 5_000) {
                media.playbackState?.let { it.state == PlaybackState.STATE_PAUSED && abs(it.position - 60_000L) <= 250 } == true
            }
            media.transportControls.play()
            ui.await("Existing song did not advance", 8_000) {
                media.playbackState?.let { it.state == PlaybackState.STATE_PLAYING && projectedPosition(it) >= 60_500L } == true
            }
            ui.await("No real lyric rows", 10_000) { lyricsLayout(ui) &&
                ui.snapshot().ids.any { it.matches(Regex("lyrics-row-\\d+")) } }
            check(identity(media) == identity)
            wake(ui)
            ui.key(KeyEvent.KEYCODE_BACK, 0)
            ui.awaitGone("player-controls")
            ui.device.takeScreenshot(File(out, "lyrics-setup.png"))
            report.put("setup", ui.snapshot().json()).put("pid", ui.device.executeShellCommand("pidof $TARGET").trim())
            val surface = ui.device.executeShellCommand("dumpsys SurfaceFlinger --list").lineSequence()
                .map(String::trim).filter { it.matches(Regex("dev\\.amatv/dev\\.amatv\\.MainActivity#\\d+")) }
                .maxByOrNull { it.substringAfterLast('#').toInt() }
            report.put("surface_name", surface)
            File(out, "trace-ready").writeText(SystemClock.elapsedRealtimeNanos().toString())
            if (args.getString("external_trace") == "1") ui.await("External trace did not release measurement", 20_000) {
                File(out, "trace-go").exists()
            }
            check(identity(media) == identity && media.playbackState?.state == PlaybackState.STATE_PLAYING)
            File(out, "gfxinfo-reset.txt").writeText(ui.device.executeShellCommand("dumpsys gfxinfo $TARGET reset"))
            report.put("before", mediaState(media)).put("start_ns", System.nanoTime())
                .put("trace_start_ns", SystemClock.elapsedRealtimeNanos())
            Trace.beginSection("survey:physical-playing-lyrics")
            try { SystemClock.sleep(6_000) } finally {
                report.put("end_ns", System.nanoTime()).put("trace_end_ns", SystemClock.elapsedRealtimeNanos())
                Trace.endSection()
            }
            // No accessibility/screenshot/media-session polling occurs inside the measured body.
            report.put("after", mediaState(media))
            File(out, "gfxinfo.txt").writeText(ui.device.executeShellCommand("dumpsys gfxinfo $TARGET framestats"))
            File(out, "surface-latency.txt").writeText(if (surface != null) ui.device.executeShellCommand(
                "dumpsys SurfaceFlinger --latency $surface") else "No current main-activity surface")
            check(identity(media) == identity && media.playbackState?.state == PlaybackState.STATE_PLAYING)
            check(report.getJSONObject("after").getLong("projected_position_ms") -
                report.getJSONObject("before").getLong("projected_position_ms") >= 5_000L)
            check(lyricsLayout(ui) && ui.snapshot().ids.any { it.startsWith("lyrics-row-") })
            report.put("end_view", ui.snapshot().json())
            ui.device.takeScreenshot(File(out, "lyrics-end.png"))
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.toString())
        } finally {
            val cleanup = JSONObject()
            try {
                if (bootstrapClicked) {
                    val owned = ownSessions()
                    owned.forEach { it.transportControls.pause() }
                    // A Play may still be preparing before any session exists.
                    // Cancel only this app's proven pending Pause action as well.
                    if (ui.has("player-screen") && !ui.playerPaused()) {
                        wake(ui)
                        val pauseLabel = node(ui, "player-play-pause") { it.stateDescription?.toString() }
                        if (pauseLabel in ui.stringLabels("player_pause")) clickOwnTransport(ui, "player_pause")
                    }
                    ui.await("Bootstrap cleanup did not pause its owned player", 5_000) {
                        ownSessions().all { it.playbackState?.state in setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_NONE) } &&
                            (!ui.has("player-screen") || ui.playerPaused())
                    }
                    cleanup.put("bootstrap_owned_session_pause", true)
                }
                val media = controller
                val baseline = original
                if (media != null && baseline != null && transportChanged) {
                    // Pause the owned session before checking identity, even on a failed measurement.
                    media.transportControls.pause()
                    ui.await("Cleanup could not pause", 5_000) { media.playbackState?.state == PlaybackState.STATE_PAUSED }
                    check(identity(media) == identity) { "Track identity changed; refusing to seek another song" }
                    media.transportControls.seekTo(baseline.position)
                    ui.await("Exact millisecond cursor was not restored", 6_000) {
                        media.playbackState?.let { it.state == PlaybackState.STATE_PAUSED && abs(it.position - baseline.position) <= 250 } == true
                    }
                    cleanup.put("position_requested_ms", baseline.position)
                        .put("position_delta_ms", checkNotNull(media.playbackState).position - baseline.position)
                        .put("position_tolerance_ms", 250).put("media", mediaState(media))
                }
                if (capturedUi && ui.has("player-screen")) {
                    wake(ui)
                    if (ui.has("queue-panel")) { ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("queue-panel") }
                    if (lyricsLayout(ui) != originalLyrics) {
                        activateToggle(ui, "player-lyrics")
                        ui.await("Original lyrics layout did not restore", 5_000) { lyricsLayout(ui) == originalLyrics }
                    }
                    if (originalQueue) { activateToggle(ui, "player-queue"); ui.awaitId("queue-panel", 5_000) }
                    if (!originalControls && ui.has("player-controls")) {
                        if (originalQueue) {
                            // Queue stays open when its chrome auto-hides. Down
                            // from Controls would instead close the queue.
                            ui.await("Original queue chrome did not auto-hide", 7_000) { !ui.has("player-controls") }
                        } else ui.key(KeyEvent.KEYCODE_BACK, 0)
                        ui.awaitGone("player-controls")
                    }
                    check(ui.has("queue-panel") == originalQueue && lyricsLayout(ui) == originalLyrics &&
                        ui.has("player-controls") == originalControls)
                    cleanup.put("view_restored", true)
                }
                cleanup.put("status", "passed")
            } catch (error: Throwable) {
                cleanup.put("status", "failed").put("error", error.toString())
                report.put("status", "failed")
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally {
                try {
                    if (listenerGrantedByTest) {
                        cleanup.put("listener_revoke_response",
                            ui.device.executeShellCommand("cmd notification disallow_listener $listenerName $user"))
                    }
                    ui.await("Notification listener access did not restore", 5_000) {
                        notificationManager.isNotificationListenerAccessGranted(listener) == listenerAccessBefore
                    }
                    val listenersAfter = listenerSettings()
                    cleanup.put("listener_granted_by_test", listenerGrantedByTest)
                        .put("listener_access_after", notificationManager.isNotificationListenerAccessGranted(listener))
                        .put("listener_secure_setting_after", listenersAfter)
                        .put("listener_setting_restored", listenerComponents(listenersAfter) == listenerComponents(listenersBefore))
                    check(cleanup.getBoolean("listener_setting_restored")) { "Enabled listener set changed; other listeners were not overwritten" }
                } catch (error: Throwable) {
                    cleanup.put("listener_cleanup_error", error.toString()).put("status", "failed")
                    report.put("status", "failed")
                    if (failure == null) failure = error else failure.addSuppressed(error)
                } finally {
                    report.put("cleanup", cleanup)
                    File(out, "result.json").writeText(report.toString(2))
                    ui.close()
                }
            }
        }
        failure?.let { throw it }
    }

    private fun identity(media: MediaController): String {
        val data = checkNotNull(media.metadata)
        return listOf(data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            data.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            data.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            data.getLong(MediaMetadata.METADATA_KEY_DURATION).toString()).joinToString("|")
            .also { check(!data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).isNullOrBlank()) }
    }

    private fun mediaState(media: MediaController): JSONObject {
        val state = checkNotNull(media.playbackState)
        return JSONObject().put("identity", identity(media)).put("state", state.state)
            .put("position_ms", state.position).put("position_updated_elapsed_ms", state.lastPositionUpdateTime)
            .put("projected_position_ms", projectedPosition(state))
            .put("speed", state.playbackSpeed).put("observed_elapsed_ns", SystemClock.elapsedRealtimeNanos())
    }

    private fun projectedPosition(state: PlaybackState): Long = state.position +
        if (state.state == PlaybackState.STATE_PLAYING) {
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L) * state.playbackSpeed).toLong()
        } else 0L

    private fun wake(ui: RenderingSurveyUi) {
        ui.requireForeground()
        // Rendering setup owns an explicit native input target; this suite
        // does not claim to validate natural page-return focus restoration.
        ui.focus("player-screen")
        if (!ui.has("player-controls")) ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.awaitId("player-controls", 4_000)
    }

    private fun clickOwnTransport(ui: RenderingSurveyUi, expectedLabelResource: String, onDispatch: () -> Unit = {}) {
        check(expectedLabelResource in setOf("player_play", "player_pause"))
        wake(ui)
        node(ui, "player-play-pause") { target ->
            check(target.isEnabled && target.isVisibleToUser && target.isClickable &&
                target.stateDescription?.toString() in ui.stringLabels(expectedLabelResource)) {
                "The current app transport is not the authorized action"
            }
            onDispatch()
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Current-song transport action was rejected" }
        }
    }

    private fun publicRemainingMs(ui: RenderingSurveyUi): Long = node(ui, "player-progress-remaining") { progress ->
        val values = mutableListOf<String>()
        fun visit(current: AccessibilityNodeInfo) {
            current.text?.toString()?.let(values::add)
            for (index in 0 until current.childCount) current.getChild(index)?.let { child ->
                try { visit(child) } finally { child.recycle() }
            }
        }
        visit(progress)
        val label = checkNotNull(values.firstOrNull { it.matches(Regex("[−-]\\d+:\\d{2}(?::\\d{2})?")) }) {
            "No public remaining-time label"
        }
        label.removePrefix("−").removePrefix("-").split(':').fold(0L) { total, part -> total * 60 + part.toLong() } * 1_000L
    }

    private fun lyricsLayout(ui: RenderingSurveyUi): Boolean = ui.has("lyrics-panel") &&
        node(ui, "player-artwork") { val bounds = Rect(); it.getBoundsInScreen(bounds)
            bounds.centerX() < ui.device.displayWidth * .4f }

    private fun activateToggle(ui: RenderingSurveyUi, id: String) {
        check(id in setOf("player-lyrics", "player-queue"))
        wake(ui)
        // Player chrome uses its production virtual cursor, not Android focus.
        repeat(8) { if (!selected(ui, id)) ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 35) }
        repeat(8) { if (!selected(ui, id)) ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 35) }
        check(selected(ui, id)) { "Cannot select production control $id" }
        node(ui, id) { check(it.isEnabled && it.isVisibleToUser && (it.isSelected || it.isChecked)) }
        check(ui.device.pressDPadCenter())
    }

    private fun selected(ui: RenderingSurveyUi, id: String): Boolean = node(ui, id) { it.isSelected || it.isChecked }

    private fun <T> node(ui: RenderingSurveyUi, id: String, action: (AccessibilityNodeInfo) -> T): T {
        ui.requireForeground()
        val root = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
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
        checkNotNull(target) { "Missing actual player target $id" }
        return try { action(target) } finally { target.recycle() }
    }
}
