package dev.amatv.benchmark

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the public remote contract with either a saved song or the existing demo catalog. */
@RunWith(AndroidJUnit4::class)
class PlayerChromeTest {
    @get:Rule val failures = UiFailureArtifacts()

    @Test fun elapsedTimeStaysClearOfRemainingAtEndOfScrubPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val ui = TvUi(device)
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET)
            ?: throw AssertionError("Target app is not installed")
        instrumentation.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.openNavigation()
        assumeTrue("Requires a saved song or the app's demo catalog", ui.has("nav-nowplaying"))
        ui.browseTab("nowplaying")
        PlayerUi(ui).focusControl("player-play-pause")
        ui.up(100)
        val remainingText = ui.id("player-progress-remaining").text.orEmpty().removePrefix("−")
        val parts = remainingText.split(":").mapNotNull { it.toIntOrNull() }
        assumeTrue("Requires a finite song duration", parts.size == 2)
        val seconds = parts[0] * 60 + parts[1]
        assumeTrue("Keep this remote UI check bounded to a song", seconds in 1..900)
        try {
            // Preview only: never commit a seek or change the saved playback position.
            repeat((seconds + 4) / 5 + 1) { ui.right(25) }
            assertTrue("Preview must reach the end of the track",
                device.wait(Until.hasObject(By.res("player-progress-remaining").text("−0:00")), 5_000L))
            val elapsed = ui.id("player-progress-elapsed").visibleBounds
            val remaining = ui.id("player-progress-remaining").visibleBounds
            val progress = ui.id("player-progress").visibleBounds
            assertTrue("Following time must not overlap the trailing time", elapsed.right < remaining.left)
            assertTrue("Both times must stay inside the progress track",
                progress.contains(elapsed) && progress.contains(remaining))
        } finally {
            device.pressBack() // Cancel the preview, preserving the actual playback position.
        }
    }

    @Test fun controlsScrubberAndAutoHideRemainReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val ui = TvUi(device)
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET)
            ?: throw AssertionError("Target app is not installed")
        instrumentation.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.openNavigation()
        assumeTrue("Requires a saved song or the app's demo catalog", ui.has("nav-nowplaying"))
        ui.browseTab("nowplaying")
        val player = PlayerUi(ui)
        player.focusControl("player-play-pause")
        ui.up(0)
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (true) {
            val progress = ui.id("player-progress")
            if (progress.isSelected || progress.isChecked) break
            assertTrue("Up must enter the scrubber's virtual focus", SystemClock.uptimeMillis() < deadline)
            ui.settle(25)
        }
        ui.left(100)
        ui.right(100)
        ui.center(100)
        ui.down(100)
        player.focusControl("player-queue")
        ui.center(100)
        ui.id("queue-panel")
        assertTrue("Controls must disappear after inactivity",
            device.wait(Until.gone(By.res("player-controls")), 8_000L))
        assertTrue("Queue remains visible after control dismissal", ui.has("queue-panel"))
        ui.right(100)
        ui.id("player-controls")
        player.focusControl("player-play-pause")
        device.pressBack()
        ui.navigation()
    }
}
