package dev.amatv.benchmark

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic, account-free checks in a benchmark-only target Activity. */
@RunWith(AndroidJUnit4::class)
class ArtworkCacheRenderingTest {
    @Test fun decodedPrefetchAppearsOnFirstDrawAndAfterRemountWithoutFetchingAgain() {
        assumeTrue("Rendering probe is emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.context.startActivity(Intent().setComponent(
            ComponentName(TARGET, "dev.amatv.ui.ArtworkCacheProbeActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        try {
            val result = device.wait(Until.findObject(By.res("artwork-cache-probe-result")), 10_000L)
                ?: throw AssertionError("Cache rendering probe did not finish")
            val report = JSONObject(result.text)
            assertFalse(report.toString(), report.has("error"))
            assertTrue("Prefetch must populate the exact shared decoded cache", report.getBoolean("shared_decoded_cache_hit"))
            assertEquals("Warm mounting must not fetch again", 1, report.getInt("fetches"))
            assertEquals("Warm mounting must not decode again", 1, report.getInt("decodes"))
            val frames = report.getJSONArray("first_draw_pixels")
            assertEquals("Initial mount and remount must both be sampled", 2, frames.length())
            repeat(2) { mount -> repeat(3) { mode ->
                val pixel = frames.getJSONArray(mount).getInt(mode)
                assertTrue("Synthetic magenta/red placeholder appeared: mount=$mount mode=$mode (static/retained/staged) pixel=${Integer.toHexString(pixel)}",
                    Color.green(pixel) > Color.red(pixel) * 2 && Color.green(pixel) > Color.blue(pixel) * 1.4)
            } }
            val elapsed = device.findObject(By.res("player-progress-elapsed")).visibleBounds
            val remaining = device.findObject(By.res("player-progress-remaining")).visibleBounds
            assertTrue("Near-end 99-minute times must not overlap", elapsed.right < remaining.left)
        } finally {
            device.pressBack()
        }
    }
}
