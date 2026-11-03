package dev.amatv.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Small optional-metadata reads survive a focus visit; never fan out across a shelf. */
internal class EditorialLookupCache<V>(
    private val now: () -> Long = System::currentTimeMillis,
    private val lifetime: (V) -> Long,
) {
    private data class Entry<V>(val value: V, val expires: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val permits = Semaphore(2)
    private val entries = linkedMapOf<String, Entry<V>>()
    private val pending = mutableMapOf<String, Deferred<V>>()
    private val changed = MutableStateFlow(0L)

    /** Mounted cards may restore known metadata without starting any work. */
    fun peek(key: String): V? = cachedEntry(key)?.value

    private fun cachedEntry(key: String): Entry<V>? = synchronized(entries) {
        entries.remove(key)?.takeIf { it.expires > now() }?.also { entries[key] = it }
    }

    suspend fun get(key: String, load: suspend () -> V): V? {
        while (true) {
        val observed = changed.value
        val task = mutex.withLock {
            cachedEntry(key)?.let { return it.value }
            pending[key] ?: run {
                // Two active and two queued reads bound fast remote scrubbing.
                // A still-focused caller waits for room without enqueuing a
                // background job; leaving focus cancels that wait immediately.
                if (pending.size >= 4) return@withLock null
                val created = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val value = permits.withPermit { load() }
                        mutex.withLock {
                            synchronized(entries) {
                                entries[key] = Entry(value, now() + lifetime(value))
                                while (entries.size > 48) entries.remove(entries.keys.first())
                            }
                        }
                        value
                    } finally {
                        mutex.withLock { pending.remove(key); changed.value++ }
                    }
                }
                pending[key] = created
                created.start()
                created
            }
        }
        // Cancelling a mounted view doesn't cancel a shared bounded read. A
        // repeated visit gets its result immediately, including static results.
        if (task != null) return task.await()
        changed.first { it != observed }
        }
    }
}
