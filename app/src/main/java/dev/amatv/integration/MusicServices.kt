package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.content.Context
import android.content.MutableContextWrapper
import android.app.Activity
import android.content.Intent
import androidx.core.content.ContextCompat
import android.media.audiofx.AudioEffect
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import dev.amatv.BuildConfig
import dev.amatv.model.ContentShelf
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.CatalogResourceProfile
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.CatalogResourceSection
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.CatalogKind
import dev.amatv.model.matchesPlaybackQueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI

enum class RadioShelfSource {
    Personal, Public, Recent;
    fun ownsShelf(id: String): Boolean = when (this) {
        Personal -> id == "radio-personal"
        Recent -> id == "radio-recent"
        Public -> id == "radio-live" || id.startsWith("public-radio:")
    }
}

data class RadioShelvesResult(
    val shelves: List<ContentShelf>,
    val successfulSources: Set<RadioShelfSource>,
    val failures: Map<RadioShelfSource, Exception>,
)

enum class MusicConnectionState {
    Demo,
    ReadyToSignIn,
    SignedIn,
}

interface RecommendationProvider {
    suspend fun createAmStation(songId: String): Pair<String?, List<Track>>
    suspend fun amStationNextTracks(stationId: String): List<Track>
    suspend fun ytmRadio(
        seed: Track,
        continuation: String? = null,
        excludedCatalogIds: Set<String> = emptySet(),
        continuationSeedVideoId: String? = null,
    ): MusicServices.ResolvedRadioPage
}

class MusicServices(context: Context) : RecommendationProvider {
    private val appContext = context.applicationContext
    /** Dynamic app locale, including AppCompat locales on API 28–32. */
    fun effectiveLanguageTag(): String = ContextCompat.getContextForLanguage(appContext)
        .resources.configuration.locales[0].toLanguageTag()
    suspend fun supportedCatalogLanguageTag(): String? = api.catalogLanguage(api.effectiveStorefront())
    val contentCache = ContentCache.get(appContext)
    val artworkMotionPreferences = ArtworkMotionPreferences.get(appContext)
    val libraryAdditionPreferences = LibraryAdditionPreferences(appContext)
    private val tokenStore = SecureTokenStore(appContext)
    private val webAuthenticationSession = WebAuthenticationSession(appContext)
    private val developerTokens = DeveloperTokenClient(
        endpoint = BuildConfig.MUSICKIT_TOKEN_ENDPOINT,
        tokenStore = tokenStore,
        bundledToken = BuildConfig.MUSICKIT_DEVELOPER_TOKEN,
    )
    private val bridge = MusicKitBridge(appContext)
    private val playbackHostContext = MutableContextWrapper(appContext).apply {
        if (context is Activity) baseContext = context
    }
    private val stationPlayer = MusicKitStationPlayer(playbackHostContext)
    private enum class PlaybackEngine { Native, StationWeb }
    private class EngineRequest(val engine: PlaybackEngine, val generation: Long) {
        var localGeneration = 0L
        var pending: PlaybackSnapshot? = null
    }
    private val playbackEpoch = AtomicLong()
    private val engineLock = Any()
    private val playbackMain = Handler(Looper.getMainLooper())
    private var activeEngineRequest: EngineRequest? = null
    @Volatile private var serviceSnapshot: PlaybackSnapshot? = null
    @Volatile private var runtimePlaybackListener: ((PlaybackSnapshot) -> Unit)? = null
    @Volatile private var spectrumActive = false
    private val songSpectrum = SongAudioSpectrum(appContext) {
        // A station WebView does not expose the native renderer's audio session.
        // Never visualize an old native song as if it belonged to the station.
        val nativeActive = synchronized(engineLock) { activeEngineRequest?.engine == PlaybackEngine.Native }
        if (nativeActive) bridge.audioSessionId() else 0
    }
    private val mediaSession = PlaybackMediaSession(appContext)
    private val playbackPreparation = Mutex()
    private val api = AppleMusicApi(
        storefront = BuildConfig.APPLE_MUSIC_GUEST_STOREFRONT,
        developerTokens = developerTokens,
        userToken = tokenStore::readUserToken,
    ).apply { language = ::effectiveLanguageTag }
    private val webPageToken = AppleWebPlaybackTokenClient()
    internal val webRequest = AppleMusicWebRequest(tokenStore::readUserToken, webPageToken)
    val libraryManagement = CatalogLibraryManagement(api::effectiveStorefront) { path, method, body ->
        webRequest.request(path, method, body, fresh = method == "GET")
    }
    private val publicBrowse = PublicMusicBrowseClient(
        storefront = api::effectiveStorefront,
        language = { effectiveLanguageTag() },
        editorialRequest = ::requestPublicEditorial,
        parseResource = api::parseResourceItem,
    )
    private val publicEditorialMotion = PublicEditorialMotionClient(
        storefront = api::effectiveStorefront,
        language = { effectiveLanguageTag() },
        cacheDirectory = java.io.File(appContext.cacheDir, "editorial-motion-metadata-v1"),
        cacheGeneration = { contentCache.generation },
        cacheEnabled = { contentCache.settings.value.let { it.enabled && !it.clearing } },
        canWriteCache = { contentCache.canWrite() },
    )
    private val editorialArtworkResolver = EditorialArtworkResolver(
        scopeKey = { "${api.userStorefront()}:${effectiveLanguageTag()}" },
        albumForSong = { song ->
            catalogPages.sectionPage(dev.amatv.model.CatalogResourceRef(checkNotNull(song.catalogId), "songs"),
                dev.amatv.model.CatalogResourceSection("albums", resourceTypes = setOf("albums"), pageSize = 10))
                .items.firstOrNull { it.catalogKind == dev.amatv.model.CatalogKind.Album }
        },
        publicArtwork = publicEditorialMotion::artwork,
        cacheGeneration = { contentCache.generation },
    )
    internal suspend fun editorialArtwork(item: Track): Track =
        if (artworkMotionPreferences.enabled.value) editorialArtworkResolver.artwork(item) else item
    internal fun cachedEditorialArtwork(item: Track): Track =
        api.cachedEffectiveStorefront()?.let { publicEditorialMotion.cachedArtwork(item, it) } ?: item
    val catalogActions: CatalogActions get() = api.actions
    val catalogPages: CatalogPagesApi get() = api.catalogPages
    val accountInsights get() = api.accountInsights
    val catalogCharts get() = api.catalogCharts
    val mediaCapabilities get() = bridge.mediaCapabilities
    val presentationCapabilities get() = CatalogPresentationCapabilities(mediaCapabilities,
        stationWebPlaybackAvailable = stationPlayer.available)

