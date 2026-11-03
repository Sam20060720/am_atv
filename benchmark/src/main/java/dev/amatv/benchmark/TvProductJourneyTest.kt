package dev.amatv.benchmark

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class TvProductJourneyTest {
    @get:Rule val failures = UiFailureArtifacts()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val ui by lazy { TvUi(device) }

    private fun coldLaunchPreservingAccount() {
        device.executeShellCommand("am force-stop $TARGET")
        launchTarget()
    }

    private fun launchTarget() {
        val context = instrumentation.context
        val intent = context.packageManager.getLaunchIntentForPackage(TARGET)
            ?: throw AssertionError("Target app not installed")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), UI_TIMEOUT))
    }

    @Test fun restoredStartupDoesNotAutomaticallyPlay() {
        coldLaunchPreservingAccount()
        assumeTrue("No saved song on this account", ui.openRetainedSong())
        val player = PlayerUi(ui)
        player.awaitState("paused")
        ui.settle(2_000)
        assertEquals("Startup must remain paused after services initialize", "paused", player.state())
    }

    @Test fun collectionSongsAndSingleBackReturnToBrowse() {
        coldLaunchPreservingAccount()
        ui.browseTab("library")
        ui.right(0)
        ui.id("library-browser")
        ui.focusUi("library-category-albums")
        ui.center(0)
        assertTrue(device.wait({ ui.has("library-grid") || ui.has("library-empty") }, 60_000L))
        assumeTrue("Account has no library albums", ui.has("library-grid"))
        ui.right(0)
        ui.awaitFocused("library-item-0-album")
        ui.center()
        ui.id("collection-screen")
        assertTrue("Collection detail must retain global navigation", ui.has("nav-menu"))
        ui.requireObject(By.res("collection-play").enabled(true), 60_000)
        // The play action deliberately keeps input focus while data is loading.
        // Real song content, rather than its focusable wrapper, signals readiness.
        ui.requireObject(By.res("collection-song-0"), 60_000)
        ui.focusUi("collection-play")
        ui.down(0)
        ui.awaitFocused("collection-song-0")
        val count = ui.requireObject(By.text(Pattern.compile("\\d+ 首歌曲.*")))
            .text.substringBefore(' ').toInt()
        val steps = (count - 1).coerceIn(0, 6)
        repeat(steps) { ui.down(100) }
        repeat(steps) { ui.up(100) }
        ui.settle(600)
        assertTrue("Song list must retain DPAD focus", ui.id("collection-song-0").isFocused)
        device.pressBack()
        ui.id("nav-menu")
        assertFalse("One Back must close collection", ui.has("collection-screen"))
        ui.awaitFocused("library-item-0-album")
    }

    @Test fun queueModesAndControllerDisappearTogether() {
        coldLaunchPreservingAccount()
        ui.requireRetainedPausedSong()
        val player = PlayerUi(ui)
        player.focusControl("player-queue")
        ui.center()
        ui.id("queue-panel")
        ui.id("queue-mode-controls")
        assertFalse(ui.device.hasObject(By.text("Playing Next")))
        repeat(4) { ui.right(150) }
        repeat(4) { ui.left(150) }
        assertTrue(device.wait(Until.gone(By.res("player-controls")), 8_000))
        assertFalse("Top queue modes must disappear with controller", ui.has("queue-mode-controls"))
        assertTrue("Queue history stays visible while controls hide", ui.has("queue-panel"))
        ui.right(450)
        ui.id("player-controls")
        ui.id("queue-mode-controls")
    }

    @Test fun nativePlaybackCanStartAndPauseFromRemote() {
        requirePlayerPlaybackOptIn()
        val allowSeed = InstrumentationRegistry.getArguments().getString("allow_playback_seed") == "1"
        coldLaunchPreservingAccount()
        if (!ui.openRetainedSong()) {
            assumeTrue("SKIPPED: need a previously selected song or allow_playback_seed=1", allowSeed)
            ui.seedPlayableSong()
        }
        assumeTrue("SKIPPED: retained song must already be paused", PlayerUi(ui).state() == "paused")
        val player = PlayerUi(ui)
        try {
            player.play()
            ui.settle(3_000)
            assertEquals("playing", player.state())
        } finally {
            player.pause()
        }
    }
}
