package dev.amatv.ui

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** One renderer for actual curator, genre, label, folder and collection metadata resources. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CatalogResourceScreen(
    source: Track, browser: CatalogPageBrowser, active: Boolean,
    firstFocus: FocusRequester, menuFocus: FocusRequester,
    capabilities: CatalogPresentationCapabilities,
    onOpen: (Track, List<Track>) -> Unit, onOpenLink: (CatalogResourceLink) -> Unit,
    onSignIn: () -> Unit = {},
) {
    if (source.resourceType == "public-rooms") {
        Box(Modifier.fillMaxSize().automationId("catalog-resource-screen")) {
            CatalogRoomScreen(source, browser, active, firstFocus, menuFocus, capabilities, onOpen, onSignIn)
        }
        return
    }
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val contentActive = active && LocalFocusTargetsEnabled.current
    val state by browser.state.collectAsState()
    val profile = state.profile
    val openActions = LocalCatalogActionMenu.current
    val folderItem = (profile?.item ?: source).takeIf { it.resourceType == "library-playlist-folders" && CatalogResourceRef.fromTrack(it) != null }
    val hasFolderActions = folderItem != null && openActions != null
    val folderActionsFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var contentHasFocus by remember { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var statusWanted by remember { mutableStateOf(false) }
    var emptySectionFocusWanted by remember { mutableStateOf<String?>(null) }
    val latestActive by rememberUpdatedState(contentActive)
    val latestSelectedKey by rememberUpdatedState(selectedKey)
    val latestSections by rememberUpdatedState(state.sections)
    val entry = LocalContentFocusEntry.current
    val entries = remember { mutableMapOf<String, FocusRequester>() }
    val sections = profile?.sections.orEmpty().filter { it.resourceTypes.any(capabilities::canOpenResourceType) }
    // Raw relationship ids/permission flags remain in the profile for routing
    // and actions; they are not music information to show in the product.
    val links = profile?.links.orEmpty().filter {
        capabilities.canOpenResourceType(it.ref.type) &&
            !it.title.isNullOrBlank() && it.title != it.ref.id && it.title != it.ref.key
    }
    val fields = profile?.metadata.orEmpty().filter {
        it.format != CatalogMetadataFormat.Identifier && (it.key in ResourceDetailsMetadataKeys ||
            it.key.startsWith("credits:") && !it.title.isNullOrBlank())
    }
    val visibleKeys by remember(list) { derivedStateOf { list.layoutInfo.visibleItemsInfo.map { it.key }.toSet() } }
    LaunchedEffect(contentActive, profile, visibleKeys, state.sections) {
        if (contentActive && profile != null) sections.filter { it.pageKey() in visibleKeys }.forEach { section ->
            val page = state.sections[section.pageKey()]
            if (page == null || (!page.loaded && !page.loading && !page.failed)) browser.ensure(section)
            else if (page.loaded && page.items.isEmpty() && page.next != null && !page.loading && !page.failed) browser.more(section)
        }
    }
    val description = remember(profile?.description) { profile?.description?.let {
        android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
    }?.takeIf(String::isNotBlank) }
    val backdrop = LocalBrowseBackdropController.current
    LaunchedEffect(profile?.item?.artworkUrl) { backdrop?.select(profile?.item ?: source) }
    LaunchedEffect(contentActive) { if (!contentActive) { statusWanted = false; emptySectionFocusWanted = null } }
    val firstSection = sections.firstOrNull { section ->
        val page = state.sections[section.pageKey()]
        page == null || !page.loaded || page.items.isNotEmpty() || page.next != null || page.loading || page.failed
    }
    val firstBodyKey = when {
        description != null -> "description"
        fields.isNotEmpty() -> "metadata:${fields.first().key}"
        links.isNotEmpty() -> "link:${links.first().ref.key}"
        state.loading || state.failed -> "profile-status"
        firstSection != null -> firstSection.pageKey()
        else -> null
    }
    LaunchedEffect(state.loading, state.failed, contentActive, firstBodyKey) {
        if (contentActive && !state.loading && !state.failed && firstBodyKey == null && !hasFolderActions) {
            statusWanted = false
            runCatching { menuFocus.requestFocus() }
            return@LaunchedEffect
        }
        if (contentActive && !state.loading && !state.failed && statusWanted) {
            list.scrollToItem(0)
            repeat(3) {
                withFrameNanos { }
                if (!statusWanted) return@LaunchedEffect
                val target = if (firstBodyKey == null && !hasFolderActions) menuFocus else firstFocus
                if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                    statusWanted = false
                    return@LaunchedEffect
                }
            }
        }
    }
    LaunchedEffect(emptySectionFocusWanted, contentActive, selectedKey, firstBodyKey) {
        val key = emptySectionFocusWanted ?: return@LaunchedEffect
        if (!contentActive || selectedKey !in setOf("$key:status", "$key:more")) {
            emptySectionFocusWanted = null
            return@LaunchedEffect
        }
        withFrameNanos { }
        runCatching { (if (firstBodyKey == null && !hasFolderActions) menuFocus else firstFocus).requestFocus() }
        emptySectionFocusWanted = null
    }
    val pageTop = rememberPageTopReveal(list) {
        if (!contentActive || !contentHasFocus) null
        else when {
            selectedKey == "actions" -> 0
            firstBodyKey != null && (selectedKey == firstBodyKey || selectedKey?.startsWith("$firstBodyKey:") == true) -> 1
            else -> null
        }
    }
    ContentUpExitReadiness(entry) {
        (selectedKey == "actions" || firstBodyKey != null &&
            (selectedKey == firstBodyKey || selectedKey?.startsWith("$firstBodyKey:") == true)) &&
            !pageTop.isRevealing && !list.isScrollInProgress
    }
    val guard = with(LocalDensity.current) { 16.dp.toPx() }
    val reveal = remember(guard) { object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            if (size <= containerSize - guard * 2) return minimumCardScroll(offset - guard, size + guard * 2, containerSize)
            // An oversized text block cannot fit at once. Reveal its near edge
            // on entry, then allow paging inside it without focus fighting back.
            return when {
                offset > guard -> offset - guard
                offset + size < containerSize - guard -> offset + size - containerSize + guard
                else -> 0f
            }
        }
    } }
    val pageReveal = remember(pageTop, reveal) { pageTop.spec(reveal) }
    if (selectedKey == "actions") SideEffect { entry?.requester = folderActionsFocus }
    Column(Modifier.fillMaxSize().automationId("catalog-resource-screen")) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageReveal, LocalPageTopReveal provides pageTop) {
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f).automationId("catalog-resource-content").pageTopRevealViewport(pageTop)
                    .onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "profile", contentType = "profile") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).semantics { heading() }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!(profile?.artworkUrl ?: source.artworkUrl).isNullOrBlank()) MotionArtwork(profile?.item ?: source,
                                Modifier.size(if (classic) 168.dp else 150.dp).coverShadow(false, 10.dp).coverHairline(10.dp),
                                animate = rememberBrowseMotionReady(contentActive), cornerRadius = 10.dp, onlyWhileVisible = true)
                            Text(profile?.name ?: source.title, color = Color.White, fontSize = if (classic) 25.sp else 29.sp, lineHeight = if (classic) 30.sp else 35.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            if (folderItem != null && openActions != null) {
                                FocusSurface({ selectedKey = "actions"; entry?.requester = folderActionsFocus; openActions(folderItem) }, Modifier.size(34.dp).focusRequester(folderActionsFocus).pageTopRevealTarget()
                                        .then(if (firstBodyKey == null) Modifier.focusRequester(firstFocus) else Modifier)
                                    .automationId("catalog-folder-actions").catalogMenuKey(folderItem)
                                    .onFocusChanged { if (it.isFocused) { selectedKey = "actions"; entry?.requester = folderActionsFocus; pageTop.request() } }
                                    .focusProperties { left = menuFocus; up = menuFocus; right = FocusRequester.Cancel }, cornerRadius = if (classic) 8.dp else 17.dp, focusedScale = 1.04f,
                                    background = Color.White.copy(alpha = 0.06f), focusedBackground = Color.White.copy(alpha = 0.17f)) {
                                    Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.catalog_more_actions),
                                        Modifier.size(18.dp).align(Alignment.Center), Color.White)
                                }
                            }
                        }
                    }
                }
                if (description != null) item(key = "description", contentType = "reading") {
                    val requester = if (firstBodyKey == "description") firstFocus else entries.getOrPut("description") { FocusRequester() }
                    if (selectedKey == "description") SideEffect { entry?.requester = requester }
                    ResourceReadingSurface("description", 1, list, contentActive, requester, menuFocus,
                        aboveFocus = if (firstBodyKey == "description") menuFocus else null,
                        onFocused = { selectedKey = "description"; entry?.requester = requester; pageTop.request() }) {
                        Text(description, color = Color.White.copy(alpha = 0.76f), fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
                itemsIndexed(fields, key = { _, field -> "metadata:${field.key}" }, contentType = { _, _ -> "reading" }) { index, field ->
                    val key = "metadata:${field.key}"
                    val requester = if (key == firstBodyKey) firstFocus else entries.getOrPut(key) { FocusRequester() }
                    if (selectedKey == key) SideEffect { entry?.requester = requester }
                    ResourceReadingSurface(key, 1 + (if (description != null) 1 else 0) + index, list, contentActive, requester, menuFocus,
                        aboveFocus = if (key == firstBodyKey) menuFocus else null,
                        onFocused = { selectedKey = key; entry?.requester = requester; if (key == firstBodyKey) pageTop.request() }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text(field.title ?: metadataLabel(field.key), color = Color.White.copy(alpha = 0.46f), fontSize = 13.sp,
                                lineHeight = 20.sp, modifier = Modifier.width(155.dp))
                            Text(metadataValue(field), color = Color.White.copy(alpha = 0.86f), fontSize = 14.sp,
                                lineHeight = 21.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                items(links, key = { "link:${it.ref.key}" }, contentType = { "link" }) { link ->
                    val key = "link:${link.ref.key}"
                    val requester = if (key == firstBodyKey) firstFocus else entries.getOrPut(key) { FocusRequester() }
                    if (selectedKey == key) SideEffect { entry?.requester = requester }
                    ArtistTextAction(link.title.orEmpty(), { onOpenLink(link) },
                        Modifier.padding(horizontal = 14.dp).focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) { selectedKey = key; if (key == firstBodyKey) pageTop.request() } }.focusProperties {
                                left = menuFocus; if (key == firstBodyKey) up = menuFocus
                            })
                }
                if (state.loading || state.failed) item(key = "profile-status", contentType = "status") {
                    val requester = if (firstBodyKey == "profile-status") firstFocus else entries.getOrPut("profile-status") { FocusRequester() }
                    if (selectedKey == "profile-status") SideEffect { entry?.requester = requester }
                    ArtistTextAction(if (state.loading) stringResource(R.string.browse_tracks_loading) else catalogRecoveryLabel(state.failureReason),
                        { performCatalogRecovery(state.failureReason, { statusWanted = true; browser.retryProfile() }, onSignIn) },
                        Modifier.padding(horizontal = 14.dp).focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) { selectedKey = "profile-status"; statusWanted = true; pageTop.request() } }
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) statusWanted = false
                                false
                            }.focusProperties { left = menuFocus; right = FocusRequester.Cancel },
                        enabled = !state.loading, retainFocus = true)
                }
                items(sections, key = { it.pageKey() }, contentType = { "shelf" }) { section ->
                    val key = section.pageKey()
                    val page = state.sections[key] ?: ArtistSectionState()
                    if (!page.loaded || page.items.isNotEmpty() || page.next != null || page.loading || page.failed) CatalogShelf(
                        key, section.title ?: metadataLabel(section.id), "resource", page, contentActive, menuFocus,
                        (if (key == firstBodyKey) firstFocus else entries.getOrPut(key) { FocusRequester() }), selectedKey, onSelected = { selectedKey = it; if (key == firstBodyKey) pageTop.request() },
                        onMore = { browser.more(section) }, onRetry = { browser.retry(section) },
                        onEmptyFocus = {
                            // Lazy virtualization also disposes a shelf. Only a
                            // terminal empty response needs a replacement owner.
                            val settled = latestSections[key]
                            if (latestActive && settled?.loaded == true && !settled.loading && !settled.failed &&
                                settled.items.isEmpty() && settled.next == null &&
                                (latestSelectedKey == "$key:status" || latestSelectedKey == "$key:more")) emptySectionFocusWanted = key
                        }, onOpen = onOpen, onSignIn = onSignIn,
                        prefetchParent = list, prefetchParentKey = key)
                }
            }
        }
    }
}

