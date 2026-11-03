package dev.amatv.benchmark

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Already-open public artist page only: no playback, account writes or long catalog setup. */
@RunWith(AndroidJUnit4::class)
class ArtistRapidRemoteTest {
    @Test fun rapidSectionReversalAndMenuKeepTheActualSource() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit artist remote experiment only", args.getString("allow_artist_remote") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Artist remote regression is emulator-only" }
        args.getString("expected_hardware")?.let { check(Build.HARDWARE == it) }
        val run = checkNotNull(args.getString("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_.-]+")))
        val artistId = args.getString("artist_id") ?: "1635469693"
        val artistName = args.getString("artist_name") ?: "NewJeans"
        require(artistId.matches(Regex("[0-9]+")) && artistName.isNotBlank() && artistName.length <= 100)
        val canonicalProfile = "artist-profile-artists-$artistId"
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "artist-rapid-remote/$run")
        check(!out.exists() && out.mkdirs()) { "Do not overwrite previous evidence" }
        val ui = RenderingSurveyUi(inst)
        val started = SystemClock.uptimeMillis()
        val deadline = started + 30_000L
        val inputs = JSONArray()
        var menuInjected = false
        val result = JSONObject().put("run_id", run).put("playback_actions", 0).put("provider_writes", false)
            .put("setup", "Already-open canonical artist page; no catalog requests fabricated")
            .put("expected_artist_id", artistId).put("expected_artist_name", artistName)
            .put("runtime_budget_ms", 30_000L).put("injected_inputs", inputs)
            .put("pending_branch_coverage", "Not directly observable through public accessibility; compare real settled sources")
        val card = Regex("artist-[a-z]+-\\d+-[a-z]+")
        val source = "artist-more"
        fun waitFor(timeout: Long, predicate: () -> Boolean): Boolean {
            val end = minOf(deadline, SystemClock.uptimeMillis() + timeout)
            do {
                if (predicate()) return true
                SystemClock.sleep(60L)
            } while (SystemClock.uptimeMillis() < end)
            return false
        }
        fun artistFocus(state: SurveySnapshot): String? = state.focused.singleOrNull {
            it == source || it == "artist-play" || card.matches(it)
        }
        fun stableFocus(expected: String? = null): String? {
            var last: String? = null
            var since = 0L
            val stable = waitFor(4_000L) {
                val state = ui.snapshot()
                check(state.foreground == TARGET && "artist-screen" in state.ids &&
                    "player-screen" !in state.ids && "collection-screen" !in state.ids && "nav-panel" !in state.ids) {
                    "Artist remote input lost its visible page: ${state.focused}"
                }
                val current = artistFocus(state)
                if (current != last || current == null || "catalog-actions-list" in state.ids) {
                    last = current
                    since = SystemClock.uptimeMillis()
                    false
                } else current != null && (expected == null || current == expected) &&
                    SystemClock.uptimeMillis() - since >= 650L
            }
            return last.takeIf { stable }
        }
        fun inject(code: Int) {
            inputs.put(JSONObject().put("offset_ms", SystemClock.uptimeMillis() - started).put("key_code", code))
            ui.inject(code)
        }
        fun reversedBurst(menu: Boolean) {
            ui.requireForeground()
            // No accessibility polling or idle wait between the real key pairs.
            repeat(4) { inject(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(2) { inject(KeyEvent.KEYCODE_DPAD_UP) }
            if (menu) {
                menuInjected = true
                inject(KeyEvent.KEYCODE_MENU)
            }
        }
        try {
            assumeTrue("Open the real canonical $artistName artist page ($artistId) before this short regression",
                ui.foreground() == TARGET && ui.has("artist-screen") &&
                    ui.has(canonicalProfile) && ui.label("artist-title").equals(artistName, true) &&
                    ui.has(source) && !ui.has("catalog-actions-list") && !ui.has("nav-panel"))
            assumeTrue("Canonical artist has no loaded Top Songs within the short data window",
                waitFor(4_500L) { ui.has("artist-topsongs-0-song") })
            ui.focus(source) // Setup only. All measured handoffs below use the remote.
            result.put("initial", ui.snapshot().json(true))
            reversedBurst(menu = false)
            val expected = stableFocus()
            val control = ui.snapshot()
            if (expected == null && control.focused.any { it.startsWith("artist-section-") })
                assumeTrue("Artist reversal reached a section with no loaded identifiable card", false)
            check(expected != null) { "Rapid reversal did not settle on a real visible artist source: ${control.focused}" }
            assumeTrue("Insufficient loaded artist sections for an identifiable two-section reversal",
                card.matches(expected))
            result.put("control_settled_source", expected).put("control_source_label", ui.label(expected))
                .put("control", control.json(true))

            // Empty sections can disappear after a real response. Return by
            // observed remote steps instead of assuming a fixed catalog order.
            var returnSteps = 0
            while (source !in ui.snapshot().focused && returnSteps < 6) {
                val previous = ui.snapshot().focused
                inject(KeyEvent.KEYCODE_DPAD_UP)
                returnSteps++
                check(waitFor(1_000L) { ui.snapshot().focused != previous }) { "Up did not move the actual artist source" }
            }
            check(stableFocus(source) == source) { "Remote Up did not return to the original profile control" }
            result.put("returned_to_original_source_by_remote", true).put("return_up_steps", returnSteps)

            reversedBurst(menu = true)
            val menuOpened = waitFor(1_000L) { ui.has("catalog-actions-list") }
            result.put("menu_opened", menuOpened).put("after_burst", ui.snapshot().json(true))
            if (menuOpened) {
                // A handoff may finish before Menu is dispatched. That menu is
                // legal only if Back restores the same real source as the control.
                inject(KeyEvent.KEYCODE_BACK)
                check(waitFor(3_000L) { !ui.has("catalog-actions-list") }) { "Back did not close the observed menu" }
            }
            val actual = stableFocus(expected)
            result.put("actual_settled_source", actual ?: JSONObject.NULL).put("final", ui.snapshot().json(true))
            check(actual == expected && !ui.has("catalog-actions-list")) {
                "Rapid Menu changed the real source: expected=$expected actual=$actual; menuOpened=$menuOpened"
            }
            result.put("status", "passed_public_remote_outcome")
        } catch (skip: AssumptionViolatedException) {
            result.put("status", "skipped").put("reason", skip.message)
            throw skip
        } catch (error: Throwable) {
            result.put("status", "failed").put("error", error.stackTraceToString().take(8_000))
            runCatching { result.put("failure_snapshot", ui.snapshot().json(true)) }
            runCatching { ui.device.takeScreenshot(File(out, "failure.png")) }
            throw error
        } finally {
            // Close only a menu this test could have opened; never activate a row.
            runCatching { if (menuInjected && ui.foreground() == TARGET && ui.has("catalog-actions-list")) ui.key(KeyEvent.KEYCODE_BACK, 0L) }
            result.put("elapsed_ms", SystemClock.uptimeMillis() - started)
            File(out, "result.json").writeText(result.toString(2))
            ui.close()
        }
    }
}
