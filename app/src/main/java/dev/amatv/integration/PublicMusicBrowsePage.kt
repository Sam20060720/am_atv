package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Anonymous server-rendered pages; never executes JavaScript/actions or sends account credentials. */
internal enum class PublicMusicPage(val path: String, val intentKind: String) {
    New("new", "BrowsePageIntent"),
    Radio("radio", "RadioPageIntent"),
    Room("room", "RoomPageIntent"),
    Curator("curator", "AnyCuratorPageDetailIntent"),
}

internal data class PublicMusicCollection(val item: Track, val shelves: List<ContentShelf>, val nextPage: String?)

object PublicMusicBrowsePage {
    const val MAX_HTML_BYTES = 4 * 1024 * 1024
    const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private val storefrontPattern = Regex("[a-z]{2}")
    private val scriptOpen = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE).toPattern()
    private val attribute = Regex("([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").toPattern()
    private val publicImage = Regex("https://[a-zA-Z0-9.-]+\\.mzstatic\\.com/image/[^\\s\"'<>\\\\,]{1,4096}").toPattern()
    private val squareSize = Regex("/(\\d{2,4})x(\\d{2,4})[a-zA-Z0-9._-]+(?:\\?[^#]*)?$")
    private val numericId = Regex("[1-9][0-9]{0,19}")
    private val playlistId = Regex("pl\\.[a-zA-Z0-9._-]{1,128}")
    private val stationId = Regex("ra\\.[a-zA-Z0-9._-]{1,128}")

    fun parse(html: String, storefront: String, capabilities: CatalogPresentationCapabilities): List<ContentShelf> =
        parsePage(html, storefront, capabilities, PublicMusicPage.New)

    fun parseRadio(html: String, storefront: String, capabilities: CatalogPresentationCapabilities): List<ContentShelf> =
        parsePage(html, storefront, capabilities, PublicMusicPage.Radio)

    private fun parsePage(html: String, storefront: String, capabilities: CatalogPresentationCapabilities,
        pageKind: PublicMusicPage): List<ContentShelf> {
        val country = storefront.lowercase(Locale.ROOT)
        val data = serverPage(html, country, pageKind).optJSONObject("data") ?: fail("Missing ${pageKind.intentKind} data")
        return shelves(html, country, capabilities, pageKind, data)
    }

    private fun serverPage(html: String, country: String, pageKind: PublicMusicPage, id: String? = null): JSONObject {
        checkPage(storefrontPattern.matches(country), "Invalid Browse storefront")
        checkPage(fits(html, MAX_HTML_BYTES), "Public Browse HTML exceeds 4 MiB")
        // Kotlin MatchResult.next creates a fresh matcher for the entire input.
        // On Android ICU that can repeatedly copy multi-MiB HTML into native
        // buffers. One call-local matcher advances in place and never escapes.
        val payloads = buildList {
            val opening = scriptOpen.matcher(html)
            while (opening.find()) {
                val attrs = buildMap {
                    // A single tag can itself approach the HTML size limit.
                    val match = attribute.matcher(opening.group())
                    while (match.find()) put(checkNotNull(match.group(1)).lowercase(Locale.ROOT), match.group(2) ?: checkNotNull(match.group(3)))
                }
                if (attrs["id"] != "serialized-server-data" || !attrs["type"].equals("application/json", true)) continue
                val start = opening.end()
                val end = html.indexOf("</script", start, ignoreCase = true)
                checkPage(end >= start, "Unclosed public Browse server data")
                add(html.substring(start, end).also { checkPage(fits(it, MAX_JSON_BYTES), "Public Browse JSON exceeds 2 MiB") })
            }
        }
        checkPage(payloads.size == 1, "Expected one public Browse server-data script")
        val root = try { JSONObject(payloads.single()) }
        catch (error: Exception) { throw PublicMusicBrowseParseException("Invalid public Browse JSON", error) }
        val pages = root.optJSONArray("data") ?: fail("Missing public Browse data array")
        val matches = pages.objects().filter { page ->
            page.optJSONObject("intent")?.let { intent ->
                intent.text("\$kind") == pageKind.intentKind && intent.text("storefront") == country &&
                    (id == null || intent.text("id") == id)
            } == true
        }
        checkPage(matches.size == 1, "Expected one ${pageKind.intentKind} for requested storefront")
        return matches.single()
    }

    private fun shelves(html: String, country: String, capabilities: CatalogPresentationCapabilities,
        pageKind: PublicMusicPage, data: JSONObject): List<ContentShelf> {
        val sections = data.optJSONArray("sections") ?: fail("Missing public Browse sections")
        checkPage(sections.length() in 1..128, "Invalid public Browse section count")
        val squareArt = renderedSquareArtwork(html)
        // collection() validates this intent's identity/cursor before returning.
        // An empty first batch must still retain the section that owns More.
        val keepEmptyRoomSection = pageKind == PublicMusicPage.Room && data.optJSONObject("nextIntent") != null
        var hasSupportedSection = false
        val shelves = sections.objects().mapNotNull { section ->
            val presentation = section.optJSONObject("presentation")
            val layout = presentation?.optJSONObject("layout")
            // The official Radio page renders free-live cards in a row grid.
            // Its station lockups have real square artwork and canonical IDs;
            // ordinary link grids and flowcase banners remain separate layouts.
            val liveRadio = pageKind == PublicMusicPage.Radio && section.text("itemKind") == "liveRadioGridLockup" &&
                layout?.text("kind") == "grid" && layout.text("flowStyle") == "row"
            val paginatedGrid = pageKind == PublicMusicPage.Room && presentation?.text("layout") == "grid"
            val roomLinks = section.text("itemKind") == "linkComponentModel" &&
                layout?.text("kind") == "grid" && layout.text("flowStyle") == "column"
            val standardShelf = (layout?.text("kind") == "shelf" || paginatedGrid) &&
                section.text("itemKind") in setOf("trackLockup", "squareLockup", "horizontalLockup", "verticalVideoLockup")
            if (presentation?.text("kind") != "collection" || (!liveRadio && !standardShelf && !roomLinks)) return@mapNotNull null
            val title = section.optJSONObject("header")?.optJSONObject("item")?.optJSONObject("titleLink")?.text("title")
                ?: return@mapNotNull null
            val sectionId = section.text("id") ?: fail("Missing public Browse shelf identity")
            val items = section.optJSONArray("items") ?: fail("Missing public Browse shelf items")
            checkPage(items.length() <= 512, "Public Browse shelf exceeds item limit")
            hasSupportedSection = true
            val tracks = items.objects().mapNotNull { item ->
                if (roomLinks) item.text("title")?.let { roomLink(item, country, it, List(2) { Color(0xFF24242A) }) }
                else parseItem(item, country, squareArt, liveRadio)
            }
                .filter(capabilities::canOpen).distinctBy { it.resourceType to it.resourceId }
            if (tracks.isEmpty() && !keepEmptyRoomSection) null else ContentShelf("public-${pageKind.path}:$country:$sectionId", title, tracks,
                featured = false, seeAll = roomHeader(section, country, title,
                    tracks.firstOrNull()?.palette ?: List(2) { Color(0xFF24242A) }))
        }
        checkPage(shelves.isNotEmpty() || pageKind == PublicMusicPage.Room && hasSupportedSection,
            "Public Browse has no supported standard shelves")
        return shelves
    }

    /** Only a matching server intent and its canonical link can create this destination. */
    private fun roomHeader(section: JSONObject, country: String, title: String, palette: List<Color>): Track? {
        val link = section.optJSONObject("header")?.optJSONObject("item")?.optJSONObject("titleLink") ?: return null
        return roomLink(link, country, title, palette)
    }

    private fun roomLink(link: JSONObject, country: String, title: String, palette: List<Color>): Track? {
        val segue = link.optJSONObject("segue")?.takeIf { it.text("\$kind") == "flowAction" } ?: return null
        val destination = segue.optJSONObject("destination")?.takeIf { it.text("kind") == "catalogPage" } ?: return null
        val intent = destination.optJSONObject("intent")?.takeIf {
            it.text("\$kind") == PublicMusicPage.Room.intentKind && it.text("storefront") == country
        } ?: return null
        val id = intent.text("id")?.takeIf(numericId::matches) ?: return null
        val descriptor = intent.optJSONObject("contentDescriptor")
        if (descriptor != null && (descriptor.text("kind") != "room" ||
                descriptor.optJSONObject("identifiers")?.opt("storeAdamID")?.toString() != id)) return null
        val suppliedUrl = link.text("url") ?: descriptor?.text("url") ?: return null
        val url = (if (suppliedUrl.startsWith('/')) "https://music.apple.com$suppliedUrl" else suppliedUrl)
            .takeIf { canonicalPageUrl(it, country, PublicMusicPage.Room, id) } ?: return null
        val colors = if (palette.size >= 2) palette else List(2) { palette.firstOrNull() ?: Color(0xFF24242A) }
        return Track("apple:room:$id", title, "", "", 0L, colors, emptyList(), catalogId = id,
            catalogKind = CatalogKind.Page, resourceId = id, resourceType = "public-rooms", url = url)
    }

    internal fun collection(html: String, storefront: String, capabilities: CatalogPresentationCapabilities,
        type: String, id: String): PublicMusicCollection {
        checkPage(numericId.matches(id), "Invalid public page identity")
        val pageKind = when (type) {
            "public-rooms" -> PublicMusicPage.Room
            "public-curators" -> PublicMusicPage.Curator
            else -> fail("Unsupported public page")
        }
        val country = storefront.lowercase(Locale.ROOT)
        val page = serverPage(html, country, pageKind, id)
        val data = page.optJSONObject("data") ?: fail("Missing public page data")
        val url = data.text("canonicalURL")?.takeIf { canonicalPageUrl(it, country, pageKind, id) }
            ?: fail("Mismatched public page canonical URL")
        val shelves = shelves(html, country, capabilities, pageKind, data)
        val title = if (pageKind == PublicMusicPage.Room) shelves.firstOrNull()?.title
            ?: data.optJSONArray("sections")?.objects()?.firstNotNullOfOrNull { section ->
                section.optJSONObject("header")?.optJSONObject("item")?.optJSONObject("titleLink")?.text("title")
            } ?: fail("Missing public room header") else {
            data.optJSONArray("sections")?.objects()?.firstOrNull { it.text("itemKind") == "headerComponentModel" }
                ?.optJSONArray("items")?.objects()?.firstNotNullOfOrNull { item ->
                    item.optJSONObject("contentDescriptor")?.takeIf { descriptor ->
                        descriptor.text("kind") == "appleCurator" &&
                            descriptor.optJSONObject("identifiers")?.opt("storeAdamID")?.toString() == id
                    }?.let { item.text("title") }
                } ?: fail("Missing matching public curator header")
        }
        val palette = shelves.firstOrNull()?.items?.firstOrNull()?.palette ?: List(2) { Color(0xFF24242A) }
        val item = Track("apple:$id", title, "", "", 0L, palette, emptyList(),
            catalogId = id, catalogKind = CatalogKind.Page, resourceId = id, resourceType = type, url = url)
        val nextIntent = data.optJSONObject("nextIntent")
        val next = if (pageKind == PublicMusicPage.Room && nextIntent != null) {
            checkPage(nextIntent.text("\$kind") == "RoomPagePaginateIntent", "Invalid room continuation intent")
            checkPage(nextIntent.optJSONObject("room")?.text("id") == id, "Mismatched room continuation identity")
            roomCursor(checkNotNull(nextIntent.text("url")), country, id)
        } else null
        return PublicMusicCollection(item, shelves, next)
    }

    internal fun canonicalPageUrl(value: String, country: String, page: PublicMusicPage, id: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val parts = uri.path?.trim('/')?.split('/').orEmpty()
        return value.length <= 4096 && uri.scheme == "https" && uri.host == "music.apple.com" &&
            uri.userInfo == null && uri.port == -1 && uri.fragment == null && uri.query == null &&
            parts.size in 3..4 && parts.first() == country && parts[1] == page.path && parts.last() == id &&
            (page != PublicMusicPage.Room || parts.size == 3)
    }

    internal fun roomCursor(value: String, country: String, roomId: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: fail("Invalid room continuation")
        checkPage(value.length <= 4096 && !uri.isAbsolute && uri.rawAuthority == null && uri.fragment == null &&
            value.none { it <= ' ' } && uri.path == "/v1/editorial/$country/rooms/$roomId/contents", "Foreign room continuation")
        return value
    }

    internal fun roomContinuation(root: JSONObject, country: String, roomId: String, requestedPage: String,
        parseResource: (JSONObject) -> Track?, capabilities: CatalogPresentationCapabilities): CatalogResourcePage {
        roomCursor(requestedPage, country, roomId)
        val data = root.optJSONArray("data") ?: fail("Missing room continuation data")
        val resources = root.optJSONObject("resources")
        checkPage(data.length() <= 512, "Room continuation exceeds item limit")
        val items = data.objects().mapNotNull { identifier ->
            val id = identifier.text("id") ?: return@mapNotNull null
            val type = identifier.text("type") ?: return@mapNotNull null
            // The live endpoint returns ordinary full REST resources; the web
            // application's serialized shape can instead use an identifier map.
            val resource = if (identifier.optJSONObject("attributes") != null) identifier else
                resources?.optJSONObject(type)?.optJSONObject(id) ?: fail("Missing declared room resource")
            checkPage(resource.text("id") == id && resource.text("type") == type, "Mismatched room resource")
            parseResource(resource)
        }.filter(capabilities::canOpen).distinctBy { it.resourceType to it.resourceId }
        val next = root.text("next")?.let { roomCursor(it, country, roomId) }
        checkPage(next != requestedPage, "Repeated room continuation")
        return CatalogResourcePage(items, next, requestedPage, root.optJSONObject("meta")?.optInt("total", -1)?.takeIf { it >= 0 })
    }

    private fun parseItem(item: JSONObject, storefront: String, squareArt: Map<String, String>, liveRadio: Boolean): Track? {
        if (item.optBoolean("isDisabled", false)) return null
        val descriptor = item.optJSONObject("contentDescriptor") ?: return null
        val kind = when (descriptor.text("kind")) {
            "song" -> CatalogKind.Song to "songs"
            "album" -> CatalogKind.Album to "albums"
            "playlist" -> CatalogKind.Playlist to "playlists"
            "artist" -> CatalogKind.Artist to "artists"
            "radioStation" -> CatalogKind.Station to "stations"
            "musicVideo" -> CatalogKind.MusicVideo to "music-videos"
            "appleCurator" -> CatalogKind.Page to "apple-curators"
            else -> return null
        }
        val id = descriptor.optJSONObject("identifiers")?.opt("storeAdamID")?.toString() ?: return null
        val validId = when (kind.first) {
            CatalogKind.Playlist -> playlistId.matches(id)
            CatalogKind.Station -> stationId.matches(id)
            else -> numericId.matches(id)
        }
        if (!validId) return null
        val title = item.text("title") ?: item.links("titleLinks") ?: (if (liveRadio) item.text("accessibilityLabel") else null) ?: return null
        val artwork = item.optJSONObject("artwork")
        val dictionary = artwork?.optJSONObject("dictionary")
        val template = dictionary?.text("url")
        val width = dictionary?.optInt("width", 0) ?: 0
        val height = dictionary?.optInt("height", 0) ?: 0
        val image = if (template == null) null else {
            val prefix = template.substringBeforeLast('/', "")
            val suppliedSquare = squareArt[prefix]
            if (width > 0 && height > 0 && width != height) {
                // The web page itself can supply a square rendition for a standard
                // playlist lockup whose dictionary advertises a wide source canvas.
                // Stations also have real banner/portrait covers. Keep their
                // source aspect and render with Fit instead of dropping a usable
                // station merely because it has no proven square rendition.
                suppliedSquare ?: if (kind.first == CatalogKind.Station) {
                    val imageHeight = (640.0 * height / width).roundToInt().takeIf { it in 1..1280 } ?: return null
                    imageUrl(template.replace("{w}", "640").replace("{h}", imageHeight.toString())
                        .replace("{c}", "bb").replace("{f}", "jpg"))
                } else return null
            } else imageUrl(template.replace("{w}", "640").replace("{h}", "640")
                .replace("{c}", artwork.text("cropStyle")?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "bb")
                .replace("{f}", "jpg")) ?: suppliedSquare
        }
        val bg = dictionary?.text("bgColor")?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }
            ?.toLongOrNull(16)?.let { Color(0xFF000000L or it) } ?: Color(0xFF24242A)
        val canonicalUrl = descriptor.text("url")?.takeIf { value ->
            val uri = runCatching { URI(value) }.getOrNull()
            uri?.scheme == "https" && uri.host == "music.apple.com" && uri.rawUserInfo == null &&
                uri.port == -1 && uri.path.startsWith("/$storefront/")
        }
        return Track(
            id = "apple:$id", title = title,
            artist = item.text("artistName") ?: item.links("subtitleLinks") ?: item.text("subtitle") ?: "",
            album = if (kind.first == CatalogKind.Album) title else item.links("tertiaryLinks") ?: "",
            // Artwork's gradient/painter contract needs at least two colors.
            // The public model supplies one real background color: repeating
            // it keeps that fallback solid instead of inventing a second tint.
            durationMs = 0L, palette = listOf(bg, bg), lyrics = emptyList(), catalogId = id,
            catalogKind = kind.first, resourceId = id, resourceType = kind.second,
            artworkUrl = image, description = item.text("description"), url = canonicalUrl,
        )
    }

    private fun renderedSquareArtwork(html: String): Map<String, String> {
        val chosen = mutableMapOf<String, Pair<Int, String>>()
        val images = publicImage.matcher(html)
        while (images.find()) {
            val url = imageUrl(images.group().replace("&amp;", "&")) ?: continue
            val size = squareSize.find(url) ?: continue
            val width = size.groupValues[1].toIntOrNull() ?: continue
            val height = size.groupValues[2].toIntOrNull() ?: continue
            if (width != height || width !in 64..1024) continue
            val prefix = url.substringBeforeLast('/')
            val distance = abs(width - 640)
            if (chosen[prefix]?.first?.let { it <= distance } != true) chosen[prefix] = distance to url
        }
        return chosen.mapValues { it.value.second }
    }

    private fun imageUrl(value: String): String? {
        if (value.length > 4096 || value.any { it <= ' ' || it == '{' || it == '}' }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return value.takeIf { uri.scheme == "https" && host.endsWith(".mzstatic.com") &&
            uri.rawUserInfo == null && uri.port == -1 && uri.fragment == null && uri.path.startsWith("/image/") }
    }

    private fun JSONObject.text(key: String) = (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    private fun JSONObject.links(key: String): String? = optJSONArray(key)?.objects()
        ?.mapNotNull { it.text("title") }?.joinToString(", ")?.takeIf { it.isNotEmpty() }
    private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
    private fun fits(value: String, bytes: Int) = value.length <= bytes && value.toByteArray(Charsets.UTF_8).size <= bytes
    private fun checkPage(valid: Boolean, message: String) { if (!valid) fail(message) }
    private fun fail(message: String): Nothing = throw PublicMusicBrowseParseException(message)
}

class PublicMusicBrowseParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
