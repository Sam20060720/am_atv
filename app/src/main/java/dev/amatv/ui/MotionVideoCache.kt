package dev.amatv.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.amatv.integration.ContentCache
import dev.amatv.integration.PublicEditorialMotionClient
import java.io.File

/** Only anonymous decorative video bytes; never the protected music player. */
@OptIn(UnstableApi::class)
internal object MotionVideoCache {
    private const val CAPACITY = 96L * 1024 * 1024
    private val lock = Any()
    private var shared: SimpleCache? = null
    private fun directory(context: Context) = File(context.cacheDir, "editorial-motion-video-v1")

    // Called on Media3's loader thread, not composition/DPAD dispatch.
    private fun cache(context: Context): SimpleCache = synchronized(lock) {
        shared ?: SimpleCache(directory(context), LeastRecentlyUsedCacheEvictor(CAPACITY),
            StandaloneDatabaseProvider(context.applicationContext)).also { shared = it }
    }

    fun mediaSource(context: Context, item: MediaItem): MediaSource {
        val app = context.applicationContext
        val factory = DataSource.Factory { LazyDataSource(app, false) }
        return if (Util.inferContentType(checkNotNull(item.localConfiguration).uri) == C.CONTENT_TYPE_HLS) {
            // Playlists remain fresh even when their URL is stable; only video
            // segments and initialization bytes enter the persistent LRU.
            HlsMediaSource.Factory(HlsDataSourceFactory { type -> LazyDataSource(app, type == C.DATA_TYPE_MANIFEST) })
                .setAllowChunklessPreparation(true).createMediaSource(item)
        } else ProgressiveMediaSource.Factory(factory).createMediaSource(item)
    }

    /** Caller is ContentCache.clear's IO block; epoch already invalidated. */
    fun clear(context: Context) {
        synchronized(lock) {
            shared?.let { existing -> existing.keys.toList().forEach(existing::removeResource) }
                ?: directory(context).listFiles()?.forEach { it.deleteRecursively() }
        }
        PublicEditorialMotionClient.clearDiskCache(metadataDirectory(context))
    }

    /** Does not initialize Media3/SQLite merely to show Settings. Call on IO. */
    fun diskBytes(context: Context): Long = synchronized(lock) {
        val video = shared?.cacheSpace ?: directory(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        video + (metadataDirectory(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L)
    }

    fun metadataDirectory(context: Context) = File(context.cacheDir, "editorial-motion-metadata-v1")

    private class LazyDataSource(private val context: Context, private val manifest: Boolean) : DataSource {
        private var source: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()
        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            source?.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val policy = ContentCache.get(context)
            val requestGeneration = policy.generation
            val upstream = DefaultHttpDataSource.Factory().setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000)
            val selected = if (manifest || !policy.settings.value.let { it.enabled && !it.clearing }) {
                upstream.createDataSource()
            } else runCatching {
                val storage = cache(context)
                CacheDataSource.Factory().setCache(storage).setUpstreamDataSourceFactory(upstream)
                    .setCacheWriteDataSinkFactory(if (policy.canWrite()) DataSink.Factory { GuardedSink(storage, policy, requestGeneration) } else null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
            }.getOrElse { upstream.createDataSource() }
            source = selected
            listeners.forEach(selected::addTransferListener)
            return selected.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(source).read(buffer, offset, length)
        override fun getUri(): Uri? = source?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders ?: emptyMap()
        override fun close() { source?.close(); source = null }
    }

    private class GuardedSink(
        private val storage: SimpleCache,
        private val policy: ContentCache,
        private val requestGeneration: Long,
    ) : DataSink {
        private var sink: DataSink? = null
        private var generation = -1L
        private var key: String? = null
        private var stale = false
        private var bytesSinceSpaceCheck = 0L
        override fun open(dataSpec: DataSpec) {
            generation = requestGeneration
            key = dataSpec.key ?: dataSpec.uri.toString()
            if (generation == policy.generation && policy.canWrite()) {
                sink = CacheDataSink.Factory().setCache(storage).setFragmentSize(512L * 1024).createDataSink()
                    .also { it.open(dataSpec) }
            }
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (generation != policy.generation || !policy.settings.value.let { it.enabled && !it.clearing }) {
                stale = true
                val previous = sink
                sink = null
                previous?.close()
                return
            }
            bytesSinceSpaceCheck += length
            if (bytesSinceSpaceCheck >= 512 * 1024L) {
                bytesSinceSpaceCheck = 0
                if (!policy.canWrite()) {
                    val previous = sink
                    sink = null
                    previous?.close()
                    return
                }
            }
            sink?.write(buffer, offset, length)
        }
        override fun close() {
            try { sink?.close() } finally {
                sink = null
                if (stale || generation != policy.generation) key?.let { runCatching { storage.removeResource(it) } }
            }
        }
    }
}
