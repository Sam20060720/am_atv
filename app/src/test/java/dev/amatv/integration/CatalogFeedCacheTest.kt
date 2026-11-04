package dev.amatv.integration

import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogFeedCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun song(id: String = "1") = Track(id, "Title $id", "Artist", "Album", 180_000, emptyList(), emptyList(), catalogId = id)
    private fun shelf(id: String = "recent", items: List<Track> = listOf(song())) = ContentShelf(id, "Recent", items)
    private fun directory() = temporary.newFolder()
    private fun cache(dir: File, now: () -> Long = { 1_000_000L }, limits: CatalogFeedCacheLimits = CatalogFeedCacheLimits()) =
        CatalogFeedCache(dir, { ContentCacheSettings() }, { 0L }, { true }, nowEpochMs = now, limits = limits)

    @Test fun snapshotsFromTheOldRegionFallbackNamespaceAreRefreshed() = runBlocking {
        val dir = directory(); val cache = cache(dir)
        assertTrue(cache.write("account", "en-US", CatalogFeedKind.Browse, listOf(shelf()), 0))
        val current = dir.listFiles()!!.single()
        val oldDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("v1\u0000account\u0000en-US\u0000Browse".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val old = JSONObject(current.readText()).put("digest", oldDigest)
        current.delete()
        File(dir, "$oldDigest.feed").writeText(old.toString())
        assertNull(cache.read("account", "en-US", CatalogFeedKind.Browse))
    }

    @Test fun publicShelfDestinationSurvivesTheDisplaySnapshotAndOldSnapshotsRemainReadable() = runBlocking {
        val cache = cache(directory())
        val route = song("6828493100").copy(catalogKind = dev.amatv.model.CatalogKind.Page,
            resourceType = "public-rooms", resourceId = "6828493100", url = "https://music.apple.com/tw/room/6828493100",
            palette = List(2) { androidx.compose.ui.graphics.Color(0xFF24242A) })
        val shelf = shelf().copy(seeAll = route)
        assertTrue(cache.write("account", "zh-TW", CatalogFeedKind.Browse, listOf(shelf), 0))
        assertEquals(route, cache.read("account", "zh-TW", CatalogFeedKind.Browse)!!.shelves.single().seeAll)
        assertTrue(cache.write("account", "zh-TW", CatalogFeedKind.Browse, listOf(shelf.copy(seeAll = null)), 0))
        assertNull(cache.read("account", "zh-TW", CatalogFeedKind.Browse)!!.shelves.single().seeAll)
    }

    @Test fun scopedRoundTripKeepsDisplayDataButDropsLyricsNativeHandlesAndCredentialUrls() = runBlocking {
        val dir = directory(); val cache = cache(dir)
        val track = song().copy(lyrics = listOf(LyricLine(listOf(LyricWord("NEVER_PERSIST_LYRICS", 0, 1000)))),
            playbackQueueId = 777, description = "A description", durationMs = 0,
            artworkUrl = "https://example.test/image.jpg",
            motionArtworkUrl = "https://example.test/motion.m3u8?access_token=NEVER_PERSIST_URL_TOKEN")
        assertTrue(cache.write("ACCOUNT_TOKEN_NEVER_PERSIST", "zh-TW", CatalogFeedKind.Home, listOf(shelf(items = listOf(track))), 0))
        val files = dir.listFiles()!!.toList()
        assertEquals(1, files.size)
        assertTrue(files.single().name.matches(Regex("[a-f0-9]{64}\\.feed")))
        val raw = files.single().readText()
        assertFalse(raw.contains("ACCOUNT_TOKEN_NEVER_PERSIST"))
        assertFalse(raw.contains("NEVER_PERSIST_LYRICS"))
        assertFalse(raw.contains("NEVER_PERSIST_URL_TOKEN"))
        assertFalse(raw.contains("playbackQueueId"))
        val decoded = cache.read("ACCOUNT_TOKEN_NEVER_PERSIST", "zh-TW", CatalogFeedKind.Home)!!.shelves.single().items.single()
        assertEquals(track.id, decoded.id)
        assertEquals("A description", decoded.description)
        assertEquals(0L, decoded.durationMs)
        assertTrue(decoded.lyrics.isEmpty())
        assertNull(decoded.playbackQueueId)
        assertNull(decoded.motionArtworkUrl)
        assertEquals(track.artworkUrl, decoded.artworkUrl)
    }

    @Test fun legacyHomeSnapshotKeepsItemsButRefreshesUnknownExpansion() = runBlocking {
        val dir = directory(); val cache = cache(dir)
        assertTrue(cache.write("account", "zh-TW", CatalogFeedKind.Home, listOf(shelf().copy(hasMore = true)), 0))
        val file = dir.listFiles()!!.single()
        val legacy = JSONObject(file.readText())
        legacy.getJSONArray("shelves").getJSONObject(0).remove("hasMore")
        file.writeText(legacy.toString())
        val decoded = cache.read("account", "zh-TW", CatalogFeedKind.Home)!!
        assertEquals(listOf("1"), decoded.shelves.single().items.map { it.id })
        assertFalse(decoded.shelves.single().hasMore)
        assertTrue(decoded.requiresRefresh)
    }

    @Test fun accountLocaleAndFeedKindsNeverShareSnapshots() = runBlocking {
        val cache = cache(directory())
        cache.write("account-a", "en", CatalogFeedKind.Home, listOf(shelf("private-a")), 0)
        assertNull(cache.read("account-b", "en", CatalogFeedKind.Home))
        assertNull(cache.read("account-a", "zh-TW", CatalogFeedKind.Home))
        assertNull(cache.read("account-a", "en", CatalogFeedKind.Browse))
        cache.write("account-b", "en", CatalogFeedKind.Home, listOf(shelf("private-b")), 0)
        assertEquals("private-a", cache.read("account-a", "en", CatalogFeedKind.Home)!!.shelves.single().id)
        assertEquals("private-b", cache.read("account-b", "en", CatalogFeedKind.Home)!!.shelves.single().id)
    }

    @Test fun disabledClearingAndOldGenerationRequestsCannotReadOrOverwriteCache() = runBlocking {
        val dir = directory(); var settings = ContentCacheSettings(); var epoch = 0L
        val cache = CatalogFeedCache(dir, { settings }, { epoch }, { true }, nowEpochMs = { 1_000_000L })
        assertTrue(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf()), epoch))
        val file = dir.listFiles()!!.single()
        val original = file.readBytes()
        settings = settings.copy(enabled = false); epoch++
        assertNull(cache.read("a", "en", CatalogFeedKind.Home))
        assertFalse(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("late")), 0))
        settings = settings.copy(enabled = true, clearing = true)
        assertFalse(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("late")), epoch))
        assertNull(cache.read("a", "en", CatalogFeedKind.Home))
        settings = settings.copy(clearing = false); epoch++
        assertFalse(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("late")), epoch - 1))
        assertArrayEquals(original, file.readBytes())
        assertEquals("recent", cache.read("a", "en", CatalogFeedKind.Home)!!.shelves.single().id)
        assertEquals(listOf(file), dir.listFiles()!!.toList())
    }

    @Test fun generationChangeDuringWriteGatePreservesTheExistingSnapshot() = runBlocking {
        val dir = directory(); var epoch = 0L; var changeEpoch = false
        val cache = CatalogFeedCache(dir, { ContentCacheSettings() }, { epoch }, {
            if (changeEpoch) epoch++
            true
        }, nowEpochMs = { 1_000_000L })
        cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("old")), epoch)
        changeEpoch = true
        assertFalse(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("late")), epoch))
        assertEquals("old", cache.read("a", "en", CatalogFeedKind.Home)!!.shelves.single().id)
        assertTrue(dir.listFiles()!!.none { it.extension == "pending" })
    }

    @Test fun queuedOldWriterCannotRepopulateAnExternallyCleanedDirectoryAfterReenable() = runBlocking {
        val dir = directory(); var epoch = 0L; var settings = ContentCacheSettings()
        val mutations = Mutex()
        val cache = CatalogFeedCache(dir, { settings }, { epoch }, { true }, mutations,
            nowEpochMs = { 1_000_000L }, io = Dispatchers.Unconfined)
        assertTrue(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf()), 0))
        val file = dir.listFiles()!!.single()
        mutations.lock()
        val writer = async(Dispatchers.Unconfined) { cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("late")), 0) }
        settings = settings.copy(clearing = true); epoch++
        // External cleanup is fixture setup; the stale writer gate is the contract under test.
        assertTrue(file.delete())
        settings = settings.copy(clearing = false); epoch++
        mutations.unlock()
        assertFalse(writer.await())
        assertNull(cache.read("a", "en", CatalogFeedKind.Home))
        assertTrue(dir.listFiles()!!.isEmpty())
        assertTrue(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("current")), epoch))
        assertEquals("current", cache.read("a", "en", CatalogFeedKind.Home)!!.shelves.single().id)
    }

    @Test fun lowStoragePausesWritesButKeepsExistingSnapshotsReadable() = runBlocking {
        val dir = directory(); var free = true
        val cache = CatalogFeedCache(dir, { ContentCacheSettings() }, { 0L }, { free }, nowEpochMs = { 1_000_000L })
        assertTrue(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf()), 0))
        free = false
        assertFalse(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("replacement")), 0))
        assertEquals("recent", cache.read("a", "en", CatalogFeedKind.Home)!!.shelves.single().id)
    }

    @Test fun corruptOversizedAndExpiredEntriesAreIgnoredAndRemoved() = runBlocking {
        val dir = directory(); var now = 1_000_000L
        val limits = CatalogFeedCacheLimits(maxSnapshotBytes = 2048, maxTotalBytes = 8192, maxAgeMillis = 100)
        val cache = cache(dir, { now }, limits)
        cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf()), 0)
        dir.listFiles()!!.single().writeText("not json")
        assertNull(cache.read("a", "en", CatalogFeedKind.Home))
        assertTrue(dir.listFiles()!!.isEmpty())
        cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf()), 0)
        dir.listFiles()!!.single().writeText("x".repeat(2049))
        assertNull(cache.read("a", "en", CatalogFeedKind.Home))
        assertTrue(dir.listFiles()!!.isEmpty())
        cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf()), 0)
        now += 101
        assertNull(cache.read("a", "en", CatalogFeedKind.Home))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun bytesAndEntryCountAreBoundedAndReadsUpdateLruAcrossClockRollback() = runBlocking {
        val dir = directory(); var now = 1_000_000L
        val limits = CatalogFeedCacheLimits(maxSnapshotBytes = 2048, maxTotalBytes = 8192, maxEntries = 2)
        val cache = cache(dir, { now }, limits)
        cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("a")), 0)
        now++
        cache.write("b", "en", CatalogFeedKind.Home, listOf(shelf("b")), 0)
        now -= 1000 // LRU access time remains logical even when wall time moved backward.
        cache.read("a", "en", CatalogFeedKind.Home)
        cache.write("c", "en", CatalogFeedKind.Home, listOf(shelf("c")), 0)
        assertNotNull(cache.read("a", "en", CatalogFeedKind.Home))
        assertNull(cache.read("b", "en", CatalogFeedKind.Home))
        assertNotNull(cache.read("c", "en", CatalogFeedKind.Home))
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= limits.maxTotalBytes)
    }

    @Test fun partialHomeSnapshotRetainsSourceProvenanceAndOldDataWithoutSerializingErrors() = runBlocking {
        val cache = cache(directory())
        val first = shelf("same-id", listOf(song("one")))
        val second = shelf("same-id", listOf(song("two"))).copy(hasMore = true)
        val home = HomeFeedSnapshot(listOf(
            HomeFeedSlot("recommendations", HomeFeedSourceStatus.Ready, HomeFeedResult(listOf(first))),
            HomeFeedSlot("recent", HomeFeedSourceStatus.Failed, HomeFeedResult(listOf(second), listOf(song("two"))),
                IllegalStateException("NEVER_PERSIST_SERVER_ERROR")),
        ))
        val flat = listOf(first.copy(id = "recommendations:same-id"), second.copy(id = "recent:same-id"))
        assertTrue(cache.write("a", "en", CatalogFeedKind.Home, flat, 0, home))
        val decoded = cache.read("a", "en", CatalogFeedKind.Home)!!
        assertTrue(decoded.requiresRefresh)
        assertFalse(decoded.complete)
        assertEquals(listOf("recommendations", "recent"), decoded.homeSnapshot!!.slots.map { it.sourceId })
        assertEquals(listOf("one", "two"), decoded.homeSnapshot.slots.flatMap { it.result!!.shelves.single().items }.map { it.id })
        assertEquals(listOf(false, true), decoded.shelves.map { it.hasMore })
        assertEquals(listOf(false, true), decoded.homeSnapshot.slots.map { it.result!!.shelves.single().hasMore })
        assertEquals(listOf("two"), decoded.homeSnapshot.recentTracks!!.map { it.id })
        assertTrue(decoded.homeSnapshot.errors.isEmpty())
    }

    @Test fun snapshotItemLimitsAreExplicitlyMarkedSoTruncatedDataWillRefresh() = runBlocking {
        val dir = directory()
        val cache = cache(dir, limits = CatalogFeedCacheLimits(maxShelves = 2, maxTracksPerShelf = 1, maxTracks = 2))
        val shelves = List(5) { index -> shelf("shelf-$index", List(5) { song("$index:$it") }) }
        assertTrue(cache.write("a", "en", CatalogFeedKind.Browse, shelves, 0))
        val decoded = cache.read("a", "en", CatalogFeedKind.Browse)!!
        assertTrue(decoded.truncated)
        assertEquals(2, decoded.shelves.size)
        assertTrue(decoded.shelves.all { it.items.size <= 1 })
        val raw = JSONObject(dir.listFiles()!!.single().readText())
        assertTrue(raw.getJSONArray("tracks").length() <= 2)
    }

    @Test fun oversizedReplacementDoesNotDestroyThePriorAtomicSnapshot() = runBlocking {
        val dir = directory()
        val cache = cache(dir, limits = CatalogFeedCacheLimits(maxSnapshotBytes = 1024, maxTotalBytes = 4096))
        assertTrue(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("old")), 0))
        val huge = song().copy(artworkUrl = "https://example.test/" + "x".repeat(2000))
        assertFalse(cache.write("a", "en", CatalogFeedKind.Home, listOf(shelf("replacement", listOf(huge))), 0))
        assertEquals("old", cache.read("a", "en", CatalogFeedKind.Home)!!.shelves.single().id)
        assertTrue(dir.listFiles()!!.none { it.extension == "pending" })
    }
}
