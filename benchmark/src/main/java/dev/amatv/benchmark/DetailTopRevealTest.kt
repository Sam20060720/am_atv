package dev.amatv.benchmark

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real artist navigation and biography layout, without playback or provider writes. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DetailTopRevealTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi

    @Test fun artistReturnsToItsExactTitleAndExpandedBiographyRetainsVisibleFocus() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_detail_top") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = checkNotNull(args.getString("run_id")); require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val folder = File(checkNotNull(instrumentation.context.getExternalFilesDir(null)), "detail-top/$run")
        check(!folder.exists() && folder.mkdirs())
        val report = JSONObject().put("run_id", run).put("provider_writes", false).put("audio_operations", false)
            .put("artist_id", ARTIST_ID).put("corrective_focus_during_down_up", false)
        ui = RenderingSurveyUi(instrumentation)
        var failure: Throwable? = null
        try {
            ui.launch()
            ui.search("artists", ARTIST)
            val result = (0 until 8).map { "search-result-$it" }.firstOrNull { id ->
                ui.has(id) && withNode(id) { exactText(it, ARTIST) }
            } ?: error("Canonical artist name was absent from the visible real artist results")
            ui.focus(result)
            center(result)
            ui.awaitId("artist-profile-artists-$ARTIST_ID", 15_000L)
            ui.await("Artist profile must load its actual sections", 20_000L) {
                ui.hasPattern(Regex("artist-section-[a-z]+"))
            }
            val profileControl = listOf("artist-more", "artist-biography-expand", "artist-play").firstOrNull(ui::has)
                ?: error("No real artist profile control is available")
            // Setup only. Every movement in the measured return below is DPAD.
            ui.focus(profileControl)
            if (ui.has(BIO) && biographyMode() == "expanded") { ui.focus(BIO); center(BIO); ui.await("Collapse biography", 5_000L) { biographyMode() == "collapsed" }; ui.focus(profileControl) }
            val title = stableTitle()
            check(bounds("artist-content").contains(title)) { "Initial artist title is clipped" }
            report.put("collapsed_title_bounds", title.flattenToString())
            val path = JSONArray()
            repeat(3) {
                if (it == 0 || boundsOrNull("artist-title") == title) {
                    val before = ui.snapshot().focused
                    ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
                    ui.await("Down must enter a different real artist section", 6_000L) {
                        val focus = ui.snapshot().focused
                        focus != before && focus.any(::sectionTarget)
                    }
                    path.put(ui.snapshot().json())
                }
            }
            check(boundsOrNull("artist-title") != title) { "Artist Down did not create a scrolled title context" }
            var upCount = 0
            while (ui.snapshot().focused.none { it in PROFILE_CONTROLS } && upCount < 6) {
                val before = ui.snapshot().focused
                ui.key(KeyEvent.KEYCODE_DPAD_UP, 0L)
                ui.await("Up must move through actual artist sections toward profile", 6_000L) {
                    val focus = ui.snapshot().focused
                    focus != before && focus.any { it in PROFILE_CONTROLS || sectionTarget(it) }
                }
                upCount++
                path.put(ui.snapshot().json())
            }
            check(ui.snapshot().focused.any { it in PROFILE_CONTROLS }) { "Remote Up did not reach a profile control" }
            ui.await("Remote Up must restore the complete title at its exact initial position", 5_000L) {
                boundsOrNull("artist-title") == title && bounds("artist-content").contains(title)
            }
            report.put("top_reveal", "passed").put("remote_path", path).put("up_count", upCount)
            if (!ui.has(BIO)) {
                report.put("biography", "not_exercised_missing_long_biography").put("status", "partial_missing_long_biography")
                throw AssumptionViolatedException("Top reveal passed; real artist data has no long biography expand control")
            }
            ui.focus(BIO) // Explicit setup for the independent dynamic-header case.
            check(biographyMode() == "collapsed")
            center(BIO)
            ui.await("Expanded biography must retain its focused button inside the viewport", 6_000L) {
                biographyMode() == "expanded" && BIO in ui.snapshot().focused &&
                    boundsOrNull(BIO)?.let { bounds("artist-content").contains(it) } == true
            }
            report.put("expanded_button_bounds", bounds(BIO).flattenToString()).put("expanded_focus", ui.snapshot().json())
            center(BIO)
            ui.await("Collapsing biography must keep the same focused button visible", 5_000L) {
                biographyMode() == "collapsed" && BIO in ui.snapshot().focused &&
                    boundsOrNull(BIO)?.let { bounds("artist-content").contains(it) } == true
            }
            report.put("biography", "passed_real_expand_and_collapse").put("status", "passed")
            check(ui.device.takeScreenshot(File(folder, "completed.png")))
        } catch (error: Throwable) {
            failure = error
            if (error !is AssumptionViolatedException) report.put("status", "failed")
            report.put("error", error.message?.take(1000))
            runCatching { ui.device.takeScreenshot(File(folder, "final.png")) }
        } finally {
            runCatching { File(folder, "final-state.json").writeText(ui.snapshot().json(true).toString(2)) }
            File(folder, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun sectionTarget(id: String): Boolean = id.startsWith("artist-section-") || Regex("artist-[a-z]+-[0-9]+-[a-z]+").matches(id)
    private fun biographyMode(): String? {
        val text = ui.label(BIO)
        return when {
            ui.stringLabels("artist_show_less").any(text::contains) -> "expanded"
            ui.stringLabels("artist_read_more").any(text::contains) -> "collapsed"
            else -> null
        }
    }
    private fun boundsOrNull(id: String): Rect? = ui.snapshot().nodes.firstOrNull { it.id == id }?.bounds?.let(Rect::unflattenFromString)
    private fun bounds(id: String): Rect = checkNotNull(boundsOrNull(id)) { "Missing actual visible bounds: $id" }
    private fun stableTitle(): Rect {
        var previous: Rect? = null
        var unchangedSince = SystemClock.uptimeMillis()
        ui.await("Artist title must settle before the remote return", 5_000L) {
            val value = boundsOrNull("artist-title")
            if (value != previous) { previous = value; unchangedSince = SystemClock.uptimeMillis() }
            value != null && SystemClock.uptimeMillis() - unchangedSince >= 320L
        }
        return checkNotNull(previous)
    }
    private fun center(id: String) {
        ui.requireForeground()
        check(id in ui.snapshot().focused)
        check(id == BIO || (id.matches(Regex("search-result-[0-9]+")) && ui.searchProof == "artists" && withNode(id) { exactText(it, ARTIST) }))
        withNode(id) { check(it.isEnabled) }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) check(instrumentation.uiAutomation.injectInputEvent(
            KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD), true))
    }
    private fun exactText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString() == text) return true
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            try { if (exactText(child, text)) return true } finally { child.recycle() }
        }
        return false
    }
    private fun <T> withNode(id: String, body: (AccessibilityNodeInfo) -> T): T {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.viewIdResourceName?.substringAfter(":id/") == id) return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { find(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val node = try { check(root.packageName?.toString() == TARGET); find(root) } finally { root.recycle() }
            ?: error("Missing own-app node $id")
        return try { body(node) } finally { node.recycle() }
    }
    private companion object {
        const val ARTIST = "Ariana Grande"
        // https://music.apple.com/us/artist/ariana-grande/412778295
        const val ARTIST_ID = "412778295"
        const val BIO = "artist-biography-expand"
        val PROFILE_CONTROLS = setOf("artist-more", "artist-play", BIO)
    }
}
