package dev.amatv.benchmark

import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Self-instrumented survey of the installed shipping app. No MacrobenchmarkRule,
 * target installation, compilation mode, force-stop, app-data clear or playback seed.
 * Host owns execution and optional streaming Perfetto; this class only drives public UI.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RenderingSurveyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: RenderingSurveyUi
    private lateinit var directory: File
    private lateinit var manifest: JSONObject
    private lateinit var manifestScenes: JSONArray
    private var cadenceMs = 110L
    private var trialCount = 3
    private var runId = ""
    private var captureScreenshots = true
    private val rememberedContent = mutableMapOf<String, String>()
    private val down = KeyEvent.KEYCODE_DPAD_DOWN
    private val up = KeyEvent.KEYCODE_DPAD_UP
    private val left = KeyEvent.KEYCODE_DPAD_LEFT
    private val right = KeyEvent.KEYCODE_DPAD_RIGHT

    @Test fun surveyInstalledStandalone() {
        val arguments = InstrumentationRegistry.getArguments()
        runId = arguments.getString("run_id") ?: "survey-" + System.currentTimeMillis()
        require(runId.matches(Regex("[A-Za-z0-9_.-]+"))) { "Unsafe run_id" }
        cadenceMs = arguments.getString("cadence_ms")?.toLongOrNull() ?: 110L
        captureScreenshots = arguments.getString("capture_screenshots") != "0"
        require(cadenceMs in 90L..120L) { "cadence_ms must be 90..120" }
        val candidateSha = arguments.getString("candidate_sha256").orEmpty()
        require(candidateSha.matches(Regex("[a-fA-F0-9]{64}"))) { "candidate_sha256 must identify the installed candidate" }
        trialCount = if (arguments.getString("initial_pilot") == "1") 1 else 3
        val base = requireNotNull(instrumentation.context.getExternalFilesDir(null))
        val relative = arguments.getString("run_dir") ?: "rendering-survey/" + runId
        require(!File(relative).isAbsolute) { "run_dir is relative to test externalFilesDir" }
        directory = File(base, relative).canonicalFile
        require(directory.path.startsWith(base.canonicalPath + File.separator)) { "run_dir escapes externalFilesDir" }
        require(!File(directory, "manifest.json").exists()) { "Choose a fresh run_dir; evidence is never overwritten" }
        directory.mkdirs()
        manifestScenes = JSONArray()
        manifest = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("candidate_sha256", candidateSha)
            .put("package", TARGET).put("test_package", instrumentation.context.packageName)
            .put("clock", "monotonic").put("trace_clock", "boottime")
            .put("test_pid", android.os.Process.myPid())
            .put("cadence_ms", cadenceMs).put("trial_count", trialCount).put("warmup_count", 1)
            .put("capture_screenshots", captureScreenshots)
            .put("scenes", manifestScenes).put("start_wall_ms", System.currentTimeMillis())
            .put("measurement_notes", JSONArray(listOf(
                "Only fixed scheduled inputs and tail inside measure; no hierarchy/idle queries",
                "Trace sections are in test PID; system FrameTimeline is collected by host",
                "gfxinfo ring may truncate longer windows; host must report coverage",
                "No playback, mode, favorites, playlist-target or account mutations are activated")))
        ui = RenderingSurveyUi(instrumentation)
        try {
            ui.launch()
            val installed = instrumentation.context.packageManager.getPackageInfo(TARGET, 0)
            manifest.put("version_name", installed.versionName).put("version_code", installed.longVersionCode)
            val selected = arguments.getString("scenes")?.split(',')?.filter(String::isNotBlank)?.toSet()
            val scenes = scenes()
            require(selected == null || selected.all { id -> scenes.any { it.id == id } }) { "Unknown scene ID" }
            persist()
            for (scene in scenes) {
                if (selected == null || scene.id in selected) runScene(scene)
            }
            manifest.put("status", "finished")
        } catch (error: Throwable) {
            manifest.put("status", "failed").put("failure_reason", error.message)
            throw error
        } finally {
            manifest.put("end_wall_ms", System.currentTimeMillis())
            persist()
            ui.close()
            instrumentation.sendStatus(2, Bundle().apply {
                putString("rendering_survey_manifest", File(directory, "manifest.json").absolutePath)
                putString("rendering_survey_run_dir", directory.absolutePath)
            })
        }
    }

    private fun keys(code: Int, count: Int) = List(count) { SurveyInput(keyCode = code) }
    private fun mixed(vararg pairs: Pair<Int, Int>) = pairs.flatMap { keys(it.first, it.second) }
    private fun rememberContent(scene: String, content: String) {
        val prior = rememberedContent.putIfAbsent(scene, content)
        if (prior != null && prior != content) throw SurveyUnavailable("Content changed across trials")
    }

    private fun horizontalShelf(tab: String, square: Boolean = false): SurveyPrepared {
        val info = if (square) ui.squareShelf(tab) else { ui.firstShelf(tab); ui.shelfInfo() }
        if (info.column != 0 || info.cardCount < 2) throw SurveyUnavailable("Shelf has no in-bounds horizontal workload")
        val distance = minOf(12, info.cardCount - 1)
        val proof = info.json().put("forward_steps", distance).put("reverse_steps", distance - 1)
            .put("planned_boundary_noops", 0).put("square_row", square)
        return SurveyPrepared("browse-" + tab, mixed(right to distance, left to distance - 1),
            endFocus = Regex("shelf-" + info.row + "-item-1-[a-z]+"), proof = proof)
    }

    private fun scenes(): List<SurveyScene> = buildList {
        for (tab in listOf("home", "browse")) {
            add(SurveyScene(tab + "_idle") {
                ui.firstShelf(tab)
                SurveyPrepared("browse-" + tab, emptyList(), allowSameEndpoint = true, idleDurationMs = 2_000)
            })
            add(SurveyScene(tab + "_horizontal") { horizontalShelf(tab) })
            add(SurveyScene(tab + "_vertical") {
                ui.firstShelf(tab)
                val info = ui.shelfInfo()
                if (info.rowCount < 2) throw SurveyUnavailable("No next shelf row")
                val distance = minOf(12, info.rowCount - 1)
                SurveyPrepared("browse-" + tab, mixed(down to distance, up to distance - 1),
                    endFocus = Regex("shelf-1-item-\\d+-[a-z]+"),
                    proof = info.json().put("forward_steps", distance).put("reverse_steps", distance - 1).put("planned_boundary_noops", 0))
            })
        }
        add(SurveyScene("home_square_horizontal") { horizontalShelf("home", square = true) })
        add(SurveyScene("sidebar_focus") {
            ui.firstShelf("home"); ui.openNavigation(); ui.focus("nav-home")
            SurveyPrepared("nav-panel", mixed(down to 3, up to 1), startFocus = Regex("nav-home"),
                endFocus = Regex("nav-library"), endFocusStableMs = 400,
                proof = JSONObject().put("journey", "home_down_three_up_one_to_library"))
        })
        add(SurveyScene("sidebar_open_close") {
            val source = ui.firstShelf("home"); ui.openNavigation(); ui.focus("nav-home")
            // Repeated Left slots allow the ordinary delayed menu entrance without idle waits.
            val input = List(4) { mixed(right to 1, left to 4) }.flatten() + keys(right, 1)
            SurveyPrepared("nav-panel", input, endContainer = "browse-home", absentAtEnd = "nav-panel",
                startFocus = Regex("nav-home"), endFocus = Regex(Regex.escape(source)))
        })
        for (tab in listOf("browse", "library", "search")) add(SurveyScene("sidebar_page_" + tab) {
            // Warm destination data through its public page before measuring the page action.
            ui.navigate(tab); ui.settle(650)
            ui.firstShelf("home"); ui.openNavigation()
            val activation = ui.verifiedActivation("nav-" + tab, "navigation")
            // Measure the page transition itself. Down events delivered while
            // the old sidebar is closing can change its selection instead of
            // entering the destination and would measure a different journey.
            SurveyPrepared("nav-panel", listOf(SurveyInput(activation = activation, gapAfterMs = 600)),
                endContainer = ui.pageContainer(tab), absentAtEnd = "nav-panel")
        })
        add(SurveyScene("search_type_switch") {
            ui.search("artists"); ui.focus("search-category-artists")
            SurveyPrepared("catalog-search", mixed(right to 6, left to 2),
                startFocus = Regex("search-category-artists"), endFocus = Regex("search-category-applecurators"),
                proof = JSONObject().put("journey", "artists_right_six_left_two_to_applecurators"))
        })
        for (category in listOf("songs", "albums")) add(SurveyScene("search_results_" + category) {
            ui.search(category); ui.focus("search-result-0")
            val input = if (category == "albums") mixed(down to 1, right to 3, left to 1) else keys(down, 16)
            SurveyPrepared("catalog-search", input,
                startFocus = Regex("search-result-0"), endFocus = Regex(if (category == "albums") "search-result-6" else "search-result-16"),
                proof = JSONObject().put("search_category", category).put("query", "NewJeans"))
        })
        add(SurveyScene("library_categories") {
            ui.navigate("library"); ui.focus("library-category-recent")
            SurveyPrepared("library-browser", mixed(down to 4, up to 2),
                startFocus = Regex("library-category-recent"), endFocus = Regex("library-category-artists"))
        })
        add(SurveyScene("library_grid") {
            ui.navigate("library"); ui.activate("library-category-albums", "library_category")
            ui.firstLibraryCard("album")
            SurveyPrepared("library-browser", mixed(down to 10, right to 3),
                endFocus = Regex("library-(item-\\d+-[a-z]+|load-more)"))
        })
        for (kind in listOf("album", "playlist")) {
            for (direction in listOf("down", "up")) add(SurveyScene(kind + "_right_" + direction) {
                val title = ui.openCollection(kind)
                rememberContent(kind, title)
                if (direction == "up") {
                    repeat(5) { ui.key(down, 150) }
                    ui.await("Up-scene start did not reach song five", 4_000) { "collection-song-5" in ui.snapshot().focused }
                    ui.settle(420)
                }
                SurveyPrepared("collection-screen", keys(if (direction == "down") down else up, 5),
                    endFocus = Regex(if (direction == "down") "collection-song-5" else "collection-song-0"),
                    proof = JSONObject().put("catalog_kind", kind).put("content_title", title)
                        .put("selection", ui.collectionSelectionProof).put("steps", 5))
            })
        }
        add(SurveyScene("artist_newjeans_sections") {
            ui.openNewJeans()
            SurveyPrepared("artist-screen", mixed(down to 3, up to 2),
                startFocus = Regex("artist-topsongs-0-song"), endFocus = Regex("artist-[a-z]+-\\d+-[a-z]+"),
                proof = JSONObject().put("catalog_kind", "artist").put("artist", "NewJeans")
                    .put("identity", ui.artistSelectionProof)
                    .put("forward_steps", 3).put("reverse_steps", 2).put("journey", "in_bounds_section_bounce"))
        })
        add(SurveyScene("artist_newjeans_horizontal") {
            ui.openNewJeans()
            val shelf = ui.artistHorizontalShelf()
            val distance = minOf(12, shelf.cardCount - 1)
            SurveyPrepared("artist-screen", mixed(right to distance, left to distance - 1),
                startFocus = Regex(Regex.escape(shelf.firstCardId)),
                endFocus = Regex("artist-" + shelf.section + "-1-[a-z]+"),
                proof = JSONObject().put("catalog_kind", "artist").put("artist", "NewJeans")
                    .put("identity", ui.artistSelectionProof).put("shelf", shelf.json())
                    .put("forward_steps", distance).put("reverse_steps", distance - 1).put("planned_boundary_noops", 0))
        })
        add(SurveyScene("context_menu_open_close") {
            val id = ui.typedCard("album"); ui.focus(id)
            // This proof permits MENU only. It does not execute the album/card click.
            ui.verifiedActivation(id, "collection").close()
            val evidence = JSONArray()
            val input = List(3) { listOf(SurveyInput(keyCode = KeyEvent.KEYCODE_MENU, gapAfterMs = 550),
                SurveyInput(keyCode = KeyEvent.KEYCODE_BACK, gapAfterMs = 350)) }.flatten()
            SurveyPrepared(if (id.startsWith("library-")) "library-browser" else "browse-home",
                input, absentAtEnd = "catalog-actions-list",
                startFocus = Regex(Regex.escape(id)), endFocus = Regex(Regex.escape(id)),
                visibleHomeCardAtStart = id.takeIf { it.startsWith("shelf-") },
                visibleHomeCardAtEnd = id.takeIf { it.startsWith("shelf-") },
                allowSameEndpoint = true, proof = JSONObject().put("menu_source", id).put("catalog_kind", "album"),
                warmupEvidence = evidence, warmupProbe = { index, event ->
                    val opening = event.keyCode == KeyEvent.KEYCODE_MENU
                    observeMenuStep(index, opening, evidence)
                })
        })
        add(SurveyScene("context_menu_focus") {
            openMenu()
            SurveyPrepared("catalog-actions-list", mixed(down to 7, up to 2), endFocus = Regex("catalog-action-.*"),
                proof = JSONObject().put("initial_menu_focus", ui.menuInitialFocusProof))
        })
        add(SurveyScene("context_menu_long_press") {
            val id = ui.homeCard("album", smallOnly = true)
            val sourceVisibility = ui.homeCardVisibility(id)
            val heldPress = ui.verifiedAlbumLongPress(id)
            val evidence = JSONArray()
            SurveyPrepared("browse-home",
                listOf(SurveyInput(longPress = heldPress, gapAfterMs = 550),
                    SurveyInput(keyCode = KeyEvent.KEYCODE_BACK, gapAfterMs = 350)),
                absentAtEnd = "catalog-actions-list", startFocus = Regex(Regex.escape(id)),
                endFocus = Regex(Regex.escape(id)), allowSameEndpoint = true,
                visibleHomeCardAtStart = id, visibleHomeCardAtEnd = id,
                proof = JSONObject().put("journey", "native_held_dpad_center_album_menu")
                    .put("menu_source", id).put("hold_ms", heldPress.holdMs).put("menu_item_activations", 0)
                    .put("source_visibility", sourceVisibility),
                warmupEvidence = evidence, warmupProbe = { index, input ->
                    observeMenuStep(index, input.longPress != null, evidence)
                })
        })
        add(SurveyScene("context_menu_ellipsis") {
            val title = ui.openCollection("album")
            rememberContent("album", title)
            ui.focus("collection-more")
            val activation = ui.verifiedActivation("collection-more", "context_menu")
            val evidence = JSONArray()
            SurveyPrepared("collection-screen", listOf(SurveyInput(activation = activation, gapAfterMs = 550),
                    SurveyInput(keyCode = KeyEvent.KEYCODE_BACK, gapAfterMs = 350)),
                absentAtEnd = "catalog-actions-list", startFocus = Regex("collection-more"),
                endFocus = Regex("collection-more"), allowSameEndpoint = true,
                proof = JSONObject().put("journey", "public_collection_more_button_menu")
                    .put("content_title", title).put("selection", ui.collectionSelectionProof)
                    .put("menu_source", "collection-more").put("menu_item_activations", 0),
                warmupEvidence = evidence, warmupProbe = { index, input ->
                    observeMenuStep(index, input.activation != null, evidence)
                })
        })
        add(SurveyScene("paused_player_controls") {
            ui.pausedPlayer(queue = false)
            SurveyPrepared("player-screen", keys(right, 5), requirePaused = true)
        })
        add(SurveyScene("paused_existing_queue") {
            ui.pausedPlayer(queue = true)
            SurveyPrepared("queue-panel", keys(right, 16), requirePaused = true)
        })
        add(SurveyScene("paused_player_idle") {
            ui.pausedPlayer(queue = false)
            ui.key(KeyEvent.KEYCODE_BACK, 420)
            if (!ui.has("player-screen")) throw SurveyUnavailable("Back did not retain the existing player")
            ui.awaitGone("player-controls")
            SurveyPrepared("player-screen", emptyList(), requirePaused = true, allowSameEndpoint = true, idleDurationMs = 2_000)
        })
        add(SurveyScene("settings_focus") {
            ui.prepareSettingsFocus()
            SurveyPrepared("browse-account", mixed(right to 3, left to 2), endFocus = Regex("language-en"),
                proof = JSONObject().put("journey", "horizontal_settings_language_button_focus")
                    .put("forward_steps", 3).put("reverse_steps", 2).put("activations", 0))
        })
        for (kind in listOf("album", "playlist")) add(SurveyScene(kind + "_back_to_source") {
            val title = ui.openCollection(kind)
            rememberContent(kind, title)
            val selection = ui.collectionSelectionProof
            val sourceCard = selection.optString("card_id")
            val sourceContainer = when {
                Regex("library-item-\\d+-" + kind).matches(sourceCard) -> "library-browser"
                Regex("shelf-\\d+-item-\\d+-" + kind).matches(sourceCard) -> "browse-home"
                else -> throw SurveyUnsafe("Collection Back has no proven source card")
            }
            SurveyPrepared("collection-screen", keys(KeyEvent.KEYCODE_BACK, 1),
                endContainer = sourceContainer, absentAtEnd = "collection-screen",
                endFocus = Regex(Regex.escape(sourceCard)),
                visibleHomeCardAtEnd = sourceCard.takeIf { it.startsWith("shelf-") },
                proof = JSONObject().put("journey", "collection_back_restores_exact_source")
                    .put("catalog_kind", kind).put("content_title", title).put("selection", selection)
                    .put("source_container", sourceContainer).put("expected_source_card", sourceCard))
        })
        add(SurveyScene("artist_back_to_search") {
            ui.openNewJeans()
            val sourceResult = ui.artistSelectionProof.optString("result_id")
            if (!Regex("search-result-\\d+").matches(sourceResult))
                throw SurveyUnsafe("Artist Back has no canonical search-result source")
            SurveyPrepared("artist-screen", keys(KeyEvent.KEYCODE_BACK, 1),
                endContainer = "catalog-search", absentAtEnd = "artist-screen",
                endFocus = Regex(Regex.escape(sourceResult)),
                proof = JSONObject().put("journey", "artist_back_restores_canonical_search_result")
                    .put("identity", ui.artistSelectionProof).put("expected_search_result", sourceResult))
        })
        add(SurveyScene("artist_left_sidebar") {
            ui.openNewJeans()
            ui.focus("artist-topsongs-0-song")
            SurveyPrepared("artist-screen", keys(left, 1), endContainer = "nav-panel",
                endFocus = Regex("nav-search"),
                proof = JSONObject().put("journey", "artist_first_column_left_opens_current_sidebar")
                    .put("identity", ui.artistSelectionProof).put("start_card", "artist-topsongs-0-song"))
        })
        add(SurveyScene("sidebar_back_home_focus") {
            ui.firstShelf("home"); ui.openNavigation(); ui.focus("nav-library"); ui.settle(150)
            ui.await("Sidebar Back setup did not retain Library highlight", 4_000) {
                "nav-library" in ui.snapshot().focused
            }
            SurveyPrepared("nav-panel", keys(KeyEvent.KEYCODE_BACK, 1), endFocus = Regex("nav-home"),
                proof = JSONObject().put("journey", "sidebar_back_moves_highlight_home_without_activating")
                    .put("start_focus", "nav-library").put("expected_focus", "nav-home").put("activations", 0))
        })
        add(SurveyScene("paused_player_back_to_home") {
            ui.pausedPlayer(queue = false)
            ui.awaitId("player-controls")
            SurveyPrepared("player-screen", List(2) { SurveyInput(keyCode = KeyEvent.KEYCODE_BACK, gapAfterMs = 350) },
                endContainer = "browse-home", absentAtEnd = "player-screen", requirePausedAtStart = true,
                proof = JSONObject().put("journey", "paused_player_back_hides_controls_then_returns_home")
                    .put("start_controls_visible", true).put("start_playback_state", "paused")
                    .put("end_playback_state_observation", "player_not_visible_after_exit")
                    .put("activations", 0))
        })
    }

    private fun openMenu(): String {
        val id = ui.typedCard("album")
        ui.focus(id)
        ui.verifiedActivation(id, "collection").close()
        ui.key(KeyEvent.KEYCODE_MENU, 450)
        ui.awaitId("catalog-actions-list")
        ui.assertNaturalFirstMenuFocus()
        return id
    }

    private fun observeMenuStep(index: Int, opening: Boolean, evidence: JSONArray) {
        val initialFocus = if (opening) ui.assertNaturalFirstMenuFocus() else null
        val visible = ui.has("catalog-actions-list")
        evidence.put(JSONObject().put("input_index", index).put("menu_visible", visible)
            .put("expected_visible", opening).put("verified_ns", System.nanoTime())
            .put("natural_first_focus", initialFocus ?: JSONObject.NULL))
        if (visible != opening) throw SurveyUnavailable("Menu did not complete warmup open/close")
    }

    private fun runScene(scene: SurveyScene) {
        val trials = JSONArray()
        val result = JSONObject().put("scene_id", scene.id).put("status", "pending").put("trials", trials)
            .put("workload", if (scene.id.endsWith("_idle")) "idle" else "interaction")
        manifestScenes.put(result); persist()
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "SURVEY_BEGIN " + scene.id + "\n") })
        try {
            val warmup = scene.setup()
            val warmupFile = scene.id + "-warmup.json"
            val warmupRecord = JSONObject().put("phase", "warmup").put("scene_id", scene.id)
                .put("excluded_from_measurement", true).put("status", "prepared")
                .put("expected_endpoint", warmup.endpointJson())
                .put("input_plan", JSONArray(warmup.inputs.map { it.label }))
                .put("input_gaps_ms", JSONArray(warmup.inputs.map { it.gapAfterMs ?: cadenceMs }))
                .put("setup_owner_recoveries", ui.consumeSetupOwnerRecoveries())
                .put("proof", warmup.proof).put("evidence_file", warmupFile)
            result.put("warmup", warmupRecord)
            val warmupActual = JSONArray()
            try {
                val before = ui.snapshot()
                warmupRecord.put("start_screenshot", captureScreenshot(scene.id + "-warmup-start"))
                warmupRecord.put("start", before.json(detailed = true)).put("start_ns", System.nanoTime())
                File(directory, warmupFile).writeText(warmupRecord.toString(2)); persist()
                validateStart(warmup, before)
                if (warmup.visibleHomeCardAtStart != null)
                    warmupRecord.put("start_home_card_visibility", ui.lastHomeCardVisibilityProof)
                var driveFailure: Throwable? = null
                try { drive(warmup.inputs, warmupActual, warmup.warmupProbe, warmup.idleDurationMs) }
                catch (error: Throwable) { driveFailure = error }
                val after = ui.snapshot()
                // Persist the observation before endpoint validation can throw.
                warmupRecord.put("end", after.json(detailed = true)).put("initial_end", after.json(detailed = true))
                    .put("end_ns", System.nanoTime())
                    .put("input_actual", warmupActual).put("semantic_evidence", warmup.warmupEvidence)
                    .put("status", "observed")
                File(directory, warmupFile).writeText(warmupRecord.toString(2)); persist()
                driveFailure?.let { throw it }
                val endpointError = settleEndpoint(warmup, before, after, warmupRecord, detailed = true)
                warmupRecord.put("end_screenshot", captureScreenshot(scene.id + "-warmup-end"))
                File(directory, warmupFile).writeText(warmupRecord.toString(2)); persist()
                endpointError?.let { throw SurveyUnavailable("Warmup: " + it) }
                warmupRecord.put("status", "ok")
                result.put("warmup_semantic_evidence", warmup.warmupEvidence)
            } catch (error: Throwable) {
                warmupRecord.put("status", "failed").put("skip_reason", error.message)
                    .put("input_actual", warmupActual).put("failure_snapshot", safeSnapshotJson())
                throw error
            } finally {
                result.put("warmup_semantic_evidence", warmup.warmupEvidence)
                warmupRecord.put("semantic_evidence", warmup.warmupEvidence)
                File(directory, warmupFile).writeText(warmupRecord.toString(2))
                event(warmupRecord); persist(); warmup.close()
            }
            for (trial in 1..trialCount) {
                val record = measure(scene, trial)
                trials.put(record); persist()
                if (record.optBoolean("abort_run")) throw SurveyUnsafe(record.optString("skip_reason"))
                if (record.optString("status") != "ok") {
                    result.put("status", record.optString("status")).put("skip_reason", record.optString("skip_reason"))
                    // A failed endpoint is not silently substituted with a different journey.
                    for (remaining in trial + 1..trialCount) trials.put(skip(scene.id, remaining, "previous_trial_not_ok"))
                    persist()
                    return
                }
            }
            result.put("status", "ok")
        } catch (unavailable: SurveyUnavailable) {
            result.put("status", "skip").put("skip_reason", unavailable.message).put("skip_snapshot", safeSnapshotJson())
                .put("failure_screenshot", captureScreenshot(scene.id + "-setup-failure"))
            for (trial in 1..trialCount) trials.put(skip(scene.id, trial, unavailable.message.orEmpty()))
        } catch (unsafe: SurveyUnsafe) {
            result.put("status", "failed").put("skip_reason", unsafe.message).put("failure_snapshot", safeSnapshotJson())
                .put("failure_screenshot", captureScreenshot(scene.id + "-unsafe-failure"))
            event(JSONObject().put("phase", "setup").put("scene_id", scene.id).put("status", "failed").put("skip_reason", unsafe.message))
            persist()
            throw unsafe // Unexpected foreground/activation/injection aborts the whole run.
        } finally { persist() }
    }

    private fun skip(scene: String, trial: Int, reason: String) = JSONObject()
        .put("scene_id", scene).put("trial", trial).put("phase", "measure").put("status", "skip")
        .put("workload", if (scene.endsWith("_idle")) "idle" else "interaction")
        .put("skip_reason", reason).put("clock", "monotonic").put("skip_snapshot", safeSnapshotJson())
        .apply { if (trial == 1 && reason != "previous_trial_not_ok") put("failure_screenshot", captureScreenshot(scene + "-trial-1-setup-failure")) }

    /** Only called outside measured/warmup input bodies; failures never turn a route into success. */
    private fun captureScreenshot(prefix: String): JSONObject {
        val record = JSONObject().put("captured_ns", System.nanoTime()).put("clock", "monotonic")
        if (!captureScreenshots) return record.put("status", "disabled")
        return try {
            if (ui.foreground() != TARGET) record.put("status", "skipped_foreign_foreground")
            else {
                val name = prefix + ".png"
                val captured = ui.device.takeScreenshot(File(directory, name))
                record.put("status", if (captured) "ok" else "failed").put("path", name)
                    .put("completed_ns", System.nanoTime())
            }
        } catch (error: Throwable) {
            record.put("status", "failed").put("error", error.javaClass.simpleName + ":" + error.message)
        }
    }

    private fun safeSnapshotJson(): JSONObject = try {
        ui.snapshot().json(detailed = true).put("captured_ns", System.nanoTime())
            .put("setup_owner_recoveries", ui.consumeSetupOwnerRecoveries()).put("artist_identity", ui.artistSelectionProof)
            .put("natural_menu_focus", ui.menuInitialFocusProof)
            .put("home_card_visibility", ui.lastHomeCardVisibilityProof)
    } catch (error: Throwable) {
        JSONObject().put("snapshot_error", error.javaClass.simpleName + ":" + error.message)
            .put("captured_ns", System.nanoTime())
            .put("home_card_visibility", ui.lastHomeCardVisibilityProof)
    }

    private fun validateStart(prepared: SurveyPrepared, snapshot: SurveySnapshot) {
        ui.requireForeground()
        if (prepared.container !in snapshot.ids) throw SurveyUnavailable("Start container missing: " + prepared.container)
        if (prepared.startFocus != null && snapshot.focused.none(prepared.startFocus::matches))
            throw SurveyUnavailable("Start focus does not match the prepared source")
        if (prepared.visibleHomeCardAtStart != null && !ui.homeCardVisibility(prepared.visibleHomeCardAtStart).optBoolean("complete"))
            throw SurveyUnavailable("Start Home cover/captions are clipped")
        if ((prepared.requirePaused || prepared.requirePausedAtStart) && !ui.playerPaused())
            throw SurveyUnavailable("Player is not paused")
    }
    private fun validateEnd(prepared: SurveyPrepared, start: SurveySnapshot, end: SurveySnapshot): String? {
        ui.requireForeground()
        if (prepared.requirePaused && !ui.playerPaused()) throw SurveyUnsafe("Playback changed during read-only scene")
        if (prepared.endContainer !in end.ids) return "endpoint_container_missing:" + prepared.endContainer
        if (prepared.absentAtEnd != null && prepared.absentAtEnd in end.ids) return "endpoint_overlay_still_present"
        if (prepared.endFocus != null && end.focused.none(prepared.endFocus::matches)) return "endpoint_focus_mismatch"
        if (prepared.visibleHomeCardAtEnd != null && !ui.homeCardVisibility(prepared.visibleHomeCardAtEnd).optBoolean("complete"))
            return "endpoint_home_cover_or_captions_clipped"
        if (!prepared.allowSameEndpoint && start.fingerprint == end.fingerprint) return "no_observed_ui_movement"
        return null
    }
    /** Observation only, strictly outside measured END; never injects corrective input. */
    private fun settleEndpoint(prepared: SurveyPrepared, start: SurveySnapshot, initial: SurveySnapshot,
        record: JSONObject, detailed: Boolean = false): String? {
        val begin = System.nanoTime()
        val deadline = begin + 5_000_000_000L
        val observations = JSONArray()
        var current = initial
        var error: String? = null
        var matchingSince = 0L
        try {
            while (true) {
                error = validateEnd(prepared, start, current)
                observations.put(JSONObject().put("verified_ns", System.nanoTime())
                    .put("focus", JSONArray(current.focused)).put("foreground", current.foreground)
                    .put("error", error ?: JSONObject.NULL))
                val now = System.nanoTime()
                if (error == null) {
                    if (matchingSince == 0L) matchingSince = now
                    if (now - matchingSince >= prepared.endFocusStableMs * 1_000_000L) return null
                } else matchingSince = 0L
                if (now >= deadline) return error ?: "endpoint_focus_not_stable"
                SystemClock.sleep(120)
                current = ui.snapshot()
            }
        } finally {
            record.put("end", current.json(detailed)).put("endpoint_observations", observations)
                .put("endpoint_verification_delay_ns", System.nanoTime() - begin)
                .put("endpoint_after_end_ns", (System.nanoTime() - record.optLong("end_ns", begin)).coerceAtLeast(0L))
                .put("endpoint_verified_ns", System.nanoTime()).put("endpoint_settle_limit_ms", 5_000)
                .put("endpoint_focus_stability_required_ms", prepared.endFocusStableMs)
            if (prepared.visibleHomeCardAtEnd != null)
                record.put("end_home_card_visibility", ui.lastHomeCardVisibilityProof)
        }
    }

    private fun measure(scene: SurveyScene, trial: Int): JSONObject {
        val prepared = try { scene.setup() } catch (unavailable: SurveyUnavailable) {
            return skip(scene.id, trial, unavailable.message.orEmpty())
        }
        try {
            val before = ui.snapshot()
            val prefix = scene.id + "-trial-" + trial
            val ownerRecoveries = ui.consumeSetupOwnerRecoveries()
            val windowFile = if (scene.id == "context_menu_focus") prefix + "-window-before.txt" else null
            if (windowFile != null) File(directory, windowFile).writeText(ui.device.executeShellCommand("dumpsys window windows"))
            File(directory, prefix + "-preflight.json").writeText(JSONObject()
                .put("scene_id", scene.id).put("trial", trial).put("phase", "setup")
                .put("start", before.json(detailed = true)).put("expected_endpoint", prepared.endpointJson())
                .put("setup_owner_recoveries", ownerRecoveries).put("window_before", windowFile ?: JSONObject.NULL)
                .put("input_plan", JSONArray(prepared.inputs.map { it.label })).put("proof", prepared.proof).toString(2))
            validateStart(prepared, before)
            val resetFile = prefix + "-reset.txt"
            File(directory, resetFile).writeText(ui.device.executeShellCommand("dumpsys gfxinfo " + TARGET + " reset"))
            val record = JSONObject().put("scene_id", scene.id).put("trial", trial).put("phase", "measure")
                .put("clock", "monotonic").put("trace_clock", "boottime").put("cadence_ms", cadenceMs)
                .put("input_plan", JSONArray(prepared.inputs.map { it.label })).put("start", before.json())
                .put("input_gaps_ms", JSONArray(prepared.inputs.map { it.gapAfterMs ?: cadenceMs }))
                .put("workload", if (prepared.idleDurationMs > 0) "idle" else "interaction")
                .put("idle_duration_ms", prepared.idleDurationMs)
                .put("expected_endpoint", prepared.endpointJson())
                .put("start_home_card_visibility", if (prepared.visibleHomeCardAtStart != null) ui.lastHomeCardVisibilityProof else JSONObject.NULL)
                .put("setup_owner_recoveries", ownerRecoveries).put("window_before", windowFile ?: JSONObject.NULL)
                .put("proof", prepared.proof).put("gfxinfo_reset", resetFile)
                .put("app_pids", JSONArray(ui.device.executeShellCommand("pidof " + TARGET).trim()
                    .split(Regex("\\s+")).mapNotNull(String::toIntOrNull)))
            val actual = JSONArray()
            val startNs = System.nanoTime()
            val traceStartNs = SystemClock.elapsedRealtimeNanos()
            val wallStart = System.currentTimeMillis()
            record.put("start_ns", startNs).put("trace_start_ns", traceStartNs).put("start_wall_ms", wallStart)
            val section = ("survey:" + scene.id + ":" + trial).take(120)
            Trace.beginSection(section)
            Log.i("AMATV-SURVEY", JSONObject().put("marker", "BEGIN").put("scene_id", scene.id).put("trial", trial)
                .put("start_ns", startNs).put("trace_start_ns", traceStartNs).toString())
            var driveFailure: Throwable? = null
            try { drive(prepared.inputs, actual, idleDurationMs = prepared.idleDurationMs) } catch (error: Throwable) { driveFailure = error }
            val endNs = System.nanoTime()
            val traceEndNs = SystemClock.elapsedRealtimeNanos()
            val wallEnd = System.currentTimeMillis()
            Trace.endSection()
            Log.i("AMATV-SURVEY", JSONObject().put("marker", "END").put("scene_id", scene.id).put("trial", trial)
                .put("end_ns", endNs).put("trace_end_ns", traceEndNs).toString())
            record.put("end_ns", endNs).put("trace_end_ns", traceEndNs).put("end_wall_ms", wallEnd)
                .put("input_actual", actual).put("duration_ns", endNs - startNs)
            val gfxFile = prefix + "-gfxinfo.txt"
            val gfx = ui.device.executeShellCommand("dumpsys gfxinfo " + TARGET + " framestats")
            File(directory, gfxFile).writeText(gfx)
            record.put("gfxinfo", gfxFile)
            val after = ui.snapshot() // Only after END and raw dump, outside measurement.
            record.put("end", after.json()).put("initial_end", after.json()).put("endpoint_verified_ns", System.nanoTime())
            File(directory, prefix + ".json").writeText(record.put("status", "pending_validation").toString(2))
            var unsafeFailure = driveFailure as? SurveyUnsafe
            val endpointError = try { settleEndpoint(prepared, before, after, record) } catch (unsafe: SurveyUnsafe) {
                unsafeFailure = unsafe
                unsafe.message
            }
            val frameCount = windowFrameCount(gfx, startNs, endNs)
            val hasFrameCapture = gfx.lineSequence().any { it.trim().startsWith("Flags,") && "IntendedVsync" in it }
            val error = driveFailure?.message ?: endpointError ?: when {
                !hasFrameCapture -> "missing_frame_capture"
                frameCount == 0 && prepared.idleDurationMs == 0L -> "no_measured_frames"
                else -> null
            }
            record.put("in_window_frame_rows", frameCount)
                .put("frame_capture_present", hasFrameCapture)
                .put("frame_activity", when {
                    !hasFrameCapture -> "missing_capture"
                    frameCount == 0 && prepared.idleDurationMs > 0 -> "quiescent"
                    else -> "sampled"
                })
                .put("gfxinfo_ring_may_be_incomplete", true)
                .put("abort_run", unsafeFailure != null)
                .put("status", if (error == null) "ok" else "failed")
            if (error != null) record.put("skip_reason", error)
            if (frameCount == 0) record.put("gpu_quantiles", JSONObject.NULL)
            record.put("end_screenshot", captureScreenshot(prefix + "-end"))
            if (scene.id == "artist_left_sidebar") {
                waitUntil(endNs + 1_000_000_000L)
                val settled = ui.snapshot()
                record.put("settled_snapshot", settled.json())
                    .put("settled_nav_search_focused", "nav-search" in settled.focused)
                    .put("settled_nav_search_selected", "nav-search" in settled.selected)
                    .put("settled_screenshot", captureScreenshot(prefix + "-settled"))
                if ("nav-panel" !in settled.ids || "nav-search" !in settled.focused) {
                    record.put("status", "failed").put("skip_reason", "settled_sidebar_focus_not_retained")
                }
            }
            event(record)
            File(directory, prefix + ".json").writeText(record.toString(2))
            return record
        } catch (unavailable: SurveyUnavailable) {
            return skip(scene.id, trial, unavailable.message.orEmpty())
        } finally { prepared.close() }
    }

    private fun drive(inputs: List<SurveyInput>, actual: JSONArray?,
        warmupProbe: ((Int, SurveyInput) -> Unit)? = null, idleDurationMs: Long = 0) {
        if (idleDurationMs > 0) {
            check(inputs.isEmpty()) { "Idle scene cannot contain input" }
            SystemClock.sleep(idleDurationMs)
            return
        }
        val zero = System.nanoTime()
        var offsetNs = 0L
        var nextNotBefore = zero
        for ((index, input) in inputs.withIndex()) {
            val planned = zero + offsetNs
            val scheduled = maxOf(planned, nextNotBefore)
            waitUntil(scheduled)
            val begin = System.nanoTime()
            if (input.activation != null) {
                if (!input.activation.execute()) throw SurveyUnsafe("Validated activation became stale: " + input.label)
            } else if (input.longPress != null) {
                input.longPress.execute()
            } else ui.inject(requireNotNull(input.keyCode))
            val finish = System.nanoTime()
            actual?.put(JSONObject().put("index", index).put("input", input.label).put("scheduled_ns", planned)
                .put("effective_not_before_ns", scheduled)
                .put("actual_ns", begin).put("injection_duration_ns", finish - begin)
                .put("intentional_hold_ms", input.longPress?.holdMs ?: 0L)
                .put("late_ns", (begin - planned).coerceAtLeast(0L)))
            offsetNs += (input.gapAfterMs ?: cadenceMs) * 1_000_000L
            // Explicit menu holds are minimum post-dispatch times, even if an
            // injection is slow. Warmup-only binder probes must not compress Back.
            nextNotBefore = maxOf(zero + offsetNs,
                finish + (input.gapAfterMs ?: 0L) * 1_000_000L)
            if (warmupProbe != null) {
                waitUntil(nextNotBefore)
                warmupProbe(index, input)
                offsetNs = System.nanoTime() - zero
                nextNotBefore = zero + offsetNs
            }
        }
        waitUntil(maxOf(zero + offsetNs, nextNotBefore))
        // Fixed animation drain, not an idle/hierarchy condition.
        SystemClock.sleep(420)
    }
    private fun waitUntil(deadline: Long) {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            SystemClock.sleep((remaining + 999_999L) / 1_000_000L)
        }
    }

    private fun windowFrameCount(raw: String, start: Long, end: Long): Int {
        var intended = -1
        var flags = -1
        var count = 0
        for (line in raw.lineSequence()) {
            val parts = line.trim().split(',')
            if (parts.firstOrNull() == "Flags") {
                intended = parts.indexOf("IntendedVsync"); flags = parts.indexOf("Flags")
            } else if (intended >= 0 && flags >= 0) {
                val vsync = parts.getOrNull(intended)?.toLongOrNull() ?: continue
                if (parts.getOrNull(flags)?.toIntOrNull() == 0 && vsync in start..end) count++
            }
        }
        return count
    }
    private fun event(value: JSONObject) {
        File(directory, "events.jsonl").appendText(value.toString() + "\n")
        Log.i("AMATV-SURVEY", value.toString())
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "SURVEY_RESULT " + value.optString("scene_id") + " " +
                value.optString("phase") + " " + value.optInt("trial", 0) + " " + value.optString("status") + "\n")
        })
    }
    private fun persist() { File(directory, "manifest.json").writeText(manifest.toString(2)) }
}

