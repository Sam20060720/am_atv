package dev.amatv.benchmark

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises production runtime commands in an account-free, separate-process fixture. */
@RunWith(AndroidJUnit4::class)
class RuntimeControlFixtureTest {
    @Test fun actualRuntimeKeepsHydrationControlsAndDemoCompletionConsistent() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val run = UUID.randomUUID().toString()
        val path = "/sdcard/Android/data/dev.amatv/files/runtime-control-fixture/$run/result.json"
        device.executeShellCommand("am start -n dev.amatv/dev.amatv.playback.RuntimeControlProbeActivity " +
            "--ez allow_runtime_control_fixture true --es run_id $run")
        var result: JSONObject? = null
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (result == null && SystemClock.uptimeMillis() < deadline) {
            val raw = device.executeShellCommand("cat $path")
            result = runCatching { JSONObject(raw) }.getOrNull()
            if (result == null) SystemClock.sleep(100L)
        }
        val report = checkNotNull(result) { "Runtime control fixture did not produce $path" }
        assertEquals(report.toString(2), run, report.getString("run_id"))
        assertEquals(report.toString(2), "passed", report.getString("status"))
        val checks = report.getJSONObject("checks")
        for (name in listOf("checkpoint_is_bounded", "pause_during_hydration_does_not_save",
            "pause_keeps_full_session", "full_restore_preserves_queue", "detached_control_continuity",
            "attached_control_callbacks", "attached_pause_clears_intent", "duplicate_occurrence_advances_once",
            "paused_completion_does_not_advance", "sleep_timer_stops_before_next", "demo_avoids_native_preparation")) {
            assertTrue("$name: $report", checks.getBoolean(name))
        }
    }
}
