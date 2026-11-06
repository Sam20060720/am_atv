package dev.amatv.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.amatv.integration.PhoneMusicAuthorization
import dev.amatv.integration.PhoneMusicAuthStatus
import dev.amatv.integration.PhoneMusicAuthorizationExpired
import dev.amatv.integration.withEditorialArtwork
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.CatalogResourceProfile
import dev.amatv.model.CatalogResourceSection
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.CatalogResourceLink
import dev.amatv.model.ArtistProfile
import dev.amatv.model.ArtistSection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.i18n.destinationLabel
import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicServices
import dev.amatv.integration.AuthLaunchGate
import dev.amatv.integration.retryCatalogRequest
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.RadioCatalogSection
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.integration.GoogleTvPairing
import dev.amatv.integration.PlaybackPreferences
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.LyricsRequestResult
import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.integration.requestLyricsWithRetry
import dev.amatv.integration.PlaybackFailureKind
import dev.amatv.integration.NativeResumeRecovery
import dev.amatv.integration.classifyPlaybackFailure
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.integration.PlaybackMediaSession
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogChartCategory
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.openAction
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import dev.amatv.playback.ContextKind
import dev.amatv.playback.EntryOrigin
import dev.amatv.playback.PlaybackContext
import dev.amatv.playback.PlaybackQueueController
import dev.amatv.playback.PlaybackRuntimeOwner.PreparationPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

internal enum class LyricsLoadPhase { Idle, Loading, Ready, Unavailable, Failed }

internal data class ActiveLyricsState(
    val trackIdentity: String,
    val lines: List<LyricLine>,
    val resolvedVariant: String? = null,
    val phase: LyricsLoadPhase = LyricsLoadPhase.Idle,
)

/** Catalog identity survives MusicKit's different browse and now-playing wrapper ids. */
internal fun Track.lyricsIdentity(): String = catalogId?.takeIf(String::isNotBlank) ?: id

internal fun lyricsCacheKey(trackIdentity: String, variant: String): String = "$trackIdentity|$variant"

/** Visible baseline text is not proof that a translation/pronunciation variant finished. */
internal fun ActiveLyricsState.resolvedCacheEntries(): Map<String, List<LyricLine>> =
    resolvedVariant?.takeIf { phase == LyricsLoadPhase.Ready && lines.isNotEmpty() }
        ?.let { mapOf(lyricsCacheKey(trackIdentity, it) to lines) }.orEmpty()

private fun Destination.availableBrowseDestination(capabilities: CatalogPresentationCapabilities): Destination =
    takeIf(capabilities::supports) ?: Destination.Home

private fun demoShelves(): Map<Destination, List<ContentShelf>> = mapOf(
    Destination.Home to listOf(
        ContentShelf("demo-picks", AppText.text(R.string.app_demo_top_picks), MockCatalog.tracks, featured = true),
        ContentShelf("demo-recent", AppText.text(R.string.app_recently_played), MockCatalog.tracks.asReversed()),
    ),
    Destination.Browse to listOf(
        ContentShelf("demo-new", AppText.text(R.string.app_demo_new_this_week), MockCatalog.tracks, featured = true),
    ),
    Destination.Library to listOf(
        ContentShelf("demo-library", AppText.text(R.string.app_recently_added), MockCatalog.tracks, featured = true),
    ),
)

