package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.EditorialMotionAsset
import dev.amatv.model.Track
import dev.amatv.playback.CollectionPlaybackCheckpoint
import dev.amatv.playback.ContextKind
import dev.amatv.playback.PlaybackContext
import org.json.JSONArray
import org.json.JSONObject

/** Metadata dictionary + entry records; lyrics and native queue handles never reach disk. */
internal object PlaybackSessionCodec {
    private const val CHECKPOINT_BEFORE = 8
    private const val CHECKPOINT_AFTER = 2

    private data class Record(val id: Long, val sourceId: Long, val track: Track, val autoplay: Boolean, val played: Boolean)

    fun encode(session: PlaybackSession): String = root(session).toString()

    fun encodeCheckpoint(session: PlaybackSession): String {
        val current = session.currentIndex.coerceIn(session.queue.indices)
        val start = (current - CHECKPOINT_BEFORE).coerceAtLeast(0)
        val end = (current + CHECKPOINT_AFTER + 1).coerceAtMost(session.queue.size)
        val ids = ids(session)
        val sources = sourceIds(session, ids)
        val compact = session.copy(
            queue = session.queue.subList(start, end), currentIndex = current - start,
            queueEntryIds = ids.subList(start, end), contextQueue = emptyList(), contextEntryIds = emptyList(),
            queueSourceEntryIds = sources.subList(start, end),
            autoplayEntryIndices = (start until end).filter { it in session.autoplayEntryIndices }.map { it - start }.toSet(),
        )
        return root(compact).put("windowStart", start)
            .put("contextSize", session.contextEntryIds.size)
            .put("contextFingerprint", contextFingerprint(session.contextEntryIds))
            .put("futureIds", JSONArray(ids.drop(current + 1))).toString()
    }

