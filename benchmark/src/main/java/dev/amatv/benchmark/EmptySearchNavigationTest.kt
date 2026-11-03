package dev.amatv.benchmark

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

/** Local history is restored by the host's cold AVD clone after this explicit case. */
@RunWith(AndroidJUnit4::class)
class EmptySearchNavigationTest {
    @Test fun emptyFieldDownReachesTheExistingScope() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allow_local_history_clear") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = requireNotNull(args.getString("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val folder = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "empty-search/$run")
        check(!folder.exists() && folder.mkdirs())
        val ui = RenderingSurveyUi(instrumentation)
        val report = JSONObject().put("provider_writes", false)
        val cases = JSONArray()
        try {
            ui.launch()
            ui.navigate("search")
            ui.clearSearchQueryForSetup()
            if (ui.has("search-history-clear")) {
                ui.focus("search-history-clear")
                ui.activate("search-history-clear", "search_history_clear")
                ui.awaitGone("search-history-clear")
                report.put("local_history_cleared", true)
            }
            // Catalog intentionally has recent-played cards on an empty query;
            // Library supplies the real no-history/no-results condition.
            for (scope in listOf("library")) {
                val target = "search-scope-$scope"
                ui.activate(target, "search_scope")
                check(!ui.has("search-result-0") && !ui.has("search-history-clear"))
                ui.focus("search-field") // Setup; measured movement is remote Down/Up.
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
                ui.await("Empty Search Down must reach its current scope", 4_000L) { target in ui.snapshot().focused }
                cases.put(JSONObject().put("scope", scope).put("after_down", ui.snapshot().json()))
                ui.key(KeyEvent.KEYCODE_DPAD_UP, 0L)
                ui.await("Scope Up must return to the field", 4_000L) { "search-field" in ui.snapshot().focused }
            }
            report.put("status", "passed").put("cases", cases)
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message?.take(500)).put("cases", cases)
            ui.device.takeScreenshot(File(folder, "failure.png"))
            throw error
        } finally {
            File(folder, "result.json").writeText(report.toString(2))
            ui.close()
        }
    }
}
