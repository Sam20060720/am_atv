package dev.amatv.benchmark

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real production composables with local benchmark-only pages; never cloud/account acceptance. */
@RunWith(AndroidJUnit4::class)
class LibraryToolsJourneyTest {
    @get:Rule val failures = UiFailureArtifacts()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = TvUi(device)

    private fun launch(delay: Long = 30L) {
        instrumentation.context.startActivity(Intent().setComponent(ComponentName(TARGET, "dev.amatv.ui.LibraryFixtureActivity"))
            .putExtra("page_delay_ms", delay).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.id("library-browser")
        ui.id("library-grid")
    }
    private fun report() = JSONObject(ui.id("library-fixture-report").text)
    private fun select(section: String) {
        ui.focusUi("library-category-$section"); ui.center(0)
        ui.id(if (section == "songs") "library-song-list" else "library-grid")
    }
    private fun click(id: String) { ui.focusUi(id); ui.center(0) }
    private fun sorted() = device.wait({
        val name = device.findObject(By.res("library-sort-name"))
        val original = device.findObject(By.res("library-sort-original"))
        // Compose exposes Selected on a non-Tab control as Android checked.
        // Check the applied selection, its opposite, and completion together.
        name != null && (name.isSelected || name.isChecked) && original != null &&
            !original.isSelected && !original.isChecked && !ui.has("library-tools-cancel")
    }, 12_000L)

    private fun artifactDirectory(scenario: String): File {
        val run = InstrumentationRegistry.getArguments().getString("run_id")
            ?: "library-tools-${SystemClock.elapsedRealtimeNanos()}"
        require(run != "." && run != ".." && run.matches(Regex("[A-Za-z0-9_.-]+")))
        return File(checkNotNull(instrumentation.context.getExternalFilesDir(null)), "shared-input/$run/$scenario")
            .also { check(!it.exists() && it.mkdirs()) }
    }

    @Test fun filterBackCancelsTheDraftAfterAVisibleKeyboard() {
        val out = artifactDirectory("ime-back")
        val result = JSONObject().put("account_mutations", false).put("audio_operations", false)
        try {
            launch(0)
            select("songs")
            ui.focusUi("library-filter")
            ui.center(0)
            ui.id("library-filter-apply")
            ui.id("library-filter").text = "discard-draft"
            assertTrue("Compose must observe the visible IME", device.wait({ report().getBoolean("ime_visible") }, 3_000L))
            result.put("keyboard_shown", report())
            device.pressBack()
            assertTrue("Back must dismiss the editor and keyboard", device.wait({
                !ui.has("library-filter-apply") && !report().getBoolean("ime_visible")
            }, 3_000L))
            ui.awaitFocused("library-filter")
            assertFalse(ui.id("library-filter").text.contains("discard-draft"))
            result.put("keyboard_hidden", report()).put("status", "passed_ime_back_cancel")
        } catch (error: Throwable) {
            result.put("status", "failed").put("error", error.toString())
            runCatching { device.takeScreenshot(File(out, "failure.png")) }
            throw error
        } finally { File(out, "result.json").writeText(result.toString(2)) }
    }

    @Test fun sharedFilterHighlightsFocusAndHoverWithoutStealingTheEditor() {
        val out = artifactDirectory("focus-hover")
        val result = JSONObject().put("account_mutations", false).put("audio_operations", false)
        fun hover(action: Int) {
            val bounds = ui.id("library-filter").visibleBounds
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_MOUSE
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        fun screenshot(name: String) { check(device.takeScreenshot(File(out, "$name.png"))) }
        try {
            launch(0)
            select("songs")
            ui.focusUi("library-filter")
            ui.awaitFocused("library-filter")
            assertEquals(1, device.findObjects(By.res("library-filter")).size)
            assertEquals("android.widget.EditText", ui.id("library-filter").className)
            ui.left(0)
            val neighbor = if (ui.has("library-sort-artist")) "library-sort-artist" else "library-sort-name"
            ui.awaitFocused(neighbor)
            ui.settle(250)
            screenshot("idle")
            hover(MotionEvent.ACTION_HOVER_ENTER)
            hover(MotionEvent.ACTION_HOVER_MOVE)
            ui.settle(250)
            ui.awaitFocused(neighbor)
            screenshot("hover")
            hover(MotionEvent.ACTION_HOVER_EXIT)
            ui.right(0)
            ui.awaitFocused("library-filter")
            ui.settle(250)
            screenshot("focus")
            result.put("focus_and_hover", "passed")
            ui.center(0)
            ui.id("library-filter-apply")
            ui.id("library-filter").text = "Needle"
            ui.id("library-filter-apply").click()
            assertTrue(device.wait({ ui.has("library-filter-clear") && !ui.has("library-tools-cancel") }, 5_000L))
            click("library-item-0-song")
            assertTrue(device.wait({ report().getString("opened") == "i.fixture-1250" }, 3_000L))
            click("library-filter-clear")
            ui.awaitFocused("library-filter")
            assertTrue(device.wait({ !ui.has("library-filter-clear") && !ui.has("library-tools-cancel") }, 3_000L))
            result.put("edit_apply_clear", "passed")
            ui.center(0)
            ui.id("library-filter-apply")
            assertTrue("Compose must observe the IME before Back", device.wait({
                report().getBoolean("ime_visible")
            }, 3_000L))
            ui.id("library-filter").text = "discard-draft"
            device.pressBack()
            assertTrue(device.wait({ !ui.has("library-filter-apply") }, 3_000L))
            ui.awaitFocused("library-filter")
            assertFalse(ui.id("library-filter").text.contains("discard-draft"))
            result.put("status", "passed_focus_hover_edit_apply_clear_cancel")
        } catch (error: Throwable) {
            result.put("status", "failed").put("error", error.toString())
            runCatching { screenshot("failure") }
            throw error
        } finally {
            runCatching { hover(MotionEvent.ACTION_HOVER_EXIT) }
            File(out, "result.json").writeText(result.toString(2))
        }
    }

    @Test fun completeCategorySortLetterFilterAndClearReachRowsBeyondTheFirstPage() {
        launch()
        select("songs")
        click("library-sort-name")
        assertTrue("Name sort must finish the full local category", sorted())
        assertEquals(2600, report().getInt("songs"))
        click("library-letter-open")
        ui.focusUi("library-letter-A")
        repeat(25) { ui.right(35) }
        ui.awaitFocused("library-letter-Z")
        ui.center(0)
        ui.awaitFocused("library-item-2500-song")
        ui.center(0)
        assertTrue(device.wait({ report().getString("opened") == "i.fixture-2500" }, 3_000L))

        click("library-filter")
        ui.id("library-filter").text = "Needle"
        ui.id("library-filter-apply").click()
        assertTrue(device.wait({ ui.has("library-filter-clear") && !ui.has("library-tools-cancel") }, 5_000L))
        click("library-item-0-song")
        assertTrue(device.wait({ report().getString("opened") == "i.fixture-1250" }, 3_000L))
        click("library-filter-clear")
        assertTrue(device.wait({ !ui.has("library-filter-clear") && !ui.has("library-tools-cancel") }, 5_000L))
        assertEquals(2600, report().getInt("songs"))
    }

    @Test fun backCancelsCollectionAndALatePageDoesNotRestartIt() {
        launch(400)
        select("songs")
        val before = report().getInt("reads")
        click("library-sort-name")
        ui.id("library-tools-cancel")
        assertTrue(device.wait({ report().getInt("reads") > before }, 2_000L))
        val dispatched = report().getInt("reads")
        device.pressBack()
        assertTrue(device.wait({ !ui.has("library-tools-cancel") }, 2_000L))
        ui.settle(850)
        assertTrue(ui.has("library-browser"))
        assertEquals("Cancelled sorting must not keep collecting pages", dispatched, report().getInt("reads"))
        assertTrue(report().getInt("songs") <= 200)
    }

    @Test fun rootManagementNestedFoldersAndDetailReturnRetainAppliedSort() {
        launch()
        select("playlists")
        click("library-playlists-manage")
        assertTrue(device.wait({ report().getString("menu") == "library-playlist-folders:p.playlistsroot" }, 2_000L))
        click("library-sort-name")
        assertTrue(sorted())
        click("library-letter-open")
        ui.id("library-letter-index")
        click("library-item-2-page")
        assertTrue("The sorted third card must open the Zulu folder identity", device.wait({
            report().getString("page") == "p.fixture-z" && report().getInt("depth") == 1
        }, 3_000L))
        ui.id("resource-children:false:-0-page")
        click("resource-children:false:-0-page")
        assertTrue("The child card must open a distinct nested folder", device.wait({
            report().getString("page") == "p.fixture-nested" && report().getInt("depth") == 2
        }, 3_000L))
        ui.id("resource-children:false:-0-playlist")
        assertEquals(2, report().getInt("depth"))
        click("resource-children:false:-0-playlist")
        assertTrue(device.wait({ report().getString("opened") == "p.fixture-leaf" }, 2_000L))
        device.pressBack()
        assertTrue(device.wait({ report().getInt("depth") == 1 }, 2_000L))
        device.pressBack()
        ui.id("library-browser")
        assertTrue("Returning from a folder must retain applied name ordering", sorted())
        ui.id("library-letter-index")
    }

    @Test fun backDuringFolderLoadingCannotReopenTheDepartedFolder() {
        launch()
        select("playlists")
        click("library-item-0-page")
        ui.id("catalog-resource-screen")
        device.pressBack()
        ui.id("library-browser")
        ui.settle(650)
        assertFalse(ui.has("catalog-resource-screen"))
        assertEquals(0, report().getInt("depth"))
        assertEquals("", report().getString("page"))
    }
}