    fun decode(raw: String): PlaybackSession? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("version") == 1) return@runCatching decodeLegacy(root)
        require(root.getInt("version") == 2)
        val dictionary = root.getJSONArray("tracks")
        val tracks = (0 until dictionary.length()).map { dictionary.getJSONObject(it).toSessionTrack() }
        fun records(name: String): List<Record> {
            val data = root.optJSONArray(name) ?: return emptyList()
            return (0 until data.length()).map { index ->
                val item = data.getJSONObject(index)
                val id = item.getLong("id")
                require(id > 0 && id < Long.MAX_VALUE)
                val sourceId = item.optLong("s", id).takeIf { it in 1L until Long.MAX_VALUE } ?: id
                Record(id, sourceId, tracks[item.getInt("r")], item.optBoolean("a"), item.optBoolean("p"))
            }
        }
        val queue = records("queue")
        require(queue.isNotEmpty() && queue.map { it.id }.distinct().size == queue.size)
        val context = records("context")
        PlaybackSession(
            queue = queue.map { it.track }, currentIndex = root.optInt("currentIndex").coerceIn(queue.indices),
            positionMs = root.optLong("positionMs").coerceAtLeast(0), wasPlaying = root.optBoolean("wasPlaying"),
            destination = enumValueOrDefault(root.optString("destination"), Destination.Home),
            previousDestination = enumValueOrDefault(root.optString("previousDestination"), Destination.Home),
            radioActive = root.optBoolean("radioActive"), radioSeed = root.optJSONObject("radioSeed")?.toSessionTrack(),
            radioContinuation = root.optionalString("radioContinuation"),
            radioSeedVideoId = root.optionalString("radioSeedVideoId"),
            autoplaySource = autoplaySourceFromStoredValue(root.optString("autoplaySource")),
            autoplayEntryIndices = queue.indices.filter { queue[it].autoplay }.toSet(),
            queueEntryIds = queue.map { it.id }, playedEntryIds = queue.filter { it.played }.map { it.id }.toSet(),
            queueSourceEntryIds = queue.map { it.sourceId },
            deferredLastEntryIds = root.optJSONArray("deferredLastEntryIds")?.let { ids ->
                (0 until ids.length()).map { ids.optLong(it) }.filter { it in 1L until Long.MAX_VALUE }.toSet()
            }.orEmpty(),
            contextQueue = context.map { it.track }, contextEntryIds = context.map { it.id },
            unshuffledEntryIds = root.optJSONArray("unshuffledEntryIds")?.let { ids ->
                (0 until ids.length()).map { ids.getLong(it) }.also { order ->
                    require(order.all { it in 1L until Long.MAX_VALUE } && order.distinct().size == order.size)
                }
            }.orEmpty(),
            containerEntryIds = root.optJSONArray("containerEntryIds")?.let { ids ->
                (0 until ids.length()).map { ids.optLong(it) }.filter { it in 1L until Long.MAX_VALUE }.toSet()
            }.orEmpty(),
            playbackContext = root.optJSONObject("playbackContext")?.let { saved ->
                runCatching { ContextKind.valueOf(saved.getString("kind")) }.getOrNull()?.let { kind ->
                    PlaybackContext(kind, saved.optionalString("id"), saved.optionalString("title"), saved.optionalString("nextPageHref"))
                }
            },
            sessionId = root.optLong("sessionId"), persistenceId = root.optString("persistenceId").ifBlank { "legacy-v1" },
            savedRevision = root.optLong("savedRevision"),
            pendingCollection = root.optJSONObject("pendingCollection")?.let(::decodeCollectionCheckpoint),
        ).sanitizedAutoplayEntries()
    }.getOrNull()

    /** Overlay the synchronous cursor/tail without trimming the full session history. */
    fun merge(full: PlaybackSession?, checkpointRaw: String?): PlaybackSession? {
        val checkpoint = checkpointRaw?.let(::decode) ?: return full
        if (full != null && checkpoint.savedRevision < full.savedRevision) return full
        if (full == null || full.persistenceId != checkpoint.persistenceId) return checkpoint
        val cpRoot = JSONObject(checkpointRaw)
        // Context edits and the cursor must come from the same saved generation.
        // Older checkpoints omitted this fingerprint and retain their migration path.
        val mismatchedContext = cpRoot.optionalString("contextFingerprint")?.let {
            it != contextFingerprint(full.contextEntryIds)
        } == true
        if (mismatchedContext) return full
        // A bounded cursor window cannot supply a newly appended full page.
        // If process death beats the atomic timeline writer, recover the last
        // consistent full prefix and its cursor rather than skip missing songs.
        if (full.pendingCollection != null || checkpoint.pendingCollection != null) {
            val advancedCursor = checkpoint.pendingCollection != null && full.pendingCollection != checkpoint.pendingCollection
            val appendedContext = cpRoot.optInt("contextSize", -1) > full.contextEntryIds.size
            if (advancedCursor || appendedContext) return full
        }
        val fullIds = ids(full)
        val fullSources = sourceIds(full, fullIds)
        val records = full.queue.mapIndexed { index, track -> Record(fullIds[index], fullSources[index], track,
            index in full.autoplayEntryIndices, fullIds[index] in full.playedEntryIds) }.toMutableList()
        val cpIds = ids(checkpoint)
        val cpSources = sourceIds(checkpoint, cpIds)
        val start = cpRoot.optInt("windowStart").coerceAtLeast(0)
        checkpoint.queue.forEachIndexed { index, track ->
            val id = cpIds[index]
            val existing = records.indexOfFirst { it.id == id }
            val item = Record(id, cpSources[index], track, index in checkpoint.autoplayEntryIndices,
                id in checkpoint.playedEntryIds || (existing >= 0 && records[existing].played))
            if (existing >= 0) records[existing] = item
            else records.add((start + index).coerceAtMost(records.size), item)
        }
        val currentId = cpIds[checkpoint.currentIndex]
        val currentIndex = records.indexOfFirst { it.id == currentId }
        if (currentIndex < 0) return full
        val future = cpRoot.optJSONArray("futureIds")
        val ordered = if (future != null) {
            val byId = records.associateBy { it.id }
            val futureIds = (0 until future.length()).map { future.optLong(it) }
            // Unknown or duplicate occurrences cannot be reconstructed from a
            // bounded window. Preserve the last complete consistent session.
            if (futureIds.distinct().size != futureIds.size || currentId in futureIds ||
                futureIds.any { it !in byId }) return full
            val futureSet = futureIds.toSet()
            val prefix = records.take(currentIndex).filterNot { it.id in futureSet }
            prefix + byId.getValue(currentId) + futureIds.map(byId::getValue)
        } else records
        return checkpoint.copy(
            queue = ordered.map { it.track }, queueEntryIds = ordered.map { it.id },
            currentIndex = ordered.indexOfFirst { it.id == currentId },
            queueSourceEntryIds = ordered.map { it.sourceId },
            autoplayEntryIndices = ordered.indices.filter { ordered[it].autoplay }.toSet(),
            playedEntryIds = ordered.filter { it.played }.map { it.id }.toSet(),
            contextQueue = full.contextQueue, contextEntryIds = full.contextEntryIds,
        ).sanitizedAutoplayEntries()
    }

    private fun ids(session: PlaybackSession): List<Long> {
        val used = HashSet<Long>()
        var next = (session.queueEntryIds + session.contextEntryIds).filter { it in 1L until Long.MAX_VALUE }
            .maxOrNull()?.plus(1) ?: 1L
        return session.queue.indices.map { index ->
            session.queueEntryIds.getOrNull(index)?.takeIf { it in 1L until Long.MAX_VALUE && used.add(it) }
                ?: run { val generated = next++; used.add(generated); generated }
        }
    }

    private fun sourceIds(session: PlaybackSession, ids: List<Long>): List<Long> = ids.mapIndexed { index, id ->
        session.queueSourceEntryIds.getOrNull(index)?.takeIf { it in 1L until Long.MAX_VALUE } ?: id
    }

    private fun root(session: PlaybackSession): JSONObject {
        require(session.queue.isNotEmpty())
        val dictionary = LinkedHashMap<Track, Int>()
        fun resource(track: Track): Int {
            val metadata = if (track.lyrics.isEmpty() && track.playbackQueueId == null) track
                else track.copy(lyrics = emptyList(), playbackQueueId = null)
            return dictionary.getOrPut(metadata) { dictionary.size }
        }
        val queueIds = ids(session)
        val sourceIds = sourceIds(session, queueIds)
        val queue = JSONArray()
        session.queue.forEachIndexed { index, track ->
            queue.put(JSONObject().put("id", queueIds[index]).put("s", sourceIds[index]).put("r", resource(track))
                .put("a", index in session.autoplayEntryIndices).put("p", queueIds[index] in session.playedEntryIds))
        }
        var nextId = (queueIds + session.contextEntryIds).maxOrNull()?.plus(1) ?: 1L
        val context = JSONArray()
        session.contextQueue.forEachIndexed { index, track ->
            val id = session.contextEntryIds.getOrNull(index)?.takeIf { it > 0 } ?: nextId++
            context.put(JSONObject().put("id", id).put("r", resource(track)))
        }
        return JSONObject().put("version", 2).put("tracks", JSONArray(dictionary.keys.map { it.toSessionJson() }))
            .put("queue", queue).put("context", context).put("currentIndex", session.currentIndex.coerceIn(session.queue.indices))
            .put("positionMs", session.positionMs.coerceAtLeast(0)).put("wasPlaying", session.wasPlaying)
            .put("destination", session.destination.name).put("previousDestination", session.previousDestination.name)
            .put("radioActive", session.radioActive).put("radioSeed", session.radioSeed?.toSessionJson())
            .put("radioContinuation", session.radioContinuation).put("radioSeedVideoId", session.radioSeedVideoId)
            .put("autoplaySource", session.autoplaySource.name).put("sessionId", session.sessionId)
            .put("persistenceId", session.persistenceId.ifBlank { "legacy-v1" }).put("savedRevision", session.savedRevision)
            .put("contextSize", session.contextEntryIds.size)
            .put("contextFingerprint", contextFingerprint(session.contextEntryIds))
            .put("containerEntryIds", JSONArray(session.containerEntryIds.toList()))
            .put("deferredLastEntryIds", JSONArray(session.deferredLastEntryIds.toList()))
            .put("unshuffledEntryIds", JSONArray(session.unshuffledEntryIds))
            .put("playbackContext", session.playbackContext?.let { context ->
                JSONObject().put("kind", context.kind.name).put("id", context.id).put("title", context.title)
                    .put("nextPageHref", context.nextPageHref)
            })
            .put("pendingCollection", session.pendingCollection?.let { cursor ->
                JSONObject().put("collection", cursor.collection.toSessionJson()).put("next", cursor.nextPage)
                    .put("visited", JSONArray(cursor.visitedPages.toList())).put("total", cursor.total)
                    .put("authorizationScope", cursor.authorizationScope)
                    .put("shuffle", cursor.shuffle?.let { selection -> JSONObject().put("rawIndex", selection.rawIndex)
                        .put("anchorEntryId", selection.anchorEntryId).put("songKey", selection.songKey) })
            })
    }

    private fun decodeLegacy(root: JSONObject): PlaybackSession? {
        val data = root.getJSONArray("queue")
        val parsed = (0 until data.length()).mapNotNull { index ->
            runCatching { index to data.getJSONObject(index).toSessionTrack() }.getOrNull()
        }
        if (parsed.isEmpty()) return null
        val rawCurrent = root.optInt("currentIndex")
        val current = parsed.indexOfFirst { it.first >= rawCurrent }.takeIf { it >= 0 } ?: parsed.lastIndex
        val origins = root.optJSONArray("autoplayEntryIndices")
        val autoplay = (0 until (origins?.length() ?: 0)).map { origins!!.optInt(it, -1) }.toSet()
        return PlaybackSession(
            parsed.map { it.second }, current, if (parsed[current].first == rawCurrent) root.optLong("positionMs").coerceAtLeast(0) else 0,
            root.optBoolean("wasPlaying"), enumValueOrDefault(root.optString("destination"), Destination.Home),
            enumValueOrDefault(root.optString("previousDestination"), Destination.Home), root.optBoolean("radioActive"),
            root.optJSONObject("radioSeed")?.toSessionTrack(), root.optionalString("radioContinuation"),
            autoplaySourceFromStoredValue(root.optString("autoplaySource")),
            root.optionalString("radioSeedVideoId") ?: root.optJSONObject("radioSeed")?.optionalString("ytmVideoId"),
            parsed.indices.filter { parsed[it].first in autoplay }.toSet(),
            queueEntryIds = parsed.map { it.first + 1L },
            queueSourceEntryIds = parsed.map { it.first + 1L },
            playedEntryIds = parsed.take(current).map { it.first + 1L }.toSet(), persistenceId = "legacy-v1",
        ).sanitizedAutoplayEntries()
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback

private fun JSONObject.optionalString(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
internal fun Track.toSessionJson() = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("artist", artist)
    .put("album", album)
    .put("durationMs", durationMs)
    .put("palette", JSONArray().apply { palette.take(5).forEach { put(it.toArgb()) } })
    .put("catalogId", catalogId)
    .put("catalogKind", catalogKind.name)
    .put("resourceId", resourceId)
    .put("resourceType", resourceType)
    .put("contentRating", contentRating)
    .put("artworkUrl", artworkUrl)
    .put("motionArtworkUrl", motionArtworkUrl)
    .put("motionPreviewUrl", motionPreviewUrl)
    .put("motionArtworkVariants", JSONArray().apply { motionArtworkVariants.take(12).forEach { asset ->
        put(JSONObject().put("flavor", asset.flavor).put("videoUrl", asset.videoUrl)
            .put("previewUrl", asset.previewUrl).put("width", asset.width).put("height", asset.height))
    } })
    .put("hasLyricsAvailable", hasLyricsAvailable)
    .put("recommendedByYtm", recommendedByYtm)
    .put("ytmVideoId", ytmVideoId)

internal fun JSONObject.toSessionTrack(): Track {
    val colors = optJSONArray("palette")
    return Track(
        id = getString("id"),
        title = getString("title"),
        artist = getString("artist"),
        album = optString("album"),
        durationMs = optLong("durationMs").coerceAtLeast(1L),
        palette = if (colors == null || colors.length() == 0) {
            List(2) { Color(0xFF202428) }
        } else if (colors.length() == 1) {
            List(2) { Color(colors.getInt(0)) }
        } else (0 until colors.length()).map { Color(colors.getInt(it)) },
        lyrics = emptyList(),
        catalogId = optionalString("catalogId"),
        catalogKind = enumValueOrDefault(optString("catalogKind"), CatalogKind.Song),
        resourceId = optString("resourceId").takeIf { it.isNotBlank() && it != "null" },
        resourceType = optString("resourceType").takeIf { it.isNotBlank() && it != "null" },
        contentRating = optionalString("contentRating")?.takeIf { it == "explicit" || it == "clean" },
        artworkUrl = optionalString("artworkUrl"),
        motionArtworkUrl = optionalString("motionArtworkUrl"),
        motionPreviewUrl = optionalString("motionPreviewUrl"),
        motionArtworkVariants = optJSONArray("motionArtworkVariants")?.let { assets ->
            (0 until minOf(assets.length(), 12)).mapNotNull { index ->
                val asset = assets.optJSONObject(index) ?: return@mapNotNull null
                val video = asset.optionalString("videoUrl")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                EditorialMotionAsset(asset.optString("flavor"), video,
                    asset.optionalString("previewUrl")?.takeIf { it.startsWith("https://") },
                    asset.optInt("width", 0).takeIf { it > 0 }, asset.optInt("height", 0).takeIf { it > 0 })
            }
        }.orEmpty(),
        hasLyricsAvailable = optBoolean("hasLyricsAvailable"),
        // Queue indices belong to one MusicKit process and cannot survive a cold start.
        playbackQueueId = null,
        recommendedByYtm = optBoolean("recommendedByYtm"),
        ytmVideoId = optionalString("ytmVideoId"),
    )
}

private fun decodeCollectionCheckpoint(root: JSONObject): CollectionPlaybackCheckpoint? = runCatching {
    val visited = root.getJSONArray("visited")
    require(visited.length() <= 512)
    CollectionPlaybackCheckpoint(root.getJSONObject("collection").toSessionTrack(), root.getString("next"),
        (0 until visited.length()).map { visited.getString(it) }.toSet(),
        root.optInt("total", -1).takeIf { it >= 0 }, root.getString("authorizationScope"),
        root.optJSONObject("shuffle")?.let { selection -> dev.amatv.playback.CollectionShuffleCheckpoint(
            selection.getInt("rawIndex"), selection.getLong("anchorEntryId"), selection.getString("songKey")) })
}.getOrNull()

private fun contextFingerprint(ids: List<Long>): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(ids.joinToString(",").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
