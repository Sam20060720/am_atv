package dev.amatv.integration

/** Bounds retries for routes whose audio effect exists but cannot deliver samples. */
internal class SpectrumCaptureRetry {
    private var session = 0
    private var failures = 0
    private var retryAtMs = 0L

    fun session(id: Int) {
        if (session == id) return
        session = id
        failures = 0
        retryAtMs = 0L
    }

    fun canAttempt(nowMs: Long): Boolean = nowMs >= retryAtMs

    fun failed(nowMs: Long) {
        val delay = 500L shl failures.coerceAtMost(4)
        failures = (failures + 1).coerceAtMost(5)
        retryAtMs = nowMs + delay
    }

    /** A real callback containing silence is still a functioning capture route. */
    fun captured() {
        failures = 0
        retryAtMs = 0L
    }
}

enum class SpectrumCaptureState {
    Disabled,
    PermissionRequired,
    WaitingForSession,
    WaitingForCapture,
    Capturing,
    Unavailable,
}
