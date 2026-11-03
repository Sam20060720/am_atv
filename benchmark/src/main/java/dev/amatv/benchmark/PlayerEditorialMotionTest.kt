package dev.amatv.benchmark

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

/** One explicit real-track activation, with host-owned exact AVD recovery. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class PlayerEditorialMotionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi
    private var appPid = ""

    @Test fun canonicalAlbumPlaybackBlendsMotionAndReturnsToItsPlayControl() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit playback/motion experiment only", args.getString("allow_player_motion") == "1")
        check(args.getString("avd_recovery_ready") == "1") { "Host must finish an exact AVD recovery clone first" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Player motion experiment is emulator-only" }
        val runId = args.getString("run_id") ?: "player-motion-${System.currentTimeMillis()}"
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        val directory = File(checkNotNull(instrumentation.context.getExternalFilesDir(null)), "player-editorial-motion/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Never overwrite prior evidence" }
        val report = JSONObject().put("schema_version", 1).put("run_id", runId).put("package", TARGET)
            .put("album_id", ALBUM_ID).put("start_wall_ms", System.currentTimeMillis())
            .put("account_mutations", false).put("queue_restore", "Exact host AVD restoration required; no UI queue reconstruction")
        ui = RenderingSurveyUi(instrumentation)
        var failure: Throwable? = null
        try {
            ui.launch()
            ui.search("albums", "Positions Ariana Grande")
            appPid = currentPid()
            val selected = openCanonicalAlbum()
            report.put("app_pid", appPid).put("selection", selected)
            val albumOpeningWall = selected.getLong("open_wall_ms")
            ui.focus("collection-play")
            check(ui.snapshot().focused.contains("collection-play"))
            // Settle the known album lease before baseline. An unfinished browse
            // lease must never stand in for the now-playing cover's own frame.
            val beforeAlbum = SystemClock.uptimeMillis()
            ui.await("Canonical album must complete its own real first-frame blend before playback baseline", 20_000L) {
                val events = motionEvents()
                events.any { first -> first.kind == "frame" && first.wallMs >= albumOpeningWall && events.any { blend ->
                    blend.kind == "blend" && blend.mediaId == first.mediaId && blend.wallMs - first.wallMs >= 300L
                } }
            }
            report.put("album_preview_ready_wait_ms", SystemClock.uptimeMillis() - beforeAlbum)
            val baselineIds = motionEvents().map { it.mediaId }.toSet()
            val openingWall = System.currentTimeMillis()
            val openingUptime = SystemClock.uptimeMillis()
            report.put("player_open_wall_ms", openingWall).put("baseline_media_ids", JSONArray(baselineIds))
            clickCanonicalPlay()
            ui.awaitId("player-screen", 12_000L)
            ui.await("Browse collection must be hidden beneath Now Playing", 5_000L) { !ui.has("collection-screen") }
            report.put("player_arrival_observed_wall_ms", System.currentTimeMillis())
            ui.await("Real album playback did not enter Playing", 35_000L) {
                ui.requireForeground()
                check(currentPid() == appPid) { "App restarted during player experiment" }
                state() == "playing"
            }
            report.put("actual_playing", true).put("player_artwork_label", ui.label("player-artwork"))
            var frame: MotionEvent? = null
            var blend: MotionEvent? = null
            val deadline = openingUptime + 40_000L
            while (SystemClock.uptimeMillis() < deadline && blend == null) {
                ui.requireForeground()
                check(ui.has("player-screen") && !ui.has("collection-screen")) { "Player must own the visible cover" }
                check(currentPid() == appPid)
                val events = motionEvents()
                frame = events.firstOrNull { it.kind == "frame" && it.mediaId !in baselineIds && it.wallMs >= openingWall }
                val first = frame
                if (first != null) blend = events.firstOrNull {
                    it.kind == "blend" && it.mediaId == first.mediaId && it.wallMs - first.wallMs >= 300L
                }
                if (blend == null) ui.settle(100L)
            }
            val rendered = checkNotNull(frame) { "No actual new player lease first frame after Play" }
            val blended = checkNotNull(blend) { "Player lease did not finish a real >=300ms poster-to-video blend" }
            report.put("first_frame", rendered.json()).put("blend_finished", blended.json())
                .put("first_frame_after_play_ms", rendered.wallMs - openingWall)
                .put("blend_duration_ms", blended.wallMs - rendered.wallMs)
            check(ui.device.takeScreenshot(File(directory, "player-motion.png")))
            ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ui.await("Actual playback must pause before leaving the player", 8_000L) { state() == "paused" }
            report.put("actual_paused", true)
            val backLayers = JSONArray()
            repeat(5) {
                if (ui.has("player-screen")) {
                    backLayers.put(ui.snapshot().json())
                    ui.key(KeyEvent.KEYCODE_BACK, 220L)
                }
            }
            ui.awaitGone("player-screen")
            ui.awaitId(PROFILE_ID, 8_000L)
            ui.await("Back must restore the exact Play control without corrective focus", 5_000L) {
                val snapshot = ui.snapshot()
                "collection-play" in snapshot.focused && "nav-panel" !in snapshot.ids
            }
            ui.settle(800L)
            val restored = ui.snapshot()
            check("collection-play" in restored.focused && "nav-panel" !in restored.ids) {
                "Restored collection focus drifted back into the navigation capsule"
            }
            report.put("back_layers", backLayers).put("restored_focus", restored.json())
                .put("corrective_focus_after_back", false).put("status", "passed_player_frame_blend_pause_and_natural_collection_return")
            check(ui.device.takeScreenshot(File(directory, "returned-collection.png")))
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            try {
                // No queue reconstruction or resume. The host restores the exact
                // pre-experiment AVD after collecting these bounded artifacts.
                ui.requireForeground()
                ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
                if (ui.has("player-screen")) {
                    ui.await("Failure cleanup must pause actual player", 8_000L) { state() == "paused" }
                    report.put("cleanup_actual_paused", true)
                }
            } catch (cleanup: Throwable) {
                report.put("cleanup_error", cleanup.message?.take(500))
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
            report.put("end_wall_ms", System.currentTimeMillis())
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun openCanonicalAlbum(): JSONObject {
        repeat(12) { index ->
            val resultId = "search-result-$index"
            repeat(3) { if (!ui.has(resultId)) ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 160L) }
            if (!ui.has(resultId)) return@repeat
            val label = ui.label(resultId)
            if (!label.contains(ALBUM_TITLE, true) || !label.contains("Ariana Grande", true)) return@repeat
            ui.focus("search-category-albums")
            val openedAt = System.currentTimeMillis()
            ui.verifiedActivation(resultId, "editorial_album").use { check(it.execute()) }
            ui.awaitId("collection-screen", 8_000L)
            ui.await("Canonical collection profile identity missing", 8_000L) {
                ui.snapshot().ids.any { it.startsWith("collection-profile-") }
            }
            if (ui.has(PROFILE_ID)) {
                check(ui.label("collection-title").equals(ALBUM_TITLE, true))
                return JSONObject().put("result_id", resultId).put("public_label", label).put("profile_id", PROFILE_ID).put("open_wall_ms", openedAt)
            }
            ui.key(KeyEvent.KEYCODE_BACK, 0L)
            ui.awaitGone("collection-screen")
        }
        error("Canonical album $ALBUM_ID not found among twelve validated album results")
    }

    private fun clickCanonicalPlay() {
        check(ui.has(PROFILE_ID) && ui.label("collection-title").equals(ALBUM_TITLE, true))
        check("collection-play" in ui.snapshot().focused)
        withNode("collection-play") { node ->
            check(node.isEnabled && node.isClickable && node.isVisibleToUser)
            check(node.className?.toString() in setOf("android.view.View", "android.widget.Button"))
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Canonical collection Play rejected activation" }
        }
    }

    private fun state(): String = withNode("player-screen") { node ->
        val value = node.stateDescription?.toString().orEmpty()
        listOf("playing", "paused", "preparing").firstOrNull { value in ui.stringLabels("player_state_$it") } ?: "unknown"
    }

    private fun <T> withNode(id: String, block: (AccessibilityNodeInfo) -> T): T {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.isVisibleToUser && node.viewIdResourceName?.substringAfter(":id/") == id)
                return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { find(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val node = try { check(root.packageName?.toString() == TARGET); find(root) } finally { root.recycle() }
            ?: error("Missing current app node: $id")
        return try { block(node) } finally { node.recycle() }
    }

    private fun currentPid() = ui.device.executeShellCommand("pidof $TARGET").trim().also {
        check(it.matches(Regex("[0-9]+"))) { "Expected one target process" }
    }

    private data class MotionEvent(val kind: String, val wallMs: Long, val mediaId: String, val line: String) {
        fun json() = JSONObject().put("kind", kind).put("event_wall_ms", wallMs).put("media_id", mediaId).put("log", line)
    }
    private fun motionEvents(): List<MotionEvent> = ui.device.executeShellCommand(
        "logcat -d -v epoch --pid=$appPid -t 200 -s AMATV-Motion:I '*:S'",
    ).lineSequence().mapNotNull { line ->
        val match = EVENT.matchEntire(line) ?: return@mapNotNull null
        MotionEvent(if (match.groupValues[2] == "First editorial frame") "frame" else "blend",
            (match.groupValues[1].toDouble() * 1_000).toLong(), match.groupValues[3], line.trim())
    }.toList()

    private companion object {
        const val ALBUM_ID = "1553944254"
        const val ALBUM_TITLE = "Positions (Deluxe)"
        const val PROFILE_ID = "collection-profile-albums-1553944254"
        val EVENT = Regex("\\s*([0-9]+\\.[0-9]+)\\s+[0-9]+\\s+[0-9]+\\s+I\\s+AMATV-Motion\\s*:\\s*(First editorial frame|Editorial blend finished) mediaId=(amatv-motion:[0-9]+)\\s*")
    }
}
