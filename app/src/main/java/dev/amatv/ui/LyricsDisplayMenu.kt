package dev.amatv.ui

import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.amatv.R
import java.util.Locale

private enum class LyricsLanguagePicker { Translation, Pronunciation, LargerText }

private val TranslationLanguageTags = listOf(
    "", "zh-TW", "zh-CN", "en-US", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE",
)

/** Lyrics preferences use the same panel, rows and window-local focus as catalog actions. */
@Composable
internal fun LyricsDisplaySettingsDialog(
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
    var picker by remember { mutableStateOf<LyricsLanguagePicker?>(null) }
    var returnRow by remember { mutableStateOf("translation") }
    var pendingFocus by remember { mutableStateOf<String?>("translation") }
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
        returnRow = origin
        picker = next
        pendingFocus = "option-0"
        focusedRow = null
    }
    fun back() {
        inputGate.dialogBackDismissed()
        backHeld = false
        if (picker != null) {
            picker = null
            pendingFocus = returnRow
            focusedRow = null
        } else onDismiss()
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false,
        windowTitle = "AMATV.LyricsMenu",
    )) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            window?.setWindowAnimations(0)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
            onDispose { }
        }
        TvBrowseViewport {
            val metrics = tvMenuMetrics()
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            val pronunciationOptions = remember(appLanguageTag) { listOf("en-US", appLanguageTag).distinct() }
            val entryIds = when (picker) {
                null -> listOf("translation", "translation-language", "pronunciation", "pronunciation-language", "larger")
                LyricsLanguagePicker.Translation -> TranslationLanguageTags.indices.map { "option-$it" }
                LyricsLanguagePicker.Pronunciation -> pronunciationOptions.indices.map { "option-$it" }
                LyricsLanguagePicker.LargerText -> listOf("option-0", "option-1")
            }
            val requesters = remember(entryIds) {
                (entryIds + "back" + "done").associateWith { FocusRequester() }
            }
            val scroll = if (picker == null) mainScroll else pickerScroll
            val navigationIds = if (picker == null) entryIds + "done" else listOf("back") + entryIds
            val ready = windowFocused && focusedRow != null && pendingFocus == null
            val latestReady by rememberUpdatedState(ready)
            LaunchedEffect(picker, pendingFocus, focusedRow, windowFocused) {
                if (!windowFocused) return@LaunchedEffect
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
                up = entryIds.getOrNull(index - 1)?.let(requesters::getValue)
                    ?: if (picker != null) requesters.getValue("back") else FocusRequester.Cancel
                down = entryIds.getOrNull(index + 1)?.let(requesters::getValue)
                    ?: if (picker == null) requesters.getValue("done") else FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            val title = stringResource(when (picker) {
                null -> R.string.player_lyrics_display_title
                LyricsLanguagePicker.Translation -> R.string.player_lyrics_choose_translation_language
                LyricsLanguagePicker.Pronunciation -> R.string.player_lyrics_choose_pronunciation_script
                LyricsLanguagePicker.LargerText -> R.string.player_lyrics_larger_text
            })
            CompositionLocalProvider(
                LocalFocusTargetsEnabled provides windowFocused,
                LocalContentFocusEntry provides null,
                LocalCatalogMenuCanActivate provides { latestReady },
                LocalCatalogMenuAnchorReporter provides null,
            ) {
                TvMenuFocusScope(active = windowFocused) {
                    Box(Modifier.fillMaxSize().automationId("lyrics-display-menu")
                        .onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            val backKey = key.keyCode == KeyEvent.KEYCODE_BACK
                            val activation = key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
                            val vertical = key.keyCode == KeyEvent.KEYCODE_DPAD_UP || key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            if (vertical) {
                                if (key.action == KeyEvent.ACTION_DOWN && windowFocused) {
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
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .30f))
                            .pointerInput(picker) { detectTapGestures { back() } })
                        TvMenuSurface(Modifier.width(340.dp).heightIn(max = metrics.maxHeight)
                            .automationId("lyrics-menu-panel")
                            .focusGroup().pointerInput(Unit) { detectTapGestures { } }) {
                            if (picker == null) {
                                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = metrics.viewportPadding, vertical = 6.dp))
                                Text(stringResource(R.string.player_lyrics_source_language) + ": " +
                                    stringResource(R.string.player_lyrics_source_auto),
                                    color = Color.White.copy(alpha = .62f), fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = metrics.viewportPadding, vertical = 4.dp))
                            } else {
                                CatalogSheetRow(title, target("back").automationId("lyrics-menu-back")
                                    .padding(horizontal = metrics.viewportPadding).focusProperties {
                                        up = FocusRequester.Cancel; down = requesters.getValue(entryIds.first())
                                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                    }, icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = ::back)
                            }
                            CatalogSheetSeparator()
                            LazyColumn(state = scroll, modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing),
                                contentPadding = PaddingValues(metrics.viewportPadding)) {
                                itemsIndexed(entryIds, key = { _, id -> id }) { index, id ->
                                    val row = rowModifier(id, index)
                                    when (picker) {
                                        null -> when (id) {
                                            "translation" -> CatalogSheetRow(
                                                stringResource(R.string.player_lyrics_translation),
                                                row.automationId("lyrics-menu-translation"), selected = translationEnabled,
                                                icon = Icons.Rounded.Translate,
                                                subtitle = stringResource(when {
                                                    translationEnabled && enhancementsFailed -> R.string.player_lyrics_enhancement_load_failed
                                                    translationEnabled && !enhancementsReady -> R.string.player_lyrics_enhancement_loading
                                                    translationEnabled && !translationAvailable -> R.string.player_lyrics_translation_unavailable
                                                    else -> R.string.player_lyrics_translation_hint
                                                }),
                                                value = stringResource(if (translationEnabled) R.string.player_lyrics_display_on else R.string.player_lyrics_display_off),
                                                onClick = { onTranslationChange(!translationEnabled) })
                                            "translation-language" -> CatalogSheetRow(
                                                stringResource(R.string.player_lyrics_translate_to),
                                                row.automationId("lyrics-menu-translation-language"), submenu = true,
                                                value = if (translationLanguageTag.isBlank()) stringResource(
                                                    R.string.player_lyrics_language_follow_app_format, languageName(appLanguageTag))
                                                    else languageName(translationLanguageTag),
                                                onClick = { openPicker(LyricsLanguagePicker.Translation, id) })
                                            "pronunciation" -> CatalogSheetRow(
                                                stringResource(R.string.player_lyrics_pronunciation),
                                                row.automationId("lyrics-menu-pronunciation"), selected = pronunciationEnabled,
                                                subtitle = stringResource(when {
                                                    pronunciationEnabled && enhancementsFailed -> R.string.player_lyrics_enhancement_load_failed
                                                    pronunciationEnabled && !enhancementsReady -> R.string.player_lyrics_enhancement_loading
                                                    pronunciationEnabled && !pronunciationAvailable -> R.string.player_lyrics_pronunciation_unavailable
                                                    else -> R.string.player_lyrics_pronunciation_hint
                                                }),
                                                value = stringResource(if (pronunciationEnabled) R.string.player_lyrics_display_on else R.string.player_lyrics_display_off),
                                                onClick = { onPronunciationChange(!pronunciationEnabled) })
                                            "pronunciation-language" -> CatalogSheetRow(
                                                stringResource(R.string.player_lyrics_pronunciation_script),
                                                row.automationId("lyrics-menu-pronunciation-language"), submenu = true,
                                                value = if (pronunciationLanguageTag == "en-US") stringResource(R.string.player_lyrics_pronunciation_latin)
                                                    else stringResource(R.string.player_lyrics_pronunciation_app_script_format, languageName(pronunciationLanguageTag)),
                                                onClick = { openPicker(LyricsLanguagePicker.Pronunciation, id) })
                                            "larger" -> CatalogSheetRow(
                                                stringResource(R.string.player_lyrics_larger_text),
                                                row.automationId("lyrics-larger-text"), submenu = true,
                                                value = stringResource(if (pronunciationLarger) R.string.player_lyrics_larger_pronunciation else R.string.player_lyrics_larger_lyrics),
                                                onClick = { openPicker(LyricsLanguagePicker.LargerText, id) })
                                        }
                                        LyricsLanguagePicker.Translation -> {
                                            val tag = TranslationLanguageTags[index]
                                            CatalogSheetRow(
                                                if (tag.isBlank()) stringResource(R.string.player_lyrics_language_follow_app_format, languageName(appLanguageTag))
                                                else languageName(tag),
                                                row.automationId("lyrics-translation-language-${tag.ifBlank { "app" }}"),
                                                selected = tag == translationLanguageTag,
                                                onClick = { onTranslationLanguageChange(tag); back() })
                                        }
                                        LyricsLanguagePicker.Pronunciation -> {
                                            val tag = pronunciationOptions[index]
                                            CatalogSheetRow(
                                                if (tag == "en-US") stringResource(R.string.player_lyrics_pronunciation_latin)
                                                else stringResource(R.string.player_lyrics_pronunciation_app_script_format, languageName(tag)),
                                                row.automationId("lyrics-pronunciation-language-$tag"),
                                                selected = tag == pronunciationLanguageTag,
                                                onClick = { onPronunciationLanguageChange(tag); back() })
                                        }
                                        LyricsLanguagePicker.LargerText -> {
                                            val larger = index == 1
                                            CatalogSheetRow(stringResource(if (larger) R.string.player_lyrics_larger_pronunciation else R.string.player_lyrics_larger_lyrics),
                                                row.automationId(if (larger) "lyrics-larger-pronunciation" else "lyrics-larger-lyrics"),
                                                selected = larger == pronunciationLarger,
                                                onClick = { onPronunciationLargerChange(larger); back() })
                                        }
                                    }
                                }
                            }
                            if (picker == null) {
                                CatalogSheetSeparator()
                                CatalogSheetRow(stringResource(R.string.player_lyrics_display_done),
                                    target("done").automationId("lyrics-menu-done").padding(horizontal = metrics.viewportPadding)
                                        .focusProperties {
                                            up = requesters.getValue(entryIds.last()); down = FocusRequester.Cancel
                                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                        }, icon = Icons.Rounded.Close, onClick = ::back)
                            }
                        }
                    }
                }
            }
        }
    }
}
