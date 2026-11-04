package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.model.Track

/** A Room is a full collection, not another metadata page with a long horizontal shelf. */
@Composable
internal fun CatalogRoomScreen(
    source: Track,
    browser: CatalogPageBrowser,
    active: Boolean,
    firstFocus: FocusRequester,
    menuFocus: FocusRequester,
    capabilities: CatalogPresentationCapabilities,
    onOpen: (Track, List<Track>) -> Unit,
    onSignIn: () -> Unit = {},
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val contentActive = active && LocalFocusTargetsEnabled.current
    val state by browser.state.collectAsState()
    val profile = state.profile
    val sections = remember(profile?.sections, capabilities) {
        profile?.sections.orEmpty().filter { section ->
            section.resourceTypes.isEmpty() || section.resourceTypes.any(capabilities::canOpenResourceType)
        }
    }
    var selectedSectionKey by rememberSaveable(source.resourceType, source.resourceId ?: source.id) {
        mutableStateOf<String?>(null)
    }
    val section = sections.firstOrNull { it.pageKey() == selectedSectionKey } ?: sections.firstOrNull()
    val sectionKey = section?.pageKey()
    // Keep the initial loading anchor and the first real collection in the same
    // slot. Other server sections keep their own scroll and selected item.
    val sectionSlot = if (section == null || section == sections.firstOrNull()) "first" else sectionKey.orEmpty()
    val page = sectionKey?.let(state.sections::get)
    val items = remember(page?.items, capabilities) { capabilities.filterTracks(page?.items.orEmpty()) }
    val title = profile?.name?.takeIf(String::isNotBlank) ?: source.title
    val description = remember(profile?.description) {
        profile?.description?.let {
            android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
        }?.takeIf(String::isNotBlank)
    }
    val sectionStates = rememberSaveableStateHolder()
    val tabRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val bodyFocus = firstFocus
    val entry = LocalContentFocusEntry.current
    val showSections = sections.size > 1
    val selectedTab = sectionKey?.takeIf { showSections }?.let {
        tabRequesters.getOrPut(it) { FocusRequester() }
    }
    var enterSection by remember { mutableStateOf<String?>(null) }
    val latestActive by rememberUpdatedState(contentActive)
    val latestSection by rememberUpdatedState(sectionKey)

    LaunchedEffect(contentActive, sectionKey, profile != null) {
        if (contentActive && section != null) browser.ensure(section)
        if (!contentActive) enterSection = null
    }
    LaunchedEffect(contentActive, sectionKey, enterSection) {
        val requested = enterSection ?: return@LaunchedEffect
        if (!contentActive || requested != sectionKey) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (!latestActive || latestSection != requested || enterSection != requested) return@LaunchedEffect
            if (runCatching { bodyFocus.requestFocus() }.getOrDefault(false)) {
                enterSection = null
                return@LaunchedEffect
            }
        }
        enterSection = null
    }
    val backdrop = LocalBrowseBackdropController.current
    LaunchedEffect(profile?.item?.artworkUrl) { backdrop?.select(profile?.item ?: source) }

    Column(Modifier.fillMaxSize().automationId("catalog-room-screen").onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) enterSection = null
        false
    }) {
        Text(title, color = Color.White, fontSize = if (classic) 25.sp else 29.sp, lineHeight = if (classic) 30.sp else 35.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                .automationId("catalog-room-title").semantics { heading() })
        if (description != null) Text(description, color = Color.White.copy(alpha = 0.68f),
            fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        if (showSections) FocusTrackingRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(sections, key = { _, value -> value.pageKey() }) { index, value ->
                val key = value.pageKey()
                val requester = tabRequesters.getOrPut(key) { FocusRequester() }
                val chosen = key == sectionKey
                ArtistTextAction(value.title?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.collection_section_number, index + 1),
                    { selectedSectionKey = key },
                    Modifier.background(if (chosen) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                        RoundedCornerShape(if (classic) 8.dp else 18.dp)).focusRequester(requester)
                        .automationId("catalog-room-section-$index").semantics { selected = chosen }
                        .onFocusChanged { if (it.isFocused) entry?.requester = requester }
                        .focusProperties {
                            if (index == 0) left = menuFocus
                            if (index == sections.lastIndex) right = FocusRequester.Cancel
                            up = menuFocus
                            down = bodyFocus
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                selectedSectionKey = key
                                enterSection = key
                                true
                            } else false
                        })
            }
        }
        sectionStates.SaveableStateProvider(sectionSlot) {
            CatalogCollectionBody(
                contentKey = "room:${source.resourceType}:${source.resourceId ?: source.id}:$sectionSlot",
                items = items,
                active = contentActive,
                loaded = profile != null && (section == null || page?.loaded == true),
                loading = state.loading || page?.loading == true || section != null && page == null,
                nextPage = page?.next,
                failed = state.failed || page?.failed == true,
                firstFocus = bodyFocus,
                menuFocus = menuFocus,
                onOpen = onOpen,
                onMore = { section?.let(browser::more) },
                onRetry = { if (state.failed || section == null) browser.retryProfile() else browser.retry(section) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                failureReason = if (state.failed) state.failureReason else page?.failureReason,
                onSignIn = onSignIn,
                topFocus = selectedTab,
            )
        }
    }
}
