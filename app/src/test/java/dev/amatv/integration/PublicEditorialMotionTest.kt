package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicEditorialMotionTest {
    // Envelope/field names are from the recorded public Positions/Ariana pages,
    // not from an API shape that is denied editorialArtwork permissions.
    private fun page(id: String = "1553944254", country: String = "tw", heroId: String = id): String {
        fun descriptor(value: String) = JSONObject().put("kind", "album")
            .put("identifiers", JSONObject().put("storeAdamID", value))
        val video = JSONObject().put("motionDetailSquare", JSONObject()
            .put("video", "https://mvod.itunes.apple.com/example.m3u8")
            .put("previewFrame", JSONObject().put("width", 3840).put("height", 3840)
                .put("url", "https://is1-ssl.mzstatic.com/image/thumb/example/{w}x{h}bb.{f}")))
        val hero = JSONObject().put("contentDescriptor", descriptor(heroId))
            .put("videoArtwork", JSONObject().put("dictionary", video))
        val intent = JSONObject().put("\$kind", "AlbumDetailPageIntent")
            .put("contentDescriptor", descriptor(id).put("locale", JSONObject().put("storefront", country)))
        val root = JSONObject().put("data", JSONArray().put(JSONObject().put("intent", intent)
            .put("data", JSONObject().put("sections", JSONArray().put(JSONObject()
                .put("itemKind", "containerDetailHeaderLockup").put("items", JSONArray().put(hero)))))))
        return "<html><script type='application/json' id='serialized-server-data'>$root</script></html>"
    }

    private fun item() = Track("library:l.real", "Positions", "Ariana", "Positions", 0, emptyList(), emptyList(),
        catalogId = "1553944254", catalogKind = CatalogKind.Album, resourceId = "l.real", resourceType = "library-albums")

    @Test fun publicDetailHeaderSuppliesMotionWhenRestExtensionIsNotPermitted() {
        val assets = PublicEditorialMotionPage.parse(page(), "tw", "album", "1553944254")
        assertEquals(1, assets.size)
        assertEquals("motionDetailSquare", assets.single().flavor)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/example/600x600bb.jpg", assets.single().previewUrl)
    }

    @Test fun neverBorrowsMotionFromWrongPageHeroOrStorefront() {
        assertTrue(PublicEditorialMotionPage.parse(page(id = "42"), "tw", "album", "1553944254").isEmpty())
        assertTrue(PublicEditorialMotionPage.parse(page(heroId = "42"), "tw", "album", "1553944254").isEmpty())
        assertTrue(PublicEditorialMotionPage.parse(page(country = "us"), "tw", "album", "1553944254").isEmpty())
    }

    @Test fun enrichmentPreservesLibraryIdentityAndUsesOneCachedRead() = runBlocking {
        var reads = 0
        val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { url, _ ->
            assertEquals("https://music.apple.com/tw/album/1553944254", url)
            reads++
            page()
        })
        val original = item()
        val enriched = client.artwork(original)
        assertEquals(original, enriched.copy(motionArtworkUrl = null, motionPreviewUrl = null, motionArtworkVariants = emptyList()))
        assertEquals(enriched, client.artwork(original))
        assertEquals(1, reads)
    }

    @Test fun idleRemountRestoresKnownMotionWithoutStorefrontOrNetworkWork() = runBlocking {
        var storefrontReads = 0
        var pageReads = 0
        val client = PublicEditorialMotionClient({ storefrontReads++; "tw" }, { "zh-TW" }, { _, _ ->
            pageReads++
            page()
        })
        val original = item()
        assertSame(original, client.cachedArtwork(original, "tw"))
        assertEquals(0, storefrontReads)
        assertEquals(0, pageReads)
        val enriched = client.artwork(original)
        // The current card supplies labels and identity; only motion metadata is reused.
        val remounted = original.copy(title = "Updated library title", playbackQueueId = 7L)
        assertEquals(remounted.copy(motionArtworkUrl = enriched.motionArtworkUrl,
            motionPreviewUrl = enriched.motionPreviewUrl, motionArtworkVariants = enriched.motionArtworkVariants),
            client.cachedArtwork(remounted, "tw"))
        assertEquals(1, storefrontReads)
        assertEquals(1, pageReads)
    }

    @Test fun idleLookupHonorsExistingMetadataScopeExpiryAndCacheGeneration() = runBlocking {
        var now = 1_000L
        var language = "zh-TW"
        var generation = 0L
        var enabled = true
        val client = PublicEditorialMotionClient({ "tw" }, { language }, { _, _ -> page() },
            now = { now }, cacheGeneration = { generation }, cacheEnabled = { enabled })
        val original = item()
        client.artwork(original)
        assertNotNull(client.cachedArtwork(original, "tw").motionArtworkUrl)
        assertSame(original, client.cachedArtwork(original, "us"))
        val other = original.copy(catalogId = "42")
        assertSame(other, client.cachedArtwork(other, "tw"))
        language = "en-US"
        assertSame(original, client.cachedArtwork(original, "tw"))
        language = "zh-TW"
        generation++
        assertSame(original, client.cachedArtwork(original, "tw"))
        generation--
        enabled = false
        assertSame(original, client.cachedArtwork(original, "tw"))
        enabled = true
        val authoritative = original.copy(motionArtworkUrl = "https://example.test/new-motion.m3u8")
        assertSame(authoritative, client.cachedArtwork(authoritative, "tw"))
        now += 6 * 60 * 60_000L
        assertSame(original, client.cachedArtwork(original, "tw"))
    }

    @Test fun idleLookupUsesTheSameBoundedMetadataLru() = runBlocking {
        val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { url, _ ->
            page(id = url.substringAfterLast('/'))
        })
        fun album(id: Int) = item().copy(catalogId = id.toString())
        for (id in 1..48) client.artwork(album(id))
        assertNotNull(client.cachedArtwork(album(1), "tw").motionArtworkUrl)
        client.artwork(album(49))
        val evicted = album(2)
        assertSame(evicted, client.cachedArtwork(evicted, "tw"))
        assertNotNull(client.cachedArtwork(album(1), "tw").motionArtworkUrl)
        assertNotNull(client.cachedArtwork(album(49), "tw").motionArtworkUrl)
    }

    @Test fun unmappedLibraryResourceNeverBecomesAPublicCatalogRequest() = runBlocking {
        val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ -> fail("Unmapped library request"); "" })
        val original = item().copy(catalogId = null)
        assertSame(original, client.artwork(original))
    }

    @Test fun failuresAreOptionalAndDoNotPoisonLaterVisit() = runBlocking {
        var reads = 0
        val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ ->
            if (++reads == 1) throw IOException()
            page()
        })
        val original = item()
        assertSame(original, client.artwork(original))
        assertNotNull(client.artwork(original).motionArtworkUrl)
        assertEquals(2, reads)
    }

    @Test fun cancellationNeverReturnsAStaleArtworkResult() = runBlocking {
        val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ -> throw CancellationException("leave") })
        try { client.artwork(item()); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun independentResourcesDoNotWaitForTheSlowPreviousHeader() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { url, _ ->
            if (url.endsWith("1553944254")) {
                firstStarted.complete(Unit)
                finishFirst.await()
                page()
            } else page(id = "42")
        })
        val slow = async { client.artwork(item()) }
        firstStarted.await()
        val fast = withTimeout(2_000) { client.artwork(item().copy(catalogId = "42")) }
        assertNotNull(fast.motionArtworkUrl)
        finishFirst.complete(Unit)
        assertNotNull(slow.await().motionArtworkUrl)
    }

    @Test fun leavingFocusKeepsOneSharedReadForTheNextVisit() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var reads = 0
        val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ ->
            reads++
            started.complete(Unit)
            response.await()
            page()
        })
        val firstVisit = async { client.artwork(item()) }
        started.await()
        firstVisit.cancelAndJoin()
        val nextVisit = async { client.artwork(item()) }
        response.complete(Unit)
        assertNotNull(withTimeout(2_000) { nextVisit.await() }.motionArtworkUrl)
        assertEquals(1, reads)
    }

    @Test fun processRestartReadsPersistedMetadataAndExpiredAssetsRefetch() = runBlocking {
        val directory = Files.createTempDirectory("motion-metadata-test").toFile()
        try {
            var clock = 10_000L
            var reads = 0
            fun client() = PublicEditorialMotionClient({ "tw" }, { "zh-TW" },
                { _, _ -> reads++; page() }, now = { clock }, cacheDirectory = directory)
            val original = client().artwork(item())
            assertEquals(original, client().artwork(item()))
            assertEquals(1, reads)
            clock += 7 * 60 * 60_000L
            assertNotNull(client().artwork(item()).motionArtworkUrl)
            assertEquals(2, reads)
        } finally { directory.deleteRecursively() }
    }

    @Test fun diskHitKeepsOriginalExpiryWhenMetadataEntersMemory() = runBlocking {
        for ((lifetime, response) in listOf(6 * 60 * 60_000L to page(), 15 * 60_000L to page(heroId = "42"))) {
            val directory = Files.createTempDirectory("motion-expiry-test").toFile()
            try {
                var clock = 10_000L
                var reads = 0
                fun client() = PublicEditorialMotionClient({ "tw" }, { "zh-TW" },
                    { _, _ -> reads++; response }, now = { clock }, cacheDirectory = directory)
                val original = item()
                val enriched = client().artwork(original)
                val expiresAt = clock + lifetime
                val file = directory.listFiles().orEmpty().single { it.extension == "json" }
                assertEquals(expiresAt, JSONObject(file.readText()).getLong("expires"))

                // A restarted process reads the disk value one millisecond
                // before expiry; that hit must not receive a fresh full TTL.
                clock = expiresAt - 1L
                val restarted = client()
                assertEquals(enriched, restarted.artwork(original))
                assertEquals(enriched, restarted.artwork(original))
                assertEquals(1, reads)
                assertEquals(expiresAt, JSONObject(file.readText()).getLong("expires"))
                clock = expiresAt
                assertSame(original, restarted.cachedArtwork(original, "tw"))
                assertEquals(enriched, restarted.artwork(original))
                assertEquals(2, reads)
                assertEquals(clock + lifetime, JSONObject(file.readText()).getLong("expires"))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun clearingDuringNetworkReadCannotRepopulateDisk() = runBlocking {
        val directory = Files.createTempDirectory("motion-clear-test").toFile()
        try {
            var generation = 0L
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val client = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ ->
                started.complete(Unit)
                response.await()
                page()
            }, cacheDirectory = directory, cacheGeneration = { generation })
            val read = async { client.artwork(item()) }
            started.await()
            generation++
            PublicEditorialMotionClient.clearDiskCache(directory)
            response.complete(Unit)
            assertNotNull(read.await().motionArtworkUrl)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun lateMetadataCannotAppearInANewLocaleOrCacheGeneration() = runBlocking {
        var language = "zh-TW"
        var generation = 0L
        var reads = 0
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val client = PublicEditorialMotionClient({ "tw" }, { language }, { _, _ ->
            if (++reads == 1) { started.complete(Unit); response.await() }
            page()
        }, cacheGeneration = { generation })
        val oldVisit = async { client.artwork(item()) }
        started.await()
        // A locale/account-cache transition may happen while the shared read
        // survives the departing card. Its eventual response stays in its old key.
        language = "en-US"
        generation++
        response.complete(Unit)
        assertNotNull(oldVisit.await().motionArtworkUrl)
        val remounted = item()
        assertSame(remounted, client.cachedArtwork(remounted, "tw"))
        assertNotNull(client.artwork(remounted).motionArtworkUrl)
        assertNotNull(client.cachedArtwork(remounted, "tw").motionArtworkUrl)
        assertEquals(2, reads)
    }

    @Test fun disabledOrLowStorageCacheDoesNotWriteMetadata() = runBlocking {
        val directory = Files.createTempDirectory("motion-policy-test").toFile()
        try {
            val disabled = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ -> page() },
                cacheDirectory = directory, cacheEnabled = { false })
            assertNotNull(disabled.artwork(item()).motionArtworkUrl)
            val lowSpace = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ -> page() },
                cacheDirectory = directory, canWriteCache = { false })
            assertNotNull(lowSpace.artwork(item()).motionArtworkUrl)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

}
