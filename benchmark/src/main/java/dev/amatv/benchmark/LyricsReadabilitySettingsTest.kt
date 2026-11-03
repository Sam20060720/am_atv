package dev.amatv.benchmark

import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit paused-app process restart; no playback, track, account or cloud mutation. */
@RunWith(AndroidJUnit4::class)
class LyricsReadabilitySettingsTest {
    @Test fun largerTextChoiceSurvivesProcessRestartAndRestoresItsPriorValue() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allow_lyrics_readability_restart") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val survey = RenderingSurveyUi(inst)
        val ui = TvUi(survey.device)
        val player = PlayerUi(ui)
        var original: Boolean? = null
        var changed = false
        fun choices() {
            if (survey.has("lyrics-larger-pronunciation")) return
            if (!survey.has("lyrics-larger-text")) {
                player.focusControl("player-lyrics-display")
                ui.center(150)
            }
            survey.focus("lyrics-larger-text")
            assertTrue("Larger Text must own focus before activation",
                "lyrics-larger-text" in survey.snapshot().focused)
            ui.center(150)
            survey.awaitId("lyrics-larger-pronunciation")
        }
        fun selected(pronunciation: Boolean): Boolean {
            val id = if (pronunciation) "lyrics-larger-pronunciation" else "lyrics-larger-lyrics"
            val node = checkNotNull(survey.device.findObject(By.res(id)))
            return node.isSelected || node.isChecked
        }
        fun select(pronunciation: Boolean) {
            choices()
            val id = if (pronunciation) "lyrics-larger-pronunciation" else "lyrics-larger-lyrics"
            survey.focus(id)
            assertTrue("The intended Larger Text choice must own focus before activation",
                id in survey.snapshot().focused)
            ui.center(150)
            survey.awaitGone("lyrics-larger-pronunciation")
            choices()
            assertTrue("Choice must be adopted by the existing settings UI", selected(pronunciation))
            survey.key(KeyEvent.KEYCODE_BACK, 150)
            survey.awaitGone("lyrics-larger-pronunciation")
        }
        try {
            survey.launch()
            ui.browseTab("nowplaying")
            assumeTrue("Only a retained paused song may be restarted", player.state() == "paused")
            player.wakeControls()
            assumeTrue("Retained item must support lyrics settings", survey.has("player-lyrics-display"))
            val artwork = survey.label("player-artwork").also { check(it.isNotBlank()) }
            choices()
            val baseline = selected(true)
            original = baseline
            assertTrue("Exactly one native Larger Text choice must be selected", baseline != selected(false))
            survey.key(KeyEvent.KEYCODE_BACK, 150)
            changed = true // Cleanup also owns a dispatch whose acknowledgement fails.
            select(!baseline)
            // Leaving the activity lets Android drain queued preference writes;
            // the subsequent forced process boundary proves more than remember state.
            survey.device.pressHome()
            survey.await("Target did not enter the background", 5_000) { survey.foreground() != TARGET }
            val beforePid = survey.device.executeShellCommand("pidof $TARGET").trim()
            survey.device.executeShellCommand("am force-stop $TARGET")
            survey.await("Owned paused app process did not stop", 5_000) {
                survey.device.executeShellCommand("pidof $TARGET").isBlank()
            }
            survey.launch()
            ui.browseTab("nowplaying")
            val afterPid = survey.device.executeShellCommand("pidof $TARGET").trim()
            check(beforePid.isNotBlank() && afterPid.isNotBlank() && beforePid != afterPid)
            assertEquals("No playback action may be introduced by readability", "paused", player.state())
            assertEquals("Process restart must retain the same song", artwork, survey.label("player-artwork"))
            choices()
            assertTrue("Preference must be read back in the new process", selected(!baseline))
            Log.i("AMATV-readability", "persisted choice=${!baseline} across pid $beforePid -> $afterPid; same paused artwork")
        } finally {
            try {
                if (changed) original?.let { prior ->
                    survey.launch()
                    if (!survey.has("player-screen")) ui.browseTab("nowplaying")
                    select(prior)
                    assertEquals("Cleanup must leave playback paused", "paused", player.state())
                }
            } finally { survey.close() }
        }
    }
}
