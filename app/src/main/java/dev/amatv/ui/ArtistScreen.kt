package dev.amatv.ui

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.ui.classic.ClassicBrowseVisuals
import dev.amatv.R
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.model.ArtistSection
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Only the current visit is composed. Its lazy positions survive related-artist navigation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArtistScreen(
    visit: ArtistVisit,
    active: Boolean,
    firstFocus: FocusRequester,
    menuFocus: FocusRequester,
    capabilities: CatalogPresentationCapabilities,
    onRetry: () -> Unit,
    onEnsure: (ArtistSection) -> Unit,
    onMore: (ArtistSection) -> Unit,
    onRetrySection: (ArtistSection) -> Unit,
    onOpen: (Track, List<Track>) -> Unit,
    onSignIn: () -> Unit = {},
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val contentActive = active && LocalBrowseContentVisible.current && LocalFocusTargetsEnabled.current
    val list = rememberLazyListState()
    var contentHasFocus by remember { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val entry = LocalContentFocusEntry.current
    val profile = visit.profile
    val artist = profile?.catalogArtist ?: profile?.artist ?: visit.source
    val topSongs = visit.sections[ArtistSection.TopSongs]?.items.orEmpty()
    var biographyExpanded by rememberSaveable { mutableStateOf(false) }
    val biography = remember(profile?.biography) { profile?.biography?.let {
        android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
    }?.takeIf(String::isNotBlank) }
    val playFocus = remember { FocusRequester() }
    val biographyFocus = remember { FocusRequester() }
    val biographyReadingFocus = remember { FocusRequester() }
    var biographyReadingWanted by remember { mutableStateOf(false) }
    val moreFocus = remember { FocusRequester() }
    val profileRetryFocus = remember { FocusRequester() }
    val sectionEntries = remember { mutableMapOf<ArtistSection, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val cancelShelfIntents = remember { mutableMapOf<String, () -> Unit>() }
    var sectionFocusJob by remember { mutableStateOf<Job?>(null) }
    val sectionNavigation = remember(visit.key) {
        ArtistSectionNavigation(listOf(ArtistSectionNavigation.Profile) + ArtistSection.entries.map { it.name })
    }
    var lastProfileControl by rememberSaveable { mutableStateOf("more") }
    val latestActive by rememberUpdatedState(contentActive)
    val latestSelectedKey by rememberUpdatedState(selectedKey)
    var retainProfileRetry by remember { mutableStateOf(false) }
    var profileRetryFocusWanted by remember { mutableStateOf(false) }
    val canPlay = topSongs.isNotEmpty()
    val openActions = LocalCatalogActionMenu.current
    val showPlay = visit.loading || (profile != null && ArtistSection.TopSongs in profile.applicableSections &&
        (visit.sections[ArtistSection.TopSongs]?.loaded != true || canPlay))
    LaunchedEffect(showPlay, selectedKey, contentActive) {
        if (contentActive && !showPlay && selectedKey == "play" && !sectionNavigation.pending) {
            selectedKey = if (openActions != null) "more" else null
            withFrameNanos { }
            runCatching { if (openActions != null) moreFocus.requestFocus() else menuFocus.requestFocus() }
        }
    }
    val sections = profile?.applicableSections.orEmpty().filter { it.resourceTypes.any(capabilities::canOpenResourceType) }
    // Omit terminal-empty lazy items themselves: a zero-height item still
    // participates in spacedBy and leaves a gap for every absent section.
    // Unknown/loading/error pages and empty continuations retain their anchors.
    val visibleSections = sections.filter { section ->
        val page = visit.sections[section]
        page == null || !page.loaded || page.items.isNotEmpty() || page.next != null || page.failed || page.loading
    }
    val sectionByName = remember(sections) { sections.associateBy { it.name } }
    val latestVisibleSections by rememberUpdatedState(visibleSections)
    val latestHeaderCount by rememberUpdatedState(1 +
        (if (visit.loading && !retainProfileRetry) 1 else 0) +
        (if (visit.failed || retainProfileRetry) 1 else 0))
    val latestSections by rememberUpdatedState(visit.sections)
    val ensure by rememberUpdatedState(onEnsure)
    val more by rememberUpdatedState(onMore)
    val backdrop = LocalBrowseBackdropController.current
    val profileEntry = when {
        lastProfileControl == "play" && showPlay -> playFocus
        lastProfileControl == "biography" && biography != null && biography.length > 180 -> biographyFocus
        lastProfileControl == "biography-reading" && biographyExpanded -> biographyReadingFocus
        lastProfileControl == "profile-error" && (visit.failed || retainProfileRetry) -> profileRetryFocus
        openActions != null -> moreFocus
        showPlay -> playFocus
        biography != null && biography.length > 180 -> biographyFocus
        visit.failed || retainProfileRetry -> profileRetryFocus
        else -> null
    }
    val latestProfileEntry by rememberUpdatedState(profileEntry)
    fun selectedRoute(): String? = selectedKey?.substringBefore(':')?.takeIf { it in sectionByName }
        ?: selectedKey?.takeIf { it in setOf("play", "more", "biography", "biography-reading", "profile-error") }
            ?.let { ArtistSectionNavigation.Profile }
    fun updateSectionRoute() = sectionNavigation.updateVisible(
        listOf(ArtistSectionNavigation.Profile) + latestVisibleSections.map { it.name })
    SideEffect { updateSectionRoute() }
    val pageTop = rememberPageTopReveal(list) {
        if (!contentActive || !contentHasFocus) null
        else when {
            selectedRoute() == ArtistSectionNavigation.Profile -> if (selectedKey == "profile-error") latestHeaderCount - 1 else 0
            visibleSections.isNotEmpty() && selectedRoute() == visibleSections.first().name -> latestHeaderCount
            else -> null
        }
    }
    ContentUpExitReadiness(entry) {
        selectedRoute() == ArtistSectionNavigation.Profile && !sectionNavigation.pending &&
            sectionFocusJob?.isActive != true && !pageTop.isRevealing && !list.isScrollInProgress
    }
    val inheritedReveal = LocalBringIntoViewSpec.current
    val pageReveal = remember(pageTop, inheritedReveal) { pageTop.spec(inheritedReveal) }
    fun profileFocused(key: String) {
        selectedKey = key
        lastProfileControl = key
        sectionNavigation.focused(ArtistSectionNavigation.Profile)
        pageTop.request()
    }
    fun cancelSectionFocus() {
        sectionFocusJob?.cancel()
        sectionFocusJob = null
        sectionNavigation.cancel(selectedRoute())
    }
    fun requestSectionFocus(ticket: Long) {
        sectionFocusJob?.cancel()
        sectionFocusJob = scope.launch {
            // Empty-result disposal can run during a composition apply. Let
            // the replacement row attach before the first focus request.
            withFrameNanos { }
            while (latestActive && sectionNavigation.isCurrent(ticket) && sectionNavigation.pending) {
                updateSectionRoute()
                val next = sectionNavigation.nextStep() ?: return@launch
                fun requestPlaced(): Boolean = runCatching {
                    if (next == ArtistSectionNavigation.Profile) latestProfileEntry?.requestFocus() == true
                    else sectionEntries.getOrPut(ArtistSection.valueOf(next)) { FocusRequester() }.requestFocus()
                }.getOrDefault(false)
                var acquired = requestPlaced()
                if (!acquired) {
                    val sectionIndex = latestVisibleSections.indexOfFirst { it.name == next }
                    val lazyIndex = if (next == ArtistSectionNavigation.Profile) 0
                        else if (sectionIndex >= 0) sectionIndex + latestHeaderCount
                        else { sectionNavigation.cancel(selectedRoute()); return@launch }
                    coroutineScope {
                        val scroll = launch { list.animateScrollToItem(lazyIndex) }
                        do {
                            withFrameNanos { }
                            if (!latestActive || !sectionNavigation.isCurrent(ticket)) {
                                scroll.cancel()
                                return@coroutineScope
                            }
                            updateSectionRoute()
                            if (next != ArtistSectionNavigation.Profile && latestVisibleSections.none { it.name == next }) {
                                scroll.cancel()
                                return@coroutineScope
                            }
                            if (requestPlaced()) {
                                acquired = true
                                scroll.cancel()
                                return@coroutineScope
                            }
                        } while (scroll.isActive)
                    }
                }
                if (!latestActive || !sectionNavigation.isCurrent(ticket)) return@launch
                if (next != ArtistSectionNavigation.Profile && latestVisibleSections.none { it.name == next }) continue
                if (!acquired) {
                    repeat(3) { if (!acquired) { withFrameNanos { }; acquired = requestPlaced() } }
                }
                if (!acquired) { sectionNavigation.cancel(selectedRoute()); return@launch }
                sectionNavigation.focused(next)
                withFrameNanos { }
            }
        }
    }
    LaunchedEffect(artist.artworkUrl, artist.motionArtworkUrl) { backdrop?.select(artist) }
    LaunchedEffect(contentActive) {
        if (!contentActive) {
            biographyReadingWanted = false
            cancelShelfIntents.values.toList().forEach { it() }
            profileRetryFocusWanted = false
            cancelSectionFocus()
        }
    }
    LaunchedEffect(biographyExpanded, biographyReadingWanted, contentActive) {
        if (!biographyReadingWanted || !biographyExpanded || !contentActive) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (selectedKey != "biography") return@LaunchedEffect
            if (runCatching { biographyReadingFocus.requestFocus() }.getOrDefault(false)) {
                biographyReadingWanted = false
                return@LaunchedEffect
            }
        }
        biographyReadingWanted = false
    }
    DisposableEffect(visit.key) { onDispose { sectionFocusJob?.cancel() } }
    LaunchedEffect(visit.loading, visit.failed, retainProfileRetry, contentActive) {
        if (retainProfileRetry && !visit.loading) {
            retainProfileRetry = false
            if (!visit.failed && profileRetryFocusWanted && contentActive && !sectionNavigation.pending) {
                withFrameNanos { }
                runCatching { firstFocus.requestFocus() }
            }
            profileRetryFocusWanted = false
        }
    }
    LaunchedEffect(visit.key, profile, contentActive) {
        if (contentActive && profile != null && ArtistSection.TopSongs in sections) ensure(ArtistSection.TopSongs)
    }
    LaunchedEffect(visit.key, contentActive, sectionByName) {
        if (!contentActive || profile == null) return@LaunchedEffect
        // Lazy prefetch and focus-search can compose distant rows. Only actual
        // viewport items may start their requests; focusable status anchors let
        // a remote bring an unloaded next section into that viewport first.
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.mapNotNull { sectionByName[it.key] }
                .map { it to latestSections[it] }
        }.distinctUntilChanged().collect { visible ->
            visible.forEach { (section, page) ->
                if (page == null || (!page.loaded && !page.loading && !page.failed)) ensure(section)
                else if (page.loaded && page.items.isEmpty() && page.next != null && !page.loading && !page.failed) more(section)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pageReveal, LocalPageTopReveal provides pageTop) {
    Column(Modifier.fillMaxSize().automationId("artist-screen").onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        // A section reveal still leaves the previous card focused until its
        // successor is placed. Do not play/open that old card for a new intent.
        if (sectionNavigation.pending && key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU))
            return@onPreviewKeyEvent true
        if (key.action == KeyEvent.ACTION_DOWN && key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK)) {
            biographyReadingWanted = false
            cancelShelfIntents.values.toList().forEach { it() }
        }
        if (key.action != KeyEvent.ACTION_DOWN) false
        else if (latestActive && key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            val actual = selectedRoute()
            // Expanded text owns paging until its real beginning/end. Let its
            // child handler decide before the section navigation consumes Down.
            if (selectedKey == "biography-reading" || actual == null || (actual == ArtistSectionNavigation.Profile &&
                    key.keyCode == KeyEvent.KEYCODE_DPAD_UP && !sectionNavigation.pending)) false
            else {
                updateSectionRoute()
                val ticket = sectionNavigation.requestNext(actual, if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                requestSectionFocus(ticket)
                true
            }
        } else {
            if (key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) cancelSectionFocus()
            false
        }
    }) {
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f).automationId("artist-content").pageTopRevealViewport(pageTop)
            .onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "profile", contentType = "profile") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(25.dp), verticalAlignment = Alignment.CenterVertically) {
                    MotionArtwork(artist, Modifier.size(144.dp).coverShadow(false, 72.dp).clip(CircleShape).coverHairline(72.dp),
                        animate = rememberBrowseMotionReady(contentActive), cornerRadius = 72.dp, onlyWhileVisible = true)
                    Column(Modifier.weight(1f)
                        .automationId("artist-profile-${artist.resourceType}-${artist.resourceId ?: artist.id}"),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(artist.title, modifier = Modifier.automationId("artist-title"), color = Color.White, fontSize = if (classic) 28.sp else 31.sp, lineHeight = if (classic) 34.sp else 36.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        profile?.genreNames?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        profile?.origin?.let { Text(stringResource(R.string.catalog_artist_origin) + " · " + it,
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 17.sp) }
                        profile?.bornOrFormed?.let { Text(stringResource(if (profile.isGroup == true) R.string.catalog_artist_formed else R.string.catalog_artist_born) + " · " + it,
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 17.sp) }
                        if (biography != null) {
                            if (biographyExpanded) {
                                if (selectedKey == "biography-reading") SideEffect { entry?.requester = biographyReadingFocus }
                                ResourceReadingSurface("artist-biography", 0, list, contentActive,
                                    biographyReadingFocus, menuFocus, aboveFocus = menuFocus,
                                    onFocused = { profileFocused("biography-reading") }) {
                                    Text(biography, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            } else Text(biography, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, lineHeight = 19.sp,
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                            if (biography.length > 180) {
                                if (selectedKey == "biography") SideEffect { entry?.requester = biographyFocus }
                                ArtistTextAction(
                                    stringResource(if (biographyExpanded) R.string.artist_show_less else R.string.artist_read_more),
                                    {
                                        selectedKey = "biography"; entry?.requester = biographyFocus
                                        biographyExpanded = !biographyExpanded
                                        biographyReadingWanted = biographyExpanded
                                    }, Modifier.focusRequester(biographyFocus).automationId("artist-biography-expand")
                                        .onFocusChanged { if (it.isFocused) profileFocused("biography") }
                                        .focusProperties { left = menuFocus; up = if (biographyExpanded) biographyReadingFocus else menuFocus })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (showPlay) {
                                val playDescription = stringResource(R.string.artist_play_top_songs)
                                if (selectedKey == "play") SideEffect { entry?.requester = playFocus }
                                CollectionAction(stringResource(R.string.browse_play), Icons.Rounded.PlayArrow,
                                    { if (canPlay) onOpen(topSongs.first(), topSongs) },
                                    enabled = canPlay, retainFocus = true, primary = true, cornerRadius = 21.dp,
                                    modifier = Modifier.width(132.dp).height(42.dp).automationId("artist-play")
                                        .focusRequester(playFocus).focusRequester(firstFocus)
                                        .semantics { contentDescription = playDescription }
                                        .onFocusChanged { if (it.isFocused) profileFocused("play") }
                                        .focusProperties {
                                            left = menuFocus; up = menuFocus
                                            right = if (openActions != null) moreFocus else FocusRequester.Cancel
                                        })
                            }
                            if (openActions != null) {
                                val actionArtist = profile?.artist ?: visit.source
                                if (selectedKey == "more") SideEffect { entry?.requester = moreFocus }
                                CollectionAction(stringResource(R.string.catalog_more_actions), Icons.Rounded.MoreHoriz, {
                                    selectedKey = "more"; entry?.requester = moreFocus
                                    openActions(actionArtist)
                                }, iconOnly = true, cornerRadius = 21.dp,
                                    modifier = Modifier.size(42.dp).focusRequester(moreFocus)
                                        .then(if (!showPlay) Modifier.focusRequester(firstFocus) else Modifier)
                                        .automationId("artist-more").catalogMenuKey(actionArtist)
                                        .onFocusChanged { if (it.isFocused) profileFocused("more") }
                                        .focusProperties {
                                            left = if (showPlay) playFocus else menuFocus
                                            up = menuFocus; right = FocusRequester.Cancel
                                        })
                            }
                        }
                    }
                }
            }
            if (visit.loading && !retainProfileRetry) item(key = "profile-loading", contentType = "status") {
                Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) { AppleLoadingIndicator() }
            }
            if (visit.failed || retainProfileRetry) item(key = "profile-error", contentType = "status") {
                if (selectedKey == "profile-error") SideEffect { entry?.requester = profileRetryFocus }
                ArtistTextAction(if (visit.loading || !visit.failed) stringResource(R.string.browse_tracks_loading) else catalogRecoveryLabel(visit.failureReason),
                    { performCatalogRecovery(visit.failureReason,
                        { retainProfileRetry = true; profileRetryFocusWanted = true; onRetry() }, onSignIn) },
                    Modifier.padding(horizontal = 14.dp).focusRequester(profileRetryFocus)
                        .then(if (openActions == null && !showPlay) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) profileFocused("profile-error") }
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) profileRetryFocusWanted = false
                            false
                        }.focusProperties { left = menuFocus; up = menuFocus; right = FocusRequester.Cancel; down = FocusRequester.Cancel },
                    enabled = !visit.loading, retainFocus = true)
            }
            items(visibleSections, key = { it.name }, contentType = { "shelf" }) { section ->
                val page = visit.sections[section] ?: ArtistSectionState()
                    CatalogShelf(section.name, stringResource(section.label()), "artist", page, contentActive, menuFocus,
                        sectionEntries.getOrPut(section) { FocusRequester() }, selectedKey,
                        onSelected = { selectedKey = it; sectionNavigation.focused(section.name); if (section == visibleSections.firstOrNull()) pageTop.request() }, onMore = { onMore(section) },
                        onRetry = { onRetrySection(section) },
                        onRegisterCancelPending = { cancel ->
                            if (cancel == null) cancelShelfIntents.remove(section.name) else cancelShelfIntents[section.name] = cancel
                        },
                        onEmptyFocus = {
                            val removedKey = latestSelectedKey
                            val current = latestSections[section]
                            val terminalEmpty = current?.loaded == true && !current.loading && !current.failed &&
                                current.items.isEmpty() && current.next == null
                            // Lazy disposal alone is not an empty result and must
                            // never pull focus back to the hero/navigation.
                            if (latestActive && terminalEmpty && removedKey?.startsWith("${section.name}:") == true) {
                                updateSectionRoute()
                                if (!sectionNavigation.pending) {
                                    requestSectionFocus(sectionNavigation.repairRemoved(section.name))
                                }
                            }
                        }, onOpen = onOpen, onSignIn = onSignIn,
                        prefetchParent = list, prefetchParentKey = section.name)
            }
        }
    }
    }
}

