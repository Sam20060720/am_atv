package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.EditorialMotionAsset
import dev.amatv.model.Track
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * Optional, anonymous artwork metadata, not a playback or authentication route.
 * Only an explicitly visited resource is fetched; no per-shelf card requests.
 */
internal class PublicEditorialMotionClient(
    private val storefront: suspend () -> String,
    private val language: () -> String,
    private val fetch: suspend (String, String) -> String = ::fetchPublicEditorialMotion,
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheDirectory: File? = null,
    private val cacheGeneration: () -> Long = { 0L },
    private val cacheEnabled: () -> Boolean = { true },
    private val canWriteCache: () -> Boolean = { true },
) {
    private data class CachedAssets(val assets: List<EditorialMotionAsset>, val expiresAt: Long)

    // The disk deadline and now() are both wall-clock milliseconds. A disk hit
    // keeps only its remaining lifetime when it enters the memory cache.
    private val cache = EditorialLookupCache<CachedAssets>(now) { value ->
        if (!cacheEnabled()) 0L else (value.expiresAt - now()).coerceAtLeast(0L)
    }

    /** Memory only: a remounted idle card must not trigger a public-page read. */
    fun cachedArtwork(item: Track, country: String): Track {
        if (item.motionArtworkVariants.isNotEmpty() || item.motionArtworkUrl != null) return item
        if (!cacheEnabled()) return item
        val (type, id) = resource(item) ?: return item
        val normalizedCountry = country.lowercase(Locale.ROOT)
        if (!normalizedCountry.matches(Regex("[a-z]{2}"))) return item
        val key = "${cacheGeneration()}:$normalizedCountry:${language()}:$type:$id"
        return item.withMotionAssets(cache.peek(key)?.assets)
    }

    /** Preserves source/library/queue identity and all non-artwork metadata. */
    suspend fun artwork(item: Track): Track {
        if (item.motionArtworkVariants.isNotEmpty() || item.motionArtworkUrl != null) return item
        val (type, id) = resource(item) ?: return item
        val country = storefront().lowercase(Locale.ROOT)
        if (!country.matches(Regex("[a-z]{2}"))) return item
        val tag = language()
        val generation = cacheGeneration()
        val key = "$generation:$country:$tag:$type:$id"
        val diskKey = "$country:$tag:$type:$id"
        val cached = try {
            val load: suspend () -> CachedAssets = {
                readCachedAssets(diskKey) ?: run {
                    val html = fetch("https://music.apple.com/$country/$type/$id", tag)
                    val result = withContext(Dispatchers.Default) { PublicEditorialMotionPage.parse(html, country, type, id) }
                    val value = CachedAssets(result, now() + if (result.isEmpty()) STATIC_LIFETIME else MOTION_LIFETIME)
                    writeCachedAssets(diskKey, value, generation)
                    value
                }
            }
            cache.get(key, load)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
        currentCoroutineContext().ensureActive()
        return item.withMotionAssets(cached?.assets)
    }

    private fun resource(item: Track): Pair<String, String>? {
        val type = when (item.catalogKind) {
            CatalogKind.Album -> "album"
            CatalogKind.Artist -> "artist"
            CatalogKind.Playlist -> "playlist"
            else -> return null
        }
        // A library r./l./p. identifier is never guessed into a public catalog ID.
        val id = item.catalogId ?: item.resourceId?.takeUnless { item.resourceType?.startsWith("library-") == true }
            ?: return null
        return if (publicMotionId(type, id)) type to id else null
    }

    private fun Track.withMotionAssets(assets: List<EditorialMotionAsset>?): Track {
        val first = assets?.firstOrNull() ?: return this
        return copy(motionArtworkUrl = first.videoUrl, motionPreviewUrl = first.previewUrl, motionArtworkVariants = assets)
    }

    private fun fileFor(key: String): File? = cacheDirectory?.let { directory ->
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(directory, "$hash.json")
    }

    private fun readCachedAssets(key: String): CachedAssets? = synchronized(diskLock) { try {
        if (!cacheEnabled()) return@synchronized null
        val file = fileFor(key)
        if (file == null || !file.isFile || file.length() > 64 * 1024) null else {
            val root = JSONObject(file.readText())
            val expiresAt = root.optLong("expires")
            if (expiresAt <= now()) { file.delete(); null } else {
                val values = root.getJSONArray("assets")
                require(values.length() <= 16)
                val result = (0 until values.length()).map { index ->
                    val item = values.getJSONObject(index)
                    val url = item.getString("video")
                    require(URI(url).scheme == "https")
                    EditorialMotionAsset(item.getString("flavor"), url,
                        item.optString("preview").takeIf { it.startsWith("https://") },
                        item.optInt("width").takeIf { it > 0 }, item.optInt("height").takeIf { it > 0 })
                }
                file.setLastModified(now())
                CachedAssets(result, expiresAt)
            }
        }
    } catch (_: Exception) { null } }

    private fun writeCachedAssets(key: String, value: CachedAssets, generation: Long) {
        synchronized(diskLock) { try {
            if (generation != cacheGeneration() || !cacheEnabled() || !canWriteCache()) return
            val file = fileFor(key) ?: return
            if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) return
            val values = JSONArray()
            value.assets.forEach { asset -> values.put(JSONObject().put("flavor", asset.flavor).put("video", asset.videoUrl)
                .put("preview", asset.previewUrl).put("width", asset.width).put("height", asset.height)) }
            val root = JSONObject().put("expires", value.expiresAt)
                .put("assets", values)
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(root.toString())
            if (generation != cacheGeneration() || !cacheEnabled()) { temporary.delete(); return }
            if (!temporary.renameTo(file)) temporary.delete()
            file.parentFile!!.listFiles { child -> child.extension == "json" }
                ?.sortedByDescending { it.lastModified() }?.drop(48)?.forEach { it.delete() }
        } catch (_: Exception) { /* Optional cache failure never affects artwork. */ } }
    }

    companion object {
        private val diskLock = Any()
        fun clearDiskCache(directory: File) = synchronized(diskLock) {
            directory.listFiles()?.forEach { it.delete() }; Unit
        }
    }

}

