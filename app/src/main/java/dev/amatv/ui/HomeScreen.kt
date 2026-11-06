package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import coil3.compose.asPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.amatv.R
import dev.amatv.BuildConfig
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.GoogleTvPairing
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.ui.classic.ClassicBrowseVisuals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext

/** The shelf retains its existing cards while one continuation page is requested. */
internal data class LibraryShelfPagination(
    val hasMore: Boolean,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val total: Int? = null,
    val empty: Boolean = false,
    val loadedPages: Int = 0,
    val failureReason: dev.amatv.integration.CatalogFailureReason? = null,
    val refreshGeneration: Long = 0L,
) {
    val showAction: Boolean get() = hasMore || loading || failed || empty
}

/** Viewport/cursor state survives tabs; attached feeds cache bounded neighboring rows. */
@OptIn(ExperimentalFoundationApi::class)
internal class HomeShelfRetention {
    // A symmetric window keeps both sides attached when DPAD reverses direction.
    // Zero ahead would immediately trim the rows that were behind before that
    // reversal. Only lazy shells may prefetch; visible-card grants still gate
    // first-time artwork/text content through HomeCardWarmup.
    val column = androidx.compose.foundation.lazy.LazyListState(
        cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 1f),
    )
    val mountedCards = HomeCardMountHistory()
    val rows = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    var focused: ShelfFocusPosition? = null
    var focusAnchor: ShelfFocusAnchor? = null
    var activatedAnchor by mutableStateOf<ShelfFocusAnchor?>(null)
    var headerAction by mutableStateOf<Pair<String, String>?>(null)
    // Automatic first focus is not permission to pin an early network source
    // when higher-priority shelves are inserted above it a moment later.
    var userNavigated = false
    val requesters = mutableMapOf<String, MutableMap<String, FocusRequester>>()
}
internal val LocalHomeShelfRetention = staticCompositionLocalOf<HomeShelfRetention?> { null }

internal data class HomeInsightsLink(
    val source: ListeningInsightsSource,
    val recommendationId: String? = null,
    val title: String? = null,
)

private sealed interface HomeHeadingAction {
    val key: String
    data class Insights(val link: HomeInsightsLink) : HomeHeadingAction {
        override val key = "insights-${link.source.name.lowercase()}"
    }
    data class Resource(val track: Track) : HomeHeadingAction { override val key = "see-all" }
    data object StationGenres : HomeHeadingAction { override val key = "station-genres" }
}

// Keep Replay's data/browser available while its Home entry is hidden.
private const val HOME_REPLAY_ACTION_VISIBLE = false

/** Home's source-qualified IDs retain the real recommendation identity, never a translated title. */
internal fun homeShelfInsightsLink(shelfId: String, title: String? = null): HomeInsightsLink? {
    val source = when (shelfId) {
        "recent-tracks", "recent-tracks:recent-tracks" -> ListeningInsightsSource.RecentTracks
        "recent", "recent:recent" -> ListeningInsightsSource.RecentResources
        "heavy-rotation", "heavy-rotation:heavy-rotation" -> ListeningInsightsSource.HeavyRotation
        "recommendations", "recommendations:recommendations" -> ListeningInsightsSource.Recommendations
        else -> null
    }
    if (source != null) return HomeInsightsLink(source, title = title)
    val recommendation = shelfId.removePrefix("recommendations:")
        .takeIf { it.startsWith("recommendation:") }?.removePrefix("recommendation:")?.takeIf(String::isNotBlank)
    return recommendation?.let { HomeInsightsLink(ListeningInsightsSource.Recommendations, it, title) }
}

/** Geometry changes with the presentation; shelf identity and loading stay shared. */
internal class HomeShelfLayout(val classic: Boolean) {
    val showcaseWidth = if (classic) ClassicBrowseVisuals.ShowcaseWidth else BrowseTokens.ShowcaseWidth
    val showcaseHeight = if (classic) ClassicBrowseVisuals.ShowcaseHeight else BrowseTokens.ShowcaseHeight
    val showcaseRadius = if (classic) ClassicBrowseVisuals.ShowcaseRadius else BrowseTokens.ShowcaseRadius
    val cardWidth = if (classic) ClassicBrowseVisuals.CardWidth else BrowseTokens.CardWidth
    val cardHeight = if (classic) ClassicBrowseVisuals.CardHeight else BrowseTokens.CardHeight
    val cardRadius = if (classic) ClassicBrowseVisuals.CardRadius else BrowseTokens.CardRadius
    val showcaseFocusScale = if (classic) ClassicBrowseVisuals.ShowcaseFocusScale else BrowseTokens.ShowcaseFocusScale
    val cardFocusScale = if (classic) ClassicBrowseVisuals.CardFocusScale else BrowseTokens.CardFocusScale
    val shelfGap = if (classic) 18.dp else BrowseTokens.ShelfGap
    val headingSize = if (classic) 22.sp else BrowseTokens.HeadingSize
    val headingLineHeight = if (classic) 26.sp else BrowseTokens.HeadingLineHeight
    val headingStart = if (classic) 8.dp else BrowseTokens.RowHorizontalPadding

    fun columnGap(featured: Boolean) = if (classic && !featured) 20.dp else BrowseTokens.ColumnGap
    fun headingGap(featured: Boolean) = if (classic && featured) 4.dp else BrowseTokens.HeadingCardGap
    fun focusOutset(featured: Boolean) = if (classic) {
        val width = if (featured) showcaseWidth else cardWidth
        val scale = if (featured) showcaseFocusScale else cardFocusScale
        width * ((scale - 1f) / 2f) + CardVisuals.ShadowOutset
    } else CardVisuals.focusOutset(featured)
}

private val appleHomeShelfLayout = HomeShelfLayout(classic = false)
private val classicHomeShelfLayout = HomeShelfLayout(classic = true)

@Composable
private fun homeShelfLayout() = if (LocalInterfaceStyle.current == InterfaceStyle.Classic)
    classicHomeShelfLayout else appleHomeShelfLayout

private fun ContentShelf.cardWidth(layout: HomeShelfLayout) = when {
    id == BrowseInlineSection.Genres.shelfId -> 190.dp
    featured -> layout.showcaseWidth
    else -> layout.cardWidth
}

// Album/playlist kind labels are intentionally omitted from classic eyebrows.
// Reserve that band for the whole row only when a card can actually use it.
private fun ContentShelf.hasClassicEyebrowBand() = items.isEmpty() || items.any {
    it.catalogKind != CatalogKind.Album && it.catalogKind != CatalogKind.Playlist
}

private fun ContentShelf.featuredHeight(layout: HomeShelfLayout) = layout.showcaseHeight -
    if (layout.classic && !hasClassicEyebrowBand()) 24.dp else 0.dp

private fun ContentShelf.rowHeight(layout: HomeShelfLayout) = when {
    id == BrowseInlineSection.Genres.shelfId -> 76.dp + BrowseTokens.RowVerticalPadding * 2
    featured -> featuredHeight(layout) + BrowseTokens.RowVerticalPadding * 2
    else -> layout.cardHeight + BrowseTokens.RowVerticalPadding * 2
}

/** Same text-first genre tile as the catalog root, inside the shared shelf. */
@Composable
private fun InlineGenreCard(track: Track, onClick: () -> Unit, modifier: Modifier) {
    val menu = catalogMenuFor(track)
    FocusSurface(onClick,
        modifier.width(190.dp).height(76.dp).catalogMenuKey(track),
        cornerRadius = 12.dp, focusedScale = 1.025f,
        background = Color.White.copy(alpha = 0.055f), focusedBackground = Color.White.copy(alpha = 0.86f),
        padding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
        onLongClick = menu?.let { open -> { open(track) } },
        onLongClickLabel = stringResource(R.string.actions_menu),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(track.title, color = focusSurfaceContentColor(Color.White.copy(alpha = 0.91f), Color(0xFF1B1B1F)),
                fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(17.dp),
                focusSurfaceContentColor(Color.White.copy(alpha = 0.35f), Color(0xFF1B1B1F).copy(alpha = 0.45f)))
        }
    }
}

@Composable
internal fun HomeScreen(
    destination: Destination,
    currentTrack: Track,
    homeShelves: List<ContentShelf> = emptyList(),
    homeLoading: Boolean = false,
    homeShelfPagination: Map<String, LibraryShelfPagination> = emptyMap(),
    onLoadMoreShelf: (String) -> Unit = {},
    onSelectLibrarySection: (String) -> Unit = {},
    connectionState: MusicConnectionState = MusicConnectionState.Demo,
    setupMessage: String? = null,
    onSignIn: () -> Unit = {},
    ytmSignedIn: Boolean = false,
    ytmPairing: GoogleTvPairing? = null,
    ytmPairingConfigured: Boolean = false,
    onYtmSignIn: () -> Unit = {},
    onYtmSignOut: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenTrack: (Track, List<Track>) -> Unit = { _, _ -> },
    onOpenInsights: ((HomeInsightsLink) -> Unit)? = null,
    onOpenGenres: (() -> Unit)? = null,
    onRegisterReturnToTop: ((() -> Boolean)?) -> Unit = {},
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    autoplaySource: dev.amatv.integration.AutoplaySource = dev.amatv.integration.AutoplaySource.AppleMusic,
    onAutoplaySourceChange: (dev.amatv.integration.AutoplaySource) -> Unit = {},
    lowPerformanceMode: Boolean = false,
    onLowPerformanceModeChange: (Boolean) -> Unit = {},
    firstContentFocusRequester: FocusRequester? = null,
    navigationFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    cacheSettingsContent: @Composable () -> Unit = {},
    searchContent: @Composable (FocusRequester?, FocusRequester?) -> Unit,
    onPhoneSignIn: (() -> Unit)? = null,
    onLibrarySearch: () -> Unit = {},
    contentAuthorizationRequired: Boolean = false,
    onRequestNavigation: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().automationId("browse-${destination.name.lowercase()}")) {
        when (destination) {
            Destination.Account -> {
                AccountScreen(
                    connectionState = connectionState,
                    setupMessage = setupMessage,
                    onSignIn = onSignIn,
                    onPhoneSignIn = onPhoneSignIn,
                    ytmSignedIn = ytmSignedIn,
                    ytmPairing = ytmPairing,
                    ytmPairingConfigured = ytmPairingConfigured,
                    onYtmSignIn = onYtmSignIn,
                    onYtmSignOut = onYtmSignOut,
                    onSignOut = onSignOut,
                    onRetry = onRetry,
                    lyricOffsetMs = lyricOffsetMs,
                    onLyricOffsetChange = onLyricOffsetChange,
                    autoplaySource = autoplaySource,
                    onAutoplaySourceChange = onAutoplaySourceChange,
                    lowPerformanceMode = lowPerformanceMode,
                    onLowPerformanceModeChange = onLowPerformanceModeChange,
                    cacheSettingsContent = cacheSettingsContent,
                    firstContentFocusRequester = firstContentFocusRequester,
                    navigationFocusRequester = navigationFocusRequester,
                )
            }

            Destination.Library -> {
                if (connectionState != MusicConnectionState.SignedIn && connectionState != MusicConnectionState.Demo) {
                    AccountScreen(
                        connectionState = connectionState,
                        setupMessage = setupMessage,
                        onSignIn = onSignIn,
                        onPhoneSignIn = onPhoneSignIn,
                        onSignOut = onSignOut,
                        onRetry = onRetry,
                        firstContentFocusRequester = firstContentFocusRequester,
                        navigationFocusRequester = navigationFocusRequester,
                        headingOverride = stringResource(R.string.browse_library_sign_in),
                    )
                } else {
                    LibraryScreen(
                        shelves = homeShelves,
                        pagination = homeShelfPagination,
                        onSelectSection = onSelectLibrarySection,
                        onLoadMore = onLoadMoreShelf,
                        onOpen = onOpenTrack,
                        firstFocus = firstContentFocusRequester,
                        menuFocus = navigationFocusRequester,
                        onLibrarySearch = onLibrarySearch,
                        onSignIn = onSignIn,
                    )
                }
            }

            Destination.Search -> searchContent(firstContentFocusRequester, navigationFocusRequester)

            else -> {
                HomeShelves(
                    shelves = homeShelves,
                    loading = homeLoading,
                    connectionState = connectionState,
                    setupMessage = setupMessage,
                    authorizationRequired = contentAuthorizationRequired,
                    onSignIn = onSignIn,
                    onRetry = onRetry,
                    onOpenTrack = onOpenTrack,
                    pagination = homeShelfPagination,
                    onLoadMoreShelf = onLoadMoreShelf,
                    firstContentFocusRequester = firstContentFocusRequester,
                    navigationFocusRequester = navigationFocusRequester,
                    onRegisterReturnToTop = onRegisterReturnToTop,
                    onOpenInsights = onOpenInsights.takeIf {
                        destination == Destination.Home && connectionState == MusicConnectionState.SignedIn
                    },
                    onOpenStationGenres = onOpenGenres.takeIf { destination == Destination.Radio },
                    onRequestNavigation = onRequestNavigation,
                )
            }
        }
    }
}

