package dev.amatv.benchmark

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Public readback after the root-owned private-state restore; never dispatches Play. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class RestorationStateTest {
    @Test fun verifyRestoredPausedState() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_production_journey") == "1")
        check(Build.HARDWARE == "m7632") { "Final restoration readback is scoped to the physical TV" }
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val expectedHash = checkNotNull(args.getString("expected_apk_sha256")).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val expectedTitle = checkNotNull(args.getString("expected_current_title")).trim().also { require(it.isNotEmpty()) }
        val expectedPosition = checkNotNull(args.getString("expected_position_ms")).toLong().also { require(it >= 0) }
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "restoration-state/$run")
        check(!out.exists() && out.mkdirs())
        val result = JSONObject().put("run_id", run).put("hardware", Build.HARDWARE)
            .put("expected_title", expectedTitle).put("expected_position_ms", expectedPosition)
            .put("play_actions", 0).put("media_session_listener", false).put("sdk_seed", false)
        var ui: RenderingSurveyUi? = null
        try {
            val app = inst.context.packageManager.getApplicationInfo(TARGET, 0)
            check(app.splitSourceDirs.isNullOrEmpty()) { "Expected the exact universal standalone" }
            val actualHash = File(app.sourceDir).inputStream().buffered().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            result.put("actual_apk_sha256", actualHash)
            check(actualHash == expectedHash) { "Installed APK hash differs from the authorized final standalone" }
            val receivers = inst.context.packageManager.getPackageInfo(TARGET, PackageManager.GET_RECEIVERS).receivers.orEmpty()
            check(receivers.none { it.name.startsWith("dev.amatv.benchmark.") }) { "Benchmark receiver found in final APK" }
            result.put("benchmark_receivers_absent", true)
            val reader = RenderingSurveyUi(inst)
            ui = reader
            fun wakefulness() = reader.device.executeShellCommand("dumpsys power").lineSequence()
                .firstOrNull { "mWakefulness=" in it }?.trim().orEmpty()
            val wakeRequired = !reader.device.isScreenOn || "mWakefulness=Awake" !in wakefulness()
            if (wakeRequired) {
                reader.device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
                reader.await("TV did not leave sleep/dream", 6_000) {
                    reader.device.isScreenOn && "mWakefulness=Awake" in wakefulness()
                }
                reader.settle(1_200)
            }
            result.put("wake_action", if (wakeRequired) "wake_and_settle" else "none_already_awake")
            reader.launch()
            if (!reader.has("player-screen")) {
                reader.openNavigation()
                reader.awaitId("nav-nowplaying", 8_000)
                reader.activate("nav-nowplaying", "navigation")
                reader.awaitId("player-screen", 8_000)
            }
            val selected = reader.snapshot().selected
            check("player-progress" !in selected && selected.none { it.startsWith("player-queue-item-") }) {
                "Unexpected retained scrub/Queue owner; refusing a directional input that could mutate it"
            }
            reader.focus("player-screen")
            reader.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            reader.awaitId("player-play-pause", 4_000)
            reader.await("Restored public player is not paused on the expected title", 8_000) {
                reader.playerPaused() && reader.label("player-artwork").contains(expectedTitle, ignoreCase = true)
            }
            val elapsedLabel = reader.label("player-progress-elapsed")
            val time = checkNotNull(Regex("(?<![\\d:])(\\d+:\\d{2}(?::\\d{2})?)(?![\\d:])")
                .find(elapsedLabel)?.groupValues?.get(1)) { "Missing actual public elapsed clock: $elapsedLabel" }
            val elapsedMs = time.split(':').fold(0L) { total, part -> total * 60 + part.toLong() } * 1_000
            val paused = JSONObject().put("title_label", reader.label("player-artwork"))
                .put("elapsed_label", elapsedLabel).put("elapsed_ms", elapsedMs)
                .put("position_delta_ms", elapsedMs - expectedPosition).put("paused", reader.playerPaused())
                .put("snapshot", reader.snapshot().json(detailed = true))
            result.put("restored_player", paused)
            File(out, "paused-snapshot.json").writeText(paused.toString(2))
            check(abs(elapsedMs - expectedPosition) <= 1_500) { "Restored public clock differs from the checkpoint by more than 1.5 seconds" }
            // No action can start or replace playback; Back then the public Home
            // root-to-top action leaves a useful, naturally traversable browse owner.
            val back = reader.leavePlayerWithBack()
            val first = reader.firstShelf("home")
            val home = reader.snapshot()
            result.put("back_steps", back).put("home_source", first).put("home_snapshot", home.json(detailed = true))
            File(out, "home-snapshot.json").writeText(home.json(detailed = true).toString(2))
            check(first in home.focused && first.matches(Regex("shelf-0-item-0-[a-z]+")))
            check(!reader.has("player-screen"))
            result.put("status", "passed").put("restored_state_readback_only", true)
        } catch (error: Throwable) {
            result.put("status", "failed").put("error", error.stackTraceToString().take(10_000))
            ui?.let { reader -> runCatching { reader.snapshot().json(detailed = true) }
                .onSuccess { result.put("failure_snapshot", it) }
                .onFailure { result.put("failure_snapshot_error", it.toString()) } }
            throw error
        } finally {
            result.put("finished_elapsed_ms", SystemClock.elapsedRealtime())
            File(out, "result.json").writeText(result.toString(2))
            ui?.close()
        }
    }
}
