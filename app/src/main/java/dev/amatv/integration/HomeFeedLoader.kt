package dev.amatv.integration

import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A stable source slot; provider order is presentation order, never completion order. */
internal data class HomeFeedSource(val id: String, val load: suspend () -> HomeFeedResult)

internal data class HomeFeedResult(
    val shelves: List<ContentShelf>,
    /** Null means this source does not provide recent tracks; empty is an actual empty result. */
    val recentTracks: List<Track>? = null,
)

internal enum class HomeFeedSourceStatus { Loading, Ready, Failed }

internal data class HomeFeedSlot(
    val sourceId: String,
    val status: HomeFeedSourceStatus = HomeFeedSourceStatus.Loading,
    val result: HomeFeedResult? = null,
    val error: Throwable? = null,
)

/** Shelf IDs only have to be unique inside one source. Keep both keys for focus restoration. */
internal data class HomeFeedSectionKey(val sourceId: String, val shelfId: String)
internal data class HomeFeedSection(val key: HomeFeedSectionKey, val shelf: ContentShelf)

internal data class HomeFeedSnapshot(val slots: List<HomeFeedSlot>) {
    val complete: Boolean get() = slots.none { it.status == HomeFeedSourceStatus.Loading }
    val sections: List<HomeFeedSection> get() = slots.flatMap { slot ->
        slot.result?.shelves.orEmpty().map { HomeFeedSection(HomeFeedSectionKey(slot.sourceId, it.id), it) }
    }
    val recentTracks: List<Track>? get() = slots.firstNotNullOfOrNull { it.result?.recentTracks }
    val errors: Map<String, Throwable> get() = slots.mapNotNull { slot -> slot.error?.let { slot.sourceId to it } }.toMap()
}

/**
 * Independent, bounded home reads with serialized partial publication. A slow or
 * failed source cannot hold up another source's successful content. The UI owns
 * stable source placeholders/section-key anchoring; this never invents Tracks or
 * appends shelves in network-completion order.
 */
internal class HomeFeedLoader(private val parallelism: Int = 3) {
    init { require(parallelism in 1..8) }

    suspend fun load(
        sources: List<HomeFeedSource>,
        previous: HomeFeedSnapshot? = null,
        isCurrent: () -> Boolean = { true },
        onPartial: (HomeFeedSnapshot) -> Unit,
    ): HomeFeedSnapshot = coroutineScope {
        require(sources.all { it.id.isNotBlank() } && sources.map { it.id }.distinct().size == sources.size)
        val before = previous?.slots.orEmpty().associateBy { it.sourceId }
        val slots = sources.map { HomeFeedSlot(it.id, result = before[it.id]?.result) }.toMutableList()
        suspend fun publish(): HomeFeedSnapshot {
            // Check after provider completion and before every callback. A
            // provider that ignores cancellation cannot publish an old visit.
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("Home feed visit superseded")
            return HomeFeedSnapshot(slots.toList()).also(onPartial)
        }
        // Do not start any reads until the caller accepts the initial slot set.
        var latest = publish()
        val completions = Channel<Completion>(parallelism)
        val permits = Semaphore(parallelism)
        sources.forEachIndexed { index, source -> launch {
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) {
                    this@coroutineScope.cancel("Home feed visit superseded")
                    return@withPermit
                }
                val completion = try {
                    val result = source.load()
                    currentCoroutineContext().ensureActive()
                    require(result.shelves.all { it.id.isNotBlank() } &&
                        result.shelves.map { it.id }.distinct().size == result.shelves.size) {
                        "Home source returned ambiguous section keys"
                    }
                    Completion(index, result.copy(
                        shelves = result.shelves.map { it.copy(items = it.items.toList()) },
                        recentTracks = result.recentTracks?.toList(),
                    ))
                } catch (cancelled: CancellationException) {
                    this@coroutineScope.cancel(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    Completion(index, error = error)
                }
                completions.send(completion)
            }
        } }
        try {
            repeat(sources.size) {
                val next = completions.receive()
                val old = slots[next.index]
                slots[next.index] = if (next.error == null) old.copy(
                    status = HomeFeedSourceStatus.Ready, result = next.result, error = null,
                ) else old.copy(status = HomeFeedSourceStatus.Failed, error = next.error)
                latest = publish()
            }
            latest
        } finally { completions.close() }
    }

    private data class Completion(val index: Int, val result: HomeFeedResult? = null, val error: Throwable? = null)
}
