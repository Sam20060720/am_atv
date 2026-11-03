package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

data class ManagedLibraryEntry(val id: String, val type: String, val title: String, val artist: String)
data class ManagedLibraryResource(val id: String, val type: String, val name: String,
    val canEdit: Boolean?, val canDelete: Boolean?)
data class ManagedPlaylistSnapshot internal constructor(val resource: ManagedLibraryResource,
    val entries: List<ManagedLibraryEntry>, val complete: Boolean = false)
data class PendingLibraryDeletion internal constructor(val item: Track, val parentId: String?, val catalogId: String? = null)

class LibraryManagementUnconfirmed(cause: Throwable? = null) :
    IllegalStateException("Read the changed resource before another write", cause)
class LibraryPlaylistConflict : IllegalStateException("Playlist changed since it was loaded")
class LibraryFolderNotEmpty : IllegalStateException("Move this folder's contents before deleting it")

/** Actual web operations; the sheet only exposes operations verified by the dedicated probe. */
class CatalogLibraryManagement internal constructor(
    private val storefront: suspend () -> String,
    private val request: suspend (String, String, String?) -> JSONObject,
) {
    val capabilities: CatalogMutationCapabilities get() = CatalogMutationCapabilities.VerifiedWebMusicUserToken

    suspend fun resource(item: Track): ManagedLibraryResource {
        val (id, type) = libraryIdentity(item)
        if (type == "library-playlist-folders" && id == "p.playlistsroot") {
            // Canonical virtual root uses the documented identity lookup, not a single-folder GET.
            val root = request("/v1/me/library/playlist-folders?filter%5Bidentity%5D=playlistsroot", "GET", null)
            val arrays = listOfNotNull(root.optJSONArray("data"), root.optJSONObject("meta")
                ?.optJSONObject("filters")?.optJSONObject("identity")?.optJSONArray("playlistsroot"))
            val row = checkNotNull(arrays.asSequence().flatMap { data -> (0 until data.length()).asSequence().mapNotNull(data::optJSONObject) }
                .firstOrNull { it.optString("id") == id && it.optString("type") == type })
            // Root identity may have no metadata. Its name is rendered by the existing Library label.
            return ManagedLibraryResource(id, type, row.optJSONObject("attributes")?.optString("name").orEmpty(),
                canEdit = false, canDelete = false)
        }
        val row = exact(request(resourcePath(id, type), "GET", null), id, type)
        val attributes = checkNotNull(row.optJSONObject("attributes"))
        return ManagedLibraryResource(id, type, attributes.optString("name"),
            attributes.opt("canEdit") as? Boolean, attributes.opt("canDelete") as? Boolean)
    }

    suspend fun playlist(item: Track): ManagedPlaylistSnapshot = withCatalogRequestDeadline {
        val metadata = resource(item)
        require(metadata.type == "library-playlists")
        val endpoint = "/v1/me/library/playlists/${metadata.id}/tracks"
        val entries = mutableListOf<ManagedLibraryEntry>()
        val visited = HashSet<String>()
        var total: Long? = null
        var path: String? = "$endpoint?limit=100"
        while (path != null) {
            check(visited.add(path) && visited.size <= 1_000) { "Incomplete playlist pagination" }
            val page = request(path, "GET", null)
            (page.optJSONObject("meta")?.opt("total") as? Number)?.toLong()?.let { value ->
                check(value >= 0 && (total == null || total == value)) { "Playlist changed while loading" }
                total = value
            }
            val data = checkNotNull(page.optJSONArray("data"))
            for (index in 0 until data.length()) {
                val row = data.getJSONObject(index)
                val id = row.getString("id").also { require(validId(it)) }
                val type = row.getString("type").also { require(it.matches(Regex("[a-z][a-z0-9-]*"))) }
                val attributes = row.optJSONObject("attributes")
                entries += ManagedLibraryEntry(id, type, attributes?.optString("name").orEmpty(),
                    attributes?.optString("artistName").orEmpty())
            }
            path = (page.opt("next") as? String)?.takeIf(String::isNotBlank)?.let { href ->
                val uri = URI("https://amp-api.music.apple.com").resolve(href)
                require(uri.scheme == "https" && uri.host in setOf("amp-api.music.apple.com", "api.music.apple.com") &&
                    uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.rawPath == endpoint)
                uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            }
        }
        check(total == null || total == entries.size.toLong()) { "Incomplete playlist contents" }
        ManagedPlaylistSnapshot(metadata, entries.toList(), complete = true)
    }

    suspend fun rename(item: Track, name: String): Boolean {
        val target = name.trim().also { require(it.isNotEmpty() && it.length <= 200) }
        val metadata = resource(item).also {
            require(it.type in setOf("library-playlists", "library-playlist-folders"))
            require(it.id != "p.playlistsroot" && if (it.type == "library-playlist-folders") it.canDelete != false else it.canEdit != false)
        }
        write("/v1/me/library/playlists/${metadata.id}", "PATCH",
            JSONObject().put("attributes", JSONObject().put("name", target)).toString())
        return confirm { resource(item).name == target }
    }

    suspend fun prepareDeletion(item: Track): PendingLibraryDeletion {
        val metadata = resource(item).also { require(it.canDelete != false && it.id != "p.playlistsroot") }
        if (metadata.type == "library-songs") {
            val (catalogId, type) = catalogIdentity(item).also { require(it.second == "songs") }
            val rows = exact(request("/v1/catalog/${storefront()}/$type/$catalogId?include=library", "GET", null), catalogId, type)
                .optJSONObject("relationships")?.optJSONObject("library")?.optJSONArray("data")
                ?: error("Unknown Library membership")
            require((0 until rows.length()).any { rows.optJSONObject(it)?.let { row ->
                row.optString("id") == metadata.id && row.optString("type") == "library-songs"
            } == true }) { "Exact Library song membership not confirmed" }
            return PendingLibraryDeletion(item, parentId = null, catalogId = catalogId)
        }
        if (metadata.type == "library-playlist-folders" && scanChildren(metadata.id, null)) throw LibraryFolderNotEmpty()
        val parentId = parent(item) ?: "p.playlistsroot".takeIf {
            scanChildren(it, metadata.id to metadata.type)
        } ?: error("Missing original playlist parent")
        return PendingLibraryDeletion(item, parentId)
    }

    suspend fun submitDeletion(pending: PendingLibraryDeletion) {
        val metadata = resource(pending.item).also { require(it.canDelete != false && it.id != "p.playlistsroot") }
        if (metadata.type == "library-playlist-folders" && scanChildren(metadata.id, null)) throw LibraryFolderNotEmpty()
        // Shipped gc[PlaylistFolder] is Playlists: both kinds mutate through this path.
        val type = if (metadata.type == "library-songs") "songs" else "playlists"
        write("/v1/me/library/$type/${metadata.id}", "DELETE", null)
    }

    /** Apple retains a readable tombstone; require its shape AND absence from the original container. */
    suspend fun confirmDeletion(pending: PendingLibraryDeletion): Boolean = confirm {
        if (pending.item.resourceType == "library-songs") {
            val id = checkNotNull(pending.catalogId)
            val rows = exact(request("/v1/catalog/${storefront()}/songs/$id?include=library", "GET", null), id, "songs")
                .optJSONObject("relationships")?.optJSONObject("library")?.optJSONArray("data") ?: return@confirm false
            return@confirm (0 until rows.length()).none { rows.optJSONObject(it)?.let { row ->
                row.optString("id") == pending.item.resourceId && row.optString("type") == "library-songs"
            } == true }
        }
        val metadata = try { resource(pending.item) }
        catch (failure: CatalogRequestException) { if (failure.httpStatus == 404) return@confirm true else throw failure }
        if (metadata.name.isNotBlank() || metadata.canEdit != false || metadata.canDelete != false) return@confirm false
        val identity = metadata.id to metadata.type
        val parentId = checkNotNull(pending.parentId)
        !scanChildren(parentId, identity) &&
            (parentId == "p.playlistsroot" || !scanChildren("p.playlistsroot", identity))
    }

    suspend fun reorder(item: Track, original: ManagedPlaylistSnapshot, order: List<Int>): Boolean {
        val (id, type) = libraryIdentity(item)
        require(type == "library-playlists" && original.resource.id == id && original.resource.canEdit != false && original.complete)
        require(order.size == original.entries.size && order.toSet() == original.entries.indices.toSet())
        val live = playlist(item).also { require(it.resource.canEdit != false) }
        if (identity(live.entries) != identity(original.entries)) throw LibraryPlaylistConflict()
        val desired = order.map(original.entries::get)
        if (identity(desired) == identity(live.entries)) return true
        val data = JSONArray(desired.map { JSONObject().put("id", it.id).put("type", it.type) })
        write("/v1/me/library/playlists/$id/tracks", "PUT", JSONObject().put("data", data).toString())
        return confirm { identity(playlist(item).entries) == identity(desired) }
    }

    /** The shipped move intent addresses playlists/{id}/parent for playlists and folders. */
    suspend fun move(item: Track, folder: Track): Boolean {
        val source = resource(item).also { require(it.canDelete != false && it.id != "p.playlistsroot") }
        require(source.type in setOf("library-playlists", "library-playlist-folders"))
        val destination = resource(folder).also { require(it.type == "library-playlist-folders" &&
            (it.id == "p.playlistsroot" || it.name.isNotBlank())) }
        require(source.id != destination.id)
        if (source.type == "library-playlist-folders") withCatalogRequestDeadline {
            val visited = HashSet<String>()
            var ancestor = folder
            while (true) {
                val id = checkNotNull(ancestor.resourceId)
                require(id != source.id) { "A folder cannot be moved into its descendant" }
                check(visited.add(id) && visited.size <= 1_000) { "Invalid folder hierarchy" }
                if (id == "p.playlistsroot") break
                val parentId = checkNotNull(parent(ancestor)) { "Missing destination ancestor" }
                ancestor = folder.copy(id = "apple:$parentId", resourceId = parentId)
            }
        }
        val data = JSONArray().put(JSONObject().put("id", destination.id).put("type", destination.type))
        write("/v1/me/library/playlists/${source.id}/parent", "PUT", JSONObject().put("data", data).toString())
        return confirm { parent(item) == destination.id }
    }

    suspend fun parent(item: Track): String? {
        val (id, type) = libraryIdentity(item)
        val row = request(resourcePath(id, type) + "/parent", "GET", null)
            .optJSONArray("data")?.optJSONObject(0) ?: return null
        return row.optString("id").takeIf { row.optString("type") == "library-playlist-folders" && validId(it) }
    }

    suspend fun removeFavorite(item: Track): Boolean {
        val (id, type) = catalogIdentity(item)
        val key = URLEncoder.encode("ids[$type]", "UTF-8")
        write("/v1/me/favorites?$key=${URLEncoder.encode(id, "UTF-8")}", "DELETE", null)
        return confirm { favoriteState(item) == false }
    }

    suspend fun favoriteState(item: Track): Boolean? {
        val (id, type) = catalogIdentity(item)
        val row = exact(request("/v1/catalog/${storefront()}/$type/$id?extend=inFavorites", "GET", null), id, type)
        return row.optJSONObject("attributes")?.opt("inFavorites") as? Boolean
    }

    private fun catalogIdentity(item: Track): Pair<String, String> {
        val type = when (item.catalogKind) {
            CatalogKind.Song -> "songs"; CatalogKind.Album -> "albums"; CatalogKind.Artist -> "artists"
            CatalogKind.Playlist -> "playlists"; CatalogKind.MusicVideo -> "music-videos"
            else -> error("Unsupported favorite resource")
        }
        val library = item.resourceType?.startsWith("library-") == true
        val id = (if (library) item.catalogId?.takeIf { it != item.resourceId }
            else item.resourceId ?: item.catalogId ?: item.id.removePrefix("apple:"))
            ?.also { require(validId(it)) } ?: error("Canonical catalog identity required")
        require(if (type == "playlists") id.startsWith("pl.") else id.matches(Regex("[1-9][0-9]*")))
        return id to type
    }
    private fun libraryIdentity(item: Track): Pair<String, String> {
        val type = checkNotNull(item.resourceType).also {
            require(it in setOf("library-playlists", "library-playlist-folders", "library-songs"))
        }
        val id = checkNotNull(item.resourceId).also {
            require(it.startsWith(if (type == "library-songs") "i." else "p.") && validId(it))
        }
        return id to type
    }
    private fun resourcePath(id: String, type: String) = "/v1/me/library/${type.removePrefix("library-")}/$id"
    private suspend fun scanChildren(folderId: String, target: Pair<String, String>?): Boolean = withCatalogRequestDeadline {
        require(validId(folderId) && folderId.startsWith("p."))
        val endpoint = "/v1/me/library/playlist-folders/$folderId/children"
        var path: String? = "$endpoint?limit=100"
        val visited = HashSet<String>()
        while (path != null) {
            check(visited.add(path) && visited.size <= 1_000) { "Incomplete folder pagination" }
            val page = requestRelationshipData { request(checkNotNull(path), "GET", null) }
            val data = checkNotNull(page.optJSONArray("data"))
            if (target == null && data.length() > 0) return@withCatalogRequestDeadline true
            for (index in 0 until data.length()) {
                val row = data.getJSONObject(index)
                if (target != null && row.optString("id") == target.first && row.optString("type") == target.second)
                    return@withCatalogRequestDeadline true
            }
            path = (page.opt("next") as? String)?.takeIf(String::isNotBlank)?.let { href ->
                val uri = URI("https://amp-api.music.apple.com").resolve(href)
                require(uri.scheme == "https" && uri.host in setOf("amp-api.music.apple.com", "api.music.apple.com") &&
                    uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.rawPath == endpoint)
                uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            }
        }
        false
    }
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9._-]+"))
    private fun exact(root: JSONObject, id: String, type: String): JSONObject =
        checkNotNull(root.optJSONArray("data")?.let { data -> (0 until data.length()).mapNotNull(data::optJSONObject)
            .firstOrNull { it.optString("id") == id && it.optString("type") == type } })
    private fun identity(entries: List<ManagedLibraryEntry>) = entries.map { it.type to it.id }
    private suspend fun write(path: String, method: String, body: String?) {
        try { request(path, method, body) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure is CatalogRequestException && failure.httpStatus in 400..499) throw failure
            throw LibraryManagementUnconfirmed(failure)
        }
    }
    private suspend fun confirm(read: suspend () -> Boolean): Boolean = try { read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw LibraryManagementUnconfirmed(failure) }
}
