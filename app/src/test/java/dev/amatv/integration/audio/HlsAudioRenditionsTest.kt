package dev.amatv.integration.audio

import org.junit.Assert.*
import org.junit.Test

/** Test diagnostic quality policy only. Media3 owns and tests the HLS grammar. */
class HlsAudioRenditionsTest {
    private fun rendition(codecs: String, channels: String? = null, supplemental: String? = null,
        mime: String? = null, bandwidth: Long = 312606L, name: String? = null) = HlsAudioRendition(
        url = "https://audio.example.test/fixture.m3u8", codecs = codecs,
        supplementalCodecs = supplemental, bandwidth = bandwidth,
        audioGroupId = "fixture", channels = channels, name = name, audioMimeType = mime,
    )

    @Test fun media3JocMimeAndNormalizedCodecAreReliableAtmosSignals() {
        assertTrue(rendition("ec-3", mime = "audio/eac3-joc").isDolbyAtmos)
        assertTrue(rendition("ec+3").isDolbyAtmos)
        assertTrue(rendition("ec-3", channels = "16/JOC").isDolbyAtmos)
    }

    @Test fun plainEac3MultichannelOrAnAtmosNameAreNotJocEvidence() {
        val ordinary = rendition("ec-3", channels = "6", name = "Dolby Atmos")
        assertEquals(HlsAudioFormat.Eac3, ordinary.format)
        assertFalse(ordinary.isDolbyAtmos)
        assertFalse(rendition("ec-3", channels = "16").isDolbyAtmos)
    }

    @Test fun dolbyVisionSupplementalSignalingDoesNotTurnEac3IntoAtmos() {
        assertEquals(HlsAudioFormat.Eac3,
            rendition("hvc1.2.4.L153.B0,ec-3", supplemental = "dvh1.08.07/db1p").format)
        assertTrue(rendition("ec-3", supplemental = "ec-3/JOC").isDolbyAtmos)
    }

    @Test fun onlyActualAlacDeclarationsAreLosslessCandidates() {
        assertTrue(rendition("alac").isLossless)
        assertFalse(rendition("mp4a.40.2", name = "Lossless").isLossless)
        assertTrue(rendition("mp4a.40.2").isAac)
        assertTrue(rendition("mp4a.40.5").isAac)
        assertEquals(HlsAudioFormat.Unknown, rendition("mp4a.40.34").format)
    }

    @Test fun ambiguousOrContradictoryDeclarationsStayUnknown() {
        assertEquals(HlsAudioFormat.Unknown, rendition("alac,mp4a.40.2").format)
        assertEquals(HlsAudioFormat.Unknown, rendition("alac", channels = "16/JOC").format)
    }

    @Test fun qualitySelectionFiltersBeforeComparingAdvertisedBandwidth() {
        val candidates = listOf(
            rendition("ec-3", bandwidth = 450000L),
            rendition("ec+3", bandwidth = 770672L),
            rendition("mp4a.40.5", bandwidth = 80823L),
            rendition("alac", bandwidth = 1929988L),
        )
        assertEquals("ec+3", candidates.filter { it.isDolbyAtmos }.minByOrNull { it.bandwidth }?.codecs)
        assertEquals("alac", candidates.filter { it.isLossless }.minByOrNull { it.bandwidth }?.codecs)
        assertEquals("mp4a.40.5", candidates.filter { it.isAac }.minByOrNull { it.bandwidth }?.codecs)
    }
}
