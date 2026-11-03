package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

/** App occurrence identity is independent of a recording's catalog identifier. */
data class NativeQueueEntry(val entryId: Long, val track: Track)

internal data class NativeQueueObservation(val nativeId: Long, val catalogId: String?)

/** The public SDK returns only FOLLOWING items; its real current item is separate. */
internal fun nativeQueueObservationWindow(
    current: NativeQueueObservation?,
    following: List<NativeQueueObservation>,
): List<NativeQueueObservation> = buildList {
    val seen = mutableSetOf<Long>()
    current?.let { add(it); seen += it.nativeId }
    // Deduplicate native occurrence IDs only. Equal catalog IDs remain separate.
    for (item in following) if (seen.add(item.nativeId)) add(item)
}
internal data class NativeQueueAdoption(val entry: NativeQueueEntry, val changed: Boolean, val rejected: Boolean = false)
internal data class NativeQueueEdit(val removeIds: List<Long>, val append: List<NativeQueueEntry>, val window: List<NativeQueueEntry>)

/**
 * A small public-SDK rail, not a second player or an audio-file cache. Bind native
 * IDs in observed order so duplicate recordings remain distinct occurrences.
 * All calls belong to the MusicKit controller handler.
 */
internal class NativeQueueRail {
    var generation: Long = 0L
        private set
    var current: NativeQueueEntry? = null
        private set
    private var initialEntryId: Long? = null
    private var desired = emptyList<NativeQueueEntry>()
    private var uploaded = emptyList<NativeQueueEntry>()
    private val bindings = linkedMapOf<Long, NativeQueueEntry>()
    private val retired = linkedSetOf<Long>()
    private var observedNativeIds = emptySet<Long>()

    val desiredCount: Int get() = desired.size
    val handedOff: Boolean get() = current?.entryId != null && current?.entryId != initialEntryId
    val plannedTracks: List<Track> get() = listOfNotNull(current?.track) + uploaded.map { it.track }

    fun begin(generation: Long, current: NativeQueueEntry?, upcoming: List<NativeQueueEntry>) {
        this.generation = generation
        this.current = current
        initialEntryId = current?.entryId
        bindings.clear()
        retired.clear()
        observedNativeIds = emptySet()
        uploaded = emptyList()
        desired = clean(upcoming)
    }

    fun updateUpcoming(generation: Long, upcoming: List<NativeQueueEntry>): Boolean {
        if (this.generation != generation || current == null) return false
        desired = clean(upcoming)
        return true
    }

    private fun clean(entries: List<NativeQueueEntry>): List<NativeQueueEntry> = entries.asSequence()
        .filter { it.entryId != current?.entryId && it.entryId !in retired }
        .takeWhile { it.entryId > 0L && it.track.catalogKind == CatalogKind.Song &&
            it.track.catalogId?.matches(Regex("[1-9][0-9]*")) == true }
        .distinctBy { it.entryId }.take(2).toList()

    fun observe(queue: List<NativeQueueObservation>, nativeCurrentId: Long?): NativeQueueAdoption? {
        val original = current ?: return null
        observedNativeIds = queue.mapTo(mutableSetOf()) { it.nativeId }
        val active = queue.firstOrNull { it.nativeId == nativeCurrentId } ?: return null
        val hasCurrentAnchor = queue.any { bindings[it.nativeId]?.entryId == original.entryId }
        if (!hasCurrentAnchor && bindings[active.nativeId] == null && active.catalogId == original.track.catalogId &&
            bindings.values.none { it.entryId == original.entryId }) {
            // This is only the already requested occurrence, never a guessed
            // future match. It also tolerates the SDK replacing its queue IDs.
            bindings[active.nativeId] = original
        }
        // Bind uploaded future occurrences from their last known current anchor.
        val anchor = queue.indexOfFirst { bindings[it.nativeId]?.entryId == original.entryId }
        if (anchor >= 0) {
            var expected = 0
            for (observed in queue.drop(anchor + 1)) {
                val next = uploaded.getOrNull(expected) ?: break
                val known = bindings[observed.nativeId]
                if ((known == null || known.entryId == next.entryId) && observed.catalogId == next.track.catalogId) {
                    bindings[observed.nativeId] = next
                    expected++
                } else break // A reordered/missing resource is not a safe occurrence mapping.
            }
        }
        val mapped = bindings[active.nativeId] ?: return null
        if (mapped.entryId == original.entryId) return NativeQueueAdoption(original, false)
        if (uploaded.firstOrNull()?.entryId != mapped.entryId || desired.firstOrNull()?.entryId != mapped.entryId) {
            return NativeQueueAdoption(mapped, false, rejected = true)
        }
        retired += original.entryId
        while (retired.size > 16) retired.remove(retired.first())
        current = mapped
        uploaded = uploaded.drop(1)
        desired = clean(desired)
        val liveNativeIds = queue.mapTo(mutableSetOf()) { it.nativeId }
        bindings.entries.removeAll { it.key !in liveNativeIds }
        return NativeQueueAdoption(mapped, true)
    }

    fun entryForNativeId(nativeId: Long?): NativeQueueEntry? = nativeId?.let(bindings::get)

    fun hasReadySuccessor(): Boolean {
        val next = desired.firstOrNull() ?: return false
        return uploaded.firstOrNull()?.entryId == next.entryId && bindings.any { (nativeId, entry) -> nativeId in observedNativeIds && entry.entryId == next.entryId }
    }

    fun edit(queue: List<NativeQueueObservation>, nativeCurrentId: Long?): NativeQueueEdit? {
        val activeIndex = queue.indexOfFirst { it.nativeId == nativeCurrentId }
        if (activeIndex < 0 || entryForNativeId(nativeCurrentId)?.entryId != current?.entryId) return null
        val actual = queue.drop(activeIndex + 1)
        val actualEntries = actual.map { entryForNativeId(it.nativeId) ?: return null }
        // An async add which has not appeared yet is still owned by that SDK
        // request. Do not append the same page again or guess its queue IDs.
        if (actualEntries.size != uploaded.size || actualEntries.map { it.entryId } != uploaded.map { it.entryId }) return null
        var common = 0
        while (common < actualEntries.size && common < desired.size &&
            actualEntries[common].entryId == desired[common].entryId) common++
        val remove = queue.take(activeIndex).map { it.nativeId } + actual.drop(common).map { it.nativeId }
        val append = desired.drop(common)
        if (remove.isEmpty() && append.isEmpty()) return null
        return NativeQueueEdit(remove, append, desired.toList())
    }

    fun committed(edit: NativeQueueEdit) { uploaded = edit.window }

    fun reset() = begin(0L, null, emptyList())
}
