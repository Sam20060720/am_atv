package dev.amatv.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

internal class SurveyUnavailable(message: String) : RuntimeException(message)
internal class SurveyUnsafe(message: String) : AssertionError(message)

/** Public UI only. This helper deliberately does not inherit playback-seeding TvUi journeys. */
@Suppress("DEPRECATION")
internal class RenderingSurveyUi(private val instrumentation: Instrumentation) {
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val automation get() = instrumentation.uiAutomation
    private val labels = mutableMapOf<String, Set<String>>()
    private val configurator = Configurator.getInstance()
    private val oldIdle = configurator.waitForIdleTimeout
    private val oldDelay = configurator.keyInjectionDelay
    var searchProof: String? = null
        private set
    private val collectionChoices = mutableMapOf<String, Pair<String, String>>()
    var collectionSelectionProof = JSONObject()
        private set
    private val setupOwnerRecoveries = JSONArray()
    var artistSelectionProof = JSONObject()
        private set
    var menuInitialFocusProof = JSONObject()
        private set
    var lastHomeCardVisibilityProof = JSONObject()
        private set

    fun consumeSetupOwnerRecoveries(): JSONArray = JSONArray(setupOwnerRecoveries.toString()).also {
        while (setupOwnerRecoveries.length() > 0) setupOwnerRecoveries.remove(0)
    }

    init { configurator.waitForIdleTimeout = 0L; configurator.keyInjectionDelay = 0L }
    fun close() { configurator.waitForIdleTimeout = oldIdle; configurator.keyInjectionDelay = oldDelay }