@Composable
internal fun CatalogShelf(
    sectionKey: String, title: String, automationPrefix: String, page: ArtistSectionState, active: Boolean,
    menuFocus: FocusRequester, entryFocus: FocusRequester,
    selectedKey: String?, onSelected: (String) -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit, onEmptyFocus: () -> Unit,
    onOpen: (Track, List<Track>) -> Unit,
    onSignIn: () -> Unit = {},
    prefetchParent: LazyListState? = null,
    prefetchParentKey: Any? = null,
    onRegisterCancelPending: ((() -> Unit)?) -> Unit = {},
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val cardWidth = if (classic) ClassicBrowseVisuals.CardWidth else BrowseTokens.CardWidth
    val row = rememberLazyListState()
    rememberArtworkPrefetch(page.items, row, active, coverSize = cardWidth,
        parentViewport = prefetchParent, parentItemKey = prefetchParentKey)
    val entry = LocalContentFocusEntry.current
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var retainedEntryKey by rememberSaveable(sectionKey) { mutableStateOf<String?>(null) }
    var wantsStatusContent by remember { mutableStateOf(false) }
    var appendIndex by remember { mutableStateOf<Int?>(null) }
    val cancelRegistration by rememberUpdatedState(onRegisterCancelPending)
    val cancelIntent by rememberUpdatedState<() -> Unit>({ appendIndex = null; wantsStatusContent = false })
    DisposableEffect(Unit) {
        cancelRegistration { cancelIntent() }
        onDispose { cancelRegistration(null) }
    }
    val footerFocus = remember { FocusRequester() }
    val statusKey = "${sectionKey}:status"
    val hasFooter = page.next != null || page.failed || page.loading
    val retainedEntry = retainedEntryKey?.takeIf { key ->
        page.items.any { artistItemKey(sectionKey, it) == key } || (key == "${sectionKey}:more" && hasFooter)
    }
    val emptyFallback by rememberUpdatedState(onEmptyFocus)
    DisposableEffect(Unit) {
        onDispose { emptyFallback() }
    }
    LaunchedEffect(active, page.failed) {
        if (!active || page.failed) appendIndex = null
        if (!active) wantsStatusContent = false
    }
    LaunchedEffect(page.items.size, page.loading, page.next, appendIndex, wantsStatusContent, active) {
        if (!active || page.loading || page.failed) return@LaunchedEffect
        val requested = appendIndex
        val target = when {
            wantsStatusContent && page.items.isNotEmpty() -> 0
            requested != null && page.items.size > requested -> requested
            requested != null && page.next == null && page.items.isNotEmpty() -> page.items.lastIndex
            else -> return@LaunchedEffect
        }
        row.scrollToItem(target)
        val key = artistItemKey(sectionKey, page.items[target])
        var granted = false
        repeat(3) {
            if (!granted) {
                withFrameNanos { }
                granted = requesters[key]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
            }
        }
        if (!granted) emptyFallback()
        appendIndex = null
        wantsStatusContent = false
    }
    val cancelPending = Modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) {
            appendIndex = null
            wantsStatusContent = false
        }
        false
    }
    Column(Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus) { appendIndex = null; wantsStatusContent = false } }.focusGroup()
        .automationId("$automationPrefix-section-${sectionKey.lowercase()}")
        .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = page.items.size) }
        .then(cancelPending)) {
        Text(title, color = Color.White.copy(alpha = 0.94f), fontSize = if (classic) 21.sp else 18.sp, lineHeight = if (classic) 27.sp else BrowseTokens.HeadingLineHeight,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, bottom = 5.dp))
        if (page.items.isEmpty()) {
            if (selectedKey == statusKey) SideEffect { entry?.requester = entryFocus }
            Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 14.dp), Alignment.CenterStart) {
                ArtistTextAction(if (page.loading || !page.failed) stringResource(R.string.browse_tracks_loading) else catalogRecoveryLabel(page.failureReason),
                    { if (page.failed && !page.loading) performCatalogRecovery(page.failureReason,
                        { wantsStatusContent = true; onRetry() }, onSignIn) },
                    Modifier.width(cardWidth + 42.dp).focusRequester(entryFocus)
                        .onFocusChanged {
                            if (it.isFocused) { wantsStatusContent = true; onSelected(statusKey) }
                        }.focusProperties { left = menuFocus; right = FocusRequester.Cancel },
                    enabled = page.failed && !page.loading, retainFocus = true)
            }
        } else FocusTrackingRow(state = row, modifier = Modifier.fillMaxWidth().focusGroup(),
            focusVisualOutset = CardVisuals.focusOutset(false),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = BrowseTokens.RowVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(BrowseTokens.ColumnGap)) {
            itemsIndexed(page.items, key = { _, item -> artistItemKey(sectionKey, item) }) { index, item ->
                val key = artistItemKey(sectionKey, item)
                val requester = requesters.getOrPut(key) { FocusRequester() }
                if (key == selectedKey) SideEffect { entry?.requester = requester }
                val isEntry = key == retainedEntry || (retainedEntry == null && index == 0)
                ArtistCover(item, Modifier.automationId("$automationPrefix-${sectionKey.lowercase()}-$index-${item.catalogKind.name.lowercase()}")
                    .focusRequester(requester).then(if (isEntry) Modifier.focusRequester(entryFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) { retainedEntryKey = key; onSelected(key) } }
                    .focusProperties { if (index == 0) left = menuFocus; if (index == page.items.lastIndex && page.next == null) right = FocusRequester.Cancel },
                    onClick = { onOpen(item, page.items) })
            }
            if (hasFooter) item(key = "continuation") {
                if (selectedKey == "${sectionKey}:more") SideEffect { entry?.requester = footerFocus }
                ArtistTextAction(when {
                    page.loading -> stringResource(R.string.browse_tracks_loading)
                    page.failed -> catalogRecoveryLabel(page.failureReason, continuation = true)
                    else -> stringResource(R.string.artist_more)
                }, { if (!page.loading) {
                    if (page.failed) performCatalogRecovery(page.failureReason,
                        { appendIndex = page.items.size; onRetry() }, onSignIn)
                    else { appendIndex = page.items.size; onMore() }
                } },
                    Modifier.width(cardWidth).height(cardWidth).focusRequester(footerFocus)
                        .then(if (retainedEntry == "${sectionKey}:more") Modifier.focusRequester(entryFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) { retainedEntryKey = "${sectionKey}:more"; onSelected("${sectionKey}:more") } }
                        .focusProperties { right = FocusRequester.Cancel }, enabled = !page.loading, retainFocus = true)
            }
        }
    }
}

