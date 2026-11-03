package dev.amatv.integration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RadioCatalogApiTest {
    private val parser = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
    private val response = """{"data":[{"id":"ra.real","type":"stations","attributes":{"name":"Real Radio","playParams":{"id":"ra.real","kind":"radioStation"}},"relationships":{"contents":{"data":[{"id":"123","type":"songs","attributes":{"name":"Not a second station"}}]}}},{"id":"124","type":"songs","attributes":{"name":"Not a station"}}],"meta":{"total":12}}"""

    @Test fun livePersonalAndHistoryUseTheirActualEndpointAuthAndResourceType() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val api = RadioCatalogApi({ "tw" }, { path, user -> calls += path to user; JSONObject(response) }, parser::parseResourceItem, Dispatchers.Unconfined)
        for (section in RadioCatalogSection.entries) {
            val page = api.page(section)
            assertEquals(listOf("ra.real"), page.items.map { it.resourceId })
            assertEquals(12, page.total)
        }
        assertTrue(calls[0].first.startsWith("/v1/catalog/tw/stations?filter%5Bfeatured%5D=apple-music-live-radio&"))
        assertFalse(calls[0].second)
        assertTrue(calls[1].first.startsWith("/v1/catalog/tw/stations?filter%5Bidentity%5D=personal&"))
        assertTrue(calls[1].second)
        assertTrue(calls[2].first.startsWith("/v1/me/recent/radio-stations?limit=25&"))
        assertTrue(calls[2].second)
    }

    @Test fun optionalEnrichmentDenialFallsBackWithoutLosingIdentityOrAuthorization() = runBlocking {
        val calls = mutableListOf<String>()
        val api = RadioCatalogApi({ "tw" }, { path, user ->
            assertTrue(user)
            calls += path
            if ("extend=" in path) throw CatalogRequestException(CatalogFailureReason.Authorization, "Optional metadata denied", 403)
            JSONObject(response)
        }, parser::parseResourceItem, Dispatchers.Unconfined)
        assertEquals("Real Radio", api.page(RadioCatalogSection.Personal).items.single().title)
        assertEquals(2, calls.size)
        assertEquals("/v1/catalog/tw/stations?filter%5Bidentity%5D=personal", calls.last())
    }

    @Test fun stationContinuationPreservesFilterAndRejectsAnotherRootBeforeRequest() = runBlocking {
        val calls = mutableListOf<String>()
        val api = RadioCatalogApi({ "tw" }, { path, _ -> calls += path; JSONObject("""{"data":[],"next":"/v1/catalog/tw/stations?offset=25"}""") }, parser::parseResourceItem, Dispatchers.Unconfined)
        val first = api.page(RadioCatalogSection.Personal)
        assertEquals("/v1/catalog/tw/stations?offset=25&filter%5Bidentity%5D=personal", first.nextPage)
        for (path in listOf(
            "https://evil.test/v1/catalog/tw/stations?offset=25",
            "/v1/catalog/us/stations?offset=25",
            "/v1/catalog/tw/albums?offset=25",
            "/v1/catalog/tw/stations?filter[featured]=apple-music-live-radio&offset=25",
            "/v1/catalog/tw/stations?filter[identity]=personal&filter[identity]=other&offset=25",
        )) assertTrue(runCatching { api.page(RadioCatalogSection.Personal, path) }.isFailure)
        assertTrue(runCatching { api.page(RadioCatalogSection.Live, first.nextPage) }.isFailure)
        assertEquals(1, calls.size)
    }

    @Test fun recentContinuationRejectsCrossAccountNamespaceAndMalformedData() = runBlocking {
        val calls = mutableListOf<String>()
        val api = RadioCatalogApi({ "tw" }, { path, _ ->
            calls += path
            JSONObject("""{"data":[],"next":"https://api.music.apple.com/v1/me/recent/radio-stations?offset=25"}""")
        }, parser::parseResourceItem, Dispatchers.Unconfined)
        assertEquals("/v1/me/recent/radio-stations?offset=25", api.page(RadioCatalogSection.Recent).nextPage)
        assertTrue(runCatching { api.page(RadioCatalogSection.Recent, "/v1/me/library/songs?offset=25") }.isFailure)
        assertEquals(1, calls.size)
        val malformed = RadioCatalogApi({ "tw" }, { _, _ -> JSONObject("""{"error":"not a valid empty page"}""") }, parser::parseResourceItem, Dispatchers.Unconfined)
        assertTrue(runCatching { malformed.page(RadioCatalogSection.Recent) }.isFailure)
    }

    @Test fun personalAndRecentReadCurrentAccountResponseWithoutReusingAnotherAccountStations() = runBlocking {
        var country = "tw"
        var account = "first"
        val calls = mutableListOf<Pair<String, Boolean>>()
        val api = RadioCatalogApi({ country }, { path, user ->
            calls += path to user
            JSONObject("""{"data":[{"id":"ra.u-$account","type":"stations","attributes":{"name":"$account station","playParams":{"id":"ra.u-$account","kind":"radioStation"}}}]}""")
        }, parser::parseResourceItem, Dispatchers.Unconfined)
        assertEquals("ra.u-first", api.page(RadioCatalogSection.Personal).items.single().catalogId)
        assertEquals("ra.u-first", api.page(RadioCatalogSection.Recent).items.single().catalogId)

        // The same API object survives sign-out/sign-in. The supplied account
        // transport and its current storefront remain authoritative each time.
        country = "us"
        account = "second"
        assertEquals("ra.u-second", api.page(RadioCatalogSection.Personal).items.single().catalogId)
        assertEquals("ra.u-second", api.page(RadioCatalogSection.Recent).items.single().catalogId)
        assertTrue(calls.all { it.second })
        assertTrue(calls[0].first.startsWith("/v1/catalog/tw/stations?"))
        assertTrue(calls[2].first.startsWith("/v1/catalog/us/stations?"))
        assertEquals(4, calls.size)
    }

    @Test fun anEmptyPersonalResponseRemainsEmptyInsteadOfInventingAStation() = runBlocking {
        val api = RadioCatalogApi({ "tw" }, { _, user ->
            assertTrue(user)
            JSONObject("""{"data":[]}""")
        }, parser::parseResourceItem, Dispatchers.Unconfined)
        assertTrue(api.page(RadioCatalogSection.Personal).items.isEmpty())
        assertTrue(api.page(RadioCatalogSection.Recent).items.isEmpty())
    }
}
