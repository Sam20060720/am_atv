package dev.amatv.benchmark

import android.os.Build
import android.os.SystemClock
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public New → real Room → exact server continuation; no playback or account writes. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class PublicRoomJourneyTest {
    @Test fun roomLoadsBeyondItsHundredItemFirstPageAndReturnsToItsHeader() = runJourney(continueRoom = true)

    @Test fun retainedRoomReturnsToItsExactHeaderAfterTabRecreation() = runJourney(continueRoom = false)

    private fun runJourney(continueRoom: Boolean) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_public_room") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val room = args.getString("room_id") ?: "6828493100"
        require(room.matches(Regex("[1-9][0-9]{0,19}")))
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)),
            "${if (continueRoom) "public-room" else "public-room-remount"}/$run")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val result = JSONObject().put("run_id", run).put("room_id", room).put("provider_writes", false)
            .put("playback_actions", 0).put("data_source", "real public New/Room/editorial continuation")
            .put("tab_recreation", !continueRoom)
        val start = SystemClock.uptimeMillis()
        val deadline = start + 55_000L
        val waits = JSONArray()
        result.put("journey_budget_ms", 55_000L).put("waits", waits)
        fun waitFor(message: String, timeout: Long, predicate: () -> Boolean) {
            val began = SystemClock.uptimeMillis()
            val end = minOf(deadline, began + timeout)
            val observation = JSONObject().put("stage", message).put("started_elapsed_ms", began - start)
                .put("phase_budget_ms", timeout).put("capped_end_elapsed_ms", end - start)
            waits.put(observation)
            var polls = 0
            while (polls == 0 || SystemClock.uptimeMillis() < end) {
                // Even an exhausted journey must read its current condition once.
                // No sleep or additional poll occurs beyond the unchanged budget.
                polls++
                val matched = predicate()
                val now = SystemClock.uptimeMillis()
                observation.put("polls", polls).put("matched", matched).put("observed_elapsed_ms", now - start)
                    .put("deadline_exhausted", now >= deadline).put("phase_deadline_exhausted", now >= end)
                if (matched) return
                if (now >= end) {
                    if (now >= deadline) error("Public Room 55s journey budget exhausted: $message (polls=$polls)")
                    error(message)
                }
                SystemClock.sleep(minOf(60L, end - now))
            }
            val now = SystemClock.uptimeMillis()
            observation.put("deadline_exhausted", now >= deadline).put("phase_deadline_exhausted", true)
            if (now >= deadline) error("Public Room 55s journey budget exhausted: $message (polls=$polls)")
            error(message)
        }
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.packageName?.toString() == TARGET && predicate(node)) return node
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val hit = walk(child)
                    if (hit != null) { node.recycle(); return hit }
                }
                node.recycle()
                return null
            }
            return inst.uiAutomation.rootInActiveWindow?.let(::walk)
        }
        fun id(node: AccessibilityNodeInfo) = node.viewIdResourceName.orEmpty().substringAfter(":id/")
        fun gridInfo(): Pair<Int, Int> = find { id(it) == "catalog-collection-body" }
            ?.let { node -> try { node.collectionInfo?.let { it.rowCount to it.columnCount } }
                finally { node.recycle() } } ?: (0 to 0)
        // This is row capacity, including the continuation row, not an invented
        // exact resource total. Item #100 below proves real appended content.
        fun capacity(): Int = gridInfo().let { it.first * it.second }
        val itemPattern = Regex("catalog-collection-item-(\\d+)-([a-z]+)")
        fun focusedItem(): String? = ui.snapshot().focused.firstOrNull(itemPattern::matches)
        fun bounds(target: String): Rect = checkNotNull(find { id(it) == target }).let { node ->
            try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
        }
        fun itemIndex(target: String): Int = checkNotNull(itemPattern.matchEntire(target)).groupValues[1].toInt()
        fun indexFocused(index: Int): Boolean = focusedItem()?.let(::itemIndex) == index
        fun currentIndex(): Int = focusedItem()?.let(::itemIndex) ?: -1
        fun snapshotGrid() = JSONObject().put("rows", gridInfo().first).put("columns", gridInfo().second)
            .put("row_capacity", capacity()).put("focus", focusedItem())
        fun selectedItem(target: String): Boolean {
            val snapshot = ui.snapshot()
            return target in snapshot.focused && target in snapshot.selected
        }
        try {
            ui.launch()
            ui.navigate("browse")
            waitFor("New editorials did not expose any real Show All header", 12_000) {
                ui.snapshot().ids.any { it.startsWith("home-shelf-public-new:") && it.endsWith("-see-all") }
            }
            var header: String? = null
            repeat(5) {
                if (header == null) {
                    header = ui.snapshot().ids.firstOrNull { value ->
                        value.startsWith("home-shelf-public-new:") && value.contains(" - $room-") && value.endsWith("-see-all")
                    }
                    if (header == null) ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 200)
                }
            }
            if (!continueRoom) {
                result.put("bounded_source_scan", ui.snapshot().json())
                assumeTrue("Exact Room header unavailable in the bounded current New scan; no substitute selected", header != null)
            }
            val source = checkNotNull(header) { "Real New page did not expose the expected room header" }
            ui.focus(source)
            val sourceLabel = ui.label(source)
            find { id(it) == source && it.isVisibleToUser && it.isEnabled && it.isClickable }?.let { node ->
                try { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) } finally { node.recycle() }
            } ?: error("Verified header is not a native navigation action")
            ui.awaitId("catalog-room-screen", 15_000)
            waitFor("Room did not expose its real first grid page", 15_000) {
                ui.has("catalog-collection-covers") && gridInfo().second >= 2 && capacity() >= 100
            }
            val initialCapacity = capacity()
            result.put("first_grid", snapshotGrid()).put("source_header", source).put("source_label", sourceLabel)
            if (continueRoom) {
                val columns = gridInfo().second
                val first = ui.snapshot().ids.firstOrNull { itemPattern.matches(it) && itemIndex(it) == 0 }
                    ?: error("Room has no actual first card")
                ui.focus(first)
                ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 120L)
                waitFor("Grid Right did not move within its first row", 3_000L) { indexFocused(1) }
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 120L)
                waitFor("Grid Down did not move to the same column in its next row", 3_000L) { indexFocused(columns + 1) }
                ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 120L)
                waitFor("Grid Left did not remain in its current row", 3_000L) { indexFocused(columns) }
                val steps = JSONArray()
                var moves = 0
                while (currentIndex() < 100 && moves < (100 / columns + 5) && SystemClock.uptimeMillis() < deadline) {
                    val before = currentIndex()
                    check(before >= 0) { "Grid lost its card owner before Down" }
                    ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100L)
                    waitFor("Vertical paging did not reach the next real card", 8_000L) { currentIndex() > before }
                    steps.put(snapshotGrid()); moves++
                }
                val target = checkNotNull(focusedItem())
                check(itemIndex(target) >= 100 && capacity() > initialCapacity) {
                    "Near-end paging did not expose an actual item beyond the original hundred"
                }
                val kind = checkNotNull(itemPattern.matchEntire(target)).groupValues[2]
                assumeTrue("The bounded real Room entry is not an Album; never activate a Song", kind == "album")
                val label = ui.label(target)
                // Let only existing focus animation settle before recording its geometry.
                ui.settle(220L)
                val originalBounds = bounds(target)
                result.put("vertical_steps", steps).put("actual_item_index_after_paging", itemIndex(target))
                    .put("after_continuation", ui.snapshot().json(true)).put("selected_card", target)
                check(selectedItem(target))
                check(ui.device.pressDPadCenter()) // Proven Album: opens its collection, never Play.
                ui.awaitId("collection-screen", 10_000L)
                result.put("child_album_title", ui.label("collection-title"))
                ui.key(KeyEvent.KEYCODE_BACK, 0L)
                waitFor("Album Back did not restore the same Room card", 6_000L) {
                    ui.has("catalog-room-screen") && !ui.has("collection-screen") && selectedItem(target)
                }
                waitFor("Album Back changed the Room viewport", 2_000L) { bounds(target) == originalBounds }
                check(ui.label(target) == label)
                result.put("same_card_and_viewport", true).put("after_child", ui.snapshot().json(true))
                ui.device.takeScreenshot(File(out, "layout.png"))
            } else {
                result.put("before_tab_change", ui.snapshot().json())
                // navigate() intentionally clears retained detail by confirming
                // the destination twice. Select each different tab once here.
                ui.openNavigation()
                ui.activate("nav-home", "navigation")
                waitFor("Home tab did not replace the Room owner", 8_000) {
                    ui.has("browse-home") && !ui.has("catalog-resource-screen") && !ui.has("nav-panel")
                }
                result.put("home_tab", ui.snapshot().json())
                ui.openNavigation()
                ui.activate("nav-browse", "navigation")
                waitFor("Browse did not retain the same real Room detail", 8_000) {
                    ui.has("catalog-room-screen") && !ui.has("nav-panel") && capacity() >= initialCapacity
                }
                result.put("retained_room", ui.snapshot().json()).put("retained_grid", snapshotGrid())
            }
            result.put("back_started_elapsed_ms", SystemClock.uptimeMillis() - start)
            ui.key(KeyEvent.KEYCODE_BACK, 0)
            result.put("back_dispatched_elapsed_ms", SystemClock.uptimeMillis() - start)
            ui.awaitGone("catalog-resource-screen")
            result.put("room_gone_elapsed_ms", SystemClock.uptimeMillis() - start)
            waitFor("Back did not restore the exact Show All header", 5_000) { source in ui.snapshot().focused }
            result.put("returned_source", ui.snapshot().json()).put("status", "passed")
        } catch (skip: AssumptionViolatedException) {
            result.put("status", "skipped_source_unavailable").put("reason", skip.message)
            throw skip
        } catch (error: Throwable) {
            result.put("status", "failed").put("error", error.stackTraceToString().take(8_000))
            runCatching { result.put("failure_snapshot", ui.snapshot().json(true)) }
            throw error
        } finally {
            result.put("elapsed_ms", SystemClock.uptimeMillis() - start)
                .put("deadline_exhausted", SystemClock.uptimeMillis() >= deadline)
            File(out, "result.json").writeText(result.toString(2))
            ui.close()
        }
    }
}
