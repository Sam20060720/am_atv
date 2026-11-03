package dev.amatv.ui

import dev.amatv.integration.editorialMotionVariants
import dev.amatv.integration.toSessionJson
import dev.amatv.integration.toSessionTrack
import dev.amatv.model.EditorialMotionAsset
import dev.amatv.model.Track
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MotionArtworkPolicyTest {
    @Test fun stillUsesTheChosenVideoLayoutRatherThanTheDifferentCatalogCover() {
        val square = EditorialMotionAsset("square", "https://example.test/square.m3u8", "https://example.test/square-poster.jpg", 600, 600)
        val tall = EditorialMotionAsset("tall", "https://example.test/tall.m3u8", "https://example.test/tall-poster.jpg", 600, 800)
        val source = track().copy(artworkUrl = "https://example.test/catalog-two-line.jpg",
            motionPreviewUrl = tall.previewUrl, motionArtworkVariants = listOf(tall, square))
        assertEquals(square.previewUrl, source.posterForAspect(1f))
        assertEquals(tall.previewUrl, source.posterForAspect(.75f))
        assertEquals(source.artworkUrl, source.copy(motionArtworkVariants = emptyList(), motionPreviewUrl = null).posterForAspect())
    }

    private fun track() = Track("artist", "Artist", "Artist", "", 0, emptyList(), emptyList())

    @Test fun optionalFocusedArtworkCanOnlyChangeMotionOfTheSameResource() {
        val lyrics = listOf(LyricLine(listOf(LyricWord("Keep", 0, 1_000))))
        val original = track().copy(catalogKind = CatalogKind.Album, lyrics = lyrics, playbackQueueId = 42L)
        assertTrue(original.canLoadEditorialArtwork())
        assertFalse(original.copy(catalogKind = CatalogKind.Song).canLoadEditorialArtwork())
        val loaded = original.copy(title = "Wrong label", lyrics = emptyList(), playbackQueueId = null,
            motionArtworkUrl = "https://example.test/motion.m3u8")
        val result = original.withEditorialMotionFrom(loaded)
        assertEquals(original, result.copy(motionArtworkUrl = null))
        assertSame(original, original.withEditorialMotionFrom(loaded.copy(id = "other-album")))
        assertFalse(result.canLoadEditorialArtwork())
    }

    @Test fun actualArtistShapesRemainAvailableAndTemplateCropIsExpanded() {
        val assets = JSONObject("""{"editorialVideo":{
          "motionArtistFullscreen16x9":{"video":"https://example.test/wide.m3u8","previewFrame":{"width":1920,"height":1080}},
          "motionArtistSquare1x1":{"video":"https://example.test/square.m3u8","previewFrame":{
            "width":360,"height":360,"url":"https://example.test/image/{w}x{h}{c}.{f}"}}}}""")
            .editorialMotionVariants(artist = true)
        assertEquals("motionArtistFullscreen16x9", assets.first().flavor)
        val source = track().copy(motionArtworkUrl = assets.first().videoUrl, motionArtworkVariants = assets)
        val square = checkNotNull(source.motionForAspect(1f))
        assertEquals("https://example.test/square.m3u8", square.videoUrl)
        assertEquals("https://example.test/image/600x600bb.jpg", square.previewUrl)
        assertEquals("https://example.test/wide.m3u8", source.motionForAspect(16f / 9f)?.videoUrl)
    }

    @Test fun normalTvTilesAdmitTheOfficialLowestRenditionsWithoutRemovingTheCeiling() {
        val smallSquare = motionDecodeBounds(124f * 2 * 1.25f, 124f * 2 * 1.25f, 1f)
        assertEquals(MotionDecodeBounds(360, 360), smallSquare)
        val wideInSmallHero = motionDecodeBounds(144f * 2 * 1.25f, 144f * 2 * 1.25f, 16f / 9f)
        assertTrue(wideInSmallHero.width >= 480 && wideInSmallHero.height >= 270)
        val enormous = motionDecodeBounds(8_000f, 8_000f, 21f / 9f)
        assertTrue(enormous.width <= 960 && enormous.height <= 960)
        val legacy = motionDecodeBounds(150f, 150f, null)
        assertTrue(legacy.width >= 480 && legacy.height >= 360)
        assertTrue(legacy.width <= 960 && legacy.height <= 960)
    }

    @Test fun publicWideAndTallPostersPreserveTheirSourceAspect() {
        val assets = JSONObject("""{"wideVideoArtwork":{"dictionary":{
          "motionArtistFullscreen16x9":{"video":"https://example.test/wide.m3u8","previewFrame":{
          "width":3840,"height":2160,"url":"https://example.test/{w}x{h}{c}.{f}"}}}},
          "tallVideoArtwork":{"dictionary":{"motionDetailTall":{"video":"https://example.test/tall.m3u8",
          "previewFrame":{"width":2048,"height":2732,"url":"https://example.test/{w}x{h}{c}.{f}"}}}}}""")
            .editorialMotionVariants(artist = true)
        assertEquals("https://example.test/600x338bb.jpg", assets.first { it.flavor.contains("Fullscreen") }.previewUrl)
        assertEquals("https://example.test/600x800bb.jpg", assets.first { it.flavor == "motionDetailTall" }.previewUrl)
    }

    @Test fun sessionRoundTripPreservesVariantsAndOldSnapshotsRemainReadable() {
        val asset = EditorialMotionAsset("motionArtistSquare1x1", "https://example.test/a.m3u8", "https://example.test/a.jpg", 360, 360)
        val original = track().copy(motionArtworkUrl = asset.videoUrl, motionArtworkVariants = listOf(asset))
        val json = original.toSessionJson()
        assertEquals(original.motionArtworkVariants, json.toSessionTrack().motionArtworkVariants)
        json.remove("motionArtworkVariants")
        val old = json.toSessionTrack()
        assertTrue(old.motionArtworkVariants.isEmpty())
        assertEquals(original.motionArtworkUrl, old.motionArtworkUrl)
    }
}
