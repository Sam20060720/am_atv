package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogMetadataField
import dev.amatv.model.CatalogMetadataFormat
import dev.amatv.model.CatalogResourceLink
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.CatalogResourceProfile
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.CatalogResourceSection
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Metadata and one requested relationship/view page; never an eager recursive resource crawl. */
class CatalogPagesApi internal constructor(
    private val storefront: suspend () -> String,
    private val hasUserToken: () -> Boolean,
    private val request: suspend (String, Boolean) -> JSONObject,
    private val parseItem: (JSONObject) -> Track?,
    private val parseDispatcher: CoroutineDispatcher,
) {
    suspend fun profile(ref: CatalogResourceRef): CatalogResourceProfile = withContext(parseDispatcher) {
        require(ref.type in supportedTypes)
        if (ref.type == "library-playlist-folders" && ref.id == "p.playlistsroot")
            return@withContext rootPlaylistFolderProfile(ref)
        val library = ref.type.startsWith("library-")
        val endpoint = endpoint(ref)
        val include = when {
            ref.type in libraryMappedTypes -> "catalog"
            ref.type in catalogLibraryTypes && hasUserToken() -> "library"
            else -> null
        }
        val legacyBase = endpoint + (include?.let { "?include=$it" } ?: "")
        val includes = listOfNotNull(include, "credits".takeIf { ref.type == "songs" })
        val base = endpoint + if (includes.isEmpty()) "" else "?include=${includes.joinToString(",")}"
        val extensions = when (ref.type) {
            "albums", "songs" -> "audioVariants,artistUrl,editorialArtwork,editorialVideo,plainEditorialNotes"
            "playlists", "music-videos" -> "editorialArtwork,editorialVideo,plainEditorialNotes"
            else -> null
        }
        val path = base + (extensions?.let { (if ('?' in base) "&" else "?") + "extend=$it" } ?: "")
        currentCoroutineContext().ensureActive()
        val root = try { request(path, library) }
        catch (error: CatalogRequestException) {
            if ((extensions == null && base == legacyBase) || error.httpStatus !in setOf(400, 403)) throw error
            // Optional artwork extensions and credits can have independent
            // availability. Keep the ordinary metadata page usable either way.
            if (path == base) request(legacyBase, library)
            else try { request(base, library) }
            catch (fallback: CatalogRequestException) {
                if (base == legacyBase || fallback.httpStatus !in setOf(400, 403)) throw fallback
                request(legacyBase, library)
            }
        }
        currentCoroutineContext().ensureActive()
        val resource = root.optJSONArray("data")?.objects()?.firstOrNull {
            it.optString("id") == ref.id && it.optString("type") == ref.type
        } ?: error(AppText.text(R.string.services_track_unavailable))
        val item = checkNotNull(parseItem(resource)) { AppText.text(R.string.services_track_unavailable) }
        val attributes = resource.optJSONObject("attributes") ?: JSONObject()
        val catalog = resource.optJSONObject("relationships")?.optJSONObject("catalog")?.optJSONArray("data")
            ?.optJSONObject(0)?.optJSONObject("attributes")
        val merged = JSONObject(catalog?.toString() ?: "{}")
        attributes.keys().forEachRemaining { merged.put(it, attributes.opt(it)) }
        val sections = sections(ref.type).filter { !it.requiresUserToken || hasUserToken() }.map { section ->
            val group = resource.optJSONObject(if (section.isView) "views" else "relationships")?.optJSONObject(section.id)
            val title = group?.optJSONObject("attributes")?.displayText("title")
                ?: group?.optJSONObject("attributes")?.displayText("name")
            section.copy(title = title)
        }
        val links = buildList {
            if (ref.type == "genres") attributes.text("parentId")?.takeIf(::validId)?.let { id ->
                add(CatalogResourceLink(CatalogResourceRef(id, "genres"), attributes.text("parentName")))
            }
        }
        val profile = CatalogResourceProfile(
            ref = ref,
            item = item,
            name = item.title,
            description = item.description,
            artworkUrl = item.artworkUrl,
            motionArtworkUrl = item.motionArtworkUrl,
            motionPreviewUrl = item.motionPreviewUrl,
            url = item.url,
            metadata = metadata(merged),
            sections = sections,
            links = links,
            inFavorites = merged.opt("inFavorites") as? Boolean,
            inLibrary = if (library) true else resource.optJSONObject("relationships")?.optJSONObject("library")
                ?.optJSONArray("data")?.let { it.length() > 0 },
            canEdit = attributes.opt("canEdit") as? Boolean,
        )
        attachSongCredits(profile, root)
    }

    /** Optional public song data can enrich a Library profile without replacing its identity. */
    internal fun attachSongCredits(profile: CatalogResourceProfile, root: JSONObject): CatalogResourceProfile {
        val songId = when (profile.ref.type) {
            "songs" -> profile.ref.id
            "library-songs" -> profile.item.catalogId
            else -> null
        }?.takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: return profile
        val data = root.optJSONArray("data")?.objects().orEmpty()
        val source = data.firstOrNull { it.optString("type") == "songs" && it.optString("id") == songId }
        val song = source?.takeIf { it.has("relationships") }
            ?: root.optJSONObject("resources")?.optJSONObject("songs")?.optJSONObject(songId)
            ?: source
            ?: return profile
        if (song.optString("type") != "songs" || song.optString("id") != songId) return profile
        val fields = songCreditMetadata(root, song)
        return if (fields.isEmpty()) profile else profile.copy(
            metadata = profile.metadata.filterNot { it.key.startsWith("credits:") } + fields)
    }

    suspend fun sectionPage(
        ref: CatalogResourceRef,
        section: CatalogResourceSection,
        nextPage: String? = null,
    ): CatalogResourcePage = withContext(parseDispatcher) {
        require(ref.type in supportedTypes)
        // UI/server titles may vary; endpoint identity comes from this verified table.
        val actual = sections(ref.type).firstOrNull { it.id == section.id && it.isView == section.isView && it.chartType == section.chartType }
            ?: error(AppText.text(R.string.services_collection_required))
        if (actual.chartType != null) return@withContext genreChartPage(ref, actual, nextPage)
        val library = ref.type.startsWith("library-")
        val component = if (actual.isView) "view/${actual.id}" else actual.id
        val endpoint = endpoint(ref) + "/$component"
        val includeCatalog = library && actual.resourceTypes.any { it.startsWith("library-") && it != "library-playlist-folders" }
        val requestPath = CollectionRequest(endpoint, library, actual.pageSize, includeCatalog)
        val firstPage = requestPath.firstPage + if (actual.isView) {
            (if ('?' in requestPath.firstPage) "&" else "?") + "with=attributes"
        } else ""
        val loaded = page(requestPath, actual.resourceTypes, nextPage, firstPage, library || actual.requiresUserToken, emptyRelationship = true)
        // Only a real station's radio-show relationship selects the additional
        // public episode shelves; ordinary curator pages retain their REST route.
        if (ref.type == "stations" && actual.id == "radio-show") loaded.copy(items = loaded.items.map { item ->
            if (item.resourceType == "apple-curators") item.copy(resourceType = "public-curators") else item
        }) else loaded
    }

    /** Public genre lists have their own server cursors; curators/labels are reached by actual IDs. */
    suspend fun rootPage(type: String, nextPage: String? = null): CatalogResourcePage = withContext(parseDispatcher) {
        require(type in setOf("genres", "station-genres"))
        val sf = storefront().also { require(it.matches(Regex("[a-z]{2}"))) }
        val requestPath = CollectionRequest("/v1/catalog/$sf/$type", false, 25, includeCatalog = false)
        page(requestPath, setOf(type), nextPage, requestPath.firstPage, false)
    }

    /** The root lookup can return only a filter identity, without ordinary folder metadata. */
    private suspend fun rootPlaylistFolderProfile(ref: CatalogResourceRef): CatalogResourceProfile {
        val root = request("/v1/me/library/playlist-folders?${encode("filter[identity]")}=playlistsroot", true)
        currentCoroutineContext().ensureActive()
        val data = root.optJSONArray("data")?.objects().orEmpty()
        val identities = root.optJSONObject("meta")?.optJSONObject("filters")?.optJSONObject("identity")
            ?.optJSONArray("playlistsroot")?.objects().orEmpty()
        val resource = (data + identities).firstOrNull {
            it.optString("id") == ref.id && it.optString("type") == ref.type
        } ?: error(AppText.text(R.string.services_track_unavailable))
        val attributes = resource.optJSONObject("attributes")
        val name = attributes?.displayText("name") ?: AppText.text(R.string.app_library_playlists)
        val item = Track(id = "apple:${ref.id}", title = name, artist = "", album = "", durationMs = 0L,
            palette = List(2) { androidx.compose.ui.graphics.Color(0xFF24242A) }, lyrics = emptyList(),
            catalogKind = CatalogKind.Page, resourceId = ref.id, resourceType = ref.type)
        return CatalogResourceProfile(ref, item, name, description = null, artworkUrl = null, motionArtworkUrl = null,
            motionPreviewUrl = null, url = null, metadata = attributes?.let(::metadata).orEmpty(),
            sections = sections(ref.type).filter { it.id == "children" }, inLibrary = true,
            canEdit = attributes?.opt("canEdit") as? Boolean)
    }

    /** Follow the source song's actual album relationship, including unmatched Library songs. */
    suspend fun albumForSong(item: Track): Track? {
        val ref = CatalogResourceRef.fromTrack(item)?.takeIf { it.type in setOf("songs", "library-songs") }
            ?: return null
        val albumSection = sections(ref.type).single { it.id == "albums" && !it.isView }
        return sectionPage(ref, albumSection).items.firstOrNull {
            it.catalogKind == dev.amatv.model.CatalogKind.Album
        }
    }

    private suspend fun genreChartPage(
        ref: CatalogResourceRef,
        section: CatalogResourceSection,
        nextPage: String?,
    ): CatalogResourcePage {
        val sf = storefront().also { require(it.matches(Regex("[a-z]{2}"))) }
        val type = section.resourceTypes.single()
        val requestPath = CollectionRequest("/v1/catalog/$sf/charts", false, 25, includeCatalog = false)
        val first = "${requestPath.endpoint}?types=$type&genre=${encode(ref.id)}&chart=most-played&limit=25"
        nextPage?.let {
            val query = query(requestPath.nextPage(it))
            require(query["genre"] == ref.id && query["types"] == type)
        }
        return page(requestPath, section.resourceTypes, nextPage, first, false) { root ->
            root.optJSONObject("results")?.optJSONArray(type)?.optJSONObject(0) ?: JSONObject().put("data", JSONArray())
        }
    }

    private suspend fun page(
        requestPath: CollectionRequest,
        types: Set<String>,
        nextPage: String?,
        firstPage: String,
        user: Boolean,
        emptyRelationship: Boolean = false,
        select: (JSONObject) -> JSONObject = { it },
    ): CatalogResourcePage {
        val path = nextPage?.let(requestPath::nextPage) ?: firstPage
        currentCoroutineContext().ensureActive()
        val response = if (emptyRelationship) {
            requestRelationshipData { requestEditorialVideo(path, types) { request(it, user) } }
        } else requestEditorialVideo(path, types) { request(it, user) }
        val root = select(response)
        currentCoroutineContext().ensureActive()
        val data = checkNotNull(root.optJSONArray("data")) { AppText.text(R.string.services_playlist_tracks_unavailable) }
        val items = data.objects().filter { it.optString("type") in types }.mapNotNull(parseItem)
        val next = root.text("next")?.let(requestPath::nextPage)
        check(next != path) { AppText.text(R.string.services_playlist_page_repeated) }
        val total = root.optJSONObject("meta")?.optInt("total", -1)?.takeIf { it >= 0 }
        return CatalogResourcePage(mergeLibraryPage(emptyList(), items), next, path, total)
    }

    private suspend fun endpoint(ref: CatalogResourceRef): String = if (ref.type.startsWith("library-")) {
        "/v1/me/library/${ref.type.removePrefix("library-")}/${ref.id}"
    } else {
        val sf = storefront().also { require(it.matches(Regex("[a-z]{2}"))) }
        "/v1/catalog/$sf/${ref.type}/${ref.id}"
    }

    private fun sections(type: String): List<CatalogResourceSection> {
        fun rel(id: String, vararg types: String, limit: Int? = 25) = CatalogResourceSection(id, false, types.toSet(), limit,
            requiresUserToken = id == "library")
        fun view(id: String, vararg types: String, limit: Int = 25) = CatalogResourceSection(id, true, types.toSet(), limit)
        return when (type) {
            "albums" -> listOf(rel("artists", "artists", limit = 10), rel("record-labels", "record-labels", limit = 10),
                rel("genres", "genres", limit = null), rel("tracks", "songs", "music-videos", limit = 100),
                view("other-versions", "albums"), view("related-albums", "albums"), view("appears-on", "playlists"),
                view("related-videos", "music-videos"), rel("library", "library-albums", limit = null))
            "songs" -> listOf(rel("artists", "artists", limit = 10), rel("composers", "artists", limit = null),
                rel("albums", "albums", limit = 10), rel("genres", "genres", limit = null),
                rel("music-videos", "music-videos", limit = null), rel("station", "stations", limit = null),
                rel("library", "library-songs", limit = null))
            "playlists" -> listOf(rel("curator", "curators", "apple-curators", limit = null),
                view("featured-artists", "artists"), view("more-by-curator", "playlists", limit = 10),
                rel("tracks", "songs", "music-videos", limit = 100), rel("library", "library-playlists", limit = null))
            "artists" -> dev.amatv.model.ArtistSection.entries.filterNot { it == dev.amatv.model.ArtistSection.LibraryAlbums }
                .map { CatalogResourceSection(it.apiName, it.isView, it.resourceTypes, it.pageSize) } + rel("genres", "genres", limit = null)
            "music-videos" -> listOf(rel("artists", "artists", limit = 10), rel("albums", "albums", limit = 10),
                rel("genres", "genres", limit = null), rel("songs", "songs", limit = 10),
                rel("library", "library-music-videos", limit = null))
            "curators", "apple-curators", "activities" -> listOf(rel("playlists", "playlists", limit = 10))
            "record-labels" -> listOf(view("latest-releases", "albums"), view("top-releases", "albums"))
            "genres" -> listOf("songs", "albums", "playlists", "music-videos").map {
                CatalogResourceSection("charts-$it", false, setOf(it), 25, chartType = "most-played")
            }
            "station-genres" -> listOf(rel("stations", "stations"))
            "stations" -> listOf(rel("radio-show", "apple-curators", limit = null))
            "library-albums" -> listOf(rel("artists", "library-artists", limit = 10),
                rel("catalog", "albums", limit = null), rel("tracks", "library-songs", "library-music-videos", limit = 100))
            "library-songs" -> listOf(rel("artists", "library-artists", limit = 10), rel("albums", "library-albums", limit = 10),
                rel("catalog", "songs", limit = null))
            "library-music-videos" -> listOf(rel("artists", "library-artists", limit = 10), rel("albums", "library-albums", limit = 10),
                rel("catalog", "music-videos", limit = null))
            "library-artists" -> listOf(rel("albums", "library-albums", limit = 100), rel("catalog", "artists", limit = null))
            "library-playlists" -> listOf(rel("catalog", "playlists", limit = null),
                rel("tracks", "library-songs", "library-music-videos", limit = 100))
            "library-playlist-folders" -> listOf(rel("children", "library-playlist-folders", "library-playlists", limit = 100),
                rel("parent", "library-playlist-folders", limit = null))
            else -> emptyList()
        }
    }

    private fun metadata(attributes: JSONObject): List<CatalogMetadataField> {
        val primary = listOf("artistName", "albumName", "genreNames", "releaseDate", "dateAdded", "lastModifiedDate",
            "copyright", "recordLabel", "trackCount", "contentRating", "audioVariants", "composerName", "workName",
            "movementName", "movementNumber", "movementCount", "discNumber", "trackNumber", "durationInMillis",
            "isrc", "upc", "attribution", "playlistType", "trackTypes", "isChart", "isSingle", "isCompilation", "isComplete",
            "isAppleDigitalMaster", "isMasteredForItunes", "hasLyrics", "has4K", "hasHDR", "videoSubType",
            "episodeNumber", "stationProviderName", "mediaKind", "isLive", "kind", "shortName", "showHostName",
            "parentId", "parentName", "chartLabel", "canEdit", "isPublic", "hasCatalog", "inFavorites")
        return primary.mapNotNull { key ->
            val raw = attributes.opt(key)
            val values = when (raw) {
                is String -> listOf(raw).filter { it.isNotBlank() && it != "null" }
                is Number, is Boolean -> listOf(raw.toString())
                is JSONArray -> (0 until raw.length()).mapNotNull { index ->
                    when (val value = raw.opt(index)) { is String -> value.takeIf(String::isNotBlank); is Number, is Boolean -> value.toString(); else -> null }
                }
                else -> emptyList()
            }
            if (values.isEmpty()) return@mapNotNull null
            val format = when {
                key == "audioVariants" -> CatalogMetadataFormat.AudioVariants
                key == "genreNames" -> CatalogMetadataFormat.Genres
                key == "durationInMillis" -> CatalogMetadataFormat.Duration
                key in setOf("releaseDate", "dateAdded", "lastModifiedDate") -> CatalogMetadataFormat.Date
                key in setOf("isrc", "upc", "parentId") -> CatalogMetadataFormat.Identifier
                raw is Boolean -> CatalogMetadataFormat.Boolean
                raw is Number -> CatalogMetadataFormat.Number
                else -> CatalogMetadataFormat.Text
            }
            CatalogMetadataField(key, values, format)
        }
    }

    /** Read actual groups/roles, never turn composerName into a complete credit list. */
    private fun songCreditMetadata(root: JSONObject, song: JSONObject): List<CatalogMetadataField> {
        val embedded = root.optJSONArray("data")?.objects().orEmpty() + root.optJSONArray("included")?.objects().orEmpty()
        val resources = root.optJSONObject("resources")
        fun resolve(reference: JSONObject): JSONObject {
            if (reference.has("attributes") || reference.has("relationships")) return reference
            val type = reference.optString("type")
            val id = reference.optString("id")
            return resources?.optJSONObject(type)?.optJSONObject(id) ?: embedded.firstOrNull {
                it.optString("type") == type && it.optString("id") == id
            } ?: reference
        }
        return song.optJSONObject("relationships")?.optJSONObject("credits")?.optJSONArray("data")
            ?.objects().orEmpty().mapNotNull group@ { reference ->
                if (reference.optString("type") != "role-categories") return@group null
                val group = resolve(reference)
                val attributes = group.optJSONObject("attributes") ?: return@group null
                val title = attributes.displayText("title") ?: return@group null
                // Credit IDs are opaque display keys, never request paths.
                val id = reference.text("id")?.takeIf { it.length <= 256 } ?: return@group null
                val values = group.optJSONObject("relationships")?.optJSONObject("credit-artists")?.optJSONArray("data")
                    ?.objects().orEmpty().mapNotNull person@ { person ->
                        if (person.optString("type") != "credit-artists") return@person null
                        val credit = resolve(person).optJSONObject("attributes") ?: return@person null
                        val name = credit.text("name") ?: return@person null
                        val roles = credit.optJSONArray("roleNames")?.let { array ->
                            (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }
                        }.orEmpty()
                        if (roles.isEmpty()) name else "$name · ${roles.joinToString(", ")}"
                    }
                values.takeIf { it.isNotEmpty() }?.let { CatalogMetadataField("credits:$id", it, title = title) }
            }
    }

    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject.displayText(key: String): String? = text(key) ?: optJSONObject(key)?.text("stringForDisplay")
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9._-]+"))
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun query(path: String): Map<String, String> = URI(path).rawQuery.orEmpty().split('&').mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size != 2) null else URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }.toMap()

    private companion object {
        val catalogLibraryTypes = setOf("albums", "songs", "playlists", "music-videos")
        val libraryMappedTypes = setOf("library-albums", "library-songs", "library-playlists", "library-music-videos", "library-artists")
        val supportedTypes = catalogLibraryTypes + libraryMappedTypes + setOf("artists", "stations", "curators", "apple-curators",
            "record-labels", "activities", "genres", "station-genres", "library-playlist-folders")
    }
}