@Composable
private fun AccountScreen(
    connectionState: MusicConnectionState,
    setupMessage: String?,
    onSignIn: () -> Unit,
    ytmSignedIn: Boolean = false,
    ytmPairing: GoogleTvPairing? = null,
    ytmPairingConfigured: Boolean = false,
    onYtmSignIn: () -> Unit = {},
    onYtmSignOut: () -> Unit = {},
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    autoplaySource: dev.amatv.integration.AutoplaySource = dev.amatv.integration.AutoplaySource.AppleMusic,
    onAutoplaySourceChange: (dev.amatv.integration.AutoplaySource) -> Unit = {},
    lowPerformanceMode: Boolean = false,
    onLowPerformanceModeChange: (Boolean) -> Unit = {},
    firstContentFocusRequester: FocusRequester?,
    navigationFocusRequester: FocusRequester?,
    headingOverride: String? = null,
    cacheSettingsContent: @Composable () -> Unit = {},
    onPhoneSignIn: (() -> Unit)? = null,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val connected = connectionState == MusicConnectionState.SignedIn
    val surface = if (classic) Color.White.copy(alpha = 0.14f) else BrowseTokens.Surface
    val controlHeight = if (classic) 46.dp else 48.dp
    val controlRadius = if (classic) 23.dp else 12.dp
    val demo = connectionState == MusicConnectionState.Demo
    val phoneFocus = firstContentFocusRequester ?: remember { FocusRequester() }
    val primaryFocus = if (onPhoneSignIn == null) phoneFocus else remember { FocusRequester() }
    val demoFirstFocus = firstContentFocusRequester ?: remember { FocusRequester() }
    val secondaryFocus = remember { FocusRequester() }
    val offsetFocus = remember { List(5) { FocusRequester() } }
    val autoplayFocus = remember { List(2) { FocusRequester() } }
    val performanceFocus = remember { List(2) { FocusRequester() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = headingOverride
                ?: if (connected) stringResource(R.string.browse_apple_connected) else stringResource(R.string.browse_apple_sign_in),
            color = Color.White,
            fontSize = if (classic) 32.sp else 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
        )
        if (!setupMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(if (classic) 14.dp else 12.dp))
                    .background(if (classic) Color.White.copy(alpha = 0.07f) else BrowseTokens.Surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(
                    text = setupMessage,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (onPhoneSignIn != null && connectionState != MusicConnectionState.Demo) {
            FocusSurface(
                onClick = onPhoneSignIn,
                modifier = Modifier.width(280.dp).height(if (classic) 54.dp else 52.dp).focusRequester(phoneFocus)
                    .automationId("account-phone-sign-in").focusProperties {
                        if (navigationFocusRequester != null) up = navigationFocusRequester
                        if (navigationFocusRequester != null) left = navigationFocusRequester
                        right = FocusRequester.Cancel; down = primaryFocus
                    },
                cornerRadius = if (classic) 27.dp else 12.dp, focusedScale = if (classic) 1.03f else 1.025f,
                background = Color.White.copy(alpha = .10f), focusedBackground = Color.White.copy(alpha = .90f),
                padding = PaddingValues(horizontal = 20.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.phone_sign_in_title),
                        color = focusSurfaceContentColor(Color.White, Color(0xFF171717)),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!demo) {
                FocusSurface(
                    onClick = if (connected) onRetry else onSignIn,
                    modifier = Modifier
                        .width(280.dp)
                        .height(if (classic) 54.dp else 52.dp)
                        .focusRequester(primaryFocus)
                        .focusProperties {
                            if (onPhoneSignIn != null) up = phoneFocus
                            else if (navigationFocusRequester != null) up = navigationFocusRequester
                            if (navigationFocusRequester != null) left = navigationFocusRequester
                            right = secondaryFocus
                        },
                    cornerRadius = if (classic) 27.dp else 12.dp,
                    focusedScale = if (classic) 1.03f else 1.025f,
                    background = Color.White.copy(alpha = if (classic) 0.90f else 0.10f),
                    focusedBackground = Color.White.copy(alpha = if (classic) 1f else 0.90f),
                    padding = PaddingValues(horizontal = 20.dp),
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (connected) stringResource(R.string.browse_refresh_apple) else stringResource(R.string.browse_sign_in_tv),
                            color = if (classic) Color(0xFF111713) else focusSurfaceContentColor(Color.White, Color(0xFF171717)),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                FocusSurface(
                    onClick = if (connected) onSignOut else onRetry,
                    modifier = Modifier
                        .width(240.dp)
                        .height(if (classic) 54.dp else 52.dp)
                        .focusRequester(secondaryFocus)
                        .focusProperties {
                            if (onPhoneSignIn != null) up = phoneFocus
                            else if (navigationFocusRequester != null) up = navigationFocusRequester
                            left = primaryFocus
                            right = FocusRequester.Cancel
                        },
                    cornerRadius = if (classic) 27.dp else 12.dp,
                    focusedScale = if (classic) 1.03f else 1.025f,
                    background = surface,
                    focusedBackground = Color.White.copy(alpha = if (classic) 0.28f else 0.9f),
                    padding = PaddingValues(horizontal = 20.dp),
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (connected) stringResource(R.string.browse_sign_out_tv) else stringResource(R.string.browse_retry_connection),
                            color = if (classic) Color.White else focusSurfaceContentColor(Color.White, Color(0xFF171717)),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        if (headingOverride == null) {
            if (ytmPairingConfigured) {
            Text(
                text = if (ytmSignedIn) stringResource(R.string.browse_ytm_connected) else stringResource(R.string.browse_ytm_recommendations),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    ytmPairing != null ->
                        stringResource(R.string.browse_pairing_instructions, ytmPairing.verificationUrl, ytmPairing.userCode)
                    ytmSignedIn -> stringResource(R.string.browse_personalized_enabled)
                    else -> stringResource(R.string.browse_pairing_hint)
                },
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
            )
            if (ytmPairingConfigured) {
                FocusSurface(
                    onClick = { if (ytmSignedIn) onYtmSignOut() else if (ytmPairing == null) onYtmSignIn() },
                    modifier = Modifier.width(280.dp).height(controlHeight)
                        .then(if (demo) Modifier.focusRequester(demoFirstFocus) else Modifier).focusProperties {
                        if (navigationFocusRequester != null) left = navigationFocusRequester
                        right = FocusRequester.Cancel
                    },
                    cornerRadius = controlRadius,
                    background = surface,
                    focusedBackground = Color.White.copy(alpha = if (classic) 0.28f else 0.9f),
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (ytmSignedIn) stringResource(R.string.browse_ytm_sign_out) else stringResource(R.string.browse_ytm_pair),
                            color = if (classic) Color.White else focusSurfaceContentColor(Color.White, Color(0xFF171717)),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browse_lyrics_sync),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.browse_lyrics_sync_hint),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs - 100L).coerceAtLeast(-3000L)) },
                    modifier = Modifier.width(96.dp).height(controlHeight).focusRequester(offsetFocus[0])
                        .then(if (demo && !ytmPairingConfigured) Modifier.focusRequester(demoFirstFocus) else Modifier).focusProperties {
                        if (navigationFocusRequester != null) left = navigationFocusRequester
                        right = offsetFocus[1]
                    },
                    cornerRadius = controlRadius,
                    background = surface,
                    focusedBackground = Color.White.copy(alpha = if (classic) 0.28f else 0.9f),
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, -100), color = if (classic) Color.White else focusSurfaceContentColor(Color.White, Color(0xFF171717)), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs - 50L).coerceAtLeast(-3000L)) },
                    modifier = Modifier.width(90.dp).height(controlHeight).focusRequester(offsetFocus[1]).focusProperties {
                        left = offsetFocus[0]; right = offsetFocus[2]
                    },
                    cornerRadius = controlRadius,
                    background = surface,
                    focusedBackground = Color.White.copy(alpha = if (classic) 0.28f else 0.9f),
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, -50), color = if (classic) Color.White else focusSurfaceContentColor(Color.White, Color(0xFF171717)), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(
                    modifier = Modifier
                        .height(controlHeight)
                        .clip(RoundedCornerShape(controlRadius))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            lyricOffsetMs > 0L -> stringResource(R.string.browse_offset_value, lyricOffsetMs)
                            lyricOffsetMs < 0L -> stringResource(R.string.browse_offset_value, lyricOffsetMs)
                            else -> stringResource(R.string.browse_offset_default)
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs + 50L).coerceAtMost(3000L)) },
                    modifier = Modifier.width(90.dp).height(controlHeight).focusRequester(offsetFocus[2]).focusProperties {
                        left = offsetFocus[1]; right = offsetFocus[3]
                    },
                    cornerRadius = controlRadius,
                    background = surface,
                    focusedBackground = Color.White.copy(alpha = if (classic) 0.28f else 0.9f),
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, 50), color = if (classic) Color.White else focusSurfaceContentColor(Color.White, Color(0xFF171717)), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs + 100L).coerceAtMost(3000L)) },
                    modifier = Modifier.width(96.dp).height(controlHeight).focusRequester(offsetFocus[3]).focusProperties {
                        left = offsetFocus[2]
                        right = if (lyricOffsetMs != 0L) offsetFocus[4] else FocusRequester.Cancel
                    },
                    cornerRadius = controlRadius,
                    background = surface,
                    focusedBackground = Color.White.copy(alpha = if (classic) 0.28f else 0.9f),
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, 100), color = if (classic) Color.White else focusSurfaceContentColor(Color.White, Color(0xFF171717)), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (lyricOffsetMs != 0L) {
                    FocusSurface(
                        onClick = {
                            // Reset removes itself; retain an existing control
                            // before that recomposition rather than losing focus.
                            runCatching { offsetFocus[3].requestFocus() }
                            onLyricOffsetChange(0L)
                        },
                        modifier = Modifier.height(controlHeight).focusRequester(offsetFocus[4]).focusProperties {
                            left = offsetFocus[3]; right = FocusRequester.Cancel
                        },
                        cornerRadius = controlRadius,
                        background = surface,
                        focusedBackground = Color.White.copy(alpha = if (classic) 0.28f else 0.9f),
                        padding = PaddingValues(horizontal = 16.dp),
                    ) { focused ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.browse_reset), color = if (classic) Color.White.copy(alpha = 0.85f) else focusSurfaceContentColor(Color.White.copy(alpha = 0.85f), Color(0xFF171717)), fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.browse_autoplay_source),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isYtm = autoplaySource == dev.amatv.integration.AutoplaySource.YouTubeMusic
                AccountChoiceButton(
                    label = stringResource(R.string.browse_ytm_name),
                    selected = isYtm,
                    onClick = { onAutoplaySourceChange(dev.amatv.integration.AutoplaySource.YouTubeMusic) },
                    modifier = Modifier.focusRequester(autoplayFocus[0]).focusProperties {
                        if (navigationFocusRequester != null) left = navigationFocusRequester
                        right = autoplayFocus[1]
                    },
                )
                val isAm = autoplaySource == dev.amatv.integration.AutoplaySource.AppleMusic
                AccountChoiceButton(
                    label = stringResource(R.string.browse_apple_name),
                    selected = isAm,
                    onClick = { onAutoplaySourceChange(dev.amatv.integration.AutoplaySource.AppleMusic) },
                    modifier = Modifier.focusRequester(autoplayFocus[1]).focusProperties {
                        left = autoplayFocus[0]; right = FocusRequester.Cancel
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.browse_performance_mode),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.browse_performance_mode_hint),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountChoiceButton(
                    label = stringResource(R.string.browse_performance_standard),
                    selected = !lowPerformanceMode,
                    onClick = { onLowPerformanceModeChange(false) },
                    modifier = Modifier.focusRequester(performanceFocus[0]).focusProperties {
                        if (navigationFocusRequester != null) left = navigationFocusRequester
                        right = performanceFocus[1]
                    },
                )
                AccountChoiceButton(
                    label = stringResource(R.string.browse_performance_low),
                    selected = lowPerformanceMode,
                    onClick = { onLowPerformanceModeChange(true) },
                    modifier = Modifier.focusRequester(performanceFocus[1]).focusProperties {
                        left = performanceFocus[0]; right = FocusRequester.Cancel
                    },
                )
            }
        }
        cacheSettingsContent()
    }
}

