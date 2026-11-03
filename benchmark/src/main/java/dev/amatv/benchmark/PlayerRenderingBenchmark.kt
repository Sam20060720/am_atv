package dev.amatv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Live subscribed account, real native audio, real loaded TTML; no mock renderer or hidden hooks. */
@OptIn(ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
@LargeTest
class PlayerRenderingBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @get:Rule val failures = UiFailureArtifacts()
    private var playbackStarted = false

    @Before fun requireExplicitPlayback() {
        requirePlayerPlaybackOptIn()
    }

    private fun prepareRetainedPausedSong(ui: TvUi): PlayerUi {
        assumeTrue("SKIPPED: need a previously selected real song", ui.openRetainedSong())
        val player = PlayerUi(ui)
        // Later repetitions may still own playback from the preceding window.
        if (playbackStarted && player.state() in setOf("playing", "preparing")) player.pause()
        assumeTrue("SKIPPED: retained song must already be paused", player.state() == "paused")
        return player
    }

    private fun MacrobenchmarkScope.prepareLivePlayer(showLyrics: Boolean) {
        startActivityAndWait()
        val ui = TvUi(device)
        val player = prepareRetainedPausedSong(ui)
        playbackStarted = true
        player.play()
        // Normalize both cases through a genuinely rendered TTML panel. This
        // makes the artwork run a comparator for the same lyric-capable song,
        // rather than accidentally measuring an instrumental/failed request.
        if (!device.wait(Until.hasObject(By.res("lyrics-panel")), 3_000)) {
            player.focusControl("player-lyrics")
            ui.center()
        }
        assumeTrue("SKIPPED: saved song has no loaded synchronized TTML",
            device.wait(Until.hasObject(By.res("lyrics-panel")), 45_000))
        if (!showLyrics) {
            player.focusControl("player-lyrics")
            ui.center()
            assertTrue("Lyrics must close for the artwork-only comparison",
                device.wait(Until.gone(By.res("lyrics-panel")), 3_000))
        }
        assertTrue("Controller must finish hiding before steady measurement",
            device.wait(Until.gone(By.res("player-controls")), 8_000))
        assertEquals("playing", player.state())
    }

    @Test fun steadyLyricsAndNativeLava() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        iterations = configuredBenchmarkIterations(),
        setupBlock = { prepareLivePlayer(showLyrics = true) },
    ) {
        val ui = TvUi(device)
        ui.settle(8_000)
        assertTrue("Real lyrics must remain visible", ui.has("lyrics-panel"))
        assertEquals("playing", PlayerUi(ui).state())
    }

    /** Includes the first TTML publication and word layout, not only already-rendered lyrics. */
    @Test fun initialLyricsLoadAfterRestoredPlay() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        iterations = configuredBenchmarkIterations(),
        setupBlock = {
            startActivityAndWait()
            val preparingUi = TvUi(device)
            val preparingPlayer = prepareRetainedPausedSong(preparingUi)
            // Keep TTML word drawing cold while setting the real saved cursor.
            preparingPlayer.focusControl("player-lyrics")
            preparingUi.center(0)
            preparingUi.center(0)
            playbackStarted = true
            preparingPlayer.play()
            preparingPlayer.focusControl("player-play-pause")
            preparingUi.up(0)
            // Save an actual mid-song cursor. Starting near a vocal/interlude
            // boundary cannot distinguish opening placement from its normal
            // 750 ms interlude collapse.
            repeat(60) { preparingUi.left(0) }
            repeat(12) { preparingUi.right(0) }
            preparingUi.center(500)
            preparingUi.down(0)
            preparingPlayer.pause()
            // Normal lifecycle persistence, then a real paused restoration. No
            // account, cache, compilation or app-data reset is involved.
            device.pressHome()
            TvUi(device).settle(600)
            device.executeShellCommand("am force-stop $TARGET")
            startActivityAndWait()
            val ui = TvUi(device)
            assumeTrue("SKIPPED: need a previously selected real song", ui.openRetainedSong())
            val player = PlayerUi(ui)
            player.awaitState("paused")
            // Restored queue metadata excludes TTML; show its empty stage before
            // the first explicit Play causes the genuine lyrics request.
            player.focusControl("player-lyrics")
            ui.center()
            assertTrue("A restored paused song must not already have rendered lyrics", !ui.has("lyrics-panel"))
            player.focusControl("player-play-pause")
        },
    ) {
        val ui = TvUi(device)
        ui.center(0)
        assumeTrue("SKIPPED: selected real song has no synchronized TTML",
            device.wait(Until.hasObject(By.res("lyrics-panel")), 45_000))
        val initialCue = ui.stateDescription("lyrics-panel")
        if (initialCue?.startsWith("line:") == true) {
            val index = initialCue.substringAfter(':').toInt()
            val row = ui.id("lyrics-row-$index")
            val panel = ui.id("lyrics-panel").visibleBounds
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val stageOffset = if (ui.has("player-controls")) -28f * density else 0f
            val expectedTop = panel.top + kotlin.math.round(panel.height() * .26f) - 2f * density + stageOffset
            assertTrue("First lyric row was not placed at the restored cue: row=${row.visibleBounds} expectedTop=$expectedTop cue=$initialCue",
                kotlin.math.abs(row.visibleBounds.top - expectedTop) <= 12f)
        }
        PlayerUi(ui).awaitState("playing", 60_000)
        ui.settle(2_000)
        assertTrue("Loaded lyrics must contain actual mounted rows",
            device.hasObject(By.res(java.util.regex.Pattern.compile("lyrics-row-\\d+"))))
        assertEquals("playing", PlayerUi(ui).state())
    }

    @Test fun controllerShowAndAutoHideDuringLyrics() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        iterations = configuredBenchmarkIterations(),
        setupBlock = { prepareLivePlayer(showLyrics = true) },
    ) {
        val ui = TvUi(device)
        repeat(2) {
            // First key wakes controls without invoking a player action.
            ui.right(0)
            ui.id("player-controls")
            assertTrue("Controller must auto-hide during real playback",
                device.wait(Until.gone(By.res("player-controls")), 8_000))
        }
        assertTrue(ui.has("lyrics-panel"))
        assertEquals("playing", PlayerUi(ui).state())
    }

    @Test fun steadyArtworkAndNativeLava() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        iterations = configuredBenchmarkIterations(),
        setupBlock = { prepareLivePlayer(showLyrics = false) },
    ) {
        val ui = TvUi(device)
        ui.settle(8_000)
        assertTrue("Artwork comparator must not auto-switch to lyrics", !ui.has("lyrics-panel"))
        assertEquals("playing", PlayerUi(ui).state())
    }

    @After fun pauseRealAudio() {
        if (!playbackStarted) return
        val ui = TvUi(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
        if (!ui.has("player-screen")) return
        val player = PlayerUi(ui)
        if (player.state() in setOf("playing", "preparing")) player.pause()
    }
}
