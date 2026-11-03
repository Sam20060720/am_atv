package dev.amatv.benchmark

import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The reported exact search, through actual UI and DPAD; never activates a song. */
@RunWith(AndroidJUnit4::class)
class SearchPresentationTest {
    @Test fun historyRestoresItsCachedCategoryAndClearReturnsFocusToInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_search_history") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = args.getString("run_id") ?: "history-" + System.currentTimeMillis()
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val folder = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "search-history/$run")
        check(!folder.exists() && folder.mkdirs())
        val ui = RenderingSurveyUi(instrumentation)
        val report = JSONObject().put("run_id", run).put("audio_actions", false).put("provider_mutations", false)
            .put("local_history_clear", true)
        val wantedQuery = "Ariana Grande"
        try {
            ui.launch()
            ui.search("albums", wantedQuery)
            val cachedAlbumLabel = ui.label("search-result-0")
            check(cachedAlbumLabel.isNotBlank()) { "Album search did not expose a real result" }
            ui.search("songs", "positions ariana grande")
            ui.clearSearchQueryForSetup()
            ui.awaitId("search-history")
            val choice = (0 until 3).map { "search-history-$it" }
                .firstOrNull { ui.has(it) && ui.label(it).equals(wantedQuery, ignoreCase = true) }
                ?: error("The newly submitted album query is missing from recent history")
            ui.focus(choice)
            ui.activate(choice, "search_history_query")
            ui.await("History did not restore its query and collection results", 8_000) {
                ui.label("search-field").contains(wantedQuery, ignoreCase = true) && ui.has("search-result-grid") && ui.has("search-result-0")
            }
            ui.await("History did not restore its actual cached album result", 5_000) {
                ui.label("search-result-0") == cachedAlbumLabel
            }
            ui.await("History restore did not enter its first actual result", 5_000) {
                "search-result-0" in ui.snapshot().focused
            }
            ui.key(KeyEvent.KEYCODE_DPAD_UP, 0)
            ui.await("Restored result Up must reach its Albums category", 3_000) {
                "search-category-albums" in ui.snapshot().focused
            }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Restored Albums category Down must return to its first result", 3_000) {
                "search-result-0" in ui.snapshot().focused
            }
            report.put("restored_query", wantedQuery).put("restored_category", "albums")
                .put("restored_first_result", cachedAlbumLabel).put("restore_focus", JSONArray(ui.snapshot().focused))
            check(ui.device.takeScreenshot(File(folder, "history-restored.png")))

            ui.clearSearchQueryForSetup()
            ui.awaitId("search-history-0")
            ui.focus("search-history-0")
            // Clear follows the actual history entries. Reveal it with bounded
            // remote movement, without assuming every chip is already composed.
            repeat(21) {
                if ("search-history-clear" !in ui.snapshot().focused) ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 80)
            }
            check("search-history-clear" in ui.snapshot().focused) { "History Clear is unreachable with the remote" }
            ui.activate("search-history-clear", "search_history_clear")
            ui.awaitGone("search-history")
            ui.await("Clearing history lost the persistent input focus", 3_000) { "search-field" in ui.snapshot().focused }
            ui.navigate("home"); ui.navigate("search")
            check(!ui.has("search-history")) { "Cleared history reappeared after returning to Search" }
            report.put("history_absent_after_return", true).put("clear_focus", JSONArray(ui.snapshot().focused))
                .put("status", "passed")
            check(ui.device.takeScreenshot(File(folder, "history-cleared.png")))
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message)
            runCatching { ui.device.takeScreenshot(File(folder, "failure.png")) }
            throw error
        } finally { File(folder, "result.json").writeText(report.toString(2)); ui.close() }
    }

    @Test fun songVersionsAndRemoteLayoutAreReadable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_search_presentation") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = args.getString("run_id") ?: "search-" + System.currentTimeMillis()
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val folder = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "search-presentation/$run")
        check(!folder.exists() && folder.mkdirs())
        val ui = RenderingSurveyUi(instrumentation)
        val report = JSONObject().put("run_id", run).put("audio_actions", false).put("account_mutations", false)
        try {
            ui.launch()
            args.getString("expected_current_title")?.let { title ->
                ui.pausedPlayer(queue = false)
                ui.await("Restored original recording is missing", 8_000) {
                    ui.label("player-screen").contains(title, ignoreCase = true)
                }
                check(ui.playerPaused())
                report.put("restored_track_title_verified", title).put("restored_player_paused", true)
            }
            ui.search("songs", "positions ariana grande")
            val state = ui.snapshot()
            val first = Rect.unflattenFromString(state.nodes.first { it.id == "search-result-0" }.bounds)!!
            check(first.top < ui.device.displayHeight * .45) { "Search controls still push first result below the upper half" }
            val labels = (0 until 8).map { "search-result-$it" }.filter(ui::has).map(ui::label)
            check(labels.size >= 4)
            check(labels.any { it.contains("Deluxe", true) }) { "Actual release names missing from same-title results" }
            report.put("visible_results", JSONArray(labels)).put("first_result_top", first.top)
                .put("display_height", ui.device.displayHeight)
            check(ui.device.takeScreenshot(File(folder, "search-songs.png")))

            // Some queries legitimately return no refinements after removing
            // the duplicate input. This artist query exposed actual server
            // refinements in the independent history-restore runtime proof.
            val suggestionQuery = args.getString("suggestion_query") ?: "Ariana Grande"
            ui.search("songs", suggestionQuery)
            // search() dismisses the actual IME before selecting Songs. The
            // query's real suggestions must survive that dismissal unchanged.
            ui.await("Suggestions disappeared after keyboard dismissal", 8_000) { ui.has("search-suggestion-0") }
            val suggestion = ui.label("search-suggestion-0")
            check(suggestion.isNotBlank()) { "Persistent suggestion has no readable label" }
            ui.focus("search-field")
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            ui.await("Readonly field Right must reach adjacent scope", 3_000) { "search-scope-catalog" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Scope Down must reach persistent suggestions", 3_000) { "search-suggestion-0" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Suggestion Down must reach song category", 3_000) { "search-category-songs" in ui.snapshot().focused }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            ui.await("Category Down must enter actual first result", 8_000) { "search-result-0" in ui.snapshot().focused }
            ui.settle(400)
            check(ui.has("search-suggestion-0") && ui.label("search-suggestion-0") == suggestion) {
                "Suggestions changed or disappeared while navigating settled results"
            }
            report.put("suggestion_query", suggestionQuery).put("persistent_suggestion", suggestion)
                .put("keyboard_dismissal_preserved_suggestions", true)
                .put("remote", "field-right-scope-down-suggestion-down-category-down-result")
                .put("status", "passed")
            check(ui.device.takeScreenshot(File(folder, "search-suggestions.png")))
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message)
            runCatching { ui.device.takeScreenshot(File(folder, "failure.png")) }
            throw error
        } finally { File(folder, "result.json").writeText(report.toString(2)); ui.close() }
    }
}
