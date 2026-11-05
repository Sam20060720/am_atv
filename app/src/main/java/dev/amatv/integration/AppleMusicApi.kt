package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.amatv.BuildConfig
import dev.amatv.model.ArtistProfile
import dev.amatv.model.ArtistSection
import dev.amatv.model.CatalogSearchType
import dev.amatv.model.CatalogSearchSuggestions
import dev.amatv.model.CatalogSearchTerm
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class DeveloperTokenClient(
    private val endpoint: String,
    private val tokenStore: SecureTokenStore? = null,
    private val bundledToken: String = "",
) {
    @Volatile
    private var cachedToken: String? = null

    suspend fun token(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        // A distributable APK carries a signed, expiring developer token, never its signing key
        // or a user's token. Prefer it over a short-lived cache from an earlier LAN build.
        if (DeveloperTokenPolicy.isUsable(bundledToken)) return@withContext bundledToken
        if (!forceRefresh) {
            cachedToken?.takeIf(::isJwtValid)?.let { return@withContext it }
            tokenStore?.readDeveloperToken()?.takeIf(::isJwtValid)?.let { stored ->
                currentCoroutineContext().ensureActive()
                cachedToken = stored
                return@withContext stored
            }
        } else {
            currentCoroutineContext().ensureActive()
            cachedToken = null
            tokenStore?.clearDeveloperToken()
        }

        check(endpoint.isNotBlank()) {
            if (bundledToken.isNotBlank()) AppText.text(R.string.services_credentials_expired_update)
            else AppText.text(R.string.services_full_version_required)
        }
        val secureEndpoint = endpoint.startsWith("https://")
        val debugLanEndpoint = BuildConfig.DEBUG && endpoint.startsWith("http://")
        check(secureEndpoint || debugLanEndpoint) {
            AppText.text(R.string.services_token_https_required)
        }
        val value = runCatching { withCatalogRequestDeadline {
                val reply = CatalogHttpTransport(playbackCatalogHttpClient).execute(Request.Builder().url(endpoint)
                    .header("Accept", "application/json, text/plain").get().build())
                val status = reply.status
                val body = reply.body.trim()
                check(status in 200..299) { AppText.text(R.string.services_token_http_error, status) }
                val parsed = if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    json.optString("developerToken").ifBlank { json.optString("token") }
                } else {
                    body
                }
                check(isJwtValid(parsed)) { AppText.text(R.string.services_token_invalid) }
                parsed
        } }.getOrElse { error ->
            if (error is CancellationException) throw error
            throw IllegalStateException(
                AppText.text(R.string.services_token_connection_failed, endpoint, error.message ?: error.javaClass.simpleName),
                error,
            )
        }

        currentCoroutineContext().ensureActive()
        cachedToken = value
        tokenStore?.writeDeveloperToken(value)
        value
    }

    private fun isJwtValid(token: String): Boolean = DeveloperTokenPolicy.isUsable(token)
}