    private val appleLyrics = AppleMusicLyricsClient(
        storefrontProvider = api::userStorefront,
        userToken = tokenStore::readUserToken,
        debugDumpDir = if (BuildConfig.DEBUG) java.io.File(appContext.cacheDir, "lyrics-ttml") else null,
        contentCache = contentCache,
        webToken = webPageToken,
    )
    private val google = GoogleTvAuthorization(tokenStore)
    private val ytm = YtmInnertubeClient(country = api::effectiveStorefront, accessToken = google::accessToken)
    private val resolver = CrossCatalogResolver(api)

    /** Opaque per-authorization cache boundary; tokens never reach cache keys or logs. */
    fun authorizationCacheScope(): String = appleAuthorizationCacheScope(tokenStore.readUserToken())

    /** The complete read/prepare/write/readback chain keeps the account captured by its UI owner. */
    suspend fun <T> withAuthorizationScope(expectedScope: String, operation: suspend () -> T): T {
        val token = tokenStore.readUserToken()
        if (appleAuthorizationCacheScope(token) != expectedScope) throw CancellationException("Apple account changed")
        val account = AppleAuthorizationScope(token)
        return withContext(account) {
            account.checkCurrent(tokenStore::readUserToken)
            operation().also { account.checkCurrent(tokenStore::readUserToken) }
        }
    }

    /** A retained engine can reattach its tiny station view without retaining an old Activity. */
    fun attachPlaybackHost(activity: Activity?) {
        val attach = {
            playbackHostContext.baseContext = activity ?: appContext
            stationPlayer.attachHost(activity)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) attach() else playbackMain.post { attach() }
    }

