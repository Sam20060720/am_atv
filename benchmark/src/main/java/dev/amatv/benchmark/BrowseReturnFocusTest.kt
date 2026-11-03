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

/** Resume existing playback UI only: never starts a song or changes the user's queue. */
@RunWith(AndroidJUnit4::class)
class BrowseReturnFocusTest {
    @Test fun existingPlayerReturnsToEachActualSourceControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit return-navigation experiment only", args.getString("allow_return_focus") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Return-navigation experiment is emulator-only" }
        val runId = args.getString("run_id") ?: "return-focus-" + System.currentTimeMillis()
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        val folder = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "return-focus/$runId")
        check(!folder.exists() && folder.mkdirs()) { "Do not overwrite an earlier return experiment" }
        val ui = RenderingSurveyUi(instrumentation)
        val cases = JSONArray()
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("account_mutations", false).put("cases", cases)
        try {
            ui.launch()
            // A non-leading card distinguishes exact restoration from first-item fallback.
            val first = ui.firstShelf("home")
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
            ui.await("Need a second Home card", 4_000L) {
                ui.snapshot().focused.any { it != first && Regex("shelf-0-item-\\d+-[a-z]+").matches(it) }
            }
            ui.settle(400L)
            verifyReturn(ui, "home", cases)

            ui.navigate("library")
            ui.focus("library-category-albums")
            verifyReturn(ui, "library-category", cases)

            ui.search("albums", "Positions Ariana Grande")
            ui.awaitId("search-result-0", 20_000L)
            ui.focus("search-result-0")
            verifyReturn(ui, "search-result", cases)

            // Opening a canonical public album is navigation, never an audio action.
            val canonical = (0 until 12).map { "search-result-$it" }.firstOrNull { id ->
                ui.has(id) && ui.label(id).contains("Positions (Deluxe)", true) &&
                    ui.label(id).contains("Ariana Grande", true)
            } ?: error("Canonical album not present in visible search results")
            ui.activate(canonical, "editorial_album")
            ui.awaitId("collection-more", 10_000L)
            verifyDetailTopReveal(ui, report)
            ui.focus("collection-more")
            verifyReturn(ui, "collection-control", cases)
            ui.key(KeyEvent.KEYCODE_BACK, 0L)
            ui.awaitGone("collection-screen")
            ui.await("Album Back must restore the clicked search source", 5_000L) { canonical in ui.snapshot().focused }
            ui.settle(700L)
            check(canonical in ui.snapshot().focused && !ui.has("nav-panel")) { "Search source lost focus after detail Back" }
            report.put("detail_back_source", canonical).put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(folder, "failure.png")) }
            throw error
        } finally {
            File(folder, "result.json").writeText(report.toString(2))
            ui.close()
        }
    }

    /** Process recreation discards every old requester; exercise the real first-target fallback. */
    @Test fun recreatedPlayerReturnsThroughFirstContentFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit return-navigation experiment only", args.getString("allow_return_focus") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Process-recreation experiment is emulator-only" }
        val runId = args.getString("run_id") ?: "return-fallback-" + System.currentTimeMillis()
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        val folder = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "return-focus/$runId")
        check(!folder.exists() && folder.mkdirs()) { "Do not overwrite an earlier return experiment" }
        val ui = RenderingSurveyUi(instrumentation)
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("account_mutations", false).put("queue_mutations", false).put("corrective_focus_requests", 0)
        try {
            ui.requireForeground()
            check(ui.has("catalog-search") && !ui.has("collection-screen") && !ui.has("artist-screen") && !ui.has("player-screen")) {
                "Fallback setup requires the current real Search page; no source state is fabricated"
            }
            ui.openNavigation()
            check(ui.has("nav-nowplaying")) { "Requires an existing player; no song will be started" }
            ui.activate("nav-nowplaying", "navigation")
            ui.awaitId("player-screen", 8_000L)
            check(ui.playerPaused()) { "Force-stop is permitted only for already paused playback; no pause is injected" }
            report.put("source_container", "catalog-search").put("before_restart_state", ui.playerStateDescription())
            // The product saves after 350 ms. Allow its normal debounce plus a
            // disk-write buffer; verify persistence only by actual restored UI.
            // This also works for the nondebuggable standalone APK.
            ui.settle(1_500L)
            report.put("checkpoint_wait_ms", 1_500L)
            check(ui.playerPaused()) { "Playback changed before force-stop; leave the user's audio untouched" }
            val oldPid = ui.device.executeShellCommand("pidof dev.amatv").trim()
            check(oldPid.isNotBlank()) { "Target process identity was unavailable" }
            ui.device.executeShellCommand("am force-stop dev.amatv")
            ui.await("Target process did not stop", 5_000L) { ui.device.executeShellCommand("pidof dev.amatv").trim().isBlank() }
            ui.launch()
            // No fallback navigation or test focus request is allowed here. A
            // different restored page is a checkpoint failure, not setup success.
            ui.awaitId("player-screen", 12_000L)
            ui.await("Restored player must remain paused", 8_000L) { ui.playerPaused() }
            val newPid = ui.device.executeShellCommand("pidof dev.amatv").trim()
            check(newPid.isNotBlank() && newPid != oldPid) { "The player did not come from a new process" }
            report.put("old_pid", oldPid).put("new_pid", newPid).put("restored_state", ui.playerStateDescription())
            report.put("back_steps", ui.leavePlayerWithBack())
            ui.awaitId("catalog-search", 8_000L)
            ui.await("Recreated Search must receive a valid first content target", 6_000L) {
                val state = ui.snapshot()
                "player-screen" !in state.ids && "nav-panel" !in state.ids &&
                    state.focused.any { it.startsWith("search-") } && state.focused.none { it.startsWith("nav-") }
            }
            ui.settle(800L)
            val fallback = ui.snapshot()
            val initial = fallback.focused.singleOrNull { it.startsWith("search-") }
                ?: error("Recreated Search has no unique valid fallback focus")
            check("nav-panel" !in fallback.ids && fallback.focused.none { it.startsWith("nav-") }) { "Fallback drifted into navigation" }
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
            ui.await("A real Down must move the recreated content focus", 6_000L) {
                val state = ui.snapshot()
                initial !in state.focused && "nav-panel" !in state.ids &&
                    state.focused.any { it.startsWith("search-") } && state.focused.none { it.startsWith("nav-") }
            }
            ui.settle(500L)
            val moved = ui.snapshot()
            check(initial !in moved.focused && "nav-panel" !in moved.ids && moved.focused.any { it.startsWith("search-") }) {
                "Recreated Search lost its content owner after Down"
            }
            report.put("fallback", fallback.json()).put("remote_input", "DPAD_DOWN").put("after_down", moved.json()).put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(folder, "failure.png")) }
            throw error
        } finally {
            File(folder, "result.json").writeText(report.toString(2))
            ui.close()
        }
    }

    private fun verifyReturn(ui: RenderingSurveyUi, label: String, cases: JSONArray) {
        val before = ui.snapshot()
        val source = before.focused.singleOrNull { !it.startsWith("nav-") }
            ?: error("$label has no unique actual content focus")
        val bounds = before.nodes.first { it.id == source }.bounds
        val entry = JSONObject().put("case", label).put("source", source).put("before_bounds", bounds)
        cases.put(entry)
        ui.openNavigation()
        check(ui.has("nav-nowplaying")) { "Requires an existing player; no song is started for this test" }
        ui.activate("nav-nowplaying", "navigation")
        ui.awaitId("player-screen", 8_000L)
        entry.put("existing_playback_state", ui.playerStateDescription())
        // Existing playback is observed only, never paused, resumed or reseeded.
        entry.put("back_steps", ui.leavePlayerWithBack())
        ui.awaitGone("player-screen")
        ui.await("$label did not naturally regain $source", 5_000L) { source in ui.snapshot().focused }
        ui.settle(800L) // Catch the previous 200ms pill-dwell reopening defect.
        val after = ui.snapshot()
        check(source in after.focused && "nav-panel" !in after.ids) { "$label drifted into navigation after Back" }
        val afterBounds = after.nodes.first { it.id == source }.bounds
        check(bounds == afterBounds) { "$label changed its retained viewport: $bounds -> $afterBounds" }
        entry.put("after", after.json()).put("after_bounds", afterBounds)
        if (label != "collection-control") {
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
            ui.await("$label Right must move the naturally restored content focus", 8_000L) {
                val moved = ui.snapshot()
                source !in moved.focused && "nav-panel" !in moved.ids &&
                    moved.focused.any { target -> when (label) {
                        "home" -> Regex("shelf-\\d+-item-\\d+-[a-z]+").matches(target)
                        "library-category" -> target.startsWith("library-item-") || target.startsWith("library-sort-") ||
                            target.startsWith("library-filter") || target.startsWith("library-retry")
                        "search-result" -> target.startsWith("search-result-")
                        else -> false
                    } }
            }
            ui.settle(350L)
            val moved = ui.snapshot()
            check(source !in moved.focused && "nav-panel" !in moved.ids) { "$label Right lost its content owner" }
            entry.put("right_input", "DPAD_RIGHT").put("after_right", moved.json())
        }
        entry.put("status", "passed")
    }
    private fun verifyDetailTopReveal(ui: RenderingSurveyUi, report: JSONObject) {
        ui.focus("collection-play") // Setup only; the measured return below uses DPAD.
        ui.settle(450L)
        val initialTitle = ui.snapshot().nodes.firstOrNull { it.id == "collection-title" }?.bounds
            ?: error("Canonical detail has no initial title bounds")
        ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
        ui.await("Detail Down must enter song zero", 4_000L) { "collection-song-0" in ui.snapshot().focused }
        repeat(4) { ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100L) }
        ui.await("Detail Down must reach song four", 4_000L) { "collection-song-4" in ui.snapshot().focused }
        repeat(4) { ui.key(KeyEvent.KEYCODE_DPAD_UP, 100L) }
        ui.await("Detail Up must return to song zero", 4_000L) { "collection-song-0" in ui.snapshot().focused }
        ui.key(KeyEvent.KEYCODE_DPAD_UP, 0L)
        ui.await("Detail Up must restore Play and the original complete title position", 6_000L) {
            val state = ui.snapshot()
            "collection-play" in state.focused && state.nodes.any { it.id == "collection-title" && it.bounds == initialTitle }
        }
        ui.settle(500L)
        val after = ui.snapshot()
        check("collection-play" in after.focused && after.nodes.any { it.id == "collection-title" && it.bounds == initialTitle }) {
            "Detail header drifted after returning to Play"
        }
        report.put("detail_top_reveal", JSONObject().put("initial_title_bounds", initialTitle)
            .put("restored_title_bounds", after.nodes.first { it.id == "collection-title" }.bounds)
            .put("down_target", "collection-song-4").put("return_focus", "collection-play")
            .put("corrective_focus_requests", 0).put("status", "passed"))
    }

}
