package dev.amatv.ui

import dev.amatv.model.ContentShelf
import dev.amatv.model.Track

internal data class ShelfFocusAnchor(val shelfId: String, val itemKey: String?, val itemIndex: Int)
internal fun Track.shelfFocusKey(): String = "${resourceType ?: catalogKind.name}:${resourceId ?: id}"

/** Server completion may insert an earlier shelf; row indices are not identities. */
internal fun resolveShelfFocusAnchor(anchor: ShelfFocusAnchor?, shelves: List<ContentShelf>, sizes: List<Int>): ShelfFocusPosition? {
    anchor ?: return null
    val shelf = shelves.indexOfFirst { it.id == anchor.shelfId }.takeIf { it >= 0 } ?: return null
    if (sizes.getOrElse(shelf) { 0 } <= 0) return null
    val item = anchor.itemKey?.let { key -> shelves[shelf].items.indexOfFirst { it.shelfFocusKey() == key }.takeIf { it >= 0 } }
        ?: anchor.itemIndex.coerceIn(0, sizes[shelf] - 1)
    return ShelfFocusPosition(shelf, item)
}

/** Initial entry prefers real content over a loading/continuation slot. */
internal fun firstShelfEntry(shelves: List<ContentShelf>, sizes: List<Int>): ShelfFocusPosition? {
    val shelf = shelves.indexOfFirst { it.items.isNotEmpty() }.takeIf { it >= 0 }
        ?: sizes.indexOfFirst { it > 0 }.takeIf { it >= 0 }
        ?: return null
    return ShelfFocusPosition(shelf, 0)
}

/** A retained old row, or a card that changed identity at this index, is not a completed root entry. */
internal fun shelfEntryHasFocus(target: ShelfFocusPosition, shelves: List<ContentShelf>, observed: ShelfFocusAnchor?): Boolean {
    val shelf = shelves.getOrNull(target.shelf) ?: return false
    if (observed?.shelfId != shelf.id) return false
    val track = shelf.items.getOrNull(target.item)
    return if (track != null) observed.itemKey == track.shelfFocusKey()
        else observed.itemKey == null && observed.itemIndex == target.item
}
