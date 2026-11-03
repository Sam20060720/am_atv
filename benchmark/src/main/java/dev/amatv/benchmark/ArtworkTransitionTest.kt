package dev.amatv.benchmark

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real focused artwork -> poster -> video, plus interrupted DPAD handoff; no audio actions. */
@RunWith(AndroidJUnit4::class)
class ArtworkTransitionTest {
    @Test fun focusedCoverBlendsInBothDirections() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_artwork_transition") == "1")
        val expectedHardware = args.getString("expected_hardware")
        check(if (expectedHardware != null) Build.HARDWARE == expectedHardware
            else Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = args.getString("run_id") ?: "blend-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "artwork-transition/$run")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val result = JSONObject().put("run_id", run).put("audio_operations", false).put("account_mutations", false)
        var pid = ""
        var recorder: String? = null
        fun startClip(name: String) {
            recorder = ui.device.executeShellCommand("screenrecord --time-limit 5 --bit-rate 6000000 ${out.path}/$name.mp4 >/dev/null 2>&1 </dev/null & echo \$!")
                .trim().takeIf { it.matches(Regex("[0-9]+")) }
        }
        fun stopClip() {
            recorder?.let { ui.device.executeShellCommand("kill -2 $it 2>/dev/null") }
            recorder = null
        }
        fun events(): List<Pair<String, Long>> = ui.device.executeShellCommand(
            "logcat -d -v epoch --pid=$pid -t 300 -s AMATV-Motion:I '*:S'",
        ).lineSequence().mapNotNull { line ->
            val m = Regex("\\s*([0-9]+\\.[0-9]+)\\s+.*?I\\s+AMATV-Motion\\s*:\\s*(.*)").matchEntire(line)
            m?.let { it.groupValues[2] to (it.groupValues[1].toDouble() * 1000).toLong() }
        }.toList()
        try {
            ui.launch()
            if (args.getString("check_home_header") == "1") {
                val card = ui.firstShelf("home")
                var header: String? = null
                startClip("header")
                repeat(4) {
                    ui.key(KeyEvent.KEYCODE_DPAD_UP, 130)
                    ui.await("Card Up lost its actual heading action", 2_000) {
                        ui.snapshot().focused.singleOrNull { it.startsWith("home-shelf-") }?.also { value -> header = value } != null
                    }
                    ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 130)
                    ui.await("Heading Down did not return to its original card", 2_000) { card in ui.snapshot().focused }
                }
                stopClip()
                result.put("header", header).put("header_round_trips", 4)
            }
            ui.search("albums", "Positions Ariana Grande")
            pid = ui.device.executeShellCommand("pidof $TARGET").trim()
            check(pid.matches(Regex("[0-9]+")))
            val canonical = (0 until 12).map { "search-result-$it" }.firstOrNull {
                ui.has(it) && ui.label(it).contains("Positions (Deluxe)", true) && ui.label(it).contains("Ariana Grande", true)
            } ?: error("Actual public motion album missing")
            ui.focus("search-category-albums")
            val started = System.currentTimeMillis()
            ui.focus(canonical)
            ui.await("Real focused cover must finish its entry blend", 15_000) {
                events().any { it.second >= started && it.first.startsWith("Editorial blend finished mediaId=") }
            }
            check(ui.device.takeScreenshot(File(out, "motion.png")))
            val exiting = System.currentTimeMillis()
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 0)
            ui.await("Up must leave the actual cover", 3_000) { "search-category-albums" in ui.snapshot().focused }
            ui.await("Motion-to-poster blend did not complete", 5_000) {
                events().any { it.second >= exiting && it.first.startsWith("Editorial poster blend finished mediaId=") }
            }
            val exit = events().filter { it.second >= exiting && it.first.startsWith("Editorial poster blend") }
            val begin = exit.first { it.first.startsWith("Editorial poster blend started") }
            val end = exit.first { it.first.startsWith("Editorial poster blend finished") }
            check(end.second - begin.second >= 380) { "Poster snapped instead of fading" }
            result.put("exit_duration_ms", end.second - begin.second)
            check(ui.device.takeScreenshot(File(out, "poster.png")))
            // Re-enter using only real remote movement. Pause for a ready frame,
            // then reverse the exit before it finishes, without touching audio.
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Return to original cover", 3_000) { canonical in ui.snapshot().focused }
            val reentry = System.currentTimeMillis()
            ui.await("Cover did not blend back to real video", 10_000) {
                events().any { it.second >= reentry && it.first.startsWith("Editorial blend finished mediaId=") }
            }
            startClip("interrupted")
            val interrupted = System.currentTimeMillis()
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 100)
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Reversal did not render a new editorial frame", 10_000) {
                events().any { it.second >= interrupted && it.first.startsWith("First editorial frame mediaId=") }
            }
            ui.settle(140)
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 70)
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Interrupted crossfade did not retain both visible layers", 3_000) {
                events().any { it.second >= interrupted && it.first.startsWith("Editorial interrupted blend retained layers=2") }
            }
            ui.await("Cover did not resume after the interrupted blend", 10_000) {
                events().any { it.second >= interrupted + 300 && it.first.startsWith("Editorial blend finished mediaId=") }
            }
            stopClip()
            check(canonical in ui.snapshot().focused && !ui.has("nav-panel"))
            check(ui.device.takeScreenshot(File(out, "reversed.png")))
            result.put("events", JSONArray(events().filter { it.second >= started }.map {
                JSONObject().put("message", it.first).put("wall_ms", it.second)
            })).put("status", "passed_bidirectional_and_interrupted_focus")
        } catch (e: Throwable) {
            result.put("status", "failed").put("error", e.message)
            runCatching { ui.device.takeScreenshot(File(out, "failure.png")) }
            throw e
        } finally { stopClip(); File(out, "result.json").writeText(result.toString(2)); ui.close() }
    }
}
