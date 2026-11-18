package dev.amatv.benchmark

import android.content.ComponentName
import android.content.Intent
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real production composables/controllers, benchmark-only host, gated local HTTP data. */
@RunWith(AndroidJUnit4::class)
class NavigationRaceFixtureTest {
    @Test fun homeHeaderRoutesAndBackRetainTheirActualEntryAndCard() {
        runCase("home-insights-headers", "home") { ui, http, report ->
            http.releaseFirst.countDown()
            val card = "shelf-0-item-0-song"
            val all = "home-shelf-recent-tracks:recent-tracks-insights-recenttracks"
            val replay = "home-shelf-recent-tracks:recent-tracks-insights-replay"
            ui.awaitId(card, 3_000L)
            ui.focus(card)
            ui.inject(KeyEvent.KEYCODE_DPAD_UP)
            ui.await("Card Up did not enter its Show All header", 3_000L) { all in ui.snapshot().focused }
            check(!ui.has(replay)) { "Hidden Replay must not be restored by a fixture" }
            activateHeader(ui, all)
            ui.await("Show All did not deliver RecentTracks", 3_000L) { ui.label("fixture-home-target") == "RecentTracks" }
            ui.inject(KeyEvent.KEYCODE_BACK)
            ui.await("Show All Back did not restore that same entry", 3_000L) { all in ui.snapshot().focused }
            ui.inject(KeyEvent.KEYCODE_DPAD_DOWN)
            ui.await("Header Down did not return to its source card", 3_000L) { card in ui.snapshot().focused }
            report.put("real_callback_sources", JSONArray(listOf("RecentTracks")))
        }
        for ((scenario, heading, target) in listOf(
            Triple("home-resource", "home-shelf-fixture-public-see-all", "Resource"),
            Triple("radio", "home-shelf-fixture-radio-station-genres", "StationGenres"),
        )) runCase("$scenario-header", scenario) { ui, http, report ->
            http.releaseFirst.countDown()
            val card = "shelf-0-item-0-song"
            ui.awaitId(card, 3_000L)
            ui.focus(card)
            ui.inject(KeyEvent.KEYCODE_DPAD_UP)
            ui.await("Card Up did not reach $heading", 3_000L) { heading in ui.snapshot().focused }
            activateHeader(ui, heading)
            ui.await("Header did not route to $target", 3_000L) { ui.label("fixture-home-target") == target }
            ui.inject(KeyEvent.KEYCODE_BACK)
            ui.await("Back did not restore $heading", 3_000L) { heading in ui.snapshot().focused }
            ui.inject(KeyEvent.KEYCODE_DPAD_DOWN)
            ui.await("Header Down did not restore its source card", 3_000L) { card in ui.snapshot().focused }
            report.put("real_callback_source", target)
        }
    }