    fun launch() {
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET)
            ?: throw SurveyUnsafe("Installed target missing: " + TARGET)
        instrumentation.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("target foreground", 15_000) { foreground() == TARGET }
    }

    fun foreground(): String = device.currentPackageName.orEmpty()
    fun requireForeground() {
        if (foreground() != TARGET) throw SurveyUnsafe("Target lost foreground: " + foreground())
    }

    fun stringLabels(name: String): Set<String> = labels.getOrPut(name) {
        val context = instrumentation.context.createPackageContext(TARGET, 0)
        listOf(Locale.ENGLISH, Locale.TAIWAN, Locale.SIMPLIFIED_CHINESE).mapNotNull { locale ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val resources = context.createConfigurationContext(configuration).resources
            resources.getIdentifier(name, "string", TARGET).takeIf { it != 0 }?.let(resources::getString)
        }.toSet()
    }

    private fun normalizedId(node: AccessibilityNodeInfo): String =
        node.viewIdResourceName.orEmpty().substringAfter(":id/")

    private fun targetRoots(): List<AccessibilityNodeInfo> {
        val active = automation.rootInActiveWindow
        if (active?.packageName?.toString() == TARGET) return listOf(active)
        val activeId = active?.windowId
        active?.recycle()
        // A native IME can be the active accessibility window during text setup.
        // Only read our own underlying app window; no external window is clicked.
        val windows = automation.windows.sortedByDescending { it.layer }
        val ime = windows.any { it.id == activeId && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (!ime || !targetIsResumed()) {
            windows.forEach { it.recycle() }
            return emptyList()
        }
        return windows.mapNotNull { window ->
            try { window.root?.let { root -> if (root.packageName?.toString() == TARGET) root else { root.recycle(); null } } }
            finally { window.recycle() }
        }
    }
    private fun targetIsResumed(): Boolean = Regex("(mResumedActivity|topResumedActivity).*dev\\.amatv/")
        .containsMatchIn(device.executeShellCommand("dumpsys activity activities"))
    private fun requireTargetOrItsIme() {
        if (foreground() == TARGET) return
        val active = automation.rootInActiveWindow
        val activeId = active?.windowId
        active?.recycle()
        val windows = automation.windows
        val ime = try { windows.any { it.id == activeId && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        finally { windows.forEach { it.recycle() } }
        if (!ime || !targetIsResumed()) throw SurveyUnsafe("Search lost app/IME ownership")
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = try {
            if (!node.refresh()) null
            else if (node.isVisibleToUser && node.packageName?.toString() == TARGET && predicate(node)) {
                AccessibilityNodeInfo.obtain(node)
            } else {
                var result: AccessibilityNodeInfo? = null
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    result = visit(child)
                    if (result != null) break
                }
                result
            }
        } finally { node.recycle() }
        val roots = targetRoots()
        for ((index, root) in roots.withIndex()) {
            val result = visit(root)
            if (result != null) {
                roots.drop(index + 1).forEach { it.recycle() }
                return result
            }
        }
        return null
    }

    private fun hasFocusedChild(node: AccessibilityNodeInfo): Boolean {
        if (!node.refresh()) return false
        if (node.isFocused) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (hasFocusedChild(child)) return true } finally { child.recycle() }
        }
        return false
    }
    private fun requestChildFocus(node: AccessibilityNodeInfo): Boolean {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (child.isEnabled && child.isVisibleToUser && child.isFocusable &&
                    child.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
                if (requestChildFocus(child)) return true
            } finally { child.recycle() }
        }
        return false
    }
    private fun requireActiveFocusedSearchInput() {
        val node = findNode { normalizedId(it) == "search-field" }
            ?: throw SurveyUnsafe("Search input disappeared before CENTER")
        val active = automation.rootInActiveWindow
        try {
            if (node.className?.toString() != "android.widget.EditText" || !hasFocusedChild(node) ||
                active == null || node.windowId != active.windowId) {
                throw SurveyUnsafe("Search CENTER requires focused EditText in active window")
            }
        } finally { node.recycle(); active?.recycle() }
    }

    fun has(id: String): Boolean = findNode { normalizedId(it) == id }?.let { it.recycle(); true } == true
    fun hasPattern(pattern: Regex): Boolean = findNode { pattern.matches(normalizedId(it)) }?.let { it.recycle(); true } == true
    fun await(message: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            SystemClock.sleep(80)
        }
        throw SurveyUnavailable(message)
    }
    fun awaitId(id: String, timeout: Long = 20_000) = await("Missing " + id, timeout) { has(id) }
    fun awaitGone(id: String) = await("Still visible " + id, 8_000) { !has(id) }
    fun settle(ms: Long = 420) = SystemClock.sleep(ms)

    fun focus(id: String) {
        requireForeground()
        awaitId(id)
        val browseContentTarget = id.startsWith("library-") || id.startsWith("shelf-") ||
            id.startsWith("search-") || id.startsWith("artist-") || id.startsWith("collection-") || id.startsWith("language-")
        if (browseContentTarget) enterBrowseContentForSetup()
        val deadline = SystemClock.uptimeMillis() + 4_000
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            requireForeground()
            if (browseContentTarget && has("nav-panel")) {
                enterBrowseContentForSetup()
                stableSince = 0L
            }
            val node = findNode { normalizedId(it) == id }
            if (node != null) {
                try {
                    if (hasFocusedChild(node)) {
                        if (!browseContentTarget) return
                        if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                        if (SystemClock.uptimeMillis() - stableSince >= 400) return
                    } else {
                        stableSince = 0L
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || requestChildFocus(node)
                    }
                } finally { node.recycle() }
            }
            settle(120)
        }
        throw SurveyUnavailable("Cannot establish exact focus " + id)
    }

    /** Setup only: Right is the app's public sidebar/pill-to-content operation. */
    private fun enterBrowseContentForSetup() {
        requireForeground()
        repeat(3) {
            val state = snapshot()
            val panel = "nav-panel" in state.ids
            val pill = "nav-menu" in state.focused
            if (!panel && !pill) return
            if (panel && state.focused.none { it.startsWith("nav-") }) focus("nav-home")
            setupOwnerRecoveries.put(JSONObject().put("at_ns", System.nanoTime())
                .put("owner", if (panel) "nav-panel" else "nav-menu")
                .put("focus", JSONArray(state.focused)).put("action", "DPAD_RIGHT_ENTER_CONTENT"))
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            awaitGone("nav-panel")
            await("Sidebar did not hand off to real browse content", 8_000) {
                val current = snapshot()
                "nav-panel" !in current.ids && current.focused.any { !it.startsWith("nav-") }
            }
            settle(300)
        }
        val end = snapshot()
        if ("nav-panel" in end.ids || "nav-menu" in end.focused)
            throw SurveyUnavailable("Browse content owner repeatedly returned to navigation during setup")
    }

    /** Validation is immediately before every activation. Songs and mutation controls never match. */
    fun verifiedActivation(id: String, kind: String): SurveyActivation {
        requireForeground()
        val node = findNode { normalizedId(it) == id } ?: throw SurveyUnavailable("Activation absent " + id)
        try {
            val allowed = when (kind) {
                "artwork_setting" -> id == "settings-artwork-motion"
                "navigation" -> id in setOf("nav-menu", "nav-home", "nav-browse", "nav-radio", "nav-search", "nav-library", "nav-account", "nav-nowplaying")
                "library_category" -> id.matches(Regex("library-category-(recent|albums|artists|playlists|songs)"))
                "search_scope" -> id in setOf("search-scope-catalog", "search-scope-library")
                "search_history_clear" -> id == "search-history-clear"
                "search_history_query" -> id.matches(Regex("search-history-\\d+"))
                "search_category" -> id.matches(Regex("search-category-(artists|albums|playlists|songs)"))
                "search_input" -> id == "search-field" && node.className?.toString() == "android.widget.EditText"
                "collection" -> id.matches(Regex("(shelf-\\d+-item-\\d+|library-item-\\d+)-(album|playlist)"))
                "artist" -> id.matches(Regex("(shelf-\\d+-item-\\d+|library-item-\\d+)-artist")) ||
                    (id.matches(Regex("search-result-\\d+")) && searchProof == "artists" && has("search-result-grid") &&
                        hasExactPublicText(node, "NewJeans"))
                "editorial_album" -> id.matches(Regex("search-result-\\d+")) && searchProof == "albums" &&
                    has("search-result-grid") && hasExactPublicText(node, "Positions (Deluxe)") &&
                    hasExactPublicText(node, "Ariana Grande")
                "queue_view" -> id == "player-queue" && playerPaused()
                "context_menu" -> id in setOf("collection-more", "artist-more")
                else -> false
            }
            val role = node.className?.toString().orEmpty()
            if (!allowed || role !in setOf("android.view.View", "android.widget.Button", "android.widget.ImageButton", "android.widget.EditText") ||
                !node.isEnabled || (kind != "search_input" && !node.isClickable)) {
                throw SurveyUnsafe("Activation rejected id=" + id + " kind=" + kind + " class=" + role)
            }
            return SurveyActivation(id, kind, role, node)
        } catch (error: Throwable) { node.recycle(); throw error }
    }

    fun activate(id: String, kind: String) {
        awaitId(id)
        verifiedActivation(id, kind).use { if (!it.execute()) throw SurveyUnsafe("Activation failed " + id) }
        settle(320)
    }

    fun verifiedAlbumLongPress(id: String): SurveyLongPress {
        if (!Regex("(shelf-\\d+-item-\\d+|library-item-\\d+)-album").matches(id))
            throw SurveyUnsafe("Long press is restricted to a proven album card")
        verifiedActivation(id, "collection").close()
        val node = findNode { normalizedId(it) == id } ?: throw SurveyUnavailable("Long-press source disappeared")
        try {
            if (!node.isLongClickable || !hasFocusedChild(node))
                throw SurveyUnavailable("Album does not expose a focused native long-click target")
        } finally { node.recycle() }
        val holdMs = ViewConfiguration.getLongPressTimeout().toLong() + 250L
        if (holdMs !in 300L..10_000L) throw SurveyUnavailable("Long-press timeout outside bounded survey")
        return SurveyLongPress(id, holdMs) { injectHeldCenter(holdMs) }
    }

    private fun injectHeldCenter(holdMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        try {
            val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
            if (!automation.injectInputEvent(down, true)) throw SurveyUnsafe("Long-press Down injection failed")
            SystemClock.sleep(holdMs)
        } finally {
            // Always release while instrumentation is alive, including a failed Down/hold.
            val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
            if (!automation.injectInputEvent(up, true)) throw SurveyUnsafe("Long-press Up injection failed")
        }
    }

    /** Observe default menu focus before any corrective focus request. Setup/warmup only. */
    fun assertNaturalFirstMenuFocus(): JSONObject {
        awaitId("catalog-actions-list")
        var observation = JSONObject()
        await("Menu did not naturally focus its first enabled visible option", 4_000) {
            val state = snapshot()
            val rows = state.nodes.filter { row -> row.id.startsWith("catalog-action-") &&
                state.nodes.any { child -> child.resolvedId == row.id && child.focusable && child.clickable } }
                .sortedBy { Rect.unflattenFromString(it.bounds)?.top ?: Int.MAX_VALUE }
                .distinctBy { it.id }
            val first = rows.firstOrNull()?.id
            observation = JSONObject().put("first_enabled_action", first ?: JSONObject.NULL)
                .put("focus", JSONArray(state.focused)).put("visible_actions_in_order", JSONArray(rows.map { it.id }))
                .put("verified_ns", System.nanoTime()).put("corrective_focus_requests", 0)
            menuInitialFocusProof = observation
            first != null && first in state.focused
        }
        menuInitialFocusProof = observation
        return observation
    }

    fun key(code: Int, pause: Long = 160) {
        requireForeground()
        inject(code)
        if (pause > 0) SystemClock.sleep(pause)
    }

    /** No UiDevice implicit idle wait: a fresh paired event on the same DPAD device/downTime. */
    fun inject(code: Int) {
        if (code !in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) {
            throw SurveyUnsafe("Unvalidated key " + code)
        }
        injectPair(code)
    }
    private fun injectPair(code: Int) {
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        if (!automation.injectInputEvent(down, true)) throw SurveyUnsafe("Down injection failed " + code)
        // Generate Up only after Down dispatch; do not predate a slow injection.
        val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        if (!automation.injectInputEvent(up, true)) throw SurveyUnsafe("Up injection failed " + code)
    }

    private fun publicLabel(node: AccessibilityNodeInfo): String {
        val result = StringBuilder()
        fun collect(value: AccessibilityNodeInfo, recycle: Boolean) {
            try {
                result.append(value.text?.toString().orEmpty()).append(' ')
                    .append(value.contentDescription?.toString().orEmpty()).append(' ')
                for (i in 0 until value.childCount) value.getChild(i)?.let { collect(it, true) }
            } finally { if (recycle) value.recycle() }
        }
        collect(node, false)
        return result.toString()
    }

    private fun hasExactPublicText(node: AccessibilityNodeInfo, expected: String): Boolean {
        if (node.text?.toString()?.trim()?.equals(expected, ignoreCase = true) == true) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (hasExactPublicText(child, expected)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun exactTextAt(id: String, expected: String): Boolean = findNode { normalizedId(it) == id }?.let {
        try { hasExactPublicText(it, expected) } finally { it.recycle() }
    } == true

    private fun sectionColumns(id: String): Int? = findNode { normalizedId(it) == id }?.let {
        try { it.collectionInfo?.columnCount } finally { it.recycle() }
    }

    fun label(id: String): String = findNode { normalizedId(it) == id }?.let { node ->
        try { publicLabel(node).replace(Regex("\\s+"), " ").trim() } finally { node.recycle() }
    }.orEmpty()

    fun navigate(tab: String) {
        searchProof = null
        if (foreground() == "com.google.android.tvlauncher") launch() else requireForeground()
        // Normalize only the known owner, waiting for its transition before
        // another input. A sidebar over a retained artist is already usable.
        if (has("catalog-actions-list")) {
            key(KeyEvent.KEYCODE_BACK, 0)
            awaitGone("catalog-actions-list")
        }
        leavePlayerWithBack()
        requireForeground()
        for (selection in 0 until 2) {
            openNavigation()
            activate("nav-" + tab, "navigation")
            awaitGone("nav-panel")
            // A newly selected retained tab can have an old detail visit.
            // Confirming that same tab clears it through the public action.
            await("Tab selection owner did not arrive", 12_000) {
                has(pageContainer(tab)) || has("collection-screen") || has("artist-screen") || has("catalog-resource-screen")
            }
            if (has(pageContainer(tab)) && !has("collection-screen") && !has("artist-screen") && !has("catalog-resource-screen")) break
        }
        awaitId(pageContainer(tab), 12_000)
        if (tab != "nowplaying") enterBrowseContentForSetup()
        requireForeground()
    }
    /** Real remote Back only. Internal player layers need not remove a public node. */
    fun leavePlayerWithBack(): JSONArray {
        val steps = JSONArray()
        repeat(6) {
            if (!has("player-screen")) return steps
            // Waiting for an ordinary pending focus handoff is allowed; fixing
            // focus with accessibility would hide the product's return defect.
            await("Player does not own remote Back: " + snapshot().focused, 1_500L) {
                !has("player-screen") || "player-screen" in snapshot().focused
            }
            if (!has("player-screen")) return steps
            val before = snapshot()
            key(KeyEvent.KEYCODE_BACK, 0L)
            // Back may cancel a scrub or selected lyric before hiding chrome.
            // Bound the actual contract rather than demanding a node vanish on
            // every press; never send activation, focus correction or audio input.
            settle(420L)
            steps.put(JSONObject().put("press", it + 1).put("before", before.json())
                .put("after", snapshot().json()))
        }
        if (has("player-screen")) throw SurveyUnavailable("Player did not exit after six owned Back presses: " + steps)
        return steps
    }

    fun pageContainer(tab: String): String = when (tab) {
        // Canonical inner destinations remain exposed even if a wrapper is merged.
        "search" -> "catalog-search"
        "library" -> "library-browser"
        "nowplaying" -> "player-screen"
        else -> "browse-" + tab
    }
    fun openNavigation() {
        requireForeground()
        await("Navigation owner unavailable", 8_000) { has("nav-panel") || has("nav-menu") }
        fun interactivePanel(): Boolean = findNode { normalizedId(it) == "nav-home" &&
            it.isEnabled && it.isClickable }?.let { it.recycle(); true } == true
        // Closing contents remain mounted for their spring, with input already
        // disabled. Their presence alone does not mean the drawer is open.
        if (!interactivePanel()) activate("nav-menu", "navigation")
        await("Navigation panel did not become interactive", 8_000) { interactivePanel() }
    }

    fun firstShelf(tab: String): String {
        navigate(tab)
        // Tabs retain their lazy viewports. Confirming the current tab is the
        // public root-to-top action; switching tabs is not a state reset.
        openNavigation(); activate("nav-" + tab, "navigation"); awaitGone("nav-panel")
        val firstRow = Regex("shelf-0-item-(\\d+)-[a-z]+")
        await("First shelf has no visible card", 45_000) {
            snapshot().ids.any(firstRow::matches)
        }
        // A vertical viewport already at zero can keep row 1 focused. The
        // current-tab action does not promise row-0 focus; establish it through
        // a visible public focus target after the page transition has settled.
        settle(650)
        val visibleFirstRowCard = snapshot().ids.filter(firstRow::matches)
            .minByOrNull { firstRow.matchEntire(it)!!.groupValues[1].toInt() }
            ?: throw SurveyUnavailable("First shelf disappeared before setup focus")
        focus(visibleFirstRowCard)
        val current = shelfInfo()
        if (current.row != 0 || current.column > 100) throw SurveyUnavailable("Unexpected first-row setup position")
        for (column in current.column downTo 1) {
            key(KeyEvent.KEYCODE_DPAD_LEFT, 120)
            val targetColumn = column - 1
            await("First-row setup Left did not reach column " + targetColumn, 4_000) {
                snapshot().focused.any { it.matches(Regex("shelf-0-item-" + targetColumn + "-[a-z]+")) }
            }
        }
        await("First card focus missing", 6_000) { snapshot().focused.any { it.matches(Regex("shelf-0-item-0-[a-z]+")) } }
        val real = snapshot().focused.first { it.matches(Regex("shelf-0-item-0-[a-z]+")) }
        focus(real)
        // Establish a real remote visit against the actual feed shape. A
        // personalized Radio shelf may legitimately contain only one station.
        val anchor = shelfInfo()
        if (anchor.cardCount > 1) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 120)
            await("First shelf remote Right did not reach the next card", 4_000) {
                snapshot().focused.any { it.matches(Regex("shelf-0-item-1-[a-z]+")) }
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT, 120)
            await("First shelf remote Left did not return to its source", 4_000) { real in snapshot().focused }
        } else if (anchor.rowCount > 1) {
            key(KeyEvent.KEYCODE_DPAD_DOWN, 120)
            await("Single-card first shelf remote Down did not reach row 1", 4_000) {
                snapshot().focused.any { it.matches(Regex("shelf-1-item-\\d+-[a-z]+")) }
            }
            key(KeyEvent.KEYCODE_DPAD_UP, 120)
            await("Single-card first shelf remote Up did not return to its source", 4_000) { real in snapshot().focused }
        } else {
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 120)
            await("Only shelf/card lost its source after remote Right", 4_000) { real in snapshot().focused }
        }
        settle(350)
        return real
    }

    fun shelfInfo(): SurveyShelf {
        val state = snapshot()
        val id = state.focused.firstOrNull { it.matches(Regex("shelf-\\d+-item-\\d+-[a-z]+")) }
            ?: throw SurveyUnavailable("No focused shelf card")
        val parts = Regex("shelf-(\\d+)-item-(\\d+)-([a-z]+)").matchEntire(id)!!.groupValues
        val row = parts[1].toInt()
        val count = state.ids.firstNotNullOfOrNull { Regex("home-row-" + row + "-count-(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            ?: throw SurveyUnavailable("Shelf count metadata missing")
        val rows = state.ids.firstNotNullOfOrNull { Regex("home-shelves-count-(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            ?: throw SurveyUnavailable("Row count metadata missing")
        val rect = state.nodes.firstOrNull { it.id == id }?.bounds?.let(Rect::unflattenFromString)
            ?: throw SurveyUnavailable("Card bounds missing")
        val content = label("home-row-" + row + "-count-" + count)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return SurveyShelf(row, parts[2].toInt(), parts[3], count, rows, rect.width(), device.displayWidth, fingerprint)
    }

    fun squareShelf(tab: String): SurveyShelf {
        firstShelf(tab)
        val limit = shelfInfo().rowCount.coerceAtMost(20)
        for (step in 0 until limit) {
            val info = shelfInfo()
            if (info.widthPx < info.displayWidthPx * 0.18f && info.cardCount > 1) {
                // Entering a retained row can restore its previous column.
                // Establish and verify column zero before every warmup/trial.
                val rowPattern = Regex("shelf-" + info.row + "-item-(\\d+)-[a-z]+")
                val firstVisible = snapshot().ids.filter(rowPattern::matches)
                    .minByOrNull { rowPattern.matchEntire(it)!!.groupValues[1].toInt() }
                    ?: throw SurveyUnavailable("Square shelf has no visible focus anchor")
                focus(firstVisible)
                val current = shelfInfo()
                if (current.row != info.row || current.column > 100)
                    throw SurveyUnavailable("Unexpected square-row setup position")
                for (column in current.column downTo 1) {
                    key(KeyEvent.KEYCODE_DPAD_LEFT, 120)
                    val target = Regex("shelf-" + info.row + "-item-" + (column - 1) + "-[a-z]+")
                    await("Square-row setup Left did not reach column " + (column - 1), 4_000) {
                        snapshot().focused.any(target::matches)
                    }
                }
                val zero = Regex("shelf-" + info.row + "-item-0-[a-z]+")
                await("Square first-card focus missing", 6_000) { snapshot().focused.any(zero::matches) }
                settle(650)
                return shelfInfo()
            }
            if (info.row >= info.rowCount - 1) break
            val next = info.row + 1
            key(KeyEvent.KEYCODE_DPAD_DOWN, 340)
            await("Next square-row candidate unavailable", 6_000) {
                snapshot().focused.any { it.startsWith("shelf-" + next + "-item-") }
            }
        }
        throw SurveyUnavailable("No square-card row under bounded search")
    }

    fun typedCard(kind: String): String {
        navigate("library")
        if (has("library-category-" + kind + "s")) {
            focus("library-category-" + kind + "s")
            activate("library-category-" + kind + "s", "library_category")
            settle(650)
            await("Library typed item or proven empty state not ready", 45_000) {
                hasPattern(Regex("library-item-\\d+-" + kind)) || has("library-empty")
            }
            if (hasPattern(Regex("library-item-\\d+-" + kind))) return firstLibraryCard(kind)
        }
        return homeCard(kind)
    }

    /** Real remote navigation reveals a shelf; ACTION_FOCUS on a sliver cannot do so. */
    fun homeCard(kind: String, smallOnly: Boolean = false): String {
        if (kind !in setOf("album", "playlist")) throw SurveyUnsafe("Unsupported Home card kind")
        firstShelf("home")
        val limit = shelfInfo().rowCount.coerceAtMost(20)
        repeat(limit) {
            val current = shelfInfo()
            val small = current.widthPx < current.displayWidthPx * 0.18f
            val pattern = Regex("shelf-" + current.row + "-item-(\\d+)-" + kind)
            val candidate = if (!smallOnly || small) snapshot().ids.filter(pattern::matches)
                .minByOrNull { pattern.matchEntire(it)!!.groupValues[1].toInt() } else null
            if (candidate != null) {
                val target = pattern.matchEntire(candidate)!!.groupValues[1].toInt()
                var column = current.column
                while (column != target) {
                    val next = column + if (column < target) 1 else -1
                    key(if (column < target) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, 180)
                    await("Home candidate column was not reached", 4_000) {
                        snapshot().focused.any { Regex("shelf-" + current.row + "-item-" + next + "-[a-z]+").matches(it) }
                    }
                    column = next
                }
                await("Home candidate did not own native focus", 4_000) { candidate in snapshot().focused }
                awaitFullyVisibleHomeCard(candidate)
                return candidate
            }
            if (current.row >= current.rowCount - 1) throw SurveyUnavailable("No matching Home card before the last shelf")
            val nextRow = current.row + 1
            key(KeyEvent.KEYCODE_DPAD_DOWN, 340)
            await("Home fallback did not enter the next real shelf", 6_000) {
                snapshot().focused.any { it.startsWith("shelf-" + nextRow + "-item-") }
            }
            settle(350)
        }
        throw SurveyUnavailable("No matching Home card within bounded remote traversal")
    }

    private fun awaitFullyVisibleHomeCard(id: String) {
        await("Home cover/captions are not fully inside the viewport", 6_000) {
            homeCardVisibility(id).optBoolean("complete")
        }
    }

    /** Staged Home content clears child semantics; derive regions from its measured, loaded focus shell. */
    fun homeCardVisibility(id: String): JSONObject {
        if (!Regex("shelf-\\d+-item-\\d+-(album|playlist)").matches(id))
            throw SurveyUnsafe("Unrecognized Home visibility target")
        val proof = JSONObject().put("card_id", id).put("captured_ns", System.nanoTime())
            .put("screen_width", device.displayWidth).put("screen_height", device.displayHeight)
            .put("proof_kind", "actual_loaded_card_bounds_with_source_derived_cover_caption_regions")
        val node = findNode { normalizedId(it) == id }
        if (node == null) return proof.put("complete", false).put("reason", "card_not_visible").also { lastHomeCardVisibilityProof = it }
        try {
            proof.put("cached_card_bounds", Rect().also(node::getBoundsInScreen).flattenToString())
            if (!node.refresh()) return proof.put("complete", false).put("reason", "obsolete_card").also { lastHomeCardVisibilityProof = it }
            // Read every decisive property after the same refresh. hasFocusedChild
            // refreshes its argument, so calling it after measuring the shell can
            // otherwise combine geometry from one accessibility epoch with focus
            // and content state from another while the viewport is settling.
            val descendantFocus = if (node.isFocused) false else (0 until node.childCount).any { index ->
                val child = node.getChild(index) ?: return@any false
                try { hasFocusedChild(child) } finally { child.recycle() }
            }
            if (!node.refresh()) return proof.put("complete", false).put("reason", "obsolete_card_after_focus_read").also { lastHomeCardVisibilityProof = it }
            val card = Rect().also(node::getBoundsInScreen)
            val small = card.width() < device.displayWidth * 0.18f
            val focused = node.isFocused || descendantFocus
            val loadedState = node.stateDescription?.toString().orEmpty()
            val nativeDescription = node.contentDescription?.toString().orEmpty()
            val nativeText = node.text?.toString().orEmpty()
            // Compose can export a merging parent's contentDescription through
            // a synthetic child; public text may also live on descendants.
            // Accept actual labels from this card's native
            // subtree, never its state label, action label, or automation ID.
            val publicNodes = JSONArray()
            val publicLabels = linkedSetOf<String>()
            val genericLabels = stringLabels("browse_loaded") + stringLabels("browse_loading") + stringLabels("actions_menu")
            fun addLabel(value: String) {
                value.trim().takeIf { it.isNotEmpty() && it !in genericLabels }?.let(publicLabels::add)
            }
            fun recordPublicNode(value: AccessibilityNodeInfo, path: String, depth: Int, refresh: Boolean) {
                if (publicNodes.length() >= 48 || depth > 6) return
                val current = !refresh || value.refresh()
                val item = JSONObject().put("path", path).put("refreshed", current)
                publicNodes.put(item)
                if (!current) return
                val text = value.text?.toString().orEmpty()
                val description = value.contentDescription?.toString().orEmpty()
                val visible = value.isVisibleToUser
                item.put("id", normalizedId(value)).put("role", value.className?.toString().orEmpty())
                    .put("visible", visible).put("text", text).put("content_description", description)
                    .put("state", value.stateDescription?.toString().orEmpty())
                    .put("bounds", Rect().also(value::getBoundsInScreen).flattenToString())
                if (visible) { addLabel(text); addLabel(description) }
                for (index in 0 until value.childCount) {
                    val child = value.getChild(index) ?: continue
                    try { recordPublicNode(child, path + "/" + index, depth + 1, refresh = true) }
                    finally { child.recycle() }
                }
            }
            recordPublicNode(node, "card", 0, refresh = false)
            val accessibleCaption = publicLabels.joinToString(", ")
            // TvBrowseViewport uses area scaling, not wm density. Native bounds include focus scale.
            val viewportScale = kotlin.math.sqrt(device.displayWidth.toFloat() * device.displayHeight / (960f * 540f))
            val focusScale = if (focused) if (small) 1.04f else 1.035f else 1f
            val px = viewportScale * focusScale
            val expectedWidth = (if (small) 124f else 200f) * px
            val expectedHeight = (if (small) 170f else 278f) * px
            fun rect(top: Float, bottom: Float) = Rect(card.left, (card.top + top * px).toInt(), card.right, (card.top + bottom * px).toInt())
            val fontScale = instrumentation.context.createPackageContext(TARGET, 0).resources.configuration.fontScale
            val cover = if (small) rect(0f, 124f) else Rect(card)
            val titleTop = 124f + 7f
            val titleBottom = titleTop + 16f * fontScale
            val artistTop = titleBottom + 2f
            val artistBottom = artistTop + 14f * fontScale
            val captions = if (small) listOf(rect(titleTop, titleBottom), rect(artistTop, artistBottom))
                else listOf(rect(278f - (13f + 34f + 16f) * fontScale - 6f - 26f, 278f - 13f))
            fun insideScreen(value: Rect) = value.width() > 0 && value.height() > 0 && value.left >= 2 && value.top >= 2 &&
                value.right <= device.displayWidth - 2 && value.bottom <= device.displayHeight - 2
            val widthComplete = kotlin.math.abs(card.width() - expectedWidth) <= 4f
            val heightComplete = kotlin.math.abs(card.height() - expectedHeight) <= 4f
            val cardInsideScreen = insideScreen(card)
            val fullCard = widthComplete && heightComplete && cardInsideScreen
            val loadedLabels = stringLabels("browse_loaded")
            val loadedStateMatches = loadedState in loadedLabels
            val captionPresent = accessibleCaption.isNotBlank()
            val loaded = loadedStateMatches && captionPresent
            val fullCover = insideScreen(cover) && card.contains(cover)
            val captionInsideScreen = captions.map(::insideScreen)
            val captionInsideCard = captions.map(card::contains)
            val fullCaptions = captionInsideScreen.all { it } && captionInsideCard.all { it }
            proof.put("card_bounds", card.flattenToString()).put("small_card", small).put("focused", focused)
                .put("viewport_scale", viewportScale).put("focus_scale", focusScale).put("font_scale", fontScale)
                .put("expected_card_width_px", expectedWidth).put("expected_card_height_px", expectedHeight)
                .put("card_width_complete", widthComplete).put("card_height_complete", heightComplete)
                .put("card_inside_screen", cardInsideScreen).put("card_layout_complete", fullCard)
                .put("cover_region", cover.flattenToString()).put("cover_inside_screen", insideScreen(cover))
                .put("cover_inside_card", card.contains(cover))
                .put("cover_complete", fullCover).put("captions_complete", fullCaptions)
                .put("caption_regions", JSONArray(captions.map(Rect::flattenToString)))
                .put("caption_inside_screen", JSONArray(captionInsideScreen)).put("caption_inside_card", JSONArray(captionInsideCard))
                .put("expected_loaded_labels", JSONArray(loadedLabels.toList()))
                .put("content_state_matches", loadedStateMatches).put("accessible_caption_present", captionPresent)
                .put("native_text", nativeText).put("native_content_description", nativeDescription)
                .put("public_caption_labels", JSONArray(publicLabels.toList())).put("public_semantics_nodes", publicNodes)
                .put("accessible_caption", accessibleCaption).put("content_state", loadedState).put("content_loaded", loaded)
                .put("child_semantics", "cleared_by_HomeCardContent; regions are derived from source layout, not OCR")
                .put("complete", fullCard && fullCover && fullCaptions && loaded)
            return proof.also { lastHomeCardVisibilityProof = it }
        } finally { node.recycle() }
    }

    fun firstLibraryCard(kind: String): String {
        if (kind !in setOf("album", "playlist")) throw SurveyUnsafe("Unsupported library setup kind " + kind)
        settle(420)
        val first = "library-item-0-" + kind
        resetIndexedViewport("library-grid", Regex("library-item-(\\d+)-" + kind), first)
        return first
    }

    /** Setup only: restore a retained public list/grid without activating a media item. */
    private fun resetIndexedViewport(container: String, item: Regex, first: String) {
        if (container !in setOf("library-grid", "search-result-list", "search-result-grid") || !item.matches(first))
            throw SurveyUnsafe("Unrecognized indexed viewport reset")
        requireForeground()
        awaitId(container)
        await("Indexed viewport has no ready item", 45_000) { hasPattern(item) }
        // Move only through proven item focus, observing progress after each
        // Up instead of depending on optional virtual scrolling actions or
        // sending an unbounded key burst.
        repeat(80) {
            if (has(first)) { focus(first); settle(650); return }
            val anchor = snapshot().ids.filter(item::matches)
                .minByOrNull { item.matchEntire(it)!!.groupValues[1].toInt() }
                ?: throw SurveyUnavailable("Indexed viewport has no visible focus anchor")
            val previous = item.matchEntire(anchor)!!.groupValues[1].toInt()
            focus(anchor)
            key(KeyEvent.KEYCODE_DPAD_UP, 120)
            await("Indexed viewport reset did not move toward item zero", 4_000) {
                has(first) || snapshot().focused.any {
                    item.matchEntire(it)?.groupValues?.get(1)?.toInt()?.let { index -> index < previous } == true
                }
            }
        }
        throw SurveyUnavailable("Indexed viewport reset exceeded bounded traversal")
    }

    fun openCollection(kind: String): String {
        val cached = collectionChoices[kind]
        val initial = if (cached != null) {
            positionCollectionCard(cached.first, kind)
            cached.first
        } else typedCard(kind)
        val homeCard = Regex("shelf-(\\d+)-item-(\\d+)-" + kind).matchEntire(initial)
        val candidates = when {
            cached != null -> listOf(cached.first)
            initial.startsWith("library-") -> (0..11).map { "library-item-" + it + "-" + kind }
            homeCard != null -> {
                val sameRow = Regex("shelf-" + homeCard.groupValues[1] + "-item-(\\d+)-" + kind)
                snapshot().ids.filter(sameRow::matches)
                    .sortedBy { sameRow.matchEntire(it)!!.groupValues[1].toInt() }.take(4)
            }
            else -> throw SurveyUnavailable("No recognized collection candidate source")
        }
        val rejected = JSONArray()
        for ((candidateIndex, id) in candidates.withIndex()) {
            if (candidateIndex > 0) positionCollectionCard(id, kind)
            try { awaitId(id, 4_000) } catch (_: SurveyUnavailable) { break }
            focus(id)
            val sourceVisibility = if (id.startsWith("shelf-")) homeCardVisibility(id) else null
            activate(id, "collection")
            awaitId("collection-screen"); awaitId("collection-song-0", 60_000)
            await("Collection title not ready", 15_000) { label("collection-title").isNotBlank() }
            val title = label("collection-title")
            if (cached != null && title != cached.second) throw SurveyUnavailable("Chosen collection content changed")
            focus("collection-song-0"); settle(420)
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN, 150) }
            val eligible = try {
                await("Collection does not prove six navigable tracks", 4_000) { "collection-song-5" in snapshot().focused }
                true
            } catch (_: SurveyUnavailable) { false }
            if (!eligible) {
                rejected.put(JSONObject().put("card_id", id).put("title", title).put("reason", "six_track_focus_proof_failed"))
                if (cached != null) throw SurveyUnavailable("Chosen collection lost its six-track focus proof")
                continue
            }
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_UP, 150) }
            await("Collection did not reset to song zero", 4_000) { "collection-song-0" in snapshot().focused }
            focus("collection-song-0"); settle(650)
            collectionChoices[kind] = id to title
            collectionSelectionProof = JSONObject().put("card_id", id).put("title", title)
                .put("minimum_proven_tracks", 6).put("cached_choice", cached != null).put("rejected_candidates", rejected)
                .put("candidate_source", if (id.startsWith("library-")) "library_first_twelve" else "home_same_row_first_four_visible")
                .put("source_visibility", sourceVisibility ?: JSONObject.NULL)
            return title
        }
        throw SurveyUnavailable("No six-track collection among the bounded visible candidates: " + rejected)
    }

    /** Explicit host-approved fixture only; a changed card/title/track list fails without rediscovery. */
    fun openPreferredAlbum(cardId: String, expectedTitle: String): JSONObject {
        val match = Regex("library-item-(\\d+)-album").matchEntire(cardId)
            ?: throw SurveyUnsafe("Preferred album must be an exact Library album card")
        if (match.groupValues[1].toInt() !in 0..11 || expectedTitle.isBlank())
            throw SurveyUnsafe("Preferred album is outside the bounded public fixture")
        val proof = JSONObject().put("card_id", cardId).put("expected_title", expectedTitle)
            .put("explicit_host_fixture", true).put("fallback_allowed", false)
        collectionSelectionProof = proof
        positionCollectionCard(cardId, "album")
        val sourceLabel = label(cardId)
        proof.put("source_label", sourceLabel).put("source_snapshot", snapshot().json())
        // Library accessibility combines the cover description, title and artist
        // with spaces. The opened collection's exact title is checked below
        // before any playback; do not mistake this merged label for a new title.
        if (sourceLabel != expectedTitle && !sourceLabel.startsWith("$expectedTitle, ") &&
            !sourceLabel.startsWith("$expectedTitle "))
            throw SurveyUnavailable("Preferred album card title changed: $sourceLabel")
        activate(cardId, "collection")
        awaitId("collection-screen"); awaitId("collection-song-0", 60_000); awaitId("collection-song-1", 8_000)
        await("Preferred album collection title is not ready", 8_000) { label("collection-title").isNotBlank() }
        val title = label("collection-title")
        proof.put("title", title).put("song_zero", label("collection-song-0")).put("song_one", label("collection-song-1"))
        if (title != expectedTitle) throw SurveyUnavailable("Preferred album opened a different title: $title")
        if (label("collection-song-0").isBlank() || label("collection-song-1").isBlank())
            throw SurveyUnavailable("Preferred album has no two real visible song labels")
        focus("collection-song-0")
        key(KeyEvent.KEYCODE_DPAD_DOWN, 150)
        await("Preferred album Down did not reach actual song one", 4_000) { "collection-song-1" in snapshot().focused }
        key(KeyEvent.KEYCODE_DPAD_UP, 150)
        await("Preferred album Up did not return to actual song zero", 4_000) { "collection-song-0" in snapshot().focused }
        return proof.put("minimum_proven_tracks", 2).put("actual_song_down_up", true)
            .put("candidate_source", "explicit_preferred_library_card").put("rejected_candidates", JSONArray())
    }

    /** Restore the proven card's own source; a later Library result cannot replace a Home choice. */
    private fun positionCollectionCard(id: String, kind: String) {
        val library = Regex("library-item-(\\d+)-" + kind).matchEntire(id)
        val home = Regex("shelf-(\\d+)-item-(\\d+)-" + kind).matchEntire(id)
        when {
            library != null -> {
                navigate("library")
                focus("library-category-" + kind + "s")
                activate("library-category-" + kind + "s", "library_category")
                await("Proven library category did not load", 45_000) { hasPattern(Regex("library-item-\\d+-" + kind)) }
                firstLibraryCard(kind)
                // The discovery bound is twelve; the first viewport has three five-column rows.
                if (library.groupValues[1].toInt() > 11) throw SurveyUnavailable("Cached library card exceeds discovery bound")
                awaitId(id, 8_000)
            }
            home != null -> {
                firstShelf("home")
                val row = home.groupValues[1].toInt()
                val column = home.groupValues[2].toInt()
                if (row > 20 || column > 100) throw SurveyUnavailable("Cached Home card exceeds bounded setup")
                for (next in 1..row) {
                    key(KeyEvent.KEYCODE_DPAD_DOWN, 200)
                    await("Proven Home row not reached: " + next, 6_000) {
                        snapshot().focused.any { it.startsWith("shelf-" + next + "-item-") }
                    }
                }
                var current = shelfInfo().column
                while (current != column) {
                    val target = current + if (current < column) 1 else -1
                    key(if (current < column) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, 150)
                    await("Proven Home column not reached: " + target, 4_000) {
                        snapshot().focused.any { Regex("shelf-" + row + "-item-" + target + "-[a-z]+").matches(it) }
                    }
                    current = target
                }
                awaitId(id, 6_000)
                awaitFullyVisibleHomeCard(id)
            }
            else -> throw SurveyUnsafe("Unrecognized cached collection source " + id)
        }
        focus(id)
    }

    fun clearSearchQueryForSetup() {
        requireForeground()
        focus("search-field")
        verifiedActivation("search-field", "search_input").use {
            requireActiveFocusedSearchInput()
            injectPair(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        await("Search must expose its real editing action", 3_000L) {
            findNode { normalizedId(it) == "search-field" }?.let { node ->
                try { node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
                finally { node.recycle() }
            } == true
        }
        val field = findNode { normalizedId(it) == "search-field" } ?: throw SurveyUnavailable("Search editor unavailable")
        try {
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            })) { "Real Search editor rejected empty text" }
        } finally { field.recycle() }
        // One Back dismisses the IME. The explicit scope action below ends
        // editing, avoiding a second Back that can open the navigation panel.
        requireTargetOrItsIme(); inject(KeyEvent.KEYCODE_BACK); settle(400L)
        await("Search must regain its native window", 3_000L) { foreground() == TARGET }
        activate("search-scope-library", "search_scope")
    }

    fun search(category: String, query: String = "NewJeans") {
        require(query.isNotBlank() && query.length <= 80)
        navigate("search")
        activate("search-scope-catalog", "search_scope")
        focus("search-field")
        // CENTER is confined to the proven text field; it cannot activate a result.
        verifiedActivation("search-field", "search_input").use {
            requireActiveFocusedSearchInput()
            injectPair(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        settle(180)
        if (!targetIsResumed())
            throw SurveyUnsafe("Target is not resumed during text input")
        val node = findNode { normalizedId(it) == "search-field" } ?: throw SurveyUnavailable("No search field")
        try {
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                })) throw SurveyUnavailable("Search field cannot accept text")
        } finally { node.recycle() }
        // Cancel native IME / edit mode before the category/result proof.
        repeat(2) {
            requireTargetOrItsIme()
            if (foreground() != TARGET || !readOnlySearch()) { inject(KeyEvent.KEYCODE_BACK); settle(240) }
        }
        requireForeground()
        focusSearchCategory(category)
        activate("search-category-" + category, "search_category")
        searchProof = category
        val resultContainer = if (category == "songs") "search-result-list" else "search-result-grid"
        resetIndexedViewport(resultContainer, Regex("search-result-(\\d+)"), "search-result-0")
    }

    /** Public category focus only; retained lazy category viewports can hide Songs. */
    private fun focusSearchCategory(category: String) {
        // CatalogSearchScreen's presentation order differs from enum order.
        // Capabilities may remove entries, so observe each actual focus move
        // instead of assuming that every intermediate category is available.
        val order = listOf("songs", "artists", "albums", "playlists", "curators", "applecurators",
            "recordlabels", "activities", "musicvideos", "stations")
        val desired = order.indexOf(category)
        if (desired < 0) throw SurveyUnsafe("Unknown search setup category " + category)
        val target = "search-category-" + category
        await("No visible search category", 15_000) {
            snapshot().ids.any { it.startsWith("search-category-") && it.removePrefix("search-category-") in order }
        }
        if (has(target)) { focus(target); return }
        val anchor = snapshot().ids.filter { it.startsWith("search-category-") && it.removePrefix("search-category-") in order }
            .minByOrNull { kotlin.math.abs(order.indexOf(it.removePrefix("search-category-")) - desired) }
            ?: throw SurveyUnavailable("No known search category focus anchor")
        focus(anchor)
        repeat(order.size) {
            val focused = snapshot().focused.firstOrNull { it.startsWith("search-category-") }
                ?: throw SurveyUnavailable("Search category setup lost focus")
            if (focused == target) return
            val previous = order.indexOf(focused.removePrefix("search-category-"))
            if (previous < 0) throw SurveyUnavailable("Unrecognized focused search category")
            key(if (previous > desired) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, 120)
            await("Search category setup did not move from " + focused, 4_000) {
                snapshot().focused.any { it.startsWith("search-category-") && it != focused }
            }
            val moved = snapshot().focused.firstOrNull { it.startsWith("search-category-") }
                ?: throw SurveyUnavailable("Search category setup lost its destination")
            val next = order.indexOf(moved.removePrefix("search-category-"))
            if (next < 0 || (previous > desired && next > previous) || (previous < desired && next < previous) ||
                (previous > desired && next < desired) || (previous < desired && next > desired))
                throw SurveyUnavailable("Requested search category is not reachable in the available category order")
        }
        throw SurveyUnavailable("Search category setup exceeded its bounded traversal")
    }

    private fun readOnlySearch(): Boolean = findNode { normalizedId(it) == "search-field" }?.let { node ->
        try { node.stateDescription?.toString() in stringLabels("browse_search_hint_remote") } finally { node.recycle() }
    } == true

    fun openNewJeans() {
        val canonical = "artist-profile-artists-1635469693"
        val probes = JSONArray()
        artistSelectionProof = JSONObject().put("requested_artist", "NewJeans")
            .put("requested_profile_id", canonical).put("candidate_probes", probes)
        search("artists")
        var showingProfile = false
        // Accessibility traversal can place the focused result last. Probe by
        // actual numeric result order, never by tree traversal's first match.
        for (index in 0 until 10) {
            if (showingProfile) { search("artists"); showingProfile = false }
            val id = "search-result-" + index
            if (!showArtistSearchResult(index)) break
            val resultLabel = label(id)
            if (!exactTextAt(id, "NewJeans")) {
                probes.put(JSONObject().put("result_id", id).put("result_label", resultLabel)
                    .put("reason", "not_exact_public_artist_name"))
                continue
            }
            focus(id)
            activate(id, "artist")
            showingProfile = true
            awaitId("artist-screen")
            awaitId("artist-title", 60_000)
            await("Artist profile identity did not arrive", 15_000) {
                snapshot().ids.any { it.startsWith("artist-profile-") }
            }
            val title = label("artist-title")
            val ids = snapshot().ids.filter { it.startsWith("artist-profile-") }
            val canonicalMatch = canonical in ids && title.equals("NewJeans", ignoreCase = true)
            probes.put(JSONObject().put("result_id", id).put("result_label", resultLabel)
                .put("rendered_artist_title", title).put("profile_ids", JSONArray(ids))
                .put("canonical_match", canonicalMatch))
            if (!canonicalMatch) continue
            await("Artist sections unavailable", 60_000) { hasPattern(Regex("artist-section-.*")) }
            awaitId("artist-topsongs-0-song", 45_000)
            focus("artist-topsongs-0-song")
            artistSelectionProof.put("result_id", id).put("result_label", resultLabel)
                .put("rendered_artist_title", title).put("canonical_profile_id", canonical)
                .put("search_category", "artists").put("sections_at_entry", artistSectionCounts())
            return
        }
        throw SurveyUnavailable("Canonical NewJeans was not found among the first ten public artist results: " + probes)
    }

    private fun showArtistSearchResult(index: Int): Boolean {
        if (searchProof != "artists" || !has("search-result-grid"))
            throw SurveyUnsafe("Artist candidate discovery lost its proven search category")
        val id = "search-result-" + index
        repeat(4) {
            if (has(id)) return true
            val before = snapshot().focused.firstOrNull { Regex("search-result-\\d+").matches(it) }
                ?: throw SurveyUnavailable("Artist candidate discovery has no result focus")
            key(KeyEvent.KEYCODE_DPAD_DOWN, 350)
            settle(150)
            if (has(id)) return true
            val after = snapshot().focused.firstOrNull { Regex("search-result-\\d+").matches(it) }
            if (after == before) return false
        }
        return has(id)
    }

    private fun artistSectionCounts(): JSONArray = JSONArray(snapshot().ids.filter { it.startsWith("artist-section-") }.map {
        JSONObject().put("section_id", it).put("loaded_item_count", sectionColumns(it) ?: JSONObject.NULL)
    })

    /** Setup only: choose a loaded real shelf and restore its first card. */
    fun artistHorizontalShelf(): SurveyArtistShelf {
        repeat(12) {
            val state = snapshot()
            val sectionIds = state.ids.filter { it.startsWith("artist-section-") }
            for (sectionId in sectionIds) {
                val count = sectionColumns(sectionId) ?: continue
                if (count < 2) continue
                val section = sectionId.removePrefix("artist-section-")
                val pattern = Regex("artist-" + section + "-(\\d+)-[a-z]+")
                val anchor = state.ids.filter(pattern::matches)
                    .minByOrNull { pattern.matchEntire(it)!!.groupValues[1].toInt() } ?: continue
                focus(anchor)
                val current = snapshot().focused.firstOrNull(pattern::matches) ?: continue
                val column = pattern.matchEntire(current)!!.groupValues[1].toInt()
                if (column > 100) throw SurveyUnavailable("Artist shelf start column outside bounded setup")
                for (position in column downTo 1) {
                    key(KeyEvent.KEYCODE_DPAD_LEFT, 120)
                    val target = Regex("artist-" + section + "-" + (position - 1) + "-[a-z]+")
                    await("Artist horizontal setup did not reach column " + (position - 1), 4_000) {
                        snapshot().focused.any(target::matches)
                    }
                }
                val first = snapshot().focused.firstOrNull { Regex("artist-" + section + "-0-[a-z]+").matches(it) }
                    ?: throw SurveyUnavailable("Artist shelf first card not focused")
                focus(first)
                return SurveyArtistShelf(section, count, first)
            }
            if ("nav-panel" in state.ids) enterBrowseContentForSetup()
            key(KeyEvent.KEYCODE_DPAD_DOWN, 350)
            settle(350)
        }
        artistSelectionProof.put("sections_after_bounded_scan", artistSectionCounts())
        throw SurveyUnavailable("No loaded artist shelf with at least two real items")
    }

    fun playerStateDescription(): String {
        val node = findNode { normalizedId(it) == "player-screen" } ?: return ""
        return try { node.stateDescription?.toString().orEmpty() } finally { node.recycle() }
    }

    fun playerPaused(): Boolean = playerStateDescription() in stringLabels("player_state_paused")

    fun pausedPlayer(queue: Boolean) {
        navigate("home")
        openNavigation()
        if (!has("nav-nowplaying")) throw SurveyUnavailable("No existing player")
        activate("nav-nowplaying", "navigation")
        awaitId("player-screen")
        if (!playerPaused()) throw SurveyUnavailable("Existing player is not paused; playback left untouched")
        focus("player-screen")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, 320)
        awaitId("player-controls")
        if (queue) {
            if (!has("queue-panel")) activate("player-queue", "queue_view")
            awaitId("queue-panel")
            await("No existing queue entries", 5_000) { hasPattern(Regex("player-queue-item-\\d+")) }
            if (snapshot().selected.none { it.startsWith("player-queue-item-") }) {
                key(KeyEvent.KEYCODE_DPAD_UP, 160); key(KeyEvent.KEYCODE_DPAD_UP, 160)
            }
            if (snapshot().selected.none { it.startsWith("player-queue-item-") })
                throw SurveyUnavailable("Queue has no proven item-selection zone")
        } else {
            if (has("queue-panel")) activate("player-queue", "queue_view")
            repeat(6) { key(KeyEvent.KEYCODE_DPAD_DOWN, 80) }
            repeat(10) { key(KeyEvent.KEYCODE_DPAD_LEFT, 50) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 120)
        }
        if (!playerPaused()) throw SurveyUnsafe("Playback state changed during player setup")
    }

    fun prepareSettingsFocus() {
        navigate("account")
        if (!has("language-system")) {
            focusSettingsControl()
            settle(420)
            repeat(30) {
                if (has("language-system")) { focus("language-system"); settle(420); return }
                if (has("nav-panel")) throw SurveyUnavailable("Settings Down left its account owner")
                // Setup scrolling only. Left/Right might change a slider;
                // CENTER would activate a control, so neither is sent here.
                key(KeyEvent.KEYCODE_DPAD_DOWN, 150)
            }
        }
        if (!has("language-system")) throw SurveyUnavailable("Language controls not reached within bounded Settings scroll")
        focus("language-system"); settle(420)
    }

    fun focusSettingsControl() {
        awaitId("browse-account")
        fun insideAccount(node: AccessibilityNodeInfo): Boolean {
            val parent = node.parent ?: return false
            return try { normalizedId(parent) == "browse-account" || insideAccount(parent) }
            finally { parent.recycle() }
        }
        val node = findNode { normalizedId(it).isBlank() && it.isFocusable && it.isClickable && insideAccount(it) }
            ?: throw SurveyUnavailable("No public focusable Settings control")
        try {
            if (!node.isFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                throw SurveyUnavailable("Settings control cannot receive focus")
        } finally { node.recycle() }
    }

    fun snapshot(): SurveySnapshot {
        val nodes = mutableListOf<SurveyNode>()
        var refreshedFocusNodes = 0
        var obsoleteFocusNodes = 0
        fun visit(node: AccessibilityNodeInfo, path: String, ancestorId: String) {
            try {
                // Virtual player controls own selection while their parent owns
                // native focus. Refresh their IDs too: cached selected flags can
                // otherwise combine a previous scrubber with the new control.
                val focusNode = node.isFocusable || node.isFocused
                if (focusNode || normalizedId(node).isNotBlank() || node.isSelected || node.isChecked) {
                    if (!node.refresh()) { if (focusNode) obsoleteFocusNodes++; return }
                    if (focusNode) refreshedFocusNodes++
                }
                val ownId = normalizedId(node)
                val resolvedId = ownId.ifBlank { ancestorId }
                if (node.isVisibleToUser && node.packageName?.toString() == TARGET) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    nodes += SurveyNode(ownId, resolvedId, path, node.className?.toString().orEmpty(),
                        bounds.flattenToString(), node.isFocused, node.isSelected || node.isChecked, node.isFocusable, node.isClickable)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, path + "/" + i, resolvedId) }
            } finally { node.recycle() }
        }
        targetRoots().forEachIndexed { index, root -> visit(root, index.toString(), "") }
        return SurveySnapshot(foreground(), nodes, refreshedFocusNodes, obsoleteFocusNodes)
    }
}

