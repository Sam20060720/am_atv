package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class LyricDisplayDeduplicationTest {
    @Test fun englishOriginalDoesNotGainASecondRowFromPronunciationSpans() {
        val words = listOf(
            LyricWord("Stay", 1_000, 1_500, pronunciationText = "Stay", pronunciationEndMs = 1_500),
            LyricWord("with", 1_500, 2_000, pronunciationText = "with"),
            LyricWord("me", 2_000, 2_500, pronunciationText = "me"),
        )
        val original = LyricLine(words, translation = "留在我身邊", pronunciationText = "Stay with me",
            pronunciationWords = words, timing = LyricTiming.Word, sourceKey = "L1")

        val shown = original.forDisplay(true, true).withoutRedundantLocalization()

        assertNull(shown.pronunciationText)
        assertTrue(shown.pronunciationWords.isEmpty())
        assertTrue(shown.words.all { it.pronunciationText == null && it.pronunciationEndMs == null })
        assertEquals(original.translation, shown.translation)
        assertEquals(original.startMs, shown.startMs)
        assertEquals(original.endMs, shown.endMs)
        assertEquals(original.sourceKey, shown.sourceKey)
        assertEquals("Stay", original.words.first().pronunciationText) // Raw material survives toggles.
    }

    @Test fun typographicQuotesAndInvisibleSpacesDoNotProduceAnExtraTranslation() {
        val original = LyricLine(listOf(LyricWord("Say “hello”", 0, 1_000)),
            translation = "  Say\u00a0\"hello\"\u200b ", pronunciationText = "Say \"hello\"")

        val shown = original.forDisplay(true, true).withoutRedundantLocalization()

        assertNull(shown.translation)
        assertNull(shown.pronunciationText)
        assertEquals(original.text, shown.text)
        assertEquals(estimateLyricRowHeight(original.copy(translation = null, pronunciationText = null), 800f, 1f, 1f),
            estimateLyricRowHeight(shown, 800f, 1f, 1f), 0f)
    }

    @Test fun identicalAuxiliaryRowsKeepTranslationButPronunciationReturnsWhenTranslationIsDisabled() {
        val original = LyricLine(listOf(LyricWord("안녕", 0, 1_000, pronunciationText = "Hello")),
            translation = "Hello", pronunciationText = "Hello")

        val both = original.forDisplay(true, true).withoutRedundantLocalization()
        assertEquals("Hello", both.translation)
        assertNull(both.pronunciationText)
        assertNull(both.words.single().pronunciationText)

        // Rendering must not erase a still-requested raw overlay in the cache.
        val cached = original.forDisplay(true, true)
        assertEquals("Hello", cached.pronunciationText)
        val pronunciationOnly = cached.forDisplay(false, true).withoutRedundantLocalization()
        assertNull(pronunciationOnly.translation)
        assertEquals("Hello", pronunciationOnly.pronunciationText)
        assertEquals("Hello", pronunciationOnly.words.single().pronunciationText)
    }

    @Test fun mixedLanguageLineKeepsUsefulRomanizationAndRemovesOnlyRepeatedEnglishWords() {
        val original = LyricLine(listOf(
            LyricWord("안녕", 0, 500, pronunciationText = "annyeong"),
            LyricWord("hello", 500, 1_000, pronunciationText = "hello"),
        ), translation = "你好", pronunciationText = "annyeong hello")

        val shown = original.forDisplay(true, true).withoutRedundantLocalization()

        assertEquals("annyeong", shown.words.first().pronunciationText)
        assertNull(shown.words.last().pronunciationText)
        assertEquals("annyeong hello", shown.pronunciationText)
        assertEquals("你好", shown.translation)
    }

    @Test fun backingVocalsUseTheirOwnOriginalAndKeepTheirTimingAndSinger() {
        val original = LyricLine(listOf(LyricWord("Lead", 1_000, 2_000)),
            backgroundWords = listOf(LyricWord("Echo", 1_500, 2_500, isBackgroundVocal = true, pronunciationText = "Echo")),
            backgroundTranslation = "Echo", backgroundPronunciationText = "(Echo)",
            backgroundPronunciationWords = listOf(LyricWord("Echo", 1_500, 2_500)),
            pronunciationText = "Echo", agentId = "duet", isMultiAgentSong = true)

        val shown = original.forDisplay(true, true).withoutRedundantLocalization()

        assertNull(shown.backgroundTranslation)
        assertNull(shown.backgroundPronunciationText)
        assertTrue(shown.backgroundPronunciationWords.isEmpty())
        assertNull(shown.backgroundWords.single().pronunciationText)
        assertEquals("Echo", shown.pronunciationText) // A different vocal lane is not a duplicate.
        assertEquals(original.backgroundText, shown.backgroundText)
        assertEquals(1_500L, shown.backgroundWords.single().startMs)
        assertTrue(shown.backgroundWords.single().isBackgroundVocal)
        assertEquals(original.agentId, shown.agentId)
    }

    @Test fun chorusAtDifferentTimestampsRemainsTwoSeparateLyricLines() {
        val lines = listOf(1_000L, 10_000L).map { start ->
            LyricLine(listOf(LyricWord("Again", start, start + 1_000)), pronunciationText = "Again")
        }

        val shown = lines.map { it.forDisplay(true, true).withoutRedundantLocalization() }

        assertEquals(2, shown.size)
        assertEquals(listOf(1_000L, 10_000L), shown.map { it.startMs })
        assertTrue(shown.all { it.text == "Again" && it.pronunciationText == null })
    }

    @Test fun differentAccentOrActualTranslationIsNotFuzzilyCollapsed() {
        val original = LyricLine(listOf(LyricWord("resume", 0, 1_000)),
            translation = "繼續", pronunciationText = "résumé")

        val shown = original.forDisplay(true, true).withoutRedundantLocalization()

        assertEquals("résumé", shown.pronunciationText)
        assertEquals("繼續", shown.translation)
    }
}
