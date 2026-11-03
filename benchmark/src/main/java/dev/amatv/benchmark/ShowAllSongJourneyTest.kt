package dev.amatv.benchmark

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
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

/** One actual Home collection. No Song Center, playback, queue edits or cloud writes. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ShowAllSongJourneyTest {
    @Test fun recentSongsUseVerticalRowsAndDetailsBackRetainsTheSameViewport() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_show_all_ux") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "show-all-ux/$run-songs")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val start = SystemClock.uptimeMillis()
        val deadline = start + 75_000L
        val report = JSONObject().put("run_id", run).put("provider_writes", false).put("playback_actions", 0)
        fun waitFor(message: String, timeout: Long, condition: () -> Boolean) {
            val end = minOf(deadline, SystemClock.uptimeMillis() + timeout)
            do {
                if (condition()) return
                if (SystemClock.uptimeMillis() >= end) error(message)
                SystemClock.sleep(60L)
            } while (true)
        }
        fun find(id: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.packageName?.toString() == TARGET && node.isVisibleToUser &&
                    node.viewIdResourceName.orEmpty().substringAfter(":id/") == id) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                    walk(child)?.let { hit -> node.recycle(); return hit }
                }
                node.recycle(); return null
            }
            return inst.uiAutomation.rootInActiveWindow?.let(::walk)
        }
        fun clickReadOnly(id: String) {
            require(id.endsWith("-insights-recenttracks") || id == "catalog-action-details")
            val root = checkNotNull(find(id)) { "Missing exact read-only action: $id" }
            fun click(node: AccessibilityNodeInfo): Boolean {
                if (node.isEnabled && node.isVisibleToUser && node.isClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                    try { if (click(child)) return true } finally { child.recycle() }
                }
                return false
            }
            try { check(click(root)) { "Exact read-only action did not activate: $id" } } finally { root.recycle() }
        }
        fun bounds(id: String): Rect = checkNotNull(find(id)).let { node ->
            try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
        }
        try {
            ui.launch(); ui.navigate("home")
            waitFor("Home shelves did not arrive", 15_000L) { ui.hasPattern(Regex("home-shelves-count-\\d+")) }
            var header: String? = null
            for (step in 0 until 20) {
                header = ui.snapshot().ids.firstOrNull {
                    it.startsWith("home-shelf-") && it.endsWith("-insights-recenttracks")
                }
                if (header != null) break
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 150L)
            }
            assumeTrue("This account exposes no recent Songs shelf in the bounded Home scan", header != null)
            val source = checkNotNull(header)
            ui.focus(source); clickReadOnly(source)
            waitFor("Recent Songs did not open its actual vertical collection", 15_000L) {
                ui.has("listening-insights") && ui.has("catalog-collection-songs") &&
                    ui.has("catalog-collection-item-0-song") && ui.has("catalog-collection-item-1-song")
            }
            val title = ui.label("insights-title")
            check(title in (ui.stringLabels("app_recent_tracks") + ui.stringLabels("insights_recent_tracks"))) {
                "Show All lost its Home source title: $title"
            }
            check(ui.snapshot().ids.none { it.startsWith("insights-source-") }) { "Unrelated source rail remained" }
            val body = checkNotNull(find("catalog-collection-body"))
            val rows = try {
                val info = checkNotNull(body.collectionInfo)
                check(info.columnCount == 1) { "Songs are still arranged as a horizontal shelf" }
                info.rowCount
            } finally { body.recycle() }
            val first = bounds("catalog-collection-item-0-song")
            val second = bounds("catalog-collection-item-1-song")
            check(second.top > first.top && first.width() > first.height() * 2)
            ui.focus("catalog-collection-item-0-song")
            val targetIndex = minOf(8, (rows - 2).coerceAtLeast(1))
            val navigation = JSONArray()
            for (index in 1..targetIndex) {
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 110L)
                val expected = "catalog-collection-item-$index-song"
                waitFor("Down did not reach the next actual Song row: $index", 3_000L) { expected in ui.snapshot().focused }
                navigation.put(expected)
            }
            val target = "catalog-collection-item-$targetIndex-song"
            val sourceLabel = ui.label(target)
            val originalBounds = bounds(target)
            val visibleBefore = ui.snapshot().ids.filter { it.startsWith("catalog-collection-item-") }.sorted()
            report.put("title", title).put("source_header", source).put("navigation", navigation)
                .put("selected_row", target).put("selected_label", sourceLabel)
                .put("row_bounds", originalBounds.flattenToString()).put("before_child", ui.snapshot().json(true))
            ui.inject(KeyEvent.KEYCODE_MENU)
            val menuDeadline = minOf(deadline, SystemClock.uptimeMillis() + 6_000L)
            val menuSteps = JSONArray()
            report.put("menu_navigation", menuSteps)
            waitFor("Song Menu did not acquire a real action owner", 3_000L) {
                ui.snapshot().focused.any { it.startsWith("catalog-action-") }
            }
            // Details is below the first lazy-menu viewport. Reveal it by real
            // DPAD only; never activate an intervening mutation or Song action.
            while (!ui.has("catalog-action-details") && SystemClock.uptimeMillis() < menuDeadline) {
                val before = ui.snapshot().focused.singleOrNull { it.startsWith("catalog-action-") }
                    ?: error("Song Menu lost its action owner")
                menuSteps.put(before)
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 60L)
                waitFor("Song Menu Down did not leave $before", minOf(1_000L,
                    (menuDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))) {
                    ui.snapshot().focused.any { it.startsWith("catalog-action-") && it != before }
                }
            }
            check(ui.has("catalog-action-details")) { "Song Menu did not expose its existing Details action" }
            clickReadOnly("catalog-action-details")
            waitFor("Details did not open a real metadata child", 10_000L) { ui.has("catalog-resource-screen") }
            report.put("child", ui.snapshot().json())
            ui.key(KeyEvent.KEYCODE_BACK, 0L)
            waitFor("Details Back did not restore the same Song row", 8_000L) {
                ui.has("listening-insights") && !ui.has("catalog-resource-screen") && target in ui.snapshot().focused
            }
            check(ui.label(target) == sourceLabel)
            check(bounds(target) == originalBounds) { "Song Back changed the retained viewport" }
            check(ui.snapshot().ids.filter { it.startsWith("catalog-collection-item-") }.sorted() == visibleBefore)
            report.put("after_child", ui.snapshot().json(true)).put("same_row_and_viewport", true)
            ui.device.takeScreenshot(File(out, "layout.png"))
            ui.key(KeyEvent.KEYCODE_BACK, 0L)
            waitFor("Show All Back did not restore its exact Home heading", 6_000L) {
                !ui.has("listening-insights") && source in ui.snapshot().focused
            }
            report.put("returned_source", ui.snapshot().json()).put("status", "passed")
        } catch (skip: AssumptionViolatedException) {
            report.put("status", "skipped_source_unavailable").put("reason", skip.message); throw skip
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.stackTraceToString().take(6_000))
            runCatching { report.put("failure_snapshot", ui.snapshot().json(true)) }
            runCatching { ui.device.takeScreenshot(File(out, "failure.png")) }
            throw error
        } finally {
            report.put("elapsed_ms", SystemClock.uptimeMillis() - start)
            File(out, "result.json").writeText(report.toString(2)); ui.close()
        }
    }
}