internal class SurveyActivation(val id: String, val kind: String, val role: String,
    private val node: AccessibilityNodeInfo) : AutoCloseable {
    fun execute(): Boolean {
        check(node.refresh() && node.viewIdResourceName?.substringAfter(":id/") == id && node.isEnabled && node.isClickable) {
            "Activation source changed before click: $id"
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    @Suppress("DEPRECATION") override fun close() = node.recycle()
}

internal class SurveyLongPress(val id: String, val holdMs: Long, private val action: () -> Unit) {
    fun execute() = action()
}

internal data class SurveyNode(val id: String, val resolvedId: String, val path: String, val role: String, val bounds: String,
    val focused: Boolean, val selected: Boolean, val focusable: Boolean, val clickable: Boolean)

internal data class SurveySnapshot(val foreground: String, val nodes: List<SurveyNode>,
    val refreshedFocusNodes: Int = 0, val obsoleteFocusNodes: Int = 0) {
    val ids get() = nodes.map { it.id }.filter(String::isNotBlank).distinct()
    val focused get() = nodes.filter { it.focused }.map { it.resolvedId.ifBlank { it.path + ":" + it.bounds } }.distinct()
    val selected get() = nodes.filter { it.selected }.map { it.resolvedId.ifBlank { it.path + ":" + it.bounds } }.distinct()
    val fingerprint get() = nodes.filter { it.id.isNotBlank() || it.focused || it.selected }
        .map { it.id + ":" + it.bounds + ":" + it.focused + ":" + it.selected }
    fun json(detailed: Boolean = false): JSONObject = JSONObject().put("foreground", foreground).put("focus", JSONArray(focused))
        .put("refreshed_focus_nodes", refreshedFocusNodes).put("obsolete_focus_nodes", obsoleteFocusNodes)
        .put("selected", JSONArray(selected)).put("visible_ids", JSONArray(ids))
        .put("rows", JSONObject().apply {
            for (prefix in listOf("collection-song-", "player-queue-item-", "search-result-", "library-item-")) {
                put(prefix, JSONArray(ids.filter { it.startsWith(prefix) }))
            }
        })
        .apply {
            if (detailed) put("nodes", JSONArray(nodes.map { node ->
                JSONObject().put("id", node.id).put("resolved_focus_id", node.resolvedId)
                    .put("path", node.path).put("role", node.role).put("bounds", node.bounds)
                    .put("focused", node.focused).put("selected", node.selected)
                    .put("focusable", node.focusable).put("clickable", node.clickable)
            }))
        }
}

internal data class SurveyShelf(val row: Int, val column: Int, val kind: String, val cardCount: Int,
    val rowCount: Int, val widthPx: Int, val displayWidthPx: Int, val fingerprint: String) {
    fun json(): JSONObject = JSONObject().put("row", row).put("start_column", column)
        .put("start_card_kind", kind)
        .put("available_card_count", cardCount).put("available_row_count", rowCount)
        .put("card_width_px", widthPx).put("display_width_px", displayWidthPx).put("content_fingerprint", fingerprint)
}

internal data class SurveyArtistShelf(val section: String, val cardCount: Int, val firstCardId: String) {
    fun json(): JSONObject = JSONObject().put("section", section).put("loaded_item_count", cardCount)
        .put("start_card_id", firstCardId).put("start_column", 0)
}
