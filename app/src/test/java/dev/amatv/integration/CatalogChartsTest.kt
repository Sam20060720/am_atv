package dev.amatv.integration

import dev.amatv.model.CatalogChartCategory
import dev.amatv.model.CatalogChartCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogChartsTest {
    private val parser = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    @Test fun chartsRetainServerMetadataOpaqueCursorAndExactCategoryIdentity() = runBlocking {
        val requests = mutableListOf<String>()
        val cursor = "/v1/catalog/tw/charts?types=songs&chart=most-played&offset=25&cursor=a%2Bb%3D&l=zh-Hant"
        val api = CatalogChartsApi({ "tw" }, { path, user ->
            assertFalse(user); requests += path
            assertTrue(path.endsWith("&extend%5Bsongs%5D=editorialVideo"))
            if (requests.size == 1) JSONObject("""{"results":{"songs":[{"chart":"most-played","name":"熱門歌曲","orderId":"most-played:songs",
              "href":"/v1/catalog/tw/charts?types=songs&chart=most-played","next":"$cursor","meta":{"total":200},"data":[
              {"id":"1","type":"songs","attributes":{"name":"Song"},"relationships":{"albums":{"data":[{"id":"2","type":"albums","attributes":{"name":"Not a second item"}}]}}},
              {"id":"3","type":"albums","attributes":{"name":"Wrong resource type"}}]}]}}""")
            else JSONObject("""{"results":{"songs":[{"chart":"most-played","data":[]}]}}""")
        }, parser::parseResourceItem, Dispatchers.Unconfined)
        val first = api.page(CatalogChartCategory.Songs)
        assertEquals(listOf("1"), first.items.map { it.resourceId })
        assertEquals("熱門歌曲", first.title)
        assertEquals("most-played:songs", first.orderId)
        assertEquals(200, first.total)
        assertEquals(cursor, first.nextPage?.key)
        assertNull(api.page(CatalogChartCategory.Songs, first.nextPage).nextPage)
        assertTrue(requests.last().startsWith(cursor))
        assertTrue(runCatching { api.page(CatalogChartCategory.Albums, first.nextPage) }.isFailure)
        for (path in listOf(
            "https://evil.test/v1/catalog/tw/charts?types=songs&chart=most-played&offset=25",
            "/v1/catalog/us/charts?types=songs&chart=most-played&offset=25",
            "/v1/catalog/tw/playlists?types=songs&chart=most-played&offset=25",
            "/v1/catalog/tw/charts?types=albums&chart=most-played&offset=25",
            "/v1/catalog/tw/charts?types=songs&types=albums&chart=most-played&offset=25",
            "/v1/catalog/tw/charts?types=songs&chart=other-chart&offset=25",
        )) assertTrue(runCatching { api.page(CatalogChartCategory.Songs,
            CatalogChartCursor(CatalogChartCategory.Songs, "tw", "most-played", null, path)) }.isFailure)
        assertEquals(2, requests.size)
    }

    @Test fun cityAndDailyGlobalUseActualPlaylistGroupsAndOptionalMetadataCanFallBack() = runBlocking {
        val requests = mutableListOf<String>()
        val api = CatalogChartsApi({ "tw" }, { path, _ ->
            requests += path
            if ("extend%5B" in path) throw CatalogRequestException(CatalogFailureReason.Authorization, "Optional metadata denied", 403)
            if ("chartId=135" in path) {
                JSONObject("""{"results":{"cityCharts":[{"chart":"city-top","name":"城市排行榜",
                  "href":"/v1/catalog/tw/charts?chartId=135&limit=25&offset=25",
                  "data":[{"id":"pl.next-city","type":"playlists","attributes":{"name":"Next city"}}]}]}}""")
            } else
            JSONObject("""{"results":{
              "playlists":[{"chart":"most-played","data":[{"id":"pl.other","type":"playlists","attributes":{"name":"Other collection"}}]}],
              "cityCharts":[{"chart":"city-top","name":"City Charts","href":"/v1/catalog/tw/charts?chartId=135&limit=25",
                "next":"/v1/catalog/tw/charts?chartId=135&limit=25&offset=25",
                "data":[{"id":"pl.city","type":"playlists","attributes":{"name":"City chart playlist"}}]}],
              "dailyGlobalTopCharts":[{"chart":"daily-global-top","name":"Daily Top 100","href":"/v1/catalog/tw/charts?chartId=119&limit=25",
                "next":"/v1/catalog/tw/charts?chartId=119&limit=25&offset=25",
                "data":[{"id":"pl.global","type":"playlists","attributes":{"name":"Global chart playlist"}}]}]
            }}""")
        }, parser::parseResourceItem, Dispatchers.Unconfined)
        val city = api.page(CatalogChartCategory.City)
        assertEquals("/v1/catalog/tw/charts?types=playlists&limit=25&with=cityCharts", requests[1])
        assertEquals(listOf("pl.city"), city.items.map { it.resourceId })
        assertEquals("city-top", city.chart)
        assertEquals("135", city.nextPage?.chartId)
        assertNull(city.total)
        assertTrue(runCatching { api.page(CatalogChartCategory.DailyGlobal, city.nextPage) }.isFailure)
        for (invalid in listOf(
            "/v1/catalog/tw/charts?chartId=119&offset=25",
            "/v1/catalog/tw/charts?chartId=135&chartId=119&offset=25",
            "/v1/catalog/tw/charts?chartId=135&types=songs&offset=25",
            "/v1/catalog/tw/charts?types=playlists&with=cityCharts&offset=25",
        )) assertTrue(runCatching { api.page(CatalogChartCategory.City,
            CatalogChartCursor(CatalogChartCategory.City, "tw", "city-top", null, invalid, "135")) }.isFailure)
        val next = api.page(CatalogChartCategory.City, city.nextPage)
        assertEquals(listOf("pl.next-city"), next.items.map { it.resourceId })
        assertNull(next.nextPage)
        val global = api.page(CatalogChartCategory.DailyGlobal)
        assertEquals(listOf("pl.global"), global.items.map { it.resourceId })
        assertEquals("119", global.nextPage?.chartId)
        assertEquals("/v1/catalog/tw/charts?types=playlists&limit=25&with=dailyGlobalTopCharts", requests.last())
        assertEquals(6, requests.size)
    }
}
