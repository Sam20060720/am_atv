package dev.amatv.benchmark

import android.app.Instrumentation
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public presentation switches only: no transport, queue, account or network mutations. */
@RunWith(AndroidJUnit4::class)
class InterfaceStyleSwitchTest {
    @Test fun stylesKeepPlaybackAndReturnOwnership() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit interface-switch check only", args.getString("allow_interface_style") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Interface check is emulator-only" }
        val runId = (args.getString("run_id") ?: "interface-" + System.currentTimeMillis())
            .also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "interface-style/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Do not overwrite an earlier interface check" }
        val ui = RenderingSurveyUi(instrumentation)
        val cases = JSONArray()
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("queue_mutations", false).put("account_mutations", false).put("cases", cases)
        var original: String? = null
        var failure: Throwable? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            ui.launch()
            openSettings(ui)
            original = selectedStyle(ui)
            report.put("original_style", original)
            val pid = pid(ui)
            val session = media(instrumentation)
            switchStyle(instrumentation, ui, "apple", pid, session?.token, cases, directory)
            verifyBackToExit(ui, "apple", cases, directory)
            openSettings(ui)
            switchStyle(instrumentation, ui, "classic", pid, session?.token, cases, directory)
            verifyBackToExit(ui, "classic", cases, directory)
            verifyClassicCollection(ui, cases, directory)
            openSettings(ui)
            switchStyle(instrumentation, ui, "apple", pid, session?.token, cases, directory)
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            val restoreStyle = original
            if (restoreStyle != null) runCatching {
                openSettings(ui)
                if (selectedStyle(ui) != restoreStyle) {
                    activateStyle(instrumentation, ui, restoreStyle)
                    awaitSelectedFocus(ui, restoreStyle)
                }
                check(selectedStyle(ui) == restoreStyle) { "Original interface was not restored" }
                report.put("restored_style", restoreStyle)
            }.onFailure { error ->
                report.put("restore_error", error.message?.take(500))
                if (failure == null) failure = error
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if (failure != null) report.put("status", "failed")
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun openSettings(ui: RenderingSurveyUi) {
        if (ui.has("app-exit-dialog")) {
            ui.key(KeyEvent.KEYCODE_BACK, 0)
            ui.awaitGone("app-exit-dialog")
        }
        ui.leavePlayerWithBack()
        navigate(ui, "account")
        if (ui.snapshot().focused.any { it.startsWith("nav-") }) {
            // Apple enters from the sidebar/pill with Right; Classic enters
            // from its top bar with Down. Down on Apple's pill reopens it.
            ui.key(if (ui.has("classic-navigation")) KeyEvent.KEYCODE_DPAD_DOWN
                else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        ui.await("Settings content did not take remote focus", 4_000) {
            val state = ui.snapshot()
            "browse-account" in state.ids && state.focused.isNotEmpty() &&
                state.focused.none { it.startsWith("nav-") || it == "classic-navigation" }
        }
        repeat(32) {
            if (ui.has(APPLE) && ui.has(CLASSIC)) return
            // Down only changes focus/scroll; it cannot activate a setting.
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100)
        }
        error("Interface controls were not reached within bounded Settings scroll")
    }

    private fun navigate(ui: RenderingSurveyUi, tab: String) {
        if (!ui.has("classic-navigation")) ui.openNavigation()
        ui.focus("nav-$tab")
        ui.activate("nav-$tab", "navigation")
        ui.awaitId("browse-$tab", 8_000)
        if (!ui.has("classic-navigation")) ui.awaitGone("nav-panel")
    }

    private fun switchStyle(instrumentation: Instrumentation, ui: RenderingSurveyUi, style: String,
        expectedPid: String, expectedToken: MediaSession.Token?, cases: JSONArray, directory: File) {
        val before = media(instrumentation)
        activateStyle(instrumentation, ui, style)
        awaitSelectedFocus(ui, style)
        check(pid(ui) == expectedPid) { "Switching interface restarted the app process" }
        val after = media(instrumentation)
        check(after?.token == expectedToken) { "Switching interface replaced the active media session" }
        val checkMetadata = before?.stableSong == true
        if (checkMetadata) check(after?.identity == before?.identity) { "Switching interface changed the existing song" }
        check(ui.has("classic-navigation") == (style == "classic")) { "Selected style does not match actual navigation" }
        check(style == "classic" || ui.has("nav-menu")) { "Apple navigation did not return" }
        check(ui.has("browse-account")) { "Style switch left Settings" }
        val screenshot = "settings-$style-${cases.length() + 1}.png"
        check(ui.device.takeScreenshot(File(directory, screenshot))) { "Settings visual proof was not saved" }
        cases.put(JSONObject().put("case", "switch-$style").put("pid", expectedPid)
            .put("session_present", after != null).put("song_identity_checked", checkMetadata)
            .put("metadata_before", before?.identity).put("metadata_after", after?.identity)
            .put("playback_state_before", before?.state ?: JSONObject.NULL)
            .put("playback_state_after", after?.state ?: JSONObject.NULL)
            .put("position_ms_before", before?.positionMs ?: JSONObject.NULL)
            .put("position_ms_after", after?.positionMs ?: JSONObject.NULL)
            .put("playing_at_both_samples", before?.state == PlaybackState.STATE_PLAYING && after?.state == PlaybackState.STATE_PLAYING)
            .put("screenshot", screenshot)
            .put("after", ui.snapshot().json()))
    }

    private fun activateStyle(instrumentation: Instrumentation, ui: RenderingSurveyUi, style: String) {
        val id = styleId(style)
        ui.focus(id)
        check(ui.snapshot().nodes.any { it.id == id && it.clickable }) { "Style option is not an actual clickable control" }
        check(id in ui.snapshot().focused) { "Style option does not own Center" }
        val start = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(start, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DPAD_CENTER,
            0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        check(instrumentation.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_DOWN), true))
        check(instrumentation.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_UP), true))
    }

    private fun awaitSelectedFocus(ui: RenderingSurveyUi, style: String) {
        val id = styleId(style)
        var stableSince = 0L
        ui.await("Selected interface option lost focus after switching", 4_000) {
            val state = ui.snapshot()
            if (id in state.selected && id in state.focused && "browse-account" in state.ids) {
                if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                // Covers the sidebar pill's old 200ms delayed focus takeover.
                SystemClock.uptimeMillis() - stableSince >= 400L
            } else { stableSince = 0L; false }
        }
    }

    private fun verifyBackToExit(ui: RenderingSurveyUi, style: String, cases: JSONArray, directory: File) {
        val source = if (ui.has("classic-navigation")) {
            if (ui.has("nav-radio")) "radio" else "home"
        } else {
            ui.openNavigation()
            if (ui.has("nav-radio")) "radio" else "home"
        }
        navigate(ui, source)
        val steps = JSONArray()
        var sawHome = source == "home"
        var homeCaptured = false
        repeat(8) { index ->
            ui.key(KeyEvent.KEYCODE_BACK, 350)
            val state = ui.snapshot()
            sawHome = sawHome || "browse-home" in state.ids
            if (!homeCaptured && "browse-home" in state.ids) {
                check(ui.device.takeScreenshot(File(directory, "home-$style.png"))) { "Home visual proof was not saved" }
                homeCaptured = true
            }
            steps.put(JSONObject().put("press", index + 1).put("state", state.json()))
            if ("app-exit-dialog" in state.ids) {
                check(sawHome) { "Exit prompt opened before returning to Home" }
                ui.await("Exit prompt must default to Stay", 2_000) { "app-exit-stay" in ui.snapshot().focused }
                ui.key(KeyEvent.KEYCODE_BACK, 0)
                ui.awaitGone("app-exit-dialog")
                cases.put(JSONObject().put("case", "$style-$source-back").put("steps", steps)
                    .put("home_screenshot", if (homeCaptured) "home-$style.png" else JSONObject.NULL))
                return
            }
        }
        error("$style Back did not exhaust $source to the exit prompt")
    }

    private fun verifyClassicCollection(ui: RenderingSurveyUi, cases: JSONArray, directory: File) {
        check(ui.has("classic-navigation") && ui.has("browse-home"))
        if (ui.snapshot().focused.any { it.startsWith("nav-") }) ui.key(KeyEvent.KEYCODE_DPAD_DOWN)
        val collectionCard = Regex("shelf-\\d+-item-\\d+-(album|playlist)")
        try {
            ui.await("No existing visible collection card", 4_000) { ui.hasPattern(collectionCard) }
        } catch (_: SurveyUnavailable) {
            cases.put(JSONObject().put("case", "classic-collection").put("status", "skipped")
                .put("reason", "Home has no existing visible album or playlist card"))
            return
        }
        val source = ui.snapshot().ids.first(collectionCard::matches)
        ui.focus(source)
        ui.activate(source, "collection")
        ui.awaitId("collection-screen", 8_000)
        ui.awaitId("collection-actions", 4_000)
        val state = ui.snapshot()
        fun bounds(id: String): Rect = requireNotNull(Rect.unflattenFromString(
            state.nodes.first { it.id == id }.bounds))
        val screen = bounds("collection-screen")
        val artwork = bounds("collection-artwork")
        val metadata = bounds("collection-metadata")
        val actions = bounds("collection-actions")
        val songs = bounds("collection-content")
        check(listOf(artwork, metadata, actions, songs).all { !it.isEmpty && screen.contains(it) }) {
            "Classic collection artwork, metadata, actions or song list is clipped"
        }
        check(metadata.right <= songs.left && actions.right <= songs.left && artwork.bottom <= metadata.top + 2) {
            "Classic collection must keep artwork/metadata/actions on the left and songs on the right"
        }
        val loadedSongs = state.nodes.filter { it.id.matches(Regex("collection-song-\\d+")) }
        if (loadedSongs.isNotEmpty()) {
            val rows = loadedSongs.map { requireNotNull(Rect.unflattenFromString(it.bounds)) }
            // A lazy viewport may legitimately clip its first/last row vertically.
            check(rows.any { !it.isEmpty && songs.contains(it) } &&
                rows.all { it.left >= songs.left && it.right <= songs.right }) { "Loaded song list has no complete row or overflows horizontally" }
        }
        check(ui.device.takeScreenshot(File(directory, "classic-collection.png")))
        ui.key(KeyEvent.KEYCODE_BACK, 0)
        ui.awaitGone("collection-screen")
        ui.await("Collection Back did not restore its actual Home source", 4_000) {
            !ui.has("app-exit-dialog") && source in ui.snapshot().focused
        }
        cases.put(JSONObject().put("case", "classic-collection").put("status", "passed")
            .put("source", source).put("song_row_observed", loadedSongs.isNotEmpty())
            .put("screenshot", "classic-collection.png").put("layout", state.json(true)))
    }

    private fun selectedStyle(ui: RenderingSurveyUi): String {
        val selected = ui.snapshot().selected.filter { it == APPLE || it == CLASSIC }
        check(selected.size == 1) { "Interface selection is not unique: $selected" }
        return if (selected.single() == APPLE) "apple" else "classic"
    }

    private fun styleId(style: String): String = when (style) {
        "apple" -> APPLE
        "classic" -> CLASSIC
        else -> error("Unknown interface style $style")
    }

    private fun pid(ui: RenderingSurveyUi): String = ui.device.executeShellCommand("pidof dev.amatv").trim()
        .also { check(it.matches(Regex("[0-9]+"))) { "Target has no unique process" } }

    private data class Media(val token: MediaSession.Token, val identity: String, val stableSong: Boolean,
        val state: Int?, val positionMs: Long)

    private fun media(instrumentation: Instrumentation): Media? {
        val manager = instrumentation.context.getSystemService(MediaSessionManager::class.java)
        val controllers = manager.getActiveSessions(null).filter { it.packageName == "dev.amatv" }
        check(controllers.size <= 1) { "Multiple target media sessions are active" }
        val controller = controllers.singleOrNull() ?: return null
        val metadata = controller.metadata
        val state = controller.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = (state?.position ?: 0L) + if (state?.state == PlaybackState.STATE_PLAYING)
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0L
        // Radio metadata may change naturally; final song seconds can auto-advance.
        val stableSong = duration > 0 && (state?.state != PlaybackState.STATE_PLAYING || duration - position > 5_000)
        val identity = listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM)
            .joinToString("|") { metadata?.getString(it).orEmpty() }
        return Media(controller.sessionToken, identity, stableSong, state?.state, position)
    }

    private companion object {
        const val APPLE = "settings-interface-apple"
        const val CLASSIC = "settings-interface-classic"
    }
}
