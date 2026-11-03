package dev.amatv.benchmark

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Real signed-in library, without account reset or mock catalog responses. */
@RunWith(AndroidJUnit4::class)
class LibraryNavigationTest {
    @get:Rule val failures = UiFailureArtifacts()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = TvUi(device)

    @Before fun launchPreservingAccount() {
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET)!!
        instrumentation.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.browseTab("library")
        ui.right(0)
        ui.id("library-browser")
    }

    private fun select(section: String) {
        ui.focusUi("library-category-$section")
        ui.center(0)
        assertTrue("Library category did not finish loading: $section", device.wait({
            ui.has("library-grid") || ui.has("library-song-list") || ui.has("library-empty")
        }, 60_000L))
        assertFalse("Library must not use Home shelves or featured cards", device.hasObject(
            By.res(Pattern.compile("home-shelves-count-\\d+"))))
    }

    private fun evidence(name: String) {
        val dir = File(instrumentation.context.getExternalFilesDir(null), "library-review").apply { mkdirs() }
        device.takeScreenshot(File(dir, "$name.png"))
        device.dumpWindowHierarchy(File(dir, "$name.xml"))
        instrumentation.sendStatus(2, Bundle().apply { putString("libraryReview", name) })
    }

    private fun focusSheetRow(id: String) {
        // CatalogSheetRow tags its measuring wrapper; the descendant
        // FocusSurface owns input focus. Wait for that actual action to be ready.
        ui.requireObject(By.res(id).hasDescendant(By.focusable(true).enabled(true)))
        @Suppress("DEPRECATION")
        fun visit(node: AccessibilityNodeInfo, insideRow: Boolean = false): Boolean = try {
            val inside = insideRow || node.viewIdResourceName == id
            if (inside && node.isFocusable && node.isEnabled &&
                (node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))) true
            else (0 until node.childCount).any { index -> node.getChild(index)?.let { visit(it, inside) } == true }
        } finally { node.recycle() }
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("Missing active catalog menu window")
        assertTrue("Catalog row's actual action must accept input focus: $id", visit(root))
        ui.requireObject(By.res(id).hasDescendant(By.focused(true)))
    }

    private fun leaveCreationForm(formId: String) {
        @Suppress("DEPRECATION")
        fun imeVisible(): Boolean {
            val windows = instrumentation.uiAutomation.windows
            return try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            finally { windows.forEach { it.recycle() } }
        }
        ui.requireObject(By.res(formId).enabled(true))
        if (imeVisible()) {
            device.pressBack()
            assertTrue("IME must close before leaving the creation form", device.wait({ !imeVisible() }, UI_TIMEOUT))
        }
        device.pressBack()
        assertTrue("Creation form must finish returning to the action pane", device.wait({
            !ui.has(formId) && ui.has("catalog-actions-list")
        }, UI_TIMEOUT))
    }

    @Test fun categoriesUseCompactCollectionsAndSongs() {
        listOf("recent", "albums", "artists", "playlists", "songs").forEach { section ->
            select(section)
            if (section == "songs" && !ui.has("library-empty")) ui.id("library-song-list")
            if (section != "songs" && !ui.has("library-empty")) {
                val card = ui.requireObject(By.res(Pattern.compile("library-item-0-[a-z]+")))
                assertTrue("Library cover must stay compact", card.visibleBounds.width() < device.displayWidth / 5)
            }
            ui.settle(340)
            evidence(section)
        }
    }

    @Test fun rootPlaylistManagementOpensBothCreationFormsWithoutWriting() {
        select("playlists")
        ui.focusUi("library-playlists-manage")
        ui.center(0)
        focusSheetRow("catalog-action-create-folder")
        ui.center(0)
        ui.id("catalog-folder-name")
        ui.id("catalog-folder-create")
        evidence("root-create-folder-form")
        leaveCreationForm("catalog-folder-name")
        focusSheetRow("catalog-action-create")
        ui.center(0)
        ui.id("catalog-playlist-name")
        ui.id("catalog-playlist-create")
        evidence("root-create-playlist-form")
        leaveCreationForm("catalog-playlist-name")
        focusSheetRow("catalog-action-create")
        device.pressBack()
        assertTrue("The action pane must finish closing before returning to Library", device.wait(
            Until.gone(By.res("catalog-actions-list")), UI_TIMEOUT))
        ui.id("library-browser")
        ui.awaitFocused("library-playlists-manage")
    }

    @Test fun collectionBackRestoresLibraryCard() {
        select("albums")
        assumeTrue("Account has no library albums", ui.has("library-grid"))
        ui.right(0)
        ui.awaitFocused("library-item-0-album")
        ui.center(0)
        ui.id("collection-screen")
        ui.requireObject(By.res("collection-song-0"), 60_000L)
        device.pressBack()
        ui.id("library-browser")
        ui.awaitFocused("library-item-0-album")
        assertFalse(ui.has("collection-screen"))
        evidence("album-back")
    }

    @Test fun artistOpensOwnLibraryAlbumsAndReturnsThroughBothLevels() {
        select("artists")
        assumeTrue("Account has no library artists", ui.has("library-grid"))
        ui.right(0)
        ui.awaitFocused("library-item-0-artist")
        ui.center(0)
        ui.id("library-artist-screen")
        assertTrue(device.wait({ ui.has("library-grid") || ui.has("library-empty") }, 60_000L))
        if (ui.has("library-grid")) {
            ui.focusUi("library-artist-back")
            ui.down(0)
            ui.awaitFocused("library-item-0-album")
            ui.center(0)
            ui.id("collection-screen")
            device.pressBack()
            ui.id("library-artist-screen")
            ui.awaitFocused("library-item-0-album")
        }
        device.pressBack()
        ui.id("library-browser")
        ui.awaitFocused("library-item-0-artist")
        assertFalse(ui.has("library-artist-screen"))
        evidence("artist-back")
    }
}