/** Parse only the requested page's own header, never recommendations or metrics. */
internal object PublicEditorialMotionPage {
    private val scriptOpen = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE).toPattern()
    private val attribute = Regex("([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").toPattern()

    fun parse(html: String, country: String, type: String, id: String): List<EditorialMotionAsset> {
        require(country.matches(Regex("[a-z]{2}")) && publicMotionId(type, id))
        require(html.length <= MAX_MOTION_HTML_BYTES && html.toByteArray(Charsets.UTF_8).size <= MAX_MOTION_HTML_BYTES)
        // Retain one local ICU matcher instead of rebuilding a whole-HTML
        // matcher for every script tag through Kotlin's lazy MatchResult.next.
        val payloads = buildList {
            val opening = scriptOpen.matcher(html)
            while (opening.find()) {
                val attrs = buildMap {
                    val match = attribute.matcher(opening.group())
                    while (match.find()) put(match.group(1).lowercase(Locale.ROOT), match.group(2) ?: checkNotNull(match.group(3)))
                }
                if (attrs["id"] != "serialized-server-data" || !attrs["type"].equals("application/json", true)) continue
                val start = opening.end()
                val end = html.indexOf("</script", start, ignoreCase = true)
                require(end >= start && end - start <= MAX_MOTION_JSON_BYTES)
                add(html.substring(start, end).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_MOTION_JSON_BYTES) })
            }
        }
        require(payloads.size == 1)
        val pages = JSONObject(payloads.single()).optJSONArray("data") ?: return emptyList()
        require(pages.length() <= 32)
        val expectedIntent = when (type) {
            "album" -> "AlbumDetailPageIntent"
            "artist" -> "ArtistDetailPageIntent"
            "playlist" -> "PlaylistDetailPageIntent"
            else -> return emptyList()
        }
        val matching = (0 until pages.length()).mapNotNull(pages::optJSONObject).filter { page ->
            val intent = page.optJSONObject("intent")
            val descriptor = intent?.optJSONObject("contentDescriptor")
            intent?.optString("\$kind") == expectedIntent && matches(descriptor, type, id) &&
                descriptor?.optJSONObject("locale")?.optString("storefront") == country
        }
        if (matching.size != 1) return emptyList()
        val sections = matching.single().optJSONObject("data")?.optJSONArray("sections") ?: return emptyList()
        require(sections.length() <= 128)
        return (0 until sections.length()).mapNotNull(sections::optJSONObject)
            .filter { it.optString("itemKind") in setOf("containerDetailHeaderLockup", "artistDetailHeader", "artistDetailHeaderLockup") }
            .flatMap { section ->
                val items = section.optJSONArray("items") ?: return@flatMap emptyList()
                require(items.length() <= 16)
                (0 until items.length()).mapNotNull(items::optJSONObject)
                    .filter { matches(it.optJSONObject("contentDescriptor"), type, id) }
                    .flatMap { it.editorialMotionVariants(artist = type == "artist") }
            }.distinctBy { it.videoUrl }
    }

    private fun matches(descriptor: JSONObject?, type: String, id: String) =
        descriptor?.optString("kind") == type && descriptor?.optJSONObject("identifiers")?.optString("storeAdamID") == id
}

private const val MOTION_LIFETIME = 6 * 60 * 60_000L
private const val STATIC_LIFETIME = 15 * 60_000L
private const val MAX_MOTION_HTML_BYTES = 4 * 1024 * 1024
private const val MAX_MOTION_JSON_BYTES = 2 * 1024 * 1024

private fun publicMotionId(type: String, id: String): Boolean = when (type) {
    "album", "artist" -> id.matches(Regex("[1-9][0-9]{0,19}"))
    "playlist" -> id.matches(Regex("pl\\.[a-zA-Z0-9._-]{1,128}"))
    else -> false
}

private suspend fun fetchPublicEditorialMotion(address: String, language: String): String = withContext(Dispatchers.IO) {
    var uri = URI(address)
    val country = uri.path.split('/').getOrNull(1) ?: throw IOException()
    repeat(4) {
        currentCoroutineContext().ensureActive()
        require(uri.scheme == "https" && uri.host == "music.apple.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.fragment == null && uri.path.startsWith("/$country/"))
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("Accept", "text/html")
        connection.setRequestProperty("Accept-Language", language)
        try {
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                uri = uri.resolve(connection.getHeaderField("Location") ?: throw IOException())
            } else {
                if (status != 200 || connection.contentLengthLong > MAX_MOTION_HTML_BYTES) throw IOException()
                return@withContext connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(64 * 1024)
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_MOTION_HTML_BYTES) throw IOException()
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            }
        } finally { connection.disconnect() }
    }
    throw IOException()
}
