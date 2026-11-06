package dev.amatv.integration.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.extractor.mp4.PsshAtomUtil
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Maps segment ranges to public content IDs. No license or content key is parsed here. */
@UnstableApi
internal class HlsContentIdResolver {
    private data class SegmentId(val uri: Uri, val start: Long, val length: Long, val id: ByteArray)
    private val playlists = ConcurrentHashMap<String, List<SegmentId>>()
    private val loadingId = ThreadLocal<ByteArray?>()

    fun currentId(): ByteArray? = loadingId.get()

    fun playlistFactory(): HlsPlaylistParserFactory {
        val original = DefaultHlsPlaylistParserFactory()
        fun observe(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            parser.parse(uri, input).also { playlist ->
                if (playlist is HlsMediaPlaylist) {
                    playlists[playlist.baseUri] = playlist.segments.mapNotNull { segment ->
                        val id = WidevineContentIds.fromDrmData(segment.drmInitData).singleOrNull()
                            ?: return@mapNotNull null
                        SegmentId(UriUtil.resolveToUri(playlist.baseUri, segment.url),
                            segment.byteRangeOffset, segment.byteRangeLength, id)
                    }
                }
            }
        }
        return object : HlsPlaylistParserFactory {
            override fun createPlaylistParser() = observe(original.createPlaylistParser())
            override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist,
                previousMediaPlaylist: HlsMediaPlaylist?) =
                observe(original.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))
        }
    }

    fun dataSourceFactory(original: DataSource.Factory): HlsDataSourceFactory = HlsDataSourceFactory { type ->
        val source = original.createDataSource()
        if (type != C.DATA_TYPE_MEDIA) source else object : DataSource by source {
            override fun open(dataSpec: DataSpec): Long {
                loadingId.remove()
                val result = source.open(dataSpec)
                // Loading and extraction run on the same loader thread. Snapshot the ID when
                // emitting metadata, so buffering ahead cannot change an earlier sample's ID.
                val candidates = playlists.values.asSequence().flatten().filter { segment ->
                    segment.uri == dataSpec.uri && dataSpec.position >= segment.start &&
                        (segment.length == C.LENGTH_UNSET.toLong() || dataSpec.position - segment.start < segment.length)
                }.map { it.id }.distinctBy { it.toList() }.toList()
                loadingId.set(candidates.singleOrNull())
                return result
            }

            override fun close() {
                try { source.close() } finally { loadingId.remove() }
            }
        }
    }
}

/** Reads standard PSSH identifiers, not encryption keys or license payloads. */
@UnstableApi
internal object WidevineContentIds {
    fun fromDrmData(drm: DrmInitData?): List<ByteArray> {
        if (drm == null) return emptyList()
        return (0 until drm.schemeDataCount).flatMap { index ->
            val scheme = drm[index]
            if (scheme.uuid != C.WIDEVINE_UUID) emptyList()
            else scheme.data?.let(::fromPssh).orEmpty()
        }.distinctBy { it.toList() }
    }

    internal fun fromPssh(data: ByteArray): List<ByteArray> {
        val atom = PsshAtomUtil.parsePsshAtom(data) ?: return emptyList()
        if (atom.uuid != C.WIDEVINE_UUID) return emptyList()
        val declared = atom.keyIds?.map { id ->
            ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
        }.orEmpty()
        if (declared.isNotEmpty()) return declared
        return fromWidevineData(atom.schemeData)
    }

    // WidevinePsshData.key_id is repeated bytes field 2 in the public protobuf schema.
    internal fun fromWidevineData(data: ByteArray): List<ByteArray> = runCatching {
        require(data.size <= 65_536)
        var position = 0
        fun number(): Long {
            var result = 0L
            var shift = 0
            while (position < data.size && shift < 64) {
                val value = data[position++].toInt() and 255
                result = result or ((value and 127).toLong() shl shift)
                if (value and 128 == 0) return result
                shift += 7
            }
            error("Invalid PSSH metadata")
        }
        val ids = mutableListOf<ByteArray>()
        while (position < data.size) {
            val tag = number()
            when ((tag and 7).toInt()) {
                0 -> number()
                1 -> { require(data.size - position >= 8); position += 8 }
                2 -> {
                    val length = number()
                    require(length >= 0 && length <= data.size - position)
                    if (tag ushr 3 == 2L && length == 16L)
                        ids += data.copyOfRange(position, position + 16)
                    position += length.toInt()
                }
                5 -> { require(data.size - position >= 4); position += 4 }
                else -> error("Unsupported PSSH metadata")
            }
        }
        ids.distinctBy { it.toList() }
    }.getOrDefault(emptyList())
}