    val ytmPairingConfigured get() = google.configured
    val ytmSignedIn get() = google.signedIn
    val ytmAuthorizationState get() = google.state
    fun ytmAuthorizationCacheScope(): String = google.state.value.scope
    fun cancelYtmPairing() = google.cancelPairing()
    suspend fun startYtmPairing(): GoogleTvPairing = google.startPairing()
    suspend fun awaitYtmPairing(pairing: GoogleTvPairing) = google.awaitPairing(pairing)
    fun signOutYtm() = google.signOut()

    data class ResolvedRadioPage(
        val tracks: List<Track>,
        val continuation: String?,
        val seedVideoId: String?,
    )

    private suspend fun resolveRecommendation(song: YtmSong): Track? = try {
        resolver.resolve(song)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w("AMATV-YTM", "Skipping recommendation ${song.videoId}", error)
        null
    }

    override suspend fun ytmRadio(
        seed: Track,
        continuation: String?,
        excludedCatalogIds: Set<String>,
        continuationSeedVideoId: String?,
    ): ResolvedRadioPage {
        val saved = YtmResolutionCursor.decode(continuation)
        val videoId = saved?.seedVideoId
            ?: continuationSeedVideoId?.takeIf { continuation != null }
            ?: seed.ytmVideoId ?: ytm.searchSong(seed.title, seed.artist, seed.durationMs)?.videoId
            ?: return ResolvedRadioPage(emptyList(), null, null)
        val candidates: List<YtmSong>
        val providerNext: String?
        if (saved != null && saved.pending.isNotEmpty()) {
            candidates = saved.pending
            providerNext = saved.providerContinuation
        } else {
            // A corrupt local cursor restarts the original seed instead of sending
            // application metadata as an undocumented provider token.
            val page = ytm.radio(videoId, continuation.takeUnless { YtmResolutionCursor.isLocal(it) })
            candidates = page.songs
            providerNext = page.continuation
        }
        // Resolve a bounded batch in provider order. Preserve every remaining song
        // in the persisted cursor rather than dropping everything after take(15).
        val batch = candidates.take(12)
        val resolved = coroutineScope {
            batch.map { song -> async { resolveRecommendation(song) } }.awaitAll()
                .filterNotNull().filter { it.catalogId !in excludedCatalogIds }
                .distinctBy { it.catalogId }
        }
        val next = YtmResolutionCursor(videoId, candidates.drop(batch.size), providerNext).encode()
        return ResolvedRadioPage(resolved, next, videoId)
    }

    suspend fun ytmHomeShelves(): List<ContentShelf> = coroutineScope {
        ytm.home().sortedByDescending { shelf ->
            when {
                shelf.title.contains("supermix", ignoreCase = true) -> 3
                shelf.title.contains("listen again", ignoreCase = true) -> 2
                else -> 1
            }
        }.take(3).mapIndexed { index, shelf ->
            async {
                val songs = if (shelf.playlistId != null && shelf.songs.isEmpty()) {
                    ytm.playlist(shelf.playlistId)
                } else shelf.songs
                val tracks = songs.take(6).map { song ->
                    async { resolveRecommendation(song) }
                }.awaitAll().filterNotNull().distinctBy { it.catalogId ?: it.id }
                ContentShelf("ytm:$index", "YTM · ${shelf.title}", tracks)
            }
        }.awaitAll().filter { it.items.isNotEmpty() }
    }

    val connectionState: MusicConnectionState
        get() = when {
            tokenStore.readUserToken() != null -> MusicConnectionState.SignedIn
            isCatalogConfigured ->
                MusicConnectionState.ReadyToSignIn
            else -> MusicConnectionState.Demo
        }

    val setupMessage: String?
        get() = when {
            !isCatalogConfigured -> AppText.text(R.string.services_catalog_not_configured)
            BuildConfig.MUSICKIT_DEVELOPER_TOKEN.isNotBlank() &&
                !DeveloperTokenPolicy.isUsable(BuildConfig.MUSICKIT_DEVELOPER_TOKEN) &&
                BuildConfig.MUSICKIT_TOKEN_ENDPOINT.isBlank() -> AppText.text(R.string.services_credentials_expired_update)
            else -> null
        }

