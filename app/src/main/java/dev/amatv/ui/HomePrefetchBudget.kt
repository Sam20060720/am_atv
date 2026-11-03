package dev.amatv.ui

import android.os.Looper
import android.view.Choreographer

/**
 * Version-checked Foundation 1.11.4 backport for b/531649461. Only its unbounded
 * idle branch calls this helper; normal finite framework deadlines are untouched.
 * Remove with the upstream scheduler fix during the coordinated Compose upgrade.
 */
object HomePrefetchBudget {
    private val deadline = IdlePrefetchDeadline()
    private var frameCallbackPending = false
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPending = false
        deadline.onFrame(frameTimeNanos)
    }

    @JvmStatic
    fun remainingIdleBudgetNanos(): Long {
        // Foundation's Android scheduler runs on the owner View's main thread.
        // If that contract ever changes, decline speculative work on that thread.
        if (Looper.myLooper() !== Looper.getMainLooper()) return 0L
        if (!frameCallbackPending) {
            frameCallbackPending = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        return deadline.cap(Long.MAX_VALUE, System.nanoTime())
    }
}
