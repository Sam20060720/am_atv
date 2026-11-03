package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Favorites and personal ratings are separate Apple Music concepts. */
enum class PersonalRating(val value: Int) { Like(1), Dislike(-1) }

data class CatalogItemActions(
    val canAddToLibrary: Boolean,
    val canFavorite: Boolean,
    val canRate: Boolean,
    val canAppendToPlaylist: Boolean,
)

data class CatalogActionState(
    val inFavorites: Boolean?,
    val rating: PersonalRating?,
    val canEditPlaylist: Boolean?,
    val inLibrary: Boolean? = null,
    val libraryResource: CatalogResourceRef? = null,
)

data class CreatedLibraryResource(val id: String, val type: String, val name: String)

/** A failed optional Library step does not undo an already-confirmed favorite/playlist write. */
data class LibraryAdditionResult(
    val attemptedItems: List<Track>,
    val pendingItems: List<Track>,
    val retryAllowed: Boolean = false,
)

/** A returned ID can be rechecked without another POST or a full Library scan. */
data class PendingPlaylistCreation internal constructor(
    val name: String,
    val description: String?,
    val tracks: List<Track>,
    val parentFolderId: String,
    val folder: Boolean,
    val created: CreatedLibraryResource? = null,
)

class PendingPlaylistAppend internal constructor(
    val playlist: Track,
    val tracks: List<Track>,
    internal val previousTracks: List<PlaylistMutationTrack>,
)

internal data class PlaylistMutationTrack(val id: String, val type: String, val catalogId: String?)

/**
 * User-triggered mutations share the API transport and authentication. No automatic
 * retry is added here: retrying a timed-out playlist append/create can duplicate it.
 */