class AppleMusicApi(
    private val storefront: String,
    private val developerTokens: DeveloperTokenClient,
    private val httpCalls: Call.Factory? = null,
    private val userToken: () -> String?,
) {
    private data class UserStorefrontCache(val userToken: String, val storefront: String)
    internal var language: () -> String = { Locale.getDefault().toLanguageTag() }
    private val storefrontLocalizations = java.util.concurrent.ConcurrentHashMap<String, StorefrontLocalization>()
    private val localizationMutex = Mutex()

    internal suspend fun <T> withCurrentAuthorization(operation: suspend () -> T): T {
        val owner = AppleAuthorizationScope(scopedAppleToken(userToken))
        return withContext(owner) { operation().also { owner.checkCurrent(userToken) } }
    }

    internal suspend fun resolutionCacheScope(): String =
        "${appleAuthorizationCacheScope(scopedAppleToken(userToken))}:${effectiveStorefront()}:${language()}"

    internal suspend fun catalogLanguage(region: String): String? {
        val metadata = storefrontLocalizations[region] ?: localizationMutex.withLock {
            storefrontLocalizations[region] ?: run {
                val root = try { requestRoot("/v1/storefronts/$region", false, localize = false) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@withLock null }
                parseStorefrontLocalization(root)?.also { storefrontLocalizations[region] = it }
            }
        } ?: return null
        return selectCatalogLanguage(language(), metadata.supported, metadata.default)
    }

    private val parseDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val userStorefrontMutex = Mutex()
    private val catalogSongResolver = CatalogSongResolver(
        accountToken = userToken,
        storefront = ::userStorefront,
        libraryCatalogId = ::libraryCatalogSongId,
        equivalentCatalogId = ::equivalentSongId,
        authorizationMessage = { AppText.text(R.string.services_sign_in_apple_music) },
    )

    val actions = CatalogActions(::effectiveStorefront) { path, method, body ->
        requestRoot(path, needsUserToken = true, method = method, postBody = body)
    }

    val catalogPages = CatalogPagesApi(::effectiveStorefront, { !userToken().isNullOrBlank() },
        { path, user -> requestRoot(path, user) }, ::parseResourceItem, parseDispatcher)

    val accountInsights = AccountInsightsApi(::effectiveStorefront,
        { path, user -> editorialPage(path, user, setOf("songs", "albums", "artists", "playlists")) }, ::parseResourceItem, parseDispatcher)

    val radioCatalog = RadioCatalogApi(::effectiveStorefront, { path, user -> requestRoot(path, user) },
        ::parseResourceItem, parseDispatcher)

    val catalogCharts = CatalogChartsApi(::effectiveStorefront,
        { path, user -> requestRoot(path, user) }, ::parseResourceItem, parseDispatcher)

    private suspend fun editorialPage(path: String, user: Boolean, types: Set<String>): JSONObject =
        requestEditorialVideo(path, types) { requestRoot(it, user) }

    @Volatile
    private var userStorefrontCache: UserStorefrontCache? = null

    /** Never request the account storefront merely to restore an idle cover. */
    internal fun cachedEffectiveStorefront(): String? {
        val currentUserToken = userToken()?.takeIf(String::isNotBlank) ?: return storefront
        return userStorefrontCache?.takeIf { it.userToken == currentUserToken }?.storefront
    }

    suspend fun userStorefront(): String {
        val currentUserToken = scopedAppleToken(userToken)?.takeIf(String::isNotBlank)
            ?: throw CatalogRequestException(CatalogFailureReason.Authorization,
                AppText.text(R.string.services_sign_in_apple_music))
        userStorefrontCache?.takeIf { it.userToken == currentUserToken }?.let { return it.storefront }
        return userStorefrontMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (userToken() != currentUserToken) throw CancellationException("Apple account changed during storefront resolution")
            userStorefrontCache?.takeIf { it.userToken == currentUserToken }?.let { return@withLock it.storefront }
            val root = requestRoot(path = "/v1/me/storefront", needsUserToken = true, playback = true,
                requestUserToken = currentUserToken)
            val resolved = parseUserStorefrontId(root)
                ?: error(AppText.text(R.string.services_user_storefront_missing))
            currentCoroutineContext().ensureActive()
            if (userToken() != currentUserToken) throw CancellationException("Apple account changed during storefront resolution")
            parseStorefrontLocalization(root)?.let { storefrontLocalizations[resolved] = it }
            userStorefrontCache = UserStorefrontCache(currentUserToken, resolved)
            runCatching { Log.i(TAG, "Apple user storefront=$resolved") }
            resolved
        }
    }

    suspend fun effectiveStorefront(): String {
        val currentUserToken = scopedAppleToken(userToken)?.takeIf(String::isNotBlank)
        if (currentUserToken == null) return storefront
        return userStorefront()
    }

    suspend fun recommendationShelves(): List<ContentShelf> {
        val root = requestRoot(
            path = "/v1/me/recommendations?limit=10&extend=editorialArtwork,editorialVideo",
            needsUserToken = true,
        )
        val shelves = withContext(parseDispatcher) { parseRecommendationShelves(root) }
        val motionCount = shelves.sumOf { shelf -> shelf.items.count { it.motionArtworkUrl != null } }
        Log.i(TAG, "Home motion artwork: $motionCount/${shelves.sumOf { it.items.size }}")
        return shelves
    }

    /** Keep expansion evidence from the same response that supplies Home's cards. */
    internal fun parseRecommendationShelves(root: JSONObject): List<ContentShelf> {
        val recommendations = root.optJSONArray("data") ?: JSONArray()
        val shelves = buildList {
            for (index in 0 until recommendations.length()) {
                val recommendation = recommendations.optJSONObject(index) ?: continue
                val recommendationId = recommendation.optString("id").takeIf { it.isNotBlank() && it != "null" }
                val attributes = recommendation.optJSONObject("attributes")
                val title = attributes?.displayText("title")
                    ?: attributes?.displayText("name")
                    ?: AppText.text(R.string.services_recommended_for_you)
                val contents = recommendation.optJSONObject("relationships")?.optJSONObject("contents") ?: continue
                val tracks = parseResources(contents)
                if (tracks.isNotEmpty()) add(ContentShelf(
                    id = "recommendation:${recommendationId ?: index}",
                    title = title, items = tracks, featured = isEmpty(),
                    hasMore = recommendationId != null && hasMoreHomeItems(contents),
                ))
            }
        }
        if (shelves.isNotEmpty()) return shelves
        val fallback = parseResources(root)
        return if (fallback.isEmpty()) emptyList() else listOf(ContentShelf(
            "recommendations", AppText.text(R.string.services_top_picks_for_you), fallback,
            featured = true, hasMore = hasMoreHomeItems(root),
        ))
    }

    private fun hasMoreHomeItems(root: JSONObject): Boolean {
        val data = root.optJSONArray("data")
        val rawCount = data?.length() ?: 0
        val next = (root.opt("next") as? String)?.takeIf { it.isNotBlank() && it != "null" }
        val total = (root.optJSONObject("meta")?.opt("total") as? Number)?.toLong()
        // Child recommendations have no direct Home card. Their own contents
        // and hierarchy are available only after opening the parent group.
        val omittedGroup = data != null && (0 until data.length()).any {
            data.optJSONObject(it)?.optString("type") == "personal-recommendation"
        }
        return next != null || data != null && total != null && total > rawCount || omittedGroup
    }

    suspend fun recentlyPlayedPage(): LibraryPage {
        // Keep the same mixed-resource request and optional artwork fallback.
        val path = "/v1/me/recent/played?types=albums,playlists,stations&limit=10"
        val root = editorialPage(path, true, setOf("albums", "playlists"))
        return withContext(parseDispatcher) { homeHistoryPage(root, path) }.also {
            Log.i(TAG, "Recent containers loaded=${it.items.size}")
        }
    }

    suspend fun recentlyPlayedTracksPage(): LibraryPage {
        val path = "/v1/me/recent/played/tracks?types=songs,library-songs,music-videos,library-music-videos&limit=20&extend=editorialArtwork,editorialVideo"
        val root = requestRoot(path, needsUserToken = true)
        return withContext(parseDispatcher) { homeHistoryPage(root, path) }
    }

    private fun homeHistoryPage(root: JSONObject, path: String): LibraryPage = LibraryPage(
        items = parseResources(root),
        nextPage = (root.opt("next") as? String)?.takeIf { it.isNotBlank() && it != "null" },
        requestedPage = path,
        total = (root.optJSONObject("meta")?.opt("total") as? Number)?.toLong()
            ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
        rawItemCount = root.optJSONArray("data")?.length() ?: 0,
    )

    suspend fun recentlyAddedPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.RecentlyAdded, nextPage) { editorialPage(it, true, LibraryCollection.RecentlyAdded.resourceTypes) }

    suspend fun libraryAlbumsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Albums, nextPage) { editorialPage(it, true, LibraryCollection.Albums.resourceTypes) }

    suspend fun libraryArtistsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Artists, nextPage) { editorialPage(it, true, LibraryCollection.Artists.resourceTypes) }

    internal suspend fun libraryArtistAlbumsPage(
        artist: Track,
        nextPage: String? = null,
        fetchPage: suspend (String) -> JSONObject,
    ): LibraryPage = resourcePage(
        artist.libraryArtistAlbumsRequest(), LibraryCollection.Albums.resourceTypes, nextPage,
        { path -> requestRelationshipData { fetchPage(path) } },
    )

    suspend fun artistProfile(artist: Track): ArtistProfile =
        artistProfile(artist, effectiveStorefront()) { path, authorized -> requestRoot(path, authorized) }

    internal suspend fun artistProfile(
        artist: Track,
        storefront: String,
        fetch: suspend (String, Boolean) -> JSONObject,
    ): ArtistProfile = withContext(parseDispatcher) {
        require(artist.catalogKind == CatalogKind.Artist)
        require(storefront.matches(Regex("[a-z]{2}")))
        val sourceId = resourceId(artist)
        val library = artist.resourceType == "library-artists"
        require(library || artist.resourceType == "artists")
        val source = if (library) {
            val root = fetch("/v1/me/library/artists/$sourceId?include=catalog", true)
            resource(root, sourceId, "library-artists")
        } else null
        val libraryAttributes = source?.optJSONObject("attributes")
        val linked = source?.optJSONObject("relationships")?.optJSONObject("catalog")
            ?.optJSONArray("data")?.optJSONObject(0)?.takeIf { it.optString("type") == "artists" }
        val catalogId = if (library) linked?.optString("id")?.takeIf(::validResourceId)
            ?: artist.catalogId?.takeIf { it != sourceId && validResourceId(it) }
        else sourceId
        val catalogResource = catalogId?.let { id ->
            val base = "/v1/catalog/$storefront/artists/$id"
            // Verified against the shipped public Apple Music web artist/bio requests.
            // Optional web extensions enrich one opened profile, never every card.
            val extended = "$base?extend=artistBio,bornOrFormed,origin,isGroup,editorialArtwork,editorialVideo,extendedAssetUrls,plainEditorialNotes"
            try {
                val root = try { fetch(extended, false) }
                catch (error: CatalogRequestException) {
                    if (error.httpStatus !in setOf(400, 403)) throw error
                    // API credentials/regions need not support every web extension.
                    fetch(base, false)
                }
                resource(root, id, "artists")
            } catch (error: CatalogRequestException) {
                // The saved artist still owns Library Albums if its catalog
                // counterpart is unavailable in this account's storefront.
                if (!library || error.reason != CatalogFailureReason.Unavailable) throw error
                null
            }
        }
        val catalogAttributes = catalogResource?.optJSONObject("attributes")
        val catalogArtist = catalogAttributes?.toTrack(checkNotNull(catalogId), CatalogKind.Artist, resourceType = "artists")
        val sourceArtist = if (library) {
            libraryAttributes?.toTrack(sourceId, CatalogKind.Artist, linked, "library-artists", allowLibraryPlaceholder = true)
                ?.let { parsed -> parsed.copy(
                    artworkUrl = parsed.artworkUrl ?: catalogArtist?.artworkUrl,
                    description = parsed.description ?: catalogArtist?.description,
                    catalogId = catalogArtist?.catalogId ?: parsed.catalogId,
                ) } ?: artist
        } else checkNotNull(catalogArtist) { AppText.text(R.string.services_track_unavailable) }
        ArtistProfile(
            artist = sourceArtist,
            catalogArtist = catalogArtist,
            biography = catalogAttributes?.stringAttribute("artistBio")
                ?: catalogAttributes?.collectionDescription() ?: libraryAttributes?.collectionDescription(),
            genreNames = catalogAttributes?.optJSONArray("genreNames")?.stringValues().orEmpty(),
            url = catalogAttributes?.optString("url")?.takeIf { it.startsWith("https://music.apple.com/") },
            inFavorites = when {
                libraryAttributes?.has("inFavorites") == true -> libraryAttributes.optBoolean("inFavorites")
                catalogAttributes?.has("inFavorites") == true -> catalogAttributes.optBoolean("inFavorites")
                else -> null
            },
            origin = catalogAttributes?.stringAttribute("origin"),
            bornOrFormed = catalogAttributes?.stringAttribute("bornOrFormed"),
            isGroup = catalogAttributes?.takeIf { it.has("isGroup") }?.optBoolean("isGroup"),
        )
    }

    suspend fun artistSectionPage(artist: Track, section: ArtistSection, nextPage: String? = null): LibraryPage =
        artistSectionPage(artist, section, effectiveStorefront(), nextPage) { path, authorized -> editorialPage(path, authorized, section.resourceTypes) }

    internal suspend fun artistSectionPage(
        artist: Track,
        section: ArtistSection,
        storefront: String,
        nextPage: String? = null,
        fetch: suspend (String, Boolean) -> JSONObject,
    ): LibraryPage {
        if (section == ArtistSection.LibraryAlbums) {
            return libraryArtistAlbumsPage(artist, nextPage) { fetch(it, true) }
        }
        require(artist.catalogKind == CatalogKind.Artist && artist.resourceType == "artists") {
            AppText.text(R.string.services_collection_required)
        }
        require(storefront.matches(Regex("[a-z]{2}")))
        val id = resourceId(artist)
        val component = if (section.isView) "view/${section.apiName}" else section.apiName
        val request = CollectionRequest("/v1/catalog/$storefront/artists/$id/$component", false,
            pageSize = section.pageSize, includeCatalog = false)
        return resourcePage(request, section.resourceTypes, nextPage, { path -> requestRelationshipData { fetch(path, false) } },
            firstPage = request.firstPage + if (section.isView) "&with=attributes" else "")
    }

    /** Read the actual artist relationship, never search an artistName and guess an identity. */
    suspend fun relatedArtistsPage(item: Track, nextPage: String? = null): LibraryPage =
        relatedArtistsPage(item, effectiveStorefront(), nextPage) { path, authorized -> editorialPage(path, authorized, setOf("artists")) }

    internal suspend fun relatedArtistsPage(
        item: Track,
        storefront: String,
        nextPage: String? = null,
        fetch: suspend (String, Boolean) -> JSONObject,
    ): LibraryPage {
        val type = when (item.catalogKind) {
            CatalogKind.Album -> "albums"
            CatalogKind.Song -> "songs"
            CatalogKind.MusicVideo -> "music-videos"
            else -> error(AppText.text(R.string.services_collection_required))
        }
        require(storefront.matches(Regex("[a-z]{2}")))
        val id = resourceId(item)
        val library = item.resourceType?.startsWith("library-")
            ?: (id.startsWith("i.") || id.startsWith("l."))
        // The documented artist relationships for saved albums/songs return saved artists.
        // Catalog and library paths retain their own resource IDs and token requirements.
        val base = if (library) "/v1/me/library" else "/v1/catalog/$storefront"
        val request = CollectionRequest("$base/$type/$id/artists", library,
            pageSize = 10, includeCatalog = library)
        return resourcePage(request, setOf(if (library) "library-artists" else "artists"), nextPage,
            { path -> requestRelationshipData { fetch(path, library) } })
    }

    internal suspend fun searchHints(term: String, storefront: String, fetch: suspend (String) -> JSONObject): List<String> =
        withContext(parseDispatcher) {
            if (term.isBlank()) return@withContext emptyList()
            require(storefront.matches(Regex("[a-z]{2}")))
            val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
            fetch("/v1/catalog/$storefront/search/hints?term=$encoded&limit=5")
                .optJSONObject("results")?.optJSONArray("terms")?.stringValues().orEmpty().distinct()
        }

    suspend fun searchSuggestions(term: String): CatalogSearchSuggestions =
        searchSuggestions(term, effectiveStorefront()) { path ->
            if (path.substringBefore('?').endsWith("/hints")) requestRoot(path, false)
            else editorialPage(path, false, CatalogSearchType.entries.mapTo(mutableSetOf()) { type -> type.resourceType })
        }

    internal suspend fun searchSuggestions(
        term: String, storefront: String, fetch: suspend (String) -> JSONObject,
    ): CatalogSearchSuggestions = withContext(parseDispatcher) {
        if (term.isBlank()) return@withContext CatalogSearchSuggestions()
        require(storefront.matches(Regex("[a-z]{2}")))
        fun hinted(values: List<String>) = CatalogSearchSuggestions(values.map { CatalogSearchTerm(it, it) })
        if (term.trim().length < 2) return@withContext hinted(searchHints(term, storefront, fetch))
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        val types = CatalogSearchType.entries.joinToString(",") { it.resourceType }
        val root = try { fetch("/v1/catalog/$storefront/search/suggestions?term=$encoded&kinds=terms,topResults&types=$types&limit=5") }
        catch (error: CatalogRequestException) {
            if (error.httpStatus !in setOf(400, 404, 501)) throw error
            return@withContext hinted(searchHints(term, storefront, fetch))
        }
        val data = root.optJSONObject("results")?.optJSONArray("suggestions") ?: JSONArray()
        val terms = ArrayList<CatalogSearchTerm>()
        val topResults = ArrayList<Track>()
        for (index in 0 until data.length()) {
            val suggestion = data.optJSONObject(index) ?: continue
            when (suggestion.optString("kind")) {
                "terms" -> {
                    val searchTerm = suggestion.stringAttribute("searchTerm") ?: continue
                    val displayTerm = suggestion.stringAttribute("displayTerm") ?: continue
                    terms += CatalogSearchTerm(displayTerm, searchTerm)
                }
                "topResults" -> suggestion.optJSONObject("content")?.let(::parseResourceItem)?.let(topResults::add)
            }
        }
        CatalogSearchSuggestions(terms.distinctBy { it.searchTerm }, mergeLibraryPage(emptyList(), topResults))
    }

    suspend fun searchPage(term: String, type: CatalogSearchType, nextPage: String? = null): LibraryPage =
        searchPage(term, type, effectiveStorefront(), nextPage, library = false) { path, authorized -> editorialPage(path, authorized, setOf(type.resourceType)) }

    suspend fun librarySearchPage(term: String, type: CatalogSearchType, nextPage: String? = null): LibraryPage =
        searchPage(term, type, effectiveStorefront(), nextPage, library = true) { path, authorized -> editorialPage(path, authorized, setOf(type.resourceType)) }

    internal suspend fun searchPage(
        term: String,
        type: CatalogSearchType,
        storefront: String,
        nextPage: String? = null,
        library: Boolean = false,
        fetch: suspend (String, Boolean) -> JSONObject,
    ): LibraryPage {
        require(term.isNotBlank())
        require(storefront.matches(Regex("[a-z]{2}")))
        val resourceType = if (library) checkNotNull(type.libraryResourceType) else type.resourceType
        val base = if (library) "/v1/me/library" else "/v1/catalog/$storefront"
        val request = CollectionRequest("$base/search", library, pageSize = 25, includeCatalog = false)
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        fun continuation(href: String): String {
            val path = request.nextPage(href)
            val parameters = URI(path).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { parameter ->
                val pair = parameter.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }.groupBy({ it.first }, { it.second })
            check(parameters["term"] == listOf(term.trim()) && parameters["types"] == listOf(resourceType)) {
                AppText.text(R.string.services_collection_next_page_failed)
            }
            return path
        }
        val result = resourcePage(request, setOf(resourceType), nextPage?.let(::continuation), { fetch(it, library) },
            firstPage = "${request.endpoint}?term=$encoded&types=$resourceType&limit=25",
            select = { root -> root.optJSONObject("results")?.optJSONObject(resourceType)
                ?: JSONObject().put("data", JSONArray()) })
        val page = result.copy(nextPage = result.nextPage?.let(::continuation))
        if (!library || type != CatalogSearchType.Artists || page.items.none { it.artworkUrl.isNullOrBlank() }) return page
        // Library search doesn't document include=catalog. One supported batch
        // lookup enriches this page; never issue a request for each artist card.
        return withContext(parseDispatcher) {
            val ids = page.items.mapNotNull { it.resourceId }.filter(::validResourceId).distinct()
            if (ids.isEmpty()) return@withContext page
            try {
                currentCoroutineContext().ensureActive()
                val root = fetch("/v1/me/library/artists?ids=${URLEncoder.encode(ids.joinToString(","), "UTF-8")}&include=catalog", true)
                val data = root.optJSONArray("data") ?: return@withContext page
                val enriched = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                    .filter { it.optString("type") == "library-artists" }.mapNotNull(::parseResourceItem)
                    .associateBy { it.resourceId }
                page.copy(items = page.items.map { item -> enriched[item.resourceId]?.let { extra -> item.copy(
                    artworkUrl = item.artworkUrl ?: extra.artworkUrl, catalogId = item.catalogId ?: extra.catalogId,
                    motionArtworkUrl = item.motionArtworkUrl ?: extra.motionArtworkUrl,
                    motionPreviewUrl = item.motionPreviewUrl ?: extra.motionPreviewUrl,
                    motionArtworkVariants = item.motionArtworkVariants.ifEmpty { extra.motionArtworkVariants },
                    description = item.description ?: extra.description, url = item.url ?: extra.url,
                ) } ?: item })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { page } // A thumbnail lookup cannot erase successful search results.
        }
    }

    private fun resourceId(item: Track): String = (item.resourceId ?: item.catalogId ?: item.id.removePrefix("apple:"))
        .also { require(validResourceId(it)) { AppText.text(R.string.services_collection_unrecognized) } }

    private fun validResourceId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9._-]+"))

    private fun resource(root: JSONObject, id: String, type: String): JSONObject {
        val data = root.optJSONArray("data")
        return (0 until (data?.length() ?: 0)).asSequence().mapNotNull { data?.optJSONObject(it) }
            .firstOrNull { it.optString("id") == id && it.optString("type") == type }
            ?: error(AppText.text(R.string.services_track_unavailable))
    }

    private fun JSONArray.stringValues(): List<String> = (0 until length()).mapNotNull {
        optString(it).takeIf { value -> value.isNotBlank() && value != "null" }
    }

    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Playlists, nextPage) { editorialPage(it, true, LibraryCollection.Playlists.resourceTypes) }

    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Songs, nextPage) { editorialPage(it, true, LibraryCollection.Songs.resourceTypes) }

    internal suspend fun libraryPage(
        collection: LibraryCollection,
        nextPage: String? = null,
        fetchPage: suspend (String) -> JSONObject,
    ): LibraryPage = resourcePage(collection.request, collection.resourceTypes, nextPage, fetchPage)

    private suspend fun resourcePage(
        request: CollectionRequest,
        resourceTypes: Set<String>,
        nextPage: String?,
        fetchPage: suspend (String) -> JSONObject,
        firstPage: String = request.firstPage,
        select: (JSONObject) -> JSONObject = { it },
    ): LibraryPage = withContext(parseDispatcher) {
        val path = nextPage?.let(request::nextPage) ?: firstPage
        currentCoroutineContext().ensureActive()
        val root = select(fetchPage(path))
        val data = checkNotNull(root.optJSONArray("data")) {
            AppText.text(R.string.services_playlist_tracks_unavailable)
        }
        val tracks = buildList {
            for (index in 0 until data.length()) {
                currentCoroutineContext().ensureActive()
                val item = data.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (type !in resourceTypes) continue
                val attributes = item.optJSONObject("attributes") ?: continue
                val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
                    ?.optJSONArray("data")?.optJSONObject(0)
                attributes.toTrack(item.getString("id"), checkNotNull(catalogKind(type)), linked, type,
                    allowLibraryPlaceholder = type.startsWith("library-"))?.let(::add)
            }
        }
        val next = root.optString("next").takeIf { it.isNotBlank() && it != "null" }?.let(request::nextPage)
        check(next != path) { AppText.text(R.string.services_playlist_page_repeated) }
        val total = root.optJSONObject("meta")?.optInt("total", -1)?.takeIf { it >= 0 }
        LibraryPage(mergeLibraryPage(emptyList(), tracks), next, path, total)
    }

    suspend fun resolveCatalogSongId(id: String): String = catalogSongResolver.resolve(id)

    private suspend fun libraryCatalogSongId(id: String): String {
        val root = requestRoot(
            path = "/v1/me/library/songs/${URLEncoder.encode(id, Charsets.UTF_8.name())}?include=catalog",
            needsUserToken = true,
            playback = true,
        )
        val item = root.optJSONArray("data")?.optJSONObject(0)
            ?: throw CatalogRequestException(CatalogFailureReason.Unavailable,
                AppText.text(R.string.services_library_song_catalog_missing, id))
        val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
            ?.optJSONArray("data")?.optJSONObject(0)
        val resolved = catalogPlaybackId(id, "library-songs",
            item.optJSONObject("attributes")?.optJSONObject("playParams"), linked)
        if (resolved == id || resolved.startsWith("i.")) {
            throw CatalogRequestException(CatalogFailureReason.Unavailable,
                AppText.text(R.string.services_track_unavailable))
        }
        Log.i(TAG, "Library source catalog: $id -> $resolved")
        return resolved
    }

    private suspend fun equivalentSongId(storefront: String, id: String): String {
        // Catalog queues as well as library links can outlive a release. Apple
        // owns the recording equivalence; the account storefront owns availability.
        val equivalents = requestRoot(
            path = "/v1/catalog/$storefront/songs?filter%5Bequivalents%5D=${URLEncoder.encode(id, Charsets.UTF_8.name())}",
            needsUserToken = false,
            playback = true,
        )
        val playable = requireEquivalentCatalogSongId(
            equivalents, id,
            unavailableMessage = AppText.text(R.string.services_track_unavailable),
            transientMessage = AppText.text(R.string.services_song_connection_failed),
        )
        Log.i(TAG, "Catalog equivalence: $id -> $playable (storefront=$storefront)")
        return playable
    }

    /** Search only playable songs for cross-catalog resolution. */
    suspend fun searchSongs(term: String): List<Track> {
        if (term.isBlank()) return emptyList()
        val sf = effectiveStorefront()
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        return request(
            path = "/v1/catalog/$sf/search?term=$encoded&types=songs&limit=25",
            needsUserToken = false,
        ).filter { it.catalogKind == CatalogKind.Song && it.catalogId != null }
    }

    suspend fun createContinuousStation(songId: String): Pair<String?, List<Track>> {
        val cleanSongId = songId.removePrefix("apple:")
        val type = if (cleanSongId.startsWith("i.")) "library-songs" else "songs"
        val bodyJson = JSONObject().put("data", JSONArray().put(JSONObject().put("id", cleanSongId).put("type", type)))
        val root = requestRoot(
            path = "/v1/me/stations/continuous?limit[results:tracks]=10&with=tracks",
            needsUserToken = true,
            method = "POST",
            postBody = bodyJson.toString(),
            playback = true,
        )
        return continuousStationFromResponse(root, ::stationNextTracks)
    }

    suspend fun stationNextTracks(stationId: String): List<Track> {
        val root = requestRoot(
            path = "/v1/me/stations/next-tracks/$stationId?limit=10",
            needsUserToken = true,
            method = "POST",
            postBody = "{}",
            playback = true,
        )
        return withContext(parseDispatcher) { parseStationTracks(root) }
    }

    internal suspend fun continuousStationFromResponse(
        root: JSONObject,
        loadNextTracks: suspend (String) -> List<Track>,
    ): Pair<String?, List<Track>> {
        val (stationId, tracks) = withContext(parseDispatcher) {
            val data = root.optJSONArray("data")
            val station = (0 until (data?.length() ?: 0)).firstNotNullOfOrNull { index ->
                data?.optJSONObject(index)?.takeIf { it.optString("type") == "stations" }
            } ?: root.optJSONObject("results")?.optJSONObject("station")
            val id = station?.optString("id")?.takeIf(String::isNotBlank)
            id to parseStationTracks(root)
        }
        // Station creation can return only its resource metadata. That resource
        // is a continuation handle, never an item for the native song queue.
        val songs = if (tracks.isEmpty() && stationId != null) loadNextTracks(stationId) else tracks
        return stationId to songs.filter { it.catalogKind == CatalogKind.Song }
    }

    internal fun parseStationTracks(root: JSONObject): List<Track> {
        val output = LinkedHashMap<String, Track>()

        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    val id = value.optString("id")
                    val type = value.optString("type")
                    val attributes = value.optJSONObject("attributes")
                    val kind = catalogKind(type)
                    if (id.isNotBlank() && attributes != null && kind == CatalogKind.Song) {
                        val linkedCatalog = value.optJSONObject("relationships")
                            ?.optJSONObject("catalog")
                            ?.optJSONArray("data")
                            ?.optJSONObject(0)
                        val track = attributes.toTrack(id, kind, linkedCatalog, type)
                        if (track != null && !output.containsKey(track.id)) {
                            output[track.id] = track
                        }
                        return
                    }
                    value.keys().forEachRemaining { key ->
                        if (key != "attributes") visit(value.opt(key))
                    }
                }
            }
        }

        visit(root)
        return output.values.toList()
    }

    suspend fun collectionTracks(collection: Track): List<Track> =
        collectionTracks(collection) { path, needsUserToken -> requestRoot(path, needsUserToken) }

    /** A relationship page preserves occurrences: repeated playlist songs are intentional. */
    suspend fun collectionTracksPage(collection: Track, nextPage: String? = null, reversePlaylist: Boolean = false): LibraryPage =
        withCurrentAuthorization {
            collectionTracksPage(collection, nextPage, reversePlaylist) { path, needsUserToken -> requestRoot(path, needsUserToken) }
        }

    internal suspend fun sampleCollectionShuffleSong(collection: Track, total: Int): CollectionShuffleSample? =
        sampleCollectionShuffleSong(collection, total, { path, user -> requestRoot(path, user) })

    internal suspend fun sampleCollectionShuffleSong(
        collection: Track,
        total: Int,
        fetchPage: suspend (String, Boolean) -> JSONObject,
        randomIndex: (Int) -> Int = { kotlin.random.Random.nextInt(it) },
    ): CollectionShuffleSample? {
        if (total <= 0 || collection.catalogKind != CatalogKind.Playlist) return null
        val request = collection.collectionRequest(effectiveStorefront())
        // Rejection sampling over RAW occurrences is uniform among parsed songs,
        // including repeats. Exhaustion falls back to the complete population.
        repeat(6) {
            val index = randomIndex(total).also { require(it in 0 until total) }
            val page = collectionTracksPage(request, request.singleOccurrence(index), fetchPage)
            if (page.total != total || page.rawItemCount != 1) return CollectionShuffleSample(null, index, total, request.firstPage)
            val nextOffset = page.nextPage?.let { java.net.URI(it).rawQuery.orEmpty().split('&')
                .singleOrNull { part -> part.substringBefore('=') == "offset" }?.substringAfter('=')?.toIntOrNull() }
            if (if (index == total - 1) page.nextPage != null else nextOffset != index + 1)
                return CollectionShuffleSample(null, index, total, request.firstPage)
            page.items.singleOrNull()?.let { return CollectionShuffleSample(it, index, total, request.firstPage) }
        }
        return null
    }

    internal suspend fun collectionTracksPage(
        collection: Track,
        nextPage: String? = null,
        fetchPage: suspend (String, Boolean) -> JSONObject,
    ): LibraryPage = collectionTracksPage(collection, nextPage, false, fetchPage)

    internal suspend fun collectionTracksPage(
        collection: Track,
        nextPage: String?,
        reversePlaylist: Boolean,
        fetchPage: suspend (String, Boolean) -> JSONObject,
    ): LibraryPage {
        val request = collection.collectionRequest(effectiveStorefront())
        val reverseCursor = ReversePlaylistCursor.parse(nextPage)
        if (reverseCursor != null || nextPage == null && reversePlaylist && collection.catalogKind == CatalogKind.Playlist) {
            require(collection.catalogKind == CatalogKind.Playlist)
            return reversePlaylistPage(request, nextPage) { path -> collectionTracksPage(request, path, fetchPage) }
        }
        return collectionTracksPage(request, nextPage, fetchPage)
    }

    private suspend fun collectionTracksPage(
        request: CollectionRequest,
        nextPage: String?,
        fetchPage: suspend (String, Boolean) -> JSONObject,
    ): LibraryPage = withContext(parseDispatcher) {
        val path = nextPage?.let { request.nextPage(it, retainPageSize = true) } ?: request.firstPage
        currentCoroutineContext().ensureActive()
        val root = fetchPage(path, request.needsUserToken)
        currentCoroutineContext().ensureActive()
        val data = root.optJSONArray("data") ?: error(AppText.text(R.string.services_playlist_tracks_unavailable))
        val rawOffsets = mutableListOf<Int>()
        val tracks = buildList {
            for (index in 0 until data.length()) {
                currentCoroutineContext().ensureActive()
                val item = data.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (catalogKind(type) != CatalogKind.Song) continue
                val attributes = item.optJSONObject("attributes") ?: continue
                val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
                    ?.optJSONArray("data")?.optJSONObject(0)
                attributes.toTrack(item.getString("id"), CatalogKind.Song, linked, type,
                    allowLibraryPlaceholder = type.startsWith("library-"))?.let { add(it); rawOffsets += index }
            }
        }
        val next = root.optString("next").takeIf { it.isNotBlank() && it != "null" }
            ?.let { request.nextPage(it, retainPageSize = true) }
        check(next != path) { AppText.text(R.string.services_playlist_page_repeated) }
        LibraryPage(tracks, next, path, root.optJSONObject("meta")?.optInt("total", -1)?.takeIf { it >= 0 },
            rawItemCount = data.length(), itemRawOffsets = rawOffsets)
    }

    internal suspend fun collectionTracks(
        collection: Track,
        fetchPage: suspend (String, Boolean) -> JSONObject,
    ): List<Track> {
        val request = collection.collectionRequest(effectiveStorefront())
        val visited = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        var next: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = collectionTracksPage(request, next, fetchPage)
            check(visited.add(page.requestedPage) && (page.nextPage == null || page.nextPage !in visited)) {
                AppText.text(R.string.services_playlist_page_repeated)
            }
            tracks += page.items
            next = page.nextPage
        } while (next != null)
        return tracks
    }

    private suspend fun request(path: String, needsUserToken: Boolean): List<Track> {
        val root = requestRoot(path, needsUserToken)
        return withContext(parseDispatcher) { parseResources(root) }
    }

    private suspend fun requestRoot(
        path: String,
        needsUserToken: Boolean,
        method: String = "GET",
        postBody: String? = null,
        playback: Boolean = false,
        requestUserToken: String? = null,
        localize: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) { withCatalogRequestDeadline {
        val capturedScope = currentCoroutineContext()[AppleAuthorizationScope]
        val musicUserToken = requestUserToken ?: scopedAppleToken(userToken)
        val account = AppleAuthorizationScope(musicUserToken)
        capturedScope?.checkCurrent(userToken)
        val region = if (method == "GET" && localize) {
            Regex("^/v1/catalog/([a-z]{2})/").find(path)?.groupValues?.get(1)
                ?: if (path.startsWith("/v1/me/") && !path.startsWith("/v1/me/storefront")) effectiveStorefront() else null
        } else null
        val localizedPath = region?.let { country -> catalogLanguage(country)?.let { withCatalogLanguage(path, it) } } ?: path
        val endpoint = URI("https://api.music.apple.com$localizedPath")
        require(path.startsWith("/v1/") && endpoint.scheme == "https" && endpoint.host == "api.music.apple.com" &&
            endpoint.port == -1 && endpoint.userInfo == null && endpoint.fragment == null)
        require(method in setOf("GET", "POST", "PUT", "DELETE"))
        if (needsUserToken) {
            if (musicUserToken.isNullOrBlank()) {
                throw CatalogRequestException(CatalogFailureReason.Authorization,
                    AppText.text(R.string.services_sign_in_apple_music))
            }
        }

        suspend fun execute(forceTokenRefresh: Boolean): CatalogHttpReply {
            val developerToken = developerTokens.token(forceRefresh = forceTokenRefresh)
            currentCoroutineContext().ensureActive()
            // Credentials are captured before any suspending dependency. A chain
            // started by A cannot dispatch a later write using B's account.
            account.checkCurrent(userToken)
            val requestBody = (postBody ?: if (method == "POST" || method == "PUT") "" else null)
                ?.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(endpoint.toString())
                .header("Authorization", "Bearer $developerToken").header("Accept", "application/json")
                .apply { if (!musicUserToken.isNullOrBlank()) header("Music-User-Token", musicUserToken) }
                .method(method, requestBody).build()
            return CatalogHttpTransport(httpCalls ?: if (playback) playbackCatalogHttpClient else catalogHttpClient).execute(request)
        }

        val first = execute(false)
        val (status, body) = if (method == "GET" && first.status == 401) execute(true) else first
        account.checkCurrent(userToken)
        if (status !in 200..299) {
            val message = when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> AppText.text(R.string.services_apple_auth_http_error, status)
                else -> AppText.text(R.string.services_apple_api_http_error, status)
            }
            throw catalogRequestFailure(status, body, message)
        }
        if (body.isBlank()) JSONObject() else JSONObject(body)
    } }

    internal fun parseResourceItem(item: JSONObject): Track? {
        val id = item.stringAttribute("id") ?: return null
        val type = item.stringAttribute("type") ?: return null
        val kind = catalogKind(type) ?: return null
        val attributes = item.optJSONObject("attributes") ?: return null
        val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")?.optJSONArray("data")?.optJSONObject(0)
        return attributes.toTrack(id, kind, linked, type, allowLibraryPlaceholder = type.startsWith("library-"))
    }

    private fun parseResources(root: JSONObject): List<Track> {
        val output = LinkedHashMap<String, Track>()

        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    val id = value.optString("id")
                    val type = value.optString("type")
                    val attributes = value.optJSONObject("attributes")
                    val kind = catalogKind(type)
                    if (id.isNotBlank() && attributes != null && kind != null) {
                        val linkedCatalog = value.optJSONObject("relationships")
                            ?.optJSONObject("catalog")
                            ?.optJSONArray("data")
                            ?.optJSONObject(0)
                        val track = attributes.toTrack(id, kind, linkedCatalog, type)
                        if (track != null) output.putIfAbsent(libraryResourceKey(track), track)
                        return
                    }
                    value.keys().forEachRemaining { key ->
                        if (key != "attributes") visit(value.opt(key))
                    }
                }
            }
        }

        visit(root)
        return output.values.toList()
    }

    private fun JSONObject.toTrack(
        id: String,
        kind: CatalogKind,
        linkedCatalog: JSONObject? = null,
        resourceType: String? = null,
        allowLibraryPlaceholder: Boolean = false,
    ): Track? {
        val catalogAttrs = linkedCatalog?.optJSONObject("attributes")
        val playParams = optJSONObject("playParams")
        val resolvedCatalogId = catalogPlaybackId(id, resourceType, playParams, linkedCatalog)

        val rawName = optString("name").ifBlank {
            catalogAttrs?.optString("name").orEmpty()
        }
        val rawArtist = optString("artistName").ifBlank {
            optString("curatorName").ifBlank {
                catalogAttrs?.optString("artistName").orEmpty().ifBlank {
                    catalogAttrs?.optString("curatorName").orEmpty()
                }
            }
        }
        val artwork = (optJSONObject("artwork")?.optString("url")
            ?: catalogAttrs?.optJSONObject("artwork")?.optString("url"))
            ?.asArtworkUrl()

        // Skip unplayable unmatched iCloud Music Library local stub containers
        // (e.g. `l.z-...` or `i.eoD...` with no catalog match and no metadata).
        val isUnmatchedLocalContainer = (id.startsWith("l.z-") || id.startsWith("i.")) &&
            resolvedCatalogId == id &&
            artwork == null &&
            (rawName.isBlank() || rawName == "Unknown Album" || rawArtist.isBlank())
        if (isUnmatchedLocalContainer && !allowLibraryPlaceholder) return null

        val editorialNotes = optJSONObject("editorialNotes")
            ?: catalogAttrs?.optJSONObject("editorialNotes")
        val name = rawName.ifBlank {
            editorialNotes?.optString("name").orEmpty().ifBlank {
                if (allowLibraryPlaceholder) when (kind) {
                    CatalogKind.Album -> AppText.text(R.string.services_kind_album)
                    CatalogKind.Playlist -> AppText.text(R.string.services_kind_playlist)
                    CatalogKind.MusicVideo -> AppText.text(R.string.services_kind_music_video)
                    CatalogKind.Song -> AppText.text(R.string.services_kind_song)
                    else -> AppText.text(R.string.services_unknown_title)
                } else AppText.text(R.string.services_unknown_title)
            }
        }
        val artist = rawArtist.ifBlank {
            when {
                kind == CatalogKind.Artist -> name
                allowLibraryPlaceholder -> ""
                else -> "Apple Music"
            }
        }
        val album = optString("albumName").ifBlank {
            catalogAttrs?.optString("albumName").orEmpty().ifBlank {
                when (kind) {
                    CatalogKind.Album -> AppText.text(R.string.services_kind_album)
                    CatalogKind.Playlist -> AppText.text(R.string.services_kind_playlist)
                    CatalogKind.Station -> AppText.text(R.string.services_kind_station)
                    CatalogKind.MusicVideo -> AppText.text(R.string.services_kind_music_video)
                    CatalogKind.Song -> AppText.text(R.string.services_kind_song)
                    CatalogKind.Artist -> ""
                    CatalogKind.Page -> ""
                }
            }
        }
        val motionVariants = editorialMotionVariants(kind == CatalogKind.Artist).ifEmpty {
            catalogAttrs?.editorialMotionVariants(kind == CatalogKind.Artist).orEmpty()
        }
        val motion = motionVariants.firstOrNull()
        val palettes = paletteFor(resolvedCatalogId)
        val hasLyrics = optBoolean("hasLyrics", false) ||
            (catalogAttrs?.optBoolean("hasLyrics", false) == true)
        return Track(
            id = "apple:$resolvedCatalogId",
            title = name,
            artist = artist,
            album = album,
            durationMs = optLong("durationInMillis", 0L).takeIf { it > 0L }
                ?: catalogAttrs?.optLong("durationInMillis", 0L)?.coerceAtLeast(0L) ?: 0L,
            palette = palettes,
            lyrics = emptyList(),
            // A source-library ID is not proof of an Apple Music catalog match.
            // Keep it as resourceId for browsing and the resolver's library lookup.
            catalogId = resolvedCatalogId.takeUnless { allowLibraryPlaceholder && it == id },
            catalogKind = kind,
            artworkUrl = artwork,
            motionArtworkUrl = motion?.videoUrl,
            motionPreviewUrl = motion?.previewUrl,
            motionArtworkVariants = motionVariants,
            hasLyricsAvailable = hasLyrics,
            resourceId = id,
            resourceType = resourceType,
            description = collectionDescription() ?: catalogAttrs?.collectionDescription(),
            url = canonicalUrl() ?: catalogAttrs?.canonicalUrl(),
            contentRating = sequenceOf(stringAttribute("contentRating"), catalogAttrs?.stringAttribute("contentRating"))
                .firstOrNull { it == "explicit" || it == "clean" },
        )
    }

    private fun JSONObject.collectionDescription(): String? {
        fun text(key: String): String? = when (val value = opt(key)) {
            is String -> value.takeIf { it.isNotBlank() && it != "null" }
            is JSONObject -> sequenceOf("standard", "short", "stringForDisplay")
                .map { value.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
            else -> null
        }
        return text("description") ?: text("plainEditorialNotes") ?: text("editorialNotes")
    }

    private fun JSONObject.stringAttribute(key: String): String? = (opt(key) as? String)
        ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.canonicalUrl(): String? = stringAttribute("url")?.takeIf { value ->
        runCatching { URI(value) }.getOrNull()?.let {
            it.scheme == "https" && it.host == "music.apple.com" && it.userInfo == null && it.port == -1
        } == true
    }


    private fun JSONObject.displayText(key: String): String? {
        val value = opt(key)
        return when (value) {
            is String -> value.takeIf(String::isNotBlank)
            is JSONObject -> value.optString("stringForDisplay").ifBlank {
                value.optString("name")
            }.takeIf(String::isNotBlank)
            else -> null
        }
    }

    private fun String.asArtworkUrl(): String? = editorialArtworkUrl()

    private fun catalogKind(type: String): CatalogKind? = when (type) {
        "songs", "library-songs" -> CatalogKind.Song
        "albums", "library-albums" -> CatalogKind.Album
        "playlists", "library-playlists" -> CatalogKind.Playlist
        "stations" -> CatalogKind.Station
        "music-videos", "library-music-videos" -> CatalogKind.MusicVideo
        "artists", "library-artists" -> CatalogKind.Artist
        "curators", "apple-curators", "record-labels", "activities", "genres", "station-genres", "library-playlist-folders" -> CatalogKind.Page
        else -> null
    }

    private fun paletteFor(id: String): List<Color> {
        val palettes = listOf(
            listOf(Color(0xFF38104E), Color(0xFFA41E62), Color(0xFFE17665)),
            listOf(Color(0xFF102E49), Color(0xFF147185), Color(0xFFD3A25E)),
            listOf(Color(0xFF242044), Color(0xFF5B55A7), Color(0xFFEA786D)),
            listOf(Color(0xFF143A32), Color(0xFF378C69), Color(0xFFD6C94D)),
        )
        return palettes[(id.hashCode() and Int.MAX_VALUE) % palettes.size]
    }

    private companion object {
        const val TAG = "AmAtvAppleMusic"
    }
}