/** Deliberate product copy, rather than a dump of every returned API attribute. */
private val ResourceDetailsMetadataKeys = setOf(
    "artistName", "albumName", "genreNames", "releaseDate", "dateAdded", "lastModifiedDate",
    "copyright", "recordLabel", "trackCount", "songCount", "contentRating", "audioVariants",
    "composerName", "workName", "movementName", "movementNumber", "movementCount",
    "discNumber", "trackNumber", "durationInMillis", "attribution", "playlistType",
    "episodeNumber", "stationProviderName", "showHostName", "chartLabel",
)

/** Text is a focus target for reading, never a button with an empty click action. */
@Composable
internal fun ResourceReadingSurface(
    key: String, listIndex: Int, list: LazyListState, active: Boolean,
    requester: FocusRequester, menuFocus: FocusRequester, aboveFocus: FocusRequester? = null,
    onFocused: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    var focused by remember { mutableStateOf(false) }
    val focusFrameKey = remember { Any() }
    val targetsEnabled = active && LocalFocusTargetsEnabled.current
    val scope = rememberCoroutineScope()
    val scrollJob = remember { arrayOfNulls<Job>(1) }
    val margin = with(LocalDensity.current) { 16.dp.toPx() }
    DisposableEffect(targetsEnabled) { onDispose { scrollJob[0]?.cancel() } }
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp).automationId("catalog-reading-$key").pageTopRevealTarget()
        .focusRequester(requester).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused() else scrollJob[0]?.cancel()
        }.focusProperties {
            left = menuFocus; right = FocusRequester.Cancel
            if (aboveFocus != null) up = aboveFocus
        }.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
            else {
                val direction = when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> 0
                }
                if (direction == 0) {
                    scrollJob[0]?.cancel()
                    false
                } else {
                    val layout = list.layoutInfo
                    val row = layout.visibleItemsInfo.firstOrNull { it.index == listIndex }
                    val amount = if (row == null) 0f else readingPageScrollAmount(
                        row.offset.toFloat(), row.size.toFloat(), layout.viewportStartOffset.toFloat(),
                        layout.viewportEndOffset.toFloat(), margin, direction,
                        canScrollBackward = list.canScrollBackward, canScrollForward = list.canScrollForward)
                    if (abs(amount) < 1f) false else {
                        scrollJob[0]?.cancel()
                        scrollJob[0] = scope.launch { list.animateScrollBy(amount) }
                        true
                    }
                }
            }
        }.then(if (classic) Modifier.focusFrameTarget(focused && targetsEnabled, focusFrameKey, 10.dp) else Modifier)
        .background(if (focused) Color.White.copy(alpha = 0.055f) else Color.Transparent, RoundedCornerShape(10.dp))
        .semantics(mergeDescendants = true) {}.focusable(enabled = targetsEnabled).padding(horizontal = 12.dp, vertical = 10.dp),
        content = content)
}

