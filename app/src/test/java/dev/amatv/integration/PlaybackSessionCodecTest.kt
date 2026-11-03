package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionCodecTest {
    @Test fun bufferedShuffleCheckpointRoundTripsItsRawOccurrenceAndAlwaysRestartsAtFirstPage() {
        val playlist = track("playlist").copy(catalogKind = dev.amatv.model.CatalogKind.Playlist,
            palette = listOf(Color(0xFF202428), Color(0xFF30343A)))
        val pending = dev.amatv.playback.CollectionPlaybackCheckpoint(playlist, "/first?limit=300", emptySet(), 1_000,
            "a".repeat(32), dev.amatv.playback.CollectionShuffleCheckpoint(999, 1L, "songs:1"))
        val original = session(3, 0).copy(pendingCollection = pending, unshuffledEntryIds = listOf(3L, 2L))
        val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(original))!!
        assertEquals(pending, decoded.pendingCollection)
        assertEquals(listOf(3L, 2L), decoded.unshuffledEntryIds)
        val compact = PlaybackSessionCodec.decode(PlaybackSessionCodec.encodeCheckpoint(decoded))!!
        assertEquals("/first?limit=300", compact.pendingCollection?.nextPage)
        assertTrue(compact.pendingCollection!!.visitedPages.isEmpty())
        assertEquals(pending.shuffle, compact.pendingCollection?.shuffle)
    }

    @Test fun legacyMissingEmptyOrSinglePaletteRestoresTheTwoColorArtworkContract() {
        for (palette in listOf(null, org.json.JSONArray(), org.json.JSONArray().put(0xFF216843.toInt()))) {
            val encoded = JSONObject(PlaybackSessionCodec.encode(session(1, 0)))
            val resource = encoded.getJSONArray("tracks").getJSONObject(0)
            if (palette == null) resource.remove("palette") else resource.put("palette", palette)
            val restored = checkNotNull(PlaybackSessionCodec.decode(encoded.toString())).queue.single()
            assertEquals("1", restored.id)
            assertEquals(2, restored.palette.size)
            assertEquals(restored.palette.first(), restored.palette[1])
            if (palette != null && palette.length() == 1) assertEquals(Color(0xFF216843), restored.palette.first())
        }
    }

    @Test fun completedPopulationCheckpointCannotReplaceAnOlderFullSampleWithOnlyItsWindow() {
        val playlist = track("playlist").copy(catalogKind = dev.amatv.model.CatalogKind.Playlist)
        val pending = dev.amatv.playback.CollectionPlaybackCheckpoint(playlist, "/first?limit=300", emptySet(), 100,
            "a".repeat(32), dev.amatv.playback.CollectionShuffleCheckpoint(0, 1L, "songs:1"))
        val full = session(1, 0).copy(pendingCollection = pending, savedRevision = 100)
        val completed = session(100, 0).let { it.copy(contextQueue = it.queue, contextEntryIds = it.queueEntryIds,
            pendingCollection = null, savedRevision = 101) }
        // Process death before the async full write must retain the sampled
        // prefix + restart cursor, rather than lose 97 songs outside the window.
        val restored = PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(completed))!!
        assertEquals(full.queueEntryIds, restored.queueEntryIds)
        assertEquals(pending, restored.pendingCollection)
    }

    @Test fun originalContainerMembershipSurvivesCheckpointMergeWithoutClassifyingExplicitEntries() {
        val original = session(80, 40).copy(
            containerEntryIds = (1L..70L).filter { it != 41L }.toSet(),
            playbackContext = dev.amatv.playback.PlaybackContext(dev.amatv.playback.ContextKind.Playlist, "pl.original", "Original"),
        )
        val encoded = PlaybackSessionCodec.encode(original)
        val decoded = PlaybackSessionCodec.decode(encoded)!!
        assertEquals(original.containerEntryIds, decoded.containerEntryIds)
        assertEquals(original.playbackContext, decoded.playbackContext)
        assertFalse(41L in decoded.containerEntryIds) // Deliberate insertion remains outside the original container.
        val checkpoint = PlaybackSessionCodec.encodeCheckpoint(decoded.copy(currentIndex = 60, savedRevision = 101))
        assertEquals(original.containerEntryIds, PlaybackSessionCodec.decode(checkpoint)!!.containerEntryIds)
        val merged = PlaybackSessionCodec.merge(decoded, checkpoint)!!
        assertEquals(original.containerEntryIds, merged.containerEntryIds)
        assertEquals(original.playbackContext, merged.playbackContext)

        val legacy = JSONObject(encoded).apply { remove("containerEntryIds"); remove("playbackContext") }
        val older = PlaybackSessionCodec.decode(legacy.toString())!!
        assertTrue(older.containerEntryIds.isEmpty())
        assertNull(older.playbackContext)
    }

    private fun track(id: String) = Track(id, "Song $id", "Artist", "Album", 180_000,
        emptyList(), emptyList(), catalogId = id)
    private fun session(count: Int, current: Int = count - 1): PlaybackSession = PlaybackSession(
        queue = (1..count).map { track("$it") }, currentIndex = current, positionMs = 42_000,
        wasPlaying = true, destination = Destination.NowPlaying, previousDestination = Destination.Home,
        radioActive = true, radioSeed = track("1"), radioContinuation = "cursor",
        autoplayEntryIndices = (count - 10 until count).filter { it >= 0 }.toSet(),
        queueEntryIds = (1..count).map { it.toLong() }, playedEntryIds = (1..current).map { it.toLong() }.toSet(),
        contextQueue = listOf(track("1")), contextEntryIds = listOf(1), sessionId = 3,
        persistenceId = "logical-session", savedRevision = 100,
    )

    @Test fun longTimelineRoundTripRetainsEveryEntryIdOriginAndPlayedFlag() {
        val original = session(1_500, 1_250)
        val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(original))!!
        assertEquals(1_500, decoded.queue.size)
        assertEquals(original.queueEntryIds, decoded.queueEntryIds)
        assertEquals(original.playedEntryIds, decoded.playedEntryIds)
        assertEquals(original.autoplayEntryIndices, decoded.autoplayEntryIndices)
        assertEquals(original.currentIndex, decoded.currentIndex)
        assertEquals(original.contextEntryIds, decoded.contextEntryIds)
    }

    @Test fun repeatedSongMetadataUsesDictionaryAndNeverStoresLyricsOrNativeQueueIds() {
        val lyrics = listOf(LyricLine(listOf(LyricWord("DO_NOT_PERSIST_LYRICS", 0, 1_000))))
        val repeated = track("1").copy(lyrics = lyrics, playbackQueueId = 777)
        val original = session(500).copy(queue = List(500) { repeated }, contextQueue = listOf(repeated))
        val encoded = PlaybackSessionCodec.encode(original)
        assertFalse(encoded.contains("DO_NOT_PERSIST_LYRICS"))
        assertEquals(1, JSONObject(encoded).getJSONArray("tracks").length())
        val decoded = PlaybackSessionCodec.decode(encoded)!!
        assertEquals(500, decoded.queueEntryIds.distinct().size)
        assertTrue(decoded.queue.all { it.lyrics.isEmpty() && it.playbackQueueId == null })
    }

    @Test fun releaseRatingRoundTripsAndOlderMetadataRemainsUnknown() {
        for (rating in listOf("explicit", "clean", null)) {
            val metadata = track("1").copy(contentRating = rating).toSessionJson()
            assertEquals(rating, metadata.toSessionTrack().contentRating)
        }
        val older = track("1").toSessionJson().apply { remove("contentRating") }
        assertNull(older.toSessionTrack().contentRating)
        older.put("contentRating", "unexpected")
        assertNull(older.toSessionTrack().contentRating)
    }

    @Test fun smallCheckpointOverlaysRecentTailWithoutDroppingFullHistory() {
        val full = session(1_000, 900)
        val latest = session(1_005, 1_002).copy(savedRevision = 101, positionMs = 81_000)
        val checkpoint = PlaybackSessionCodec.encodeCheckpoint(latest)
        assertTrue(PlaybackSessionCodec.decode(checkpoint)!!.queue.size <= 11)
        val restored = PlaybackSessionCodec.merge(full, checkpoint)!!
        assertEquals(1_005, restored.queue.size)
        assertEquals(1L, restored.queueEntryIds.first())
        assertEquals(1_002, restored.currentIndex)
        assertEquals(81_000L, restored.positionMs)
        assertEquals(full.contextEntryIds, restored.contextEntryIds)
    }

    @Test fun checkpointMovingBackwardKeepsTheFullFutureAndStableIds() {
        val full = session(1_000, 900)
        val selectedHistory = full.copy(currentIndex = 400, positionMs = 0, savedRevision = 101)
        val restored = PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(selectedHistory))!!
        assertEquals(full.queueEntryIds, restored.queueEntryIds)
        assertEquals(400, restored.currentIndex)
    }

    @Test fun deletedUpcomingContextCannotMixWithAnOlderFullRepeatCycle() {
        val full = session(20, 5).let { it.copy(contextQueue = it.queue, contextEntryIds = it.queueEntryIds,
            autoplayEntryIndices = emptySet()) }
        val removedIndex = 15
        val latest = full.copy(
            queue = full.queue.filterIndexed { index, _ -> index != removedIndex },
            queueEntryIds = full.queueEntryIds.filterIndexed { index, _ -> index != removedIndex },
            contextQueue = full.contextQueue.filterIndexed { index, _ -> index != removedIndex },
            contextEntryIds = full.contextEntryIds.filterIndexed { index, _ -> index != removedIndex },
            savedRevision = 101,
        )
        val restored = PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(latest))!!
        assertEquals(full, restored)
    }

    @Test fun clearedUpcomingContextCannotMixWithAnOlderFullRepeatCycle() {
        val full = session(20, 5).let { it.copy(contextQueue = it.queue, contextEntryIds = it.queueEntryIds,
            autoplayEntryIndices = emptySet()) }
        val latest = full.copy(
            queue = full.queue.take(6), queueEntryIds = full.queueEntryIds.take(6),
            contextQueue = full.contextQueue.take(6), contextEntryIds = full.contextEntryIds.take(6),
            autoplayEntryIndices = emptySet(), savedRevision = 101,
        )
        assertEquals(full, PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(latest)))
    }

    @Test fun olderCheckpointWithoutContextFingerprintStillMigratesItsCursor() {
        val full = session(80, 20)
        val checkpoint = JSONObject(PlaybackSessionCodec.encodeCheckpoint(full.copy(currentIndex = 40, savedRevision = 101)))
            .apply { remove("contextFingerprint") }
        val restored = PlaybackSessionCodec.merge(full, checkpoint.toString())!!
        assertEquals(full.queueEntryIds, restored.queueEntryIds)
        assertEquals(40, restored.currentIndex)
    }

    @Test fun repeatedOccurrenceSourcesAndDeferredLastBarrierSurviveFullCheckpointAndMerge() {
        fun repeated(count: Int, current: Int) = session(count, current).copy(
            queue = (1..count).map { track("${(it - 1) % 4 + 1}") },
            queueSourceEntryIds = (1L..count.toLong()).map { (it - 1L) % 4L + 1L },
            contextQueue = (1..4).map { track("$it") }, contextEntryIds = (1L..4L).toList(),
            autoplayEntryIndices = emptySet(),
        )
        val full = repeated(80, 40)
        val latest = repeated(85, 82).copy(deferredLastEntryIds = setOf(84L, 85L), savedRevision = 101)
        val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(latest))!!
        assertEquals(latest.queueSourceEntryIds, decoded.queueSourceEntryIds)
        assertEquals(latest.deferredLastEntryIds, decoded.deferredLastEntryIds)
        val checkpoint = PlaybackSessionCodec.encodeCheckpoint(decoded)
        val compact = PlaybackSessionCodec.decode(checkpoint)!!
        assertEquals(latest.queueSourceEntryIds.subList(74, 85), compact.queueSourceEntryIds)
        assertEquals(latest.deferredLastEntryIds, compact.deferredLastEntryIds)
        val earlyCheckpoint = PlaybackSessionCodec.decode(PlaybackSessionCodec.encodeCheckpoint(decoded.copy(currentIndex = 40)))!!
        assertFalse(84L in earlyCheckpoint.queueEntryIds)
        assertEquals(latest.deferredLastEntryIds, earlyCheckpoint.deferredLastEntryIds)
        val merged = PlaybackSessionCodec.merge(full, checkpoint)!!
        assertEquals(latest.queueEntryIds, merged.queueEntryIds)
        assertEquals(latest.queueSourceEntryIds, merged.queueSourceEntryIds)
        assertEquals(latest.deferredLastEntryIds, merged.deferredLastEntryIds)
    }

    @Test fun olderVersionTwoRecordsDefaultTheirSourceToEachOccurrenceIdentity() {
        val original = session(3, 1)
        val encoded = JSONObject(PlaybackSessionCodec.encode(original)).apply {
            remove("deferredLastEntryIds")
            val queue = getJSONArray("queue")
            (0 until queue.length()).forEach { queue.getJSONObject(it).remove("s") }
        }
        val restored = PlaybackSessionCodec.decode(encoded.toString())!!
        assertEquals(original.queueEntryIds, restored.queueSourceEntryIds)
        assertTrue(restored.deferredLastEntryIds.isEmpty())
    }

    @Test fun olderAsyncCursorCannotOverrideANewerAtomicSnapshot() {
        val full = session(1_000, 900).copy(savedRevision = 200)
        val oldCursor = session(1_000, 800).copy(savedRevision = 199)
        assertEquals(900, PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(oldCursor))!!.currentIndex)
    }

    @Test fun newLogicalContextNeverReattachesThePreviousSessionsHistory() {
        val old = session(1_000)
        val next = session(3, 0).copy(persistenceId = "another-context", savedRevision = 101)
        val restored = PlaybackSessionCodec.merge(old, PlaybackSessionCodec.encodeCheckpoint(next))!!
        assertEquals("another-context", restored.persistenceId)
        assertEquals(3, restored.queue.size)
    }

    @Test fun versionOneMigrationPreservesValidQueueAndAssignsAppEntryIds() {
        val old = JSONObject().put("version", 1)
            .put("queue", org.json.JSONArray(listOf(track("1").toSessionJson(), track("1").toSessionJson())))
            .put("currentIndex", 1).put("positionMs", 45_000).put("wasPlaying", true)
        val restored = PlaybackSessionCodec.decode(old.toString())!!
        assertEquals(listOf(1L, 2L), restored.queueEntryIds)
        assertEquals(listOf(1L, 2L), restored.queueSourceEntryIds)
        assertEquals(1, restored.currentIndex)
        assertEquals(45_000L, restored.positionMs)
        assertEquals(setOf(1L), restored.playedEntryIds)
    }

    @Test fun versionOneSourceIdentityKeepsRawOffsetsWhenMalformedTracksAreSkipped() {
        val old = JSONObject().put("version", 1)
            .put("queue", org.json.JSONArray(listOf(track("1").toSessionJson(), JSONObject(), track("3").toSessionJson())))
            .put("currentIndex", 2).put("positionMs", 45_000)
        val restored = PlaybackSessionCodec.decode(old.toString())!!
        assertEquals(listOf(1L, 3L), restored.queueEntryIds)
        assertEquals(restored.queueEntryIds, restored.queueSourceEntryIds)
        assertEquals(1, restored.currentIndex)
        assertEquals(45_000L, restored.positionMs)
    }

    @Test fun currentSessionMissingOrInvalidSourceUsesAppleWithoutErasingExplicitYtmProvenance() {
        for (raw in listOf(null, "", "invalid-provider", "YouTubeMusic", "AppleMusic")) {
            val root = JSONObject(PlaybackSessionCodec.encode(session(2)))
            if (raw == null) root.remove("autoplaySource") else root.put("autoplaySource", raw)
            val decoded = PlaybackSessionCodec.decode(root.toString())!!
            val expected = if (raw == "YouTubeMusic") AutoplaySource.YouTubeMusic else AutoplaySource.AppleMusic
            assertEquals(expected, decoded.autoplaySource)
            assertEquals(2, decoded.queue.size)
            assertEquals(listOf(1L, 2L), decoded.queueEntryIds)
        }
    }

    @Test fun legacySessionMissingOrInvalidSourceAlsoUsesAppleMusic() {
        for (raw in listOf(null, "", "invalid-provider", "YouTubeMusic", "AppleMusic")) {
            val root = JSONObject().put("version", 1)
                .put("queue", org.json.JSONArray(listOf(track("1").toSessionJson())))
                .put("currentIndex", 0).put("positionMs", 25_000)
            if (raw != null) root.put("autoplaySource", raw)
            val decoded = PlaybackSessionCodec.decode(root.toString())!!
            val expected = if (raw == "YouTubeMusic") AutoplaySource.YouTubeMusic else AutoplaySource.AppleMusic
            assertEquals(expected, decoded.autoplaySource)
            assertEquals("1", decoded.queue.single().id)
            assertEquals(25_000L, decoded.positionMs)
        }
    }
    @Test fun pendingCollectionRoundTripsWithoutDuplicatingFullQueueOrCredentials() {
        val container = track("playlist").copy(catalogKind = dev.amatv.model.CatalogKind.Playlist)
        val cursor = dev.amatv.playback.CollectionPlaybackCheckpoint(container,
            "/v1/me/library/playlists/p.test/tracks?offset=100", setOf("/v1/me/library/playlists/p.test/tracks?limit=100"),
            200, "a".repeat(32))
        val source = session(100, 10).copy(pendingCollection = cursor)
        val encoded = PlaybackSessionCodec.encode(source)
        val decoded = PlaybackSessionCodec.decode(encoded)!!
        assertEquals(cursor, decoded.pendingCollection?.copy(collection = container))
        val pending = JSONObject(encoded).getJSONObject("pendingCollection")
        assertFalse(pending.has("queue"))
        assertFalse(pending.has("userToken"))
        assertEquals("a".repeat(32), pending.getString("authorizationScope"))
    }

    @Test fun malformedOptionalCursorDoesNotEraseTheSavedMusicSession() {
        val root = JSONObject(PlaybackSessionCodec.encode(session(3)))
        root.put("pendingCollection", JSONObject().put("authorizationScope", "raw-token-is-not-a-scope"))
        val decoded = PlaybackSessionCodec.decode(root.toString())!!
        assertEquals(3, decoded.queue.size)
        assertNull(decoded.pendingCollection)
    }

    @Test fun newerCursorCannotSkipAPageThatTheAtomicTimelineHasNotSavedYet() {
        val container = track("playlist").copy(catalogKind = dev.amatv.model.CatalogKind.Playlist)
        fun cursor(next: String) = dev.amatv.playback.CollectionPlaybackCheckpoint(container, next, setOf("first"), 300, "b".repeat(32))
        val full = session(100, 10).copy(pendingCollection = cursor("second"), savedRevision = 100)
        val notYetWritten = full.copy(pendingCollection = cursor("third"), savedRevision = 101)
        val restored = PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(notYetWritten))!!
        assertEquals("second", restored.pendingCollection?.nextPage)
        assertEquals(full.queueEntryIds, restored.queueEntryIds)
    }

}