    val isCatalogConfigured: Boolean
        get() = DeveloperTokenPolicy.isConfigured(
            BuildConfig.MUSICKIT_DEVELOPER_TOKEN, BuildConfig.MUSICKIT_TOKEN_ENDPOINT,
        )

    suspend fun webAuthenticationIntent(): Intent {
        // Gecko owns a fresh private profile. Do not initialize the system
        // WebView provider on a compatibility build whose provider may be broken.
        if (!BuildConfig.AUTH_BROWSER_IS_GECKO) webAuthenticationSession.prepareForSignIn()
        val developerToken = developerTokens.token()
        return Intent().setClassName(appContext, BuildConfig.AUTH_BROWSER_ACTIVITY).putExtra(
            MusicKitWebAuthActivity.EXTRA_DEVELOPER_TOKEN,
            developerToken,
        )
    }

    internal suspend fun phoneAuthentication(advertisedAuthority: String? = null, port: Int = 0): PhoneMusicAuthorization {
        val developerToken = developerTokens.token()
        var created: PhoneMusicAuthorization? = null
        try {
            return withContext(Dispatchers.IO) {
                PhoneMusicAuthorization(developerToken, BuildConfig.VERSION_NAME,
                    effectiveLanguageTag(),
                    advertisedAuthority = advertisedAuthority, port = port).also { created = it }
            }
        } catch (failure: Throwable) {
            // The return dispatch can be cancelled after IO created the socket.
            created?.close()
            throw failure
        }
    }

    internal class ValidatedPhoneAuthentication internal constructor(
        internal val userToken: String,
        internal val previousToken: String?,
    )

    /** Candidate validation never changes the retained account. */
    internal suspend fun validatePhoneAuthentication(userToken: String): ValidatedPhoneAuthentication {
        require(isPhoneMusicUserToken(userToken)) { AppText.text(R.string.services_auth_invalid_user_token) }
        val previous = tokenStore.readUserToken()
        val candidate = AppleMusicApi(BuildConfig.APPLE_MUSIC_GUEST_STOREFRONT, developerTokens) { userToken }
            .apply { language = ::effectiveLanguageTag }
        candidate.userStorefront()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return ValidatedPhoneAuthentication(userToken, previous)
    }

    /** Called synchronously by the current main-thread attempt, immediately before UI handoff. */
    internal fun completePhoneAuthentication(candidate: ValidatedPhoneAuthentication) {
        check(tokenStore.replaceUserToken(candidate.previousToken, candidate.userToken)) { "Apple account changed" }
        mediaSession.clear()
        resetPlaybackEngines()
    }

    fun completeWebAuthentication(data: Intent): String {
        val userToken = checkNotNull(
            data.getStringExtra(MusicKitWebAuthActivity.EXTRA_USER_TOKEN),
        ) { AppText.text(R.string.services_auth_invalid_user_token) }
        tokenStore.writeUserToken(userToken)
        mediaSession.clear()
        resetPlaybackEngines()
        return userToken
    }

    fun signOut() {
        webAuthenticationSession.markLocalWebSessionForReset()
        setSpectrumActive(false)
        mediaSession.clear()
        resetPlaybackEngines()
        tokenStore.clear()
    }

    suspend fun recommendationShelves(): List<ContentShelf> = api.recommendationShelves()
    suspend fun browseShelves(): List<ContentShelf> = publicBrowse.shelves(presentationCapabilities)
    /** Public editorial pages retain the same native detail browser and lifecycle. */
    internal suspend fun resourceProfile(ref: CatalogResourceRef): CatalogResourceProfile {
        if (ref.type == "public-rooms") return publicBrowse.profile(ref, presentationCapabilities)
        if (ref.type != "public-curators") return withPublicSongCredits(catalogPages.profile(ref))
        val original = catalogPages.profile(CatalogResourceRef(ref.id, "apple-curators")).let {
            it.copy(ref = ref, item = it.item.copy(resourceType = ref.type))
        }
        val editorial = try { publicBrowse.profile(ref, presentationCapabilities) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return original
        }
        return original.copy(sections = original.sections + editorial.sections.filter { "stations" in it.resourceTypes })
    }

