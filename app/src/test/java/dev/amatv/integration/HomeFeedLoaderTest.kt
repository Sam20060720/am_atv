package dev.amatv.integration

import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class HomeFeedLoaderTest {
    private fun track(id: String) = Track(id, id, "", "", 0, emptyList(), emptyList())
    private fun result(id: String, recent: List<Track>? = null) = HomeFeedResult(
        listOf(ContentShelf(id, id, listOf(track(id)))), recent,
    )

    @Test fun fastSourcePublishesWithoutWaitingAndSlotsRemainInDeclaredOrder() = runBlocking {
        val slow = CompletableDeferred<HomeFeedResult>()
        val fast = CompletableDeferred<HomeFeedResult>()
        val updates = Channel<HomeFeedSnapshot>(Channel.UNLIMITED)
        val job = async { HomeFeedLoader().load(listOf(
            HomeFeedSource("recommendations") { slow.await() },
            HomeFeedSource("recent") { fast.await() },
        )) { updates.trySend(it) } }
        val initial = updates.receive()
        assertEquals(listOf("recommendations", "recent"), initial.slots.map { it.sourceId })
        fast.complete(result("recent-tracks", listOf(track("recent-song"))))
        val partial = updates.receive()
        assertFalse(slow.isCompleted)
        assertEquals(HomeFeedSourceStatus.Loading, partial.slots.first().status)
        assertEquals(listOf("recommendations", "recent"), partial.slots.map { it.sourceId })
        assertEquals("recent-song", partial.recentTracks!!.single().id)
        val anchor = HomeFeedSectionKey("recent", "recent-tracks")
        assertEquals(anchor, partial.sections.single().key)
        slow.complete(result("for-you"))
        val final = job.await()
        assertTrue(final.complete)
        assertEquals(listOf("for-you", "recent-tracks"), final.sections.map { it.shelf.id })
        assertEquals(anchor, final.sections[1].key)
        assertEquals(HomeFeedSourceStatus.Loading, initial.slots[1].status)
    }

    @Test fun requestsAreBoundedAndFailureRetainsOtherSourcesAndPreviousData() = runBlocking {
        val gates = List(7) { CompletableDeferred<Unit>() }
        val updates = Channel<HomeFeedSnapshot>(Channel.UNLIMITED)
        var active = 0
        var maximum = 0
        val started = Channel<Int>(Channel.UNLIMITED)
        val failure = IllegalStateException("One source failed")
        val previous = HomeFeedSnapshot(listOf(HomeFeedSlot("source-1", HomeFeedSourceStatus.Ready, result("saved"))))
        val job = async { HomeFeedLoader(2).load((0..6).map { index ->
            HomeFeedSource("source-$index") {
                active++; maximum = maxOf(maximum, active); started.trySend(index)
                try {
                    gates[index].await()
                    if (index == 1) throw failure
                    result("shelf-$index")
                } finally { active-- }
            }
        }, previous) { updates.trySend(it) } }
        assertEquals("saved", updates.receive().sections.single().shelf.id)
        assertEquals(0, started.receive()); assertEquals(1, started.receive())
        assertEquals(2, active)
        gates.forEach { it.complete(Unit) }
        val final = job.await()
        assertEquals(2, maximum)
        assertEquals(0, active)
        assertSame(failure, final.errors["source-1"])
        assertEquals(HomeFeedSourceStatus.Failed, final.slots[1].status)
        assertEquals("saved", final.slots[1].result!!.shelves.single().id)
        assertEquals(7, final.sections.size)
        assertTrue(final.complete)
    }

    @Test fun cancellingAVisitPreventsEvenACancellationIgnoringProviderFromPublishing() = runBlocking {
        val late = CompletableDeferred<HomeFeedResult>()
        val started = CompletableDeferred<Unit>()
        val updates = mutableListOf<HomeFeedSnapshot>()
        val job = launch { HomeFeedLoader().load(listOf(HomeFeedSource("slow") {
            started.complete(Unit)
            withContext(NonCancellable) { late.await() }
        })) { updates += it } }
        started.await()
        job.cancel()
        late.complete(result("stale"))
        job.join()
        assertEquals(1, updates.size)
        assertTrue(updates.single().sections.isEmpty())
    }

    @Test fun supersededGenerationCannotCommitWithoutExplicitJobCancellation() = runBlocking {
        val late = CompletableDeferred<HomeFeedResult>()
        var current = true
        val updates = Channel<HomeFeedSnapshot>(Channel.UNLIMITED)
        val job = async { HomeFeedLoader().load(listOf(HomeFeedSource("source") { late.await() }),
            isCurrent = { current }) { updates.trySend(it) } }
        updates.receive()
        current = false
        late.complete(result("stale"))
        try { job.await(); fail("Old generation must be cancelled") } catch (_: CancellationException) { }
        assertTrue(updates.tryReceive().isFailure)
    }

    @Test fun providerCancellationCancelsTheAggregateInsteadOfWaitingForever() = runBlocking {
        withTimeout(1_000) {
            val job = async { HomeFeedLoader().load(listOf(HomeFeedSource("cancelled") {
                throw CancellationException("Visit left")
            })) { } }
            try { job.await(); fail("Source cancellation must propagate") } catch (_: CancellationException) { }
        }
    }

    @Test fun emptySuccessClearsOldPayloadWhileMissingRecentTrackPayloadDoesNotInventOne() = runBlocking {
        val previous = HomeFeedSnapshot(listOf(HomeFeedSlot("recent", HomeFeedSourceStatus.Ready,
            result("old", listOf(track("old"))))))
        val result = HomeFeedLoader().load(listOf(HomeFeedSource("recent") { HomeFeedResult(emptyList(), emptyList()) }), previous) { }
        assertTrue(result.sections.isEmpty())
        assertEquals(emptyList<Track>(), result.recentTracks)
        assertTrue(result.errors.isEmpty())
        assertNull(HomeFeedLoader().load(emptyList()) { }.recentTracks)
    }

    @Test fun duplicateShelfKeysFailOnlyTheirSourceAndSourceNamespacesRemainDistinct() = runBlocking {
        val final = HomeFeedLoader().load(listOf(
            HomeFeedSource("bad") { HomeFeedResult(result("duplicate").shelves + result("duplicate").shelves) },
            HomeFeedSource("a") { result("same") }, HomeFeedSource("b") { result("same") },
        )) { }
        assertTrue(final.errors.containsKey("bad"))
        assertEquals(listOf(HomeFeedSectionKey("a", "same"), HomeFeedSectionKey("b", "same")), final.sections.map { it.key })
    }
}
