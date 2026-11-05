package dev.amatv.ui

import kotlinx.coroutines.channels.Channel
import dev.amatv.model.Track

/** Supplemental URL changes do not create a new card identity. */
internal fun Track.homeCardMountKey(): List<Any?> =
    listOf(id, catalogKind, catalogId, resourceType, resourceId)

/** Small per-style identity history; a tab return is not a cold card visit. */
internal class HomeCardMountHistory(private val limit: Int = 256) {
    // Per-style retention owns only identities, never lazy callbacks or images.
    private val mounted = LinkedHashMap<Any, Unit>(128, .75f, true)
    fun contains(key: Any): Boolean = mounted[key] != null
    fun remember(key: Any) {
        mounted[key] = Unit
        if (mounted.size > limit) mounted.remove(mounted.keys.first())
    }
}

/**
 * One feed-wide budget for expensive card content. Entries exist only while their
 * lazy card slots are attached; mounted and disposed cards leave no queued work.
 */
internal class HomeCardWarmup(private val history: HomeCardMountHistory = HomeCardMountHistory()) {
    internal class Ticket(
        val sequence: Long,
        var priority: (() -> Int)?,
        var mount: (() -> Unit)?,
    )

    private val pending = LinkedHashMap<Long, Ticket>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var nextSequence = 0L
    private var lastMountFrame = Long.MIN_VALUE
    private var closed = false
    private var visibleCards: Set<Any> = emptySet()
    fun wasMounted(key: Any): Boolean = history.contains(key)

    fun rememberMounted(key: Any) {
        if (!closed) history.remember(key)
    }

    /** One viewport sample serves every pending card; no per-ticket lazy scans. */
    fun updateVisibleCards(cards: Set<Any>) {
        if (cards == visibleCards) return
        visibleCards = cards
        notifyPrioritiesChanged()
    }

    /** Lazy stable item keys survive progressive shelf insertions and reordering. */
    fun priority(cardKey: String, isFocused: Boolean): Int = when {
        cardKey !in visibleCards -> 2
        isFocused -> 0
        else -> 1
    }

    val pendingCount: Int get() = pending.size

    fun request(priority: () -> Int, mount: () -> Unit): Ticket {
        val ticket = Ticket(nextSequence++, priority, mount)
        if (closed) {
            release(ticket)
        } else {
            pending[ticket.sequence] = ticket
            wakeup.trySend(Unit)
        }
        return ticket
    }

    fun cancel(ticket: Ticket) {
        val wasPending = pending.remove(ticket.sequence) != null
        release(ticket)
        if (wasPending) notifyPrioritiesChanged()
    }

    /** Visibility and focus can change without registering a new card. */
    fun notifyPrioritiesChanged() {
        if (!closed) wakeup.trySend(Unit)
    }

    suspend fun awaitPending(): Boolean {
        while (!closed && nextEligible() == null) {
            if (wakeup.receiveCatching().isClosed) return false
        }
        return !closed
    }

    /** Dynamic priority lets a new remote destination overtake visible siblings. */
    fun mountNext(frameNanos: Long): Boolean {
        if (closed || frameNanos == lastMountFrame) return false
        val ticket = nextEligible() ?: return false
        pending.remove(ticket.sequence)
        val mount = ticket.mount
        release(ticket)
        if (mount == null) return false
        lastMountFrame = frameNanos
        mount()
        return true
    }

    private fun nextEligible(): Ticket? {
        var best: Ticket? = null
        var bestPriority = Int.MAX_VALUE
        for (ticket in pending.values) {
            val priority = ticket.priority?.invoke() ?: continue
            // An inactive prefetched composition may not consume its permit
            // until a later measure. Do not accumulate ready offscreen cards.
            if (priority <= 1 && priority < bestPriority) {
                best = ticket
                bestPriority = priority
                // No later ticket can outrank the focused card. Preserve FIFO
                // for equal priorities without scanning the remaining feed.
                if (priority == 0) break
            }
        }
        return best
    }

    fun close() {
        closed = true
        pending.values.forEach(::release)
        pending.clear()
        visibleCards = emptySet()
        wakeup.close()
    }

    private fun release(ticket: Ticket) {
        ticket.priority = null
        ticket.mount = null
    }
}