private class SurveyScene(val id: String, val setup: () -> SurveyPrepared)
private class SurveyPrepared(
    val container: String,
    val inputs: List<SurveyInput>,
    val endContainer: String = container,
    val absentAtEnd: String? = null,
    val startFocus: Regex? = null,
    val endFocus: Regex? = null,
    val endFocusStableMs: Long = 0,
    val visibleHomeCardAtStart: String? = null,
    val visibleHomeCardAtEnd: String? = null,
    val requirePaused: Boolean = false,
    val requirePausedAtStart: Boolean = false,
    val allowSameEndpoint: Boolean = false,
    val idleDurationMs: Long = 0,
    val proof: JSONObject = JSONObject(),
    val warmupEvidence: JSONArray = JSONArray(),
    val warmupProbe: ((Int, SurveyInput) -> Unit)? = null,
) : AutoCloseable {
    override fun close() { inputs.forEach { it.activation?.close() } }
    fun endpointJson(): JSONObject = JSONObject().put("start_container", container)
        .put("end_container", endContainer).put("absent_at_end", absentAtEnd ?: JSONObject.NULL)
        .put("focus_regex", endFocus?.pattern ?: JSONObject.NULL)
        .put("start_focus_regex", startFocus?.pattern ?: JSONObject.NULL)
        .put("end_focus_stable_ms", endFocusStableMs)
        .put("fully_visible_home_card_at_start", visibleHomeCardAtStart ?: JSONObject.NULL)
        .put("fully_visible_home_card_at_end", visibleHomeCardAtEnd ?: JSONObject.NULL)
        .put("require_paused", requirePaused).put("require_paused_at_start", requirePausedAtStart)
        .put("allow_same_endpoint", allowSameEndpoint)
}
private class SurveyInput(val keyCode: Int? = null, val activation: SurveyActivation? = null,
    val longPress: SurveyLongPress? = null, val gapAfterMs: Long? = null) {
    val label: String get() = activation?.let { "activate:" + it.id + ":" + it.kind }
        ?: longPress?.let { "long_press_album:" + it.id + ":hold_ms=" + it.holdMs }
        ?: KeyEvent.keyCodeToString(requireNotNull(keyCode))
}
