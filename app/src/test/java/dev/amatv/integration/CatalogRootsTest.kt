package dev.amatv.integration

import dev.amatv.model.CatalogKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogRootsTest {
    @Test fun genreRootUsesRealPageIdsAndFollowsOnlyItsOwnOpaqueCursor() = runBlocking {
        val parser = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
        val requested = mutableListOf<String>()
        val pages = CatalogPagesApi({ "tw" }, { false }, { path, user ->
            assertFalse(user); requested += path
            if (requested.size == 1) {
                assertEquals("/v1/catalog/tw/genres?limit=25", path)
                JSONObject("""{"data":[{"id":"20","type":"genres","attributes":{"name":"Alternative"},
                    "relationships":{"tracks":{"data":[{"id":"99","type":"songs","attributes":{"name":"Not a genre"}}]}}}],
                    "next":"/v1/catalog/tw/genres?offset=25&cursor=a%2Bb%3D&l=zh-Hant"}""")
            } else JSONObject("""{"data":[{"id":"34","type":"genres","attributes":{"name":"Music"}}]}""")
        }, parser::parseResourceItem, Dispatchers.Unconfined)
        val first = pages.rootPage("genres")
        assertEquals(listOf("20"), first.items.map { it.resourceId })
        assertEquals(CatalogKind.Page, first.items.single().catalogKind)
        assertEquals("genres", first.items.single().resourceType)
        assertEquals("/v1/catalog/tw/genres?offset=25&cursor=a%2Bb%3D&l=zh-Hant", first.nextPage)
        val second = pages.rootPage("genres", first.nextPage)
        assertEquals(first.nextPage, requested.last())
        assertEquals(listOf("20", "34"), mergeLibraryPage(first.items, second.items).map { it.resourceId })
        assertNull(second.nextPage)
        for (cursor in listOf(
            "/v1/catalog/tw/station-genres?offset=25",
            "/v1/catalog/us/genres?offset=25",
            "https://evil.example/v1/catalog/tw/genres?offset=25",
        )) {
            assertTrue(runCatching { pages.rootPage("genres", cursor) }.isFailure)
            assertEquals(2, requested.size)
        }
    }
}