@Composable
internal fun ArtistCover(item: Track, modifier: Modifier, onClick: () -> Unit) {
    val artist = item.catalogKind == CatalogKind.Artist
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val cardWidth = if (classic) ClassicBrowseVisuals.CardWidth else BrowseTokens.CardWidth
    val cardHeight = if (classic) ClassicBrowseVisuals.CardHeight else BrowseTokens.CardHeight
    val openActions = catalogMenuFor(item)
    val radius = if (artist) cardWidth / 2 else if (classic) ClassicBrowseVisuals.CardRadius else BrowseTokens.CardRadius
    FocusSurface(onClick, modifier.catalogMenuKey(item).width(cardWidth).height(cardHeight).pageTopRevealTarget(),
        cornerRadius = if (classic) ClassicBrowseVisuals.CardRadius else 18.dp,
        focusedScale = if (classic) ClassicBrowseVisuals.CardFocusScale else BrowseTokens.CardFocusScale,
        focusedLift = if (classic) ClassicBrowseVisuals.CardLift else 0.dp, background = Color.Transparent, focusedBackground = Color.Transparent,
        onLongClick = openActions?.let { { it(item) } }) { focused ->
        Column(horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start) {
            MotionArtwork(item, Modifier.size(cardWidth).coverShadow(focused, radius).coverHairline(radius),
                animate = rememberBrowseMotionReady(focused), cornerRadius = radius)
            TrackTitle(item, color = Color.White.copy(alpha = 0.95f), fontSize = if (classic) ClassicBrowseVisuals.CardTitleSize else 12.sp, lineHeight = if (classic) 20.sp else 16.sp,
                fontWeight = if (classic) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (classic) 9.dp else 7.dp))
            if (!artist) Text(item.artist, color = Color.White.copy(alpha = 0.55f),
                fontSize = if (classic) ClassicBrowseVisuals.CardSubtitleSize else 11.sp, lineHeight = if (classic) 17.sp else 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (classic) 1.dp else 2.dp))
        }
    }
}