@Composable
private fun AccountChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(220.dp).height(if (classic) 46.dp else 48.dp).then(modifier),
        cornerRadius = if (classic) 23.dp else 12.dp,
        background = if (classic) Color.White.copy(alpha = if (selected) 0.26f else 0.12f)
            else if (selected) Color.White.copy(alpha = 0.18f) else BrowseTokens.Surface,
        focusedBackground = Color.White.copy(alpha = if (classic) 0.38f else 0.9f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (selected) stringResource(R.string.browse_selected_source, label) else label,
                color = if (classic) Color.White else focusSurfaceContentColor(Color.White, Color(0xFF171717)),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private object StationaryShelfViewport : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

internal fun shelfRevealScroll(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int): Float = when {
    offset < viewportStart -> (offset - viewportStart).toFloat()
    offset + size > viewportEnd -> minOf(offset - viewportStart, offset + size - viewportEnd).toFloat()
    else -> 0f
}

internal data class ShelfFocusScroll(val shelfIndex: Int, val destinationOffset: Float) {
    fun remaining(shelf: Int, currentOffset: Int): Float =
        if (shelf == shelfIndex) currentOffset - destinationOffset else 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShelves(
    shelves: List<ContentShelf>,
    loading: Boolean,
    connectionState: MusicConnectionState,
    setupMessage: String?,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    pagination: Map<String, LibraryShelfPagination> = emptyMap(),
    onLoadMoreShelf: (String) -> Unit = {},
    firstContentFocusRequester: FocusRequester?,
    navigationFocusRequester: FocusRequester?,
    aboveContentFocusRequester: FocusRequester? = null,
    authorizationRequired: Boolean = false,
    onRegisterReturnToTop: ((() -> Boolean)?) -> Unit = {},
    onOpenInsights: ((HomeInsightsLink) -> Unit)? = null,
    onOpenStationGenres: (() -> Unit)? = null,
    onRequestNavigation: (() -> Unit)? = null,
) {
    val homeLayout = homeShelfLayout()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val leadingKey = if (isRtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    if (shelves.isEmpty()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppleLoadingIndicator()
            }
            return
        }
        val retryRequester = firstContentFocusRequester ?: remember { FocusRequester() }
        val signInRequester = remember { FocusRequester() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(480.dp)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.browse_content_unavailable),
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                val detailText = when {
                    connectionState != MusicConnectionState.SignedIn ->
                        stringResource(R.string.browse_sign_in_personalized)
                    else -> stringResource(R.string.browse_check_connection)
                }
                Text(
                    text = detailText,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                )
                if (!loading) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FocusSurface(
                            onClick = onRetry,
                            modifier = Modifier
                                .width(140.dp)
                                .height(38.dp)
                                .focusRequester(retryRequester)
                                .focusProperties {
                                    (aboveContentFocusRequester ?: navigationFocusRequester)?.let { up = it }
                                    if (navigationFocusRequester != null) left = navigationFocusRequester
                                    right = if (connectionState != MusicConnectionState.Demo) signInRequester else FocusRequester.Cancel
                                },
                            cornerRadius = 19.dp,
                            focusedScale = 1.04f,
                            background = Color.White.copy(alpha = 0.10f),
                            focusedBackground = Color.White.copy(alpha = 0.90f),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.browse_reload),
                                    color = focusSurfaceContentColor(Color.White, Color(0xFF111713)),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (connectionState == MusicConnectionState.ReadyToSignIn || authorizationRequired) {
                            FocusSurface(
                                onClick = onSignIn,
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(38.dp)
                                    .focusRequester(signInRequester)
                                    .focusProperties {
                                        (aboveContentFocusRequester ?: navigationFocusRequester)?.let { up = it }
                                        left = retryRequester
                                    },
                                cornerRadius = 19.dp,
                                focusedScale = 1.04f,
                                background = Color.White.copy(alpha = 0.14f),
                                focusedBackground = Color.White.copy(alpha = 0.28f),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (connectionState == MusicConnectionState.SignedIn) {
                                            stringResource(R.string.browse_sign_in_again)
                                        } else {
                                            stringResource(R.string.browse_apple_sign_in)
                                        },
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val shelfKey = remember(shelves) { shelves.map(ContentShelf::id) }
    val resourceCounts = remember(shelves) { shelves.map { it.items.size } }
    val shelfSizes = remember(shelves, pagination) {
        shelves.map { it.items.size + if (pagination[it.id]?.showAction == true) 1 else 0 }
    }
    val retained = LocalHomeShelfRetention.current ?: remember { HomeShelfRetention() }
    val columnState = retained.column
    val rowStates = remember(shelfKey, retained) {
        shelves.map { retained.rows.getOrPut(it.id) { androidx.compose.foundation.lazy.LazyListState() } }
    }
    // A root reset is an explicit destination intent, not an ordinary Up key.
    // Keep it across progressive row replacement until that real card owns focus.
    var topEntryRequest by remember(retained) { mutableIntStateOf(0) }
    val firstEntry = firstShelfEntry(shelves, shelfSizes) ?: ShelfFocusPosition(0, 0)
    val restoredFocus = if (topEntryRequest != 0) firstEntry else if (retained.userNavigated)
        resolveShelfFocusAnchor(retained.activatedAnchor ?: retained.focusAnchor, shelves, shelfSizes) ?: firstEntry
        else firstEntry
    // Lazy rows keep their actual focused nodes when an earlier source arrives.
    // Keep their requesters as well; replacing the whole matrix detached remote
    // navigation from the row that still owned Android focus.
    val itemFocusRequesters = remember(shelves, shelfSizes, retained) {
        shelves.map { shelf ->
            val row = retained.requesters.getOrPut(shelf.id) { mutableMapOf() }
            buildList {
                shelf.items.forEach { track -> add(row.getOrPut(track.shelfFocusKey()) { FocusRequester() }) }
                if (pagination[shelf.id]?.showAction == true)
                    add(row.getOrPut("continuation:${shelf.items.size}") { FocusRequester() })
            }
        }
    }
    // The key handler reads this holder without invalidating the whole LazyColumn
    // whenever focus moves one card horizontally.
    val navigationCursor = remember(retained) { ShelfNavigationCursor(shelfSizes).apply {
        onFocused(restoredFocus.shelf, restoredFocus.item)
        updateShelfSizes(shelfSizes)
    } }
    val structure = remember(retained) { arrayOf<List<String>?>(null) }
    val contentEntry = LocalContentFocusEntry.current
    // Heading membership follows source identities, never transient focus.
    val replayShelf = retained.headerAction?.takeIf { it.second == "insights-replay" && it.first in shelfKey }?.first
        ?: shelves.firstOrNull()?.id
    val headerActions = remember(shelves, onOpenInsights != null, onOpenStationGenres != null, replayShelf) {
        shelves.mapIndexed { index, shelf ->
            shelf.id to buildList<HomeHeadingAction> {
                shelf.seeAll?.let { add(HomeHeadingAction.Resource(it)) }
                if (onOpenInsights != null) {
                    if (shelf.seeAll == null && shelf.hasMore) homeShelfInsightsLink(shelf.id, shelf.title)?.let { add(HomeHeadingAction.Insights(it)) }
                    if (HOME_REPLAY_ACTION_VISIBLE && shelf.id == replayShelf)
                        add(HomeHeadingAction.Insights(HomeInsightsLink(ListeningInsightsSource.Replay)))
                }
                if (index == 0 && onOpenStationGenres != null) add(HomeHeadingAction.StationGenres)
            }
        }.toMap()
    }
    val headerRequesters = remember(headerActions, retained) {
        buildMap {
            headerActions.forEach { (shelf, actions) -> actions.forEach { action ->
                put(shelf to action.key, retained.requesters.getOrPut(shelf) { mutableMapOf() }
                    .getOrPut("header:${action.key}") { FocusRequester() })
            } }
        }
    }
    var focusedHeader by remember { mutableStateOf<Pair<String, String>?>(null) }
    DisposableEffect(contentEntry) {
        onDispose { contentEntry?.preferredReturnRequester = null }
    }
    var contentHasFocus by remember { mutableStateOf(false) }
    val currentContentHasFocus by rememberUpdatedState(contentHasFocus)
    SideEffect {
        navigationCursor.updateShelfSizes(shelfSizes)
        // Native focus callbacks can update the anchor after this composition
        // read it. Resolve again at commit; never write its old row over the
        // card that actually won focus in that same transaction.
        val committedFocus = if (topEntryRequest != 0) firstEntry else if (retained.userNavigated)
            resolveShelfFocusAnchor(retained.activatedAnchor ?: retained.focusAnchor, shelves, shelfSizes) ?: firstEntry
            else firstEntry
        // A pending destination is not proof that its native target has focus.
        if (topEntryRequest == 0) navigationCursor.onFocused(committedFocus.shelf, committedFocus.item)
        // An incidental first native focus on a heading is not a user source.
        // Initial entry belongs to the first real card; explicit Up/clicks set
        // userNavigated and still restore their actual heading on return.
        val headerRequester = retained.headerAction?.takeIf { retained.userNavigated }?.let(headerRequesters::get)
        contentEntry?.requester = headerRequester ?: itemFocusRequesters.getOrNull(committedFocus.shelf)?.getOrNull(committedFocus.item)
        if (headerRequester == null) {
            retained.headerAction = null
            contentEntry?.preferredReturnRequester = if (retained.activatedAnchor != null)
                itemFocusRequesters.getOrNull(committedFocus.shelf)?.getOrNull(committedFocus.item) else null
        }
        if (structure[0] != shelfKey) {
            structure[0] = shelfKey
            if (!retained.userNavigated) {
                // Forget LazyColumn's previous first-visible key before the new
                // provider is measured. Otherwise each prepended source moves
                // the initial page farther down despite zero user input.
                columnState.requestScrollToItem(firstEntry.shelf)
                rowStates[firstEntry.shelf].requestScrollToItem(0)
            }
        }
    }
    var pendingPageFocus by remember(shelfKey) { mutableStateOf<ShelfFocusPosition?>(null) }
    val scope = rememberCoroutineScope()
    val focusJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val verticalPlan = remember { mutableStateOf<ShelfFocusScroll?>(null) }
    val density = LocalDensity.current
    val focusTargetsEnabled = LocalFocusTargetsEnabled.current
    val contentVisible = LocalBrowseContentVisible.current
    ContentUpExitReadiness(contentEntry) {
        val actual = resolveShelfFocusAnchor(retained.focusAnchor, shelves, shelfSizes)
        val header = focusedHeader
        val actualShelf = actual?.shelf?.let(shelves::getOrNull)
        (if (header != null) header.first == shelves[firstEntry.shelf].id
            else actualShelf?.let { headerActions[it.id] }.isNullOrEmpty() &&
                actual?.shelf == firstEntry.shelf) && navigationCursor.requestedPosition.shelf == firstEntry.shelf &&
            !columnState.isScrollInProgress && focusJob[0]?.isActive != true &&
            columnState.firstVisibleItemIndex == 0 && columnState.firstVisibleItemScrollOffset == 0
    }
    rememberHomeArtworkPrefetch(shelves, columnState, rowStates, homeLayout, contentVisible && focusTargetsEnabled)
    // A final one-item page replaces its continuation card without changing the
    // total slot count. Resource counts must still trigger focus restoration.
    LaunchedEffect(resourceCounts, shelfSizes, focusTargetsEnabled) {
        val request = pendingPageFocus ?: return@LaunchedEffect
        if (!focusTargetsEnabled) {
            pendingPageFocus = null
            return@LaunchedEffect
        }
        val shelf = shelves.getOrNull(request.shelf) ?: return@LaunchedEffect
        val page = pagination[shelf.id]
        if (shelf.items.size <= request.item && page?.showAction == true) return@LaunchedEffect
        val target = request.item.coerceAtMost(shelf.items.lastIndex)
        if (target < 0) {
            pendingPageFocus = null
            navigationFocusRequester?.let { runCatching { it.requestFocus() } }
            return@LaunchedEffect
        }
        // The old continuation card is replaced at the same index by the first
        // new resource. Existing row state, cards and focus requesters stay alive.
        repeat(3) {
            withFrameNanos { }
            if (pendingPageFocus != request) return@LaunchedEffect
            if (runCatching { itemFocusRequesters[request.shelf][target].requestFocus() }.getOrDefault(false)) {
                pendingPageFocus = null
                return@LaunchedEffect
            }
        }
        if (pendingPageFocus == request) {
            rowStates[request.shelf].scrollToItem(target)
            withFrameNanos { }
            runCatching { itemFocusRequesters[request.shelf][target].requestFocus() }
            pendingPageFocus = null
        }
    }
    // Progressive sources change shelfKey. They must not revoke every mounted
    // card's permit and replace cached covers/labels with skeletons again.
    val cardWarmup = remember(retained) { HomeCardWarmup(retained.mountedCards) }
    DisposableEffect(cardWarmup) {
        onDispose { cardWarmup.close() }
    }
    LaunchedEffect(cardWarmup, contentVisible) {
        if (!contentVisible) return@LaunchedEffect
        while (cardWarmup.awaitPending()) {
            withFrameNanos { cardWarmup.mountNext(it) }
        }
    }
    LaunchedEffect(cardWarmup, columnState, rowStates, contentVisible) {
        if (!contentVisible) return@LaunchedEffect
        // Prefetched slots cannot consume a content permit until they are in
        // the real viewport. Observe index sets, rather than every scroll pixel.
        snapshotFlow {
            val column = columnState.layoutInfo
            buildSet {
                for (shelf in column.visibleItemsInfo) {
                    if (shelf.offset >= column.viewportEndOffset ||
                        shelf.offset + shelf.size <= column.viewportStartOffset) continue
                    // Layout can still describe the previous ordering for one
                    // frame after an insertion. Resolve its stable shelf/card
                    // keys, never reinterpret that frame's numeric indices.
                    val stableShelfId = shelf.key as? String ?: continue
                    val row = retained.rows[stableShelfId]?.layoutInfo ?: continue
                    for (card in row.visibleItemsInfo) {
                        if (card.offset < row.viewportEndOffset && card.offset + card.size > row.viewportStartOffset) {
                            add(card.key)
                        }
                    }
                }
            }
        }.collect(cardWarmup::updateVisibleCards)
    }
    DisposableEffect(shelfKey, focusTargetsEnabled) {
        onDispose {
            focusJob[0]?.cancel()
            navigationCursor.cancel()
            verticalPlan.value = null
        }
    }

    fun cancelVerticalFocus(keepFocusedRowReveal: Boolean = false) {
        // A fast horizontal key may follow Down before its 260 ms reveal
        // settles. Keep revealing the row that already owns input focus;
        // cancelling that scroll strands its cards below the viewport.
        if (keepFocusedRowReveal && verticalPlan.value?.shelfIndex == navigationCursor.focused.shelf) {
            navigationCursor.cancel()
            cardWarmup.notifyPrioritiesChanged()
            return
        }
        focusJob[0]?.cancel()
        navigationCursor.cancel()
        verticalPlan.value = null
        cardWarmup.notifyPrioritiesChanged()
    }

    fun requestNavigationFocus(): Boolean {
        val request = onRequestNavigation ?: return false
        // Leaving content ends its pending scroll/focus work before the host
        // transfers ownership. Both visual styles use that same handoff.
        topEntryRequest = 0
        pendingPageFocus = null
        contentEntry?.preferredReturnRequester = null
        cancelVerticalFocus()
        request()
        return true
    }

    fun focusAboveContent() {
        if (aboveContentFocusRequester != null) {
            runCatching { aboveContentFocusRequester.requestFocus() }
        } else if (!requestNavigationFocus()) {
            navigationFocusRequester?.let { runCatching { it.requestFocus() } }
        }
    }

    fun handleLeadingBoundary(): Boolean {
        if (!homeLayout.classic) return requestNavigationFocus()
        // The classic top bar is reached vertically. Left belongs to this row,
        // including repeats; keep a focused row's reveal settling in place.
        pendingPageFocus = null
        cancelVerticalFocus(keepFocusedRowReveal = true)
        return true
    }

    fun recordFocused(shelf: Int, item: Int) {
        val returningHeader = retained.headerAction?.let(headerRequesters::get)
        if (returningHeader == null || contentEntry?.preferredReturnRequester !== returningHeader)
            retained.headerAction = null
        if (pendingPageFocus != ShelfFocusPosition(shelf, item)) pendingPageFocus = null
        navigationCursor.onFocused(shelf, item)
        retained.focused = ShelfFocusPosition(shelf, item)
        shelves.getOrNull(shelf)?.let { row ->
            retained.focusAnchor = ShelfFocusAnchor(row.id, row.items.getOrNull(item)?.shelfFocusKey(), item)
        }
        val activated = retained.activatedAnchor
        val returning = activated?.let { resolveShelfFocusAnchor(it, shelves, shelfSizes) }
        if (focusTargetsEnabled && contentVisible && activated != null &&
            (returning == null || returning == ShelfFocusPosition(shelf, item))) {
            retained.activatedAnchor = null
            contentEntry?.preferredReturnRequester = null
        }
        cardWarmup.notifyPrioritiesChanged()
    }

    fun activateCard(shelf: Int, item: Int, track: Track, queue: List<Track>) {
        topEntryRequest = 0
        retained.userNavigated = true
        // Pointer/accessibility activation does not run the Center key preview.
        // Cancel its outstanding reveal just like an actual remote activation.
        cancelVerticalFocus()
        recordFocused(shelf, item)
        retained.activatedAnchor = ShelfFocusAnchor(shelves[shelf].id, track.shelfFocusKey(), item)
        contentEntry?.preferredReturnRequester = itemFocusRequesters[shelf][item]
        onOpenTrack(track, queue)
    }

    LaunchedEffect(retained.activatedAnchor, shelves, focusTargetsEnabled, contentVisible) {
        val anchor = retained.activatedAnchor ?: return@LaunchedEffect
        if (!focusTargetsEnabled || !contentVisible) return@LaunchedEffect
        val target = resolveShelfFocusAnchor(anchor, shelves, shelfSizes) ?: restoredFocus
        val row = columnState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == shelves[target.shelf].id }
        val item = rowStates[target.shelf].layoutInfo.visibleItemsInfo.firstOrNull { it.index == target.item }
        if (row == null) columnState.scrollToItem(target.shelf)
        if (item == null) rowStates[target.shelf].scrollToItem(target.item)
        repeat(3) {
            withFrameNanos { }
            if (retained.activatedAnchor != anchor) return@LaunchedEffect
            runCatching { itemFocusRequesters[target.shelf][target.item].requestFocus() }
        }
    }

    fun shelfBounds(shelfIndex: Int): ShelfRowBounds {
        val row = rowStates[shelfIndex]
        val layout = row.layoutInfo
        val outset = with(density) { homeLayout.focusOutset(shelves[shelfIndex].featured).toPx() }
        fun ShelfRowBounds.withDrawingFootprint() = copy(cards = cards.map {
            it.copy(left = it.left - outset, width = it.width + outset * 2f)
        })
        val measuredWidth = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
        if (measuredWidth > 0f && layout.visibleItemsInfo.isNotEmpty()) {
            return ShelfRowBounds(measuredWidth, layout.visibleItemsInfo.map { item ->
                ShelfCardBounds(item.index, (item.offset - layout.viewportStartOffset).toFloat(), item.size.toFloat())
            }).withDrawingFootprint()
        }
        val shelf = shelves[shelfIndex]
        val viewportWidth = columnState.layoutInfo.viewportSize.width.toFloat()
        return estimateShelfRowBounds(
            shelfSizes[shelfIndex], row.firstVisibleItemIndex, row.firstVisibleItemScrollOffset,
            with(density) { shelf.cardWidth(homeLayout).toPx() },
            with(density) { homeLayout.columnGap(shelf.featured).toPx() },
            with(density) { BrowseTokens.RowHorizontalPadding.toPx() }.coerceAtLeast(outset + 1f), viewportWidth,
        ).withDrawingFootprint()
    }

    fun moveVerticalFocus(direction: Int, includeHeader: Boolean = true): Boolean {
        val from = navigationCursor.requestedPosition
        if (direction < 0 && includeHeader) {
            val shelfId = shelves[from.shelf].id
            val action = headerActions[shelfId]?.firstOrNull()
            val requester = action?.let { headerRequesters[shelfId to it.key] }
            if (requester != null) {
                cancelVerticalFocus()
                focusJob[0] = scope.launch {
                    // The card may be visible while its heading is above the viewport.
                    val row = columnState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == shelfId }
                    if (row == null || row.offset < columnState.layoutInfo.viewportStartOffset)
                        columnState.animateScrollToItem(from.shelf)
                    repeat(3) {
                        withFrameNanos { }
                        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
                    }
                }
                return true
            }
        }
        val sourceCenter = shelfBounds(from.shelf).cards.firstOrNull { it.index == from.item }?.centerX
        fun requestPrevious() = navigationCursor.request(direction, sourceCenter) { target, anchor ->
            nearestVisibleShelfCard(anchor, shelfBounds(target))
        }
        val request = requestPrevious()
        if (request == null) {
            if (direction < 0 && from.shelf == firstEntry.shelf) {
                // The requested cursor reaches row zero before native focus
                // and its reveal do. Repeated Up must finish that same move,
                // not cancel it and send its remaining repeats into the pill.
                if (focusJob[0]?.isActive == true || columnState.isScrollInProgress) return true
                if (columnState.firstVisibleItemIndex != 0 || columnState.firstVisibleItemScrollOffset != 0) {
                    focusJob[0] = scope.launch { columnState.animateScrollToItem(0) }
                    return true
                }
            }
            return false
        }
        cardWarmup.notifyPrioritiesChanged()
        val targetShelf = request.position.shelf
        focusJob[0]?.cancel()
        verticalPlan.value = null
        focusJob[0] = scope.launch {
            val layout = columnState.layoutInfo
            val visible = layout.visibleItemsInfo.firstOrNull { it.key == shelves[targetShelf].id }
            val distance = visible?.let {
                shelfRevealScroll(it.offset, it.size, layout.viewportStartOffset, layout.viewportEndOffset)
            } ?: 0f
            // animateScrollToItem aligns to the top unless the remaining content
            // reaches the end first. Predict that reachable endpoint before the
            // incoming target can report its focus-frame bounds.
            val destinationOffset = if (visible != null) visible.offset - distance else {
                val rowGap = with(density) { homeLayout.shelfGap.toPx() }
                val trailingHeight = (targetShelf until shelves.size).sumOf { index ->
                    val measured = layout.visibleItemsInfo.firstOrNull { it.key == shelves[index].id }?.size?.toFloat()
                    (measured ?: with(density) {
                        homeLayout.headingLineHeight.toPx() + homeLayout.headingGap(shelves[index].featured).toPx() +
                            shelves[index].rowHeight(homeLayout).toPx()
                    }).toDouble()
                }.toFloat() + rowGap * (shelves.lastIndex - targetShelf)
                maxOf(layout.viewportStartOffset.toFloat(),
                    layout.viewportEndOffset - layout.afterContentPadding - trailingHeight)
            }
            val plan = ShelfFocusScroll(targetShelf, destinationOffset)
            verticalPlan.value = plan
            fun tryFocusItem(): Boolean {
                if (!navigationCursor.isCurrent(request)) return false
                val column = columnState.layoutInfo
                val attachedRow = column.visibleItemsInfo.firstOrNull { it.key == shelves[targetShelf].id }
                    ?: return false
                if (attachedRow.offset >= column.viewportEndOffset ||
                    attachedRow.offset + attachedRow.size <= column.viewportStartOffset) return false
                val row = rowStates[targetShelf].layoutInfo
                if (row.visibleItemsInfo.isEmpty() || row.viewportEndOffset <= row.viewportStartOffset) return false
                val item = nearestVisibleShelfCard(request.anchorX, shelfBounds(targetShelf))
                if (!navigationCursor.refine(request, item)) return false
                cardWarmup.notifyPrioritiesChanged()
                if (navigationCursor.focused == request.position) return true
                // A retained LazyListState can still report its old children
                // while their FocusRequesters are detached. Retry on placement.
                return runCatching {
                    itemFocusRequesters[targetShelf][item].requestFocus() &&
                        navigationCursor.focused == request.position
                }.getOrDefault(false)
            }
            try {
                // Visible incoming cards receive focus in the input transaction,
                // before the viewport moves. Offscreen cards receive it on their
                // first attached frame while reveal is still running.
                var focused = tryFocusItem()
                val reveal = launch(start = CoroutineStart.UNDISPATCHED) {
                    if (visible == null) columnState.animateScrollToItem(targetShelf)
                    else if (kotlin.math.abs(distance) > 1f) {
                        columnState.animateScrollBy(distance, tween(260, easing = MotionTokens.KaraokeLineEasing))
                    }
                }
                var placementFramesAfterReveal = 0
                while (!focused && navigationCursor.isCurrent(request) &&
                    (!reveal.isCompleted || placementFramesAfterReveal < 2)) {
                    withFrameNanos { }
                    focused = tryFocusItem()
                    if (reveal.isCompleted) placementFramesAfterReveal++
                }
                reveal.join()
            } finally {
                if (verticalPlan.value === plan) verticalPlan.value = null
                navigationCursor.complete(request)
                cardWarmup.notifyPrioritiesChanged()
            }
        }
        return true
    }

    LaunchedEffect(topEntryRequest, shelfKey, resourceCounts, focusTargetsEnabled, contentVisible) {
        if (topEntryRequest == 0 || !focusTargetsEnabled || !contentVisible) return@LaunchedEffect
        val target = firstShelfEntry(shelves, shelfSizes) ?: return@LaunchedEffect
        cancelVerticalFocus()
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        focusJob[0] = job
        try {
            columnState.animateScrollToItem(target.shelf)
            rowStates[target.shelf].scrollToItem(target.item)
            repeat(3) {
                withFrameNanos { }
                val acquired = runCatching { itemFocusRequesters[target.shelf][target.item].requestFocus() }.getOrDefault(false)
                if (acquired) {
                    withFrameNanos { }
                    if (currentContentHasFocus && shelfEntryHasFocus(target, shelves, retained.focusAnchor)) {
                        topEntryRequest = 0
                        return@LaunchedEffect
                    }
                }
            }
            // Keep the intent. A source/placement replacement can cancel this
            // worker, but the next provider revision must still enter the top.
        } finally { if (focusJob[0] === job) focusJob[0] = null }
    }

    DisposableEffect(columnState, shelfKey, resourceCounts, focusTargetsEnabled, contentVisible, onRegisterReturnToTop) {
        onRegisterReturnToTop(if (!focusTargetsEnabled || !contentVisible) null else {
            {
                val scrolled = columnState.firstVisibleItemIndex > 0 || columnState.firstVisibleItemScrollOffset > 0
                if (!scrolled) false else {
                    retained.headerAction = null
                    retained.activatedAnchor = null
                    contentEntry?.preferredReturnRequester = null
                    pendingPageFocus = null
                    cancelVerticalFocus()
                    firstShelfEntry(shelves, shelfSizes)?.let { target ->
                        // The host may re-enter while the old card is still
                        // pinned. Never point that handoff back at its old row.
                        contentEntry?.requester = itemFocusRequesters[target.shelf][target.item]
                    }
                    topEntryRequest = if (topEntryRequest == Int.MAX_VALUE) 1 else topEntryRequest + 1
                    true
                }
            }
        })
        onDispose { onRegisterReturnToTop(null) }
    }

    LaunchedEffect(shelfKey, resourceCounts, focusTargetsEnabled, contentVisible, contentHasFocus, topEntryRequest) {
        if (topEntryRequest != 0 || retained.userNavigated || !focusTargetsEnabled || !contentVisible || !contentHasFocus) return@LaunchedEffect
        val target = firstShelfEntry(shelves, shelfSizes) ?: return@LaunchedEffect
        val anchor = retained.focusAnchor
        val firstTrackKey = shelves[target.shelf].items.firstOrNull()?.shelfFocusKey()
        if (anchor?.shelfId == shelves[target.shelf].id && anchor.itemKey == firstTrackKey &&
            columnState.firstVisibleItemIndex == target.shelf && columnState.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
        cancelVerticalFocus()
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        focusJob[0] = job
        try {
            columnState.requestScrollToItem(target.shelf)
            rowStates[target.shelf].requestScrollToItem(0)
            repeat(3) {
                withFrameNanos { }
                if (retained.userNavigated || !currentContentHasFocus) return@LaunchedEffect
                if (runCatching { itemFocusRequesters[target.shelf][0].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        } finally { if (focusJob[0] === job) focusJob[0] = null }
    }

    // Only explicit Up/Down navigation may move the vertical viewport. Focused
    // artwork scales in drawing and must not continuously re-center the column.
    // Benchmark APK only: distinguish genuine scrolling from idle 30fps video.
    // Constant BUILD_TYPE removes this observer entirely from standalone/release.
    if (BuildConfig.BUILD_TYPE == "benchmark" && android.os.Build.VERSION.SDK_INT >= 29) {
        LaunchedEffect(columnState, contentVisible) {
            if (!contentVisible) return@LaunchedEffect
            val cookie = System.identityHashCode(columnState)
            var sectionOpen = false
            try {
                snapshotFlow { columnState.isScrollInProgress }.collect { moving ->
                    if (moving && !sectionOpen) {
                        android.os.Trace.beginAsyncSection("AMATV:home-scroll", cookie)
                        sectionOpen = true
                    } else if (!moving && sectionOpen) {
                        android.os.Trace.endAsyncSection("AMATV:home-scroll", cookie)
                        sectionOpen = false
                    }
                }
            } finally {
                if (sectionOpen) android.os.Trace.endAsyncSection("AMATV:home-scroll", cookie)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides StationaryShelfViewport) {
        LazyColumn(
            state = columnState,
            modifier = Modifier
                .automationId("home-shelves-count-${shelves.size}")
                .fillMaxSize()
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) topEntryRequest = 0
                    if (focusedHeader != null) {
                        val header = focusedHeader
                        retained.userNavigated = true
                        topEntryRequest = 0
                        contentEntry?.preferredReturnRequester = null
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                cancelVerticalFocus()
                                val shelf = shelves.indexOfFirst { it.id == focusedHeader?.first }
                                val item = navigationCursor.focused.item.coerceIn(0, (shelfSizes.getOrNull(shelf)?.minus(1) ?: 0).coerceAtLeast(0))
                                itemFocusRequesters.getOrNull(shelf)?.getOrNull(item)?.let { runCatching { it.requestFocus() } }
                                return@onPreviewKeyEvent true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!moveVerticalFocus(-1, includeHeader = false)) {
                                    cancelVerticalFocus()
                                    focusAboveContent()
                                }
                                return@onPreviewKeyEvent true
                            }
                            leadingKey -> {
                                val firstAction = header?.let { headerActions[it.first]?.firstOrNull() }
                                return@onPreviewKeyEvent firstAction != null && firstAction.key == header?.second && handleLeadingBoundary()
                            }
                            else -> return@onPreviewKeyEvent false
                        }
                    }
                    if (event.nativeKeyEvent.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU)) {
                        retained.activatedAnchor = null
                        retained.headerAction = null
                        contentEntry?.preferredReturnRequester = null
                        topEntryRequest = 0
                        retained.userNavigated = true
                        resolveShelfFocusAnchor(retained.focusAnchor, shelves, shelfSizes)?.let {
                            navigationCursor.updateShelfSizes(shelfSizes)
                            navigationCursor.onFocused(it.shelf, it.item)
                        }
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            pendingPageFocus = null
                            if (!moveVerticalFocus(-1)) {
                                cancelVerticalFocus()
                                focusAboveContent()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            pendingPageFocus = null
                            moveVerticalFocus(1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            pendingPageFocus = null
                            if (event.nativeKeyEvent.keyCode == leadingKey &&
                                navigationCursor.focused.item == 0 && handleLeadingBoundary()) true
                            else {
                                cancelVerticalFocus(keepFocusedRowReveal = true)
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BACK -> {
                            if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_ENTER
                            ) pendingPageFocus = null
                            cancelVerticalFocus()
                            false
                        }
                        else -> false
                    }
                },
            contentPadding = PaddingValues(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(homeLayout.shelfGap),
        ) {
            itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
                Column(Modifier.automationId("home-row-$shelfIndex-count-${shelf.items.size}")) {
                    val reserveClassicEyebrow = shelf.hasClassicEyebrowBand()
                    val actions = headerActions[shelf.id].orEmpty()
                    if (actions.isEmpty()) SectionHeading(shelf.title)
                    else Row(Modifier.fillMaxWidth().padding(end = BrowseTokens.RowHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionHeading(shelf.title, Modifier.weight(1f))
                        actions.forEachIndexed { actionIndex, action ->
                            // Progressive sources can move Replay between headings.
                            // Preserve each button's focus/animation state by identity,
                            // rather than transferring it to the action at this index.
                            key(action.key) {
                                val actionKey = shelf.id to action.key
                                val requester = headerRequesters.getValue(actionKey)
                                ArtistTextAction(label = stringResource(when (action) {
                                    is HomeHeadingAction.Insights -> if (action.link.source == ListeningInsightsSource.Replay) R.string.insights_replay else R.string.insights_show_all
                                    is HomeHeadingAction.Resource -> R.string.insights_show_all
                                    HomeHeadingAction.StationGenres -> R.string.radio_browse_genres
                                }), onClick = {
                                    cancelVerticalFocus()
                                    retained.userNavigated = true
                                    retained.headerAction = actionKey
                                    retained.activatedAnchor = null
                                    contentEntry?.preferredReturnRequester = requester
                                    when (action) {
                                        is HomeHeadingAction.Insights -> onOpenInsights?.invoke(action.link)
                                        is HomeHeadingAction.Resource -> onOpenTrack(action.track, listOf(action.track))
                                        HomeHeadingAction.StationGenres -> onOpenStationGenres?.invoke()
                                    }
                                }, modifier = Modifier.focusRequester(requester)
                                    .automationId("home-shelf-${shelf.id}-${action.key}")
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            focusedHeader = actionKey
                                            pendingPageFocus = null
                                            retained.headerAction = actionKey
                                            val item = if (navigationCursor.focused.shelf == shelfIndex) navigationCursor.focused.item
                                                else rowStates[shelfIndex].firstVisibleItemIndex
                                            val selectedItem = item.coerceIn(0, (shelfSizes[shelfIndex] - 1).coerceAtLeast(0))
                                            navigationCursor.onFocused(shelfIndex, selectedItem)
                                            retained.focusAnchor = ShelfFocusAnchor(shelf.id, shelf.items.getOrNull(selectedItem)?.shelfFocusKey(), selectedItem)
                                            contentEntry?.requester = requester
                                            if (focusTargetsEnabled && contentVisible) contentEntry?.preferredReturnRequester = null
                                        } else if (focusedHeader == actionKey) focusedHeader = null
                                    }.focusProperties {
                                        start = if (actionIndex > 0) headerRequesters.getValue(shelf.id to actions[actionIndex - 1].key)
                                            else if (homeLayout.classic) FocusRequester.Cancel
                                            else navigationFocusRequester ?: FocusRequester.Cancel
                                        end = if (actionIndex < actions.lastIndex) headerRequesters.getValue(shelf.id to actions[actionIndex + 1].key)
                                            else FocusRequester.Cancel
                                    })
                            }
                        }
                    }
                    Spacer(Modifier.height(homeLayout.headingGap(shelf.featured)))
                    val remainingVerticalScroll = remember(shelfIndex, columnState) {
                        derivedStateOf {
                            val offset = columnState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == shelf.id }?.offset
                            if (offset == null) 0f else verticalPlan.value?.remaining(shelfIndex, offset) ?: 0f
                        }
                    }
                    FocusTrackingRow(
                        state = rowStates[shelfIndex],
                        focusVisualOutset = homeLayout.focusOutset(shelf.featured),
                        verticalScrollOffset = remainingVerticalScroll,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(shelf.rowHeight(homeLayout))
                            .focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(homeLayout.columnGap(shelf.featured)),
                        contentPadding = PaddingValues(
                            horizontal = BrowseTokens.RowHorizontalPadding,
                            vertical = BrowseTokens.RowVerticalPadding,
                        ),
                    ) {
                        itemsIndexed(
                            shelf.items,
                            key = { _, track -> "${shelf.id}:${track.resourceType}:${track.resourceId ?: track.id}" },
                        ) { itemIndex, track ->
                            val focusModifier = Modifier
                                .automationId("shelf-$shelfIndex-item-$itemIndex-${track.catalogKind.name.lowercase()}")
                                .focusRequester(itemFocusRequesters[shelfIndex][itemIndex])
                                .then(if (firstContentFocusRequester != null && firstEntry == ShelfFocusPosition(shelfIndex, itemIndex))
                                    Modifier.focusRequester(firstContentFocusRequester) else Modifier)
                                .focusProperties {
                                    if (itemIndex == 0) start = if (homeLayout.classic) FocusRequester.Cancel
                                        else navigationFocusRequester ?: FocusRequester.Cancel
                                    if (itemIndex == shelf.items.lastIndex &&
                                        pagination[shelf.id]?.showAction != true
                                    ) end = FocusRequester.Cancel
                                }
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        recordFocused(shelfIndex, itemIndex)
                                    }
                                }
                            val mountPriority = {
                                val requested = navigationCursor.requestedPosition
                                cardWarmup.priority(
                                    "${shelf.id}:${track.resourceType}:${track.resourceId ?: track.id}",
                                    shelfIndex == requested.shelf && itemIndex == requested.item,
                                )
                            }
                            if (shelf.id == BrowseInlineSection.Genres.shelfId) {
                                InlineGenreCard(track, { activateCard(shelfIndex, itemIndex, track, shelf.items) }, focusModifier)
                            } else if (shelf.featured) {
                                TopPick(
                                    label = track.eyebrow(),
                                    track = track,
                                    onClick = { activateCard(shelfIndex, itemIndex, track, shelf.items) },
                                    focusModifier = focusModifier,
                                    warmup = cardWarmup,
                                    mountPriority = mountPriority,
                                    reserveClassicEyebrow = reserveClassicEyebrow,
                                )
                            } else {
                                RecentCard(
                                    track = track,
                                    onClick = { activateCard(shelfIndex, itemIndex, track, shelf.items) },
                                    focusModifier = focusModifier,
                                    warmup = cardWarmup,
                                    mountPriority = mountPriority,
                                )
                            }
                        }
                        val page = pagination[shelf.id]
                        if (page?.showAction == true) {
                            // Its key changes after append so the previously focused
                            // action does not drag the viewport to the new row end.
                            item(key = "${shelf.id}:continuation:${shelf.items.size}") {
                                LibraryLoadMoreCard(
                                    pagination = page,
                                    featured = shelf.featured,
                                    compact = shelf.id == BrowseInlineSection.Genres.shelfId,
                                    reserveClassicEyebrow = reserveClassicEyebrow,
                                    onClick = {
                                        if (page.failureReason == dev.amatv.integration.CatalogFailureReason.Authorization) onSignIn()
                                        else if (!page.loading) {
                                            topEntryRequest = 0
                                            pendingPageFocus = ShelfFocusPosition(shelfIndex, shelf.items.size)
                                            onLoadMoreShelf(shelf.id)
                                        }
                                    },
                                    modifier = Modifier
                                        .automationId("shelf-$shelfIndex-load-more")
                                        .focusRequester(itemFocusRequesters[shelfIndex][shelf.items.size])
                                        .then(if (firstContentFocusRequester != null && firstEntry == ShelfFocusPosition(shelfIndex, shelf.items.size))
                                            Modifier.focusRequester(firstContentFocusRequester) else Modifier)
                                        .focusProperties {
                                            if (shelf.items.isEmpty()) start = if (homeLayout.classic) FocusRequester.Cancel
                                                else navigationFocusRequester ?: FocusRequester.Cancel
                                            end = FocusRequester.Cancel
                                        }
                                        .onFocusChanged { state ->
                                            if (state.isFocused) {
                                                recordFocused(shelfIndex, shelf.items.size)
                                                // Track ownership before loading starts too:
                                                // background paging may replace this footer
                                                // without another native focus callback.
                                                pendingPageFocus = ShelfFocusPosition(shelfIndex, shelf.items.size)
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryLoadMoreCard(
    pagination: LibraryShelfPagination,
    featured: Boolean,
    compact: Boolean = false,
    reserveClassicEyebrow: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeLayout = homeShelfLayout()
    val label = stringResource(when {
        pagination.loading -> R.string.browse_loading
        pagination.failed -> R.string.browse_load_more_retry
        pagination.empty -> R.string.browse_reload
        else -> R.string.browse_load_more
    })
    val cardHeight = when {
        compact -> 76.dp
        featured -> homeLayout.showcaseHeight - if (homeLayout.classic && !reserveClassicEyebrow) 24.dp else 0.dp
        else -> homeLayout.cardHeight
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .width(if (compact) 190.dp else if (featured) homeLayout.showcaseWidth else homeLayout.cardWidth)
            .height(cardHeight)
            .semantics { stateDescription = label },
        cornerRadius = if (featured) homeLayout.showcaseRadius else homeLayout.cardRadius,
        focusedScale = if (featured) homeLayout.showcaseFocusScale else homeLayout.cardFocusScale,
        focusedLift = 2.dp,
        background = if (homeLayout.classic) Color.White.copy(alpha = 0.06f) else BrowseTokens.Surface,
        focusedBackground = if (homeLayout.classic) Color.White.copy(alpha = 0.14f) else Color(0xFF303033),
        padding = PaddingValues(16.dp),
    ) {
        if (compact) Row(
            Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pagination.loading) AppleLoadingIndicator()
            else Text("+", color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp)
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pagination.loading) AppleLoadingIndicator()
            else Text("+", color = Color.White.copy(alpha = 0.75f), fontSize = 38.sp)
            Spacer(Modifier.height(16.dp))
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            if (pagination.failed) {
                Text(
                    stringResource(R.string.browse_load_more_error),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    val homeLayout = homeShelfLayout()
    Text(
        text = text,
        color = Color.White.copy(alpha = if (homeLayout.classic) 0.66f else 0.94f),
        fontSize = homeLayout.headingSize,
        lineHeight = homeLayout.headingLineHeight,
        fontWeight = if (homeLayout.classic) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = homeLayout.headingStart),
    )
}


@Composable
private fun rememberHomeCardMount(
    warmup: HomeCardWarmup?,
    key: Any,
    priority: () -> Int,
): State<Boolean> {
    val ready = remember(warmup, key) { mutableStateOf(warmup == null || warmup.wasMounted(key)) }
    val currentPriority = rememberUpdatedState(priority)
    if (warmup != null) {
        DisposableEffect(warmup, key) {
            val ticket = if (warmup.wasMounted(key)) {
                ready.value = true
                null
            } else warmup.request({ currentPriority.value() }) {
                warmup.rememberMounted(key)
                ready.value = true
            }
            onDispose {
                ticket?.let(warmup::cancel)
                // Returning content keeps its permit. Unmounted/reused cells
                // still join the first-visit budget under their new identity.
                ready.value = warmup.wasMounted(key)
            }
        }
    }
    return ready
}

@Composable
private fun Modifier.homeCardSemantics(track: Track, ready: State<Boolean>, staged: Boolean): Modifier {
    val loadedLabel = stringResource(R.string.browse_loaded)
    val loadingLabel = stringResource(R.string.browse_loading)
    return if (!staged) this else semantics {
        contentDescription = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(", ")
        stateDescription = if (ready.value) loadedLabel else loadingLabel
    }
}

/** Only this small restart scope reads the permit, never the focus shell or row. */
@Composable
private fun HomeCardContent(
    ready: State<Boolean>,
    track: Track,
    featured: Boolean,
    staged: Boolean,
    shadow: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(if (staged) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
        if (!staged || ready.value) {
            // Artwork already blends each new image over an opaque poster.
            // Keep the card/labels present when its mounting permit arrives.
            shadow()
            CachedHomeCard(content)
        } else HomeCardPlaceholder(track, featured)
    }
}

/** Cache the exact foreground raster while its ancestor moves; the shadow stays separate. */
@Composable
private fun CachedHomeCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        // Auto can replay every image/gradient/label during scrolling on a
        // 32-bit TV. Retain the completed card instead, at its existing size.
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }) { content() }
}

/** Resolve a visible playlist's paired still before drawing its baked-in title. */
@Composable
private fun rememberHomePlaylistArtwork(track: Track, mounted: State<Boolean>): EditorialArtworkPresentation {
    if (track.catalogKind != CatalogKind.Playlist) return EditorialArtworkPresentation(track, true)
    val presentation = rememberEditorialArtworkPresentation(track,
        mounted.value && LocalBrowseContentVisible.current &&
            androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused)
    return remember(presentation) { presentation.copy(track = presentation.track.forHomePlaylistArtwork()) }
}

@Composable
private fun HomeArtworkLayer(presentation: EditorialArtworkPresentation, modifier: Modifier,
    animate: Boolean, cornerRadius: androidx.compose.ui.unit.Dp, aspect: Float = 1f) {
    if (presentation.ready) MotionArtwork(presentation.track, modifier, animate = animate,
        cornerRadius = cornerRadius, artworkAspectRatio = aspect,
        resolveEditorialArtwork = presentation.track.catalogKind != CatalogKind.Playlist)
    else Box(modifier.clip(RoundedCornerShape(cornerRadius)).background(Brush.linearGradient(presentation.track.palette)))
}

/** No font shaping or image request before the grant; cached covers remain visible. */
@Composable
internal fun HomeCardPlaceholder(track: Track, featured: Boolean) {
    if (LocalInterfaceStyle.current == InterfaceStyle.Classic) {
        ClassicHomeCardPlaceholder(track, featured)
        return
    }
    val context = LocalContext.current
    val portrait = featured && track.catalogKind != CatalogKind.Playlist && track.motionForAspect(
        BrowseTokens.ShowcaseWidth.value / BrowseTokens.ShowcaseHeight.value,
    )?.aspectRatio?.let { it < 0.9f } == true
    val url = track.posterForAspect(if (portrait)
        BrowseTokens.ShowcaseWidth.value / BrowseTokens.ShowcaseHeight.value else 1f)
    val matchingPosterKnown = track.catalogKind != CatalogKind.Playlist || track.motionForAspect(1f)?.previewUrl != null
    val cached = remember(url, matchingPosterKnown) { if (matchingPosterKnown) ArtworkImages.cachedImage(context, url)?.asPainter(context) else null }
    Box(Modifier.fillMaxSize()) {
        if (cached != null) Image(
            painter = cached,
            contentDescription = null,
            contentScale = if (track.catalogKind == CatalogKind.Station) ContentScale.Fit else ContentScale.Crop,
            modifier = (if (portrait) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(1f))
                .clip(RoundedCornerShape(if (featured) BrowseTokens.ShowcaseRadius else BrowseTokens.CardRadius)),
        )
        Canvas(Modifier.fillMaxSize()) {
            val coverHeight = if (featured) size.height else BrowseTokens.CardWidth.toPx()
            val radius = (if (featured) BrowseTokens.ShowcaseRadius else BrowseTokens.CardRadius).toPx()
            if (cached == null) drawRoundRect(
                color = track.palette.first(),
                size = Size(size.width, coverHeight),
                cornerRadius = CornerRadius(radius),
            )
            if (featured) drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.86f),
                ),
                cornerRadius = CornerRadius(radius),
            )
            val textX = if (featured) 14.dp.toPx() else 0f
            val titleY = if (featured) size.height - 43.dp.toPx() else coverHeight + 9.dp.toPx()
            val titleWidth = size.width * (if (featured) 0.72f else 0.84f)
            if (featured) drawRoundRect(
                Color.White.copy(alpha = 0.12f), Offset(textX, titleY - 17.dp.toPx()),
                Size(titleWidth * 0.65f, 5.dp.toPx()), CornerRadius(2.5.dp.toPx()),
            )
            drawRoundRect(
                Color.White.copy(alpha = 0.18f), Offset(textX, titleY),
                Size(titleWidth, 7.dp.toPx()), CornerRadius(3.dp.toPx()),
            )
            drawRoundRect(
                Color.White.copy(alpha = 0.09f), Offset(textX, titleY + 18.dp.toPx()),
                Size(titleWidth * 0.72f, 5.dp.toPx()), CornerRadius(2.5.dp.toPx()),
            )
        }
    }
}

/** Original card proportions, with the same cached-poster fallback as Apple. */
@Composable
private fun ClassicHomeCardPlaceholder(track: Track, featured: Boolean) {
    val context = LocalContext.current
    val url = track.posterForAspect(1f)
    val matchingPosterKnown = track.catalogKind != CatalogKind.Playlist || track.motionForAspect(1f)?.previewUrl != null
    val cached = remember(url, matchingPosterKnown) { if (matchingPosterKnown) ArtworkImages.cachedImage(context, url)?.asPainter(context) else null }
    val coverHeight = if (featured) 184.dp else ClassicBrowseVisuals.CardWidth
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(ClassicBrowseVisuals.CardRadius))) {
        Canvas(Modifier.fillMaxSize()) {
            val imageHeight = coverHeight.toPx()
            if (featured) drawRect(track.palette.last().copy(alpha = 0.58f))
            if (cached == null) drawRoundRect(
                color = track.palette.first(), size = Size(size.width, imageHeight),
                cornerRadius = if (featured) CornerRadius.Zero else CornerRadius(ClassicBrowseVisuals.CardRadius.toPx()),
            )
            val titleWidth = size.width * 0.64f
            val artistWidth = size.width * 0.42f
            val titleY = imageHeight + (if (featured) 20.dp else 13.dp).toPx()
            val titleX = if (featured) (size.width - titleWidth) / 2f else 0f
            val artistX = if (featured) (size.width - artistWidth) / 2f else 0f
            drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(titleX, titleY),
                Size(titleWidth, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = 0.07f), Offset(artistX, titleY + 16.dp.toPx()),
                Size(artistWidth, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
        if (cached != null) Image(
            painter = cached, contentDescription = null,
            contentScale = if (track.catalogKind == CatalogKind.Station) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(coverHeight)
                .clip(RoundedCornerShape(if (featured) 0.dp else ClassicBrowseVisuals.CardRadius)),
        )
    }
}

/** Classic editorial layout shares the mounting, actions and motion lifecycle. */
@Composable
private fun ClassicTopPick(
    label: String,
    track: Track,
    onClick: () -> Unit,
    focusModifier: Modifier,
    warmup: HomeCardWarmup?,
    mountPriority: () -> Int,
    reserveClassicEyebrow: Boolean,
) {
    val mountKey = remember(track.id, track.catalogKind, track.catalogId, track.resourceType, track.resourceId) {
        track.homeCardMountKey()
    }
    val contentReady = rememberHomeCardMount(warmup, mountKey, mountPriority)
    val backdrop = LocalBrowseBackdropController.current
    val actionMenu = catalogMenuFor(track)
    val genericEyebrows = listOf(
        stringResource(R.string.browse_kind_song), stringResource(R.string.browse_kind_album),
        stringResource(R.string.browse_kind_playlist), "Song", "Album", "Playlist",
    )
    val distinctEyebrow = label.takeIf {
        it.isNotBlank() && !it.equals(track.artist, ignoreCase = true) &&
            genericEyebrows.none { generic -> it.equals(generic, ignoreCase = true) }
    }.orEmpty()
    Column(Modifier.width(ClassicBrowseVisuals.ShowcaseWidth)
        .height(ClassicBrowseVisuals.ShowcaseHeight - if (reserveClassicEyebrow) 0.dp else 24.dp)) {
        if (reserveClassicEyebrow) Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.TopStart) {
            if (contentReady.value) Text(distinctEyebrow,
                color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FocusSurface(
            onClick = onClick,
            onLongClick = actionMenu?.let { open -> { open(track) } },
            onLongClickLabel = stringResource(R.string.actions_menu),
            deferIdleVisuals = warmup != null, stableGeometry = true,
            modifier = focusModifier.catalogMenuKey(track)
                .onFocusChanged { if (it.isFocused) backdrop?.select(track) }
                .homeCardSemantics(track, contentReady, warmup != null)
                .fillMaxWidth().height(256.dp),
            cornerRadius = ClassicBrowseVisuals.ShowcaseRadius,
            focusedScale = ClassicBrowseVisuals.ShowcaseFocusScale, focusedLift = ClassicBrowseVisuals.CardLift,
            restingElevation = 0.dp, focusedElevation = 0.dp, showBorder = false,
            background = Color.Transparent, focusedBackground = Color.Transparent,
        ) { focused ->
            HomeCardContent(contentReady, track, featured = true, staged = warmup != null,
                shadow = { Box(Modifier.fillMaxSize().coverShadow(focused, ClassicBrowseVisuals.ShowcaseRadius, isolated = true)) }) {
                val artwork = rememberHomePlaylistArtwork(track, contentReady)
                Column(Modifier.fillMaxSize().clip(RoundedCornerShape(ClassicBrowseVisuals.CardRadius))
                    .background(track.palette.last().copy(alpha = 0.58f))) {
                    Box(Modifier.weight(1f)) {
                        HomeArtworkLayer(artwork, Modifier.fillMaxSize(),
                            animate = rememberBrowseMotionReady(focused), cornerRadius = 0.dp,
                            aspect = 1f)
                    }
                    Column(Modifier.fillMaxWidth().height(72.dp)
                        .background(Brush.verticalGradient(listOf(
                            Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.32f))))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        TrackTitle(track, color = Color.White.copy(alpha = 0.96f),
                            fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (track.artist.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(track.artist, color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopPick(
    label: String,
    track: Track,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
    warmup: HomeCardWarmup? = null,
    mountPriority: () -> Int = { 1 },
    reserveClassicEyebrow: Boolean = true,
) {
    if (LocalInterfaceStyle.current == InterfaceStyle.Classic) {
        ClassicTopPick(label, track, onClick, focusModifier, warmup, mountPriority, reserveClassicEyebrow)
        return
    }
    val mountKey = remember(track.id, track.catalogKind, track.catalogId, track.resourceType, track.resourceId) {
        track.homeCardMountKey()
    }
    val contentReady = rememberHomeCardMount(warmup, mountKey, mountPriority)
    val backdrop = LocalBrowseBackdropController.current
    val actionMenu = catalogMenuFor(track)
    val portraitAspect = BrowseTokens.ShowcaseWidth.value / BrowseTokens.ShowcaseHeight.value
    FocusSurface(
        onClick = onClick,
        onLongClick = actionMenu?.let { open -> { open(track) } },
        onLongClickLabel = stringResource(R.string.actions_menu),
        deferIdleVisuals = warmup != null,
        stableGeometry = true,
        modifier = focusModifier.catalogMenuKey(track).onFocusChanged { if (it.isFocused) backdrop?.select(track) }
            .homeCardSemantics(track, contentReady, warmup != null)
            .width(BrowseTokens.ShowcaseWidth)
            .height(BrowseTokens.ShowcaseHeight),
        cornerRadius = BrowseTokens.ShowcaseRadius,
        focusedScale = BrowseTokens.ShowcaseFocusScale,
        focusedLift = 1.dp,
        restingElevation = 0.dp,
        focusedElevation = 0.dp,
        showBorder = false,
        background = Color.Transparent,
        focusedBackground = Color.Transparent,
    ) { focused ->
        HomeCardContent(contentReady, track, featured = true, staged = warmup != null,
            shadow = { Box(Modifier.fillMaxSize().coverShadow(focused, BrowseTokens.ShowcaseRadius, isolated = true)) }) {
            val motionReady = rememberBrowseMotionReady(focused)
            val playlistArtwork = rememberHomePlaylistArtwork(track, contentReady)
            val presentationTrack = if (track.catalogKind == CatalogKind.Playlist) playlistArtwork.track else rememberEditorialArtwork(track, motionReady &&
                android.animation.ValueAnimator.areAnimatorsEnabled() && LocalFocusTargetsEnabled.current &&
                androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused)
            val hasPortraitArtwork = track.catalogKind != CatalogKind.Playlist && presentationTrack.motionForAspect(portraitAspect)?.aspectRatio?.let { it < 0.9f } == true
            val shape = RoundedCornerShape(BrowseTokens.ShowcaseRadius)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .coverHairline(BrowseTokens.ShowcaseRadius)
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.24f)),
            ) {
                HomeArtworkLayer(
                    presentation = playlistArtwork.copy(track = presentationTrack),
                    // A square catalog cover often includes typography. Keep
                    // that image complete; only genuine portrait artwork may
                    // fill this tall editorial card.
                    modifier = if (hasPortraitArtwork) Modifier.fillMaxSize()
                        else Modifier.fillMaxWidth().aspectRatio(1f).align(Alignment.TopCenter),
                    animate = motionReady,
                    cornerRadius = if (hasPortraitArtwork) BrowseTokens.ShowcaseRadius else 0.dp,
                    aspect = if (hasPortraitArtwork) portraitAspect else 1f,
                )
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.4f to Color.Transparent,
                        0.68f to Color.Black.copy(alpha = 0.12f),
                        1f to Color.Black.copy(alpha = 0.46f),
                    ),
                ))
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (label.isNotBlank() && !label.equals(track.artist, ignoreCase = true)) {
                        Text(
                            text = label,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TrackTitle(
                        track = track,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track.artist.isNotBlank()) {
                        Text(
                            text = track.artist,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(
    track: Track,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
    warmup: HomeCardWarmup? = null,
    mountPriority: () -> Int = { 1 },
) {
    val homeLayout = homeShelfLayout()
    val mountKey = remember(track.id, track.catalogKind, track.catalogId, track.resourceType, track.resourceId) {
        track.homeCardMountKey()
    }
    val contentReady = rememberHomeCardMount(warmup, mountKey, mountPriority)
    val backdrop = LocalBrowseBackdropController.current
    val actionMenu = catalogMenuFor(track)
    FocusSurface(
        onClick = onClick,
        onLongClick = actionMenu?.let { open -> { open(track) } },
        onLongClickLabel = stringResource(R.string.actions_menu),
        deferIdleVisuals = warmup != null,
        stableGeometry = true,
        modifier = focusModifier.catalogMenuKey(track).onFocusChanged { if (it.isFocused) backdrop?.select(track) }.homeCardSemantics(track, contentReady, warmup != null)
            .width(homeLayout.cardWidth).height(homeLayout.cardHeight),
        cornerRadius = homeLayout.cardRadius,
        focusedScale = homeLayout.cardFocusScale,
        focusedLift = if (homeLayout.classic) ClassicBrowseVisuals.CardLift else 1.dp,
        restingElevation = 0.dp,
        focusedElevation = 0.dp,
        showBorder = false,
        background = Color.Transparent,
        focusedBackground = Color.Transparent,
    ) { focused ->
        HomeCardContent(contentReady, track, featured = false, staged = warmup != null,
            shadow = { Box(Modifier.size(homeLayout.cardWidth).coverShadow(focused, homeLayout.cardRadius, isolated = true)) }) {
            val artwork = rememberHomePlaylistArtwork(track, contentReady)
            Column {
                Box(Modifier.size(homeLayout.cardWidth)
                    .coverHairline(homeLayout.cardRadius)) {
                    HomeArtworkLayer(
                        presentation = artwork,
                        modifier = Modifier.fillMaxSize(),
                        animate = rememberBrowseMotionReady(focused),
                        cornerRadius = homeLayout.cardRadius,
                    )
                }
                TrackTitle(
                    track = track,
                    color = Color.White.copy(alpha = if (homeLayout.classic) 0.92f else 0.96f),
                    fontSize = if (homeLayout.classic) ClassicBrowseVisuals.CardTitleSize else 12.sp,
                    lineHeight = if (homeLayout.classic) 20.sp else 16.sp,
                    fontWeight = if (homeLayout.classic) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (homeLayout.classic) 9.dp else 7.dp),
                )
                Text(
                    text = track.artist,
                    color = Color.White.copy(alpha = if (homeLayout.classic) 0.56f else 0.58f),
                    fontSize = if (homeLayout.classic) ClassicBrowseVisuals.CardSubtitleSize else 11.sp,
                    lineHeight = if (homeLayout.classic) 18.sp else 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (homeLayout.classic) 1.dp else 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Track.eyebrow(): String = when (catalogKind) {
    CatalogKind.Song -> album.takeIf { it.isNotBlank() && it != "Song" } ?: stringResource(R.string.browse_kind_song)
    CatalogKind.Album -> stringResource(R.string.browse_kind_album)
    CatalogKind.Playlist -> artist.takeIf { it.isNotBlank() } ?: stringResource(R.string.browse_kind_playlist)
    CatalogKind.Station -> stringResource(R.string.browse_kind_station)
    CatalogKind.MusicVideo -> stringResource(R.string.browse_kind_music_video)
    CatalogKind.Artist -> stringResource(R.string.library_artists)
    CatalogKind.Page -> stringResource(R.string.nav_browse)
}
