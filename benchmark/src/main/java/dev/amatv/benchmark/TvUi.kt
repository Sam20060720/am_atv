package dev.amatv.benchmark

import android.os.SystemClock
import android.content.res.Configuration
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.util.regex.Pattern
import java.util.Locale

internal const val TARGET = "dev.amatv"
internal const val UI_TIMEOUT = 30_000L
internal val CARD_PATTERN = Pattern.compile("shelf-(\\d+)-item-(\\d+)-[a-z]+")

internal fun requirePlayerPlaybackOptIn() {
    assumeTrue("Explicit playback requires allow_player_playback=1",
        InstrumentationRegistry.getArguments().getString("allow_player_playback") == "1")
}

/** All user journeys use real key events. Wait for semantic destinations, not global animation idle. */
internal class TvUi(val device: UiDevice) {
    private val targetStrings = mutableMapOf<String, Set<String>>()

    private fun stringLabels(resourceName: String): Set<String> = targetStrings.getOrPut(resourceName) {
        val context = InstrumentationRegistry.getInstrumentation().context.createPackageContext(TARGET, 0)
        listOf(Locale.ENGLISH, Locale.TAIWAN, Locale.SIMPLIFIED_CHINESE).mapNotNull { locale ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val resources = context.createConfigurationContext(configuration).resources
            val id = resources.getIdentifier(resourceName, "string", TARGET)
            if (id == 0) null else resources.getString(id)
        }.toSet().also { require(it.isNotEmpty()) { "Missing target string: $resourceName" } }
    }

    fun homeShelf(row: Int, titleResource: String): UiObject2? {
        val titlePattern = Pattern.compile(stringLabels(titleResource).joinToString("|") { Pattern.quote(it) })
        return device.findObject(
            By.res(Pattern.compile("home-row-$row-count-\\d+")).hasDescendant(By.text(titlePattern)),
        )
    }

    init {
        // Native/Compose animation does not imply input is unsafe. Implicit idle
        // waits would stretch a 110ms DPAD burst into seconds and hide the defect.
        Configurator.getInstance().waitForIdleTimeout = 0L
        Configurator.getInstance().keyInjectionDelay = 0L
    }
    fun requireObject(selector: BySelector, timeout: Long = UI_TIMEOUT): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("Missing UI destination: $selector")
    fun id(value: String): UiObject2 = requireObject(By.res(value))
    fun has(value: String): Boolean = device.hasObject(By.res(value))
    fun settle(milliseconds: Long = 340L) = SystemClock.sleep(milliseconds)
    fun key(code: Int, pause: Long = 100L) {
        assertTrue("Key injection failed: $code", device.pressKeyCode(code))
        if (pause > 0) settle(pause)
    }
    fun down(pause: Long = 340L) = key(KeyEvent.KEYCODE_DPAD_DOWN, pause)
    fun up(pause: Long = 340L) = key(KeyEvent.KEYCODE_DPAD_UP, pause)
    fun left(pause: Long = 120L) = key(KeyEvent.KEYCODE_DPAD_LEFT, pause)
    fun right(pause: Long = 120L) = key(KeyEvent.KEYCODE_DPAD_RIGHT, pause)
    fun center(pause: Long = 340L) = key(KeyEvent.KEYCODE_DPAD_CENTER, pause)

    fun isClassic(): Boolean = has("classic-navigation")
    fun navigation(): UiObject2 = requireObject(By.res(Pattern.compile("classic-navigation|nav-menu")))
    fun enterContent(pause: Long = 0L) = key(
        if (isClassic()) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT, pause,
    )

    /** Opens the selected style's navigation through its public UI. */
    fun openNavigation() {
        requireObject(By.res(Pattern.compile("classic-navigation|nav-menu|nav-panel")))
        if (isClassic()) { id("classic-navigation"); return }
        if (!has("nav-panel")) {
            id("nav-menu")
            assertTrue("Navigation menu did not open",
                performUiAction("nav-menu", AccessibilityNodeInfo.ACTION_CLICK))
        }
        id("nav-panel")
    }

    /** Setup ends on the selected menu item; enterContent uses the style's actual key. */
    fun browseTab(tab: String) {
        repeat(5) {
            if (has("player-screen") || has("collection-screen")) {
                device.pressBack()
                settle()
            }
        }
        openNavigation()
        // Screen setup uses a real UI click. Re-reading global By.focused()
        // selectors can return an earlier accessibility snapshot and queue
        // enough directional keys to overshoot the intended tab.
        id("nav-$tab")
        assertTrue("Navigation UI action failed: $tab",
            performUiAction("nav-$tab", AccessibilityNodeInfo.ACTION_CLICK))
        val destinationId = if (tab == "nowplaying") "player-screen" else "browse-$tab"
        id(destinationId)
        if (isClassic()) id("classic-navigation") else assertTrue(
            "Selecting $tab must close the navigation menu",
            device.wait(Until.gone(By.res("nav-panel")), 5_000L))
        if (tab == "nowplaying") {
            focusUi("player-screen")
        } else {
            // A cold catalog can expose its page before a focus target exists.
            // Observe the completed selection before issuing a new menu intent;
            // this also verifies that loading eventually grants content focus.
            requireObject(By.res(destinationId).hasDescendant(By.focused(true)))
            // Reopen only for setup so callers start their content journey with
            // the style's real entry key, after the previous navigation has completed.
            openNavigation()
            focusUi("nav-$tab")
        }
    }

    /** Standard accessibility focus action for setup, including clicking the already-selected tab. */
    private fun performUiAction(id: String, action: Int): Boolean {
        @Suppress("DEPRECATION")
        fun visit(node: AccessibilityNodeInfo): Boolean = try {
            if (node.viewIdResourceName == id) node.performAction(action)
            else (0 until node.childCount).any { index -> node.getChild(index)?.let(::visit) == true }
        } finally { node.recycle() }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        return visit(root)
    }

