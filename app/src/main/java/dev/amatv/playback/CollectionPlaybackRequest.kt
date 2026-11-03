package dev.amatv.playback

import dev.amatv.integration.LibraryPage
import dev.amatv.integration.CollectionShuffleSample
import dev.amatv.integration.collectionOccurrenceKey
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * A ready-to-play ordered prefix, including the selected occurrence. A normal
 * Play hands this off without waiting for the rest of a large collection.
 * A sampled shuffle owns a buffered continuation: no partial tail is published.
 */
internal data class CollectionPlaybackRequest(
    val collection: Track,
    val tracks: List<Track>,
    val startIndex: Int,
    val shuffle: Boolean,
    val continuation: CollectionPlaybackContinuation? = null,
) {
    init {
        require(startIndex in tracks.indices)
        require(!shuffle || continuation == null || continuation.bufferedShuffle)
    }
}

/** A delta, not a replacement queue. Equal recordings remain distinct occurrences. */
internal data class CollectionPlaybackPage(
    val tracks: List<Track>,
    val complete: Boolean,
    val total: Int?,
    val shuffleSourceIndex: Int? = null,
    val shuffleAnchorEntryId: Long? = null,
)

/**
 * Playback owns this cursor after handoff; hiding/closing the browse page must
 * not stop it. Consume it in the playback session's cancellable coroutine and
 * cancel it when that session is replaced. A failed/cancelled page does not
 * advance the cursor, so retry cannot skip songs or append them twice.
 */