@Composable
internal fun metadataLabel(key: String): String = metadataLabelResource(key)?.let { stringResource(it) } ?: humanizeMetadata(key)

@StringRes
private fun metadataLabelResource(key: String): Int? = when (key) {
    "artistName", "artists", "library-artists" -> R.string.resource_artist
    "albumName", "albums", "library-albums" -> R.string.resource_album
    "genreNames", "genres", "station-genres" -> R.string.resource_genre
    "releaseDate" -> R.string.resource_release_date
    "dateAdded" -> R.string.resource_date_added
    "lastModifiedDate" -> R.string.resource_date_modified
    "copyright" -> R.string.resource_copyright
    "recordLabel", "record-labels" -> R.string.resource_record_label
    "trackCount", "songCount" -> R.string.resource_track_count
    "contentRating" -> R.string.resource_content_rating
    "audioVariants" -> R.string.resource_audio_variants
    "composerName", "composers" -> R.string.resource_composer
    "durationInMillis" -> R.string.resource_duration
    "discNumber" -> R.string.resource_disc_number
    "trackNumber" -> R.string.resource_track_number
    "playlists", "library-playlists" -> R.string.resource_playlists
    "children" -> R.string.resource_contents
    "latest-releases" -> R.string.resource_latest_releases
    "top-releases" -> R.string.resource_top_releases
    "top-songs" -> R.string.resource_popular_songs
    "charts-songs" -> R.string.resource_chart_songs
    "charts-albums" -> R.string.resource_chart_albums
    "charts-playlists" -> R.string.resource_chart_playlists
    "charts-music-videos" -> R.string.resource_chart_videos
    "featured-albums" -> R.string.resource_featured_albums
    "appears-on" -> R.string.resource_appears_on
    "other-versions" -> R.string.resource_other_versions
    "curators", "apple-curators" -> R.string.resource_curator
    "workName" -> R.string.resource_work
    "movementName" -> R.string.resource_movement
    "movementNumber" -> R.string.resource_movement_number
    "movementCount" -> R.string.resource_movement_count
    "attribution" -> R.string.resource_attribution
    "playlistType" -> R.string.resource_playlist_type
    "trackTypes" -> R.string.resource_track_types
    "isChart" -> R.string.resource_is_chart
    "isSingle" -> R.string.resource_is_single
    "isCompilation" -> R.string.resource_is_compilation
    "isComplete" -> R.string.resource_is_complete
    "isAppleDigitalMaster" -> R.string.resource_digital_master
    "isMasteredForItunes" -> R.string.resource_mastered_itunes
    "hasLyrics" -> R.string.resource_has_lyrics
    "has4K" -> R.string.resource_has_4k
    "hasHDR" -> R.string.resource_has_hdr
    "videoSubType" -> R.string.resource_video_type
    "episodeNumber" -> R.string.resource_episode_number
    "stationProviderName" -> R.string.resource_station_provider
    "mediaKind" -> R.string.resource_media_kind
    "isLive" -> R.string.resource_is_live
    "kind" -> R.string.resource_kind
    "shortName" -> R.string.resource_short_name
    "showHostName" -> R.string.resource_host
    "chartLabel" -> R.string.resource_chart_label
    "isPublic" -> R.string.resource_is_public
    "parent", "library-playlist-folders" -> R.string.resource_folders
    "radio-show" -> R.string.resource_radio_show
    "catalog" -> R.string.resource_apple_music
    "library" -> R.string.nav_library
    "activities" -> R.string.resource_activities
    "stations" -> R.string.resource_stations
    "music-videos", "library-music-videos" -> R.string.resource_videos
    "songs", "library-songs" -> R.string.resource_song
    else -> null
}

