package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One immutable account for an entire multi-request user operation. Never persisted or logged. */
internal class AppleAuthorizationScope(val token: String?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AppleAuthorizationScope>
}

internal fun appleAuthorizationCacheScope(token: String?): String = token?.takeIf(String::isNotBlank)?.let {
    MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
} ?: "guest"

internal suspend fun scopedAppleToken(current: () -> String?): String? {
    val captured = currentCoroutineContext()[AppleAuthorizationScope] ?: return current()
    captured.checkCurrent(current)
    return captured.token
}

internal fun AppleAuthorizationScope.checkCurrent(current: () -> String?) {
    if (current() != token) throw CancellationException("Apple account changed")
}
