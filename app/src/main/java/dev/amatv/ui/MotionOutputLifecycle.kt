package dev.amatv.ui

/** Worker-owned proof, independent of the Surface/TextureView that owns the resource. */
internal class MotionOutputLifecycle {
    private enum class Phase { New, Attached, Clearing, Confirmed, Released }
    private var phase = Phase.New
    val released: Boolean get() = phase == Phase.Released

    fun attached() { check(phase == Phase.New); phase = Phase.Attached }
    fun beginClear(): Boolean {
        if (phase != Phase.Attached) return false
        phase = Phase.Clearing
        return true
    }
    fun playbackMarkerDelivered(): Boolean {
        if (phase != Phase.Clearing) return false
        phase = Phase.Confirmed
        return true
    }
    fun playbackThreadTerminated() {
        if (phase == Phase.Attached || phase == Phase.Clearing) phase = Phase.Confirmed
    }
    fun releaseOnce(): Boolean {
        if (phase != Phase.New && phase != Phase.Confirmed) return false
        phase = Phase.Released
        return true
    }
}