    fun homeCard(): UiObject2 = requireObject(By.res(CARD_PATTERN).focused(true))
    fun homeRowCount(): Int = requireObject(By.res(Pattern.compile("home-shelves-count-\\d+")))
        .resourceName.substringAfterLast('-').toInt()
    fun focusedCardPosition(): Pair<Int, Int> {
        val card = homeCard()
        val name = card.resourceName
        val match = CARD_PATTERN.matcher(name)
        assertTrue("Card has no stable coordinate: $name", match.matches())
        Log.i("AMATV-UI", "focus=${match.group(1)},${match.group(2)} bounds=${card.visibleBounds}")
        return match.group(1).toInt() to match.group(2).toInt()
    }
    fun homeItemCount(row: Int): Int = requireObject(By.res(Pattern.compile("home-row-$row-count-\\d+")))
        .resourceName.substringAfterLast('-').toInt()
    fun awaitCardPosition(row: Int, column: Int, timeout: Long = 5_000L): UiObject2 =
        requireObject(By.res(Pattern.compile("shelf-$row-item-$column-[a-z]+")).focused(true), timeout)

    fun awaitFocusedRow(row: Int, timeout: Long = 5_000L): UiObject2 =
        requireObject(By.res(Pattern.compile("shelf-$row-item-\\d+-[a-z]+")).focused(true), timeout)

    fun enterHomeFirstCard() {
        // Confirming the current tab is the public root-to-top operation;
        // Home's retained viewport deliberately survives an ordinary tab switch.
        browseTab("home")
        browseTab("home")
        requireObject(By.res(Pattern.compile("home-shelves-count-\\d+")))
        assertTrue("Home needs at least one shelf", homeRowCount() > 0)
        requireObject(By.res(Pattern.compile("shelf-0-item-0-[a-z]+")))
        enterContent()
        awaitCardPosition(0, 0)
        assertEquals(0 to 0, focusedCardPosition())
    }
    /** Pin the horizontal measurement to a public chart shelf, never a changing personalized first row. */
    fun enterHomeShelf(titleResource: String, minimumCardCount: Int = 11) {
        enterHomeFirstCard()
        val rowCount = homeRowCount()
        repeat(rowCount) {
            val row = focusedCardPosition().first
            val namedRow = homeShelf(row, titleResource)
            if (namedRow != null) {
                val count = homeItemCount(row)
                assertTrue("Public chart must contain at least $minimumCardCount cards", count >= minimumCardCount)
                // A wide showcase's center can enter the second square card.
                // Reset this setup row using actual remote keys before measuring.
                repeat(focusedCardPosition().second) { left(0) }
                awaitCardPosition(row, 0)
                Log.i("AMATV-UI", "benchmark shelf=$titleResource items=$count bounds=${homeCard().visibleBounds}")
                return
            }
            if (row < rowCount - 1) {
                down(0)
                awaitFocusedRow(row + 1)
            }
        }
        throw AssertionError("Public Home shelf not available: $titleResource")
    }

    fun awaitFocused(id: String, timeout: Long = 5_000L) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.res(id))?.isFocused == true) return
            settle(50)
        }
        throw AssertionError("Input focus did not reach $id")
    }

    fun focusUi(id: String) {
        this.id(id)
        assertTrue("UI focus action failed: $id",
            device.findObject(By.res(id))?.isFocused == true ||
                performUiAction(id, AccessibilityNodeInfo.ACTION_FOCUS))
        awaitFocused(id)
    }

    /** Opens an existing player without selecting or starting a song. */
    fun openRetainedSong(): Boolean {
        browseTab("home")
        assumeTrue("SKIPPED: Apple Music account is not signed in",
            stringLabels("app_account").any { device.hasObject(By.text(it)) })
        if (has("nav-nowplaying")) {
            // Older builds may expose a ghost tab despite having no selected song.
            // Prove that it opens a real player before trusting its presence.
            performUiAction("nav-nowplaying", AccessibilityNodeInfo.ACTION_CLICK)
            if (device.wait(Until.hasObject(By.res("player-screen")), 2_000)) {
                focusUi("player-screen")
                return true
            }
            browseTab("home")
        }
        return false
    }

    fun requireRetainedPausedSong() {
        assumeTrue("SKIPPED: need a previously selected real song", openRetainedSong())
        assumeTrue("SKIPPED: retained song must already be paused", PlayerUi(this).state() == "paused")
    }

    /** Explicit fixture creation only; never called by generic setup or rendering benchmarks. */
    fun seedPlayableSong() {
        requirePlayerPlaybackOptIn()
        assumeTrue("Public-song selection requires allow_playback_seed=1",
            InstrumentationRegistry.getArguments().getString("allow_playback_seed") == "1")
        enterHomeShelf("app_top_songs", minimumCardCount = 1)
        assertTrue("Public songs shelf must contain an actual song", homeCard().resourceName.endsWith("-song"))
        center()
        id("player-screen")
        val player = PlayerUi(this)
        try {
            player.awaitState("playing", 60_000)
        } finally {
            player.pause()
        }
    }

    /** Read public accessibility state without waiting for artwork/text content to mount. */
    fun stateDescription(id: String): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        @Suppress("DEPRECATION")
        fun visit(node: AccessibilityNodeInfo): String? = try {
            if (node.viewIdResourceName == id) node.stateDescription?.toString()
            else {
                var found: String? = null
                for (index in 0 until node.childCount) {
                    found = node.getChild(index)?.let(::visit)
                    if (found != null) break
                }
                found
            }
        } finally { node.recycle() }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        return visit(root)
    }
}
