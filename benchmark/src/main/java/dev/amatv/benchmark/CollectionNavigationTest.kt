package dev.amatv.benchmark

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Public catalog and remote input only; never clear or sign out the account. */
@RunWith(AndroidJUnit4::class)
class CollectionNavigationTest {
    @get:Rule val failures = UiFailureArtifacts()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = TvUi(device)

    @Before fun openPlaylist() {
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET)!!
        instrumentation.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.enterHomeShelf("app_top_playlists", minimumCardCount = 1)
        ui.center(0)
        ui.id("collection-screen")
        ui.requireObject(By.res("collection-song-0"), 60_000L)
    }

    @Test fun headerScrollsWithSongsAndMenuReturnsToTheSameSong() {
        ui.focusUi("collection-play")
        val cover = ui.id("collection-artwork").visibleBounds
        ui.id("nav-menu")
        ui.id("collection-title")
        ui.down(0)
        ui.awaitFocused("collection-song-0")
        repeat(12) { index ->
            ui.down(80)
            ui.awaitFocused("collection-song-${index + 1}")
        }
        ui.settle(340)
        assertEquals("Left artwork must remain stationary", cover, ui.id("collection-artwork").visibleBounds)
        val header = device.findObject(By.res("collection-title"))
        assertTrue("Title must scroll off with actions, instead of remaining pinned", header == null || header.visibleBounds.isEmpty)
        assertFalse("Actions must also leave the visible viewport", ui.has("collection-play"))
        ui.left(350)
        ui.id("nav-panel")
        assertTrue("Menu overlays the detail rather than closing it", ui.has("collection-screen"))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.res("nav-panel")), 5_000L))
        ui.awaitFocused("collection-song-12")
        repeat(12) { index ->
            ui.up(80)
            ui.awaitFocused("collection-song-${11 - index}")
        }
        ui.up(0)
        ui.awaitFocused("collection-play")
        ui.id("collection-title")
        assertEquals(cover, ui.id("collection-artwork").visibleBounds)
    }

    @Test fun homeMenuActionLeavesDetailEvenWhenHomeIsTheParent() {
        ui.openNavigation()
        ui.focusUi("nav-home")
        ui.center(0)
        ui.id("browse-home")
        assertTrue(device.wait(Until.gone(By.res("collection-screen")), 5_000L))
        assertFalse(ui.has("nav-panel"))
    }
}