@Composable
private fun metadataValue(field: CatalogMetadataField): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val formatted = ArrayList<String>(field.values.size)
    for (value in field.values) {
        val display = when (field.format) {
            CatalogMetadataFormat.Boolean -> when (value.lowercase(Locale.ROOT)) {
                "true" -> stringResource(R.string.resource_yes)
                "false" -> stringResource(R.string.resource_no)
                else -> value
            }
            CatalogMetadataFormat.Duration -> value.toLongOrNull()?.let { millis ->
                val seconds = millis.coerceAtLeast(0) / 1_000
                if (seconds >= 3_600) String.format(locale, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
                else String.format(locale, "%d:%02d", seconds / 60, seconds % 60)
            } ?: value
            CatalogMetadataFormat.Number -> value.toLongOrNull()?.let { NumberFormat.getIntegerInstance(locale).format(it) }
                ?: value.toDoubleOrNull()?.let { NumberFormat.getNumberInstance(locale).format(it) } ?: value
            CatalogMetadataFormat.Date -> runCatching {
                if ('T' in value) OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
                else LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            }.getOrDefault(value)
            CatalogMetadataFormat.AudioVariants -> when (value.lowercase(Locale.ROOT)) {
                "lossy-stereo", "aac" -> stringResource(R.string.resource_lossy_stereo)
                "lossless" -> stringResource(R.string.resource_lossless)
                "hi-res-lossless", "high-resolution-lossless" -> stringResource(R.string.resource_hi_res_lossless)
                "dolby-atmos" -> stringResource(R.string.resource_dolby_atmos)
                "dolby-audio" -> stringResource(R.string.resource_dolby_audio)
                else -> humanizeMetadata(value)
            }
            else -> when {
                field.key == "contentRating" && value == "explicit" -> stringResource(R.string.resource_explicit)
                field.key == "contentRating" && value == "clean" -> stringResource(R.string.resource_clean)
                field.key == "playlistType" && value == "editorial" -> stringResource(R.string.resource_playlist_editorial)
                field.key == "playlistType" && value == "user-shared" -> stringResource(R.string.resource_playlist_shared)
                field.key == "playlistType" && value == "external" -> stringResource(R.string.resource_playlist_external)
                field.key == "mediaKind" && value == "audio" -> stringResource(R.string.resource_audio)
                field.key == "mediaKind" && value == "video" -> stringResource(R.string.resource_video)
                field.key == "trackTypes" && value == "song" -> stringResource(R.string.resource_song)
                field.key == "trackTypes" && value == "music-video" -> stringResource(R.string.resource_videos)
                else -> value
            }
        }
        formatted.add(display)
    }
    return formatted.joinToString(if (field.key.startsWith("credits:")) "\n" else " · ")
}

private fun humanizeMetadata(key: String): String = key.replace('-', ' ')
    .replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