    private suspend fun withPublicSongCredits(profile: CatalogResourceProfile): CatalogResourceProfile {
        val songId = when (profile.ref.type) {
            "songs" -> profile.ref.id
            "library-songs" -> profile.item.catalogId
            else -> null
        }?.takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: return profile
        if (profile.metadata.any { it.key.startsWith("credits:") }) return profile
        val storefront = api.cachedEffectiveStorefront()?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: return profile
        val language = api.catalogLanguage(storefront)
        val path = "/v1/catalog/$storefront/songs/$songId?include=credits,albums,artists" +
            "&format%5Bresources%5D=map&platform=web" +
            (language?.let { "&l=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}" } ?: "")
        return try {
            // Public names and roles need the web Origin contract, not an
            // account write or another player/client. Keep Library identity.
            val root = webRequest.request(path, needsUserToken = false, language = language)
            catalogPages.attachSongCredits(profile, root)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); profile }
    }

    internal suspend fun resourceSectionPage(ref: CatalogResourceRef, section: CatalogResourceSection,
        nextPage: String?): CatalogResourcePage = when {
        ref.type == "public-rooms" || ref.type == "public-curators" && section.id.startsWith("public:") ->
            publicBrowse.sectionPage(ref, section, nextPage, presentationCapabilities)
        ref.type == "public-curators" -> catalogPages.sectionPage(CatalogResourceRef(ref.id, "apple-curators"), section, nextPage)
        else -> catalogPages.sectionPage(ref, section, nextPage)
    }

    private suspend fun requestPublicEditorial(path: String): JSONObject {
        val uri = URI("https://amp-api.music.apple.com$path")
        require(uri.host == "amp-api.music.apple.com" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
            uri.path.matches(Regex("/v1/editorial/[a-z]{2}/rooms/[1-9][0-9]{0,19}/contents")))
        return try {
            webRequest.request(path, needsUserToken = false,
                maxResponseBytes = PublicMusicBrowsePage.MAX_JSON_BYTES,
                language = effectiveLanguageTag())
        } catch (failure: CatalogRequestException) {
            // Anonymous editorial access uses the same Origin contract as the
            // existing lyrics client. Rejected access must not demand re-login.
            if (failure.httpStatus in setOf(401, 403)) throw CatalogRequestException(CatalogFailureReason.Unavailable,
                AppText.text(R.string.browse_content_unavailable), failure.httpStatus, failure.serviceCode)
            throw failure
        }
    }
    suspend fun recentlyPlayedPage(): LibraryPage = api.recentlyPlayedPage()
    suspend fun recentlyPlayedTracksPage(): LibraryPage = api.recentlyPlayedTracksPage()
    suspend fun recentlyAddedPage(nextPage: String? = null): LibraryPage = api.recentlyAddedPage(nextPage)
    suspend fun libraryAlbumsPage(nextPage: String? = null): LibraryPage = api.libraryAlbumsPage(nextPage)
    suspend fun libraryArtistsPage(nextPage: String? = null): LibraryPage = api.libraryArtistsPage(nextPage)
    suspend fun artistProfile(artist: Track): dev.amatv.model.ArtistProfile = api.artistProfile(artist)
    suspend fun artistSectionPage(artist: Track, section: dev.amatv.model.ArtistSection, nextPage: String? = null): LibraryPage =
        api.artistSectionPage(artist, section, nextPage)
    suspend fun relatedArtistsPage(item: Track, nextPage: String? = null): LibraryPage = api.relatedArtistsPage(item, nextPage)
    suspend fun searchSuggestions(term: String): dev.amatv.model.CatalogSearchSuggestions = api.searchSuggestions(term)
    suspend fun searchPage(term: String, type: dev.amatv.model.CatalogSearchType, nextPage: String? = null): LibraryPage =
        api.searchPage(term, type, nextPage)
    suspend fun librarySearchPage(term: String, type: dev.amatv.model.CatalogSearchType, nextPage: String? = null): LibraryPage =
        api.librarySearchPage(term, type, nextPage)
    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage = api.libraryPlaylistsPage(nextPage)
    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage = api.librarySongsPage(nextPage)
    suspend fun radioSectionPage(section: RadioCatalogSection, nextPage: String? = null): LibraryPage =
        api.radioCatalog.page(section, nextPage)

    /** Independent sources publish successful-empty ownership as well as their rows. */
    suspend fun radioShelves(
        onPage: (String, LibraryPage) -> Unit = { _, _ -> },
        onFinished: (Boolean) -> Unit = {},
    ): RadioShelvesResult = coroutineScope {
        val sections = linkedMapOf<RadioShelfSource, List<ContentShelf>>()
        val failures = linkedMapOf<RadioShelfSource, Exception>()
        suspend fun read(source: RadioShelfSource, operation: suspend () -> List<ContentShelf>) {
            try { sections[source] = operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures[source] = failure }
        }
        val publicRequest = async { read(RadioShelfSource.Public) {
            try { publicBrowse.radioShelves(presentationCapabilities) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val page = radioSectionPage(RadioCatalogSection.Live)
                onPage("radio-live", page)
                listOf(ContentShelf("radio-live", AppText.text(R.string.app_apple_music_radio), page.items,
                    hasMore = page.nextPage != null)).filter { it.items.isNotEmpty() || it.hasMore }
            }
        } }
        val personal = async { read(RadioShelfSource.Personal) {
            if (connectionState != MusicConnectionState.SignedIn) emptyList() else {
                val page = radioSectionPage(RadioCatalogSection.Personal)
                onPage("radio-personal", page)
                listOf(ContentShelf("radio-personal", AppText.text(R.string.radio_for_you), page.items,
                    hasMore = page.nextPage != null)).filter { it.items.isNotEmpty() || it.hasMore }
            }
        } }
        val recent = async { read(RadioShelfSource.Recent) {
            if (connectionState != MusicConnectionState.SignedIn) emptyList() else {
                val page = radioSectionPage(RadioCatalogSection.Recent)
                onPage("radio-recent", page)
                listOf(ContentShelf("radio-recent", AppText.text(R.string.radio_recent), page.items,
                    hasMore = page.nextPage != null)).filter { it.items.isNotEmpty() || it.hasMore }
            }
        } }
        awaitAll(publicRequest, personal, recent)
        onFinished(failures.isEmpty())
        RadioShelvesResult(listOf(RadioShelfSource.Personal, RadioShelfSource.Public, RadioShelfSource.Recent)
            .flatMap { sections[it].orEmpty() }, sections.keys.toSet(), failures.toMap())
    }
    override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> =
        api.createContinuousStation(songId)
    override suspend fun amStationNextTracks(stationId: String): List<Track> =
        api.stationNextTracks(stationId)
    suspend fun collectionTracks(collection: Track): List<Track> = api.collectionTracks(collection)
    suspend fun collectionTracksPage(collection: Track, nextPage: String? = null): LibraryPage = api.collectionTracksPage(collection, nextPage)
    internal suspend fun sampleCollectionShuffleSong(collection: Track, total: Int): CollectionShuffleSample? =
        api.sampleCollectionShuffleSong(collection, total)

    suspend fun lyricsFor(
        track: Track,
        localization: LyricsLocalizationRequest = LyricsLocalizationRequest.Disabled,
    ): List<LyricLine> {
        val playable = resolvePlayableTrack(track)
        return appleLyrics.lyricsFor(checkNotNull(playable.catalogId), localization)
    }

    /** Resolve all songs through account-storefront equivalence, retaining source provenance. */
    suspend fun resolvePlayableTrack(track: Track): Track {
        if (track.catalogKind != dev.amatv.model.CatalogKind.Song) return track
        return resolvePlayableSongTrack(track, api::resolveCatalogSongId)
    }

    suspend fun preparePlayback(
        track: Track,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
        currentAppEntryId: Long? = null,
        upcoming: List<NativeQueueEntry> = emptyList(),
    ): Long {
        val userToken = checkNotNull(tokenStore.readUserToken()) { AppText.text(R.string.services_sign_in_apple_music) }
        val developerToken = developerTokens.token()
        return playbackPreparation.withLock {
            withContext(Dispatchers.Default) {
                if (playWhenReady) mediaSession.playbackRequested()
                val engine = if (track.catalogKind == dev.amatv.model.CatalogKind.Station &&
                    !mediaCapabilities.catalogStations) PlaybackEngine.StationWeb else PlaybackEngine.Native
                val request = EngineRequest(engine, playbackEpoch.incrementAndGet())
                synchronized(engineLock) { activeEngineRequest = request; serviceSnapshot = null }
                refreshSpectrumRoute()
                try {
                    val local = if (engine == PlaybackEngine.StationWeb) {
                        bridge.pause()
                        bridge.release()
                        // Cached after browsing; a cold request uses the reserved
                        // playback dispatcher. Never turn a short queue/network
                        // delay into a different account's default storefront.
                        val stationStorefront = api.userStorefront()
                        stationPlayer.preparePlayback(track, developerToken, userToken, positionMs, playWhenReady,
                            storefront = stationStorefront)
                    } else {
                        stationPlayer.release()
                        bridge.prepareQueuedSuccessor(track, developerToken, userToken, positionMs,
                            playWhenReady, currentAppEntryId)
                            ?: bridge.preparePlayback(track, developerToken, userToken, positionMs, playWhenReady,
                                currentAppEntryId, upcoming)
                    }
                    val pending = synchronized(engineLock) {
                        request.localGeneration = local
                        request.pending.also { request.pending = null }
                    }
                    pending?.takeIf { it.requestGeneration == local }?.let { snapshot ->
                        playbackMain.post { forwardPlaybackSnapshot(engine, snapshot) }
                    }
                    request.generation
                } catch (failure: Throwable) {
                    synchronized(engineLock) {
                        if (activeEngineRequest === request) activeEngineRequest = null
                    }
                    refreshSpectrumRoute()
                    throw failure
                }
            }
        }
    }

    /** Only native media owns a queue rail, and engine-local generations stay private. */
    fun updateNativeUpcoming(serviceGeneration: Long, entries: List<NativeQueueEntry>): Boolean {
        val localGeneration = synchronized(engineLock) {
            val request = activeEngineRequest ?: return false
            if (request.engine != PlaybackEngine.Native || request.generation != serviceGeneration || request.localGeneration == 0L) return false
            request.localGeneration
        }
        return bridge.updateUpcoming(localGeneration, entries)
    }

    fun audioSpectrum(): AudioSpectrum = when {
        !spectrumActive -> AudioSpectrum()
        currentEngine() == PlaybackEngine.StationWeb -> stationPlayer.audioSpectrum()
        currentEngine() == PlaybackEngine.Native -> songSpectrum.sample()
        else -> AudioSpectrum()
    }
    fun spectrumCaptureState(): SpectrumCaptureState = when {
        !spectrumActive -> SpectrumCaptureState.Disabled
        currentEngine() == PlaybackEngine.StationWeb -> stationPlayer.spectrumCaptureState()
        currentEngine() == PlaybackEngine.Native -> songSpectrum.captureState()
        else -> SpectrumCaptureState.WaitingForSession
    }
    fun setSpectrumActive(enabled: Boolean) {
        spectrumActive = enabled
        refreshSpectrumRoute()
    }
    private fun refreshSpectrumRoute() {
        // Resolve the latest owner on the main queue, rather than posting an
        // obsolete route from a concurrently finishing playback preparation.
        playbackMain.post {
            val engine = currentEngine()
            songSpectrum.setActive(spectrumActive && engine == PlaybackEngine.Native)
            stationPlayer.setSpectrumActive(spectrumActive && engine == PlaybackEngine.StationWeb)
        }
    }

    private fun currentEngine(): PlaybackEngine? = synchronized(engineLock) { activeEngineRequest?.engine }

    fun play() {
        mediaSession.playbackRequested()
        if (currentEngine() == PlaybackEngine.StationWeb) stationPlayer.play() else bridge.play()
    }
    /** Keep the existing foreground lease while preparing an explicit replacement. */
    fun pauseForReplacement() {
        if (currentEngine() == PlaybackEngine.StationWeb) stationPlayer.pause() else bridge.pause()
    }
    fun pause() {
        mediaSession.playbackPaused()
        if (currentEngine() == PlaybackEngine.StationWeb) stationPlayer.pause() else bridge.pause()
    }
    fun seekTo(positionMs: Long) {
        if (currentEngine() == PlaybackEngine.StationWeb) stationPlayer.seekTo(positionMs) else bridge.seekTo(positionMs)
    }
    fun skipToNext() {
        if (currentEngine() == PlaybackEngine.StationWeb) stationPlayer.skipToNext() else bridge.skipToNext()
    }
    fun skipToPrevious() {
        if (currentEngine() == PlaybackEngine.StationWeb) stationPlayer.skipToPrevious() else bridge.skipToPrevious()
    }
    // Song repeat/shuffle belongs to the app queue; a station owns its actual
    // internal queue. Engine-local generations must never escape this boundary.
    fun playbackSnapshot(): PlaybackSnapshot? = serviceSnapshot

    /** The system panel may control only the currently prepared native song's renderer. */
    internal fun audioEffectsIntent(item: Track): Intent? {
        val sessionId = synchronized(engineLock) {
            val request = activeEngineRequest ?: return null
            val snapshot = serviceSnapshot ?: return null
            val current = snapshot.currentTrack ?: return null
            if (request.engine != PlaybackEngine.Native || request.localGeneration == 0L ||
                snapshot.requestGeneration != request.generation || !snapshot.isPrepared ||
                !snapshot.hasPlayerCurrentTrack || snapshot.isEnded || snapshot.errorMessage != null ||
                item.catalogKind != CatalogKind.Song || current.catalogKind != CatalogKind.Song ||
                !item.matchesPlaybackQueueEntry(current)) return null
            bridge.audioSessionId().takeIf { it > 0 } ?: return null
        }
        val panel = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        return try {
            panel.takeIf { it.resolveActivity(appContext.packageManager) != null }
        } catch (_: SecurityException) {
            null
        }
    }

    fun setRuntimePlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        runtimePlaybackListener = listener
        updateEngineListeners()
    }
    private fun updateEngineListeners() {
        val subscribed = runtimePlaybackListener != null
        bridge.setPlaybackListener(if (!subscribed) null else { snapshot ->
            forwardPlaybackSnapshot(PlaybackEngine.Native, snapshot)
        })
        stationPlayer.setPlaybackListener(if (!subscribed) null else { snapshot ->
            forwardPlaybackSnapshot(PlaybackEngine.StationWeb, snapshot)
        })
    }
    private fun forwardPlaybackSnapshot(engine: PlaybackEngine, snapshot: PlaybackSnapshot) {
        val mapped = synchronized(engineLock) {
            val request = activeEngineRequest ?: return
            if (request.engine != engine) return
            if (request.localGeneration == 0L) { request.pending = snapshot; return }
            if (snapshot.requestGeneration != request.localGeneration) return
            snapshot.copy(requestGeneration = request.generation).also { serviceSnapshot = it }
        }
        runtimePlaybackListener?.invoke(mapped)
    }
    private fun resetPlaybackEngines() {
        synchronized(engineLock) {
            playbackEpoch.incrementAndGet()
            activeEngineRequest = null
            serviceSnapshot = null
        }
        refreshSpectrumRoute()
        stationPlayer.release()
        bridge.release()
    }
    fun setRuntimeSystemPlaybackCallbacks(callbacks: PlaybackMediaSession.Callbacks?) {
        mediaSession.setCallbacks(callbacks)
    }
    fun publishSystemPlayback(snapshot: PlaybackSnapshot, canSkipNext: Boolean, canSkipPrevious: Boolean) =
        mediaSession.publish(snapshot, canSkipNext, canSkipPrevious)
    fun release() {
        runtimePlaybackListener = null
        attachPlaybackHost(null)
        songSpectrum.release()
        mediaSession.release()
        resetPlaybackEngines()
    }
}
