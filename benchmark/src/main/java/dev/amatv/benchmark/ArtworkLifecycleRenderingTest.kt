package dev.amatv.benchmark

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production MotionArtwork in a real lazy list; deterministic assets, no account or audio actions. */
@RunWith(AndroidJUnit4::class)
class ArtworkLifecycleRenderingTest {
    @Test fun lateMetadataSurvivesLazyDisposalAndImageEviction() {
        assumeTrue("Rendering probe is emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val run = "lifecycle-${System.currentTimeMillis()}"
        val output = File(checkNotNull(instrumentation.context.getExternalFilesDir(null)), "artwork-lifecycle/$run")
        check(output.mkdirs())
        instrumentation.context.startActivity(Intent().setComponent(ComponentName(TARGET, "dev.amatv.ui.ArtworkCacheProbeActivity"))
            .putExtra("artwork_lifecycle_probe", true).putExtra("run_id", run)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        var latest = JSONObject()
        fun phase(expected: String): JSONObject {
            val end = SystemClock.uptimeMillis() + 10_000L
            while (SystemClock.uptimeMillis() < end) {
                val text = device.findObject(By.res("artwork-lifecycle-report"))?.text
                if (!text.isNullOrEmpty()) {
                    latest = JSONObject(text)
                    assertFalse(latest.toString(), latest.has("error"))
                    if (latest.optString("phase") == expected) return latest
                }
                SystemClock.sleep(40)
            }
            throw AssertionError("Expected artwork fixture phase=$expected; actual=$latest")
        }
        fun frame(name: String) = latest.getJSONObject("frames").getJSONObject(name)
        fun isPairedPoster(name: String) {
            val pixel = frame(name).getInt("pixel")
            assertTrue("Wrong poster or synthetic placeholder in $name: ${Integer.toHexString(pixel)}",
                Color.blue(pixel) > Color.green(pixel) * 1.5 && Color.blue(pixel) > Color.red(pixel) * 2)
        }
        try {
            assertTrue(device.wait(Until.hasObject(By.res("artwork-lifecycle-report")), 10_000L))
            phase("ready")
            for (expected in listOf("metadata-blocked", "disposed-before-result", "late-result-cached", "remounted",
                    "motion-disabled", "motion-enabled", "complete")) {
                assertTrue("Fixture Center input failed", device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
                phase(expected)
                if (expected == "remounted") check(device.takeScreenshot(File(output, "idle-remount.png")))
            }
            val initial = frame("initial-catalog").getInt("pixel")
            assertTrue("Original poster must be green before metadata hydration",
                Color.green(initial) > Color.red(initial) * 2 && Color.green(initial) > Color.blue(initial) * 1.4)
            assertTrue("The metadata response must complete after the real lazy item was disposed",
                latest.getBoolean("late_result_completed_while_disposed"))
            assertEquals("Remount and setting toggles must reuse the same metadata read", 1, latest.getInt("metadata_reads"))
            assertTrue("The actual card must have been disposed twice", latest.getInt("disposals") >= 2)
            assertTrue("The actual card must have been mounted three times", latest.getInt("mounts") >= 3)
            assertTrue("Lazy return must recreate remembered state, not merely reattach an existing effect",
                latest.getInt("remembered_instances") >= 3)
            for (name in listOf("idle-remount", "motion-disabled", "motion-enabled", "evicted-settled")) isPairedPoster(name)
            assertEquals("Warm remount must not fetch image bytes again", frame("initial-catalog").getInt("image_fetches"),
                frame("idle-remount").getInt("image_fetches"))
            assertEquals("Warm remount must not decode again", frame("initial-catalog").getInt("image_decodes"),
                frame("idle-remount").getInt("image_decodes"))
            assertTrue("Decoded-byte eviction must exercise a new image fetch",
                frame("evicted-settled").getInt("image_fetches") > frame("idle-remount").getInt("image_fetches"))
            assertTrue("Decoded-byte eviction must exercise a new decode",
                frame("evicted-settled").getInt("image_decodes") > frame("idle-remount").getInt("image_decodes"))
            check(device.takeScreenshot(File(output, "evicted-settled.png")))
            latest.put("status", "passed_late_metadata_lazy_remount_toggle_and_eviction")
        } catch (failure: Throwable) {
            latest.put("status", "failed").put("test_error", failure.toString())
            runCatching { device.takeScreenshot(File(output, "failure.png")) }
            throw failure
        } finally {
            File(output, "result.json").writeText(latest.toString(2))
            device.pressBack()
        }
    }
}
