package dev.amatv.integration

import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class CachedCatalogFeed(
    val shelves: List<ContentShelf>,
    val savedAtEpochMs: Long,
    val homeSnapshot: HomeFeedSnapshot? = null,
    val truncated: Boolean = false,
    /** A partial source refresh must not masquerade as a complete fresh feed. */
    val complete: Boolean = true,
) {
    val requiresRefresh: Boolean get() = truncated || !complete
}

internal data class CatalogFeedCacheLimits(
    val maxSnapshotBytes: Int = 2 * 1024 * 1024,
    val maxTotalBytes: Long = 8L * 1024 * 1024,
    val maxEntries: Int = 8,
    val maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
    val maxShelves: Int = 32,
    val maxTracksPerShelf: Int = 100,
    val maxTracks: Int = 1000,
) {
    init {
        require(maxSnapshotBytes > 0 && maxTotalBytes >= maxSnapshotBytes && maxEntries > 0)
        require(maxAgeMillis > 0 && maxShelves > 0 && maxTracksPerShelf > 0 && maxTracks > 0)
    }
}

/**
 * Account/locale-scoped display metadata, never audio, lyrics, headers or credentials.
 * All file access, serialization and parsing are off-main. No retained bitmap or Track map.
 */
internal class CatalogFeedCache(
    private val directory: File,
    private val settings: () -> ContentCacheSettings,
    private val generation: () -> Long,
    private val canWrite: (Long) -> Boolean,
    private val mutations: Mutex = Mutex(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: CatalogFeedCacheLimits = CatalogFeedCacheLimits(),
) {
    constructor(cache: ContentCache) : this(cache.metadataDirectory, { cache.settings.value },
        { cache.generation }, cache::canWrite, cache.metadataMutations)

    suspend fun read(scopeKey: String, localeTag: String, kind: CatalogFeedKind): CachedCatalogFeed? = withContext(io) {
        mutations.withLock {
            if (!readAllowed() || scopeKey.isBlank() || localeTag.isBlank()) return@withLock null
            val epoch = generation()
            val digest = key(scopeKey, localeTag, kind)
            val file = File(directory, "$digest.feed")
            if (!file.isFile) return@withLock null
            if (file.length() !in 1L..limits.maxSnapshotBytes.toLong()) { file.delete(); return@withLock null }
            val cached = try { decode(file.readText(Charsets.UTF_8), digest, kind) }
            catch (_: Exception) { null }
            if (cached == null || expired(cached.savedAtEpochMs)) {
                file.delete()
                return@withLock null
            }
            currentCoroutineContext().ensureActive()
            if (!readAllowed() || epoch != generation()) return@withLock null
            // lastModified is LRU access time; the original freshness time stays in JSON.
            touch(file)
            cached
        }
    }

    suspend fun write(
        scopeKey: String,
        localeTag: String,
        kind: CatalogFeedKind,
        shelves: List<ContentShelf>,
        requestGeneration: Long,
        homeSnapshot: HomeFeedSnapshot? = null,
        savedAtEpochMs: Long = nowEpochMs(),
    ): Boolean = withContext(io) {
        mutations.withLock {
            if (scopeKey.isBlank() || localeTag.isBlank() || savedAtEpochMs <= 0L || !writeAllowed(requestGeneration)) return@withLock false
            val digest = key(scopeKey, localeTag, kind)
            val bytes = try { encode(digest, kind, shelves, homeSnapshot, savedAtEpochMs).toByteArray(Charsets.UTF_8) }
            catch (_: Exception) { return@withLock false }
            if (bytes.size !in 1..limits.maxSnapshotBytes || !canWrite(bytes.size.toLong()) || !writeAllowed(requestGeneration)) return@withLock false
            currentCoroutineContext().ensureActive()
            if (!directory.isDirectory && !directory.mkdirs()) return@withLock false
            removePendingFiles()
            val file = File(directory, "$digest.feed")
            val pending = File(directory, "$digest.pending")
            try {
                FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
                currentCoroutineContext().ensureActive()
                if (!writeAllowed(requestGeneration) || !pending.renameTo(file)) return@withLock false
                if (!writeAllowed(requestGeneration)) { file.delete(); return@withLock false }
                touch(file)
                prune()
                file.isFile
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { pending.delete() }
        }
    }

    private fun readAllowed() = settings().let { it.enabled && !it.clearing }
    private fun writeAllowed(epoch: Long) = readAllowed() && epoch == generation()
    private fun expired(writtenAt: Long): Boolean = writtenAt <= 0L ||
        (nowEpochMs() >= writtenAt && nowEpochMs() - writtenAt > limits.maxAgeMillis)
    private fun key(scope: String, locale: String, kind: CatalogFeedKind): String = MessageDigest.getInstance("SHA-256")
        // Earlier snapshots could contain configured-region fallback data for
        // an authenticated account. Refresh those instead of presenting them.
        .digest("v2\u0000$scope\u0000$locale\u0000${kind.name}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun ownedFiles(): List<File> = directory.listFiles()?.filter {
        it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.(feed|pending)"))
    }.orEmpty()
    private fun removePendingFiles() { ownedFiles().filter { it.extension == "pending" }.forEach(File::delete) }
    private fun touch(file: File) {
        // Keep LRU ordering even if the wall clock was adjusted backward.
        val newest = ownedFiles().maxOfOrNull { it.lastModified() } ?: 0L
        val logicalTime = if (newest < Long.MAX_VALUE) maxOf(nowEpochMs(), newest + 1L) else nowEpochMs()
        file.setLastModified(logicalTime)
    }
    private fun prune() {
        val files = ownedFiles().filter { it.extension == "feed" }.sortedByDescending { it.lastModified() }
        var total = 0L
        var count = 0
        files.forEach { file ->
            val length = file.length()
            if (length > limits.maxSnapshotBytes || count >= limits.maxEntries || total + length > limits.maxTotalBytes) file.delete()
            else { total += length; count++ }
        }
    }

    private fun encode(digest: String, kind: CatalogFeedKind, shelves: List<ContentShelf>, home: HomeFeedSnapshot?, writtenAt: Long): String {
        var truncated = false
        val tracks = linkedMapOf<Track, Int>()
        fun reference(track: Track): Int? {
            val safe = snapshotTrack(track) ?: run { truncated = true; return null }
            tracks[safe]?.let { return it }
            if (tracks.size >= limits.maxTracks) { truncated = true; return null }
            return tracks.size.also { tracks[safe] = it }
        }
        fun serializeShelves(items: List<ContentShelf>): JSONArray {
            require(items.map { it.id }.distinct().size == items.size)
            if (items.size > limits.maxShelves) truncated = true
            return JSONArray().apply {
                items.take(limits.maxShelves).forEach { shelf ->
                    if (shelf.id.isBlank() || shelf.id.length > 512) { truncated = true; return@forEach }
                    if (shelf.items.size > limits.maxTracksPerShelf) truncated = true
                    val refs = JSONArray()
                    shelf.items.take(limits.maxTracksPerShelf).forEach { reference(it)?.let(refs::put) }
                    put(JSONObject().put("id", shelf.id).put("title", shelf.title.take(512))
                        .put("featured", shelf.featured).put("hasMore", shelf.hasMore).put("items", refs).apply {
                            shelf.seeAll?.let { target -> reference(target)?.let { put("seeAll", it) } }
                        })
                }
            }
        }
        val root = JSONObject().put("version", 1).put("digest", digest).put("kind", kind.name)
            .put("writtenAt", writtenAt).put("shelves", serializeShelves(shelves))
        if (kind == CatalogFeedKind.Home && home != null) {
            if (home.slots.size > 16) truncated = true
            root.put("home", JSONArray().apply {
                home.slots.take(16).forEach { slot ->
                    val result = slot.result
                    require(slot.sourceId.isNotBlank() && slot.sourceId.length <= 512)
                    val json = JSONObject().put("id", slot.sourceId)
                    if (result != null) {
                        json.put("shelves", serializeShelves(result.shelves))
                        result.recentTracks?.let { recent ->
                            if (recent.size > limits.maxTracksPerShelf) truncated = true
                            json.put("recent", JSONArray().apply {
                                recent.take(limits.maxTracksPerShelf).forEach { reference(it)?.let(::put) }
                            })
                        }
                    }
                    put(json)
                }
            })
        }
        return root.put("tracks", JSONArray(tracks.keys.map { it.toSessionJson().put("description", it.description).put("url", it.url) }))
            .put("truncated", truncated).put("complete", home == null || home.slots.all { it.status == HomeFeedSourceStatus.Ready }).toString()
    }

    private fun decode(raw: String, digest: String, kind: CatalogFeedKind): CachedCatalogFeed? {
        val root = JSONObject(raw)
        if (root.optInt("version") != 1 || root.optString("digest") != digest || root.optString("kind") != kind.name) return null
        val dictionary = root.getJSONArray("tracks")
        require(dictionary.length() <= limits.maxTracks)
        val tracks = (0 until dictionary.length()).map { index ->
            val json = dictionary.getJSONObject(index)
            snapshotTrack(json.toSessionTrack().copy(durationMs = json.optLong("durationMs").coerceAtLeast(0L), description = json.optString("description").takeUnless { it.isBlank() || it == "null" },
                url = json.optString("url").takeUnless { it.isBlank() || it == "null" })) ?: error("Invalid cached resource")
        }
        var expansionKnown = true
        fun parseShelves(items: JSONArray): List<ContentShelf> {
            require(items.length() <= limits.maxShelves)
            return (0 until items.length()).map { index ->
                val shelf = items.getJSONObject(index)
                if (kind == CatalogFeedKind.Home && !shelf.has("hasMore")) expansionKnown = false
                val refs = shelf.getJSONArray("items")
                require(refs.length() <= limits.maxTracksPerShelf)
                ContentShelf(shelf.getString("id").also { require(it.isNotBlank() && it.length <= 512) },
                    shelf.getString("title").take(512), (0 until refs.length()).map { tracks[refs.getInt(it)] }, shelf.optBoolean("featured"),
                    shelf.opt("seeAll")?.let { tracks[(it as Number).toInt()] }, shelf.optBoolean("hasMore"))
            }.also { require(it.map { shelf -> shelf.id }.distinct().size == it.size) }
        }
        val home = root.optJSONArray("home")?.let { sources ->
            require(sources.length() <= 16)
            HomeFeedSnapshot((0 until sources.length()).map { index ->
                val source = sources.getJSONObject(index)
                val sourceId = source.getString("id").also { require(it.isNotBlank() && it.length <= 512) }
                val sourceShelves = source.optJSONArray("shelves")
                val recent = source.optJSONArray("recent")?.let { items ->
                    require(items.length() <= limits.maxTracksPerShelf)
                    (0 until items.length()).map { tracks[items.getInt(it)] }
                }
                HomeFeedSlot(sourceId, if (sourceShelves != null) HomeFeedSourceStatus.Ready else HomeFeedSourceStatus.Failed,
                    result = sourceShelves?.let { HomeFeedResult(parseShelves(it), recent) })
            }.also { require(it.map { slot -> slot.sourceId }.distinct().size == it.size) })
        }
        return CachedCatalogFeed(parseShelves(root.getJSONArray("shelves")), root.getLong("writtenAt"), home,
            root.optBoolean("truncated"), root.optBoolean("complete", true) && expansionKnown)
    }

    private fun snapshotTrack(track: Track): Track? {
        if (track.id.isBlank() || track.id.length > 512 || (track.resourceId?.length ?: 0) > 512 || (track.catalogId?.length ?: 0) > 512) return null
        fun safeUrl(url: String?): String? = url?.takeIf {
            it.length <= 4096 && it.startsWith("https://") &&
                !Regex("(?i)[?&](token|access_token|music_user_token|developer_token|authorization|cookie)=").containsMatchIn(it)
        }
        return track.copy(title = track.title.take(512), artist = track.artist.take(512), album = track.album.take(512),
            palette = track.palette.take(5), lyrics = emptyList(), playbackQueueId = null,
            artworkUrl = safeUrl(track.artworkUrl), motionArtworkUrl = safeUrl(track.motionArtworkUrl),
            motionPreviewUrl = safeUrl(track.motionPreviewUrl), motionArtworkVariants = track.motionArtworkVariants.take(4).mapNotNull { asset ->
                safeUrl(asset.videoUrl)?.let { asset.copy(videoUrl = it, previewUrl = safeUrl(asset.previewUrl)) }
            }, description = track.description?.take(2048), url = safeUrl(track.url))
    }
}