internal class CollectionPlaybackContinuation(
    private val collection: Track,
    nextPage: String,
    visitedPages: Set<String>,
    total: Int?,
    private val load: suspend (Track, String?) -> LibraryPage,
) {
    internal class ShuffleBuffer(val selection: CollectionShuffleCheckpoint, val firstPage: String) {
        val songs = mutableListOf<Track>()
        var rawCount = 0
        var selectedSourceIndex: Int? = null
        var anchorEntryId = selection.anchorEntryId
    }
    private data class Cursor(val next: String?, val visited: Set<String>, val total: Int?)
    @Volatile private var savedCursor = Cursor(nextPage, visitedPages.toSet(), total)
    private val mutex = Mutex()
    private var next: String? = nextPage
    private val visited = visitedPages.toMutableSet()
    private var total = total
    @Volatile private var cancelled = false
    private var shuffleBuffer: ShuffleBuffer? = null

    val bufferedShuffle: Boolean get() = shuffleBuffer != null

    fun bindShuffleAnchor(entryId: Long) {
        require(entryId > 0L)
        shuffleBuffer?.let { check(it.anchorEntryId == 0L || it.anchorEntryId == entryId); it.anchorEntryId = entryId }
    }

    fun cancel() { cancelled = true }

    fun checkpoint(authorizationScope: String): CollectionPlaybackCheckpoint? {
        if (cancelled || !authorizationScope.matches(Regex("[a-f0-9]{32}"))) return null
        shuffleBuffer?.let { buffer ->
            if (buffer.anchorEntryId <= 0L) return null
            // The population has not been committed to the queue. Disk recovery
            // must reread it in full, never resume after an unpublished prefix.
            return CollectionPlaybackCheckpoint(collection.copy(lyrics = emptyList(), playbackQueueId = null),
                buffer.firstPage, emptySet(), total, authorizationScope,
                buffer.selection.copy(anchorEntryId = buffer.anchorEntryId))
        }
        val cursor = savedCursor
        val next = cursor.next ?: return null
        return CollectionPlaybackCheckpoint(collection.copy(lyrics = emptyList(), playbackQueueId = null),
            next, cursor.visited, cursor.total, authorizationScope)
    }

    companion object {
        fun restore(checkpoint: CollectionPlaybackCheckpoint, load: suspend (Track, String?) -> LibraryPage) =
            CollectionPlaybackContinuation(checkpoint.collection, checkpoint.nextPage, checkpoint.visitedPages, checkpoint.total, load)
                .also { cursor -> cursor.shuffleBuffer = checkpoint.shuffle?.let { ShuffleBuffer(it, checkpoint.nextPage) } }

        fun sampledShuffle(collection: Track, sample: CollectionShuffleSample, load: suspend (Track, String?) -> LibraryPage): CollectionPlaybackContinuation {
            require(sample.rawIndex in 0 until sample.total)
            return CollectionPlaybackContinuation(collection, sample.firstPage, emptySet(), sample.total, load).also { cursor ->
                cursor.shuffleBuffer = ShuffleBuffer(CollectionShuffleCheckpoint(sample.rawIndex, 0L, collectionOccurrenceKey(checkNotNull(sample.track))), sample.firstPage)
            }
        }
    }

    suspend fun loadNext(): CollectionPlaybackPage? = mutex.withLock {
        ensureCurrent()
        shuffleBuffer?.let { return@withLock loadShufflePopulation(it) }
        val cursor = next ?: return@withLock null
        val page = load(collection, cursor)
        ensureCurrent()
        check(page.requestedPage !in visited && page.nextPage != page.requestedPage &&
            (page.nextPage == null || page.nextPage !in visited)) { "Repeated collection continuation" }
        visited += page.requestedPage
        next = page.nextPage
        total = page.total ?: total
        savedCursor = Cursor(next, visited.toSet(), total)
        CollectionPlaybackPage(page.items.toList(), next == null, total)
    }

    private suspend fun loadShufflePopulation(buffer: ShuffleBuffer): CollectionPlaybackPage? {
        val expectedTotal = checkNotNull(total)
        while (true) {
            val cursor = next ?: return null
            val page = load(collection, cursor)
            ensureCurrent()
            check(page.requestedPage !in visited && page.nextPage != page.requestedPage &&
                (page.nextPage == null || page.nextPage !in visited)) { "Repeated collection continuation" }
            val count = checkNotNull(page.rawItemCount)
            val end = buffer.rawCount.toLong() + count
            check(count >= 0 && end <= expectedTotal && (page.total == null || page.total == expectedTotal) &&
                page.itemRawOffsets.size == page.items.size &&
                page.items.all { it.catalogKind == dev.amatv.model.CatalogKind.Song } &&
                page.itemRawOffsets.zipWithNext().all { (a, b) -> a < b } &&
                page.itemRawOffsets.all { it in 0 until count }) { "Collection changed during shuffle preparation" }
            var selected = buffer.selectedSourceIndex
            if (buffer.selection.rawIndex in buffer.rawCount until end.toInt()) {
                val index = page.itemRawOffsets.indexOf(buffer.selection.rawIndex - buffer.rawCount)
                check(index >= 0 && collectionOccurrenceKey(page.items[index]) == buffer.selection.songKey) {
                    "Selected collection occurrence changed"
                }
                selected = buffer.songs.size + index
            }
            if (page.nextPage == null) check(end.toInt() == expectedTotal && selected != null && buffer.anchorEntryId > 0L) {
                "Incomplete shuffle population"
            }
            // Commit only a fully validated page. A failed page remains retryable.
            buffer.songs += page.items
            buffer.rawCount = end.toInt()
            buffer.selectedSourceIndex = selected
            visited += page.requestedPage
            next = page.nextPage
            if (next == null) {
                val sourceIndex = checkNotNull(selected)
                val result = CollectionPlaybackPage(buffer.songs.filterIndexed { index, _ -> index != sourceIndex }, true, total,
                    sourceIndex, buffer.anchorEntryId)
                buffer.songs.clear()
                return result
            }
            yield()
            ensureCurrent()
        }
    }

    private suspend fun ensureCurrent() {
        currentCoroutineContext().ensureActive()
        if (cancelled) throw CancellationException("Collection playback replaced")
    }
}

/** Bounded cursor metadata stored atomically with its logical playback session. */
data class CollectionPlaybackCheckpoint(
    val collection: Track,
    val nextPage: String,
    val visitedPages: Set<String>,
    val total: Int?,
    val authorizationScope: String,
    val shuffle: CollectionShuffleCheckpoint? = null,
) {
    init {
        require(collection.catalogKind == dev.amatv.model.CatalogKind.Album ||
            collection.catalogKind == dev.amatv.model.CatalogKind.Playlist)
        require(nextPage.isNotBlank() && nextPage.length <= 4096)
        require(visitedPages.size <= 512 && visitedPages.all { it.isNotBlank() && it.length <= 4096 })
        require(visitedPages.sumOf { it.length } <= 65_536)
        require(authorizationScope.matches(Regex("[a-f0-9]{32}")))
        require(shuffle == null || total != null && shuffle.rawIndex in 0 until total &&
            shuffle.anchorEntryId > 0L && visitedPages.isEmpty())
    }
}

/** Zero entry ID is allowed only before runtime handoff, never in an encoded checkpoint. */
data class CollectionShuffleCheckpoint(val rawIndex: Int, val anchorEntryId: Long, val songKey: String) {
    init { require(rawIndex >= 0 && anchorEntryId in 0L until Long.MAX_VALUE && songKey.isNotBlank() && songKey.length <= 8192) }
}
