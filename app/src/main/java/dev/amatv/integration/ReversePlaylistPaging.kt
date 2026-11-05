package dev.amatv.integration

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.net.URI

/** Local continuation only: these markers are never sent to Apple. */
internal data class ReversePlaylistCursor(val endpoint: String, val total: Int, val end: Int) {
    init { require(total >= 0 && end in 0..total && endpoint.startsWith("/v1/")) }
    val encoded: String get() = "$PREFIX$total:$end:$endpoint"
    val firstPage: String get() = copy(end = total).encoded

    companion object {
        private const val PREFIX = "amatv-playlist-reverse:1:"
        fun parse(value: String?): ReversePlaylistCursor? {
            if (value == null || !value.startsWith(PREFIX)) return null
            val parts = value.removePrefix(PREFIX).split(':', limit = 3)
            require(parts.size == 3) { "Invalid reverse playlist continuation" }
            return ReversePlaylistCursor(parts[2], parts[0].toInt(), parts[1].toInt())
        }
    }
}

/**
 * Read from the raw relationship's tail, not from a reversed loaded prefix.
 * Raw ranges include unsupported resources, preserving repeated occurrences.
 */
internal suspend fun reversePlaylistPage(
    request: CollectionRequest,
    nextPage: String?,
    load: suspend (String?) -> LibraryPage,
): LibraryPage = withCatalogRequestDeadline {
    val saved = ReversePlaylistCursor.parse(nextPage)
    if (saved != null) {
        require(saved.endpoint == request.endpoint && saved.end > 0) { "Reverse playlist source changed" }
        return@withCatalogRequestDeadline loadReverseRange(request, saved, load)
    }
    require(nextPage == null)
    val first = load(null)
    val count = checkNotNull(first.rawItemCount)
    val total = first.total
    if (first.nextPage == null) {
        check(total == null || total == count) { "Incomplete playlist relationship" }
        return@withCatalogRequestDeadline reversedPage(first, ReversePlaylistCursor(request.endpoint, count, count), 0)
    }
    if (total != null) {
        check(total > count) { "Playlist total changed" }
        return@withCatalogRequestDeadline loadReverseRange(request, ReversePlaylistCursor(request.endpoint, total, total), load)
    }

    // Missing totals require counting the relationship, not retaining all of
    // its metadata. This whole scan shares the same bounded request deadline.
    val visited = mutableSetOf(first.requestedPage)
    var cursor = first.nextPage
    var rawCount = count.toLong()
    while (cursor != null) {
        currentCoroutineContext().ensureActive()
        check(visited.size < 512) { "Playlist relationship exceeds continuation limit" }
        val page = load(cursor)
        check(visited.add(page.requestedPage) && (page.nextPage == null || page.nextPage !in visited)) {
            "Repeated playlist continuation"
        }
        rawCount += checkNotNull(page.rawItemCount)
        check(rawCount <= Int.MAX_VALUE)
        page.total?.let { reported ->
            check(reported >= rawCount && (page.nextPage != null || reported == rawCount.toInt())) {
                "Playlist total changed"
            }
            return@withCatalogRequestDeadline loadReverseRange(request, ReversePlaylistCursor(request.endpoint, reported, reported), load)
        }
        cursor = page.nextPage
        yield()
    }
    val counted = ReversePlaylistCursor(request.endpoint, rawCount.toInt(), rawCount.toInt())
    if (rawCount == 0L) reversedPage(first, counted, 0) else loadReverseRange(request, counted, load)
}

private suspend fun loadReverseRange(
    request: CollectionRequest,
    cursor: ReversePlaylistCursor,
    load: suspend (String?) -> LibraryPage,
): LibraryPage {
    val count = minOf(checkNotNull(request.pageSize), cursor.end)
    val start = cursor.end - count
    val page = load(request.range(start, count))
    check(page.rawItemCount == count && (page.total == null || page.total == cursor.total)) {
        "Playlist changed during reverse pagination"
    }
    // A capped/ignored offset must fail rather than skip or replay occurrences.
    val nextOffset = page.nextPage?.let { path ->
        URI(path).rawQuery.orEmpty().split('&').singleOrNull { it.substringBefore('=') == "offset" }
            ?.substringAfter('=')?.toIntOrNull()
    }
    check(if (cursor.end == cursor.total) page.nextPage == null else nextOffset == cursor.end) {
        "Playlist range could not be verified"
    }
    return reversedPage(page, cursor, start)
}

private fun reversedPage(page: LibraryPage, cursor: ReversePlaylistCursor, start: Int): LibraryPage {
    val count = checkNotNull(page.rawItemCount)
    check(page.itemRawOffsets.size == page.items.size &&
        page.itemRawOffsets.all { it in 0 until count } &&
        page.itemRawOffsets.zipWithNext().all { (a, b) -> a < b })
    return page.copy(items = page.items.asReversed(),
        requestedPage = cursor.encoded,
        nextPage = if (start > 0) cursor.copy(end = start).encoded else null,
        total = cursor.total,
        itemRawOffsets = page.itemRawOffsets.asReversed().map { count - 1 - it })
}
