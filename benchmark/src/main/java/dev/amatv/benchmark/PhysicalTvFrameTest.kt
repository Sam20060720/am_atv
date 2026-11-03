package dev.amatv.benchmark

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Physical-device opt-in; navigation only, no playback/account/library actions. */
@RunWith(AndroidJUnit4::class)
class PhysicalTvFrameTest {
    @Test fun homeCardRoundTrip() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_physical_frames") == "1")
        check(Build.HARDWARE == args.getString("expected_hardware"))
        val run = checkNotNull(args.getString("run_id"));require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val out=File(checkNotNull(inst.context.getExternalFilesDir(null)),"physical-frames/$run")
        check(!out.exists()&&out.mkdirs())
        val ui=RenderingSurveyUi(inst)
        val r=JSONObject().put("run_id",run).put("hardware",Build.HARDWARE).put("playback_actions",false)
        try {
            ui.launch()
            args.getString("motion_setting")?.let {
                require(it in setOf("on", "off"))
                r.put("motion_setting", it).put("prior_motion_setting", setMotion(ui, it == "on"))
            }
            val controls = args.getString("scene") == "controls"
            val vertical = args.getString("axis") == "vertical"
            val source: String
            if (controls) {
                ui.navigate("account")
                var attempts = 0
                while (!ui.has("language-system") && attempts++ < 50) ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100)
                ui.focus("language-system")
                source = "language-system"
            } else {
                val first = ui.firstShelf("home")
                check(ui.shelfInfo().cardCount>=4)
                val startShelf = args.getString("start_shelf")?.toInt() ?: 0
                require(startShelf in 0..1)
                if (startShelf == 1) ui.key(KeyEvent.KEYCODE_DPAD_DOWN,500)
                val startItem = args.getString("start_item")?.toInt() ?: 0
                require(startItem in 0..4)
                repeat(startItem) { ui.key(KeyEvent.KEYCODE_DPAD_RIGHT,180) }
                source = if (startShelf == 0 && startItem == 0) first else ui.snapshot().focused.single {
                    it.startsWith("shelf-$startShelf-item-$startItem-")
                }
                r.put("start_shelf",startShelf).put("start_item",startItem)
            }
            ui.settle(1_000)
            r.put("source",source).put("axis", if (vertical) "vertical" else "horizontal").put("scene", if (controls) "controls" else "home").put("pid",ui.device.executeShellCommand("pidof $TARGET").trim())
            val keys = if (vertical) listOf(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_UP) else listOf(
                KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_LEFT)
            if (args.getString("warm_trace") == "1") {
                ui.device.executeShellCommand("dumpsys gfxinfo $TARGET reset")
                for (code in keys) { ui.inject(code); ui.settle(140) }
                ui.settle(700)
                File(out,"cold-gfxinfo.txt").writeText(ui.device.executeShellCommand("dumpsys gfxinfo $TARGET framestats"))
                ui.await("Warmup lost original focus",4_000) { source in ui.snapshot().focused }
                r.put("excluded_warmup",true)
                if (args.getString("external_trace") == "1") {
                    File(out,"trace-ready").writeText(SystemClock.elapsedRealtimeNanos().toString())
                    ui.await("External trace runner did not release measurement",15_000) {
                        File(out,"trace-go").exists()
                    }
                }
                ui.settle(300)
            }
            if (args.getString("internal_trace") == "1") {
                val config = File(out, "perfetto.cfg")
                config.writeText("""
                    buffers { size_kb: 32768 fill_policy: RING_BUFFER }
                    duration_ms: 8000
                    data_sources { config { name: "linux.ftrace" ftrace_config {
                      ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
                      atrace_categories: "gfx" atrace_categories: "view" atrace_apps: "dev.amatv"
                    } } }
                    data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
                """.trimIndent())
                val trace = "/data/misc/perfetto-traces/amatv-$run.pftrace"
                val started = ui.device.executeShellCommand("perfetto --background --txt -c ${config.path} -o $trace")
                File(out, "perfetto-start.txt").writeText(started)
                r.put("trace_path", trace).put("trace_started_uptime_ms", SystemClock.uptimeMillis())
                // Android 11 has no --background-wait; keep setup out of the
                // measured burst and verify trace coverage in offline analysis.
                ui.settle(1_250)
            }
            ui.device.executeShellCommand("dumpsys gfxinfo $TARGET reset")
            // Window container layers have a leading token. Only the exact
            // activity buffer layer owns the presentation history we measure.
            val surfaces = ui.device.executeShellCommand("dumpsys SurfaceFlinger --list")
            File(out, "surface-list.txt").writeText(surfaces)
            val surface = surfaces.lineSequence().map(String::trim)
                .filter { it.matches(Regex("dev\\.amatv/dev\\.amatv\\.MainActivity#\\d+")) }
                .maxByOrNull { it.substringAfterLast('#').toInt() }
            r.put("surface_name", surface)
            val inputs=JSONArray();r.put("inputs",inputs)
            r.put("start_uptime_ms",SystemClock.uptimeMillis()).put("start_elapsed_ns",SystemClock.elapsedRealtimeNanos())
            repeat(2) { trial ->
                val trialStart = SystemClock.uptimeMillis()
                for (code in keys) {
                    val start=SystemClock.uptimeMillis();ui.inject(code)
                    inputs.put(JSONObject().put("key",code).put("start_ms",start).put("dispatch_ms",SystemClock.uptimeMillis()-start))
                    ui.settle(140)
                }
                ui.settle(600)
                val trialEnd = SystemClock.uptimeMillis()
                File(out,"trial-${trial + 1}-gfxinfo.txt").writeText(
                    ui.device.executeShellCommand("dumpsys gfxinfo $TARGET framestats"))
                File(out,"trial-${trial + 1}-surface-latency.txt").writeText(
                    if (surface != null) ui.device.executeShellCommand("dumpsys SurfaceFlinger --latency $surface")
                    else "No current main-activity buffer surface")
                r.put("trial_${trial + 1}_start_ms",trialStart).put("trial_${trial + 1}_end_ms",trialEnd)
                ui.device.executeShellCommand("dumpsys gfxinfo $TARGET reset")
            }
            ui.settle(700)
            r.put("end_uptime_ms",SystemClock.uptimeMillis())
            File(out,"gfxinfo.txt").writeText(ui.device.executeShellCommand("dumpsys gfxinfo $TARGET framestats"))
            ui.await("Home round trip lost source focus",4_000){source in ui.snapshot().focused}
            r.put("final",ui.snapshot().json()).put("status","passed")
            ui.device.takeScreenshot(File(out,"home.png"))
            if (!controls) {
                val visibility = ui.homeCardVisibility(source)
                r.put("source_visibility", visibility)
                check(visibility.optBoolean("complete")) { "Source cover/captions are clipped or not loaded" }
            }
            if (args.getString("check_context_menu") == "1") {
                ui.key(KeyEvent.KEYCODE_MENU,450)
                ui.awaitId("catalog-actions-list")
                ui.assertNaturalFirstMenuFocus()
                ui.device.takeScreenshot(File(out,"menu.png"))
                ui.key(KeyEvent.KEYCODE_BACK,450)
                ui.await("Context menu did not restore source",3_000) {
                    !ui.has("catalog-actions-list") && source in ui.snapshot().focused
                }
                r.put("menu_anchor_and_return","passed")
            }
            if (r.has("trace_started_uptime_ms")) ui.settle(
                (r.getLong("trace_started_uptime_ms") + 9_000 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        } catch(e:Throwable){
            r.put("status","failed").put("error",e.message).put("failure_stack",e.stackTraceToString())
            runCatching { r.put("failure_snapshot",ui.snapshot().json(detailed=true)) }
                .onFailure { r.put("failure_snapshot_error",it.toString()) }
            runCatching { r.put("failure_screenshot",ui.device.takeScreenshot(File(out,"failure.png"))) }
                .onFailure { r.put("failure_screenshot_error",it.toString()) }
            throw e
        }
        finally{File(out,"result.json").writeText(r.toString(2));ui.close()}
    }

    private fun setMotion(ui: RenderingSurveyUi, enabled: Boolean): Boolean {
        ui.navigate("account")
        var attempts = 0
        while (!ui.has("settings-artwork-motion") && attempts++ < 50) ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100)
        ui.awaitId("settings-artwork-motion", 3_000)
        ui.focus("settings-artwork-motion")
        val control = checkNotNull(ui.device.findObject(By.res("settings-artwork-motion")))
        check(control.isClickable && control.isEnabled)
        val onLabels = ui.stringLabels("artwork_motion_on")
        val offLabels = ui.stringLabels("artwork_motion_off")
        val initialLabel = ui.label("settings-artwork-motion")
        check((onLabels + offLabels).any { initialLabel.contains(it) }) { "Unknown visible artwork setting" }
        val previous = onLabels.any { initialLabel.contains(it) }
        if (previous != enabled) {
            check("settings-artwork-motion" in ui.snapshot().focused)
            ui.device.pressDPadCenter()
            ui.await("Artwork preference did not update", 3_000) {
                (if (enabled) onLabels else offLabels).any { ui.label("settings-artwork-motion").contains(it) }
            }
        }
        return previous
    }
}
