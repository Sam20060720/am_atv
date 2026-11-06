package dev.amatv.integration.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput

/** Adapts zero placeholder IDs to the current segment's public ID; ciphertext stays unchanged. */
@UnstableApi
internal class ContentIdExtractorFactory(
    private val currentContentId: () -> ByteArray?,
    private val onEncryptedSample: (TrackOutput.CryptoData, TrackOutput.CryptoData) -> Unit = { _, _ -> },
) : HlsExtractorFactory {
    private val delegate = DefaultHlsExtractorFactory()

    override fun createExtractor(
        uri: Uri, format: Format, muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster, responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput, playerId: PlayerId,
    ): HlsMediaChunkExtractor = wrap(delegate.createExtractor(uri, format, muxedCaptionFormats,
        timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId))

    private fun wrap(extractor: HlsMediaChunkExtractor): HlsMediaChunkExtractor =
        object : HlsMediaChunkExtractor by extractor {
            override fun init(output: ExtractorOutput) {
                extractor.init(object : ExtractorOutput by output {
                    override fun track(id: Int, type: Int): TrackOutput {
                        val target = output.track(id, type)
                        return object : TrackOutput by target {
                            private var previous: TrackOutput.CryptoData? = null
                            private var sourceCrypto: TrackOutput.CryptoData? = null
                            private var replacementId: ByteArray? = null
                            private var outputCrypto: TrackOutput.CryptoData? = null

                            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int,
                                offset: Int, cryptoData: TrackOutput.CryptoData?) {
                                var forwarded = cryptoData
                                if (type == C.TRACK_TYPE_AUDIO && cryptoData != null &&
                                    flags and C.BUFFER_FLAG_ENCRYPTED != 0 &&
                                    cryptoData.encryptionKey.size == 16 && cryptoData.encryptionKey.all { it == 0.toByte() }) {
                                    val id = currentContentId() ?: throw ParserException.createForUnsupportedContainerFeature(
                                        "Missing unambiguous segment content identifier")
                                    if (sourceCrypto != cryptoData || !id.contentEquals(replacementId)) {
                                        sourceCrypto = cryptoData
                                        replacementId = id.copyOf()
                                        outputCrypto = TrackOutput.CryptoData(cryptoData.cryptoMode, replacementId!!,
                                            cryptoData.encryptedBlocks, cryptoData.clearBlocks)
                                    }
                                    forwarded = outputCrypto
                                }
                                if (type == C.TRACK_TYPE_AUDIO && forwarded != null && forwarded != previous) {
                                    previous = forwarded
                                    onEncryptedSample(cryptoData!!, forwarded)
                                }
                                target.sampleMetadata(timeUs, flags, size, offset, forwarded)
                            }
                        }
                    }
                })
            }

            override fun recreate(): HlsMediaChunkExtractor = wrap(extractor.recreate())
        }
}
