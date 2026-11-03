package dev.amatv.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Host/account UI only. Never plays a result or activates any provider mutation. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ReadOnlyParityJourneyTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val activationProofs = JSONArray()

    @Test fun realSongShowAlbumBackReturnsToTheExactSearchSource() = run("song-album") { ui, report ->
        val source = prepareSong(ui, report)
        ui.inject(KeyEvent.KEYCODE_MENU)
        activateAction(ui, "album")
        ui.awaitId("collection-screen", 10_000L)
        ui.await("Show Album did not render a real native album identity", 10_000L) {
            ui.hasPattern(Regex("collection-profile-(library-)?albums-.+"))
        }
        val title = ui.label("collection-title")
        check(title.isNotBlank())
        // The source is covered now; use the public source texts recorded before opening.
        val sourceAlbum = report.optJSONArray("source_text")?.let { rows ->
            (0 until rows.length()).map(rows::getString).firstOrNull { it.contains("positions", true) }
        }
        if (sourceAlbum != null) check(title.equals(sourceAlbum, true)) { "Song relationship opened a different album" }
        report.put("actual_album_title", title).put("native_album", ui.snapshot().json(true))
        ui.inject(KeyEvent.KEYCODE_BACK)
        awaitSource(ui, source)
        report.put("back_source", ui.snapshot().json(true))
    }

    @Test fun realSongShareAndCopyLinkKeepTheSourceAndCanonicalUrl() = run("song-share-copy") { ui, report ->
        val source = prepareSong(ui, report)
        ui.inject(KeyEvent.KEYCODE_MENU)
        activateAction(ui, "share")
        var chooser = false
        ui.await("Share opened neither its QR dialog nor the actual system chooser", 5_000L) {
            ui.has("apple-music-share-dialog") || isSystemChooser(ui).also { chooser = it }
        }
        if (chooser) {
            report.put("share_surface", "system_chooser").put("share_target_activated", false)
            ui.inject(KeyEvent.KEYCODE_BACK) // Proven chooser only; never choose an external target.
            ui.await("Chooser Back did not return to AMATV", 5_000L) { ui.foreground() == TARGET }
        } else {
            ui.awaitId("apple-music-share-qr", 5_000L)
            val url = ui.label("apple-music-share-qr")
            checkCanonical(url)
            report.put("share_surface", "phone_qr").put("shared_url", url)
            ui.inject(KeyEvent.KEYCODE_BACK)
            ui.await("Share QR did not close", 3_000L) { !ui.has("apple-music-share-dialog") }
        }
        if (ui.has("catalog-actions-list")) {
            ui.inject(KeyEvent.KEYCODE_BACK)
            ui.await("Share's parent menu did not close", 3_000L) { !ui.has("catalog-actions-list") }
        }
        awaitSource(ui, source)
        report.put("share_status", "passed_actual_host").put("after_share", ui.snapshot().json(true))

        // Previous clipboard data stays only in memory. URI/Intent/HTML clips
        // are excluded because regranting their permissions would not be a safe restore.
        var previous: ClipData? = null
        try { withForegroundClipboard(ui) { previous = it.primaryClip } }
        catch (_: Exception) {
            report.put("copy_status", "skipped_foreground_clipboard_unreadable")
            assumeTrue("Share passed; foreground clipboard could not be read safely", false)
        }
        val original = previous
        val safe = original == null || original.itemCount == 1 && original.description.mimeTypeCount == 1 &&
            original.description.getMimeType(0) == "text/plain" && original.getItemAt(0).text != null &&
            original.getItemAt(0).uri == null && original.getItemAt(0).intent == null && original.getItemAt(0).htmlText == null
        if (!safe) {
            report.put("copy_status", "skipped_previous_clipboard_requires_uri_or_nontext_permissions")
            assumeTrue("Share passed; original clipboard is not safely restorable plain text", false)
        }
        var copyDispatched = false
        try {
            ui.inject(KeyEvent.KEYCODE_MENU)
            copyDispatched = true
            activateAction(ui, "copy-link")
            ui.await("Copy Link did not dismiss its action menu", 3_000L) { !ui.has("catalog-actions-list") }
            awaitSource(ui, source)
            var copied: String? = null
            try { withForegroundClipboard(ui) { clipboard -> copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString() } }
            catch (_: Exception) { }
            if (copied == null) {
                report.put("copy_status", "skipped_clipboard_readback_unavailable")
                assumeTrue("Copy was dispatched but canonical clipboard readback was unavailable", false)
            }
            checkCanonical(checkNotNull(copied))
            report.optString("shared_url").takeIf(String::isNotBlank)?.let { check(copied == it) }
            report.put("copied_url", copied).put("copy_status", "passed_foreground_clipboard_readback")
        } finally {
            if (copyDispatched) withForegroundClipboard(ui) { clipboard ->
                if (original == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(original)
                val restored = clipboard.primaryClip
                check(if (original == null) restored == null else restored?.getItemAt(0)?.text?.toString() ==
                    original.getItemAt(0).text.toString()) { "Original plain-text clipboard did not restore" }
                report.put("previous_clipboard_restored", true)
            }
        }
        awaitSource(ui, source)
    }

    @Test fun existingPausedQueueDetailsAndNestedAlbumReturnToTheSameOccurrence() = run("queue-details") { ui, report ->
        try { ui.pausedPlayer(queue = true) }
        catch (missing: SurveyUnavailable) { assumeTrue("No existing paused Queue setup: ${missing.message}", false) }
        check(ui.playerPaused())
        val selected = ui.snapshot().selected.singleOrNull { it.matches(Regex("player-queue-item-\\d+")) }
        assumeTrue("Existing Queue has no real selected occurrence", selected != null)
        val source = checkNotNull(selected)
        val title = textAt(source).firstOrNull()
        assumeTrue("Selected occurrence has no public track title", !title.isNullOrBlank())
        val label = ui.label(source)
        report.put("queue_source", source).put("queue_source_label", label).put("before", ui.snapshot().json(true))
        ui.inject(KeyEvent.KEYCODE_MENU)
        activateAction(ui, "details")
        ui.awaitId("catalog-resource-screen", 10_000L)
        ui.await("Queue Details did not render that actual track's metadata", 10_000L) {
            textAt("catalog-resource-screen").any { it.equals(title, true) } && ui.snapshot().ids.any {
                it.startsWith("catalog-reading-") || it.startsWith("resource-section-")
            }
        }
        report.put("actual_metadata", ui.snapshot().json(true))
        val albumPattern = Regex("resource-albums:false:-\\d+-album")
        var album: String? = ui.snapshot().ids.firstOrNull(albumPattern::matches)
        val scan = JSONArray()
        repeat(12) { step ->
            if (album == null) {
                ui.inject(KeyEvent.KEYCODE_DPAD_DOWN)
                ui.settle(130L)
                val observed = ui.snapshot()
                album = observed.ids.firstOrNull(albumPattern::matches)
                scan.put(JSONObject().put("down_step", step + 1).put("snapshot", observed.json()))
            }
        }
        if (album == null && ui.has("resource-section-albums:false:")) {
            ui.await("Visible real album relationship did not settle", 3_000L) {
                ui.hasPattern(albumPattern) || !ui.has("resource-section-albums:false:")
            }
            album = ui.snapshot().ids.firstOrNull(albumPattern::matches)
        }
        report.put("metadata_album_scan", scan).put("after_album_scan", ui.snapshot().json(true))
        if (album != null) {
            val child = checkNotNull(album)
            ui.focus(child)
            check(child in ui.snapshot().focused && albumPattern.matches(child))
            check(ui.device.pressDPadCenter()) // Proven metadata album, never the song or Play.
            ui.awaitId("collection-screen", 8_000L)
            report.put("nested_album", ui.label("collection-title"))
            ui.inject(KeyEvent.KEYCODE_BACK)
            ui.awaitId("catalog-resource-screen", 5_000L)
            check(!ui.has("player-screen")) { "Child Back skipped the marked metadata root" }
            report.put("nested_back_status", "passed_actual_child_then_metadata")
        } else report.put("nested_back_status", "skipped_no_album_card_after_loaded_metadata_bounded_scan")
        ui.inject(KeyEvent.KEYCODE_BACK)
        ui.await("Metadata root Back did not return to the retained paused player Queue", 6_000L) {
            ui.has("player-screen") && ui.has("queue-panel") && ui.playerPaused()
        }
        ui.await("Metadata Back did not restore the same visible Queue occurrence", 3_000L) {
            source in ui.snapshot().selected && ui.label(source) == label
        }
        check(ui.playerPaused())
        report.put("after", ui.snapshot().json(true)).put("occurrence_proof", "same exposed queue index and public label; no queue edits")
    }

    private fun prepareSong(ui: RenderingSurveyUi, report: JSONObject): String {
        try {
            if (!ui.has("catalog-search") || !ui.has("search-result-list") ||
                !ui.label("search-field").contains("shut up Ariana Grande", true)) ui.search("songs", "shut up Ariana Grande")
            ui.await("Actual canonical song search returned no visible results", 10_000L) { ui.has("search-result-0") }
            val source = ui.snapshot().ids.filter { it.matches(Regex("search-result-\\d+")) }
                .sortedBy { it.substringAfterLast('-').toInt() }.firstOrNull { id ->
                    val text = textAt(id)
                    text.any { it.equals("shut up", true) } && text.any { it.equals("Ariana Grande", true) }
                } ?: throw SurveyUnavailable("No exact public shut up / Ariana Grande result is visible")
            ui.focus(source)
            report.put("source", source).put("source_label", ui.label(source)).put("source_text", JSONArray(textAt(source)))
            return source
        } catch (missing: SurveyUnavailable) {
            assumeTrue("Required real Song setup unavailable: ${missing.message}", false)
            error("Unreachable")
        }
    }

    private fun activateAction(ui: RenderingSurveyUi, action: String) {
        require(action in setOf("album", "share", "copy-link", "details"))
        val id = "catalog-action-$action"
        ui.awaitId("catalog-actions-list", 4_000L)
        ui.await("Menu did not acquire an actual row owner", 3_000L) {
            ui.snapshot().focused.any { it.startsWith("catalog-action-") }
        }
        repeat(22) {
            val state = ui.snapshot()
            val focused = state.focused.singleOrNull { it.startsWith("catalog-action-") }
                ?: error("Menu lost its actual row owner before activation")
            if (focused == id) {
                // This is an ordinary settled action test, not the separate
                // rapid-key regression. Never replace a still-scrolling remote
                // destination with ACTION_FOCUS and race its queued handoff.
                var bounds: String? = null
                var stableSince = SystemClock.uptimeMillis()
                ui.await("Intended read-only row did not keep settled focus before Center", 1_000L) {
                    val live = ui.snapshot()
                    check(id in live.focused) { "Read-only target drifted before Center: ${live.focused}" }
                    val current = live.nodes.firstOrNull { it.resolvedId == id && it.focused }?.bounds
                    if (current != bounds) { bounds = current; stableSince = SystemClock.uptimeMillis() }
                    current != null && SystemClock.uptimeMillis() - stableSince >= 180L
                }
                val proof = JSONObject().put("requested_action", id).put("before_down", ui.snapshot().json(true))
                activationProofs.put(proof)
                val down = SystemClock.uptimeMillis()
                fun event(kind: Int) = KeyEvent(down, SystemClock.uptimeMillis(), kind, KeyEvent.KEYCODE_DPAD_CENTER,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
                check(inst.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_DOWN), true))
                try {
                    val held = ui.snapshot()
                    proof.put("after_down", held.json(true))
                    check(id in held.focused) { "Center Down was not retained by the intended read-only row" }
                } finally {
                    check(inst.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_UP), true))
                }
                proof.put("after_up", ui.snapshot().json(true))
                return
            }
            val targetBounds = state.nodes.firstOrNull { it.id == id }?.bounds?.let(Rect::unflattenFromString)
            val currentBounds = state.nodes.firstOrNull { it.resolvedId == focused && it.focused }
                ?.bounds?.let(Rect::unflattenFromString)
            val direction = if (targetBounds != null && currentBounds != null && targetBounds.centerY() < currentBounds.centerY())
                KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            ui.inject(direction)
            ui.await("Remote menu step did not leave $focused", 1_000L) {
                ui.snapshot().focused.any { it.startsWith("catalog-action-") && it != focused }
            }
        }
        error("Required read-only action is not reachable: $id")
    }

    private fun awaitSource(ui: RenderingSurveyUi, source: String) {
        ui.await("Host did not return to the same actual Search source", 5_000L) {
            ui.foreground() == TARGET && ui.has("catalog-search") && source in ui.snapshot().focused && !ui.has("nav-panel")
        }
        ui.settle(450L)
        check(source in ui.snapshot().focused && !ui.has("nav-panel"))
    }

    private fun checkCanonical(url: String) {
        val uri = URI(url)
        check(uri.scheme == "https" && uri.rawAuthority == "music.apple.com" && uri.rawUserInfo == null &&
            uri.port == -1 && uri.rawFragment == null && uri.path.isNotBlank()) { "Clipboard/share did not expose a canonical Apple Music URL" }
    }

    private fun isSystemChooser(ui: RenderingSurveyUi): Boolean {
        if (ui.foreground() == TARGET) return false
        return ui.device.executeShellCommand("dumpsys activity activities").lineSequence().any {
            (it.contains("topResumedActivity") || it.contains("mResumedActivity")) && it.contains("ChooserActivity")
        }
    }

    private fun <T> withForegroundClipboard(ui: RenderingSurveyUi, action: (ClipboardManager) -> T): T? {
        var result: T? = null
        ClipboardCaptureActivity.completed = CountDownLatch(1)
        ClipboardCaptureActivity.failure = null
        ClipboardCaptureActivity.operation = { result = action(it) }
        inst.context.startActivity(Intent(inst.context, ClipboardCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("allow_readonly_parity", true))
        check(ClipboardCaptureActivity.completed.await(3, TimeUnit.SECONDS)) { "Clipboard tester did not own a foreground window" }
        ClipboardCaptureActivity.failure?.let { throw it }
        ui.await("Foreground clipboard tester did not finish back to AMATV", 3_000L) { ui.foreground() == TARGET }
        return result
    }

    private fun textAt(id: String): List<String> {
        val root = inst.uiAutomation.rootInActiveWindow ?: return emptyList()
        fun collect(node: AccessibilityNodeInfo, inside: Boolean, out: MutableList<String>) {
            try {
                if (!node.refresh() || node.packageName?.toString() != TARGET || !node.isVisibleToUser) return
                val here = inside || node.viewIdResourceName.orEmpty().substringAfter(":id/") == id
                if (here) node.text?.toString()?.lineSequence()?.map(String::trim)
                    ?.filter(String::isNotBlank)?.forEach(out::add)
                for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, here, out) }
            } finally { node.recycle() }
        }
        return mutableListOf<String>().also { collect(root, false, it) }.distinct()
    }

    private fun run(name: String, body: (RenderingSurveyUi, JSONObject) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit read-only actual Host journey", args.getString("allow_readonly_parity") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "readonly-parity/$run-$name")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val started = SystemClock.uptimeMillis()
        val report = JSONObject().put("case", name).put("provider_writes", false).put("playback_actions", 0)
            .put("activation_proofs", activationProofs)
        try {
            ui.launch()
            body(ui, report)
            report.put("status", "passed_actual_host")
        } catch (skip: AssumptionViolatedException) {
            report.put("status", "skipped_or_partial").put("reason", skip.message)
            throw skip
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.stackTraceToString().take(8_000))
            runCatching { report.put("failure_snapshot", ui.snapshot().json(true)) }
            runCatching { ui.device.takeScreenshot(File(out, "failure.png")) }
            throw error
        } finally {
            report.put("elapsed_ms", SystemClock.uptimeMillis() - started)
            File(out, "result.json").writeText(report.toString(2))
            ui.close()
        }
    }
}