@Composable
internal fun ArtistTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, retainFocus: Boolean = false) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    FocusSurface({ if (enabled) onClick() }, modifier.heightIn(min = 36.dp).pageTopRevealTarget(), enabled = enabled || retainFocus,
        interactionFeedbackEnabled = enabled, cornerRadius = if (classic) 8.dp else 18.dp, focusedScale = 1.025f,
        background = Color.White.copy(alpha = if (enabled) 0.08f else 0.03f),
        focusedBackground = Color.White.copy(alpha = if (enabled) 0.85f else if (retainFocus) 0.10f else 0.03f),
        padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, color = if (enabled) focusSurfaceContentColor(Color.White.copy(alpha = 0.9f), Color(0xFF1B1B1F)) else Color.White.copy(alpha = 0.4f),
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
    }
}

private fun artistItemKey(section: String, track: Track) = "$section:${track.resourceType}:${track.resourceId ?: track.id}"

@StringRes
private fun ArtistSection.label(): Int = when (this) {
    ArtistSection.LibraryAlbums -> R.string.artist_library_albums
    ArtistSection.TopSongs -> R.string.artist_top_songs
    ArtistSection.LatestRelease -> R.string.artist_latest_release
    ArtistSection.FeaturedAlbums -> R.string.artist_featured_albums
    ArtistSection.FullAlbums -> R.string.artist_full_albums
    ArtistSection.Singles -> R.string.artist_singles
    ArtistSection.LiveAlbums -> R.string.artist_live_albums
    ArtistSection.CompilationAlbums -> R.string.artist_compilation_albums
    ArtistSection.AppearsOnAlbums -> R.string.artist_appears_on
    ArtistSection.FeaturedPlaylists -> R.string.artist_featured_playlists
    ArtistSection.Playlists -> R.string.artist_playlists
    ArtistSection.SimilarArtists -> R.string.artist_similar_artists
    ArtistSection.FeaturedMusicVideos -> R.string.artist_featured_videos
    ArtistSection.TopMusicVideos -> R.string.artist_top_videos
    ArtistSection.MusicVideos -> R.string.artist_videos
    ArtistSection.Albums -> R.string.artist_albums
    ArtistSection.Station -> R.string.artist_station
}
