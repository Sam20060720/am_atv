package dev.amatv.ui

/** Keeps remote intent independent of lazy placement and disappearing empty sections. */
internal class ArtistSectionNavigation(private val canonicalOrder: List<String>) {
    private var visible = emptyList<String>()
    private var direction = 1
    private var generation = 0L
    var focusedKey: String? = null
        private set
    var requestedKey: String? = null
        private set
    var pending = false
        private set

    fun updateVisible(keys: List<String>) {
        visible = canonicalOrder.filter(keys.toSet()::contains)
        if (visible.isEmpty()) {
            cancel(null)
        } else if (pending && requestedKey !in visible) {
            requestedKey = neighbour(requestedKey, direction)
        }
    }

    fun requestNext(actualKey: String?, delta: Int): Long {
        require(delta == -1 || delta == 1)
        direction = delta
        if (!pending) focusedKey = actualKey
        requestedKey = neighbour(if (pending) requestedKey else actualKey, delta)
        pending = requestedKey != null
        return ++generation
    }

    fun repairRemoved(actualKey: String): Long {
        focusedKey = actualKey
        requestedKey = neighbour(actualKey, direction)
        pending = requestedKey != null
        return ++generation
    }

    private fun neighbour(key: String?, delta: Int): String? {
        if (visible.isEmpty()) return null
        val index = visible.indexOf(key)
        if (index >= 0) return visible[(index + delta).coerceIn(0, visible.lastIndex)]
        val old = canonicalOrder.indexOf(key)
        if (old < 0) return visible.first()
        return if (delta > 0) visible.firstOrNull { canonicalOrder.indexOf(it) > old } ?: visible.last()
        else visible.lastOrNull { canonicalOrder.indexOf(it) < old } ?: visible.first()
    }

    /** Advance through adjacent rows so scrolling cannot dispose focus before its successor exists. */
    fun nextStep(): String? {
        val requested = visible.indexOf(requestedKey)
        if (requested < 0) return null
        val focused = visible.indexOf(focusedKey)
        if (focused < 0) return requestedKey
        return visible[when {
            requested > focused -> focused + 1
            requested < focused -> focused - 1
            else -> focused
        }]
    }

    fun focused(key: String) {
        if (key !in visible) return
        focusedKey = key
        if (!pending) requestedKey = key
        if (requestedKey == key) pending = false
    }

    fun isCurrent(ticket: Long) = ticket == generation

    fun cancel(actualKey: String?) {
        generation++
        focusedKey = actualKey
        requestedKey = actualKey
        pending = false
    }

    companion object { const val Profile = "@profile" }
}