class CatalogActions internal constructor(
    private val storefront: suspend () -> String,
    private val request: suspend (path: String, method: String, body: String?) -> JSONObject,
) {
    private data class Resource(val id: String, val type: String)

    fun availableActions(item: Track): CatalogItemActions {
        val catalog = catalogResource(item)
        val source = sourceResource(item)
        return CatalogItemActions(
            canAddToLibrary = source?.type?.startsWith("library-") == false && catalog?.type in libraryTypes,
            canFavorite = catalog?.type in favoriteTypes,
            canRate = source?.type in ratingTypes,
            canAppendToPlaylist = source?.type in playlistTrackTypes,
        )
    }

    /** Null fields mean the service didn't report them, not false. */
    suspend fun readState(item: Track): CatalogActionState = coroutineScope {
        val source = checkNotNull(sourceResource(item))
        val ratingRequest = if (source.type in ratingTypes) async { rating(item) } else null
        val linkedFavoriteRequest = if (source.type.startsWith("library-") && catalogResource(item) != null)
            async { favoriteState(item) } else null
        val path = if (source.type.startsWith("library-")) {
            "/v1/me/library/${source.type.removePrefix("library-")}/${source.id}"
        } else "/v1/catalog/${storefront()}/${source.type}/${source.id}" + buildList {
            if (source.type in libraryTypes) add("include=library")
            if (source.type in favoriteTypes) add("extend=inFavorites")
        }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        val root = request(path, "GET", null)
        val resource = root.optJSONArray("data")?.optJSONObject(0)
            ?.takeIf { it.optString("id") == source.id && it.optString("type") == source.type }
        val attributes = resource?.optJSONObject("attributes")
        val libraryData = resource?.optJSONObject("relationships")?.optJSONObject("library")?.optJSONArray("data")
        val linkedLibrary = linkedLibraryResource(libraryData, source.type)
        CatalogActionState(
            // Library attributes can lag the canonical catalog favorite state.
            // A missing catalog flag remains unknown; it is not permission to
            // confirm a write from a stale Library copy.
            inFavorites = if (source.type.startsWith("library-")) linkedFavoriteRequest?.await()
                else attributes?.opt("inFavorites") as? Boolean,
            rating = ratingRequest?.await(),
            canEditPlaylist = if (linkedLibrary != null) linkedLibrary.optJSONObject("attributes")?.opt("canEdit") as? Boolean
                else attributes?.opt("canEdit") as? Boolean,
            inLibrary = if (source.type.startsWith("library-")) resource?.let { true } else {
                libraryData?.let { if (it.length() == 0) false else linkedLibrary?.let { true } }
            },
            libraryResource = if (source.type.startsWith("library-")) resource?.let { CatalogResourceRef(source.id, source.type) }
                else linkedLibrary?.let { CatalogResourceRef(it.optString("id"), it.optString("type")) },
        )
    }

    /** Apple accepts additions asynchronously; refresh the resource/library to confirm visibility. */
    suspend fun addToLibrary(items: List<Track>) {
        require(items.isNotEmpty())
        val resources = items.map { item ->
            require(sourceResource(item)?.type?.startsWith("library-") == false)
            checkNotNull(catalogResource(item)).also { require(it.type in libraryTypes) }
        }
        request(typedIds("/v1/me/library", resources), "POST", null)
    }

    /** Independent of optional ratings: a failed rating read cannot hide a saved item. */
    suspend fun libraryState(item: Track): Boolean? {
        val source = checkNotNull(sourceResource(item))
        val library = source.type.startsWith("library-")
        val path = if (library) "/v1/me/library/${source.type.removePrefix("library-")}/${source.id}"
            else "/v1/catalog/${storefront()}/${source.type}/${source.id}?include=library"
        val resource = exactResource(request(path, "GET", null), source.id, source.type) ?: return null
        return if (library) true else resource.optJSONObject("relationships")?.optJSONObject("library")
            ?.optJSONArray("data")?.let { data ->
                if (data.length() == 0) false else linkedLibraryResource(data, source.type)?.let { true }
            }
    }

    /** Optional follow-up only: one Library POST, with independent readback and partial results. */
    suspend fun addEligibleItemsToLibraryAndConfirm(items: List<Track>): LibraryAdditionResult {
        val eligible = items.filter { availableActions(it).canAddToLibrary }.distinctBy { sourceResource(it) }
        if (eligible.isEmpty()) return LibraryAdditionResult(emptyList(), emptyList())
        try {
            addToLibrary(eligible)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return LibraryAdditionResult(eligible, eligible,
                retryAllowed = failure is CatalogRequestException && failure.httpStatus in 400..499)
        }
        return LibraryAdditionResult(eligible, libraryItemsAwaitingConfirmation(eligible))
    }

    /** Refreshing a partial result never repeats the favorite, playlist, or Library POST. */
    suspend fun libraryItemsAwaitingConfirmation(items: List<Track>): List<Track> {
        val pending = mutableListOf<Track>()
        for (item in items) {
            val inLibrary = try { libraryState(item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (inLibrary != true) pending += item
        }
        return pending
    }

    /** Does not change a personal Like/Dislike rating. No undocumented remove-favorite is invented. */
    suspend fun addToFavorites(items: List<Track>) {
        require(items.isNotEmpty())
        val resources = items.map { checkNotNull(catalogResource(it)).also { ref -> require(ref.type in favoriteTypes) } }
        request(typedIds("/v1/me/favorites", resources), "POST", null)
    }

    /** Favorites belong to the canonical catalog resource, including linked Library items. */
    suspend fun favoriteState(item: Track): Boolean? {
        val resource = checkNotNull(catalogResource(item)).also { require(it.type in favoriteTypes) }
        val root = request("/v1/catalog/${storefront()}/${resource.type}/${resource.id}?extend=inFavorites", "GET", null)
        val result = root.optJSONArray("data")?.optJSONObject(0)
            ?.takeIf { it.optString("id") == resource.id && it.optString("type") == resource.type }
        return result?.optJSONObject("attributes")?.opt("inFavorites") as? Boolean
    }

    /** One explicit write followed by a read; never retry the mutation after uncertainty. */
    suspend fun addToFavoritesAndConfirm(item: Track): FavoriteMutationResult {
        try {
            addToFavorites(listOf(item))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is CatalogRequestException && failure.httpStatus in 400..499) throw failure
            throw FavoriteMutationUnconfirmedException(failure)
        }
        val confirmed = try {
            favoriteState(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The POST succeeded. A failed status read cannot turn that into a
            // definitive write failure or authorize an automatic second POST.
            null
        }
        return FavoriteMutationResult(confirmed)
    }

    suspend fun rating(item: Track): PersonalRating? {
        val source = checkNotNull(sourceResource(item)).also { require(it.type in ratingTypes) }
        val root = try { request("/v1/me/ratings/${source.type}/${source.id}", "GET", null) }
        catch (error: CatalogRequestException) {
            if (error.httpStatus == 404) return null
            throw error
        }
        return when (root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")?.optInt("value")) {
            1 -> PersonalRating.Like
            -1 -> PersonalRating.Dislike
            else -> null
        }
    }

    suspend fun setRating(item: Track, rating: PersonalRating) {
        val source = checkNotNull(sourceResource(item)).also { require(it.type in ratingTypes) }
        val body = JSONObject().put("type", "rating").put("attributes", JSONObject().put("value", rating.value))
        request("/v1/me/ratings/${source.type}/${source.id}", "PUT", body.toString())
    }

    suspend fun deleteRating(item: Track) {
        val source = checkNotNull(sourceResource(item)).also { require(it.type in ratingTypes) }
        request("/v1/me/ratings/${source.type}/${source.id}", "DELETE", null)
    }

    suspend fun createPlaylist(
        name: String,
        description: String? = null,
        tracks: List<Track> = emptyList(),
        parentFolderId: String? = null,
    ): CreatedLibraryResource {
        require(name.isNotBlank())
        val attributes = JSONObject().put("name", name.trim())
        description?.takeIf(String::isNotBlank)?.let { attributes.put("description", it) }
        val body = JSONObject().put("attributes", attributes)
        val relationships = JSONObject()
        if (tracks.isNotEmpty()) relationships.put("tracks", JSONObject().put("data", trackData(tracks)))
        parentFolderId?.let { relationships.put("parent", parent(it)) }
        if (relationships.length() > 0) body.put("relationships", relationships)
        val root = request("/v1/me/library/playlists", "POST", body.toString())
        return created(root, "library-playlists", name.trim())
    }

    /** Track order and repeated entries are retained. Target must be a real library playlist. */
    suspend fun appendPlaylistTracks(playlist: Track, tracks: List<Track>) {
        val target = checkNotNull(sourceResource(playlist))
        require(target.type == "library-playlists" && tracks.isNotEmpty())
        request("/v1/me/library/playlists/${target.id}/tracks", "POST",
            JSONObject().put("data", trackData(tracks)).toString())
    }

    suspend fun createPlaylistFolder(name: String, parentFolderId: String? = null): CreatedLibraryResource {
        require(name.isNotBlank())
        val body = JSONObject().put("attributes", JSONObject().put("name", name.trim()))
        parentFolderId?.let { body.put("relationships", JSONObject().put("parent", parent(it))) }
        return created(request("/v1/me/library/playlist-folders", "POST", body.toString()),
            "library-playlist-folders", name.trim())
    }

    fun preparePlaylistCreation(
        name: String,
        description: String? = null,
        tracks: List<Track> = emptyList(),
        parentFolderId: String? = null,
        folder: Boolean = false,
    ): PendingPlaylistCreation {
        require(name.isNotBlank())
        val parent = parentFolderId ?: "p.playlistsroot"
        require(validId(parent))
        if (folder) require(tracks.isEmpty()) else trackData(tracks)
        return PendingPlaylistCreation(name.trim(), description?.takeIf(String::isNotBlank), tracks.toList(),
            parent, folder)
    }

    /** Exactly one write. A caller retains pending even if its response is lost. */
    suspend fun submitPlaylistCreation(pending: PendingPlaylistCreation): CreatedLibraryResource =
        if (pending.folder) createPlaylistFolder(pending.name, pending.parentFolderId)
        else createPlaylist(pending.name, pending.description, pending.tracks, pending.parentFolderId)

    /** A lost response ID cannot be recovered safely by guessing from a playlist name. */
    suspend fun confirmPlaylistCreation(pending: PendingPlaylistCreation): CreatedLibraryResource? {
        val type = if (pending.folder) "library-playlist-folders" else "library-playlists"
        val created = pending.created ?: return null
        if (created.type != type || !validId(created.id)) return null
        val resource = libraryResource(Resource(created.id, type)) ?: return null
        val attributes = resource.optJSONObject("attributes") ?: return null
        if (attributes.optString("name") != pending.name) return null
        if (pending.description != null) {
            val description = attributes.optJSONObject("description")?.optString("standard")
                ?: attributes.opt("description") as? String
            if (description != pending.description) return null
        }
        val parent = request("/v1/me/library/${type.removePrefix("library-")}/${created.id}/parent", "GET", null)
        if (exactResource(parent, pending.parentFolderId, "library-playlist-folders") == null) return null
        if (!pending.folder) {
            val observed = playlistTracks(created.id)
            if (observed.size != pending.tracks.size || !pending.tracks.indices.all { index ->
                    matches(checkNotNull(sourceResource(pending.tracks[index])), observed[index])
                }) return null
        }
        return CreatedLibraryResource(created.id, type, pending.name)
    }

    suspend fun preparePlaylistAppend(playlist: Track, tracks: List<Track>): PendingPlaylistAppend {
        val target = checkNotNull(sourceResource(playlist))
        require(target.type == "library-playlists" && tracks.isNotEmpty())
        trackData(tracks)
        return PendingPlaylistAppend(playlist, tracks.toList(), playlistTracks(target.id))
    }

    suspend fun submitPlaylistAppend(pending: PendingPlaylistAppend) =
        appendPlaylistTracks(pending.playlist, pending.tracks)

    /** Compare occurrences and order, never set membership or distinct song IDs. */
    suspend fun confirmPlaylistAppend(pending: PendingPlaylistAppend): Boolean {
        val target = checkNotNull(sourceResource(pending.playlist))
        val observed = playlistTracks(target.id)
        val offset = pending.previousTracks.size
        if (observed.size < offset + pending.tracks.size) return false
        if (!pending.previousTracks.indices.all { index ->
                val previous = pending.previousTracks[index]
                previous.id == observed[index].id && previous.type == observed[index].type
            }) return false
        return pending.tracks.indices.all { index ->
            matches(checkNotNull(sourceResource(pending.tracks[index])), observed[offset + index])
        }
    }

    private fun matches(expected: Resource, observed: PlaylistMutationTrack): Boolean =
        if (expected.type.startsWith("library-")) expected.id == observed.id && expected.type == observed.type
        else expected.type == observed.type.removePrefix("library-") &&
            expected.id == (if (observed.type.startsWith("library-")) observed.catalogId else observed.id)

    private suspend fun libraryResource(resource: Resource): JSONObject? = try {
        exactResource(request("/v1/me/library/${resource.type.removePrefix("library-")}/${resource.id}", "GET", null),
            resource.id, resource.type)
    } catch (failure: CatalogRequestException) {
        if (failure.httpStatus == 404) null else throw failure
    }

    private fun exactResource(root: JSONObject, id: String, type: String): JSONObject? =
        root.optJSONArray("data")?.let { data -> (0 until data.length()).mapNotNull(data::optJSONObject)
            .firstOrNull { it.optString("id") == id && it.optString("type") == type } }

    private fun linkedLibraryResource(data: JSONArray?, sourceType: String): JSONObject? {
        data ?: return null
        var linked: JSONObject? = null
        for (index in 0 until data.length()) {
            val candidate = data.optJSONObject(index) ?: return null
            val id = candidate.optString("id")
            if (candidate.optString("type") != "library-$sourceType" || !validId(id) ||
                (sourceType == "songs" && !id.startsWith("i."))) return null
            if (linked != null && linked.optString("id") != id) return null
            if (linked == null) linked = candidate
        }
        return linked
    }

    private suspend fun playlistTracks(id: String): List<PlaylistMutationTrack> = relationshipPages(
        CollectionRequest("/v1/me/library/playlists/$id/tracks", true),
    ).map { item ->
        val source = item.optString("id").also { require(validId(it)) }
        val type = item.optString("type").also { require(it in playlistTrackTypes) }
        val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")?.optJSONArray("data")
            ?.let { data -> (0 until data.length()).mapNotNull(data::optJSONObject).firstOrNull {
                it.optString("type") == type.removePrefix("library-") && validId(it.optString("id"))
            } }?.optString("id")
        val catalog = linked ?: item.optJSONObject("attributes")?.optJSONObject("playParams")
            ?.optString("catalogId")?.takeIf(::validId)
        PlaylistMutationTrack(source, type, catalog)
    }

    private suspend fun relationshipPages(pages: CollectionRequest): List<JSONObject> {
        val resources = mutableListOf<JSONObject>()
        val visited = HashSet<String>()
        var path: String? = pages.firstPage
        while (path != null) {
            val currentPath = path
            check(visited.add(currentPath)) { "Repeated library relationship page" }
            val root = requestRelationshipData { request(currentPath, "GET", null) }
            val data = checkNotNull(root.optJSONArray("data"))
            for (index in 0 until data.length()) resources += data.getJSONObject(index)
            path = root.optString("next").takeIf { it.isNotBlank() && it != "null" }?.let(pages::nextPage)
        }
        return resources
    }

    private fun parent(id: String): JSONObject {
        require(validId(id))
        return JSONObject().put("data", JSONArray().put(JSONObject().put("id", id).put("type", "library-playlist-folders")))
    }

    private fun trackData(tracks: List<Track>): JSONArray = JSONArray().also { data ->
        tracks.forEach { item ->
            val source = checkNotNull(sourceResource(item)).also { require(it.type in playlistTrackTypes) }
            data.put(JSONObject().put("id", source.id).put("type", source.type))
        }
    }

    private fun created(root: JSONObject, type: String, fallbackName: String): CreatedLibraryResource {
        val data = checkNotNull(root.optJSONArray("data")?.optJSONObject(0))
        val id = data.optString("id")
        check(validId(id) && data.optString("type") == type)
        return CreatedLibraryResource(id, type, data.optJSONObject("attributes")?.optString("name")
            ?.takeIf(String::isNotBlank) ?: fallbackName)
    }

    private fun typedIds(endpoint: String, items: List<Resource>): String = endpoint + "?" +
        items.distinct().groupBy { it.type }.entries.joinToString("&") { (type, resources) ->
            encode("ids[$type]") + "=" + encode(resources.joinToString(",") { it.id })
        }

    private fun sourceResource(item: Track): Resource? {
        if (item.catalogKind == CatalogKind.Page) return null
        val sourceIsLibrary = item.resourceType?.startsWith("library-") == true
        val id = item.resourceId ?: if (sourceIsLibrary) {
            // The parser's presentation id may contain the linked catalog id.
            // It must not be sent as a library id if original provenance was lost.
            item.id.removePrefix("apple:").takeIf { it.startsWith("i.") || it.startsWith("l.") ||
                it.startsWith("p.") || it.startsWith("r.") } ?: return null
        } else item.catalogId ?: item.id.removePrefix("apple:")
        if (!validId(id)) return null
        val type = item.resourceType ?: when {
            id.startsWith("i.") -> if (item.catalogKind == CatalogKind.MusicVideo) "library-music-videos" else "library-songs"
            id.startsWith("l.") -> "library-albums"
            id.startsWith("p.") -> "library-playlists"
            id.startsWith("r.") -> "library-artists"
            else -> catalogType(item.catalogKind)
        }
        val expected = catalogType(item.catalogKind)
        if (type != expected && type != "library-$expected") return null
        return Resource(id, type)
    }

    private fun catalogResource(item: Track): Resource? {
        val source = sourceResource(item) ?: return null
        if (!source.type.startsWith("library-")) return source
        val id = item.catalogId?.takeIf { it != source.id && validId(it) } ?: return null
        return Resource(id, catalogType(item.catalogKind))
    }

    private fun catalogType(kind: CatalogKind): String = when (kind) {
        CatalogKind.Song -> "songs"
        CatalogKind.Album -> "albums"
        CatalogKind.Playlist -> "playlists"
        CatalogKind.MusicVideo -> "music-videos"
        CatalogKind.Artist -> "artists"
        CatalogKind.Station -> "stations"
        CatalogKind.Page -> error("This page is not a catalog action resource")
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9._-]+"))
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        val libraryTypes = setOf("albums", "songs", "playlists", "music-videos")
        val favoriteTypes = libraryTypes + "artists"
        val ratingTypes = libraryTypes + "stations" + libraryTypes.map { "library-$it" }
        val playlistTrackTypes = setOf("songs", "music-videos", "library-songs", "library-music-videos")
    }
}
