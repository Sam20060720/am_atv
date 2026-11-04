package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.amatv.R
import java.util.Locale
import kotlin.math.roundToInt

private enum class LyricsLanguagePicker { Translation, Pronunciation, LargerText }

private val TranslationLanguageTags = listOf(
    "", "zh-TW", "zh-CN", "en-US", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE",
)

/** Lyrics preferences use the same panel, rows and window-local focus as catalog actions. */
@Composable
internal fun LyricsDisplaySettingsDialog(
    anchorBounds: Rect?,
    backdropLayer: GraphicsLayer? = null,
    lowPerformanceMode: Boolean = false,
    onBackHandlerChanged: ((() -> Unit)?) -> Unit = {},
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    pronunciationLarger: Boolean,
    translationAvailable: Boolean,
    pronunciationAvailable: Boolean,
    translationLanguageTag: String,
    pronunciationLanguageTag: String,
    appLanguageTag: String,
    enhancementsReady: Boolean,
    enhancementsFailed: Boolean,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    onPronunciationLargerChange: (Boolean) -> Unit,
    onTranslationLanguageChange: (String) -> Unit,
    onPronunciationLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val motion = rememberTvMenuMotion(lowPerformanceMode)
    val latestDismiss by rememberUpdatedState(onDismiss)
    var picker by remember { mutableStateOf<LyricsLanguagePicker?>(null) }
    // The intended pane changes synchronously, before the shared fade swaps
    // rendered content. Back during that fade therefore pops rather than closes.
    var requestedPicker by remember { mutableStateOf<LyricsLanguagePicker?>(null) }
    var returnRow by remember { mutableStateOf("close") }
    var pendingFocus by remember { mutableStateOf<String?>("close") }
    var focusedRow by remember { mutableStateOf<String?>(null) }
    var lastFocusedRow by remember(picker) { mutableStateOf<String?>(null) }
    var backHeld by remember { mutableStateOf(false) }
    val inputGate = remember { CatalogModalInputGate() }
    val mainScroll = rememberLazyListState()
    val pickerScroll = remember(picker) { LazyListState() }
    val appLocale = remember(appLanguageTag) {
        Locale.forLanguageTag(appLanguageTag).takeUnless { it.language.isBlank() } ?: Locale.getDefault()
    }
    fun languageName(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(appLocale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(appLocale) else it.toString() }
    fun openPicker(next: LyricsLanguagePicker, origin: String) {
        if (motion.closing || motion.transitioning) return
        returnRow = origin
        requestedPicker = next
        motion.navigate(goingBack = false) {
            picker = next
            pendingFocus = "option-0"
            focusedRow = null
        }
    }
    fun back() {
        if (motion.closing) return
        inputGate.dialogBackDismissed()
        backHeld = false
        if (requestedPicker != null) {
            requestedPicker = null
            motion.navigate(goingBack = true, replaceTransition = true,
                changeRequired = picker != null,
                beforeTransition = {
                    pendingFocus = returnRow
                    focusedRow = null
                }) {
                picker = null
                pendingFocus = returnRow
                focusedRow = null
            }
        } else motion.dismiss { latestDismiss() }
    }
    val latestBack by rememberUpdatedState<() -> Unit>(::back)
    val latestBackRegistration by rememberUpdatedState(onBackHandlerChanged)
    DisposableEffect(Unit) {
        // Back can still reach the Activity during the frame before this
        // native window takes focus. It must use the same pending exit/pane
        // motion instead of removing the menu immediately.
        latestBackRegistration { latestBack() }
        onDispose { latestBackRegistration(null) }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false,
        windowTitle = "AMATV.LyricsMenu",
    )) {
        TvMenuWindowSetup()
        TvBrowseViewport {
            val metrics = tvMenuMetrics()
            val density = LocalDensity.current
            var viewportSize by remember { mutableStateOf(IntSize.Zero) }
            var menuSize by remember { mutableStateOf(IntSize.Zero) }
            val placement = remember(anchorBounds, menuSize, viewportSize, density) {
                with(density) {
                    catalogMenuPlacement(anchorBounds, menuSize, viewportSize,
                        BrowseTokens.SafeHorizontal.toPx(), BrowseTokens.SafeTop.toPx(), CatalogMenuStyle.Gap.toPx())
                }
            }
            val material = tvMenuMaterialAvailable(backdropLayer, lowPerformanceMode)
            val focusVisibility = motion.visibility
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            val active = menuSize.width > 0 && menuSize.height > 0 && windowFocused &&
                motion.entranceComplete && !motion.closing && !motion.transitioning
            val pronunciationOptions = remember(appLanguageTag) { listOf("en-US", appLanguageTag).distinct() }
            val entryIds = when (picker) {
                null -> listOf("close", "translation", "translation-language", "pronunciation", "pronunciation-language", "larger")
                LyricsLanguagePicker.Translation -> listOf("back") + TranslationLanguageTags.indices.map { "option-$it" }
                LyricsLanguagePicker.Pronunciation -> listOf("back") + pronunciationOptions.indices.map { "option-$it" }
                LyricsLanguagePicker.LargerText -> listOf("back", "option-0", "option-1")
            }
            val requesters = remember(entryIds) {
                entryIds.associateWith { FocusRequester() }
            }
            val scroll = if (picker == null) mainScroll else pickerScroll
            val navigationIds = entryIds
            val ready = active && focusedRow != null && pendingFocus == null
            val latestReady by rememberUpdatedState(ready)
            LaunchedEffect(picker, pendingFocus, focusedRow, active) {
                if (!active) return@LaunchedEffect
                val id = pendingFocus ?: if (focusedRow == null) {
                    lastFocusedRow?.takeIf { it in navigationIds } ?: entryIds.first()
                } else return@LaunchedEffect
                // A native window/IME handoff can release Compose focus while
                // the menu remains mounted. Restore its actual row, not a new
                // preference or the top of the menu.
                if (pendingFocus == null) pendingFocus = id
                val index = entryIds.indexOf(id)
                if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    scroll.scrollToItem(index)
                }
                repeat(4) {
                    withFrameNanos { }
                    if (pendingFocus != id) return@LaunchedEffect
                    runCatching { requesters[id]?.requestFocus() }
                }
            }
            fun target(id: String): Modifier = Modifier.focusRequester(requesters.getValue(id))
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        focusedRow = id
                        lastFocusedRow = id
                        if (pendingFocus == id) pendingFocus = null
                    } else if (focusedRow == id) focusedRow = null
                }
            fun rowModifier(id: String, index: Int): Modifier = target(id).focusProperties {
                up = entryIds.getOrNull(index - 1)?.let(requesters::getValue) ?: FocusRequester.Cancel
                down = entryIds.getOrNull(index + 1)?.let(requesters::getValue) ?: FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            CompositionLocalProvider(
                LocalFocusTargetsEnabled provides active,
                LocalContentFocusEntry provides null,
                LocalCatalogMenuCanActivate provides { latestReady },
                LocalCatalogMenuAnchorReporter provides null,
            ) {
                TvMenuFocusScope(visibility = focusVisibility, active = active) {
                    Box(Modifier.fillMaxSize().automationId("lyrics-display-menu")
                        .onSizeChanged { viewportSize = it }
                        .onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            val backKey = key.keyCode == KeyEvent.KEYCODE_BACK
                            val activation = key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
                            val vertical = key.keyCode == KeyEvent.KEYCODE_DPAD_UP || key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            if (vertical) {
                                if (key.action == KeyEvent.ACTION_DOWN && active) {
                                    val current = pendingFocus ?: focusedRow ?: lastFocusedRow ?: entryIds.first()
                                    val step = if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                                    val index = navigationIds.indexOf(current).coerceAtLeast(0)
                                    val target = navigationIds[(index + step).coerceIn(navigationIds.indices)]
                                    if (target != current) pendingFocus = target
                                }
                                true
                            } else if (key.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                                false
                            } else if (backKey) {
                                inputGate.dialog(ready, true, false, key.action == KeyEvent.ACTION_DOWN,
                                    key.action == KeyEvent.ACTION_UP, key.repeatCount)
                                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) backHeld = true
                                if (key.action == KeyEvent.ACTION_UP) {
                                    val owned = backHeld
                                    backHeld = false
                                    if (owned && !key.isCanceled) back()
                                }
                                true
                            } else !inputGate.dialog(ready, false, activation,
                                key.action == KeyEvent.ACTION_DOWN, key.action == KeyEvent.ACTION_UP,
                                key.repeatCount)
                        }) {
                        Box(Modifier.fillMaxSize().tvMenuScrim(motion)
                            .pointerInput(picker) { detectTapGestures { back() } })
                        if (material) TvMenuBackdrop(checkNotNull(backdropLayer), placement, motion)
                        TvMenuSurface(Modifier
                            .offset { IntOffset(placement.bounds.left.roundToInt(), placement.bounds.top.roundToInt()) }
                            .width(metrics.width).heightIn(max = metrics.maxHeight)
                            .onSizeChanged { menuSize = it }
                            .tvMenuEntrance(motion, placement)
                            .automationId("lyrics-menu-panel")
                            .focusGroup().pointerInput(Unit) { detectTapGestures { } },
                            material = material, motionEnabled = motion.motionEnabled) {
                            LazyColumn(state = scroll,
                                modifier = Modifier.fillMaxWidth().tvMenuPane(motion),
                                verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing),
                                contentPadding = PaddingValues(metrics.viewportPadding)) {
                                itemsIndexed(entryIds, key = { _, id -> id }) { index, id ->
                                    val row = rowModifier(id, index)
                                    when (id) {
                                        "close" -> CatalogSheetRow(
                                            stringResource(R.string.player_lyrics_menu_close),
                                            row.automationId("lyrics-menu-close"), icon = Icons.Rounded.Close,
                                            singleLine = true, onClick = ::back)
                                        "back" -> CatalogSheetRow(
                                            stringResource(R.string.player_lyrics_language_back),
                                            row.automationId("lyrics-menu-back"), icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                            singleLine = true, onClick = ::back)
                                        else -> when (picker) {
                                            null -> when (id) {
                                                "translation" -> CatalogSheetRow(
                                                    stringResource(R.string.player_lyrics_translation),
                                                    row.automationId("lyrics-menu-translation"), selected = translationEnabled,
                                                    icon = Icons.Rounded.Translate, singleLine = true,
                                                    value = stringResource(toggleStatus(translationEnabled, translationAvailable,
                                                        enhancementsReady, enhancementsFailed)),
                                                    onClick = { onTranslationChange(!translationEnabled) })
                                                "translation-language" -> CatalogSheetRow(
                                                    stringResource(R.string.player_lyrics_translate_to),
                                                    row.automationId("lyrics-menu-translation-language"), submenu = true, singleLine = true,
                                                    value = if (translationLanguageTag.isBlank()) stringResource(R.string.player_lyrics_menu_follow_app)
                                                        else lyricsLanguageValue(translationLanguageTag, appLocale),
                                                    onClick = { openPicker(LyricsLanguagePicker.Translation, id) })
                                                "pronunciation" -> CatalogSheetRow(
                                                    stringResource(R.string.player_lyrics_pronunciation),
                                                    row.automationId("lyrics-menu-pronunciation"), selected = pronunciationEnabled, singleLine = true,
                                                    value = stringResource(toggleStatus(pronunciationEnabled, pronunciationAvailable,
                                                        enhancementsReady, enhancementsFailed)),
                                                    onClick = { onPronunciationChange(!pronunciationEnabled) })
                                                "pronunciation-language" -> CatalogSheetRow(
                                                    stringResource(R.string.player_lyrics_pronunciation_script),
                                                    row.automationId("lyrics-menu-pronunciation-language"), submenu = true, singleLine = true,
                                                    value = stringResource(if (pronunciationLanguageTag == "en-US") R.string.player_lyrics_menu_latin
                                                        else R.string.player_lyrics_menu_follow_app),
                                                    onClick = { openPicker(LyricsLanguagePicker.Pronunciation, id) })
                                                "larger" -> CatalogSheetRow(
                                                    stringResource(R.string.player_lyrics_larger_text),
                                                    row.automationId("lyrics-larger-text"), submenu = true, singleLine = true,
                                                    value = stringResource(if (pronunciationLarger) R.string.player_lyrics_menu_pronunciation_short
                                                        else R.string.player_lyrics),
                                                    onClick = { openPicker(LyricsLanguagePicker.LargerText, id) })
                                            }
                                            LyricsLanguagePicker.Translation -> {
                                                val tag = TranslationLanguageTags[id.removePrefix("option-").toInt()]
                                                CatalogSheetRow(
                                                    if (tag.isBlank()) stringResource(R.string.player_lyrics_menu_follow_app)
                                                        else languageName(tag),
                                                    row.automationId("lyrics-translation-language-${tag.ifBlank { "app" }}"),
                                                    selected = tag == translationLanguageTag, singleLine = true,
                                                    onClick = { onTranslationLanguageChange(tag); back() })
                                            }
                                            LyricsLanguagePicker.Pronunciation -> {
                                                val tag = pronunciationOptions[id.removePrefix("option-").toInt()]
                                                CatalogSheetRow(
                                                    if (tag == "en-US") stringResource(R.string.player_lyrics_pronunciation_latin)
                                                        else stringResource(R.string.player_lyrics_pronunciation_app_script_format, languageName(tag)),
                                                    row.automationId("lyrics-pronunciation-language-$tag"),
                                                    selected = tag == pronunciationLanguageTag, singleLine = true,
                                                    onClick = { onPronunciationLanguageChange(tag); back() })
                                            }
                                            LyricsLanguagePicker.LargerText -> {
                                                val larger = id == "option-1"
                                                CatalogSheetRow(stringResource(if (larger) R.string.player_lyrics_larger_pronunciation
                                                        else R.string.player_lyrics_larger_lyrics),
                                                    row.automationId(if (larger) "lyrics-larger-pronunciation" else "lyrics-larger-lyrics"),
                                                    selected = larger == pronunciationLarger, singleLine = true,
                                                    onClick = { onPronunciationLargerChange(larger); back() })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact main-menu values preserve load state without a second line of help text. */
private fun toggleStatus(enabled: Boolean, available: Boolean, ready: Boolean, failed: Boolean): Int = when {
    !enabled -> R.string.player_lyrics_display_off
    failed -> R.string.player_lyrics_menu_failed
    !ready -> R.string.player_loading
    !available -> R.string.player_lyrics_menu_unavailable
    else -> R.string.player_lyrics_display_on
}

@Composable
private fun lyricsLanguageValue(tag: String, appLocale: Locale): String {
    val locale = remember(tag) { Locale.forLanguageTag(tag) }
    return if (locale.language == "zh") {
        stringResource(if (locale.script == "Hant" || locale.country in listOf("TW", "HK", "MO"))
            R.string.language_traditional_chinese else R.string.language_simplified_chinese)
    } else locale.getDisplayLanguage(appLocale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(appLocale) else it.toString() }
}
