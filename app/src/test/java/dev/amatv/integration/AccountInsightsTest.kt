package dev.amatv.integration

import dev.amatv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountInsightsTest {
    private fun api(fetch: suspend (String, Boolean) -> JSONObject): AccountInsightsApi {
        val parser = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
        return AccountInsightsApi({ "tw" }, fetch, parser::parseResourceItem, Dispatchers.Unconfined)
    }

    @Test fun historyKeepsOpaqueCursorAndCannotUseItForAnotherSection() = runBlocking {
        val calls = mutableListOf<String>()
        val source = api { path, user ->
            assertTrue(user); calls += path
            JSONObject("""{"data":[],"next":"/v1/me/recent/played/tracks?types=songs&cursor=a%2Bb%3D"}""")
        }
        val first = source.history(ListeningHistorySection.RecentTracks)
        assertTrue(calls.single().contains("types=songs,library-songs,music-videos,library-music-videos"))
        val next = checkNotNull(first.nextPage)
        assertTrue(next.key.startsWith("/v1/me/recent/played/tracks?types=songs&cursor=a%2Bb%3D&"))
        assertTrue(next.key.contains("include%5Blibrary-songs%5D=catalog"))
        try {
            source.history(ListeningHistorySection.HeavyRotation, first.nextPage)
            fail("A recent-track cursor must not load Heavy Rotation")
        } catch (_: IllegalArgumentException) { assertEquals(1, calls.size) }
    }

    @Test fun replayUnwrapsPeriodRelationshipsAndBatchesMetadataWithoutInventingYear() = runBlocking {
        val calls = mutableListOf<String>()
        val source = api { path, user ->
            calls += path
            when {
                path.startsWith("/v1/catalog/") -> {
                    assertFalse(user)
                    assertEquals("/v1/catalog/tw/songs?ids=11,22", path)
                    JSONObject("""{"data":[{"id":"11","type":"songs","attributes":{"name":"One"}},
                        {"id":"22","type":"songs","attributes":{"name":"Two"}}]}""")
                }
                path.contains("/view/top-songs") -> JSONObject("""{"data":[]}""")
                path.contains("views=top-songs") -> JSONObject("""{"data":[{"id":"summary.2024","type":"music-summaries",
                    "attributes":{"year":2024,"period":"year"},"views":{"top-songs":{"data":[
                      {"id":"a","type":"song-period-summaries","relationships":{"song":{"data":[{"id":"11","type":"songs"}]}}},
                      {"id":"b","type":"song-period-summaries","relationships":{"song":{"data":[{"id":"22","type":"songs"}]}}}],
                    "next":"/v1/me/music-summaries/summary.2024/view/top-songs?cursor=x%2By"}}}]}""")
                else -> JSONObject("""{"data":[{"id":"summary.2024","type":"music-summaries","attributes":{"year":2024,"period":"year"}}]}""")
            }
        }
        val summary = source.replay().summaries.single()
        assertEquals(2024, summary.year)
        val first = (source.replayView(summary, ReplayView.TopSongs) as ListeningReplayView.Page).page
        assertEquals(listOf("11", "22"), first.items.flatMap { it.items }.map { it.catalogId })
        assertTrue(first.items.all { it.unresolvedResources.isEmpty() })
        assertEquals(3, calls.size)
        val second = (source.replayView(summary, ReplayView.TopSongs, first.nextPage) as ListeningReplayView.Page).page
        assertTrue(second.items.isEmpty())
        assertNull(second.nextPage)
        try {
            source.replayView(summary, ReplayView.TopArtists, first.nextPage)
            fail("A song-view cursor must not load a different view")
        } catch (_: IllegalArgumentException) { assertEquals(4, calls.size) }
    }

    @Test fun recommendationsLoadOnlyTheOpenedContentsPageAndKeepItsCursorOwner() = runBlocking {
        val calls = mutableListOf<String>()
        val source = api { path, user ->
            calls += path
            when (path) {
                "/v1/me/recommendations?limit=10" -> {
                    assertTrue(user)
                    JSONObject("""{"data":[{"id":"group.root","type":"personal-recommendation","attributes":{
                      "title":{"stringForDisplay":"For You"},"resourceTypes":["songs","personal-recommendation"]},
                      "relationships":{"contents":{"data":[{"id":"embedded-only","type":"songs"}]}}}],
                      "next":"/v1/me/recommendations?cursor=root%2Btail"}""")
                }
                "/v1/me/recommendations?cursor=root%2Btail" -> JSONObject("""{"data":[]}""")
                "/v1/me/recommendations/group.root/contents?limit=10" -> {
                    assertTrue(user)
                    JSONObject("""{"data":[{"id":"11","type":"songs"},
                      {"id":"group.child","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Child"}},
                        "relationships":{"contents":{"data":[{"id":"nested-only","type":"songs"}]}}},
                      {"id":"unknown-id","type":"future-resource"}],
                      "next":"/v1/me/recommendations/group.root/contents?cursor=z%2F1"}""")
                }
                "/v1/catalog/tw/songs?ids=11" -> {
                    assertFalse(user)
                    JSONObject("""{"data":[{"id":"11","type":"songs","attributes":{"name":"One"}}]}""")
                }
                "/v1/me/recommendations/group.root/contents?cursor=z%2F1" ->
                    JSONObject("""{"data":[{"id":"12","type":"songs","attributes":{"name":"Two"}}]}""")
                else -> error("Unexpected recommendation request: $path")
            }
        }
        val first = source.recommendations()
        val group = first.items.single()
        assertEquals(listOf("/v1/me/recommendations?limit=10"), calls)
        assertEquals("For You", group.title)
        assertTrue(source.recommendations(first.nextPage).items.isEmpty())
        assertEquals("/v1/me/recommendations?cursor=root%2Btail", calls.last())
        val contents = source.recommendationContents(group.id)
        assertEquals(4, calls.size)
        assertEquals("11", (contents.items[0] as RecommendationEntry.Item).track.catalogId)
        assertEquals("group.child", (contents.items[1] as RecommendationEntry.Group).recommendation.id)
        assertEquals(CatalogResourceRef("unknown-id", "future-resource"),
            (contents.items[2] as RecommendationEntry.Unresolved).ref)
        assertEquals("/v1/me/recommendations/group.root/contents?cursor=z%2F1", contents.nextPage?.key)
        try {
            source.recommendationContents("group.child", contents.nextPage)
            fail("A group cursor must not change its owner")
        } catch (_: IllegalArgumentException) { assertEquals(4, calls.size) }
        val tail = source.recommendationContents(group.id, contents.nextPage)
        assertEquals("12", (tail.items.single() as RecommendationEntry.Item).track.catalogId)
        assertNull(tail.nextPage)
        assertEquals(5, calls.size)
    }
}
