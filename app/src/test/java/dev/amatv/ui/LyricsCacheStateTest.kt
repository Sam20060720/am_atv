package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import dev.amatv.integration.LyricsLocalizationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCacheStateTest {
    private val baseline = listOf(LyricLine(listOf(LyricWord("Original", 0L, 1_000L))))

    @Test fun clearingCacheDuringLocalizationKeepsVisibleTextButDoesNotResolveThePendingVariant() {
        val loading = ActiveLyricsState("song", baseline, phase = LyricsLoadPhase.Loading)
        assertTrue(loading.resolvedCacheEntries().isEmpty())
        assertEquals(baseline, loading.lines)
        assertEquals(LyricsLoadPhase.Loading, loading.phase)
    }

    @Test fun completedLocalizationSurvivesOnlyUnderItsActualVariantKey() {
        val completed = ActiveLyricsState("song", baseline, "zh-TW|translation", LyricsLoadPhase.Ready)
        assertEquals(mapOf("song|zh-TW|translation" to baseline), completed.resolvedCacheEntries())
        assertTrue(completed.copy(resolvedVariant = null).resolvedCacheEntries().isEmpty())
        assertTrue(completed.copy(phase = LyricsLoadPhase.Failed).resolvedCacheEntries().isEmpty())
    }

    private val localized = listOf(LyricLine(
        words = listOf(LyricWord("君", 1_000L, 2_000L, pronunciationText = "kimi")),
        translation = "You",
        pronunciationText = "kimi",
    ))
    private val both = LyricsLocalizationRequest("en-US", "en-US", true, true)

    @Test fun disablingTranslationProjectsReadyPronunciationWithoutANetworkResult() {
        val ready = ActiveLyricsState("song", localized, both.variantKey, LyricsLoadPhase.Ready)
        val requested = both.copy(includeTranslations = false)
        val projected = requireNotNull(ready.projectResolvedVariant("song", requested))
        assertEquals(LyricsLoadPhase.Ready, projected.phase)
        assertEquals(requested.variantKey, projected.resolvedVariant)
        assertNull(projected.lines.single().translation)
        assertEquals("kimi", projected.lines.single().pronunciationText)
        assertEquals("kimi", projected.lines.single().words.single().pronunciationText)
        assertEquals(mapOf(lyricsCacheKey("song", requested.variantKey) to projected.lines), projected.resolvedCacheEntries())
    }

    @Test fun changingTranslationKeepsOnlyMatchingPronunciationDuringLoadingAndFailure() {
        val ready = ActiveLyricsState("song", localized, both.variantKey, LyricsLoadPhase.Ready)
        val requested = both.copy(languageTag = "zh-TW")
        assertNull(ready.projectResolvedVariant("song", requested))
        val pending = ready.pendingVariant("song", requested)
        assertEquals(LyricsLoadPhase.Loading, pending.phase)
        assertNull(pending.lines.single().translation)
        assertEquals("kimi", pending.lines.single().words.single().pronunciationText)
        assertFalse(pending.translationReadyFor(requested))
        assertTrue(pending.pronunciationReadyFor(requested))
        assertTrue(pending.resolvedCacheEntries().isEmpty())
        val failed = pending.copy(phase = LyricsLoadPhase.Failed)
        assertNull(failed.projectResolvedVariant("song", requested))
        assertEquals(LyricsLoadPhase.Ready,
            failed.projectResolvedVariant("song", requested.copy(includeTranslations = false))?.phase)
    }

    @Test fun changingSongOrPronunciationLanguageCannotReuseAnOldOverlay() {
        val ready = ActiveLyricsState("song", localized, both.variantKey, LyricsLoadPhase.Ready)
        val requested = both.copy(pronunciationLanguageTag = "ja-JP")
        assertNull(ready.projectResolvedVariant("other-song", both))
        assertTrue(ready.pendingVariant("other-song", both).lines.isEmpty())
        val pending = ready.pendingVariant("song", requested)
        assertEquals("You", pending.lines.single().translation)
        assertNull(pending.lines.single().pronunciationText)
        assertNull(pending.lines.single().words.single().pronunciationText)
        assertTrue(pending.translationReadyFor(requested))
        assertFalse(pending.pronunciationReadyFor(requested))
    }
}
