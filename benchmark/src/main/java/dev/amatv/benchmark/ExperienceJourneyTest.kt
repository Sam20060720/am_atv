package dev.amatv.benchmark

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real UI only; all provider writes are excluded, including favorites and playlists. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ExperienceJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi
    private lateinit var player: PlayerUi
    private lateinit var evidence: File
    private val stages = JSONArray()
    private val modalTransportProofs = JSONArray()
    private var appPid = ""
    private var startedWall = 0L
    private var oldAccessibilityFlags: Int? = null

    @Test fun historyQueueNaturalHandoffAndForegroundRestoration() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit emulator journey only", args.getString("allow_experience_journey") == "1")
        check(args.getString("avd_recovery_ready") == "1") { "Exact host AVD recovery clone is required" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "This local Queue experiment is emulator-only" }
        val runId = args.getString("run_id") ?: "experience-${System.currentTimeMillis()}"
        require(runId.matches(Regex("[A-Za-z0-9_.-]+")))
        evidence = File(checkNotNull(instrumentation.context.getExternalFilesDir(null)), "experience-journey/$runId")
        check(!evidence.exists() && evidence.mkdirs()) { "Never overwrite previous evidence" }
        startedWall = System.currentTimeMillis()
        val firstStage = args.getString("start_stage") ?: JOURNEY_STAGES.first()
        val firstStageIndex = JOURNEY_STAGES.indexOf(firstStage)
        require(firstStageIndex >= 0) { "Unknown start_stage=$firstStage" }
        val lastStageIndex = args.getString("end_stage")?.let(JOURNEY_STAGES::indexOf) ?: JOURNEY_STAGES.lastIndex
        require(lastStageIndex >= firstStageIndex) { "Invalid end_stage" }
        val report = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("start_wall_ms", startedWall).put("package", TARGET).put("stages", stages)
            .put("provider_writes", false).put("local_mutations", "search history and current playback Queue only")
            .put("recovery", "Host-owned exact cold AVD clone; no synthetic Queue restoration")
            .put("start_stage", firstStage).put("skipped_previously_confirmed_stages", JSONArray(JOURNEY_STAGES.take(firstStageIndex)))
            .put("modal_transport_proofs", modalTransportProofs)
        ui = RenderingSurveyUi(instrumentation)
        player = PlayerUi(TvUi(ui.device))
        enableOwnWindowReadback()
        var failure: Throwable? = null
        try {
            ui.launch()
            appPid = currentPid()
            report.put("app_pid", appPid)
            if (firstStageIndex > JOURNEY_STAGES.indexOf("normal_album_play")) {
                val closed = JSONArray()
                if (ui.has("catalog-actions-list")) {
                    ui.key(KeyEvent.KEYCODE_BACK, 0L)
                    ui.awaitGone("catalog-actions-list")
                    closed.put("catalog-actions-list")
                }
                if (ui.has("queue-panel")) {
                    ui.key(KeyEvent.KEYCODE_BACK, 0L)
                    ui.awaitGone("queue-panel")
                    closed.put("queue-panel")
                }
                report.put("resume_closed_owned_layers", closed)
            }
            fun journeyStage(name: String, body: () -> JSONObject) {
                if (JOURNEY_STAGES.indexOf(name) in firstStageIndex..lastStageIndex) stage(name, body)
            }
            journeyStage("search_result_return") { verifySearchResultReturn() }
            journeyStage("search_history") { verifySearchHistory() }
            journeyStage("normal_album_play") {
                ui.search("albums", QUERY)
                val selection = openCanonicalAlbum()
                ui.focus("collection-play")
                val initialTitleHeight = withNode("collection-title") { node ->
                    android.graphics.Rect().also(node::getBoundsInScreen).height()
                }
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
                ui.await("Down must enter first song", 3_000L) { "collection-song-0" in ui.snapshot().focused }
                repeat(4) { ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 90L) }
                ui.await("Collection scroll must reach fifth song", 3_000L) { "collection-song-4" in ui.snapshot().focused }
                repeat(4) { ui.key(KeyEvent.KEYCODE_DPAD_UP, 90L) }
                ui.await("Up must return to first song", 3_000L) { "collection-song-0" in ui.snapshot().focused }
                ui.key(KeyEvent.KEYCODE_DPAD_UP, 0L)
                ui.await("Top action must reveal complete collection title", 4_000L) {
                    ui.has("collection-title") && runCatching { withNode("collection-title") { node ->
                        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                        node.isVisibleToUser && rect.top >= 0 && rect.height() >= initialTitleHeight &&
                            rect.bottom <= ui.device.displayHeight
                    } }.getOrDefault(false)
                }
                selection.put("top_header_restored_by_remote", true)
                ui.focus("collection-play")
                val before = SystemClock.uptimeMillis()
                clickAllowed("collection-play")
                ui.awaitId("player-screen", 12_000L)
                ui.await("Normal Play must start real SDK playback", 35_000L) { healthy() && state() == "playing" }
                selection.put("play_to_actual_playing_ms", SystemClock.uptimeMillis() - before)
                    .put("waited_for_artwork_before_play", false)
            }
            journeyStage("future_queue_menu_hold_move_remove") {
                if (args.getString("queue_idle_only") == "1") verifyQueueIdle() else verifyQueueActions()
            }
            journeyStage("native_natural_successor") { verifyNaturalHandoff() }
            journeyStage("background_foreground") { verifyForegroundRestoration() }
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.stackTraceToString().take(12_000))
            runCatching { File(evidence, "failure-state.json").writeText(ui.snapshot().json(true).toString(2)) }
            runCatching { ui.device.takeScreenshot(File(evidence, "failure.png")) }
        } finally {
            runCatching { if (ui.foreground() == TARGET) ui.device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE) }
            runCatching { File(evidence, "musickit.log").writeText(musicLog()) }
            report.put("end_wall_ms", System.currentTimeMillis())
            File(evidence, "result.json").writeText(report.toString(2))
            restoreWindowReadback()
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun stage(name: String, body: () -> JSONObject) {
        val stage = JSONObject().put("name", name).put("start_wall_ms", System.currentTimeMillis())
        stages.put(stage)
        try {
            stage.put("observations", body()).put("status", "passed")
            File(evidence, "$name-state.json").writeText(ui.snapshot().json(true).toString(2))
            check(ui.device.takeScreenshot(File(evidence, "$name.png")))
        } catch (error: Throwable) {
            stage.put("status", "failed").put("error", error.message?.take(1000))
            throw error
        } finally {
            stage.put("end_wall_ms", System.currentTimeMillis())
            File(evidence, "stages.json").writeText(stages.toString(2))
        }
    }

    private fun verifySearchResultReturn(): JSONObject {
        ui.search("albums", QUERY)
        val selected = openCanonicalAlbum()
        val resultId = selected.getString("result_id")
        ui.key(KeyEvent.KEYCODE_BACK, 0L)
        ui.awaitGone("collection-screen")
        ui.await("Back must restore the exact search result without corrective focus", 5_000L) {
            resultId in ui.snapshot().focused && !ui.has("nav-panel")
        }
        val restored = ui.snapshot().json()
        return JSONObject().put("opened", selected).put("natural_search_return", restored)
            .put("corrective_focus_after_back", false)
    }

    private fun verifySearchHistory(): JSONObject {
        // Reinstalling a replacement APK can recreate the app's in-memory
        // browser. Restore only this read-only Search context, without replaying
        // the already-confirmed collection-open/Back case.
        if (!ui.has("catalog-search") || !ui.label("search-field").contains(QUERY, true)) ui.search("albums", QUERY)
        val retainedResult = ui.snapshot().ids.firstOrNull {
            it.startsWith("search-result-") && ui.label(it).contains(ALBUM_TITLE, true) && ui.label(it).contains("Ariana Grande", true)
        } ?: error("Canonical album result missing from the retained Search context")
        ui.focus(retainedResult)
        clearQueryThroughTextField()
        ui.awaitId("search-history", 5_000L)
        val historyId = (0 until 20).map { "search-history-$it" }.firstOrNull {
            ui.has(it) && ui.label(it).contains(QUERY, ignoreCase = true)
        } ?: error("Opening a real album did not record its public search query")
        ui.focus(historyId)
        clickAllowed(historyId)
        ui.await("History must restore the full query and album category", 10_000L) {
            ui.label("search-field").contains(QUERY, true) && ui.has("search-result-grid") &&
                ui.snapshot().focused.any { it.startsWith("search-result-") && ui.label(it).contains(ALBUM_TITLE, true) }
        }
        val historyReturn = ui.snapshot().json()
        clearQueryThroughTextField()
        ui.awaitId("search-history-clear", 5_000L)
        ui.focus("search-history-clear")
        clickAllowed("search-history-clear")
        ui.await("Clear history must remove the row and naturally retain text-field focus", 5_000L) {
            !ui.has("search-history") && "search-field" in ui.snapshot().focused
        }
        return JSONObject().put("history_id", historyId).put("restored_query_and_category", historyReturn)
            .put("clear_focus", ui.snapshot().json()).put("corrective_focus_after_back_or_clear", false)
    }

    private fun clearQueryThroughTextField() {
        ui.focus("search-field")
        if (readOnlySearch()) center("search-field")
        ui.await("Search input did not enter editing", 3_000L) { !readOnlySearch() }
        withNode("search-field") { node ->
            check(node.className?.toString() == "android.widget.EditText")
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            })) { "Real search EditText rejected empty text" }
        }
        // The native IME can consume the first Back. Stop as soon as the app
        // reports its read-only input; never send an unconditional second Back.
        repeat(2) {
            if (!readOnlySearch() || ui.foreground() != TARGET) {
                requireOwnAppOrIme()
                check(ui.device.pressBack())
                val deadline = SystemClock.uptimeMillis() + 1_000L
                while (SystemClock.uptimeMillis() < deadline && (!readOnlySearch() || ui.foreground() != TARGET))
                    SystemClock.sleep(50L)
            }
        }
        ui.await("Search editing must close without leaving Search", 3_000L) {
            ui.foreground() == TARGET && readOnlySearch() && ui.has("catalog-search") &&
                !ui.label("search-field").contains(QUERY, true)
        }
    }

    private fun verifyQueueIdle(): JSONObject {
        player.pause()
        player.focusControl("player-queue")
        center("player-queue", virtual = true)
        ui.awaitId("queue-panel", 4_000L)
        queueSelection()
        val started = SystemClock.uptimeMillis()
        ui.await("Idle Queue must retain its carousel and hide the cursor", 7_000L) {
            ui.has("queue-panel") && selectedQueueIndex() == null
        }
        val hiddenMs = SystemClock.uptimeMillis() - started
        ui.key(KeyEvent.KEYCODE_DPAD_UP, 0L)
        ui.await("Up must restore Queue selection", 3_000L) { selectedQueueIndex() != null }
        ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
        ui.await("Down must reach the progress controls", 3_000L) { "player-progress" in ui.snapshot().selected }
        ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
        ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 0L)
        ui.awaitGone("queue-panel")
        return JSONObject().put("idle_hide_observed_ms", hiddenMs).put("idle_carousel_retained", true)
            .put("up_restored_selection", true).put("down_reached_progress_and_exited", true)
            .put("queue_entries_changed", false).put("playback_resumed", false)
    }

    private fun verifyQueueActions(): JSONObject {
        // A fresh installed app can restore a paused Queue without a native
        // controller. Prepare only that existing song once for transport proof.
        if (prepareLines().isEmpty()) {
            player.play()
            ui.await("Existing Queue song must be prepared for transport observation", 30_000L) {
                healthy() && state() == "playing" && prepareLines().isNotEmpty()
            }
        }
        player.pause()
        player.focusControl("player-queue")
        center("player-queue", virtual = true)
        ui.awaitId("queue-panel", 5_000L)
        val current = queueSelection()
        val currentLabel = ui.label("player-queue-item-$current")
        ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        ui.await("Right must select the next Queue occurrence", 4_000L) { selectedQueueIndex() == current + 1 }
        val future = current + 1
        val futureLabel = ui.label("player-queue-item-$future")
        check(futureLabel.isNotBlank() && futureLabel != currentLabel)
        val baseline = prepareLines()
        val menuAt = SystemClock.uptimeMillis()
        ui.key(KeyEvent.KEYCODE_MENU, 0L)
        ui.awaitId("catalog-actions-list", 4_000L)
        ui.awaitId("catalog-action-queue-remove", 3_000L)
        val menuFocus = ui.assertNaturalFirstMenuFocus()
        check(transportPaused() && prepareLines() == baseline) { "Future-item Menu changed the paused transport or prepared another song" }
        val backAt = SystemClock.uptimeMillis()
        ui.key(KeyEvent.KEYCODE_BACK, 0L)
        ui.awaitGone("catalog-actions-list")
        ui.await("Back must retain the same future Queue occurrence", 3_000L) { selectedQueueIndex() == future }
        val backMs = SystemClock.uptimeMillis() - backAt
        check(backMs < 1_500L) { "Queue menu Back was delayed by ${backMs}ms" }
        longPressSelectedQueue(future)
        ui.awaitId("catalog-action-queue-move-down", 3_000L)
        check(transportPaused() && prepareLines() == baseline) { "Future-item long hold changed the paused transport or prepared another song" }
        ui.focus("catalog-action-queue-move-down")
        clickAllowed("catalog-action-queue-move-down")
        ui.awaitGone("catalog-actions-list")
        ui.await("Move Down must retain the moved occurrence at its new index", 4_000L) {
            selectedQueueIndex() == future + 1 && ui.label("player-queue-item-${future + 1}") == futureLabel
        }
        val afterMove = ui.snapshot().json()
        ui.key(KeyEvent.KEYCODE_MENU, 0L)
        ui.awaitId("catalog-action-queue-remove", 4_000L)
        ui.focus("catalog-action-queue-remove")
        clickAllowed("catalog-action-queue-remove")
        ui.awaitGone("catalog-actions-list")
        ui.await("Remove must select the adjacent occurrence, not jump to the current song", 4_000L) {
            selectedQueueIndex() == future + 1 && ui.label("player-queue-item-${future + 1}") != futureLabel
        }
        check(transportPaused() && prepareLines() == baseline) { "Local Queue edits changed the paused transport or prepared another song" }
        val afterRemove = ui.snapshot().json()
        val idleAt = SystemClock.uptimeMillis()
        ui.await("Idle Queue must keep its carousel and hide the virtual selection", 7_000L) {
            ui.has("queue-panel") && selectedQueueIndex() == null
        }
        val idleHideMs = SystemClock.uptimeMillis() - idleAt
        ui.key(KeyEvent.KEYCODE_DPAD_UP, 0L)
        ui.await("Up must restore Queue card interaction after idle", 3_000L) { selectedQueueIndex() != null }
        ui.key(KeyEvent.KEYCODE_BACK, 0L)
        ui.awaitGone("queue-panel")
        return JSONObject().put("current_index", current).put("current_label", currentLabel)
            .put("future_label", futureLabel).put("menu_first_focus", menuFocus)
            .put("menu_open_observed_ms", backAt - menuAt).put("back_ms", backMs)
            .put("after_move", afterMove).put("after_remove", afterRemove)
            .put("extra_prepare_events", 0).put("long_hold_used_real_key_down_up", true)
            .put("idle_hide_observed_ms", idleHideMs).put("idle_carousel_retained", true).put("idle_up_restored_selection", true)
    }

    private fun verifyNaturalHandoff(): JSONObject {
        player.play()
        player.focusControl("player-play-pause")
        ui.key(KeyEvent.KEYCODE_DPAD_UP, 0L)
        ui.await("Up must enter real progress scrubber", 3_000L) { "player-progress" in ui.snapshot().selected }
        val beforePrepare = prepareLines()
        val generation = beforePrepare.lastOrNull()?.let { PREPARE_GENERATION.find(it)?.groupValues?.get(1) }
            ?: error("No actual SDK Prepare generation evidence for canonical playback")
        val startElapsed = clockSeconds(ui.label("player-progress-elapsed"))
        val startRemaining = clockSeconds(ui.label("player-progress-remaining"))
        val duration = startElapsed + startRemaining
        check(startRemaining in 10..600) { "Expected a finite real song, remaining=$startRemaining" }
        val inputStart = SystemClock.uptimeMillis()
        val steps = ((startRemaining - 3 + 4) / 5).coerceAtLeast(1)
        repeat(steps) { ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 25L) }
        // The corrected clock layout deliberately hides the fixed right clock
        // near the endpoint. Derive pending remaining from the real moving UI
        // clock rather than requiring a label that is correctly not placed.
        if (clockSeconds(ui.label("player-progress-elapsed")) >= duration) ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 25L)
        val pendingRemaining = duration - clockSeconds(ui.label("player-progress-elapsed"))
        check(pendingRemaining in 1..8) { "Real scrubber did not reach a near-end pending position: $pendingRemaining" }
        val commitWall = System.currentTimeMillis()
        center("player-progress", virtual = true)
        ui.await("SDK must naturally advance from the real near-end UI seek", 18_000L) {
            healthy() && musicLog().lineSequence().any {
                eventWall(it) >= commitWall && it.contains("Native rail handoff generation=$generation ")
            } && state() == "playing"
        }
        val log = musicLog()
        val handoff = log.lineSequence().first {
            eventWall(it) >= commitWall && it.contains("Native rail handoff generation=$generation ")
        }
        check(prepareLines() == beforePrepare) { "Natural native successor caused a second app Prepare instead of adopting the SDK queue" }
        // Controls can auto-hide while the actual song ends. A direction only
        // reveals them; no Next, private seek, or direct controller call exists.
        if (!ui.has("player-progress-elapsed")) ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        ui.awaitId("player-progress-elapsed", 3_000L)
        val nextElapsed = clockSeconds(ui.label("player-progress-elapsed"))
        check(nextElapsed <= 12) { "Natural handoff metadata/progress did not reset to the successor" }
        return JSONObject().put("generation", generation).put("original_elapsed_seconds", startElapsed)
            .put("original_remaining_seconds", startRemaining).put("scrub_right_steps", steps)
            .put("pending_remaining_seconds", pendingRemaining).put("scrub_and_handoff_ms", SystemClock.uptimeMillis() - inputStart)
            .put("actual_native_handoff_log", handoff).put("successor_elapsed_seconds", nextElapsed)
            .put("additional_prepare_events", 0).put("manual_next_used", false)
    }

    private fun verifyForegroundRestoration(): JSONObject {
        check(state() == "playing")
        val beforePrepare = prepareLines()
        val before = clockSeconds(ui.label("player-progress-elapsed"))
        val backgroundAt = SystemClock.uptimeMillis()
        check(ui.device.pressHome())
        ui.await("Home must leave AMATV without process death", 4_000L) { ui.foreground() != TARGET && currentPid() == appPid }
        val session = ui.device.executeShellCommand("dumpsys media_session")
        File(evidence, "background-media-session.txt").writeText(session)
        check(Regex("package=$TARGET[\\s\\S]{0,6000}state=PlaybackState \\{state=(?:3|PLAYING\\(3\\))(?:,|\\s|\\})").containsMatchIn(session)) {
            "Own real media session did not remain Playing while backgrounded"
        }
        ui.launch()
        ui.awaitId("player-screen", 6_000L)
        ui.await("Foreground must restore ongoing playback without manual Play", 5_000L) { healthy() && state() == "playing" }
        if (!ui.has("player-progress-elapsed")) ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        ui.await("Playback position must advance across the foreground round trip", 5_000L) {
            clockSeconds(ui.label("player-progress-elapsed")) > before
        }
        check(prepareLines() == beforePrepare) { "Foreground restoration restarted the SDK item" }
        return JSONObject().put("background_round_trip_ms", SystemClock.uptimeMillis() - backgroundAt)
            .put("elapsed_before", before).put("elapsed_after", clockSeconds(ui.label("player-progress-elapsed")))
            .put("same_process", true).put("additional_prepare_events", 0).put("manual_play_used", false)
    }

    private fun openCanonicalAlbum(): JSONObject {
        repeat(12) { index ->
            val resultId = "search-result-$index"
            repeat(3) { if (!ui.has(resultId)) ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 120L) }
            if (!ui.has(resultId)) return@repeat
            val label = ui.label(resultId)
            if (!label.contains(ALBUM_TITLE, true) || !label.contains("Ariana Grande", true)) return@repeat
            ui.focus(resultId)
            ui.verifiedActivation(resultId, "editorial_album").use { check(it.execute()) }
            ui.awaitId("collection-screen", 8_000L)
            ui.await("Opened collection must expose canonical identity", 8_000L) {
                ui.snapshot().ids.any { it.startsWith("collection-profile-") }
            }
            if (ui.has(PROFILE_ID)) {
                check(ui.label("collection-title").equals(ALBUM_TITLE, true))
                return JSONObject().put("result_id", resultId).put("public_label", label).put("profile_id", PROFILE_ID)
            }
            ui.key(KeyEvent.KEYCODE_BACK, 0L)
            ui.awaitGone("collection-screen")
        }
        error("Canonical album was not present among twelve validated public results")
    }

    /** The complete activation allowlist deliberately excludes all provider writes. */
    private fun clickAllowed(id: String) {
        val allowed = id in setOf("collection-play", "search-history-clear", "catalog-action-queue-remove", "catalog-action-queue-move-down") ||
            id.matches(Regex("search-history-[0-9]+"))
        check(allowed) { "Unapproved experience-journey action: $id" }
        ui.requireForeground()
        if (id == "collection-play") check(ui.has(PROFILE_ID) && ui.label("collection-title").equals(ALBUM_TITLE, true))
        if (id.startsWith("catalog-action-queue-")) check(ui.has("catalog-actions-list") && transportPaused())
        if (id.startsWith("search-history-")) check(ui.has("search-history") && ui.has("catalog-search"))
        check(id in ui.snapshot().focused) { "Activation requires explicitly verified source focus: $id" }
        // Exercise the same fresh Down/Up pair as the TV remote. FocusSurface
        // need not advertise a pointer-click action on its outer tagged node.
        center(id)
    }

    private fun center(id: String, virtual: Boolean = false) {
        requireOwnAppOrIme()
        if (virtual) check(id in ui.snapshot().selected) else check(id in ui.snapshot().focused)
        check(id in setOf("search-field", "player-queue", "player-progress", "collection-play", "search-history-clear",
            "catalog-action-queue-remove", "catalog-action-queue-move-down") || id.matches(Regex("search-history-[0-9]+")))
        val down = SystemClock.uptimeMillis()
        keyEvent(KeyEvent.ACTION_DOWN, down)
        keyEvent(KeyEvent.ACTION_UP, down)
    }

    private fun longPressSelectedQueue(index: Int) {
        ui.requireForeground()
        check(selectedQueueIndex() == index && ui.has("queue-panel"))
        val down = SystemClock.uptimeMillis()
        keyEvent(KeyEvent.ACTION_DOWN, down)
        try {
            ui.await("A real held future Queue item must open its action menu", 2_000L) { ui.has("catalog-actions-list") }
            check(SystemClock.uptimeMillis() - down >= 450L) { "Queue hold fired before the long-press threshold" }
        } finally { keyEvent(KeyEvent.ACTION_UP, down) }
        ui.awaitId("catalog-action-queue-remove", 3_000L)
        check(!ui.has("apple-music-management-dialog")) { "Opening key release accidentally activated a child dialog" }
    }

    private fun keyEvent(action: Int, down: Long) {
        check(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action,
            KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD), true))
    }

    private fun selectedQueueIndex(): Int? = ui.snapshot().selected.mapNotNull { QUEUE_ID.matchEntire(it)?.groupValues?.get(1)?.toInt() }.singleOrNull()
    private fun queueSelection(): Int { ui.await("Queue must expose one real virtual selection", 4_000L) { selectedQueueIndex() != null }; return checkNotNull(selectedQueueIndex()) }
    private fun state(): String = runCatching { withNode("player-screen") { node ->
        val label = node.stateDescription?.toString()
        listOf("playing", "paused", "preparing").firstOrNull { label in ui.stringLabels("player_state_$it") } ?: "unknown"
    } }.getOrDefault("absent")
    private fun transportPaused(): Boolean {
        val visibleState = state()
        if (visibleState == "paused") return true
        if (visibleState != "absent" || !ui.has("catalog-actions-list")) return false
        // Android hides the activity's accessibility subtree behind its native
        // Dialog. Read only the target package's actual transport in that case;
        // an absent UI node alone must never count as a paused player.
        val lines = ui.device.executeShellCommand("dumpsys media_session").lines()
        val ownedStates = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            if (line.trim() != "package=$TARGET") return@forEachIndexed
            val indent = line.indexOfFirst { !it.isWhitespace() }
            val section = lines.drop(index + 1).takeWhile { next ->
                next.isBlank() || next.indexOfFirst { !it.isWhitespace() } >= indent
            }
            section.firstOrNull { it.trimStart().startsWith("state=PlaybackState") }?.let { ownedStates += it.trim() }
        }
        val paused = ownedStates.isNotEmpty() && ownedStates.all { PAUSED_SESSION.containsMatchIn(it) }
        modalTransportProofs.put(JSONObject().put("wall_ms", System.currentTimeMillis())
            .put("source", "dumpsys media_session").put("package", TARGET).put("ui_state", visibleState)
            .put("owned_state_lines", JSONArray(ownedStates)).put("paused", paused))
        return paused
    }
    private fun healthy(): Boolean { ui.requireForeground(); check(currentPid() == appPid) { "App process restarted" }; return true }
    private fun currentPid(): String = ui.device.executeShellCommand("pidof $TARGET").trim().also { check(it.matches(Regex("[0-9]+"))) }
    private fun musicLog(): String = if (appPid.isBlank()) "" else ui.device.executeShellCommand("logcat -d -v epoch --pid=$appPid -t 1500 -s AMATV-MusicKit:I '*:S'")
    // A resumed Queue run must retain the existing controller's generation:
    // normal Play was already verified and must not be repeated for a new log.
    private fun prepareLines(): List<String> = musicLog().lineSequence().filter { it.contains("Prepare generation=") }.toList()
    private fun eventWall(line: String): Long = EPOCH.find(line)?.groupValues?.get(1)?.toDouble()?.times(1000)?.toLong() ?: 0L
    private fun clockSeconds(label: String): Int {
        val parts = CLOCK.find(label)?.value?.split(':')?.map(String::toInt) ?: error("Missing real progress clock: $label")
        return parts.fold(0) { value, part -> value * 60 + part }
    }
    private fun readOnlySearch(): Boolean = withNode("search-field") { it.stateDescription?.toString() in ui.stringLabels("browse_search_hint_remote") }

    private fun requireOwnAppOrIme() {
        if (ui.foreground() == TARGET) return
        val active = instrumentation.uiAutomation.rootInActiveWindow
        val activeId = active?.windowId
        active?.recycle()
        val windows = instrumentation.uiAutomation.windows
        val ownIme = try { windows.any { it.id == activeId && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            finally { windows.forEach { it.recycle() } }
        check(ownIme && Regex("(mResumedActivity|topResumedActivity).*dev\\.amatv/").containsMatchIn(ui.device.executeShellCommand("dumpsys activity activities"))) {
            "Input no longer belongs to AMATV or its active native IME"
        }
    }

    private fun <T> withNode(id: String, block: (AccessibilityNodeInfo) -> T): T {
        requireOwnAppOrIme()
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.packageName?.toString() == TARGET && node.isVisibleToUser && node.viewIdResourceName?.substringAfter(":id/") == id)
                return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { find(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        var found: AccessibilityNodeInfo? = null
        val active = instrumentation.uiAutomation.rootInActiveWindow
        try { if (active?.packageName?.toString() == TARGET) found = find(active) }
        finally { active?.recycle() }
        if (found == null) {
            val windows = instrumentation.uiAutomation.windows
            try {
                for (window in windows.sortedByDescending { it.layer }) {
                    val root = window.root ?: continue
                    try { if (root.packageName?.toString() == TARGET) found = find(root) } finally { root.recycle() }
                    if (found != null) break
                }
            } finally { windows.forEach { it.recycle() } }
        }
        val node = found ?: error("Missing visible own-app node: $id")
        return try { block(node) } finally { node.recycle() }
    }

    private fun enableOwnWindowReadback() {
        val info = instrumentation.uiAutomation.serviceInfo
        oldAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
    }

    private fun restoreWindowReadback() {
        oldAccessibilityFlags?.let { flags ->
            val info = instrumentation.uiAutomation.serviceInfo
            info.flags = flags
            instrumentation.uiAutomation.serviceInfo = info
        }
    }

    private companion object {
        const val QUERY = "Positions Ariana Grande"
        const val ALBUM_TITLE = "Positions (Deluxe)"
        const val PROFILE_ID = "collection-profile-albums-1553944254"
        val QUEUE_ID = Regex("player-queue-item-([0-9]+)")
        val PREPARE_GENERATION = Regex("Prepare generation=([0-9]+)")
        val EPOCH = Regex("^\\s*([0-9]+\\.[0-9]+)")
        val CLOCK = Regex("[0-9]+:[0-9]{2}(?::[0-9]{2})?")
        val PAUSED_SESSION = Regex("^state=PlaybackState\\s*\\{state=(?:2|PAUSED\\(2\\))(?:,|\\s|\\})")
        val JOURNEY_STAGES = listOf("search_result_return", "search_history", "normal_album_play",
            "future_queue_menu_hold_move_remove", "native_natural_successor", "background_foreground")
    }
}