    @Test fun insightsLoadingCanBeWithdrawnByUpLeftAndBack() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) {
            runCase("insights-$key", "insights") { ui, http, report ->
                ui.await("Loading Insights did not retain its actual navigation owner", 3_000L) {
                    val snapshot = ui.snapshot()
                    "catalog-collection-empty" in snapshot.ids && "fixture-menu" in snapshot.focused
                }
                val loading = ui.snapshot()
                val placeholder = loading.nodes.single { it.id == "catalog-collection-empty" }
                check(!placeholder.focusable && !placeholder.clickable) {
                    "Loading text must not become an artificial input target"
                }
                check("catalog-collection-item-0-song" !in loading.ids) { "Fixture response escaped its gate" }
                report.put("loading_navigation_owner", loading.json(true)).put("withdrawal_key", key)
                ui.inject(key)
                // Loading has no actionable body. Up/Left keep the real menu;
                // Back closes the visit before its pending HTTP response.
                val expected = if (key == KeyEvent.KEYCODE_BACK) "fixture-closed" else "fixture-menu"
                ui.await("Withdrawal did not retain the expected navigation owner", 3_000L) {
                    expected in ui.snapshot().focused
                }
                report.put("before_response", ui.snapshot().json(true))
                http.releaseFirst.countDown()
                check(http.firstFinished.await(3, TimeUnit.SECONDS))
                if (key != KeyEvent.KEYCODE_BACK) ui.await("Actual late Insights data was not rendered", 3_000L) {
                    ui.has("catalog-collection-item-0-song")
                }
                assertOwner(ui, expected, "Listening Insights reclaimed a withdrawn loading entry")
                if (key == KeyEvent.KEYCODE_BACK) check(!ui.has("listening-insights"))
            }
        }
    }

    @Test fun resourceEmptyContinuationAndNativeModalKeepActualOwners() {
        runCase("resource-empty-chain", "resource") { ui, http, report ->
            val section = "resource-section-playlists:false:"
            ui.focus(section)
            check(ui.snapshot().focused.any { it == section })
            http.releaseFirst.countDown()
            check(http.secondStarted.await(3, TimeUnit.SECONDS)) { "Real empty-page continuation did not dispatch" }
            check(ui.has(section) && "fixture-menu" !in ui.snapshot().focused) {
                "An empty intermediate page with a real next cursor lost its focus anchor"
            }
            report.put("intermediate_empty_with_next", ui.snapshot().json(true))
            http.releaseSecond.countDown()
            ui.await("Terminal empty shelf did not release focus", 3_000L) {
                !ui.has(section) && "fixture-menu" in ui.snapshot().focused
            }
            assertOwner(ui, "fixture-menu", "Terminal empty resource failed to settle")
        }
        runCase("resource-modal", "resource-modal") { ui, http, report ->
            ui.focus("resource-section-playlists:false:")
            ui.inject(KeyEvent.KEYCODE_MENU)
            ui.await("Real native fixture modal did not own focus", 3_000L) {
                "fixture-modal-action" in ui.snapshot().focused
            }
            http.releaseFirst.countDown()
            check(http.firstFinished.await(3, TimeUnit.SECONDS))
            assertOwner(ui, "fixture-modal-action", "Resource completion stole native modal focus")
            report.put("modal_after_response", ui.snapshot().json(true))
            ui.inject(KeyEvent.KEYCODE_BACK)
            ui.await("Modal Back did not restore the real page menu", 3_000L) {
                !ui.has("fixture-modal-action") && "fixture-menu" in ui.snapshot().focused
            }
            ui.await("The real Resource response did not render after modal dismissal", 3_000L) {
                ui.has("resource-playlists:false:-0-playlist")
            }
        }
    }

    @Test fun searchLoadingReversalAndSearchCollectionBackDiscardLateEntries() {
        runCase("search-up", "search") { ui, http, _ ->
            ui.focus("search-category-songs")
            ui.inject(KeyEvent.KEYCODE_DPAD_DOWN)
            ui.inject(KeyEvent.KEYCODE_DPAD_UP)
            ui.await("Search Up did not return to the actual field", 3_000L) { "search-field" in ui.snapshot().focused }
            http.releaseFirst.countDown()
            ui.await("Actual delayed Search result was not rendered", 3_000L) { ui.has("search-result-0") }
            assertOwner(ui, "search-field", "Loaded Search results reclaimed a withdrawn Down")
        }
        for (scenario in listOf("search", "collection")) {
            runCase("$scenario-back", scenario) { ui, http, _ ->
                ui.focus(if (scenario == "search") "search-category-songs" else "collection-play")
                if (scenario == "search") ui.inject(KeyEvent.KEYCODE_DPAD_DOWN)
                ui.inject(KeyEvent.KEYCODE_BACK)
                ui.await("Loading $scenario Back did not close its actual screen", 3_000L) {
                    "fixture-closed" in ui.snapshot().focused
                }
                http.releaseFirst.countDown()
                check(http.firstFinished.await(3, TimeUnit.SECONDS))
                assertOwner(ui, "fixture-closed", "Late $scenario result reopened a closed screen")
                check(!ui.has(if (scenario == "search") "catalog-search" else "collection-screen"))
            }
        }
    }

    private fun assertOwner(ui: RenderingSurveyUi, expected: String, message: String) {
        val end = SystemClock.uptimeMillis() + 650L
        do {
            val snapshot = ui.snapshot()
            check(expected in snapshot.focused) { "$message: ${snapshot.focused}" }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < end)
    }

    private fun activateHeader(ui: RenderingSurveyUi, id: String) {
        check(id.startsWith("home-shelf-") && id in ui.snapshot().focused) {
            "Center is restricted to the proven non-media header: $id"
        }
        check(ui.device.pressDPadCenter())
    }

    private fun runCase(name: String, scenario: String,
        body: (RenderingSurveyUi, NavigationHttpFixture, JSONObject) -> Unit) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit benchmark-only fixture regression", args.getString("allow_navigation_fixture") == "1")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = checkNotNull(args.getString("run_id")).also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        val out = File(checkNotNull(inst.context.getExternalFilesDir(null)), "navigation-race-fixture/$run-$name")
        check(!out.exists() && out.mkdirs())
        val ui = RenderingSurveyUi(inst)
        val http = NavigationHttpFixture(scenario)
        val started = SystemClock.uptimeMillis()
        val report = JSONObject().put("case", name).put("coverage", "fixture_production_screens_controllers_local_http")
            .put("real_account_coverage", false).put("provider_writes", false).put("playback_actions", 0)
        try {
            inst.context.startActivity(Intent().setComponent(ComponentName(TARGET, "dev.amatv.ui.NavigationRaceProbeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("allow_navigation_fixture", true).putExtra("scenario", scenario)
                .putExtra("fixture_url", "http://127.0.0.1:${http.port}"))
            ui.awaitId("navigation-fixture-root", 5_000L)
            check(http.firstStarted.await(4, TimeUnit.SECONDS)) { "The real controller made no HTTP request" }
            body(ui, http, report)
            report.put("final", ui.snapshot().json(true)).put("status", "passed_fixture")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.stackTraceToString().take(8_000))
            runCatching { report.put("failure_snapshot", ui.snapshot().json(true)) }
            runCatching { ui.device.takeScreenshot(File(out, "failure.png")) }
            throw error
        } finally {
            http.close()
            // Back closes only this benchmark host/native fixture modal, never an underlying real page.
            repeat(3) {
                if (ui.foreground() == TARGET && (ui.has("navigation-fixture-root") || ui.has("fixture-modal-action"))) {
                    ui.inject(KeyEvent.KEYCODE_BACK)
                    SystemClock.sleep(100L)
                }
            }
            report.put("http_requests", JSONArray(http.paths.toList())).put("elapsed_ms", SystemClock.uptimeMillis() - started)
            File(out, "result.json").writeText(report.toString(2))
            ui.close()
        }
    }
}

