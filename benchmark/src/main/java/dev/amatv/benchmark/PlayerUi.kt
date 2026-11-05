package dev.amatv.benchmark

import android.os.SystemClock
import android.content.res.Configuration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import java.util.Locale

/** Reads actual player state through Android accessibility; no private playback command channel. */
internal class PlayerUi(private val ui: TvUi) {
    private val stateLabels by lazy {
        val context = InstrumentationRegistry.getInstrumentation().context.createPackageContext(TARGET, 0)
        buildMap {
            for (locale in listOf(Locale.ENGLISH, Locale.TAIWAN, Locale.SIMPLIFIED_CHINESE)) {
                val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
                val resources = context.createConfigurationContext(configuration).resources
                for (state in listOf("playing", "preparing", "paused")) {
                    val id = resources.getIdentifier("player_state_$state", "string", TARGET)
                    if (id != 0) put(resources.getString(id), state)
                }
            }
        }
    }
    fun state(): String? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        @Suppress("DEPRECATION")
        fun read(node: AccessibilityNodeInfo): String? = try {
            if (node.viewIdResourceName == "player-screen") node.stateDescription?.toString()
            else {
                var found: String? = null
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    found = read(child)
                    if (found != null) break
                }
                found
            }
        } finally { node.recycle() }
        val label = read(root)
        return stateLabels[label] ?: label
    }
    fun awaitState(expected: String, timeout: Long = UI_TIMEOUT) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (state() == expected) return
            ui.settle(150)
        }
        throw AssertionError("Player did not reach state=$expected; last state=${state()}")
    }
    fun wakeControls() {
        if (!ui.has("player-controls")) { ui.right(350) }
        ui.id("player-controls")
    }
    private fun isControlFocused(id: String): Boolean {
        val node = ui.device.findObject(By.res(id)) ?: return false
        // The virtual cursor has its own focus semantics; selected is toggle state.
        return node.isFocused && ui.device.findObject(By.res("player-screen"))?.isFocused == true
    }

    fun focusControl(id: String) {
        wakeControls()
        // This helper is used from the controls zone; a freshly opened player
        // starts there and a hidden player returns there on its first key.
        repeat(7) {
            if (!isControlFocused(id)) ui.left(55)
        }
        repeat(7) {
            if (!isControlFocused(id)) ui.right(80)
        }
        assertTrue("Lost virtual player focus: $id", isControlFocused(id))
    }
    fun play() {
        focusControl("player-play-pause")
        if (state() != "playing") ui.center()
        awaitState("playing")
    }
    fun pause() {
        wakeControls()
        focusControl("player-play-pause")
        if (state() != "paused") ui.center()
        awaitState("paused")
    }
}