internal fun parseUserStorefrontId(root: JSONObject): String? = root.optJSONArray("data")
    ?.optJSONObject(0)
    ?.optString("id")
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf { it.matches(Regex("[a-z]{2}")) }

/** Reporting/global IDs are not the catalog resource's current playback identity. */
internal fun catalogPlaybackId(
    resourceId: String,
    resourceType: String?,
    playParams: JSONObject?,
    linkedCatalog: JSONObject?,
): String {
    fun JSONObject?.identifier(key: String): String? = this?.optString(key)
        ?.takeIf { it.isNotBlank() && it != "null" }
    val isLibrary = resourceType?.startsWith("library-") == true ||
        resourceId.startsWith("i.") || resourceId.startsWith("l.")
    return if (isLibrary) {
        linkedCatalog.identifier("id")
            ?: linkedCatalog?.optJSONObject("attributes")?.optJSONObject("playParams").identifier("id")
            ?: playParams.identifier("catalogId")
            ?: resourceId
    } else {
        playParams.identifier("id") ?: resourceId
    }
}

/** Only Apple's explicit per-source mapping may relink a library recording. */
internal fun equivalentCatalogSongId(root: JSONObject, originalId: String): String? {
    val mappings = root.optJSONObject("meta")?.optJSONObject("filters")
        ?.optJSONObject("equivalents")?.optJSONArray(originalId) ?: return null
    val targets = (0 until mappings.length()).mapNotNull { index ->
        mappings.optJSONObject(index)?.takeIf { it.optString("type") == "songs" }
            ?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
    }.distinct()
    val target = targets.singleOrNull() ?: return null
    val data = root.optJSONArray("data") ?: return null
    val resource = (0 until data.length()).firstNotNullOfOrNull { index ->
        data.optJSONObject(index)?.takeIf {
            it.optString("id") == target && it.optString("type") == "songs"
        }
    } ?: return null
    val playParams = resource.optJSONObject("attributes")?.optJSONObject("playParams") ?: return null
    return target.takeIf { playParams.optString("id") == target && playParams.optString("kind") == "song" }
}