/** Two response gates, not a replacement UI state machine. Every request is real loopback HTTP. */
private class NavigationHttpFixture(private val scenario: String) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val firstStarted = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val firstFinished = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val releaseSecond = CountDownLatch(1)
    val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var closed = false
    init {
        workers.execute {
            while (!closed) try {
                val socket = server.accept()
                workers.execute { respond(socket) }
            } catch (_: Exception) { if (!closed) throw IllegalStateException("Fixture accept failed") }
        }
    }
    private fun respond(socket: Socket) = socket.use {
        var gated = false
        try {
            socket.soTimeout = 3_000
            val reader = socket.getInputStream().bufferedReader()
            val request = reader.readLine().orEmpty()
            require(request.startsWith("GET "))
            val path = request.substringAfter(' ').substringBefore(' ')
            paths.add(path)
            while (!reader.readLine().isNullOrEmpty()) { }
            val profile = path.substringBefore('?') == "/v1/catalog/us/apple-curators/fixture-resource"
            val second = path.contains("offset=1")
            val data = if (profile) {
                """{"data":[{"id":"fixture-resource","type":"apple-curators","attributes":{"name":"Fixture resource"}}]}"""
            } else {
                gated = true
                if (second) { secondStarted.countDown(); check(releaseSecond.await(8, TimeUnit.SECONDS)) }
                else { firstStarted.countDown(); check(releaseFirst.await(8, TimeUnit.SECONDS)) }
                when {
                    scenario == "resource" && !second -> """{"data":[],"next":"/v1/catalog/us/apple-curators/fixture-resource/playlists?offset=1"}"""
                    scenario == "resource" -> """{"data":[]}"""
                    scenario == "resource-modal" -> """{"data":[{"id":"pl.fixture","type":"playlists","attributes":{"name":"Delayed fixture playlist"}}]}"""
                    else -> """{"data":[{"id":"fixture-song","type":"songs","attributes":{"name":"Delayed fixture song","artistName":"Fixture","albumName":"Fixture","durationInMillis":180000}}]}"""
                }
            }
            val bytes = data.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        } catch (_: Exception) {
            // Closing the actual screen may cancel its real HTTP client before reply.
        } finally { if (gated) firstFinished.countDown() }
    }
    override fun close() {
        closed = true
        releaseFirst.countDown(); releaseSecond.countDown()
        server.close()
        workers.shutdownNow()
    }
}
