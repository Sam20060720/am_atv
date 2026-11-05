package dev.amatv.benchmark

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeContinuityTest {
    @Test fun activityRecreationKeepsTheSameLivePlayerAndQueue() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_runtime_continuity") == "1")
        val expected = args.getString("expected_hardware")
        check(if (expected == null) Build.HARDWARE in setOf("ranchu", "goldfish") else Build.HARDWARE == expected)
        val run = checkNotNull(args.getString("run_id")); require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        val invocation = UUID.randomUUID().toString()
        val remote = "/sdcard/Android/data/dev.amatv/files/runtime-probe/$run/$invocation/result.json"
        device.pressHome()
        device.executeShellCommand("am broadcast -n dev.amatv/dev.amatv.benchmark.RuntimeContinuityProbeReceiver --es run_id $run --es invocation_id $invocation --ez allow_physical ${expected != null}")
        val launch = checkNotNull(inst.context.packageManager.getLaunchIntentForPackage(TARGET))
        inst.context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.uptimeMillis() + 20_000
        var report: JSONObject? = null
        while (SystemClock.uptimeMillis() < deadline && report == null) {
            val raw = device.executeShellCommand("cat $remote")
            report = runCatching { JSONObject(raw.substring(raw.indexOf('{'))) }.getOrNull()
            if (report == null) SystemClock.sleep(200)
        }
        val result = checkNotNull(report) { "Runtime continuity probe did not produce a report" }
        assertEquals(result.toString(2), invocation, result.getString("invocation_id"))
        assertEquals(result.toString(2), "passed", result.getString("status"))
    }
}
