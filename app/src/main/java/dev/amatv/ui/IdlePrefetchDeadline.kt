package dev.amatv.ui

/** One shared idle allowance per actual frame, independent of Android for deterministic tests. */
internal class IdlePrefetchDeadline(private val allowanceNanos: Long = 3_000_000L) {
    init { require(allowanceNanos > 0L) }

    private var lastFrameNanos = Long.MIN_VALUE
    private var firstIdleCallNanos: Long? = null

    fun onFrame(frameTimeNanos: Long) {
        if (frameTimeNanos <= lastFrameNanos) return
        lastFrameNanos = frameTimeNanos
        firstIdleCallNanos = null
    }

    fun cap(availableNanos: Long, nowNanos: Long): Long {
        if (availableNanos != Long.MAX_VALUE) return availableNanos
        // Start only when the scheduler actually runs after drawing. Starting
        // at the animation callback would leave no allowance after a costly draw.
        // This start is shared by every nested request until the next onFrame.
        val first = firstIdleCallNanos ?: nowNanos.also { firstIdleCallNanos = it }
        val elapsed = (nowNanos - first).coerceAtLeast(0L)
        return (allowanceNanos - elapsed).coerceAtLeast(0L)
    }
}