@Composable
fun AmAtvApp() {
    val context = LocalContext.current
    // Emulator-only reproduction of late leading sources; no fake content or cache deletion.
    val homeLoadProbe = remember(context) {
        if (dev.amatv.BuildConfig.BUILD_TYPE == "benchmark" && Build.HARDWARE in setOf("ranchu", "goldfish"))
            (context as? android.app.Activity)?.intent else null
    }
    val probeHomeFixture = homeLoadProbe?.getBooleanExtra("dev.amatv.testHomeFixture", false) == true
    val probeColdHome = probeHomeFixture || homeLoadProbe?.getBooleanExtra("dev.amatv.testColdHomeFeed", false) == true
    val probeLeadingDelayMs = homeLoadProbe?.getLongExtra("dev.amatv.testHomePrimaryDelayMs", 0L)?.coerceIn(0, 5_000L) ?: 0L
    val contentLanguageTag = LocalConfiguration.current.locales.toLanguageTags()
    val lyricsLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val scope = rememberCoroutineScope()
    val application = context.applicationContext as dev.amatv.AmAtvApplication
    val interfacePreferences = remember(application) { InterfaceStylePreferences(application) }
    val interfaceStyle by interfacePreferences.selected.collectAsState()
    var interfaceStyleFocusPending by remember { mutableStateOf(false) }
    var interfaceStyleFocusRequest by remember { mutableIntStateOf(0) }
    val runtime = remember(application) { application.playbackRuntime() }
    val services = runtime.services
    val preparation by runtime.preparation.collectAsState()
    val retainedRuntimeSession = remember(runtime) { runtime.hasRetainedSession }
    val retainedSnapshot = remember(runtime) { runtime.snapshot.value }
    val retainedQueue = remember(runtime) { runtime.queue.state.value }
    val capabilities = remember(services) { services.presentationCapabilities }
    val catalogBackdropLayer = rememberGraphicsLayer()
    val catalogAnchorRef = remember { arrayOfNulls<Rect>(1) }
    var catalogActionAnchor by remember { mutableStateOf<Rect?>(null) }
    var catalogLibraryDirty by remember { mutableStateOf(false) }
    var libraryRevision by remember { mutableLongStateOf(0L) }
    var browseRefreshRevision by remember { mutableLongStateOf(0L) }
    val reportCatalogAnchor: (Rect) -> Unit = remember { { catalogAnchorRef[0] = it } }
    var catalogActionItem by remember { mutableStateOf<Track?>(null) }
    var catalogBackHandler by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingCatalogBack by remember { mutableStateOf(false) }
    var catalogQueueActionEntryId by remember { mutableStateOf<Long?>(null) }
    val catalogModalInput = remember { CatalogModalInputGate() }
    var exitConfirmationVisible by remember { mutableStateOf(false) }
    var catalogDetailIntent by remember { mutableStateOf<CatalogDetailIntent?>(null) }
    var catalogDeletedResource by remember { mutableStateOf<CatalogResourceRef?>(null) }
    var catalogModalExitToken by remember { mutableIntStateOf(0) }
    val showCatalogActions: (Track) -> Unit = remember { { item ->
        if (catalogActionItem == null && item.hasCatalogMenu() && capabilities.canOpen(item)) {
            catalogQueueActionEntryId = null
            catalogBackHandler = null
            pendingCatalogBack = false
            catalogModalInput.menuOpened()
            catalogActionAnchor = catalogAnchorRef[0]
            catalogActionItem = item
        }
    } }
    val pendingAuthentication = remember { PendingAuthenticationActions() }
    val playbackSignIn = remember { arrayOfNulls<() -> Unit>(1) }
    var pendingAuthAttempt by remember { mutableStateOf<PendingAuthenticationActions.Attempt?>(null) }
    var authenticatedAction by remember { mutableStateOf<PendingAuthenticationAction?>(null) }
    val cacheSettings by services.contentCache.settings.collectAsState()
    val playbackPrefs = runtime.preferences
    val startupCheckpoint = remember(playbackPrefs) {
        playbackPrefs.restoreCheckpointSession()?.copy(wasPlaying = false)
    }
    var startupSessionLoaded by remember { mutableStateOf(false) }
    var startupCursorEdited by remember { mutableStateOf(false) }
    val startupQueueActions = remember { StartupQueueActions() }
    var destination by remember {
        mutableStateOf((startupCheckpoint?.destination ?: playbackPrefs.activeTab).availableBrowseDestination(capabilities))
    }
    var previousDestination by remember {
        mutableStateOf((startupCheckpoint?.previousDestination ?: playbackPrefs.activeTab).availableBrowseDestination(capabilities))
    }
    var track by remember {
        mutableStateOf(retainedSnapshot?.currentTrack ?: retainedQueue.current?.track
            ?: startupCheckpoint?.queue?.getOrNull(startupCheckpoint.currentIndex) ?: MockCatalog.tracks.first())
    }
    var hasCurrentTrack by remember {
        mutableStateOf(retainedRuntimeSession || startupCheckpoint != null || !services.isCatalogConfigured)
    }
    var positionAnchorMs by remember {
        mutableLongStateOf(if (retainedRuntimeSession) runtime.currentPositionMs()
            else startupCheckpoint?.positionMs ?: if (MockCatalog.isDemoTrack(track)) 7_500L else 0L)
    }
    var playStartedRealtimeMs by remember {
        mutableLongStateOf(android.os.SystemClock.elapsedRealtime())
    }
    // Startup restores metadata and cursor only. An explicit user action owns playback.
    var isPlaying by remember { mutableStateOf(retainedSnapshot?.isPlaying == true && runtime.desiredPlay.value) }
    var playWhenReady by remember { mutableStateOf(retainedRuntimeSession && runtime.desiredPlay.value) }

    val currentView = androidx.compose.ui.platform.LocalView.current
    val shouldKeepScreenOn = isPlaying || playWhenReady
    DisposableEffect(shouldKeepScreenOn) {
        currentView.keepScreenOn = shouldKeepScreenOn
        onDispose {
            currentView.keepScreenOn = false
        }
    }
    var playbackPrepared by remember { mutableStateOf(retainedSnapshot?.isPrepared == true) }
    var hasUserPlaybackRequest by remember { mutableStateOf(retainedRuntimeSession) }
    var playerArtworkSource by remember { mutableStateOf<Track?>(null) }
    var playerArtworkIdentity by remember { mutableStateOf<String?>(null) }
    var playerArtworkScope by remember { mutableStateOf<String?>(null) }
    var lowPerformanceMode by remember { mutableStateOf(playbackPrefs.lowPerformanceMode) }
    val artworkMotionEnabled by services.artworkMotionPreferences.enabled.collectAsState()
    var playbackMessage by remember { mutableStateOf<String?>(null) }
    var waitingQueueEntryId by remember { mutableStateOf<Long?>(null) }
    var lastPlaybackFailure by remember { mutableStateOf<PlaybackFailureKind?>(null) }
    var failureHandledSelection by remember { mutableLongStateOf(-1L) }
    val nativeResumeRecovery = remember { NativeResumeRecovery() }
    var connectionState by remember { mutableStateOf(services.connectionState) }
    var serviceMessage by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    val contentAccountScope = remember(services, connectionState, reloadNonce) { services.authorizationCacheScope() }
    var shelvesLanguageTag by remember { mutableStateOf(contentLanguageTag) }
    var shelvesByDestination by remember {
        mutableStateOf<Map<Destination, List<ContentShelf>>>(
            if (services.isCatalogConfigured) {
                emptyMap()
            } else {
                demoShelves()
            },
        )
    }
    val feedCache = remember(services) { dev.amatv.integration.CatalogFeedCache(services.contentCache) }
    var feedSuccessTimes by remember { mutableStateOf<Map<Destination, Long>>(emptyMap()) }
    var feedAttemptTimes by remember { mutableStateOf<Map<Destination, Long>>(emptyMap()) }
    var feedSnapshotTimes by remember { mutableStateOf<Map<Destination, Long>>(emptyMap()) }
    var forcedFeedRefresh by remember { mutableStateOf<Set<Destination>>(emptySet()) }
    var feedRefreshTick by remember { mutableIntStateOf(0) }
    var rootFeedVisible by remember { mutableStateOf(true) }
    var feedNotices by remember { mutableStateOf<Map<Destination, Int>>(emptyMap()) }
    var feedFailureReasons by remember { mutableStateOf<Map<Destination, dev.amatv.integration.CatalogFailureReason>>(emptyMap()) }
    val feedRequestEpoch = remember { java.util.concurrent.atomic.AtomicLong() }
    val homeFeedLoader = remember { dev.amatv.integration.HomeFeedLoader() }
    var homeFeedSnapshot by remember { mutableStateOf<dev.amatv.integration.HomeFeedSnapshot?>(null) }
    var radioRefreshFinishedAt by remember { mutableLongStateOf(0L) }
    var radioRefreshComplete by remember { mutableStateOf(false) }
    var radioRecentPlaybackId by remember { mutableStateOf<String?>(null) }
    var loadingDestinations by remember {
        mutableStateOf(
            if (services.isCatalogConfigured) setOf(Destination.Home) else emptySet(),
        )
    }
    var libraryNextPages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var libraryTotals by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var libraryVisitedPages by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var libraryLoadingShelves by remember { mutableStateOf<Set<String>>(emptySet()) }
    var libraryFailedShelves by remember { mutableStateOf<Set<String>>(emptySet()) }
    var libraryFailureReasons by remember {
        mutableStateOf<Map<String, dev.amatv.integration.CatalogFailureReason>>(emptyMap())
    }
    var libraryPagingGeneration by remember { mutableLongStateOf(0L) }
    val libraryPageJobs = remember { mutableMapOf<String, Job>() }
    val libraryShelfGenerations = remember { mutableMapOf<String, Long>() }
    // Only refreshes retain a separate authoritative prefix while old rows remain visible.
    val libraryRefreshItems = remember { mutableMapOf<String, List<Track>>() }
    var recentTracks by remember {
        mutableStateOf(
            if (services.isCatalogConfigured) emptyList() else MockCatalog.tracks.asReversed(),
        )
    }
    val recentTracksMutex = remember { Mutex() }
    var recentTracksPage by remember { mutableStateOf<LibraryPage?>(null) }
    var recentTracksScope by remember { mutableStateOf<String?>(null) }
    var recentTracksLoadedAt by remember { mutableLongStateOf(0L) }
    val recentTracksJob = remember { arrayOfNulls<Job>(1) }
    val currentContentLanguage by rememberUpdatedState(contentLanguageTag)
    var advancingEndedTrackId by remember { mutableStateOf<String?>(null) }
    var ytmPairing by remember { mutableStateOf<GoogleTvPairing?>(null) }
    val ytmAuthorization by services.ytmAuthorizationState.collectAsState()
    val ytmSignedIn = ytmAuthorization.signedIn
    val ytmPairingJob = remember { arrayOfNulls<Job>(1) }
    val ytmPairingEpoch = remember { longArrayOf(0L) }
    DisposableEffect(Unit) {
        onDispose { ytmPairingEpoch[0]++; ytmPairingJob[0]?.cancel() }
    }
    var canSkipNext by remember { mutableStateOf(true) }
    var canSkipPrevious by remember { mutableStateOf(true) }
    var lyricOffsetMs by remember { mutableLongStateOf(playbackPrefs.lyricOffsetMs) }
    var lyricTranslationEnabled by remember {
        mutableStateOf(playbackPrefs.lyricTranslationEnabled)
    }
    var lyricPronunciationEnabled by remember {
        mutableStateOf(playbackPrefs.lyricPronunciationEnabled)
    }
    var lyricPronunciationLarger by remember {
        mutableStateOf(playbackPrefs.lyricPronunciationLarger)
    }
    var lyricTranslationLanguageTag by remember {
        mutableStateOf(playbackPrefs.lyricTranslationLanguageTag)
    }
    var lyricPronunciationLanguageTag by remember {
        mutableStateOf(playbackPrefs.lyricPronunciationLanguageTag)
    }
    val effectiveLyricsLanguageTag = lyricTranslationLanguageTag.ifBlank { lyricsLanguageTag }
    val effectivePronunciationLanguageTag = lyricPronunciationLanguageTag.ifBlank { lyricsLanguageTag }
    val lyricsLocalization = remember(
        effectiveLyricsLanguageTag, effectivePronunciationLanguageTag,
        lyricTranslationEnabled, lyricPronunciationEnabled,
    ) {
        LyricsLocalizationRequest(
            languageTag = effectiveLyricsLanguageTag,
            pronunciationLanguageTag = effectivePronunciationLanguageTag,
            includeTranslations = lyricTranslationEnabled,
            includePronunciations = lyricPronunciationEnabled,
        )
    }
    val lyricsVariant = lyricsLocalization.variantKey
    var nativeCanSeek by remember { mutableStateOf(retainedSnapshot?.canSeek == true) }
    var audioBuffering by remember { mutableStateOf(false) }
    var lyricsByTrackId by remember {
        mutableStateOf<Map<String, List<LyricLine>>>(emptyMap())
    }
    var lyricsLookupFailures by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeLyrics by remember {
        mutableStateOf(
            ActiveLyricsState(
                trackIdentity = track.lyricsIdentity(),
                lines = track.lyrics,
                resolvedVariant = lyricsVariant.takeUnless { lyricsLocalization.enabled },
                phase = if (track.lyrics.isEmpty()) LyricsLoadPhase.Idle else LyricsLoadPhase.Ready,
            ),
        )
    }
    var lyricsRetryNonce by remember { mutableIntStateOf(0) }
    val authLaunchGate = remember { AuthLaunchGate() }
    var phoneSignInVisible by remember { mutableStateOf(false) }
    var phoneSignInPhase by remember { mutableStateOf(PhoneSignInPhase.Starting) }
    var phoneSignInSession by remember { mutableStateOf<PhoneMusicAuthorization?>(null) }
    val phoneSignInJob = remember { arrayOfNulls<Job>(1) }
    val phoneSignInEpoch = remember { longArrayOf(0L) }
    DisposableEffect(Unit) {
        onDispose { phoneSignInEpoch[0]++; phoneSignInJob[0]?.cancel(); phoneSignInSession?.close() }
    }
    var selectionGeneration by remember { mutableLongStateOf(0L) }
    var spectrumPermissionAsked by remember { mutableStateOf(false) }
    var spectrumPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val spectrumPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        spectrumPermissionGranted = it
    }
    // Read only by callbacks/effects: native position polls do not recompose the app.
    val systemPlaybackSnapshot = remember { mutableStateOf<PlaybackSnapshot?>(null) }
    // Position snapshots arrive every 250ms. Continuation only observes the
    // ended transition; subscribing the app root to each full snapshot also
    // recomposes the retained browse/player trees during steady playback.
    val systemPlaybackEnded by remember {
        derivedStateOf { systemPlaybackSnapshot.value?.isEnded }
    }
    lateinit var queueController: PlaybackQueueController

    fun withCachedLyrics(candidate: Track): Track {
        val identity = candidate.lyricsIdentity()
        val cached = lyricsByTrackId[lyricsCacheKey(identity, lyricsVariant)].orEmpty()
        val active = activeLyrics.lines.takeIf {
            activeLyrics.trackIdentity == identity && it.isNotEmpty()
        }.orEmpty()
        val lines = cached.ifEmpty { active }.ifEmpty { candidate.lyrics }
        return if (lines == candidate.lyrics) candidate else candidate.copy(lyrics = lines)
    }

    fun cacheLyrics(identity: String, variant: String, lines: List<LyricLine>) {
        // The persistent TTML store owns history; don't grow TV heap usage all night.
        val key = lyricsCacheKey(identity, variant)
        val recent = (lyricsByTrackId - key) + (key to lines)
        lyricsByTrackId = recent.entries.toList().takeLast(24).associate { it.toPair() }
    }

    fun acceptResolvedTrack(original: Track, resolved: Track): Track {
        if (original.catalogId == resolved.catalogId) return resolved
        val oldIdentity = original.lyricsIdentity()
        val newIdentity = resolved.lyricsIdentity()
        val currentLines = activeLyrics.lines.takeIf { activeLyrics.trackIdentity == oldIdentity }
            ?: original.lyrics
        val updated = resolved.copy(lyrics = currentLines)
        queueController.updateCatalogIdentity(original, updated)
        if (track.lyricsIdentity() == oldIdentity && track.resourceId == original.resourceId) {
            lyricsByTrackId = lyricsByTrackId.entries.associate { (key, lines) ->
                val migrated = if (key.startsWith("$oldIdentity|")) newIdentity + key.removePrefix(oldIdentity) else key
                migrated to lines
            }
            lyricsLookupFailures = lyricsLookupFailures - oldIdentity
            if (activeLyrics.trackIdentity == oldIdentity) {
                activeLyrics = activeLyrics.copy(trackIdentity = newIdentity)
            }
            track = updated
        }
        return updated
    }

    fun resetLibraryPagination(preserveRadio: Boolean = false) {
        fun retained(id: String) = preserveRadio && id in setOf("radio-personal", "radio-recent", "radio-live")
        libraryPagingGeneration++
        libraryPageJobs.values.forEach(Job::cancel)
        libraryPageJobs.clear()
        libraryShelfGenerations.clear()
        libraryRefreshItems.clear()
        libraryNextPages = libraryNextPages.filterKeys(::retained)
        libraryTotals = libraryTotals.filterKeys(::retained)
        libraryVisitedPages = libraryVisitedPages.filterKeys(::retained)
        libraryLoadingShelves = emptySet()
        libraryFailedShelves = libraryFailedShelves.filterTo(mutableSetOf(), ::retained)
        libraryFailureReasons = libraryFailureReasons.filterKeys(::retained)
    }

    suspend fun loadRecentTracks(): LibraryPage? = recentTracksMutex.withLock {
        if (connectionState != MusicConnectionState.SignedIn) return@withLock null
        val requestScope = services.authorizationCacheScope() + ":" + contentLanguageTag
        if (recentTracksScope == requestScope && recentTracksPage != null &&
            SystemClock.elapsedRealtime() - recentTracksLoadedAt < 60_000L) return@withLock recentTracksPage
        val page = retryCatalogRequest({ services.recentlyPlayedTracksPage() })
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (requestScope != services.authorizationCacheScope() + ":" + currentContentLanguage)
            throw CancellationException("Recent history account or language changed")
        recentTracksPage = page
        recentTracksScope = requestScope
        recentTracksLoadedAt = SystemClock.elapsedRealtime()
        recentTracks = capabilities.filterTracks(page.items)
        page
    }

    fun ensureRecentTracks() {
        if (recentTracksJob[0]?.isActive == true) return
        recentTracksJob[0] = scope.launch { cancellableResult { loadRecentTracks() } }
    }

    fun rememberLibraryPage(shelfId: String, page: LibraryPage) {
        page.total?.let { libraryTotals = libraryTotals + (shelfId to it) }
        val visited = libraryVisitedPages[shelfId].orEmpty() + page.requestedPage
        check(page.nextPage == null || page.nextPage !in visited) {
            AppText.text(R.string.services_playlist_page_repeated)
        }
        libraryVisitedPages = libraryVisitedPages + (shelfId to visited)
        libraryNextPages = page.nextPage?.let { libraryNextPages + (shelfId to it) }
            ?: (libraryNextPages - shelfId)
    }

    fun loadLibraryShelf(shelfId: String, firstPage: Boolean) {
        val section = LibrarySection.entries.firstOrNull { it.shelfId == shelfId } ?: return
        val nextPage = if (firstPage) null else libraryNextPages[shelfId]
        if (!firstPage && nextPage == null && shelfId !in libraryFailedShelves) return
        if (connectionState != MusicConnectionState.SignedIn || shelfId in libraryLoadingShelves) return
        val generation = libraryPagingGeneration
        val shelfGeneration = libraryShelfGenerations[shelfId] ?: 0L
        fun isCurrentRequest() = generation == libraryPagingGeneration &&
            shelfGeneration == (libraryShelfGenerations[shelfId] ?: 0L)
        libraryLoadingShelves = libraryLoadingShelves + shelfId
        libraryFailedShelves = libraryFailedShelves - shelfId
        libraryFailureReasons = libraryFailureReasons - shelfId
        libraryPageJobs[shelfId] = scope.launch {
            try {
                val rawPage = retryCatalogRequest({
                    when (section) {
                        LibrarySection.Recent -> services.recentlyAddedPage(nextPage)
                        LibrarySection.Albums -> services.libraryAlbumsPage(nextPage)
                        LibrarySection.Artists -> services.libraryArtistsPage(nextPage)
                        LibrarySection.Playlists -> services.catalogPages.sectionPage(
                            CatalogResourceRef("p.playlistsroot", "library-playlist-folders"),
                            CatalogResourceSection("children", resourceTypes = setOf("library-playlist-folders", "library-playlists"), pageSize = 100), nextPage
                        ).let { LibraryPage(it.items, it.nextPage, it.requestedPage, it.total) }
                        LibrarySection.Songs -> services.librarySongsPage(nextPage)
                    }
                })
                val visible = capabilities.filterTracks(rawPage.items)
                val page = rawPage.copy(items = visible, total = rawPage.total.takeIf { visible.size == rawPage.items.size })
                if (!isCurrentRequest()) return@launch
                if (visible.size != rawPage.items.size) libraryTotals = libraryTotals - shelfId
                if (firstPage) libraryVisitedPages = libraryVisitedPages - shelfId
                check(page.requestedPage !in libraryVisitedPages[shelfId].orEmpty()) {
                    AppText.text(R.string.services_playlist_page_repeated)
                }
                rememberLibraryPage(shelfId, page)
                val existing = shelvesByDestination[Destination.Library].orEmpty()
                val previous = existing.firstOrNull { it.id == shelfId }
                val refreshing = libraryRefreshItems[shelfId]
                val verified = if (firstPage) page.items else mergeLibraryPage(
                    refreshing ?: previous?.items.orEmpty(), page.items)
                val keepFallback = refreshing != null && page.nextPage != null &&
                    verified.size < previous?.items.orEmpty().size
                if (keepFallback) libraryRefreshItems[shelfId] = verified
                else libraryRefreshItems.remove(shelfId)
                val updated = ContentShelf(shelfId, AppText.text(section.label),
                    if (keepFallback) previous?.items.orEmpty() else verified)
                val sections = (existing.filterNot { it.id == shelfId } + updated)
                    .sortedBy { shelf -> LibrarySection.entries.indexOfFirst { it.shelfId == shelf.id } }
                shelvesByDestination = shelvesByDestination + (Destination.Library to sections)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentRequest()) {
                    libraryFailedShelves = libraryFailedShelves + shelfId
                    libraryFailureReasons = libraryFailureReasons + (shelfId to error.catalogUiFailureReason())
                    Log.w("AMATV-Library", "Library page failed (${error.javaClass.simpleName})")
                }
            } finally {
                if (isCurrentRequest()) {
                    libraryLoadingShelves = libraryLoadingShelves - shelfId
                    libraryPageJobs.remove(shelfId)
                }
            }
        }
    }

    fun refreshLoadedLibrary() {
        val loaded = shelvesByDestination[Destination.Library].orEmpty().map { it.id }
        loaded.forEach { shelfId ->
            libraryShelfGenerations[shelfId] = (libraryShelfGenerations[shelfId] ?: 0L) + 1L
            libraryPageJobs.remove(shelfId)?.cancel()
            libraryNextPages = libraryNextPages - shelfId
            libraryTotals = libraryTotals - shelfId
            libraryVisitedPages = libraryVisitedPages - shelfId
            libraryLoadingShelves = libraryLoadingShelves - shelfId
            libraryFailedShelves = libraryFailedShelves - shelfId
            libraryFailureReasons = libraryFailureReasons - shelfId
            libraryRefreshItems[shelfId] = emptyList()
            loadLibraryShelf(shelfId, firstPage = true)
        }
    }

    fun ensureLibrarySection(shelfId: String) {
        val existing = shelvesByDestination[Destination.Library].orEmpty().firstOrNull { it.id == shelfId }
        if (existing == null || (shelfId in libraryFailedShelves && libraryVisitedPages[shelfId].isNullOrEmpty())) {
            loadLibraryShelf(shelfId, firstPage = true)
        }
    }

    fun loadMoreLibraryShelf(shelfId: String) = loadLibraryShelf(shelfId, firstPage = false)

    fun loadMoreRadioShelf(shelfId: String) {
        val section = when (shelfId) {
            "radio-personal" -> RadioCatalogSection.Personal
            "radio-recent" -> RadioCatalogSection.Recent
            "radio-live" -> RadioCatalogSection.Live
            else -> return
        }
        val next = libraryNextPages[shelfId] ?: return
        if (shelfId in libraryLoadingShelves) return
        val generation = libraryPagingGeneration
        libraryLoadingShelves = libraryLoadingShelves + shelfId
        libraryFailedShelves = libraryFailedShelves - shelfId
        libraryFailureReasons = libraryFailureReasons - shelfId
        libraryPageJobs[shelfId] = scope.launch {
            try {
                val page = retryCatalogRequest({ services.radioSectionPage(section, next) })
                if (generation != libraryPagingGeneration) return@launch
                rememberLibraryPage(shelfId, page)
                val updated = shelvesByDestination[Destination.Radio].orEmpty().map { shelf ->
                    if (shelf.id != shelfId) shelf else shelf.copy(
                        items = mergeLibraryPage(shelf.items, capabilities.filterTracks(page.items)))
                }
                shelvesByDestination = shelvesByDestination + (Destination.Radio to updated)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == libraryPagingGeneration) {
                    libraryFailedShelves = libraryFailedShelves + shelfId
                    libraryFailureReasons = libraryFailureReasons + (shelfId to error.catalogUiFailureReason())
                }
            } finally {
                if (generation == libraryPagingGeneration) {
                    libraryLoadingShelves = libraryLoadingShelves - shelfId
                    libraryPageJobs.remove(shelfId)
                }
            }
        }
    }

    fun refreshAllContent() {
        resetLibraryPagination(preserveRadio = true)
        libraryRevision++
        browseRefreshRevision++
        recentTracksLoadedAt = 0L
        connectionState = services.connectionState
        shelvesByDestination = shelvesByDestination.filterKeys { it != Destination.Library }
        val targets = if (destination == Destination.Home || destination == Destination.Browse || destination == Destination.Radio)
            setOf(destination) else setOf(Destination.Home, Destination.Browse)
        forcedFeedRefresh = forcedFeedRefresh + targets
        feedAttemptTimes = feedAttemptTimes - targets
        radioRefreshComplete = false
        loadingDestinations = setOf(destination)
        serviceMessage = null
        lyricsLookupFailures = emptySet()
        if (activeLyrics.phase == LyricsLoadPhase.Failed) {
            activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
        }
        reloadNonce++
    }

    fun appleAuthenticationCompleted() {
        val resumed = pendingAuthAttempt?.let(pendingAuthentication::completeAttempt)
        pendingAuthAttempt = null
        authenticatedAction = resumed
        catalogActionItem = null
        catalogLibraryDirty = false
        catalogBackHandler = null
        pendingCatalogBack = false
        catalogModalExitToken++
        if (resumed is PendingAuthenticationAction.ResumeCurrent) runtime.resetForAuthorizationResume()
        else runtime.resetSession()
        selectionGeneration++
        isPlaying = false
        playWhenReady = false
        playbackPrepared = false
        hasUserPlaybackRequest = false
        playbackMessage = null
        lyricsByTrackId = emptyMap()
        lyricsLookupFailures = emptySet()
        activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
        connectionState = MusicConnectionState.SignedIn
        resetLibraryPagination()
        shelvesByDestination = emptyMap()
        recentTracks = emptyList()
        recentTracksJob[0]?.cancel()
        recentTracksPage = null
        recentTracksScope = null
        recentTracksLoadedAt = 0L
        homeFeedSnapshot = null
        feedNotices = emptyMap()
        feedFailureReasons = emptyMap()
        feedSuccessTimes = emptyMap()
        feedAttemptTimes = emptyMap()
        feedSnapshotTimes = emptyMap()
        val origin = resumed?.origin ?: Destination.Home
        loadingDestinations = setOf(origin)
        destination = origin
        serviceMessage = null
        reloadNonce++
    }

    val webAuthenticationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        authLaunchGate.finish()
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            pendingAuthentication.clear()
            pendingAuthAttempt = null
            catalogModalExitToken++
            serviceMessage = data?.getStringExtra(
                dev.amatv.integration.MusicKitWebAuthActivity.EXTRA_ERROR,
            ) ?: AppText.text(R.string.app_apple_sign_in_cancelled)
        } else {
            runCatching { services.completeWebAuthentication(data) }
                .onSuccess {
                    appleAuthenticationCompleted()
                }
                .onFailure {
                    catalogModalExitToken++
                    serviceMessage = it.message ?: AppText.text(R.string.app_sign_in_failed)
                }
        }
    }

    fun currentPositionMs(): Long {
        if (!isPlaying) return positionAnchorMs
        val elapsed = android.os.SystemClock.elapsedRealtime() - playStartedRealtimeMs
        val advanced = positionAnchorMs + elapsed
        return if (MockCatalog.isDemoTrack(track)) advanced.mod(track.durationMs.coerceAtLeast(1L))
        else if (track.durationMs > 0L) advanced.coerceAtMost(track.durationMs) else advanced
    }

    fun launchNativePlayback(resumePositionMs: Long = 0L) {
        nativeResumeRecovery.cancel()
        hasUserPlaybackRequest = true
        playbackPrepared = false
        nativeCanSeek = false
        playbackMessage = null
        lastPlaybackFailure = null
        runtime.setDesiredPlayIntent(playWhenReady)
        runtime.prepareCurrent(resumePositionMs)
    }

    fun pausePlayback() {
        runtime.setDesiredPlayIntent(false)
        nativeResumeRecovery.cancel()
        positionAnchorMs = currentPositionMs()
        playWhenReady = false
        audioBuffering = false
        if (!MockCatalog.isDemoTrack(track)) services.pause()
        isPlaying = false
    }

    fun requestPlayback() {
        if (services.isCatalogConfigured && queueController.state.value.current == null && startupSessionLoaded) {
            playbackMessage = AppText.text(R.string.app_choose_content_before_playback)
            return
        }
        if (!MockCatalog.isDemoTrack(track) &&
            (connectionState != MusicConnectionState.SignedIn || lastPlaybackFailure == PlaybackFailureKind.Authorization ||
                runtime.preparation.value.failureKind == PlaybackFailureKind.Authorization)) {
            val live = queueController.state.value
            pendingAuthentication.remember(PendingAuthenticationAction.ResumeCurrent(
                track, live.sessionId, live.current?.entryId, currentPositionMs(), destination))
            playbackSignIn[0]?.invoke()
            return
        }
        val continuationQueue = queueController.state.value
        val continuationSnapshot = runtime.snapshot.value
        val endedContinuation = continuationSnapshot?.isEnded == true &&
            (continuationSnapshot.appQueueEntryId == null ||
                continuationSnapshot.appQueueEntryId == continuationQueue.current?.entryId) &&
            continuationSnapshot.currentTrack?.catalogId == track.catalogId &&
            continuationSnapshot.currentTrack?.catalogKind == track.catalogKind
        if (runtime.hasPendingNextIntent || endedContinuation && continuationQueue.contextContinuationPending &&
            (continuationQueue.contextContinuationFailed || waitingQueueEntryId != null)) {
            waitingQueueEntryId = continuationQueue.current?.entryId
            playWhenReady = true
            isPlaying = false
            playbackPrepared = false
            lastPlaybackFailure = null
            playbackMessage = null
            nativeResumeRecovery.cancel()
            if (if (runtime.hasPendingNextIntent) runtime.retryPendingNext()
                else runtime.retryContextContinuation(advanceWhenAvailable = true)) return
            waitingQueueEntryId = null
            playWhenReady = false
            runtime.setDesiredPlayIntent(false)
            return
        }
        runtime.setDesiredPlayIntent(true)
        nativeResumeRecovery.arm(selectionGeneration, track.catalogId, runtime.playbackGeneration,
            positionAnchorMs, prepared = playbackPrepared, paused = !isPlaying && !playWhenReady)
        playWhenReady = true
        if (activeLyrics.trackIdentity == track.lyricsIdentity() &&
            activeLyrics.phase == LyricsLoadPhase.Failed
        ) lyricsRetryNonce++
        if (!startupSessionLoaded && selectionGeneration == 0L) return
        // Play while Next is resolving belongs to the incoming entry, not
        // the old song still used as the queue's continuation anchor.
        if (waitingQueueEntryId != null) return
        if (!MockCatalog.isDemoTrack(track)) {
            val ended = runtime.snapshot.value?.isEnded == true
            if (!runtime.hasPendingPrepare && (lastPlaybackFailure != null || ended ||
                    runtime.preparation.value.phase != PreparationPhase.Ready)) {
                selectionGeneration++
                launchNativePlayback(if (ended) 0L else positionAnchorMs)
            } else if (runtime.playbackGeneration != 0L) services.play()
        } else {
            playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
            isPlaying = true
        }
        playbackMessage = null
    }

    fun togglePlayback() {
        if (playWhenReady) pausePlayback() else requestPlayback()
    }

    fun playFromLyric(positionMs: Long) {
        if (!hasCurrentTrack || track.catalogKind != CatalogKind.Song) return
        val target = positionMs.coerceAtLeast(0L).let { if (track.durationMs > 0L) it.coerceAtMost(track.durationMs) else it }
        nativeResumeRecovery.cancel()
        positionAnchorMs = target
        startupCursorEdited = true
        playStartedRealtimeMs = SystemClock.elapsedRealtime()
        if (!MockCatalog.isDemoTrack(track) &&
            (connectionState != MusicConnectionState.SignedIn || lastPlaybackFailure == PlaybackFailureKind.Authorization ||
                runtime.preparation.value.failureKind == PlaybackFailureKind.Authorization)) {
            val live = queueController.state.value
            pendingAuthentication.remember(PendingAuthenticationAction.ResumeCurrent(
                track, live.sessionId, live.current?.entryId, target, destination))
            playbackSignIn[0]?.invoke()
            return
        }
        playWhenReady = true
        playbackMessage = null
        lastPlaybackFailure = null
        // Full session hydration already preserves startupCursorEdited. Do not
        // start a checkpoint-only queue before that owner has finished restoring.
        if (!startupSessionLoaded && selectionGeneration == 0L && !retainedRuntimeSession) {
            hasUserPlaybackRequest = false
            runtime.setDesiredPlayIntent(true)
            return
        }
        waitingQueueEntryId = null
        hasUserPlaybackRequest = true
        if (MockCatalog.isDemoTrack(track)) {
            runtime.setDesiredPlayIntent(true)
            isPlaying = true
        } else {
            selectionGeneration++
            runCatching { runtime.playFromPosition(target) }
                .onSuccess { started ->
                    playbackPrepared = runtime.snapshot.value?.isPrepared == true
                    audioBuffering = started && !playbackPrepared
                    if (!playbackPrepared) isPlaying = false
                    if (!started) {
                        playWhenReady = false
                        runtime.setDesiredPlayIntent(false)
                        playbackMessage = AppText.text(R.string.app_choose_content_before_playback)
                    }
                }
                .onFailure {
                    playWhenReady = false
                    isPlaying = false
                    runtime.setDesiredPlayIntent(false)
                    playbackMessage = AppText.text(R.string.app_seek_failed)
                }
        }
    }

    fun retryCurrentLyrics() {
        val identity = track.lyricsIdentity()
        lyricsLookupFailures = lyricsLookupFailures - identity
        if (activeLyrics.trackIdentity == identity) {
            activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
        }
        lyricsRetryNonce++
    }

    fun seekPlaybackBy(delta: Long) {
        nativeResumeRecovery.cancel()
        if (track.durationMs <= 0L || (!MockCatalog.isDemoTrack(track) && !nativeCanSeek)) return
        positionAnchorMs = (currentPositionMs() + delta).coerceIn(0L, track.durationMs.coerceAtLeast(0L))
        playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        startupCursorEdited = true
        if (MockCatalog.isDemoTrack(track) || runtime.playbackGeneration == 0L) return
        if (playbackPrepared) {
            runCatching { runtime.seekTo(positionAnchorMs) }
                .onFailure { playbackMessage = AppText.text(R.string.app_seek_failed) }
        } else if (hasUserPlaybackRequest && playWhenReady) {
            selectionGeneration++
            launchNativePlayback(positionAnchorMs)
        }
    }

    fun startTelevisionSignIn() {
        if (connectionState == MusicConnectionState.Demo) {
            serviceMessage = services.setupMessage
            destination = Destination.Account
            return
        }
        if (!authLaunchGate.tryBegin()) return
        pendingAuthAttempt = pendingAuthentication.beginAttempt()
        scope.launch {
            try {
                webAuthenticationLauncher.launch(services.webAuthenticationIntent())
            } catch (cancelled: CancellationException) {
                authLaunchGate.finish()
                throw cancelled
            } catch (error: Exception) {
                authLaunchGate.finish()
                serviceMessage = error.message ?: AppText.text(R.string.app_apple_sign_in_unavailable)
            }
        }
    }

    fun closePhoneSignIn() {
        phoneSignInEpoch[0]++
        phoneSignInJob[0]?.cancel()
        phoneSignInJob[0] = null
        phoneSignInSession?.close()
        phoneSignInSession = null
        if (phoneSignInVisible) catalogModalExitToken++
        phoneSignInVisible = false
        authLaunchGate.finish()
    }

    fun startPhoneSignIn() {
        if (!services.isCatalogConfigured || !authLaunchGate.tryBegin()) return
        pendingAuthAttempt = pendingAuthentication.beginAttempt()
        val epoch = ++phoneSignInEpoch[0]
        phoneSignInVisible = true
        phoneSignInPhase = PhoneSignInPhase.Starting
        phoneSignInJob[0] = scope.launch {
            var session: PhoneMusicAuthorization? = null
            var statusJob: Job? = null
            try {
                // A real TV advertises its LAN address. The emulator-only
                // override allows the same server to be reached through ADB.
                val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
                val launch = (context as? android.app.Activity)?.intent
                val authority = if (emulator) launch?.getStringExtra("dev.amatv.phoneAuthAuthority") else null
                val port = if (emulator) launch?.getIntExtra("dev.amatv.phoneAuthPort", 0) ?: 0 else 0
                val active = services.phoneAuthentication(authority, port)
                session = active
                if (epoch != phoneSignInEpoch[0]) return@launch
                phoneSignInSession = active
                phoneSignInPhase = PhoneSignInPhase.Waiting
                statusJob = launch {
                    active.status.collect { status ->
                        if (epoch == phoneSignInEpoch[0]) when (status) {
                            PhoneMusicAuthStatus.PhoneConnected, PhoneMusicAuthStatus.Authorized ->
                                phoneSignInPhase = PhoneSignInPhase.Authorizing
                            PhoneMusicAuthStatus.Expired -> phoneSignInPhase = PhoneSignInPhase.Expired
                            else -> Unit
                        }
                    }
                }
                val token = active.awaitToken()
                phoneSignInPhase = PhoneSignInPhase.Authorizing
                val candidate = services.validatePhoneAuthentication(token)
                if (epoch != phoneSignInEpoch[0]) return@launch
                // No suspension between account commit and its owner/UI handoff.
                services.completePhoneAuthentication(candidate)
                appleAuthenticationCompleted()
                phoneSignInPhase = PhoneSignInPhase.Complete
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PhoneMusicAuthorizationExpired) {
                if (epoch == phoneSignInEpoch[0]) phoneSignInPhase = PhoneSignInPhase.Expired
            } catch (_: Exception) {
                if (epoch == phoneSignInEpoch[0]) {
                    phoneSignInPhase = PhoneSignInPhase.Failed
                }
            } finally {
                statusJob?.cancel()
                session?.close()
                if (epoch == phoneSignInEpoch[0]) authLaunchGate.finish()
            }
        }
    }

    fun startSignIn() {
        if (connectionState == MusicConnectionState.Demo) {
            serviceMessage = services.setupMessage
            destination = Destination.Account
        } else startPhoneSignIn()
    }

    SideEffect { playbackSignIn[0] = ::startSignIn }

    fun startYtmPairing() {
        if (!services.ytmPairingConfigured) {
            serviceMessage = AppText.text(R.string.app_google_sign_in_unavailable)
            return
        }
        val epoch = ++ytmPairingEpoch[0]
        ytmPairingJob[0]?.cancel()
        ytmPairingJob[0] = scope.launch {
            try {
                val pairing = services.startYtmPairing()
                if (epoch != ytmPairingEpoch[0]) return@launch
                ytmPairing = pairing
                serviceMessage = null
                services.awaitYtmPairing(pairing)
                if (epoch != ytmPairingEpoch[0]) return@launch
                ytmPairing = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (epoch != ytmPairingEpoch[0]) return@launch
                ytmPairing = null
                serviceMessage = error.message ?: AppText.text(R.string.app_google_pairing_failed)
            }
        }
    }

    fun executeTrackPlayback(nextTrack: Track) {
        runtime.setDesiredPlayIntent(playWhenReady)
        waitingQueueEntryId = null
        nativeCanSeek = false
        selectionGeneration++
        if (playWhenReady && !MockCatalog.isDemoTrack(nextTrack) && connectionState != MusicConnectionState.SignedIn) {
            serviceMessage = AppText.text(R.string.app_apple_sign_in_required)
            startSignIn()
            return
        }
        positionAnchorMs = 0L
        val initialTrack = withCachedLyrics(nextTrack)
        val nextIdentity = nextTrack.lyricsIdentity()
        val hasResolvedVariant = lyricsCacheKey(nextIdentity, lyricsVariant) in lyricsByTrackId
        if (activeLyrics.trackIdentity != nextIdentity || activeLyrics.lines != initialTrack.lyrics) {
            activeLyrics = ActiveLyricsState(
                trackIdentity = nextIdentity,
                lines = initialTrack.lyrics,
                resolvedVariant = lyricsVariant.takeIf {
                    hasResolvedVariant || (!lyricsLocalization.enabled && initialTrack.lyrics.isNotEmpty())
                },
                phase = if (initialTrack.lyrics.isEmpty()) LyricsLoadPhase.Idle else LyricsLoadPhase.Ready,
            )
        }
        track = initialTrack
        advancingEndedTrackId = null
        hasCurrentTrack = true
        playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        isPlaying = MockCatalog.isDemoTrack(nextTrack) && playWhenReady
        playbackMessage = null
        playbackPrepared = false

        if (!MockCatalog.isDemoTrack(nextTrack)) {
            // Stop the previous song while authorization/preparation is pending.
            services.pause()
            if (playWhenReady) {
                launchNativePlayback()
            } else {
                lastPlaybackFailure = null
            }
        }
    }

    val latestTrackPlayback by rememberUpdatedState<(Track) -> Unit>(::executeTrackPlayback)
    queueController = runtime.queue
    val queueState by queueController.state.collectAsState()
    val sleepTimerState by runtime.sleepTimer.collectAsState()
    val nativeUpcoming = remember(queueState.current?.entryId, queueState.upNext, queueState.autoplay,
        queueState.repeat, queueState.contextContinuationPending, queueState.deferredLastEntryIds, sleepTimerState.option) {
        runtime.nativeUpcoming(queueState)
    }
    LaunchedEffect(preparation.generation, nativeUpcoming) {
        if (runtime.playbackGeneration != 0L) services.updateNativeUpcoming(runtime.playbackGeneration, nativeUpcoming)
    }
    val timelineEntries = remember(queueState.history, queueState.current, queueState.upNext, queueState.autoplay) {
        queueState.timelineEntries
    }
    val timelineTracks = remember(timelineEntries) { timelineEntries.map { it.track } }
    val timelineEntryIds = remember(timelineEntries) { timelineEntries.map { it.entryId } }

    fun waitForNextQueueEntry(entryId: Long?) {
        if (entryId == null) return
        waitingQueueEntryId = entryId
        // Consume the startup Play gate even when only metadata navigation was
        // replayed. The next entry's callback will honor the latest user intent.
        hasUserPlaybackRequest = true
        positionAnchorMs = currentPositionMs()
        isPlaying = false
        playbackPrepared = false
        services.pauseForReplacement()
        playbackMessage = null
    }

    fun applyQueueAction(action: StartupQueueAction): Boolean {
        if (track.catalogKind == CatalogKind.Station) {
            when (action) {
                StartupQueueAction.Next -> { if (canSkipNext) services.skipToNext(); return true }
                StartupQueueAction.Previous -> { if (canSkipPrevious) services.skipToPrevious(); return true }
                StartupQueueAction.CycleRepeat, StartupQueueAction.ToggleShuffle,
                StartupQueueAction.ToggleInfinite, StartupQueueAction.ToggleSource -> return false
                else -> Unit
            }
        }
        when (action) {
            StartupQueueAction.Next -> {
                val next = runtime.requestNext()
                val live = queueController.state.value
                if (next == null && live.awaitingNext) {
                    waitForNextQueueEntry(live.current?.entryId)
                } else if (next == null) {
                    positionAnchorMs = currentPositionMs()
                    services.pause()
                    isPlaying = false
                    playWhenReady = false
                    runtime.setDesiredPlayIntent(false)
                }
            }
            StartupQueueAction.Previous -> {
                runtime.cancelPendingNext()
                queueController.advancePrevious(currentPositionMs())
            }
            is StartupQueueAction.Select -> {
                if (!queueController.selectTimelineEntry(action.entryId)) return false
            }
            StartupQueueAction.ClearUpcoming -> queueController.clearUpcoming()
            StartupQueueAction.CycleRepeat -> queueController.setRepeat(when (queueController.state.value.repeat) {
                PlaybackRepeatMode.Off -> PlaybackRepeatMode.All
                PlaybackRepeatMode.All -> PlaybackRepeatMode.One
                PlaybackRepeatMode.One -> PlaybackRepeatMode.Off
            })
            StartupQueueAction.ToggleShuffle -> queueController.setShuffle(!queueController.state.value.shuffle)
            StartupQueueAction.ToggleInfinite -> queueController.setInfinite(!queueController.state.value.infinite)
            StartupQueueAction.ToggleSource -> queueController.setAutoplaySource(
                if (queueController.state.value.source == AutoplaySource.YouTubeMusic) AutoplaySource.AppleMusic
                else AutoplaySource.YouTubeMusic,
            )
            is StartupQueueAction.SetSource -> queueController.setAutoplaySource(action.source)
        }
        return true
    }

    fun dispatchQueueAction(action: StartupQueueAction): Boolean =
        if (!startupSessionLoaded && selectionGeneration == 0L) startupQueueActions.enqueue(action)
        else applyQueueAction(action)

    fun supersedeStartupQueueWithNewContext() {
        // A catalog play action deliberately replaces the old rail. Keep pending
        // preference choices, but don't replay old navigation into the new context.
        startupQueueActions.preferencesForNewContext().forEach { applyQueueAction(it) }
    }

    LaunchedEffect(playbackPrefs) {
        if (retainedRuntimeSession) {
            startupSessionLoaded = true
            return@LaunchedEffect
        }
        startupCheckpoint?.let {
            queueController.restoreFromSession(it)
            runtime.restoreMetadataCursor(it)
        }
        val fullSession = cancellableResult {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { playbackPrefs.restoreSession() }
        }.getOrNull()?.copy(wasPlaying = false)
        if (selectionGeneration == 0L && fullSession != null) {
            val cursor = if (startupCursorEdited && track.matchesPlaybackQueueEntry(fullSession.queue[fullSession.currentIndex])) {
                positionAnchorMs
            } else fullSession.positionMs
            val restored = fullSession.copy(positionMs = cursor, wasPlaying = playWhenReady)
            queueController.restoreFromSession(restored)
            runtime.restoreMetadataCursor(restored)
            runtime.restorePendingCollection(restored)
            track = withCachedLyrics(restored.queue[restored.currentIndex])
            hasCurrentTrack = true
            positionAnchorMs = cursor
        }
        if (selectionGeneration == 0L) {
            // Replay metadata-only against the full rail, then honor the latest
            // Play/Pause choice once. No intermediate item prepares native audio.
            startupQueueActions.replayHydrated(
                requestedPlayIntent = playWhenReady,
                setPlayIntent = { playWhenReady = it; runtime.setDesiredPlayIntent(it) },
                apply = ::applyQueueAction,
            )
        } else {
            startupQueueActions.drain()
        }
        if (services.isCatalogConfigured && queueController.state.value.current == null) {
            if (playWhenReady) playbackMessage = AppText.text(R.string.app_choose_content_before_playback)
            playWhenReady = false
            isPlaying = false
            runtime.setDesiredPlayIntent(false)
        }
        startupSessionLoaded = true
    }

    // A Play press during metadata IO is deferred until the full queue/cursor is hydrated.
    LaunchedEffect(startupSessionLoaded, playWhenReady, track.id) {
        if (startupSessionLoaded && playWhenReady && !hasUserPlaybackRequest &&
            waitingQueueEntryId == null && !queueState.awaitingNext && runtime.playbackGeneration == 0L && !runtime.hasPendingPrepare
        ) {
            if (!MockCatalog.isDemoTrack(track)) {
                selectionGeneration++
                launchNativePlayback(positionAnchorMs)
            } else if (!services.isCatalogConfigured) {
                hasUserPlaybackRequest = true
                playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                isPlaying = true
            }
        }
    }
    LaunchedEffect(hasUserPlaybackRequest) {
        if (hasUserPlaybackRequest) queueController.activatePlaybackSession()
    }

    fun playTrack(nextTrack: Track, contextQueue: List<Track> = emptyList(), startStation: Boolean = false) {
        val station = nextTrack.openAction == CatalogOpenAction.Station
        if (startStation && (nextTrack.catalogKind != CatalogKind.Song || nextTrack.catalogId == null)) return
        if ((!station && nextTrack.openAction != CatalogOpenAction.Play) || !capabilities.canOpen(nextTrack)) return
        if (!MockCatalog.isDemoTrack(nextTrack) && connectionState != MusicConnectionState.SignedIn) {
            pendingAuthentication.remember(PendingAuthenticationAction.PlayTrack(nextTrack, contextQueue, destination, startStation))
            serviceMessage = AppText.text(R.string.app_apple_sign_in_required)
            startSignIn()
            return
        }
        pendingAuthentication.clear()
        supersedeStartupQueueWithNewContext()
        ++selectionGeneration
        if (destination != Destination.NowPlaying) previousDestination = destination

        // A station owns an internal MusicKit queue. Keep exactly its anchor
        // in the app queue rather than manufacturing songs or related stations.
        val fullQueue = if (startStation) listOf(withCachedLyrics(nextTrack)) else if (station) {
            canSkipNext = false
            canSkipPrevious = false
            listOf(nextTrack)
        } else if (contextQueue.isNotEmpty()) {
            contextQueue.map(::withCachedLyrics)
        } else if (MockCatalog.isDemoTrack(nextTrack)) {
            MockCatalog.tracks
        } else {
            listOf(withCachedLyrics(nextTrack))
        }

        val trackIndex = fullQueue.indexOfFirst { it.matchesPlaybackQueueEntry(nextTrack) }.coerceAtLeast(0)
        playWhenReady = true
        if (startStation) queueController.playSongStation(fullQueue.first()) else queueController.playContext(
            tracks = fullQueue,
            startIndex = trackIndex,
            context = PlaybackContext(
                kind = ContextKind.SongList,
                id = nextTrack.catalogId,
                title = nextTrack.album,
            ),
        )
        destination = Destination.NowPlaying
    }

    fun playCollection(request: dev.amatv.playback.CollectionPlaybackRequest) {
        val collection = request.collection
        val tracks = request.tracks
        val shuffle = request.shuffle
        val index = request.startIndex
        if (tracks.isEmpty() || index !in tracks.indices) return
        if (connectionState != MusicConnectionState.SignedIn) {
            pendingAuthentication.remember(PendingAuthenticationAction.PlayCollection(request, destination))
            serviceMessage = AppText.text(R.string.app_apple_sign_in_required)
            startSignIn()
            return
        }
        pendingAuthentication.clear()
        supersedeStartupQueueWithNewContext()
        if (destination != Destination.NowPlaying) previousDestination = destination
        playWhenReady = true
        queueController.playContext(
            tracks = tracks.map(::withCachedLyrics),
            startIndex = index,
            context = PlaybackContext(
                kind = when {
                    collection.catalogKind == CatalogKind.Album -> ContextKind.Album
                    collection.resourceType == "library-playlists" -> ContextKind.LibraryPlaylist
                    else -> ContextKind.Playlist
                },
                id = collection.resourceId ?: collection.catalogId,
                title = collection.title,
            ),
            shuffle = shuffle,
            pendingTail = request.continuation != null,
        )
        runtime.installContextContinuation(request.continuation)
        destination = Destination.NowPlaying
    }

    LaunchedEffect(authenticatedAction, startupSessionLoaded, connectionState) {
        if (!startupSessionLoaded || connectionState != MusicConnectionState.SignedIn) return@LaunchedEffect
        val action = authenticatedAction ?: return@LaunchedEffect
        authenticatedAction = null
        destination = action.origin
        when (action) {
            is PendingAuthenticationAction.PlayTrack -> playTrack(action.track, action.contextQueue, action.startStation)
            is PendingAuthenticationAction.PlayCollection -> {
                catalogDetailIntent = CatalogDetailIntent(action.request.collection)
                playCollection(action.request)
            }
            is PendingAuthenticationAction.ResumeCurrent -> {
                if (action.matchesCurrent(track, queueController.state.value.sessionId, queueController.state.value.current?.entryId)) {
                    positionAnchorMs = action.positionMs
                    lastPlaybackFailure = null
                    requestPlayback()
                }
            }
        }
    }

    fun advanceNextTrack(isAuto: Boolean = true) {
        val next = queueController.advanceNext(manual = !isAuto)
        val live = queueController.state.value
        if (next == null && live.awaitingNext) {
            waitForNextQueueEntry(live.current?.entryId)
        } else if (next == null) {
            isPlaying = false
            playWhenReady = false
            runtime.setDesiredPlayIntent(false)
        }
    }

    fun selectQueueTrack(index: Int) {
        timelineEntries.getOrNull(index)?.let { entry ->
            val previousIntent = playWhenReady
            playWhenReady = true
            if (!dispatchQueueAction(StartupQueueAction.Select(entry.entryId))) playWhenReady = previousIntent
        }
    }

    fun skip(relative: Int) {
        if (track.catalogKind == CatalogKind.Station) {
            if (relative > 0 && canSkipNext) services.skipToNext()
            else if (relative < 0 && canSkipPrevious) services.skipToPrevious()
            return
        }
        dispatchQueueAction(if (relative > 0) StartupQueueAction.Next else StartupQueueAction.Previous)
    }

    fun showNowPlaying() {
        if (!hasCurrentTrack) {
            val firstCandidate = shelvesByDestination[destination]?.firstOrNull()?.items?.firstOrNull()
                ?: shelvesByDestination[Destination.Home]?.firstOrNull()?.items?.firstOrNull()
            if (firstCandidate != null) {
                playTrack(firstCandidate)
            } else {
                serviceMessage = AppText.text(R.string.app_choose_content_before_playback)
            }
            return
        }
        if (destination != Destination.NowPlaying) previousDestination = destination
        destination = Destination.NowPlaying
    }

    fun handlePlaybackFailure(selection: Long, kind: PlaybackFailureKind, message: String) {
        if (selection != selectionGeneration || failureHandledSelection == selection) return
        val resumePosition = nativeResumeRecovery.consumeFailure(selection, track.catalogId,
            runtime.playbackGeneration, kind, playWhenReady)
        failureHandledSelection = selection
        lastPlaybackFailure = kind
        isPlaying = false
        audioBuffering = false
        playbackPrepared = false
        nativeCanSeek = false
        if (track.catalogKind != CatalogKind.Station && resumePosition != null) {
            // One failed explicit native resume may reprepare this occurrence.
            selectionGeneration++
            launchNativePlayback(resumePosition)
        } else {
            playWhenReady = false
            playbackMessage = message
        }
    }

    LaunchedEffect(preparation) {
        val request = preparation.request ?: return@LaunchedEffect
        val live = queueController.state.value
        if (!request.matches(runtime.preparation.value.request?.serial ?: -1L,
                live.sessionId, live.current?.entryId)) return@LaunchedEffect
        when (preparation.phase) {
            PreparationPhase.Ready -> {
                preparation.resolvedTrack?.let { acceptResolvedTrack(request.track, it) }
                lastPlaybackFailure = null
                playbackMessage = null
            }
            PreparationPhase.Failed -> handlePlaybackFailure(selectionGeneration,
                preparation.failureKind ?: PlaybackFailureKind.Unexpected,
                preparation.errorMessage ?: AppText.text(R.string.app_song_load_failed))
            PreparationPhase.Retrying -> {
                nativeResumeRecovery.cancel()
                isPlaying = false
                playbackPrepared = false
                nativeCanSeek = false
                audioBuffering = true
                val deadline = preparation.retryAtElapsedRealtimeMs ?: return@LaunchedEffect
                while (true) {
                    val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    if (remaining == 0L) break
                    playbackMessage = AppText.text(R.string.services_song_connection_retry,
                        ((remaining + 999L) / 1_000L).toInt())
                    delay(minOf(remaining, 1_000L))
                }
            }
            PreparationPhase.Preparing -> {
                nativeResumeRecovery.cancel()
                playbackMessage = null
                audioBuffering = true
            }
            PreparationPhase.Idle -> Unit
        }
    }

    LaunchedEffect(waitingQueueEntryId, queueState.current?.entryId, queueState.awaitingNext,
        queueState.autoplayStatus, queueState.contextContinuationPending, queueState.contextContinuationFailed,
        systemPlaybackEnded) {
        val waitingEntry = waitingQueueEntryId ?: queueState.current?.entryId?.takeIf {
            val ended = systemPlaybackSnapshot.value
            queueState.contextContinuationFailed && ended?.isEnded == true &&
                (ended.appQueueEntryId == null || ended.appQueueEntryId == it) &&
                ended.currentTrack?.catalogId == track.catalogId && ended.currentTrack?.catalogKind == track.catalogKind
        } ?: return@LaunchedEffect
        if (queueState.current?.entryId != waitingEntry) {
            waitingQueueEntryId = null
            return@LaunchedEffect
        }
        if (queueState.contextContinuationFailed) {
            waitingQueueEntryId = null
            playWhenReady = false
            isPlaying = false
            lastPlaybackFailure = PlaybackFailureKind.Network
            playbackMessage = context.getString(R.string.services_collection_next_page_failed)
            runtime.setDesiredPlayIntent(false, preservePendingNext = true)
            return@LaunchedEffect
        }
        if (queueState.contextContinuationPending) return@LaunchedEffect
        val message = waitingRecommendationError(
            waitingEntry, queueState.current?.entryId, queueState.awaitingNext,
            (queueState.autoplayStatus as? dev.amatv.playback.AutoplayStatus.Error)?.message,
        )
        if (message != null) {
            // The failed wait is terminal. A later explicit Play may retry the
            // displayed item, but hydration must never replay it automatically.
            waitingQueueEntryId = null
            playWhenReady = false
            isPlaying = false
            playbackMessage = message
            runtime.setDesiredPlayIntent(false)
        } else if (!queueState.awaitingNext && queueState.autoplayStatus is dev.amatv.playback.AutoplayStatus.Idle) {
            waitingQueueEntryId = null
            playWhenReady = false
            runtime.setDesiredPlayIntent(false)
        }
    }

    fun publishSystemSnapshot(snapshot: PlaybackSnapshot) {
        // Runtime owns system publication and retry masking across UI replacement.
        systemPlaybackSnapshot.value = snapshot
    }

    fun applyPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (snapshot.requestGeneration == 0L || snapshot.requestGeneration != runtime.playbackGeneration) return
        val previousOccurrence = systemPlaybackSnapshot.value?.appQueueEntryId
        if (snapshot.nativeQueueHandoff && snapshot.appQueueEntryId != null &&
            snapshot.appQueueEntryId != previousOccurrence) {
            selectionGeneration++
            nativeResumeRecovery.cancel()
            lastPlaybackFailure = null
            playbackMessage = null
        }
        publishSystemSnapshot(snapshot)
        audioBuffering = playWhenReady && snapshot.isBuffering
        // A live reconnect clears the previous song before it is prepared.
        // Show the current station fallback while waiting for fresh metadata.
        if (!snapshot.isPrepared && snapshot.errorMessage == null) {
            val current = snapshot.currentTrack
            val queued = queueController.state.value.current?.track
            if (current?.catalogKind == CatalogKind.Station && queued?.catalogKind == CatalogKind.Station &&
                current.matchesPlaybackQueueEntry(queued)) {
                if (track != current) track = current
                hasCurrentTrack = true
            }
        }
        if (runtime.hasPendingPrepare || snapshot.errorMessage != null) return
        // Runtime has already reconciled native handoffs against the live rail.
        if (snapshot.appQueueEntryId != null &&
            snapshot.appQueueEntryId != queueController.state.value.current?.entryId) return
        if (!snapshot.isPrepared) {
            playbackPrepared = false
            nativeCanSeek = false
            if (!snapshot.isPlaying) isPlaying = false
            return
        }
        nativeResumeRecovery.observePlaying(selectionGeneration, snapshot.currentTrack?.catalogId,
            snapshot.requestGeneration, snapshot.positionMs, snapshot.isPlaying)
        playbackPrepared = true
        nativeCanSeek = snapshot.canSeek && snapshot.durationMs > 0L
        val endedEntryKey = queueController.state.value.current?.entryId?.toString() ?: track.id
        if (!snapshot.isEnded && snapshot.currentTrack?.catalogId == track.catalogId) advancingEndedTrackId = null
        if (snapshot.isEnded && snapshot.currentTrack?.catalogId == track.catalogId &&
            advancingEndedTrackId != endedEntryKey
        ) {
            advancingEndedTrackId = endedEntryKey
            isPlaying = false
            if (!playWhenReady || track.catalogKind == CatalogKind.Station) {
                playWhenReady = false
                runtime.setDesiredPlayIntent(false)
                services.pause()
            } else advanceNextTrack(isAuto = true)
            return
        }
        val trackChanged = snapshot.currentTrack != null && (
            track.id != snapshot.currentTrack.id ||
            track.catalogId != snapshot.currentTrack.catalogId
        )
        snapshot.currentTrack?.let { current ->
            val provenance = queueController.state.value.current?.track?.takeIf { it.catalogId == current.catalogId }
            val artworkSource = if (current.catalogKind != CatalogKind.Station &&
                current.catalogId == track.catalogId && current.catalogKind == track.catalogKind)
                current.withEditorialArtwork(track) else current
            val withLyrics = withCachedLyrics(artworkSource.copy(
                recommendedByYtm = provenance?.recommendedByYtm == true,
                ytmVideoId = provenance?.ytmVideoId,
                contentRating = artworkSource.contentRating ?: provenance?.contentRating,
            ))
            if (track.id != withLyrics.id ||
                track.lyrics != withLyrics.lyrics ||
                track.title != withLyrics.title ||
                track.artist != withLyrics.artist ||
                track.album != withLyrics.album ||
                track.artworkUrl != withLyrics.artworkUrl ||
                track.motionArtworkUrl != withLyrics.motionArtworkUrl ||
                track.motionArtworkVariants != withLyrics.motionArtworkVariants ||
                track.durationMs != withLyrics.durationMs ||
                track.contentRating != withLyrics.contentRating ||
                track.recommendedByYtm != withLyrics.recommendedByYtm
            ) {
                track = withLyrics
            }
            hasCurrentTrack = true
        }

        // Performance optimization: prevent 20fps root recomposition by checking drift > 500ms
        val currentPredictedMs = if (isPlaying) {
            positionAnchorMs + (snapshot.capturedAtElapsedRealtimeMs - playStartedRealtimeMs)
        } else {
            positionAnchorMs
        }
        val drift = kotlin.math.abs(snapshot.positionMs - currentPredictedMs)
        val seekOrDrift = drift > 500L
        val playStateChanged = snapshot.isPlaying != isPlaying
        if (trackChanged || playStateChanged || seekOrDrift) {
            positionAnchorMs = snapshot.positionMs
            playStartedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs
        }
        if (playStateChanged) {
            isPlaying = snapshot.isPlaying
        }
        val station = track.catalogKind == CatalogKind.Station
        canSkipNext = if (station) snapshot.canSkipNext else queueState.canSkipNext
        canSkipPrevious = if (station) snapshot.canSkipPrevious else queueState.canSkipPrevious
        playbackMessage = null
    }

    val latestSnapshotHandler by rememberUpdatedState<(PlaybackSnapshot) -> Unit>(::applyPlaybackSnapshot)
    val latestSystemPlaybackCallbacks by rememberUpdatedState(
        PlaybackMediaSession.Callbacks(
            play = ::requestPlayback,
            pause = ::pausePlayback,
            next = { skip(1) },
            previous = { skip(-1) },
            seekTo = { position -> seekPlaybackBy(position - currentPositionMs()) },
            seekRelative = ::seekPlaybackBy,
        ),
    )
    DisposableEffect(runtime) {
        val token = runtime.attachUi(context as? android.app.Activity,
            dev.amatv.playback.PlaybackRuntimeOwner.UiCallbacks(
                onTrack = { latestTrackPlayback(it) },
                onSnapshot = { latestSnapshotHandler(it) },
                system = PlaybackMediaSession.Callbacks(
                    play = { latestSystemPlaybackCallbacks.play() }, pause = { latestSystemPlaybackCallbacks.pause() },
                    next = { latestSystemPlaybackCallbacks.next() }, previous = { latestSystemPlaybackCallbacks.previous() },
                    seekTo = { latestSystemPlaybackCallbacks.seekTo(it) },
                    seekRelative = { latestSystemPlaybackCallbacks.seekRelative(it) },
                ),
            ))
        onDispose {
            runtime.detachUi(token)
        }
    }
    LaunchedEffect(runtime, destination, previousDestination) { runtime.updateUiLocation(destination, previousDestination) }
    LaunchedEffect(queueState.canSkipNext, queueState.canSkipPrevious) {
        systemPlaybackSnapshot.value?.takeIf {
            it.requestGeneration == runtime.playbackGeneration && it.currentTrack?.catalogId == track.catalogId
        }?.let {
            val station = it.currentTrack?.catalogKind == CatalogKind.Station
            services.publishSystemPlayback(it,
                if (station) it.canSkipNext else queueState.canSkipNext,
                if (station) it.canSkipPrevious else queueState.canSkipPrevious)
        }
    }

    val saveCurrentSession by rememberUpdatedState<(Boolean) -> Unit> saveSession@{ synchronous ->
        playbackPrefs.saveActiveTab(
            if (destination == Destination.NowPlaying) previousDestination else destination,
            synchronous,
        )
        if (!startupSessionLoaded && selectionGeneration == 0L) return@saveSession
        if (hasCurrentTrack) {
            val currentEntries = queueState.history + listOfNotNull(queueState.current) +
                queueState.upNext + queueState.autoplay
            val currentQueue = currentEntries.map { it.track }
            if (currentQueue.isNotEmpty()) {
                val currentIdx = queueState.history.size.coerceIn(0, currentQueue.lastIndex)
                playbackPrefs.saveSession(
                    PlaybackSession(
                        queue = currentQueue,
                        currentIndex = currentIdx,
                        positionMs = currentPositionMs().coerceIn(0L, track.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE),
                        wasPlaying = playWhenReady,
                        destination = destination,
                        previousDestination = previousDestination,
                        radioActive = queueState.infinite,
                        radioSeed = queueState.recommendationSeed,
                        radioContinuation = queueState.ytmContinuation,
                        radioSeedVideoId = queueState.ytmSeedVideoId,
                        autoplayEntryIndices = currentEntries.withIndex()
                            .filter { it.value.origin == EntryOrigin.Autoplay }
                            .map { it.index }.toSet(),
                        autoplaySource = queueState.source,
                        queueEntryIds = currentEntries.map { it.entryId },
                        queueSourceEntryIds = currentEntries.map { it.sourceEntryId },
                        deferredLastEntryIds = queueState.deferredLastEntryIds,
                        playedEntryIds = currentEntries.filter { it.wasPlayed }.map { it.entryId }.toSet(),
                        contextQueue = queueState.contextEntries.map { it.track },
                        contextEntryIds = queueState.contextEntries.map { it.entryId },
                        unshuffledEntryIds = queueState.unshuffledUpNext.orEmpty().map { it.entryId },
                        containerEntryIds = queueState.containerEntryIds,
                        playbackContext = queueState.context,
                        sessionId = queueState.sessionId,
                        persistenceId = queueState.persistenceId,
                        pendingCollection = runtime.pendingCollectionCheckpoint,
                    ),
                    synchronous,
                )
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveCurrentSession(true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            saveCurrentSession(true)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(
        destination, previousDestination, track.id, queueState, isPlaying, playWhenReady,
    ) {
        delay(350L)
        saveCurrentSession(false)
    }
    LaunchedEffect(isPlaying, track.id) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            delay(5_000L)
            saveCurrentSession(false)
        }
    }
    var previousYtmScope by remember { mutableStateOf(ytmAuthorization.scope) }
    LaunchedEffect(queueState.source, ytmAuthorization.scope) {
        if (previousYtmScope != ytmAuthorization.scope) {
            previousYtmScope = ytmAuthorization.scope
            queueController.invalidateRecommendationAuthorization()
        }
        shelvesByDestination = shelvesByDestination - Destination.Home
        homeFeedSnapshot = null
        feedSuccessTimes = feedSuccessTimes - Destination.Home
        feedAttemptTimes = feedAttemptTimes - Destination.Home
        feedSnapshotTimes = feedSnapshotTimes - Destination.Home
        reloadNonce++
    }

    // Auto-advance timer: detects track finish and smoothly advances
    LaunchedEffect(isPlaying, track.id, track.durationMs) {
        // Native playback ends only on its generation-bound SDK end event.
        if (!MockCatalog.isDemoTrack(track) || !isPlaying || track.durationMs <= 2_000L) return@LaunchedEffect
        while (isPlaying) {
            val currentMs = currentPositionMs()
            if (currentMs >= track.durationMs - 250L) {
                if (!runtime.consumeSimulatedPlaybackEnd()) advanceNextTrack(isAuto = true)
                break
            }
            delay(250L)
        }
    }

    // Lyrics use the account API, not the native audio engine. Wait for the full
    // session (including deferred queue selection) before resolving the current
    // song; lyricsFor then awaits the account storefront and bearer internally.
    // Album motion is optional display metadata, never part of audio prepare.
    // Only the selected recording is enriched; rapid skips cancel old work.
    LaunchedEffect(track.catalogId, track.catalogKind, lowPerformanceMode, artworkMotionEnabled, contentLanguageTag,
        contentAccountScope, cacheSettings.revision, connectionState, startupSessionLoaded, hasCurrentTrack, reloadNonce) {
        val selected = track
        if (!startupSessionLoaded || !hasCurrentTrack || !artworkMotionEnabled || lowPerformanceMode || selected.catalogKind != CatalogKind.Song ||
            MockCatalog.isDemoTrack(selected) || connectionState != MusicConnectionState.SignedIn) return@LaunchedEffect
        val identity = "${selected.catalogKind}:${selected.catalogId}:${selected.resourceType}:${selected.resourceId}"
        val artScope = "$contentAccountScope:$contentLanguageTag:${cacheSettings.revision}"
        val previousSource = playerArtworkSource.takeIf { playerArtworkIdentity == identity }
        if (previousSource != null && playerArtworkScope == artScope) return@LaunchedEffect
        if (previousSource == null && (selected.motionArtworkUrl != null || selected.motionArtworkVariants.isNotEmpty())) {
            // A card may already have enriched the selected item. Keep that
            // image now, but retain a clean lookup source for later locale changes.
            playerArtworkSource = selected.copy(motionArtworkUrl = null, motionPreviewUrl = null,
                motionArtworkVariants = emptyList())
            playerArtworkIdentity = identity
            playerArtworkScope = artScope
            return@LaunchedEffect
        }
        val source = previousSource?.let {
            selected.copy(motionArtworkUrl = it.motionArtworkUrl, motionPreviewUrl = it.motionPreviewUrl,
                motionArtworkVariants = it.motionArtworkVariants)
        } ?: selected
        val enriched = services.editorialArtwork(source)
        if (track.catalogId == selected.catalogId && track.catalogKind == selected.catalogKind &&
            connectionState == MusicConnectionState.SignedIn && services.authorizationCacheScope() == contentAccountScope &&
            currentContentLanguage == contentLanguageTag) {
            playerArtworkSource = source
            playerArtworkIdentity = identity
            playerArtworkScope = artScope
            track = track.copy(motionArtworkUrl = enriched.motionArtworkUrl,
                motionPreviewUrl = enriched.motionPreviewUrl, motionArtworkVariants = enriched.motionArtworkVariants)
        }
    }

    val lyricsRequestReady = startupSessionLoaded && hasCurrentTrack &&
        connectionState == MusicConnectionState.SignedIn
    val currentLyricsIdentity = track.lyricsIdentity()
    val currentLyricsLines = if (activeLyrics.trackIdentity == currentLyricsIdentity) {
        activeLyrics.lines
    } else {
        track.lyrics
    }

    val prefetchTargets = remember(queueState.current?.entryId, queueState.upNext, queueState.autoplay) {
        (queueState.upNext + queueState.autoplay).take(2).map { it.track }
    }
    LaunchedEffect(
        prefetchTargets, isPlaying, cacheSettings, lyricsRequestReady, currentLyricsLines.isEmpty(),
        lyricsLookupFailures, lyricsVariant,
    ) {
        if (!lyricsRequestReady || !isPlaying || !cacheSettings.enabled || !cacheSettings.prefetchNextTwo || cacheSettings.clearing) {
            return@LaunchedEffect
        }
        val cacheGeneration = services.contentCache.generation
        if (!kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { services.contentCache.canWrite() }) {
            return@LaunchedEffect
        }
        for (candidate in prefetchTargets) {
            if (candidate.catalogKind != CatalogKind.Song) continue
            candidate.artworkUrl?.let { url -> cancellableResult { ArtworkImages.prefetch(context, url) } }
            val candidateIdentity = candidate.lyricsIdentity()
            val candidateLyricsKey = lyricsCacheKey(candidateIdentity, lyricsVariant)
            if ((currentLyricsLines.isNotEmpty() || currentLyricsIdentity in lyricsLookupFailures) &&
                (candidate.lyrics.isEmpty() || lyricsLocalization.enabled) &&
                candidateLyricsKey !in lyricsByTrackId && candidateIdentity !in lyricsLookupFailures
            ) {
                when (val result = requestLyricsWithRetry({
                    services.lyricsFor(candidate, lyricsLocalization)
                })) {
                    is LyricsRequestResult.Available -> {
                        if (cacheGeneration == services.contentCache.generation) {
                            cacheLyrics(candidateIdentity, lyricsVariant, result.lines)
                        }
                    }
                    LyricsRequestResult.Unavailable -> {
                        // A 200 response without TTML is a definitive miss. Remember it so
                        // unstable queue projections cannot restart this prefetch every poll.
                        lyricsLookupFailures = lyricsLookupFailures + candidateIdentity
                    }
                    is LyricsRequestResult.RetryableFailure -> Unit
                }
            }
        }
    }
    LaunchedEffect(cacheSettings.revision) {
        // Preserve the displayed line; clearing assets must not interrupt the current song.
        lyricsByTrackId = activeLyrics.resolvedCacheEntries()
        lyricsLookupFailures = emptySet()
        if (activeLyrics.phase == LyricsLoadPhase.Failed) {
            activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
        }
    }

    LaunchedEffect(
        currentLyricsIdentity, track.catalogKind, lyricsRequestReady, reloadNonce, lyricsRetryNonce,
        cacheSettings.revision, lyricsVariant,
    ) {
        if (!lyricsRequestReady || track.catalogKind != CatalogKind.Song) {
            return@LaunchedEffect
        }
        val lookupIdentity = currentLyricsIdentity
        val originalTrack = track
        if (activeLyrics.trackIdentity != lookupIdentity || activeLyrics.lines.isEmpty()) {
            activeLyrics = ActiveLyricsState(lookupIdentity, originalTrack.lyrics, phase = LyricsLoadPhase.Loading)
        }
        val currentLyricsKey = lyricsCacheKey(lookupIdentity, lyricsVariant)
        lyricsByTrackId[currentLyricsKey]?.let { cached ->
            activeLyrics = ActiveLyricsState(
                trackIdentity = lookupIdentity,
                lines = cached,
                resolvedVariant = lyricsVariant,
                phase = LyricsLoadPhase.Ready,
            )
            return@LaunchedEffect
        }
        if (activeLyrics.trackIdentity == lookupIdentity &&
            activeLyrics.resolvedVariant == lyricsVariant &&
            activeLyrics.phase == LyricsLoadPhase.Ready
        ) return@LaunchedEffect

        activeLyrics.projectResolvedVariant(lookupIdentity, lyricsLocalization)?.let { projected ->
            cacheLyrics(lookupIdentity, lyricsVariant, projected.lines)
            activeLyrics = projected
            return@LaunchedEffect
        }

        // The canonical lines are the stable UI baseline. Changing language never
        // clears them and never exposes a half-parsed localization document.
        val baseline = (if (activeLyrics.trackIdentity == lookupIdentity) {
            activeLyrics.lines
        } else {
            track.lyrics
        }).map { it.forDisplay(showTranslation = false, showPronunciation = false) }

        if (!lyricsLocalization.enabled && baseline.isNotEmpty()) {
            cacheLyrics(lookupIdentity, lyricsVariant, baseline)
            activeLyrics = ActiveLyricsState(
                trackIdentity = lookupIdentity,
                lines = baseline,
                resolvedVariant = lyricsVariant,
                phase = LyricsLoadPhase.Ready,
            )
            return@LaunchedEffect
        }
        val resolvedTrack = cancellableResult {
            retryCatalogRequest({ services.resolvePlayableTrack(originalTrack) })
        }.getOrElse {
            activeLyrics = activeLyrics.copy(phase = if (
                classifyPlaybackFailure(null, null, null, it) == PlaybackFailureKind.TrackUnavailable
            ) LyricsLoadPhase.Unavailable else LyricsLoadPhase.Failed)
            Log.w("AMATV-Lyrics", "Library catalog resolution failed (${it.javaClass.simpleName})")
            return@LaunchedEffect
        }
        if (resolvedTrack.catalogId != originalTrack.catalogId) {
            // Re-key the same library song before any request/cache lookup. The
            // changed identity reruns this effect without flashing empty lyrics.
            acceptResolvedTrack(originalTrack, resolvedTrack)
            return@LaunchedEffect
        }
        if (lookupIdentity in lyricsLookupFailures && baseline.isEmpty()) {
            activeLyrics = ActiveLyricsState(
                trackIdentity = lookupIdentity,
                lines = emptyList(),
                resolvedVariant = lyricsVariant,
                phase = LyricsLoadPhase.Unavailable,
            )
            return@LaunchedEffect
        }
        val lookupTrack = track
        val loadingLyrics = activeLyrics.pendingVariant(lookupIdentity, lyricsLocalization)
            .let { pending -> if (pending.lines.isEmpty()) pending.copy(lines = baseline) else pending }
        activeLyrics = loadingLyrics
        Log.i("AMATV-Lyrics", "Loading current lyrics: $lookupIdentity " +
            "source=${lookupTrack.resourceType}:${lookupTrack.resourceId} (audioPrepared=$playbackPrepared)")
        when (val result = requestLyricsWithRetry({
            services.lyricsFor(lookupTrack, lyricsLocalization)
        })) {
            is LyricsRequestResult.Available -> {
                if (track.lyricsIdentity() != lookupIdentity) return@LaunchedEffect
                cacheLyrics(lookupIdentity, lyricsVariant, result.lines)
                lyricsLookupFailures = lyricsLookupFailures - lookupIdentity
                activeLyrics = ActiveLyricsState(
                    trackIdentity = lookupIdentity,
                    lines = result.lines,
                    resolvedVariant = lyricsVariant,
                    phase = LyricsLoadPhase.Ready,
                )
            }
            LyricsRequestResult.Unavailable -> {
                if (track.lyricsIdentity() != lookupIdentity) return@LaunchedEffect
                if (baseline.isEmpty()) {
                    lyricsLookupFailures = lyricsLookupFailures + lookupIdentity
                    activeLyrics = ActiveLyricsState(
                        trackIdentity = lookupIdentity,
                        lines = emptyList(),
                        resolvedVariant = lyricsVariant,
                        phase = LyricsLoadPhase.Unavailable,
                    )
                } else {
                    // Lyrics exist but this translation/pronunciation variant does not.
                    cacheLyrics(lookupIdentity, lyricsVariant, loadingLyrics.lines)
                    activeLyrics = ActiveLyricsState(
                        trackIdentity = lookupIdentity,
                        lines = loadingLyrics.lines,
                        resolvedVariant = lyricsVariant,
                        phase = LyricsLoadPhase.Ready,
                    )
                }
            }
            is LyricsRequestResult.RetryableFailure -> {
                if (track.lyricsIdentity() != lookupIdentity) return@LaunchedEffect
                // Keep the canonical lines visible and expose a retryable state only.
                activeLyrics = loadingLyrics.copy(phase = LyricsLoadPhase.Failed)
                Log.w("AMATV-Lyrics", "Lyrics temporarily unavailable (${result.reason})")
            }
        }
    }

    LaunchedEffect(destination, rootFeedVisible, loadingDestinations, feedSuccessTimes, feedAttemptTimes, feedSnapshotTimes, contentAccountScope) {
        val kind = when (destination) {
            Destination.Home -> dev.amatv.integration.CatalogFeedKind.Home
            Destination.Browse -> dev.amatv.integration.CatalogFeedKind.Browse
            else -> return@LaunchedEffect
        }
        val wait = dev.amatv.integration.FeedFreshnessPolicy.nextCheckDelayMillis(kind,
            visible = rootFeedVisible, hasContent = shelvesByDestination[destination]?.isNotEmpty() == true,
            snapshotWrittenAtEpochMs = feedSnapshotTimes[destination],
            lastSuccessElapsedMs = feedSuccessTimes[destination], lastAttemptElapsedMs = feedAttemptTimes[destination],
            inFlight = destination in loadingDestinations, nowElapsedMs = SystemClock.elapsedRealtime()) ?: return@LaunchedEffect
        delay(wait.coerceAtLeast(250L))
        feedRefreshTick++
    }

    LaunchedEffect(destination, connectionState, reloadNonce, contentLanguageTag, queueState.source,
        ytmAuthorization.scope, feedRefreshTick, contentAccountScope) {
        if (shelvesLanguageTag != contentLanguageTag) {
            resetLibraryPagination()
            // Section titles are presentation metadata. Invalidate only this
            // cache, retaining the selected song, queue, focus and account.
            shelvesByDestination = if (services.isCatalogConfigured) emptyMap() else demoShelves()
            loadingDestinations = emptySet()
            shelvesLanguageTag = contentLanguageTag
            browseRefreshRevision++
            libraryRevision++
            homeFeedSnapshot = null
            feedNotices = emptyMap()
        feedFailureReasons = emptyMap()
            feedSuccessTimes = emptyMap()
            feedAttemptTimes = emptyMap()
            feedSnapshotTimes = emptyMap()
        }
        if (!services.isCatalogConfigured) return@LaunchedEffect
        val target = destination
        val requestEpoch = feedRequestEpoch.incrementAndGet()
        if (
            target != Destination.Home &&
            target != Destination.Browse &&
            target != Destination.Radio &&
            target != Destination.Library
        ) {
            return@LaunchedEffect
        }
        if (target == Destination.Library) {
            loadingDestinations = loadingDestinations - target
            if (connectionState == MusicConnectionState.SignedIn) ensureLibrarySection(LibrarySection.Recent.shelfId)
            return@LaunchedEffect
        }
        val playedRadio = track.catalogId.takeIf { track.catalogKind == CatalogKind.Station }
        val radioNeedsRefresh = target == Destination.Radio && (!radioRefreshComplete ||
            SystemClock.elapsedRealtime() - radioRefreshFinishedAt >= 60_000L ||
            (playedRadio != null && playedRadio != radioRecentPlaybackId))
        val feedKind = when (target) {
            Destination.Home -> dev.amatv.integration.CatalogFeedKind.Home
            Destination.Browse -> dev.amatv.integration.CatalogFeedKind.Browse
            else -> null
        }
        val snapshotScope = contentAccountScope + if (target == Destination.Home)
            ":${queueState.source.name}:${ytmAuthorization.scope.takeIf { queueState.source == AutoplaySource.YouTubeMusic }.orEmpty()}" else ""
        if (feedKind != null) {
            if (shelvesByDestination[target].isNullOrEmpty() && !(target == Destination.Home && probeColdHome)) {
                val cached = feedCache.read(snapshotScope, contentLanguageTag, feedKind)
                if (cached != null && feedRequestEpoch.get() == requestEpoch) {
                    shelvesByDestination = shelvesByDestination + (target to capabilities.filterShelves(cached.shelves))
                    if (target == Destination.Home) {
                        homeFeedSnapshot = cached.homeSnapshot
                        cached.homeSnapshot?.recentTracks?.let { recentTracks = capabilities.filterTracks(it) }
                    }
                    if (!cached.requiresRefresh) feedSnapshotTimes = feedSnapshotTimes + (target to cached.savedAtEpochMs)
                }
            }
            val refresh = dev.amatv.integration.FeedFreshnessPolicy.shouldRefresh(feedKind,
                visible = rootFeedVisible, hasContent = shelvesByDestination[target]?.isNotEmpty() == true,
                snapshotWrittenAtEpochMs = feedSnapshotTimes[target],
                lastSuccessElapsedMs = feedSuccessTimes[target], lastAttemptElapsedMs = feedAttemptTimes[target],
                force = target in forcedFeedRefresh, nowElapsedMs = SystemClock.elapsedRealtime())
            if (!refresh) { loadingDestinations = loadingDestinations - target; return@LaunchedEffect }
            forcedFeedRefresh = forcedFeedRefresh - target
            feedAttemptTimes = feedAttemptTimes + (target to SystemClock.elapsedRealtime())
        } else if (shelvesByDestination[target]?.isNotEmpty() == true && !radioNeedsRefresh) return@LaunchedEffect
        loadingDestinations = loadingDestinations + target
        val cacheGeneration = services.contentCache.generation
        val requestAttemptAt = feedAttemptTimes[target]
        try {
        val errors = mutableListOf<Throwable>()
        val loadedShelves = coroutineScope {
            when (target) {
                Destination.Home -> {
                    val signedIn = connectionState == MusicConnectionState.SignedIn
                    fun shelfSource(id: String, title: Int, fetch: suspend () -> List<Track>) =
                        dev.amatv.integration.HomeFeedSource(id) {
                            val items = capabilities.filterTracks(fetch())
                            if (id == "top-songs" && probeLeadingDelayMs > 0) delay(probeLeadingDelayMs)
                            dev.amatv.integration.HomeFeedResult(
                                if (items.isEmpty()) emptyList() else listOf(ContentShelf(id, AppText.text(title), items)))
                        }
                    val providers = buildList {
                        add(dev.amatv.integration.HomeFeedSource("recommendations") {
                            val result = if (signedIn) services.recommendationShelves() else emptyList()
                            if (probeLeadingDelayMs > 0) delay(probeLeadingDelayMs)
                            dev.amatv.integration.HomeFeedResult(result)
                        })
                        if (queueState.source == AutoplaySource.YouTubeMusic) add(dev.amatv.integration.HomeFeedSource("ytm") {
                            dev.amatv.integration.HomeFeedResult(services.ytmHomeShelves())
                        })
                        add(dev.amatv.integration.HomeFeedSource("recent-tracks") {
                            val page = if (signedIn) loadRecentTracks() else null
                            val items = capabilities.filterTracks(page?.items.orEmpty())
                            dev.amatv.integration.HomeFeedResult(
                                if (items.isEmpty()) emptyList() else listOf(ContentShelf("recent-tracks", AppText.text(R.string.app_recent_tracks), items,
                                    hasMore = page?.nextPage != null || (page?.total ?: 0) > (page?.rawItemCount ?: items.size))),
                                recentTracks = items)
                        })
                        add(dev.amatv.integration.HomeFeedSource("recent") {
                            val page = if (signedIn) services.recentlyPlayedPage() else null
                            val items = capabilities.filterTracks(page?.items.orEmpty())
                            dev.amatv.integration.HomeFeedResult(
                                if (items.isEmpty()) emptyList() else listOf(ContentShelf("recent", AppText.text(R.string.app_recent_albums_and_playlists), items,
                                    hasMore = page?.nextPage != null || (page?.total ?: 0) > (page?.rawItemCount ?: items.size))))
                        })
                        add(shelfSource("top-songs", R.string.app_top_songs) { services.catalogCharts.page(CatalogChartCategory.Songs).items.take(14) })
                        add(shelfSource("top-albums", R.string.app_top_albums) { services.catalogCharts.page(CatalogChartCategory.Albums).items.take(14) })
                        add(shelfSource("top-playlists", R.string.app_top_playlists) { services.catalogCharts.page(CatalogChartCategory.Playlists).items.take(14) })
                    }
                    // The regression fixture uses this same loader/Host/cards, with
                    // deterministic completion order and no provider/account requests.
                    val requestProviders = if (dev.amatv.BuildConfig.BUILD_TYPE == "benchmark" && probeHomeFixture) listOf(
                        dev.amatv.integration.HomeFeedSource("fixture-leading") {
                            delay(probeLeadingDelayMs)
                            dev.amatv.integration.HomeFeedResult(listOf(ContentShelf(
                                "leading", "Leading shelf", MockCatalog.tracks.reversed(), featured = true)))
                        },
                        dev.amatv.integration.HomeFeedSource("fixture-early") {
                            dev.amatv.integration.HomeFeedResult(listOf(ContentShelf(
                                "early", "Early shelf", MockCatalog.tracks)))
                        },
                        dev.amatv.integration.HomeFeedSource("fixture-lower") {
                            dev.amatv.integration.HomeFeedResult(listOf(ContentShelf(
                                "lower", "Lower shelf", MockCatalog.tracks.reversed())))
                        },
                    ) else providers
                    val loaded = homeFeedLoader.load(requestProviders, previous = homeFeedSnapshot,
                        onPartial = { partial ->
                            homeFeedSnapshot = partial
                            partial.recentTracks?.let { recentTracks = it }
                            val ready = capabilities.filterShelves(partial.sections.map { it.shelf.copy(id = "${it.key.sourceId}:${it.shelf.id}") })
                            if (ready.isNotEmpty()) shelvesByDestination = shelvesByDestination + (target to ready)
                        })
                    loaded.errors.forEach { (source, failure) ->
                        android.util.Log.w("AMATV-HomeFeed", "source=$source failure=${failure.javaClass.simpleName} cause=${failure.cause?.javaClass?.simpleName} status=${(failure as? dev.amatv.integration.CatalogRequestException)?.httpStatus}")
                    }
                    errors.addAll(loaded.errors.values)
                    loaded.sections.map { it.shelf.copy(id = "${it.key.sourceId}:${it.shelf.id}") }
                }

                Destination.Browse -> cancellableResult { services.browseShelves() }.fold(
                    onSuccess = { it },
                    onFailure = { errors.add(it); shelvesByDestination[target].orEmpty() },
                )

                Destination.Radio -> cancellableResult {
                    val generation = libraryPagingGeneration
                    radioRefreshComplete = false
                    val firstPages = linkedMapOf<String, LibraryPage>()
                    services.radioShelves(onPage = { id, page -> firstPages[id] = page }).let { result ->
                        errors.addAll(result.failures.values)
                        if (generation == libraryPagingGeneration) {
                            // Keep the old rows and their continuation controls
                            // intact during refresh. Commit page cursors with
                            // the settled shelves, not each source completion.
                            val replacedIds = (libraryNextPages.keys + libraryVisitedPages.keys + libraryTotals.keys +
                                libraryFailedShelves + firstPages.keys).filter { id -> result.successfulSources.any { it.ownsShelf(id) } }
                            replacedIds.forEach { id ->
                                libraryPageJobs.remove(id)?.cancel()
                                libraryLoadingShelves = libraryLoadingShelves - id
                                libraryVisitedPages = libraryVisitedPages - id
                                libraryNextPages = libraryNextPages - id
                                libraryTotals = libraryTotals - id
                                libraryFailedShelves = libraryFailedShelves - id
                                libraryFailureReasons = libraryFailureReasons - id
                            }
                            firstPages.forEach { (id, page) ->
                                rememberLibraryPage(id, page)
                            }
                            radioRefreshComplete = result.failures.isEmpty()
                            radioRefreshFinishedAt = SystemClock.elapsedRealtime()
                            radioRecentPlaybackId = playedRadio
                        }
                        result.shelves + shelvesByDestination[target].orEmpty().filter { shelf ->
                            result.successfulSources.none { it.ownsShelf(shelf.id) }
                        }
                    }
                }.fold(onSuccess = { it }, onFailure = { errors.add(it); shelvesByDestination[target].orEmpty() })

                Destination.Library -> emptyList()

                Destination.NowPlaying,
                Destination.Sing,
                Destination.Videos,
                Destination.Account,
                Destination.Search,
                -> emptyList()
            }
        }
        val visibleShelves = if (target == Destination.Radio) loadedShelves.mapNotNull { shelf ->
            val items = capabilities.filterTracks(shelf.items)
            shelf.copy(items = items).takeIf { items.isNotEmpty() || shelf.id in libraryNextPages }
        } else capabilities.filterShelves(loadedShelves)
        feedFailureReasons = errors.firstOrNull()?.catalogUiFailureReason()?.let {
            feedFailureReasons + (target to it)
        } ?: (feedFailureReasons - target)
        feedNotices = if (errors.isNotEmpty() && visibleShelves.isNotEmpty()) {
            val auth = errors.any { it.catalogUiFailureReason() == dev.amatv.integration.CatalogFailureReason.Authorization }
            feedNotices + (target to if (auth) R.string.feed_partial_authorization else R.string.feed_partial_unavailable)
        } else feedNotices - target
        shelvesByDestination = shelvesByDestination + (target to visibleShelves)
        if (feedKind != null) {
            if (errors.isEmpty()) feedSuccessTimes = feedSuccessTimes + (target to SystemClock.elapsedRealtime())
            if (!probeColdHome && (errors.isEmpty() || target == Destination.Home && visibleShelves.isNotEmpty())) feedCache.write(snapshotScope, contentLanguageTag, feedKind,
                visibleShelves, cacheGeneration, homeSnapshot = homeFeedSnapshot.takeIf { target == Destination.Home })
        }
        loadingDestinations = loadingDestinations - target
        serviceMessage = if (loadedShelves.isEmpty()) {
            if (target == Destination.Radio && errors.isNotEmpty()) AppText.text(R.string.browse_content_unavailable)
            else errors.firstOrNull()?.let { AppText.text(catalogFailurePresentation(it.catalogUiFailureReason()).message) }
        } else {
            null
        }
        } catch (cancelled: CancellationException) {
            // Leaving a tab is not a failed refresh. A cancelled first load must
            // be able to resume on return rather than show an empty page for TTL.
            if (requestAttemptAt != null && feedAttemptTimes[target] == requestAttemptAt)
                feedAttemptTimes = feedAttemptTimes - target
            throw cancelled
        } finally {
            if (feedRequestEpoch.get() == requestEpoch) loadingDestinations = loadingDestinations - target
        }
    }

    val isNowPlaying = destination == Destination.NowPlaying
    val spectrumRequiresPermission = track.catalogKind != CatalogKind.Station || services.mediaCapabilities.catalogStations
    LaunchedEffect(isNowPlaying, isPlaying, spectrumPermissionGranted, spectrumRequiresPermission) {
        if (isNowPlaying && isPlaying && spectrumRequiresPermission && !spectrumPermissionGranted && !spectrumPermissionAsked) {
            spectrumPermissionAsked = true
            spectrumPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(lifecycleOwner, isNowPlaying, isPlaying, spectrumPermissionGranted, spectrumRequiresPermission) {
        fun updateCapture() {
            services.setSpectrumActive(isNowPlaying && isPlaying && (spectrumPermissionGranted || !spectrumRequiresPermission) &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> updateCapture() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateCapture()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            services.setSpectrumActive(false)
        }
    }
    val activeBrowseDestination = if (isNowPlaying) previousDestination else destination
    val nowPlayingAlpha = animateFloatAsState(
        targetValue = if (isNowPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 320,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "now-playing-screen-transition",
    )

    val playerTransitionVisible by remember(nowPlayingAlpha) {
        derivedStateOf { nowPlayingAlpha.value > 0.001f }
    }
    val browseVisibility = remember(nowPlayingAlpha) {
        derivedStateOf { (1f - nowPlayingAlpha.value * 1.15f).coerceIn(0f, 1f) }
    }

    fun dismissCatalogActions() {
        catalogActionItem = null
        catalogBackHandler = null
        pendingCatalogBack = false
        catalogModalExitToken++
        if (catalogLibraryDirty) { catalogLibraryDirty = false; refreshLoadedLibrary() }
    }
    fun libraryChanged() {
        catalogLibraryDirty = true
        libraryRevision++
    }
    fun backCatalogActions() {
        val handler = catalogBackHandler
        if (handler == null) pendingCatalogBack = true else handler()
    }
    LaunchedEffect(catalogActionItem, catalogBackHandler, pendingCatalogBack) {
        if (catalogActionItem == null) {
            catalogBackHandler = null
            pendingCatalogBack = false
        } else if (pendingCatalogBack && catalogBackHandler != null) {
            pendingCatalogBack = false
            catalogBackHandler?.invoke()
        }
    }
    fun dispatchMediaKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayback()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                requestPlayback()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                pausePlayback()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                skip(1)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                skip(-1)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekPlaybackBy(-10_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekPlaybackBy(10_000L)
                true
            }
            else -> false
        }
    }
    fun dismissExitConfirmation() {
        catalogModalInput.dialogBackDismissed()
        if (exitConfirmationVisible) catalogModalExitToken++
        exitConfirmationVisible = false
    }
    fun showExitConfirmation() {
        catalogModalInput.menuOpened()
        exitConfirmationVisible = true
    }
    // Registered before the browse/player subtree: every inner Back owner gets
    // first refusal. Only an exhausted root reaches the exit confirmation.
    BackHandler {
        when {
            exitConfirmationVisible -> dismissExitConfirmation()
            catalogActionItem != null -> backCatalogActions()
            phoneSignInVisible -> {
                pendingAuthentication.clear()
                pendingAuthAttempt = null
                closePhoneSignIn()
            }
            isNowPlaying -> destination = previousDestination
            else -> showExitConfirmation()
        }
    }

    val focusFrameController = remember { FocusFrameController() }
    AmAtvTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    // Transport keys keep their existing playback policy in both
                    // windows. Navigation/activation belongs solely to the menu
                    // from the synchronous opening intent, before Dialog show.
                    if (native.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                        return@onPreviewKeyEvent if (native.action == KeyEvent.ACTION_UP) dispatchMediaKey(native.keyCode) else true
                    }
                    when (catalogModalInput.host(catalogActionItem != null || exitConfirmationVisible, native.keyCode == KeyEvent.KEYCODE_BACK,
                        native.action == KeyEvent.ACTION_DOWN, native.action == KeyEvent.ACTION_UP,
                        activation = native.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE), repeat = native.repeatCount)) {
                        CatalogModalInputGate.HostResult.DismissMenu -> {
                            if (exitConfirmationVisible) dismissExitConfirmation() else backCatalogActions()
                            return@onPreviewKeyEvent true
                        }
                        CatalogModalInputGate.HostResult.Consume -> return@onPreviewKeyEvent true
                        CatalogModalInputGate.HostResult.Pass -> Unit
                    }
                    false
                },
            color = Color(0xFF080A09),
            contentColor = Color.White,
        ) {
            CompositionLocalProvider(
                LocalInterfaceStyle provides interfaceStyle,
                LocalFocusFrameController provides focusFrameController,
                LocalArtworkMotionEnabled provides (artworkMotionEnabled && !lowPerformanceMode && catalogActionItem == null),
                LocalEditorialArtworkLoader provides remember(services, contentAccountScope, contentLanguageTag, cacheSettings.revision) {
                    EditorialArtworkLoader(cached = services::cachedEditorialArtwork, load = services::editorialArtwork)
                },
                LocalCatalogActionMenu provides showCatalogActions,
                LocalCatalogMenuAnchorReporter provides reportCatalogAnchor,
            ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    if (catalogActionItem != null && interfaceStyle == InterfaceStyle.Apple &&
                        Build.VERSION.SDK_INT >= 31 && !lowPerformanceMode) {
                        catalogBackdropLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(catalogBackdropLayer)
                    } else drawContent()
                }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = !isNowPlaying }
                        .then(if (isNowPlaying) Modifier.clearAndSetSemantics { } else Modifier)
                        .graphicsLayer {
                            val browseAlpha = (1f - nowPlayingAlpha.value * 1.15f).coerceIn(0f, 1f)
                            alpha = browseAlpha
                            val scale = 1f - 0.03f * nowPlayingAlpha.value
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    CompositionLocalProvider(
                        LocalFocusFrameEnabled provides !isNowPlaying,
                        LocalFocusTargetsEnabled provides !isNowPlaying,
                        LocalFocusFrameVisibility provides browseVisibility,
                    ) {
                    TvBrowseViewport {
                    BrowseShell(
                        services = services,
                        interfaceStyleFocusPending = interfaceStyleFocusPending,
                        onBackExhausted = ::showExitConfirmation,
                        modalVisible = catalogActionItem != null || phoneSignInVisible || exitConfirmationVisible,
                        isModalOpen = { catalogActionItem != null || phoneSignInVisible || exitConfirmationVisible },
                        onDismissPendingModal = {
                            when {
                                exitConfirmationVisible -> dismissExitConfirmation()
                                phoneSignInVisible -> {
                                    pendingAuthentication.clear()
                                    pendingAuthAttempt = null
                                    closePhoneSignIn()
                                }
                                else -> dismissCatalogActions()
                            }
                        },
                        destination = activeBrowseDestination,
                        isBrowseVisible = !isNowPlaying,
                        currentTrack = track,
                        hasCurrentTrack = hasCurrentTrack,
                        isPlaying = isPlaying,
                        recentTracks = recentTracks,
                        shelves = shelvesByDestination[activeBrowseDestination].orEmpty(),
                        shelfPagination = (libraryNextPages.keys + libraryLoadingShelves + libraryFailedShelves + libraryTotals.keys + libraryVisitedPages.keys)
                            .associateWith { id -> LibraryShelfPagination(
                                id in libraryNextPages, id in libraryLoadingShelves, id in libraryFailedShelves, libraryTotals[id],
                                loadedPages = libraryVisitedPages[id]?.size ?: 0,
                                failureReason = libraryFailureReasons[id],
                                refreshGeneration = libraryRevision,
                            ) },
                        onLoadMoreShelf = { id ->
                            if (activeBrowseDestination == Destination.Radio) loadMoreRadioShelf(id)
                            else loadMoreLibraryShelf(id)
                        },
                        onSelectLibrarySection = ::ensureLibrarySection,
                        loading = activeBrowseDestination in loadingDestinations,
                        connectionState = connectionState,
                        setupMessage = serviceMessage ?: services.setupMessage,
                        onDestination = { next ->
                            if (next == Destination.NowPlaying) {
                                showNowPlaying()
                            } else {
                                previousDestination = next
                                destination = next
                            }
                        },
                        onSignIn = (::startTelevisionSignIn),
                        onPhoneSignIn = if (connectionState == MusicConnectionState.SignedIn) null else ::startPhoneSignIn,
                        ytmSignedIn = ytmSignedIn,
                        ytmPairing = ytmPairing,
                        ytmPairingConfigured = services.ytmPairingConfigured,
                        onYtmSignIn = (::startYtmPairing),
                        onYtmSignOut = {
                            ytmPairingEpoch[0]++
                            ytmPairingJob[0]?.cancel()
                            ytmPairingJob[0] = null
                            services.signOutYtm()
                            ytmPairing = null
                        },
                        onSignOut = {
                            pendingAuthentication.clear()
                            authenticatedAction = null
                            runtime.resetSession()
                            playbackPrefs.clearSession()
                            shelvesByDestination = emptyMap()
                            homeFeedSnapshot = null
                            feedNotices = emptyMap()
        feedFailureReasons = emptyMap()
                            feedSuccessTimes = emptyMap()
                            feedAttemptTimes = emptyMap()
                            feedSnapshotTimes = emptyMap()
                            resetLibraryPagination()
                            selectionGeneration++
                            services.signOut()
                            queueController.reset()
                            playbackPrefs.clearSession()
                            scope.launch { cancellableResult { services.contentCache.clear { ArtworkImages.clear(context) } } }
                            isPlaying = false
                            playWhenReady = false
                            waitingQueueEntryId = null
                            playbackPrepared = false
                            hasUserPlaybackRequest = false
                            hasCurrentTrack = false
                            lyricsByTrackId = emptyMap()
                            lyricsLookupFailures = emptySet()
                            activeLyrics = ActiveLyricsState(
                                trackIdentity = track.lyricsIdentity(),
                                lines = emptyList(),
                            )
                            connectionState = services.connectionState
                            shelvesByDestination = emptyMap()
                            recentTracks = emptyList()
                            recentTracksJob[0]?.cancel()
                            recentTracksPage = null
                            recentTracksScope = null
                            recentTracksLoadedAt = 0L
                            serviceMessage = AppText.text(R.string.app_apple_signed_out)
                            reloadNonce++
                        },
                        onRetry = (::refreshAllContent),
                        onOpenTrack = { item, q -> playTrack(item, contextQueue = q) },
                        requestedDetail = catalogDetailIntent,
                        onDetailHandled = { catalogDetailIntent = null },
                        onReturnToPlayer = { destination = Destination.NowPlaying },
                        deletedResource = catalogDeletedResource,
                        onDeletedResourceHandled = { catalogDeletedResource = null },
                        modalExitToken = catalogModalExitToken,
                        onPlayCollection = ::playCollection,
                        searchHistoryScopeKey = contentAccountScope + ":" + contentLanguageTag,
                        accountScopeKey = contentAccountScope,
                        libraryRevision = libraryRevision,
                        browseRefreshRevision = browseRefreshRevision,
                        onEnsureRecentTracks = ::ensureRecentTracks,
                        onRootVisibleChange = { rootFeedVisible = it },
                        contentNotice = feedNotices[activeBrowseDestination]?.let { stringResource(it) },
                        contentFailureReason = feedFailureReasons[activeBrowseDestination],
                        lyricOffsetMs = lyricOffsetMs,
                        onLyricOffsetChange = { offset: Long ->
                            lyricOffsetMs = offset
                            playbackPrefs.lyricOffsetMs = offset
                        },
                        autoplaySource = queueState.source,
                        onAutoplaySourceChange = { dispatchQueueAction(StartupQueueAction.SetSource(it)) },
                        lowPerformanceMode = lowPerformanceMode,
                        onLowPerformanceModeChange = {
                            lowPerformanceMode = it
                            playbackPrefs.lowPerformanceMode = it
                        },
                        cacheSettingsContent = { navigationFocus ->
                            val focusRequest = interfaceStyleFocusRequest
                            val finishStyleFocusRequest = {
                                if (focusRequest == interfaceStyleFocusRequest) interfaceStyleFocusPending = false
                            }
                            InterfaceStyleSettings(interfaceStyle, { selected ->
                                if (selected != interfaceStyle) {
                                    interfaceStyleFocusRequest++
                                    interfaceStyleFocusPending = true
                                    interfacePreferences.select(selected)
                                }
                            }, navigationFocus, interfaceStyleFocusPending,
                                focusRequestId = focusRequest,
                                onFocusRestored = finishStyleFocusRequest,
                                onFocusRestoreFailed = finishStyleFocusRequest)
                            ArtworkMotionSettings(artworkMotionEnabled, services.artworkMotionPreferences::setEnabled, navigationFocus)
                            LibraryAdditionSettings(services.libraryAdditionPreferences, navigationFocus)
                            PlaylistOrderSettings(services.playlistOrderPreferences, navigationFocus)
                            LanguageSettings(navigationFocus)
                            ContentCacheSettings(services.contentCache, navigationFocus)
                        },
                    )
                    }
                }
                }
                if (hasCurrentTrack || isNowPlaying || playerTransitionVisible) {
                    val isPlayerVisible = isNowPlaying || playerTransitionVisible
                    CompositionLocalProvider(
                        LocalFocusFrameEnabled provides isNowPlaying,
                        LocalFocusTargetsEnabled provides (isNowPlaying && catalogActionItem == null),
                        LocalFocusFrameVisibility provides nowPlayingAlpha,
                    ) {
                    PlayerScreen(
                        track = track.copy(lyrics = currentLyricsLines),
                        positionAnchorMs = positionAnchorMs,
                        playStartedRealtimeMs = playStartedRealtimeMs,
                        isPlaying = isPlaying,
                        playWhenReady = playWhenReady,
                        audioSpectrum = services::audioSpectrum,
                        feedbackInput = PlaybackFeedbackInput(
                            requestKey = selectionGeneration,
                            playRequested = playWhenReady,
                            isPlaying = isPlaying,
                            isPrepared = playbackPrepared,
                            isBuffering = audioBuffering,
                            isReconnecting = preparation.phase == PreparationPhase.Retrying,
                            waitingForNext = queueState.awaitingNext || waitingQueueEntryId != null,
                            failureKind = lastPlaybackFailure,
                            message = playbackMessage,
                        ),
                        isActiveScreen = isNowPlaying && catalogActionItem == null,
                        isVisibleOnScreen = isPlayerVisible,
                        queue = timelineTracks,
                        queueEntryIds = timelineEntryIds,
                        queueCurrentIndex = queueState.currentTimelineIndex,
                        queueSessionId = queueState.sessionId,
                        playbackSelectionKey = "${queueState.sessionId}|${queueState.current?.entryId}",
                        canClearQueue = queueState.upNext.isNotEmpty() || queueState.autoplay.isNotEmpty() || queueState.contextContinuationPending,
                        canSkipPrevious = if (track.catalogKind == CatalogKind.Station) canSkipPrevious else queueState.canSkipPrevious,
                        canSkipNext = if (track.catalogKind == CatalogKind.Station) canSkipNext else queueState.canSkipNext,
                        canSeek = (MockCatalog.isDemoTrack(track) || (nativeCanSeek && playbackPrepared)) && track.durationMs > 0L,
                        repeatMode = if (track.catalogKind == CatalogKind.Station) PlaybackRepeatMode.Off else queueState.repeat,
                        shuffleEnabled = track.catalogKind != CatalogKind.Station && queueState.shuffle,
                        autoplayEnabled = track.catalogKind != CatalogKind.Station && queueState.infinite,
                        canSetRepeat = track.catalogKind != CatalogKind.Station,
                        canSetShuffle = track.catalogKind != CatalogKind.Station &&
                            (!queueState.contextContinuationPending || queueState.shuffle),
                        canSetAutoplay = track.catalogId != null &&
                            track.catalogKind == dev.amatv.model.CatalogKind.Song,
                        autoplaySource = queueState.source,
                        onToggleAutoplaySource = { dispatchQueueAction(StartupQueueAction.ToggleSource) },
                        onClearQueue = { dispatchQueueAction(StartupQueueAction.ClearUpcoming) },
                        onQueueItemActions = { entryId ->
                            if (catalogActionItem == null) queueController.state.value.sessionEntries.firstOrNull { it.entryId == entryId }?.let { entry ->
                                catalogModalInput.menuOpened()
                                catalogQueueActionEntryId = entryId
                                catalogBackHandler = null
                                pendingCatalogBack = false
                                catalogActionAnchor = null
                                catalogActionItem = entry.track
                            }
                        },
                        lyricsLoading = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.phase == LyricsLoadPhase.Loading,
                        lyricsLookupFailed = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.phase == LyricsLoadPhase.Unavailable,
                        lyricsTransientFailure = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.phase == LyricsLoadPhase.Failed,
                        onRetryLyrics = ::retryCurrentLyrics,
                        lyricOffsetMs = lyricOffsetMs,
                        statusMessage = playbackMessage ?: if (queueState.awaitingNext) {
                            (queueState.autoplayStatus as? dev.amatv.playback.AutoplayStatus.Error)?.message
                        } else null,
                        onTogglePlayback = (::togglePlayback),
                        onSkipPrevious = { skip(-1) },
                        onSkipNext = { skip(1) },
                        onSeekBy = ::seekPlaybackBy,
                        onPlayFromLyric = if (hasCurrentTrack) ::playFromLyric else null,
                        onSelectTrack = (::selectQueueTrack),
                        lowPerformanceMode = lowPerformanceMode,
                        lyricTranslationEnabled = lyricTranslationEnabled,
                        lyricPronunciationEnabled = lyricPronunciationEnabled,
                        lyricPronunciationLarger = lyricPronunciationLarger,
                        lyricTranslationLanguageTag = lyricTranslationLanguageTag,
                        lyricPronunciationLanguageTag = lyricPronunciationLanguageTag,
                        appLanguageTag = lyricsLanguageTag,
                        lyricsEnhancementsReady = !lyricsLocalization.enabled ||
                            (activeLyrics.trackIdentity == currentLyricsIdentity &&
                                activeLyrics.resolvedVariant == lyricsVariant &&
                                activeLyrics.phase == LyricsLoadPhase.Ready),
                        lyricTranslationReady = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.translationReadyFor(lyricsLocalization),
                        lyricPronunciationReady = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.pronunciationReadyFor(lyricsLocalization),
                        onLyricTranslationEnabledChange = { enabled ->
                            lyricTranslationEnabled = enabled
                            playbackPrefs.lyricTranslationEnabled = enabled
                        },
                        onLyricPronunciationEnabledChange = { enabled ->
                            lyricPronunciationEnabled = enabled
                            playbackPrefs.lyricPronunciationEnabled = enabled
                        },
                        onLyricPronunciationLargerChange = { larger ->
                            lyricPronunciationLarger = larger
                            playbackPrefs.lyricPronunciationLarger = larger
                        },
                        onLyricTranslationLanguageChange = { languageTag ->
                            lyricTranslationLanguageTag = languageTag
                            playbackPrefs.lyricTranslationLanguageTag = languageTag
                        },
                        onLyricPronunciationLanguageChange = { languageTag ->
                            lyricPronunciationLanguageTag = languageTag
                            playbackPrefs.lyricPronunciationLanguageTag = languageTag
                        },
                        onCycleRepeat = { if (track.catalogKind != CatalogKind.Station) dispatchQueueAction(StartupQueueAction.CycleRepeat) },
                        onToggleShuffle = { if (track.catalogKind != CatalogKind.Station) dispatchQueueAction(StartupQueueAction.ToggleShuffle) },
                        onToggleAutoplay = { dispatchQueueAction(StartupQueueAction.ToggleInfinite) },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusProperties { canFocus = isNowPlaying }
                            .then(if (!isNowPlaying) Modifier.clearAndSetSemantics { } else Modifier)
                            .graphicsLayer {
                                alpha = nowPlayingAlpha.value
                                val scale = 0.97f + 0.03f * nowPlayingAlpha.value
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                    }
                }
                } // Exclude the context menu itself from its recorded material source.
                catalogActionItem?.let { item ->
                    val enqueueSession = remember(item) { queueController.state.value.sessionId }
                    val queueEntry = catalogQueueActionEntryId?.let { id ->
                        queueController.state.value.sessionEntries.firstOrNull { it.entryId == id }
                    }
                    if (queueEntry != null) QueueEntryActionSheet(
                        queueEntry, queueState, services, connectionState == MusicConnectionState.SignedIn,
                        onSignIn = { catalogActionItem = null; startSignIn() },
                        onDismiss = ::dismissCatalogActions,
                        onLibraryChanged = ::libraryChanged,
                        onResourceDeleted = { deleted ->
                            catalogDeletedResource = CatalogResourceRef.fromTrack(deleted)
                            libraryChanged()
                        },
                        onViewArtist = { artist ->
                            val returnToPlayer = destination == Destination.NowPlaying
                            catalogActionItem = null
                            if (destination == Destination.NowPlaying) destination = previousDestination
                            catalogDetailIntent = CatalogDetailIntent(artist, returnToPlayer = returnToPlayer)
                        },
                        onOpenResource = { resource ->
                            val returnToPlayer = destination == Destination.NowPlaying
                            catalogActionItem = null
                            if (destination == Destination.NowPlaying) destination = previousDestination
                            catalogDetailIntent = CatalogDetailIntent(resource, returnToPlayer = returnToPlayer)
                        },
                        onShowDetails = { resource ->
                            val returnToPlayer = destination == Destination.NowPlaying
                            catalogActionItem = null
                            if (destination == Destination.NowPlaying) destination = previousDestination
                            catalogDetailIntent = CatalogDetailIntent(resource, metadata = true, returnToPlayer = returnToPlayer)
                        },
                        onCreateStation = { seed -> catalogActionItem = null; playTrack(seed, startStation = true) },
                        sleepTimerState = sleepTimerState,
                        canSleepAtTrackEnd = runtime.canSleepAtTrackEnd,
                        canSleepAtContextEnd = runtime.canSleepAtContextEnd,
                        onSleepTimer = if (queueEntry.entryId == queueState.current?.entryId) { option ->
                            if (queueController.state.value.current?.entryId == queueEntry.entryId) runtime.setSleepTimer(option)
                        } else null,
                        onRemove = { queueController.removeUpcoming(it) },
                        onMoveBy = { id, offset -> queueController.moveUpcomingBy(id, offset) },
                        backdropLayer = catalogBackdropLayer.takeIf { interfaceStyle == InterfaceStyle.Apple &&
                            Build.VERSION.SDK_INT >= 31 && !lowPerformanceMode },
                        lowPerformanceMode = lowPerformanceMode,
                        onMediaKey = ::dispatchMediaKey, modalInputGate = catalogModalInput,
                        accountScopeKey = contentAccountScope,
                        onRegisterBackHandler = { catalogBackHandler = it },
                    ) else
                    CatalogActionSheet(item, services, connectionState == MusicConnectionState.SignedIn,
                        sleepTimerState = sleepTimerState,
                        canSleepAtTrackEnd = runtime.canSleepAtTrackEnd,
                        canSleepAtContextEnd = runtime.canSleepAtContextEnd,
                        onSleepTimer = if (hasCurrentTrack && item.matchesPlaybackQueueEntry(track)) { option ->
                            if (queueController.state.value.current?.track?.matchesPlaybackQueueEntry(item) == true) runtime.setSleepTimer(option)
                        } else null,
                        onMediaKey = ::dispatchMediaKey,
                        modalInputGate = catalogModalInput,
                        onSignIn = { catalogActionItem = null; startSignIn() },
                        onDismiss = ::dismissCatalogActions, onLibraryChanged = ::libraryChanged,
                        onResourceDeleted = { deleted ->
                            catalogDeletedResource = CatalogResourceRef.fromTrack(deleted)
                            libraryChanged()
                        },
                        onViewArtist = { artist ->
                            val returnToPlayer = destination == Destination.NowPlaying
                            catalogActionItem = null
                            if (destination == Destination.NowPlaying) destination = previousDestination
                            catalogDetailIntent = CatalogDetailIntent(artist, returnToPlayer = returnToPlayer)
                        }, onOpenResource = { resource ->
                            val returnToPlayer = destination == Destination.NowPlaying
                            catalogActionItem = null
                            if (destination == Destination.NowPlaying) destination = previousDestination
                            catalogDetailIntent = CatalogDetailIntent(resource, returnToPlayer = returnToPlayer)
                        }, extraActions = buildList {
                            if (CatalogResourceRef.fromTrack(item) != null) add(CatalogSheetAction("details", stringResource(R.string.catalog_details)) {
                                val returnToPlayer = destination == Destination.NowPlaying
                                catalogActionItem = null
                                if (destination == Destination.NowPlaying) destination = previousDestination
                                catalogDetailIntent = CatalogDetailIntent(item, metadata = true, returnToPlayer = returnToPlayer)
                            })
                            if (item.catalogKind == CatalogKind.Song && item.catalogId != null) add(
                                CatalogSheetAction("create-station", stringResource(R.string.actions_create_station)) {
                                    catalogActionItem = null
                                    playTrack(item, startStation = true)
                                })
                        },
                        onQueue = if (queueState.current != null && item.catalogKind in setOf(
                                CatalogKind.Song, CatalogKind.Album, CatalogKind.Playlist)) { playNext ->
                            val entries = if (item.openAction == CatalogOpenAction.Collection) services.collectionPlaybackTracks(item) else listOf(item)
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            check(queueController.state.value.sessionId == enqueueSession)
                            check(if (playNext) queueController.enqueueNext(entries) else queueController.enqueueLast(entries))
                        } else null,
                        anchorBounds = catalogActionAnchor,
                        backdropLayer = catalogBackdropLayer.takeIf { interfaceStyle == InterfaceStyle.Apple &&
                            Build.VERSION.SDK_INT >= 31 && !lowPerformanceMode },
                        lowPerformanceMode = lowPerformanceMode,
                        accountScopeKey = contentAccountScope,
                        onRegisterBackHandler = { catalogBackHandler = it })
                }
                if (phoneSignInVisible) PhoneMusicSignInDialog(
                    url = phoneSignInSession?.pairing?.url,
                    phase = phoneSignInPhase,
                    onRetry = { closePhoneSignIn(); startPhoneSignIn() },
                    onDismiss = { pendingAuthentication.clear(); pendingAuthAttempt = null; closePhoneSignIn() },
                )
                if (exitConfirmationVisible) AppExitDialog(
                    backgroundPlayback = playWhenReady || isPlaying,
                    inputGate = catalogModalInput,
                    onStay = ::dismissExitConfirmation,
                    onExit = {
                        exitConfirmationVisible = false
                        (context as? android.app.Activity)?.finish()
                    },
                )
                FocusFrameOverlay(focusFrameController)
            }
            }
        }
    }
}
