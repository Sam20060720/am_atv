package dev.amatv.benchmark

import android.app.Instrumentation
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public presentation switches only: no transport, queue, account or network mutations. */
@RunWith(AndroidJUnit4::class)
class InterfaceStyleSwitchTest {
    /** Opens real menus but never activates catalog writes, lyric toggles or transport. */
    @Test fun classicMenusKeepFocusWithinSharedSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit Classic menu check only", args.getString("allow_classic_menus") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val runId = (args.getString("run_id") ?: "classic-menus-" + System.currentTimeMillis())
            .also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "classic-menus/$runId")
        check(!directory.exists() && directory.mkdirs())
        val ui = RenderingSurveyUi(instrumentation)
        val cases = JSONArray()
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("cloud_writes", false).put("lyrics_preference_changes", false)
            .put("visual_review_required", true).put("cases", cases)
        var original: String? = null
        var failure: Throwable? = null
        try {
            ui.launch(); openSettings(ui)
            original = selectedStyle(ui)
            if (original != "classic") {
                activateStyle(instrumentation, ui, "classic"); awaitSelectedFocus(ui, "classic")
            }
            navigate(ui, "home")
            ui.await("Home did not own a real card", 5_000) { ui.snapshot().focused.any(SHELF_CARD::matches) }
            var album: String? = null
            for (row in 0..4) {
                album = ui.snapshot().ids.firstOrNull { it.matches(Regex("shelf-\\d+-item-\\d+-album")) }
                if (album != null || row == 4) break
                ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 240)
            }
            val source = album
            if (source != null) {
                ui.focus(source) // Public setup only; menu focus below is never repaired.
                val longPress = ui.verifiedAlbumLongPress(source)
                longPress.execute()
                val initialFocus = ui.assertNaturalFirstMenuFocus()
                captureMenuEdges(ui, "catalog-action-", "catalog", directory, cases)
                ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("catalog-actions-list")
                ui.await("Catalog menu Back lost its actual album source", 4_000) { source in ui.snapshot().focused }
                cases.put(JSONObject().put("case", "catalog-source-return").put("source", source)
                    .put("long_press_ms", longPress.holdMs).put("initial_focus", initialFocus))
            } else cases.put(JSONObject().put("case", "catalog-menu").put("status", "skipped")
                .put("reason", "No real album card visible within four bounded Home Down steps"))

            if (ui.has("nav-nowplaying")) {
                ui.focus("nav-nowplaying"); ui.activate("nav-nowplaying", "navigation")
                ui.awaitId("player-screen", 5_000)
                revealPlayerControls(ui)
                if (ui.has("player-lyrics-display")) {
                    val state = ui.snapshot()
                    val controls = state.nodes.filter { PLAYER_CONTROL.matches(it.id) && it.clickable }.map { it.id }.distinct()
                    val from = controls.indexOfFirst { it in state.focused }
                    val to = controls.indexOf("player-lyrics-display")
                    check(from >= 0 && to >= 0)
                    repeat(kotlin.math.abs(to - from)) { ui.key(
                        if (to > from) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, 55) }
                    safeMenuCenter(instrumentation, ui, "player-lyrics-display", virtual = true)
                    ui.awaitId("lyrics-display-menu", 3_000)
                    captureMenuEdges(ui, "lyrics-menu-", "lyrics", directory, cases, "lyrics-menu-pronunciation")
                    ui.focus("lyrics-menu-translation") // Submenu source setup; no preference activation.
                    safeMenuCenter(instrumentation, ui, "lyrics-menu-translation")
                    ui.awaitId("lyrics-menu-back", 3_000)
                    ui.await("Language submenu did not own focus", 2_000) {
                        ui.snapshot().focused.any { it.startsWith("lyrics-translation-") }
                    }
                    check(ui.device.takeScreenshot(File(directory, "lyrics-language-pane.png")))
                    cases.put(JSONObject().put("case", "lyrics-language-pane").put("state", ui.snapshot().json(true)))
                    ui.key(KeyEvent.KEYCODE_BACK, 0)
                    ui.await("Language Back did not restore its actual row", 3_000) {
                        "lyrics-menu-translation" in ui.snapshot().focused
                    }
                    ui.key(KeyEvent.KEYCODE_BACK, 0); ui.awaitGone("lyrics-display-menu")
                    ui.await("Lyrics menu Back did not restore its virtual player control", 3_000) {
                        val restored = ui.snapshot()
                        "player-screen" in restored.focused && "player-lyrics-display" in restored.focused
                    }
                } else cases.put(JSONObject().put("case", "lyrics-menu").put("status", "skipped")
                    .put("reason", "Existing track exposes no actual Lyrics Display control"))
                ui.leavePlayerWithBack()
            } else cases.put(JSONObject().put("case", "lyrics-menu").put("status", "skipped")
                .put("reason", "No existing current track; playback was not seeded"))
            report.put("status", "passed_pending_visual_review")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { report.put("failure_state", ui.snapshot().json(true)) }
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            val restoreStyle = original
            if (restoreStyle != null) runCatching {
                openSettings(ui)
                if (selectedStyle(ui) != restoreStyle) {
                    activateStyle(instrumentation, ui, restoreStyle); awaitSelectedFocus(ui, restoreStyle)
                }
                check(selectedStyle(ui) == restoreStyle)
                report.put("restored_style", restoreStyle)
            }.onFailure { if (failure == null) failure = it; report.put("restore_error", it.message?.take(500)) }
            if (failure != null) report.put("status", "failed")
            File(directory, "result.json").writeText(report.toString(2)); ui.close()
        }
        failure?.let { throw it }
    }

    private fun safeMenuCenter(instrumentation: Instrumentation, ui: RenderingSurveyUi, id: String, virtual: Boolean = false) {
        check(id in setOf("player-lyrics-display", "lyrics-menu-translation"))
        ui.await("Allowed menu source does not own Center: $id", 2_000) {
            val state = ui.snapshot()
            if (virtual) "player-screen" in state.focused && id in state.focused else id in state.focused
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) check(instrumentation.uiAutomation.injectInputEvent(
            KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD), true))
    }

    private fun captureMenuEdges(ui: RenderingSurveyUi, prefix: String, name: String, directory: File,
        cases: JSONArray, extraRow: String? = null) {
        fun isRow(id: String) = id.startsWith(prefix) || id == extraRow
        fun focusedRow(): String = ui.snapshot().focused.singleOrNull(::isRow)
            ?: error("$name menu has no unique actual focused row")
        ui.awaitId("tv-menu-surface", 3_000)
        ui.await("$name menu did not naturally own row focus", 3_000) { ui.snapshot().focused.any(::isRow) }
        fun capture(edge: String) {
            ui.settle(220)
            val state = ui.snapshot()
            val id = focusedRow()
            fun bounds(target: String) = requireNotNull(Rect.unflattenFromString(state.nodes.first { it.id == target }.bounds))
            val surface = bounds("tv-menu-surface")
            val row = bounds(id)
            // Classic's shared surface is 300dp wide. Require 6dp clear space;
            // screenshot review still checks the actual painted focus outline.
            val inset = maxOf(4, (surface.width() * 6f / 300f).toInt())
            check(surface.contains(row) && row.left - surface.left >= inset && surface.right - row.right >= inset &&
                row.top - surface.top >= inset && surface.bottom - row.bottom >= inset) { "$name $edge row lacks focus-frame inset" }
            check(ui.device.takeScreenshot(File(directory, "$name-$edge.png")))
            cases.put(JSONObject().put("case", "$name-$edge").put("focus", id)
                .put("minimum_inset_px", inset).put("state", state.json(true)))
        }
        capture("first-row")
        var unchanged = 0
        var presses = 0
        while (unchanged < 2 && presses++ < 20) {
            val before = focusedRow()
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 220)
            unchanged = if (focusedRow() == before) unchanged + 1 else 0
        }
        check(unchanged == 2) { "$name menu did not reach a bounded last row" }
        capture("last-row")
    }

    /** Samples the existing feed without clearing cache or forcing a refresh. */
    @Test fun radioFeedRetainsVisibleContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit Radio stability check only", args.getString("allow_radio_stability") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val runId = (args.getString("run_id") ?: "radio-stability-" + System.currentTimeMillis())
            .also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "radio-stability/$runId")
        check(!directory.exists() && directory.mkdirs())
        val ui = RenderingSurveyUi(instrumentation)
        val samples = JSONArray()
        val signatures = mutableSetOf<String>()
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("cold_load_proven", false).put("cache_cleared", false).put("refresh_forced", false)
            .put("requested_observation_ms", 6_000).put("sample_interval_ms", 250).put("samples", samples)
        try {
            ui.launch()
            navigate(ui, "radio")
            if (ui.snapshot().focused.any { it.startsWith("nav-") }) ui.key(
                if (ui.has("classic-navigation")) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            ui.await("Radio has no existing nonempty visible feed/focus", 8_000) {
                val state = ui.snapshot()
                state.ids.any(SHELF_CARD::matches) && state.focused.isNotEmpty()
            }
            check(ui.device.takeScreenshot(File(directory, "radio-first.png")))
            val started = SystemClock.uptimeMillis()
            do {
                val state = ui.snapshot()
                val rows = state.ids.filter { it.startsWith("home-row-") && it.contains("-count-") }.sorted()
                val signature = rows.joinToString("\n") { it + ":" + ui.label(it) }
                signatures += signature
                samples.put(JSONObject().put("elapsed_ms", SystemClock.uptimeMillis() - started)
                    .put("row_signature", signature).put("state", state.json()))
                check("browse-radio" in state.ids && state.ids.any { it.matches(Regex("home-shelves-count-[1-9]\\d*")) } &&
                    state.ids.any(SHELF_CARD::matches)) { "Radio replaced visible content with a blank/loading page" }
                check(state.focused.isNotEmpty()) { "Radio feed update lost native focus" }
                val remaining = 6_000L - (SystemClock.uptimeMillis() - started)
                if (remaining > 0) ui.settle(minOf(250L, remaining))
            } while (SystemClock.uptimeMillis() - started < 6_000L)
            check(ui.device.takeScreenshot(File(directory, "radio-settled.png")))
            report.put("source_change_observed", signatures.size > 1).put("distinct_visible_row_signatures", signatures.size)
                .put("observed_ms", SystemClock.uptimeMillis() - started).put("status", "passed_sampled_window")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
            throw error
        } finally {
            report.put("source_change_observed", signatures.size > 1)
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
    }

    /** Actual Left presses own every assertion after one public card-focus setup. */
    @Test fun homeHorizontalBoundaryHonorsInterfaceStyle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit Home boundary check only", args.getString("allow_interface_navigation") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Navigation check is emulator-only" }
        val runId = (args.getString("run_id") ?: "interface-navigation-" + System.currentTimeMillis())
            .also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "interface-navigation/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Do not overwrite an earlier navigation check" }
        val ui = RenderingSurveyUi(instrumentation)
        val cases = JSONArray()
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("queue_mutations", false).put("account_mutations", false)
            .put("assertion_focus_repairs", 0).put("cases", cases)
        var original: String? = null
        var failure: Throwable? = null
        try {
            ui.launch()
            openSettings(ui)
            original = selectedStyle(ui)
            report.put("original_style", original)
            for (style in listOf("apple", "classic")) {
                if (selectedStyle(ui) != style) {
                    activateStyle(instrumentation, ui, style)
                    awaitSelectedFocus(ui, style)
                }
                navigate(ui, "home")
                // This assertion deliberately precedes public focus setup: Home
                // itself must enter its first usable card, never a heading action.
                ui.await("$style Home did not naturally focus its first usable card", 5_000) {
                    val state = ui.snapshot()
                    val first = state.ids.filter { SHELF_CARD.matchEntire(it)?.groupValues?.get(2) == "0" }
                        .minByOrNull { SHELF_CARD.matchEntire(it)!!.groupValues[1].toInt() }
                    first != null && first in state.focused
                }
                checkHomeLeftBoundary(instrumentation, ui, style, cases, directory)
                openSettings(ui)
            }
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { report.put("failure_state", ui.snapshot().json(true)) }
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            val restoreStyle = original
            if (restoreStyle != null) runCatching {
                openSettings(ui)
                if (selectedStyle(ui) != restoreStyle) {
                    activateStyle(instrumentation, ui, restoreStyle)
                    awaitSelectedFocus(ui, restoreStyle)
                }
                check(selectedStyle(ui) == restoreStyle)
                report.put("restored_style", restoreStyle)
            }.onFailure { error ->
                report.put("restore_error", error.message?.take(500))
                if (failure == null) failure = error
            }
            if (failure != null) report.put("status", "failed")
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun checkHomeLeftBoundary(instrumentation: Instrumentation, ui: RenderingSurveyUi, style: String,
        cases: JSONArray, directory: File) {
        val steps = JSONArray()
        fun record(label: String) {
            val state = ui.snapshot()
            check("browse-home" in state.ids && "app-exit-dialog" !in state.ids) { "$style Left left the Home scope" }
            check(state.ids.none { it.startsWith("home-shelf-") && it.endsWith("-insights-replay") }) {
                "$style Home still exposes the removed Replay heading action"
            }
            steps.put(JSONObject().put("step", label).put("state", state.json(true)))
        }
        val entry = JSONObject().put("case", "$style-home-left-boundary").put("steps", steps)
        cases.put(entry) // Preserve partial evidence if a later key fails.
        record("natural-first-card")
        ui.await("$style Home has no existing row with a second visible card", 4_000) {
            ui.snapshot().ids.mapNotNull { id -> SHELF_CARD.matchEntire(id)?.let { it.groupValues[1] to it.groupValues[2] } }
                .groupBy({ it.first }, { it.second }).values.any { "0" in it && "1" in it }
        }
        val cards = ui.snapshot().ids.mapNotNull { id -> SHELF_CARD.matchEntire(id)?.let {
            Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), id)
        } }.groupBy { it.first }.toSortedMap().values.first { row -> row.any { it.second == 0 } && row.any { it.second == 1 } }
        val start = cards.firstOrNull { it.second == 2 } ?: cards.first { it.second == 1 }
        val first = cards.first { it.second == 0 }.third
        fun awaitCard(column: Int) = ui.await("$style Left/Right did not reach row ${start.first} column $column", 2_000) {
            ui.snapshot().focused.any { id -> SHELF_CARD.matchEntire(id)?.let {
                it.groupValues[1].toInt() == start.first && it.groupValues[2].toInt() == column
            } == true }
        }
        fun awaitNavigation() = ui.await("$style navigation handoff did not reach actual nav-home", 2_500) {
            "nav-home" in ui.snapshot().focused
        }
        fun returnToFirst(label: String, expectedSource: String = first) {
            ui.key(if (style == "classic") KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            ui.await("$style navigation did not return to its actual content source", 2_500) { expectedSource in ui.snapshot().focused }
            ui.settle(250)
            check(expectedSource in ui.snapshot().focused) { "$style content return drifted after the handoff" }
            record(label)
        }
        // Setup only. No accessibility focus is used to repair any tested key transition.
        ui.focus(start.third)
        entry.put("source", start.third).put("boundary_card", first)
        record("public-second-or-middle-card-setup")
        repeat(start.second) { press ->
            ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 70)
            awaitCard(start.second - press - 1)
            record("fresh-left-${press + 1}")
        }
        ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        if (style == "classic") awaitCard(0) else awaitNavigation()
        record(if (style == "classic") "fresh-left-stops-at-first-card" else "fresh-left-navigation")
        ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        ui.settle(550)
        val boundaryOwner = if (style == "classic") first else "nav-home"
        check(boundaryOwner in ui.snapshot().focused) { "$style an additional Left escaped its boundary owner" }
        record("additional-left-boundary-stable")
        check(ui.device.takeScreenshot(File(directory, "$style-left-boundary.png")))
        if (style != "classic") returnToFirst("fresh-left-return")
        ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.key(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        ui.await("$style fast Right/Left reversal escaped its card", 2_000) { first in ui.snapshot().focused }
        record("fast-right-left-reversal")
        repeat(start.second) { column ->
            ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 70)
            awaitCard(column + 1)
        }
        val downTime = SystemClock.uptimeMillis()
        fun held(action: Int, repeat: Int) = KeyEvent(downTime, SystemClock.uptimeMillis(), action,
            KeyEvent.KEYCODE_DPAD_LEFT, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        try {
            // Apple crosses to navigation; Classic deliberately stops on item0.
            repeat(start.second + 2) { repeatCount ->
                check(instrumentation.uiAutomation.injectInputEvent(held(KeyEvent.ACTION_DOWN, repeatCount), true))
                if (repeatCount < start.second) awaitCard(start.second - repeatCount - 1)
                else if (style == "classic") awaitCard(0) else awaitNavigation()
                record("held-left-repeat-$repeatCount")
            }
        } finally {
            check(instrumentation.uiAutomation.injectInputEvent(held(KeyEvent.ACTION_UP, 0), true))
        }
        if (style == "classic") {
            ui.settle(250)
            check(first in ui.snapshot().focused) { "Classic held Left escaped after release" }
            record("held-left-release-still-first-card")
            var returnSource = first
            var upPresses = 0
            while ("nav-home" !in ui.snapshot().focused && upPresses < ((start.first + 1) * 2 + 1).coerceAtMost(8)) {
                ui.snapshot().focused.firstOrNull { SHELF_CARD.matches(it) || it.startsWith("home-shelf-") }
                    ?.let { returnSource = it }
                ui.key(KeyEvent.KEYCODE_DPAD_UP, 160)
                record("classic-up-${++upPresses}")
            }
            awaitNavigation()
            record("classic-up-navigation")
            entry.put("up_return_source", returnSource)
            returnToFirst("classic-down-restores-source", returnSource)
        } else returnToFirst("held-left-release-return")
        entry.put("status", "passed").put("boundary_screenshot", "$style-left-boundary.png")
    }

    /** Captures real focus rendering; the saved images still require visual inspection. */
    @Test fun classicRemoteFocusPresentation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit classic focus check only", args.getString("allow_interface_focus") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Focus check is emulator-only" }
        val runId = (args.getString("run_id") ?: "interface-focus-" + System.currentTimeMillis())
            .also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "interface-focus/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Do not overwrite an earlier focus check" }
        val ui = RenderingSurveyUi(instrumentation)
        val cases = JSONArray()
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("queue_mutations", false).put("account_mutations", false)
            .put("session_continuity_tested", false).put("visual_review_required", true).put("cases", cases)
        var original: String? = null
        var failure: Throwable? = null
        fun capture(name: String, owner: String) {
            ui.settle(250) // Let the changed focus spring/outline reach its visible state.
            val state = ui.snapshot()
            check(owner in state.focused) { "$name has no actual native focus owner $owner" }
            check(ui.device.takeScreenshot(File(directory, "$name.png")))
            cases.put(JSONObject().put("case", name).put("screenshot", "$name.png")
                .put("player_state_description", ui.playerStateDescription()).put("state", state.json(true)))
        }
        try {
            ui.launch()
            openSettings(ui)
            original = selectedStyle(ui)
            report.put("original_style", original)
            if (original != "classic") {
                activateStyle(instrumentation, ui, "classic")
                awaitSelectedFocus(ui, "classic")
            }
            navigate(ui, "home")
            ui.focus("nav-home")
            capture("topbar-home", "nav-home")
            val topbar = ui.snapshot()
            fun navBounds(id: String) = requireNotNull(Rect.unflattenFromString(topbar.nodes.first { it.id == id }.bounds))
            ui.key(if (navBounds("nav-browse").centerX() > navBounds("nav-home").centerX())
                KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            ui.await("Remote focus did not move from Home to Browse", 2_000) { "nav-browse" in ui.snapshot().focused }
            check(ui.has("browse-home")) { "Moving focus selected another tab" }
            capture("topbar-browse-home-active", "nav-browse")

            if (ui.has("nav-nowplaying")) {
                ui.focus("nav-nowplaying")
                ui.activate("nav-nowplaying", "navigation")
                ui.awaitId("player-screen", 5_000)
                ui.await("Player did not acquire native remote focus", 2_000) { "player-screen" in ui.snapshot().focused }
                revealPlayerControls(ui)
                capture("player-control", "player-screen")
                ui.key(KeyEvent.KEYCODE_DPAD_UP, 160) // Focus progress only; never seek or confirm.
                if ("player-progress" in ui.snapshot().selected) capture("player-progress", "player-screen")
                else cases.put(JSONObject().put("case", "player-progress").put("status", "skipped")
                    .put("reason", "Current retained item has no seekable progress zone"))
                revealPlayerControls(ui)
                val controls = ui.snapshot()
                val ids = controls.nodes.filter { PLAYER_CONTROL.matches(it.id) && it.clickable }.map { it.id }.distinct()
                val from = ids.indexOfFirst { it in controls.focused }
                val to = ids.indexOf("player-queue")
                check(from >= 0 && to >= 0) { "Player has no proven control selection/queue action" }
                repeat(kotlin.math.abs(to - from)) {
                    ui.key(if (to > from) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, 55)
                }
                ui.await("Queue view does not own activation", 1_500) { "player-queue" in ui.snapshot().focused }
                // The only player activation allowed by this test is opening its queue view.
                val down = SystemClock.uptimeMillis()
                fun center(action: Int) = KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DPAD_CENTER,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
                check(instrumentation.uiAutomation.injectInputEvent(center(KeyEvent.ACTION_DOWN), true))
                check(instrumentation.uiAutomation.injectInputEvent(center(KeyEvent.ACTION_UP), true))
                ui.awaitId("queue-panel", 3_000)
                if (ui.snapshot().selected.any { it.startsWith("player-queue-item-") }) {
                    capture("player-queue-card", "player-screen")
                    ui.key(KeyEvent.KEYCODE_DPAD_UP, 160) // Queue card -> modes; no mode is activated.
                } else cases.put(JSONObject().put("case", "player-queue-card").put("status", "skipped")
                    .put("reason", "Current retained queue has no selected existing card"))
                ui.awaitId("queue-mode-controls", 2_000)
                capture("player-queue-modes", "player-screen")
                report.put("player_back_steps", ui.leavePlayerWithBack())
            } else cases.put(JSONObject().put("case", "player-surfaces").put("status", "skipped")
                .put("reason", "No existing current track; this check never seeds playback"))

            navigate(ui, "home")
            ui.focus("nav-home")
            ui.key(KeyEvent.KEYCODE_BACK, 160)
            ui.awaitId("app-exit-dialog", 3_000)
            ui.await("Exit prompt did not default to Stay", 2_000) { "app-exit-stay" in ui.snapshot().focused }
            capture("exit-dialog-stay", "app-exit-stay")
            ui.key(KeyEvent.KEYCODE_BACK, 0)
            ui.awaitGone("app-exit-dialog")
            report.put("status", "passed_pending_visual_review")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            val restoreStyle = original
            if (restoreStyle != null) runCatching {
                openSettings(ui)
                if (selectedStyle(ui) != restoreStyle) {
                    activateStyle(instrumentation, ui, restoreStyle)
                    awaitSelectedFocus(ui, restoreStyle)
                }
                check(selectedStyle(ui) == restoreStyle)
                report.put("restored_style", restoreStyle)
            }.onFailure { error ->
                report.put("restore_error", error.message?.take(500))
                if (failure == null) failure = error
            }
            if (failure != null) report.put("status", "failed")
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun revealPlayerControls(ui: RenderingSurveyUi) {
        // Back unwinds preview/queue/chrome only. Wait for each closing animation
        // before another press so a stale visible node cannot cause player exit.
        repeat(3) {
            if (ui.has("player-controls") || ui.has("queue-panel")) ui.key(KeyEvent.KEYCODE_BACK, 420)
        }
        check(ui.has("player-screen") && !ui.has("player-controls") && !ui.has("queue-panel"))
        ui.key(KeyEvent.KEYCODE_DPAD_RIGHT, 320) // Hidden controls consume this key without activating transport.
        ui.await("Player controls have no virtual focus", 2_000) {
            "player-screen" in ui.snapshot().focused && ui.snapshot().focused.any(PLAYER_CONTROL::matches)
        }
    }

    @Test fun stylesKeepPlaybackAndReturnOwnership() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit interface-switch check only", args.getString("allow_interface_style") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Interface check is emulator-only" }
        val runId = (args.getString("run_id") ?: "interface-" + System.currentTimeMillis())
            .also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val directory = File(requireNotNull(instrumentation.context.getExternalFilesDir(null)), "interface-style/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Do not overwrite an earlier interface check" }
        val ui = RenderingSurveyUi(instrumentation)
        val cases = JSONArray()
        val report = JSONObject().put("run_id", runId).put("playback_operations", false)
            .put("queue_mutations", false).put("account_mutations", false).put("cases", cases)
        var original: String? = null
        var failure: Throwable? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            ui.launch()
            openSettings(ui)
            original = selectedStyle(ui)
            report.put("original_style", original)
            val pid = pid(ui)
            val session = media(instrumentation)
            switchStyle(instrumentation, ui, "apple", pid, session?.token, cases, directory)
            verifyBackToExit(ui, "apple", cases, directory)
            openSettings(ui)
            switchStyle(instrumentation, ui, "classic", pid, session?.token, cases, directory)
            verifyBackToExit(ui, "classic", cases, directory)
            verifyClassicCollection(ui, cases, directory)
            openSettings(ui)
            switchStyle(instrumentation, ui, "apple", pid, session?.token, cases, directory)
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.message?.take(500))
            runCatching { ui.device.takeScreenshot(File(directory, "failure.png")) }
        } finally {
            val restoreStyle = original
            if (restoreStyle != null) runCatching {
                openSettings(ui)
                if (selectedStyle(ui) != restoreStyle) {
                    activateStyle(instrumentation, ui, restoreStyle)
                    awaitSelectedFocus(ui, restoreStyle)
                }
                check(selectedStyle(ui) == restoreStyle) { "Original interface was not restored" }
                report.put("restored_style", restoreStyle)
            }.onFailure { error ->
                report.put("restore_error", error.message?.take(500))
                if (failure == null) failure = error
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if (failure != null) report.put("status", "failed")
            File(directory, "result.json").writeText(report.toString(2))
            ui.close()
        }
        failure?.let { throw it }
    }

    private fun openSettings(ui: RenderingSurveyUi) {
        if (ui.has("app-exit-dialog")) {
            ui.key(KeyEvent.KEYCODE_BACK, 0)
            ui.awaitGone("app-exit-dialog")
        }
        ui.leavePlayerWithBack()
        navigate(ui, "account")
        if (ui.snapshot().focused.any { it.startsWith("nav-") }) {
            // Apple enters from the sidebar/pill with Right; Classic enters
            // from its top bar with Down. Down on Apple's pill reopens it.
            ui.key(if (ui.has("classic-navigation")) KeyEvent.KEYCODE_DPAD_DOWN
                else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        ui.await("Settings content did not take remote focus", 4_000) {
            val state = ui.snapshot()
            "browse-account" in state.ids && state.focused.isNotEmpty() &&
                state.focused.none { it.startsWith("nav-") || it == "classic-navigation" }
        }
        repeat(32) {
            if (ui.has(APPLE) && ui.has(CLASSIC)) return
            // Down only changes focus/scroll; it cannot activate a setting.
            ui.key(KeyEvent.KEYCODE_DPAD_DOWN, 100)
        }
        error("Interface controls were not reached within bounded Settings scroll")
    }

    private fun navigate(ui: RenderingSurveyUi, tab: String) {
        if (!ui.has("classic-navigation")) ui.openNavigation()
        ui.focus("nav-$tab")
        ui.activate("nav-$tab", "navigation")
        ui.awaitId("browse-$tab", 8_000)
        if (!ui.has("classic-navigation")) ui.awaitGone("nav-panel")
    }

    private fun switchStyle(instrumentation: Instrumentation, ui: RenderingSurveyUi, style: String,
        expectedPid: String, expectedToken: MediaSession.Token?, cases: JSONArray, directory: File) {
        val before = media(instrumentation)
        activateStyle(instrumentation, ui, style)
        awaitSelectedFocus(ui, style)
        check(pid(ui) == expectedPid) { "Switching interface restarted the app process" }
        val after = media(instrumentation)
        check(after?.token == expectedToken) { "Switching interface replaced the active media session" }
        val checkMetadata = before?.stableSong == true
        if (checkMetadata) check(after?.identity == before?.identity) { "Switching interface changed the existing song" }
        check(ui.has("classic-navigation") == (style == "classic")) { "Selected style does not match actual navigation" }
        check(style == "classic" || ui.has("nav-menu")) { "Apple navigation did not return" }
        check(ui.has("browse-account")) { "Style switch left Settings" }
        val screenshot = "settings-$style-${cases.length() + 1}.png"
        check(ui.device.takeScreenshot(File(directory, screenshot))) { "Settings visual proof was not saved" }
        cases.put(JSONObject().put("case", "switch-$style").put("pid", expectedPid)
            .put("session_present", after != null).put("song_identity_checked", checkMetadata)
            .put("metadata_before", before?.identity).put("metadata_after", after?.identity)
            .put("playback_state_before", before?.state ?: JSONObject.NULL)
            .put("playback_state_after", after?.state ?: JSONObject.NULL)
            .put("position_ms_before", before?.positionMs ?: JSONObject.NULL)
            .put("position_ms_after", after?.positionMs ?: JSONObject.NULL)
            .put("playing_at_both_samples", before?.state == PlaybackState.STATE_PLAYING && after?.state == PlaybackState.STATE_PLAYING)
            .put("screenshot", screenshot)
            .put("after", ui.snapshot().json()))
    }

    private fun activateStyle(instrumentation: Instrumentation, ui: RenderingSurveyUi, style: String) {
        val id = styleId(style)
        ui.focus(id)
        check(ui.snapshot().nodes.any { it.id == id && it.clickable }) { "Style option is not an actual clickable control" }
        check(id in ui.snapshot().focused) { "Style option does not own Center" }
        val start = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(start, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DPAD_CENTER,
            0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        check(instrumentation.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_DOWN), true))
        check(instrumentation.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_UP), true))
    }

    private fun awaitSelectedFocus(ui: RenderingSurveyUi, style: String) {
        val id = styleId(style)
        var stableSince = 0L
        ui.await("Selected interface option lost focus after switching", 4_000) {
            val state = ui.snapshot()
            if (id in state.selected && id in state.focused && "browse-account" in state.ids) {
                if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                // Covers the sidebar pill's old 200ms delayed focus takeover.
                SystemClock.uptimeMillis() - stableSince >= 400L
            } else { stableSince = 0L; false }
        }
    }

    private fun verifyBackToExit(ui: RenderingSurveyUi, style: String, cases: JSONArray, directory: File) {
        val source = if (ui.has("classic-navigation")) {
            if (ui.has("nav-radio")) "radio" else "home"
        } else {
            ui.openNavigation()
            if (ui.has("nav-radio")) "radio" else "home"
        }
        navigate(ui, source)
        val steps = JSONArray()
        var sawHome = source == "home"
        var homeCaptured = false
        repeat(8) { index ->
            ui.key(KeyEvent.KEYCODE_BACK, 350)
            val state = ui.snapshot()
            sawHome = sawHome || "browse-home" in state.ids
            if (!homeCaptured && "browse-home" in state.ids) {
                check(ui.device.takeScreenshot(File(directory, "home-$style.png"))) { "Home visual proof was not saved" }
                homeCaptured = true
            }
            steps.put(JSONObject().put("press", index + 1).put("state", state.json()))
            if ("app-exit-dialog" in state.ids) {
                check(sawHome) { "Exit prompt opened before returning to Home" }
                ui.await("Exit prompt must default to Stay", 2_000) { "app-exit-stay" in ui.snapshot().focused }
                ui.key(KeyEvent.KEYCODE_BACK, 0)
                ui.awaitGone("app-exit-dialog")
                cases.put(JSONObject().put("case", "$style-$source-back").put("steps", steps)
                    .put("home_screenshot", if (homeCaptured) "home-$style.png" else JSONObject.NULL))
                return
            }
        }
        error("$style Back did not exhaust $source to the exit prompt")
    }

    private fun verifyClassicCollection(ui: RenderingSurveyUi, cases: JSONArray, directory: File) {
        check(ui.has("classic-navigation") && ui.has("browse-home"))
        if (ui.snapshot().focused.any { it.startsWith("nav-") }) ui.key(KeyEvent.KEYCODE_DPAD_DOWN)
        val collectionCard = Regex("shelf-\\d+-item-\\d+-(album|playlist)")
        try {
            ui.await("No existing visible collection card", 4_000) { ui.hasPattern(collectionCard) }
        } catch (_: SurveyUnavailable) {
            cases.put(JSONObject().put("case", "classic-collection").put("status", "skipped")
                .put("reason", "Home has no existing visible album or playlist card"))
            return
        }
        val source = ui.snapshot().ids.first(collectionCard::matches)
        ui.focus(source)
        ui.activate(source, "collection")
        ui.awaitId("collection-screen", 8_000)
        ui.awaitId("collection-actions", 4_000)
        val state = ui.snapshot()
        fun bounds(id: String): Rect = requireNotNull(Rect.unflattenFromString(
            state.nodes.first { it.id == id }.bounds))
        val screen = bounds("collection-screen")
        val artwork = bounds("collection-artwork")
        val metadata = bounds("collection-metadata")
        val actions = bounds("collection-actions")
        val songs = bounds("collection-content")
        check(listOf(artwork, metadata, actions, songs).all { !it.isEmpty && screen.contains(it) }) {
            "Classic collection artwork, metadata, actions or song list is clipped"
        }
        check(metadata.right <= songs.left && actions.right <= songs.left && artwork.bottom <= metadata.top + 2) {
            "Classic collection must keep artwork/metadata/actions on the left and songs on the right"
        }
        val loadedSongs = state.nodes.filter { it.id.matches(Regex("collection-song-\\d+")) }
        if (loadedSongs.isNotEmpty()) {
            val rows = loadedSongs.map { requireNotNull(Rect.unflattenFromString(it.bounds)) }
            // A lazy viewport may legitimately clip its first/last row vertically.
            check(rows.any { !it.isEmpty && songs.contains(it) } &&
                rows.all { it.left >= songs.left && it.right <= songs.right }) { "Loaded song list has no complete row or overflows horizontally" }
        }
        check(ui.device.takeScreenshot(File(directory, "classic-collection.png")))
        ui.key(KeyEvent.KEYCODE_BACK, 0)
        ui.awaitGone("collection-screen")
        ui.await("Collection Back did not restore its actual Home source", 4_000) {
            !ui.has("app-exit-dialog") && source in ui.snapshot().focused
        }
        cases.put(JSONObject().put("case", "classic-collection").put("status", "passed")
            .put("source", source).put("song_row_observed", loadedSongs.isNotEmpty())
            .put("screenshot", "classic-collection.png").put("layout", state.json(true)))
    }

    private fun selectedStyle(ui: RenderingSurveyUi): String {
        val selected = ui.snapshot().selected.filter { it == APPLE || it == CLASSIC }
        check(selected.size == 1) { "Interface selection is not unique: $selected" }
        return if (selected.single() == APPLE) "apple" else "classic"
    }

    private fun styleId(style: String): String = when (style) {
        "apple" -> APPLE
        "classic" -> CLASSIC
        else -> error("Unknown interface style $style")
    }

    private fun pid(ui: RenderingSurveyUi): String = ui.device.executeShellCommand("pidof dev.amatv").trim()
        .also { check(it.matches(Regex("[0-9]+"))) { "Target has no unique process" } }

    private data class Media(val token: MediaSession.Token, val identity: String, val stableSong: Boolean,
        val state: Int?, val positionMs: Long)

    private fun media(instrumentation: Instrumentation): Media? {
        val manager = instrumentation.context.getSystemService(MediaSessionManager::class.java)
        val controllers = manager.getActiveSessions(null).filter { it.packageName == "dev.amatv" }
        check(controllers.size <= 1) { "Multiple target media sessions are active" }
        val controller = controllers.singleOrNull() ?: return null
        val metadata = controller.metadata
        val state = controller.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = (state?.position ?: 0L) + if (state?.state == PlaybackState.STATE_PLAYING)
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0L
        // Radio metadata may change naturally; final song seconds can auto-advance.
        val stableSong = duration > 0 && (state?.state != PlaybackState.STATE_PLAYING || duration - position > 5_000)
        val identity = listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM)
            .joinToString("|") { metadata?.getString(it).orEmpty() }
        return Media(controller.sessionToken, identity, stableSong, state?.state, position)
    }

    private companion object {
        const val APPLE = "settings-interface-apple"
        const val CLASSIC = "settings-interface-classic"
        val PLAYER_CONTROL = Regex("player-(previous|play-pause|next|lyrics-display|lyrics|queue|more|radio)")
        val SHELF_CARD = Regex("shelf-(\\d+)-item-(\\d+)-[a-z]+")
    }
}
