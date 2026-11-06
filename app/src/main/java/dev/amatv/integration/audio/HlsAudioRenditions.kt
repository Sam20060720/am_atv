package dev.amatv.integration.audio

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser

/** Declared format, not proof of a decoder, a valid license, or the actual audio output. */
internal enum class HlsAudioFormat { Aac, Alac, Ac3, Eac3, Eac3Joc, Unknown }

internal data class HlsAudioRendition(
    /** EXT-X-MEDIA URI when present; otherwise the containing STREAM-INF URI. */
    val url: String,
    val codecs: String,
    val supplementalCodecs: String?,
    /** Parent STREAM-INF bandwidth, which may include video; never an observed audio bitrate. */
    val bandwidth: Long,
    val audioGroupId: String?,
    val channels: String?,
    val name: String?,
    /** Media3's normalized MIME, including its explicit E-AC-3 JOC determination. */
    val audioMimeType: String? = null,
) {
    val format: HlsAudioFormat
        get() = declaredFormat(codecs, supplementalCodecs, channels, audioMimeType)
    val isDolbyAtmos: Boolean get() = format == HlsAudioFormat.Eac3Joc
    val isLossless: Boolean get() = format == HlsAudioFormat.Alac
    val isAac: Boolean get() = format == HlsAudioFormat.Aac
}

/** Media3 owns HLS grammar, URI resolution, variable references and AUDIO-group parsing. */
@UnstableApi
internal object HlsAudioRenditions {
    fun parse(masterUrl: String, body: String): List<HlsAudioRendition> {
        if (httpUrl(masterUrl) == null) return emptyList()
        val playlist = try {
            HlsPlaylistParser().parse(Uri.parse(masterUrl), body.byteInputStream()) as? HlsMultivariantPlaylist
        } catch (_: Exception) {
            // Exception messages may contain signed URLs. The caller records only the count.
            null
        } ?: return emptyList()
        val streamTags = playlist.tags.filter {
            it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
        }
        val mediaTags = playlist.tags.filter { it.startsWith("#EXT-X-MEDIA:") }
        fun mediaTag(group: String?, name: String?) = mediaTags.firstOrNull {
            attribute(it, "TYPE") == "AUDIO" && attribute(it, "GROUP-ID") == group &&
                (name == null || attribute(it, "NAME") == name)
        }
        val result = mutableListOf<HlsAudioRendition>()
        fun add(url: Uri?, format: Format, group: String?, name: String?,
            variant: HlsMultivariantPlaylist.Variant?, tag: String?, stream: String?) {
            val safeUrl = url?.toString()?.let(::httpUrl) ?: return
            result += HlsAudioRendition(
                url = safeUrl,
                codecs = format.codecs ?: variant?.format?.codecs.orEmpty(),
                supplementalCodecs = attribute(tag, "SUPPLEMENTAL-CODECS") ?: attribute(stream, "SUPPLEMENTAL-CODECS"),
                bandwidth = variant?.format?.peakBitrate?.toLong()?.takeIf { it > 0L } ?: Long.MAX_VALUE,
                audioGroupId = group,
                channels = attribute(tag, "CHANNELS") ?: format.channelCount.takeIf { it > 0 }?.toString(),
                name = name,
                audioMimeType = format.sampleMimeType,
            )
        }
        val referencedGroups = mutableSetOf<String>()
        for (variant in playlist.variants) {
            val group = variant.audioGroupId
            if (group != null) referencedGroups += group
            // Media3 retains the original variant index in Format.id when deduplicating URLs.
            val stream = variant.format.id?.toIntOrNull()?.let(streamTags::getOrNull)
            val members = playlist.audios.filter { it.groupId == group }
            if (members.isEmpty()) {
                // Muxed audio is represented by the containing variant URI.
                val tag = mediaTag(group, null)
                add(variant.url, variant.format, group, attribute(tag, "NAME"), variant, tag, stream)
            } else {
                for (audio in members) {
                    add(audio.url, audio.format, audio.groupId, audio.name, variant,
                        mediaTag(audio.groupId, audio.name), stream)
                }
            }
        }
        for (audio in playlist.audios) {
            if (audio.groupId !in referencedGroups) {
                add(audio.url, audio.format, audio.groupId, audio.name, null,
                    mediaTag(audio.groupId, audio.name), null)
            }
        }
        return result.distinct()
    }

    // Preserve only raw diagnostic attributes that Format normalizes or does not expose.
    // The full document and its routing have already been parsed by Media3.
    private val attributes = listOf("TYPE", "GROUP-ID", "NAME", "CHANNELS", "SUPPLEMENTAL-CODECS")
        .associateWith { key -> Regex("(?:^|,)\\s*$key\\s*=\\s*(?:\"([^\"]*)\"|([^,]*))") }
    private fun attribute(tag: String?, key: String): String? = tag?.let {
        attributes.getValue(key).find(it.substringAfter(':'))?.let { match ->
            match.groups[1]?.value ?: match.groups[2]?.value?.trim()
        }
    }

    private fun httpUrl(value: String): String? = runCatching {
        assetHttpsUri(value)?.toString()
    }.getOrNull()
}

private fun declaredFormat(codecs: String, supplementalCodecs: String?, channels: String?, mime: String?): HlsAudioFormat {
    if (mime == "audio/eac3-joc") return HlsAudioFormat.Eac3Joc
    fun tokens(value: String?) = value.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val codecTokens = tokens(codecs)
    val formats = codecTokens.mapNotNull { token ->
        when (token.substringBefore('/')) {
            "alac" -> HlsAudioFormat.Alac
            "ec-3" -> HlsAudioFormat.Eac3
            "ec+3" -> HlsAudioFormat.Eac3Joc // Media3's normalized, nonstandard JOC codec identifier.
            "ac-3" -> HlsAudioFormat.Ac3
            "mp4a.40.1", "mp4a.40.2", "mp4a.40.3", "mp4a.40.4", "mp4a.40.5",
            "mp4a.40.6", "mp4a.40.17", "mp4a.40.19", "mp4a.40.20", "mp4a.40.22",
            "mp4a.40.23", "mp4a.40.29", "mp4a.40.39", "mp4a.40.42",
            "mp4a.66", "mp4a.67", "mp4a.68" -> HlsAudioFormat.Aac
            else -> null
        }
    }.toSet()
    val channelJoc = channels?.split('/')?.drop(1)?.any { it.equals("JOC", ignoreCase = true) } == true
    val codecJoc = (codecTokens + tokens(supplementalCodecs)).any { token ->
        token.substringBefore('/') == "ec-3" && token.split('/').drop(1).any { it == "joc" }
    }
    if (channelJoc || codecJoc) {
        if (formats.isEmpty() || HlsAudioFormat.Eac3 in formats || HlsAudioFormat.Eac3Joc in formats)
            return HlsAudioFormat.Eac3Joc
        return HlsAudioFormat.Unknown
    }
    // A parent's CODECS may cover several AUDIO members. Without individual evidence,
    // do not guess which audio codec a particular member carries.
    return formats.singleOrNull() ?: HlsAudioFormat.Unknown
}
