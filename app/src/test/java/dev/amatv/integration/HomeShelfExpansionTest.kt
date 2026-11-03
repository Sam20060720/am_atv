package dev.amatv.integration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeShelfExpansionTest {
    @Test fun completeRecommendationDoesNotExpandJustBecauseUnsupportedRowsWereFiltered() {
        fun playlist(id: String) = JSONObject().put("id", id).put("type", "playlists")
            .put("attributes", JSONObject().put("name", id).put("curatorName", "Apple Music"))
        fun recommendation(id: String, contents: JSONObject) = JSONObject().put("id", id).put("type", "personal-recommendation")
            .put("attributes", JSONObject().put("title", id))
            .put("relationships", JSONObject().put("contents", contents))
        val rows = JSONArray().put(playlist("pl.one")).put(JSONObject().put("id", "future").put("type", "future-kind"))
        val closed = JSONObject().put("data", rows).put("meta", JSONObject().put("total", 2))
        val paged = JSONObject(closed.toString()).put("next", "/v1/me/recommendations/paged/contents?cursor=x%2By")
        val larger = JSONObject(closed.toString()).put("meta", JSONObject().put("total", 3))
        val child = recommendation("child", JSONObject().put("data", JSONArray().put(playlist("pl.two"))))
        val grouped = JSONObject().put("data", JSONArray().put(playlist("pl.one")).put(child))
            .put("meta", JSONObject().put("total", 2))
        val source = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
        val shelves = source.parseRecommendationShelves(JSONObject().put("data", JSONArray()
            .put(recommendation("closed", closed)).put(recommendation("paged", paged))
            .put(recommendation("larger", larger)).put(recommendation("grouped", grouped))))
        assertEquals(listOf("recommendation:closed", "recommendation:paged", "recommendation:larger", "recommendation:grouped"), shelves.map { it.id })
        assertEquals(listOf("pl.one"), shelves.first().items.map { it.resourceId ?: it.catalogId })
        assertFalse(shelves.first().hasMore)
        assertTrue(shelves[1].hasMore)
        assertTrue(shelves[2].hasMore)
        assertTrue(shelves[3].hasMore)
        assertEquals(listOf("pl.one", "pl.two"), shelves[3].items.map { it.resourceId ?: it.catalogId })
    }
}
