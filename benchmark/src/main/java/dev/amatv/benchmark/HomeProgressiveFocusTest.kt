package dev.amatv.benchmark

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One deterministic UI fixture through the actual Home loader, host and cards. */
@RunWith(AndroidJUnit4::class)
class HomeProgressiveFocusTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun lateLeadingShelfKeepsInitialTopAndDirectionalNavigation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit Home regression only", args.getString("allow_home_progressive") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Cold-feed delay is emulator-only" }
        val run = checkNotNull(args.getString("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val directory = File(checkNotNull(instrumentation.context.getExternalFilesDir(null)), "home-progressive/$run")
        check(!directory.exists() && directory.mkdirs())
        val ui = RenderingSurveyUi(instrumentation)
        val timeline = JSONArray()
        val report = JSONObject().put("run_id", run).put("start_wall_ms", System.currentTimeMillis())
            .put("validation_kind", "deterministic_ui_fixture").put("deterministic_ui_fixture", true)
            .put("real_api_verified", false)
            .put("primary_delay_ms", 2500).put("disk_feed_read_skipped", true)
            .put("cache_deleted", false).put("provider_writes", false).put("timeline", timeline)
        var failure: Throwable? = null
        try {
            ui.device.executeShellCommand("am force-stop $TARGET")
            val intent = checkNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("dev.amatv.testHomeFixture", true)
                .putExtra("dev.amatv.testColdHomeFeed", true)
                .putExtra("dev.amatv.testHomePrimaryDelayMs", 2500L)
            instrumentation.context.startActivity(intent)
            ui.await("Cold app must arrive in foreground", 15_000L) { ui.foreground() == TARGET }
            check(!ui.has("phone-sign-in-dialog")) { "Home regression must not enter an account flow" }
            if (!ui.has("browse-home")) {
                if (!ui.has("nav-panel")) ui.activate("nav-menu", "navigation")
                ui.awaitId("nav-home", 3_000L)
                ui.activate("nav-home", "navigation")
                ui.awaitGone("nav-panel")
            }
            // Real Right entry while loading; never focus a chosen card.
            if (ui.has("nav-panel") || "nav-menu" in ui.snapshot().focused)
                ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
            ui.await("First Home fixture card must arrive", 20_000L) {
                ui.requireForeground()
                ui.has("browse-home") && ui.hasPattern(Regex("shelf-0-item-0-[a-z]+"))
            }
            ui.await("Initial entry must reach the first top card without corrective focus", 3_000L) {
                focusedCard(ui)?.let { it.row == 0 && it.column == 0 } == true
            }
            val first = observation(ui)
            timeline.put(first)
            val firstLabel = first.getString("top_card_label")
            val firstTop = first.getInt("top_card_y")
            val observationsUntil = SystemClock.uptimeMillis() + 10_000L
            var lateLeadingSource = false
            var lastSignature = ""
            var lastChange = SystemClock.uptimeMillis()
            var final = observation(ui)
            while (SystemClock.uptimeMillis() < observationsUntil) {
                ui.requireForeground()
                val current = observation(ui)
                val signature = current.getString("top_card_label") + ":" + current.getInt("row_count") + ":" +
                    current.optString("focused_card") + ":" + current.getInt("top_card_y")
                if (signature != lastSignature) {
                    timeline.put(current)
                    lastSignature = signature
                    lastChange = SystemClock.uptimeMillis()
                }
                if (current.getString("top_card_label") != firstLabel) lateLeadingSource = true
                final = current
                if (lateLeadingSource && SystemClock.uptimeMillis() - lastChange >= 800L) break
                SystemClock.sleep(80L)
            }
            report.put("late_leading_source_observed", lateLeadingSource).put("after_no_input", final)
            check(lateLeadingSource) { "Deterministic fixture did not produce its delayed leading insertion" }
            val beforeKeys = checkNotNull(focusedCard(ui)) { "Late source arrival lost card focus" }
            check(beforeKeys.row == 0 && beforeKeys.column == 0) { "No-input source insertion moved focus away from the first top card: $beforeKeys" }
            check(final.getInt("first_visible_row") == 0 && final.getInt("top_card_y") >= firstTop - 16) {
                "No-input source insertion scrolled the viewport down with the early shelf"
            }
            check(rowCount(ui, 0) >= 3) { "Fixture top shelf needs three cards for this exact Right regression" }
            val moves = JSONArray()
            repeat(2) { index ->
                val expected = Card(0, index + 1)
                ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
                ui.await("Right must reach adjacent top-row card $expected", 2_000L) { focusedCard(ui) == expected }
                moves.put(observation(ui))
            }
            val prior = checkNotNull(focusedCard(ui))
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
            ui.await("Down must reach a lower fixture shelf", 3_000L) { focusedCard(ui)?.row?.let { it > prior.row } == true }
            val down = checkNotNull(focusedCard(ui))
            moves.put(observation(ui))
            val expectedRight = Card(down.row, (down.column + 1).coerceAtMost(rowCount(ui, down.row) - 1))
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
            ui.await("Right after Down must follow the actual shelf cursor", 2_000L) { focusedCard(ui) == expectedRight }
            moves.put(observation(ui))
            report.put("directional_moves", moves).put("corrective_card_focus_requests", 0).put("status", "passed")
            check(ui.device.takeScreenshot(File(directory, "completed.png")))
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed")
            report.put("error", error.message?.take(1000))
            runCatching { File(directory, "final-state.json").writeText(ui.snapshot().json(true).toString(2)) }
            runCatching { ui.device.takeScreenshot(File(directory, "final.png")) }
        } finally {
            report.put("end_wall_ms", System.currentTimeMillis())
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private data class Card(val row: Int, val column: Int)
    private fun focusedCard(ui: RenderingSurveyUi): Card? = ui.snapshot().focused.mapNotNull { id ->
        CARD.matchEntire(id)?.let { Card(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    }.singleOrNull()
    private fun rowCount(ui: RenderingSurveyUi, row: Int): Int = ui.snapshot().ids.firstNotNullOfOrNull {
        Regex("home-row-$row-count-([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toInt()
    } ?: error("No item count for focused Home shelf $row")
    private fun observation(ui: RenderingSurveyUi): JSONObject {
        val state = ui.snapshot()
        val firstCard = state.nodes.firstOrNull { CARD.matchEntire(it.id)?.groupValues?.let { groups -> groups[1] == "0" && groups[2] == "0" } == true }
        val topId = firstCard?.id.orEmpty()
        val rows = state.ids.mapNotNull { ROW.matchEntire(it)?.groupValues?.get(1)?.toInt() }
        val total = state.ids.firstNotNullOfOrNull { SHELVES.matchEntire(it)?.groupValues?.get(1)?.toInt() } ?: 0
        return JSONObject().put("uptime_ms", SystemClock.uptimeMillis()).put("row_count", total)
            .put("first_visible_row", rows.minOrNull() ?: -1).put("focused_card", state.focused.firstOrNull { CARD.matches(it) }.orEmpty())
            .put("focus", JSONArray(state.focused)).put("top_card_id", topId)
            .put("top_card_label", if (topId.isBlank()) "" else ui.label(topId))
            .put("top_card_y", firstCard?.bounds?.let(Rect::unflattenFromString)?.top ?: -10_000)
    }

    private companion object {
        val CARD = Regex("shelf-([0-9]+)-item-([0-9]+)-[a-z]+")
        val ROW = Regex("home-row-([0-9]+)-count-[0-9]+")
        val SHELVES = Regex("home-shelves-count-([0-9]+)")
    }
}
