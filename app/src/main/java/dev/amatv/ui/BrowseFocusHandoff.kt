package dev.amatv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One requested owner; placement and native focus acknowledge that same visit. */
internal class BrowseFocusHandoff(initialOwner: ContentFocusEntry) {
    data class Request(val owner: ContentFocusEntry, val serial: Long, val pending: Boolean = true)

    var request by mutableStateOf(Request(initialOwner, 0L))
        private set

    fun enter(owner: ContentFocusEntry) {
        request = Request(owner, request.serial + 1L)
    }

    fun isCurrent(attempt: Request): Boolean =
        attempt.owner === request.owner && attempt.serial == request.serial

    fun acknowledge(attempt: Request): Boolean {
        if (!isCurrent(attempt) || !attempt.owner.hasFocusedTarget ||
            attempt.owner.preferredReturnRequester != null) return false
        if (request.pending) request = request.copy(pending = false)
        return true
    }

    /** An actual remote move supersedes an older automatic return. */
    fun claim(owner: ContentFocusEntry): Boolean {
        if (!owner.hasFocusedTarget) return false
        request = Request(owner, request.serial + 1L, pending = false)
        return true
    }
}
