package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class ContentCacheSettings(
    val enabled: Boolean = true,
    val prefetchNextTwo: Boolean = true,
    val clearing: Boolean = false,
    val revision: Long = 0L,
)

data class ContentCacheStorage(
    val usedBytes: Long,
    val availableBytes: Long,
    val reserveBytes: Long,
) {
    val storageLow: Boolean get() = availableBytes <= reserveBytes + ContentCache.WRITE_HEADROOM_BYTES
}

/** Private app assets only. MusicKit's protected audio is never downloaded by this cache. */
class ContentCache private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("content-cache", Context.MODE_PRIVATE)
    private val lyricsDirectory = File(appContext.filesDir, "lyrics-cache-v1")
    private val mutations = Mutex()
    private val clearOperation = Mutex()
    private val epoch = AtomicLong()
    private val mutableSettings = MutableStateFlow(ContentCacheSettings(
        enabled = preferences.getBoolean("enabled", true),
        prefetchNextTwo = preferences.getBoolean("prefetch-next-two", true),
    ))
    val settings = mutableSettings.asStateFlow()
    val generation: Long get() = epoch.get()
    internal val metadataDirectory = File(appContext.cacheDir, "catalog-feeds-v1")
    internal val metadataMutations = Mutex()

    // The write gate, rather than an arbitrary small LRU, leaves room for the OS.
    // Bound disk cache to at most 256MB to avoid consuming storage on small TV flash memory.
    val artworkCapacityBytes: Long
        get() = minOf(256L * 1024 * 1024, (appContext.filesDir.totalSpace / 20).coerceAtLeast(64L * 1024 * 1024))
    private val reserveBytes: Long
        get() = maxOf(256L * 1024 * 1024, appContext.filesDir.totalSpace / 20)

    fun setEnabled(enabled: Boolean) {
        val revision = epoch.incrementAndGet()
        preferences.edit().putBoolean("enabled", enabled).apply()
        mutableSettings.update { it.copy(enabled = enabled, revision = maxOf(it.revision, revision)) }
    }

    fun setPrefetch(enabled: Boolean) {
        preferences.edit().putBoolean("prefetch-next-two", enabled).apply()
        mutableSettings.update { it.copy(prefetchNextTwo = enabled) }
    }

    /** Call on IO; keeping a reserve also bounds concurrent artwork downloads. */
    fun canWrite(bytes: Long = WRITE_HEADROOM_BYTES): Boolean = settings.value.let {
        it.enabled && !it.clearing && appContext.filesDir.usableSpace - bytes.coerceAtLeast(0L) >= reserveBytes
    }

    suspend fun readLyrics(key: String): String? = withContext(Dispatchers.IO) {
        mutations.withLock {
            if (!settings.value.enabled || settings.value.clearing) return@withLock null
            val file = lyricFile(key)
            if (!file.isFile || file.length() !in 1..MAX_LYRICS_BYTES) return@withLock null
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        }
    }

    suspend fun removeLyrics(key: String) = withContext(Dispatchers.IO) {
        mutations.withLock { lyricFile(key).delete(); Unit }
    }

    suspend fun writeLyrics(key: String, ttml: String, requestGeneration: Long) = withContext(Dispatchers.IO) {
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_LYRICS_BYTES) return@withContext
        mutations.withLock {
            if (requestGeneration != generation || !canWrite(bytes.size.toLong())) return@withLock
            // A cache miss/write failure must never fail playback or lyrics loading.
            runCatching {
                if (!lyricsDirectory.isDirectory && !lyricsDirectory.mkdirs()) return@runCatching
                val file = lyricFile(key)
                val temporary = File(lyricsDirectory, "${file.name}.pending")
                try {
                    FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
                    if (!temporary.renameTo(file)) temporary.delete()
                } finally { temporary.delete() }
            }
        }
    }

    /** In-flight responses from before clearing/disabling may not repopulate the cache. */
    suspend fun clear(clearArtwork: suspend () -> Unit) = withContext(NonCancellable + Dispatchers.IO) {
        clearOperation.withLock {
            mutations.withLock {
                val revision = epoch.incrementAndGet()
                mutableSettings.update { it.copy(clearing = true, revision = maxOf(it.revision, revision)) }
            }
            try {
                clearArtwork()
                metadataMutations.withLock {
                    metadataDirectory.listFiles()?.forEach { file ->
                        if (file.isFile && !file.delete()) error(AppText.text(R.string.services_cache_clear_failed))
                    }
                }
                mutations.withLock {
                    lyricsDirectory.listFiles()?.forEach { file ->
                        if (file.isFile && !file.delete()) error(AppText.text(R.string.services_cache_clear_failed))
                    }
                }
            } finally {
                mutations.withLock {
                    val revision = epoch.incrementAndGet()
                    mutableSettings.update { it.copy(clearing = false, revision = maxOf(it.revision, revision)) }
                }
            }
        }
    }

    suspend fun storage(artworkBytes: Long): ContentCacheStorage = withContext(Dispatchers.IO) {
        val lyricsBytes = lyricsDirectory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val metadataBytes = metadataMutations.withLock {
            metadataDirectory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }
        ContentCacheStorage(lyricsBytes + artworkBytes + metadataBytes, appContext.filesDir.usableSpace, reserveBytes)
    }

    private fun lyricFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(lyricsDirectory, digest.joinToString("") { "%02x".format(it) } + ".ttml")
    }

    companion object {
        const val WRITE_HEADROOM_BYTES = 16L * 1024 * 1024
        private const val MAX_LYRICS_BYTES = 4L * 1024 * 1024
        @Volatile private var instance: ContentCache? = null
        fun get(context: Context): ContentCache = instance ?: synchronized(this) {
            instance ?: ContentCache(context).also { instance = it }
        }
    }
}
