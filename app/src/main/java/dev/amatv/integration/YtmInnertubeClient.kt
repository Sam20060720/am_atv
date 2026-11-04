package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** WEB_REMIX is an undocumented interface. Keep its renderer parsing isolated. */
data class YtmSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
)

data class YtmShelf(
    val title: String,
    val songs: List<YtmSong>,
    val playlistId: String? = null,
)
data class YtmRadioPage(val songs: List<YtmSong>, val continuation: String?)

class YtmInnertubeClient(
    private val country: suspend () -> String,
    private val accessToken: suspend () -> String? = { null },
) {
    @Volatile private var visitorData: String? = null
    private val io = Dispatchers.IO.limitedParallelism(3)
    private val parse = Dispatchers.Default.limitedParallelism(2)
    private val clientVersion = "1." + LocalDate.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.BASIC_ISO_DATE) + ".01.00"

    suspend fun searchSong(query: String): YtmSong? {
        if (query.isBlank()) return null
        val root = post("search", JSONObject()
            .put("query", query.trim())
            .put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF"))
        return withContext(parse) { collectSongs(root).firstOrNull() }
    }

    suspend fun searchSong(title: String, artist: String, durationMs: Long): YtmSong? {
        if (title.isBlank() || artist.isBlank()) return null
        val root = post("search", JSONObject()
            .put("query", "$title $artist".take(150))
            .put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF"))
        return withContext(parse) { selectSeedSong(title, artist, durationMs, collectSongs(root)) }
    }

    /** Do not start an unrelated radio just because it was the first search hit. */
    internal fun selectSeedSong(
        title: String,
        artist: String,
        durationMs: Long,
        candidates: List<YtmSong>,
    ): YtmSong? = candidates.take(12).filter { song ->
        SongIdentity.normalizeTitle(song.title) == SongIdentity.normalizeTitle(title) &&
            SongIdentity.normalizePrimaryArtist(song.artist) == SongIdentity.normalizePrimaryArtist(artist) &&
            SongIdentity.isSameEdition(song.title, title) &&
            (durationMs <= 0 || song.durationMs == null || abs(song.durationMs - durationMs) <= 10_000L)
    }.minByOrNull { song ->
        if (durationMs > 0) song.durationMs?.let { abs(it - durationMs) } ?: Long.MAX_VALUE else 0L
    }

    suspend fun radio(videoId: String, continuation: String? = null): YtmRadioPage {
        require(VIDEO_ID.matches(videoId)) { AppText.text(R.string.services_ytm_video_invalid) }
        val body = JSONObject().put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
        if (continuation != null) body.put("continuation", continuation)
        val root = post("next", body)
        return withContext(parse) { parseRadioResponse(root, videoId) }
    }

    /** Related tabs and their cursors are not part of the up-next radio queue. */
    internal fun parseRadioResponse(root: JSONObject, seedVideoId: String): YtmRadioPage {
        val continued = root.optJSONObject("continuationContents")?.optJSONObject("playlistPanelContinuation")
        var panel: JSONObject? = continued
        if (panel == null) walk(root) { node ->
            if (panel == null) panel = node.optJSONObject("playlistPanelRenderer")
        }
        if (panel == null) {
            val actions = root.optJSONArray("onResponseReceivedActions")
            for (index in 0 until (actions?.length() ?: 0)) {
                val contents = actions?.optJSONObject(index)?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems") ?: continue
                if ((0 until contents.length()).any { contents.optJSONObject(it)?.has("playlistPanelVideoRenderer") == true }) {
                    panel = JSONObject().put("contents", contents)
                    break
                }
            }
        }
        val queue = panel ?: return YtmRadioPage(emptyList(), null)
        return YtmRadioPage(
            collectSongs(queue).filterNot { it.videoId == seedVideoId }, findContinuation(queue),
        )
    }

    suspend fun home(): List<YtmShelf> {
        val root = post("browse", JSONObject().put("browseId", "FEmusic_home"))
        return withContext(parse) {
            val shelves = ArrayList<YtmShelf>()
            walk(root) { node ->
                for (rendererName in listOf(
                    "musicCarouselShelfRenderer", "musicShelfRenderer", "musicCardShelfRenderer",
                )) {
                    val shelf = node.optJSONObject(rendererName) ?: continue
                    val title = text(shelf.optJSONObject("header")?.optJSONObject(
                        "musicCarouselShelfBasicHeaderRenderer",
                    )?.optJSONObject("title"))
                        .ifBlank { text(shelf.optJSONObject("title")) }
                    val songs = collectSongs(shelf)
                    if (title.isNotBlank() && songs.isNotEmpty()) {
                        shelves += YtmShelf(title, songs.take(20))
                    }
                }
                val playlistCard = node.optJSONObject("musicTwoRowItemRenderer")
                if (playlistCard != null) {
                    val title = text(playlistCard.optJSONObject("title"))
                    if (title.contains("supermix", ignoreCase = true) ||
                        title.contains("listen again", ignoreCase = true)
                    ) {
                        val endpoint = playlistCard.optJSONObject("navigationEndpoint")
                        val direct = endpoint?.optJSONObject("watchPlaylistEndpoint")
                            ?.optString("playlistId")
                            ?.takeIf(String::isNotBlank)
                        val browse = endpoint?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")
                            ?.removePrefix("VL")
                            ?.takeIf(String::isNotBlank)
                        val playlistId = direct ?: browse
                        if (playlistId != null) shelves += YtmShelf(title, emptyList(), playlistId)
                    }
                }
            }
            shelves.distinctBy { it.title }.take(30)
        }
    }

    /** A playlist result from Home can be expanded with its watch endpoint. */
    suspend fun playlist(playlistId: String): List<YtmSong> {
        require(playlistId.length in 8..120 && playlistId.all { it.isLetterOrDigit() || it in "_-" }) {
            AppText.text(R.string.services_ytm_playlist_invalid)
        }
        return withContext(parse) {
            collectSongs(post("next", JSONObject().put("playlistId", playlistId)))
        }
    }

    internal suspend fun requestContext(): JSONObject {
        val region = country().trim().uppercase(Locale.ROOT)
        require(region.matches(Regex("[A-Z]{2}")))
        return JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", clientVersion)
                .put("hl", "en")
                .put("gl", region))
    }

    private suspend fun post(endpoint: String, payload: JSONObject): JSONObject {
        val token = accessToken()
        val body = JSONObject().put("context", requestContext())
        payload.keys().forEachRemaining { key -> body.put(key, payload.get(key)) }
        val raw = withContext(io) {
            coroutineContext.ensureActive()
            val conn = URI("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                .toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 6_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("X-YouTube-Client-Name", "67")
                conn.setRequestProperty("X-YouTube-Client-Version", clientVersion)
                conn.setRequestProperty("Cookie", "SOCS=CAI")
                visitorData?.let { conn.setRequestProperty("X-Goog-Visitor-Id", it) }
                token?.let {
                    conn.setRequestProperty("Authorization", "Bearer $it")
                    conn.setRequestProperty("X-Goog-Request-Time", (System.currentTimeMillis() / 1000).toString())
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                check(status in 200..299) { AppText.text(R.string.services_ytm_http_error, status) }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        return withContext(parse) {
            JSONObject(raw).also { root ->
                root.optJSONObject("responseContext")?.optString("visitorData")
                    ?.takeIf(String::isNotBlank)?.let { visitorData = it }
            }
        }
    }

    private fun collectSongs(root: JSONObject): List<YtmSong> {
        val songs = LinkedHashMap<String, YtmSong>()
        walk(root) { node ->
            val panel = node.optJSONObject("playlistPanelVideoRenderer")
            val row = node.optJSONObject("musicResponsiveListItemRenderer")
            val twoRow = node.optJSONObject("musicTwoRowItemRenderer")
            val source = panel ?: row ?: twoRow ?: return@walk
            val id = source.optString("videoId").ifBlank {
                findVideoId(source) ?: ""
            }
            if (!VIDEO_ID.matches(id)) return@walk
            val title = text(source.optJSONObject("title")).ifBlank {
                text(source.optJSONArray("flexColumns")?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text"))
            }
            val byline = text(source.optJSONObject("longBylineText")).ifBlank {
                text(source.optJSONArray("flexColumns")?.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text"))
            }
            val durationText = text(source.optJSONObject("lengthText"))
                .ifBlank { byline.split('•').lastOrNull().orEmpty().trim() }
            val duration = parseDuration(durationText)
            val artist = byline.split('•').firstOrNull().orEmpty().trim()
            if (title.isNotBlank() && artist.isNotBlank()) {
                songs.putIfAbsent(id, YtmSong(id, title, artist, duration))
            }
        }
        return songs.values.toList()
    }

    private fun findVideoId(root: JSONObject): String? {
        var result: String? = null
        walk(root, 12) { node ->
            if (result == null) result = node.optJSONObject("watchEndpoint")
                ?.optString("videoId")?.takeIf(VIDEO_ID::matches)
        }
        return result
    }

    private fun findContinuation(root: JSONObject): String? {
        var result: String? = null
        walk(root) { node ->
            if (result == null) result = node.optJSONObject("nextContinuationData")
                ?.optString("continuation")?.takeIf(String::isNotBlank)
            if (result == null) result = node.optJSONObject("nextRadioContinuationData")
                ?.optString("continuation")?.takeIf(String::isNotBlank)
            if (result == null) result = node.optJSONObject("continuationItemRenderer")
                ?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                ?.optString("token")?.takeIf(String::isNotBlank)
        }
        return result
    }

    private fun walk(root: Any?, maxDepth: Int = 48, visit: (JSONObject) -> Unit) {
        var budget = 12_000
        fun traverse(value: Any?, depth: Int) {
            if (depth > maxDepth || --budget <= 0) return
            when (value) {
                is JSONObject -> {
                    visit(value)
                    value.keys().forEachRemaining { key -> traverse(value.opt(key), depth + 1) }
                }
                is JSONArray -> for (i in 0 until value.length()) traverse(value.opt(i), depth + 1)
            }
        }
        traverse(root, 0)
    }

    private fun text(node: JSONObject?): String = node?.optJSONArray("runs")?.let { runs ->
        buildString {
            for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
    } ?: node?.optString("simpleText").orEmpty()

    private fun parseDuration(value: String): Long? {
        val pieces = value.trim().split(':')
        if (pieces.size !in 2..3 || pieces.any { it.toIntOrNull() == null }) return null
        return pieces.fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1_000L
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
