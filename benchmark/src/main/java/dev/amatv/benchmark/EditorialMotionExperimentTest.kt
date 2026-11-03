package dev.amatv.benchmark

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One real decorative-video first-frame experiment; never activates an audio item. */
@RunWith(AndroidJUnit4::class)
class EditorialMotionExperimentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi
    private lateinit var folder: File
    private lateinit var report: JSONObject
    private var appPid = ""
    private var openingWallMs = 0L
    private var openingUptimeMs = 0L
    private var baselineMediaIds = emptySet<String>()

    @Test fun positionsDeluxeRendersARealEditorialVideoFrame() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit decorative-video experiment only", args.getString("allow_editorial_motion") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Decorative-video experiment is emulator-only" }
        val runId = args.getString("run_id") ?: "motion-" + System.currentTimeMillis()
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        folder = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "editorial-motion/$runId")
        check(!folder.exists() && folder.mkdirs()) { "Do not overwrite an earlier motion experiment" }
        ui = RenderingSurveyUi(instrumentation)
        report = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("package", TARGET).put("album_id", ALBUM_ID).put("album_title", ALBUM_TITLE)
            .put("start_wall_ms", System.currentTimeMillis())
            .put("playback_operations", false).put("account_mutations", false)
            .put("verification", "Public album identity and matching-current-lease Media3 onRenderedFirstFrame; not a GPU benchmark")
        var failure: Throwable? = null
        try {
            ui.launch()
            ui.search("albums", "Positions Ariana Grande")
            appPid = currentPid()
            report.put("app_pid", appPid)
            val selected = openCanonicalAlbum(runId)
            report.put("selection", selected)
            check(ui.label("collection-title").equals(ALBUM_TITLE, ignoreCase = true)) { "Unexpected rendered album title" }
            ui.awaitId("collection-play", 4_000L)
            ui.awaitId("collection-artwork", 4_000L)
            // Focus only: prove the ordinary header controls remain usable while
            // optional artwork prepares. This never clicks More, Play or Shuffle.
            ui.focus("collection-more")
            check("collection-more" in ui.snapshot().focused) { "Album header did not accept focus" }
            report.put("header_ready", JSONObject().put("title", ui.label("collection-title"))
                .put("profile_id", PROFILE_ID).put("focused_control", "collection-more")
                .put("observed_after_open_ms", SystemClock.uptimeMillis() - openingUptimeMs))
            check(ui.device.takeScreenshot(File(folder, "album-ready.png")))

            val deadline = openingUptimeMs + 20_000L
            var rendered: FrameEvent? = null
            while (SystemClock.uptimeMillis() < deadline && rendered == null) {
                ui.requireForeground()
                check(currentPid() == appPid) { "App restarted during motion experiment" }
                check(ui.has(PROFILE_ID)) { "The proven album left the foreground before first-frame evidence" }
                rendered = readFrames().firstOrNull { it.mediaId !in baselineMediaIds && it.wallMs >= openingWallMs }
                if (rendered == null) ui.settle(200L)
            }
            check(rendered != null) { "No actual editorial first frame within 20 seconds of opening the canonical album" }
            val frame = requireNotNull(rendered)
            check(frame.wallMs - openingWallMs in 0L..20_000L) { "First-frame event is outside the bounded album-open window" }
            report.put("first_frame", JSONObject().put("media_id", frame.mediaId)
                .put("event_wall_ms", frame.wallMs).put("after_open_ms", frame.wallMs - openingWallMs)
                .put("log", frame.line).put("only_current_lease_logged", true))
            ui.settle(380L) // Let the explicit 320ms poster-to-video blend complete.
            check(ui.device.takeScreenshot(File(folder, "first-frame.png")))
            // Revisit the same real resource: reuses metadata and video bytes,
            // while requiring a new current decoder lease and actual frame.
            ui.key(KeyEvent.KEYCODE_BACK, 0L)
            ui.awaitGone("collection-screen")
            openCanonicalAlbum(runId + "-warm")
            val warmDeadline = openingUptimeMs + 20_000L
            var warmFrame: FrameEvent? = null
            while (SystemClock.uptimeMillis() < warmDeadline && warmFrame == null) {
                ui.requireForeground()
                check(currentPid() == appPid && ui.has(PROFILE_ID))
                warmFrame = readFrames().firstOrNull { it.mediaId !in baselineMediaIds && it.wallMs >= openingWallMs }
                if (warmFrame == null) ui.settle(100L)
            }
            val warm = checkNotNull(warmFrame) { "Cached revisit did not render an actual frame" }
            report.put("cached_revisit_first_frame", JSONObject().put("media_id", warm.mediaId)
                .put("after_open_ms", warm.wallMs - openingWallMs).put("log", warm.line))
            report.put("status", "passed_actual_editorial_first_frame_and_cached_revisit")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(folder, "failure.png")) }
        } finally {
            try {
                if (ui.has("collection-screen")) {
                    ui.key(KeyEvent.KEYCODE_BACK, 0L)
                    ui.awaitGone("collection-screen")
                }
                if (!ui.has("catalog-search")) ui.navigate("search")
                ui.awaitId("catalog-search", 8_000L)
                check(!ui.has("collection-screen") && !ui.has("player-screen"))
                report.put("cleanup", "returned_to_search_without_audio_actions")
            } catch (cleanup: Throwable) {
                report.put("cleanup", "failed").put("cleanup_error", cleanup.message?.take(500))
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
            report.put("end_wall_ms", System.currentTimeMillis())
            File(folder, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun openCanonicalAlbum(runId: String): JSONObject {
        val probes = JSONArray()
        report.put("candidate_probes", probes)
        for (index in 0 until 12) {
            val resultId = "search-result-$index"
            repeat(3) {
                if (!ui.has(resultId)) ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 180L)
            }
            if (!ui.has(resultId)) break
            val label = ui.label(resultId)
            if (!label.contains(ALBUM_TITLE, ignoreCase = true) || !label.contains("Ariana Grande", ignoreCase = true)) continue
            // Stop the result tile's optional preview before recording the
            // baseline. The subsequent click opens a container without ever
            // refocusing its tile, so an old search-frame cannot satisfy proof.
            ui.focus("search-category-albums")
            check(ui.has(resultId)) { "Proven album result left its viewport before activation" }
            check(ui.snapshot().focused.none { it.startsWith("search-result-") })
            baselineMediaIds = readFrames().map { it.mediaId }.toSet()
            openingWallMs = System.currentTimeMillis()
            openingUptimeMs = SystemClock.uptimeMillis()
            Log.i("AMATV-MotionExperiment", "Album-open run=$runId")
            ui.verifiedActivation(resultId, "editorial_album").use {
                check(it.execute()) { "Proven album search result did not open" }
            }
            ui.awaitId("collection-screen", 8_000L)
            ui.await("Album identity did not render", 8_000L) {
                ui.snapshot().ids.any { it.startsWith("collection-profile-") }
            }
            val identities = ui.snapshot().ids.filter { it.startsWith("collection-profile-") }
            probes.put(JSONObject().put("result_id", resultId).put("public_label", label)
                .put("rendered_profile_ids", JSONArray(identities)))
            if (PROFILE_ID in identities) return JSONObject().put("result_id", resultId)
                .put("public_label", label).put("rendered_profile_id", PROFILE_ID)
                .put("open_wall_ms", openingWallMs).put("search_query", "Positions Ariana Grande")
                .put("search_preview_withdrawn_before_open", true)
            ui.key(KeyEvent.KEYCODE_BACK, 0L)
            ui.awaitGone("collection-screen")
            ui.awaitId("search-result-grid", 8_000L)
        }
        error("Canonical Positions (Deluxe) album $ALBUM_ID was not found among twelve public album results")
    }

    private fun currentPid(): String = ui.device.executeShellCommand("pidof $TARGET").trim().also {
        check(it.matches(Regex("[0-9]+"))) { "Expected one running app process" }
    }

    private data class FrameEvent(val wallMs: Long, val mediaId: String, val line: String)
    private fun readFrames(): List<FrameEvent> = ui.device.executeShellCommand(
        "logcat -d -v epoch --pid=$appPid -t 200 -s AMATV-Motion:I '*:S'",
    ).lineSequence().mapNotNull { line ->
        val match = FRAME.matchEntire(line) ?: return@mapNotNull null
        FrameEvent((match.groupValues[1].toDouble() * 1_000).toLong(), match.groupValues[2], line.trim())
    }.toList()

    private companion object {
        const val ALBUM_ID = "1553944254"
        const val ALBUM_TITLE = "Positions (Deluxe)"
        const val PROFILE_ID = "collection-profile-albums-1553944254"
        val FRAME = Regex("\\s*([0-9]+\\.[0-9]+)\\s+[0-9]+\\s+[0-9]+\\s+I\\s+AMATV-Motion\\s*:\\s*First editorial frame mediaId=(amatv-motion:[0-9]+)\\s*")
    }
}
