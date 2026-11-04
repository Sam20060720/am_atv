package dev.amatv.ui

/** Conflates remote repeat keys without letting a late focus handoff replace the newest intent. */
internal class CollectionSongNavigation(initialIndex: Int = 0) {
    var focusedIndex = initialIndex
        private set
    var requestedIndex = initialIndex
        private set
    private var generation = 0L
    var pending = false
        private set

    fun request(index: Int): Long {
        requestedIndex = index
        // The last song may be retained while focus is on actions/navigation.
        // Matching indices do not establish that its lazy node exists yet.
        pending = true
        return ++generation
    }

    fun nextIndex(actualIndex: Int, delta: Int, lastIndex: Int): Int =
        ((if (pending) requestedIndex else actualIndex) + delta).coerceIn(0, lastIndex)

    fun focused(index: Int) {
        val wasPending = pending
        focusedIndex = index
        // An intermediate placed row is progress, not a new remote request.
        if (!wasPending) requestedIndex = index
        if (requestedIndex == index) pending = false
    }

    fun nextStep(): Int = when {
        requestedIndex > focusedIndex -> focusedIndex + 1
        requestedIndex < focusedIndex -> focusedIndex - 1
        else -> focusedIndex
    }

    fun isCurrent(ticket: Long) = ticket == generation

    fun cancel(actualIndex: Int) {
        generation++
        focusedIndex = actualIndex
        requestedIndex = actualIndex
        pending = false
    }
}

/** Song slots have equal measured heights; metadata above them need not have a fixed height. */
internal fun collectionSongHighlightTop(
    animatedSongIndex: Float,
    anchorLazyIndex: Int,
    anchorOffset: Int,
    viewportStartOffset: Int,
    rowHeight: Float,
    headerCount: Int = 2,
): Float = anchorOffset - viewportStartOffset + (animatedSongIndex + headerCount - anchorLazyIndex) * rowHeight
