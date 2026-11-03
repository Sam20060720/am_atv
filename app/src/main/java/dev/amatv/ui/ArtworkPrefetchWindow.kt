package dev.amatv.ui

/** Visible entries first; bounded neighbors support reversal without walking a whole library. */
internal fun artworkPrefetchIndices(
    itemCount: Int,
    visibleIndices: List<Int>,
    itemIndexOffset: Int = 0,
    limit: Int = 8,
): List<Int> {
    if (itemCount <= 0 || visibleIndices.isEmpty() || limit <= 0) return emptyList()
    val real = visibleIndices.map { it - itemIndexOffset }.filter { it in 0 until itemCount }.distinct()
    // A metadata-only header can prepare the first two upcoming covers. A
    // footer-only viewport prepares the final two, without treating it as a song.
    if (real.isEmpty()) return when {
        visibleIndices.all { it < itemIndexOffset } -> (0 until minOf(2, itemCount, limit)).toList()
        visibleIndices.all { it >= itemIndexOffset + itemCount } ->
            ((itemCount - minOf(2, itemCount, limit)) until itemCount).toList()
        else -> emptyList()
    }
    val result = LinkedHashSet<Int>()
    fun add(index: Int) { if (result.size < limit && index in 0 until itemCount) result += index }
    real.forEach(::add)
    for (distance in 1..2) {
        add(real.max() + distance)
        add(real.min() - distance)
    }
    return result.toList()
}
