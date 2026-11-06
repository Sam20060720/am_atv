package dev.amatv.integration

import dev.amatv.integration.audio.HlsAudioRendition
import org.junit.Assert.*
import org.junit.Test

class AppleAudioAssetSelectionTest {
    private fun rendition(codec: String, bandwidth: Long, channels: String? = null) =
        HlsAudioRendition("https://audio-ssl.itunes.apple.com/fixture.m3u8", codec, null,
            bandwidth, null, channels, null)

    @Test fun efficientAndHighChooseAmongAacWithoutSelectingLargerLosslessOrSurround() {
        val aacLow = rendition("mp4a.40.5", 80_000)
        val aacHigh = rendition("mp4a.40.2", 280_000)
        val source = listOf(rendition("alac", 2_000_000), rendition("ec-3", 700_000), aacHigh, aacLow)
        assertSame(aacLow, chooseAudioRendition(source, AudioQualitySelection(AudioQuality.Efficient), false))
        assertSame(aacHigh, chooseAudioRendition(source, AudioQualitySelection(AudioQuality.High), false))
    }

    @Test fun atmosRequiresBothAnOptInAndActualJocSignaling() {
        val ordinary = rendition("ec-3", 500_000, "6")
        val joc = rendition("ec-3", 700_000, "16/JOC")
        assertNull(chooseAudioRendition(listOf(ordinary, joc), AudioQualitySelection(), true))
        assertNull(chooseAudioRendition(listOf(ordinary), AudioQualitySelection(atmosEnabled = true), true))
        assertSame(joc, chooseAudioRendition(listOf(ordinary, joc), AudioQualitySelection(atmosEnabled = true), true))
    }

    @Test fun unknownBandwidthDoesNotBeatTheDeclaredHighQualityRendition() {
        val known = rendition("mp4a.40.2", 280_000)
        val unknown = rendition("mp4a.40.2", Long.MAX_VALUE)
        assertSame(known, chooseAudioRendition(listOf(unknown, known), AudioQualitySelection(), false))
    }
}
