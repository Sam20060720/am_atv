package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.ContentShelf
import dev.amatv.model.CatalogResourcePage
import dev.amatv.model.CatalogResourceProfile
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.CatalogResourceSection
import dev.amatv.model.Track
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads anonymous New/Radio server models; never forwards account credentials. */
internal class PublicMusicBrowseClient(
    private val storefront: suspend () -> String,
    private val language: () -> String,
    private val editorialRequest: (suspend (String) -> JSONObject)? = null,
    private val parseResource: ((JSONObject) -> Track?)? = null,
) {
    suspend fun shelves(capabilities: CatalogPresentationCapabilities): List<ContentShelf> = shelves(capabilities, PublicMusicPage.New)

    suspend fun radioShelves(capabilities: CatalogPresentationCapabilities): List<ContentShelf> = shelves(capabilities, PublicMusicPage.Radio)

    suspend fun profile(ref: CatalogResourceRef, capabilities: CatalogPresentationCapabilities): CatalogResourceProfile {
        val region = region()
        val page = collection(ref, region, capabilities)
        val sections = page.shelves.map { shelf ->
            val next = if (ref.type == "public-rooms") page.nextPage else shelf.seeAll?.url
            CatalogResourceSection("public:${shelf.id}", resourceTypes = shelf.items.mapNotNull { it.resourceType }.toSet()
                // An empty Room batch still owns its page/continuation target.
                // Actual child resources are capability-filtered when decoded.
                .ifEmpty { setOf(ref.type) },
                pageSize = null, title = shelf.title,
                initialPage = CatalogResourcePage(shelf.items, next, "${page.item.url}#${shelf.id}"))
        }
        return CatalogResourceProfile(ref, page.item, page.item.title, null, page.item.artworkUrl, null, null,
            page.item.url, emptyList(), sections)
    }

    suspend fun sectionPage(ref: CatalogResourceRef, section: CatalogResourceSection, nextPage: String?,
        capabilities: CatalogPresentationCapabilities): CatalogResourcePage {
        require(ref.type in setOf("public-rooms", "public-curators") && section.id.startsWith("public:"))
        if (nextPage == null) return checkNotNull(section.initialPage)
        val region = region()
        val roomId = if (ref.type == "public-rooms") ref.id else {
            val link = checkNotNull(section.initialPage?.nextPage)
            URI(link).path.substringAfterLast('/').also { id ->
                require(PublicMusicBrowsePage.canonicalPageUrl(link, region, PublicMusicPage.Room, id))
            }
        }
        if (nextPage.startsWith("https://")) {
            require(PublicMusicBrowsePage.canonicalPageUrl(nextPage, region, PublicMusicPage.Room, roomId))
            val page = collection(CatalogResourceRef(roomId, "public-rooms"), region, capabilities)
            return CatalogResourcePage(page.shelves.flatMap { it.items }, page.nextPage, nextPage)
        }
        PublicMusicBrowsePage.roomCursor(nextPage, region, roomId)
        val root = checkNotNull(editorialRequest)(nextPage)
        return withContext(Dispatchers.Default) {
            PublicMusicBrowsePage.roomContinuation(root, region, roomId, nextPage, checkNotNull(parseResource), capabilities)
        }
    }

    private suspend fun region(): String = storefront().lowercase(Locale.ROOT).also { require(it.matches(Regex("[a-z]{2}"))) }

    private suspend fun collection(ref: CatalogResourceRef, region: String,
        capabilities: CatalogPresentationCapabilities): PublicMusicCollection {
        require(ref.id.matches(Regex("[1-9][0-9]{0,19}")))
        val path = when (ref.type) {
            "public-rooms" -> "room"
            "public-curators" -> "curator"
            else -> error("Unsupported public resource")
        }
        val html = html(region, "/$region/$path/${ref.id}")
        return withContext(Dispatchers.Default) {
            PublicMusicBrowsePage.collection(html, region, capabilities, ref.type, ref.id)
        }
    }

    private suspend fun shelves(capabilities: CatalogPresentationCapabilities, page: PublicMusicPage): List<ContentShelf> = try {
        load(capabilities, page)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: CatalogRequestException) { throw failure }
    catch (failure: Exception) {
        throw CatalogRequestException(
            if (failure is IOException) CatalogFailureReason.Transient else CatalogFailureReason.Unavailable,
            AppText.text(R.string.browse_content_unavailable),
        )
    }

    private suspend fun load(capabilities: CatalogPresentationCapabilities, page: PublicMusicPage): List<ContentShelf> {
        val region = region()
        val html = html(region, "/$region/${page.path}")
        return withContext(Dispatchers.Default) {
            when (page) {
                PublicMusicPage.New -> PublicMusicBrowsePage.parse(html, region, capabilities)
                PublicMusicPage.Radio -> PublicMusicBrowsePage.parseRadio(html, region, capabilities)
                else -> error("A public resource requires its exact identity")
            }
        }
    }

    private suspend fun html(region: String, path: String): String {
        require(path.startsWith("/$region/"))
        return fetchPublicMusicHtml("https://music.apple.com$path", language(), MAX_HTML_BYTES, maxRequests = 3)
    }

    private companion object { const val MAX_HTML_BYTES = 4 * 1024 * 1024 }
}
