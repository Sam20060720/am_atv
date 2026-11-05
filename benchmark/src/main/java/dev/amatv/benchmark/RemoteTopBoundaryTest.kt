package dev.amatv.benchmark

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Short, public remote regression; no media, account, or library mutations. */
@RunWith(AndroidJUnit4::class)
class RemoteTopBoundaryTest {
    @Test fun capturePairedPosterBeforeAndAfterFocus() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_remote_boundary") == "1")
        check(Build.HARDWARE == args.getString("expected_hardware"))
        val run = checkNotNull(args.getString("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "remote-boundary/$run")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val result = JSONObject().put("run_id", run).put("playback_actions", 0)
        try {
            ui.launch()
            val source = ui.firstShelf("home")
            result.put("source", source).put("source_label", ui.label(source))
            ui.settle(2_500)
            check(ui.device.takeScreenshot(File(out, "first-focused.png")))
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 2_000)
            check(source !in ui.snapshot().focused)
            check(ui.device.takeScreenshot(File(out, "first-unfocused.png")))
            ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 2_000)
            ui.await("Left did not return to the same card", 3_000) { source in ui.snapshot().focused }
            check(ui.device.takeScreenshot(File(out, "first-refocused.png")))
            result.put("final", ui.snapshot().json()).put("status", "captured_for_visual_review")
        } finally {
            File(out, "result.json").writeText(result.toString(2))
            ui.close()
        }
    }

    @Test fun repeatedUpAndFastFreshUpStayInContent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_remote_boundary") == "1")
        check(Build.HARDWARE == args.getString("expected_hardware"))
        val run = checkNotNull(args.getString("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val tab = args.getString("tab") ?: "home"
        require(tab in setOf("home", "browse"))
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "remote-boundary/$run")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val result = JSONObject().put("run_id", run).put("tab", tab).put("playback_actions", 0)
        fun inject(downTime: Long, action: Int, repeat: Int) {
            check(inst.uiAutomation.injectInputEvent(KeyEvent(downTime, SystemClock.uptimeMillis(),
                action, KeyEvent.KEYCODE_DPAD_UP, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD), true))
        }
        fun secondRow() {
            ui.firstShelf(tab)
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 500)
            ui.await("Down did not establish the real second-row source", 4_000) {
                ui.snapshot().focused.any { it.startsWith("shelf-1-item-") }
            }
        }
        fun verifyContent(name: String) {
            ui.settle(450)
            val snapshot = ui.snapshot()
            result.put(name, snapshot.json(detailed = true))
            check(snapshot.focused.any { it.startsWith("shelf-0-item-") } && !ui.has("nav-panel")) {
                "$name escaped the top content row into navigation: ${snapshot.focused}"
            }
        }
        try {
            ui.launch()
            secondRow()
            val downTime = SystemClock.uptimeMillis()
            try {
                inject(downTime, KeyEvent.ACTION_DOWN, 0)
                repeat(10) { SystemClock.sleep(55); inject(downTime, KeyEvent.ACTION_DOWN, it + 1) }
            } finally { inject(downTime, KeyEvent.ACTION_UP, 0) }
            verifyContent("held_up")
            secondRow()
            // No tree polling during the burst; each pair is a genuine fresh
            // press. The last begins while the 260ms top reveal is in flight.
            repeat(2) { ui.inject(KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(20) }
            verifyContent("fast_fresh_up")
            val classicHome = tab == "home" && ui.has("classic-navigation")
            ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 250)
            if (classicHome) {
                verifyContent("classic_home_left_stop")
                ui.key(KeyEvent.KEYCODE_DPAD_UP, 250)
            }
            ui.await("The style's navigation boundary did not reach navigation", 3_000) {
                ui.has("nav-panel") || "nav-menu" in ui.snapshot().focused ||
                    ui.has("classic-navigation") && ui.snapshot().focused.any { it.startsWith("nav-") }
            }
            result.put("navigation_boundary", if (classicHome) "up" else "left")
            ui.firstShelf(tab)
            result.put("final", ui.snapshot().json()).put("status", "passed")
        } catch (error: Throwable) {
            result.put("status", "failed").put("error", error.stackTraceToString().take(8_000))
            runCatching { result.put("failure_snapshot", ui.snapshot().json(detailed = true)) }
            throw error
        } finally {
            File(out, "result.json").writeText(result.toString(2))
            ui.close()
        }
    }
}
