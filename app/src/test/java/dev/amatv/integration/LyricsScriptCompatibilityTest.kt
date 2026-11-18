package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsScriptCompatibilityTest {
    private fun requestedScripts(languageTag: String): Set<String> = LyricsLocalizationRequest(
        languageTag = "en-US",
        pronunciationLanguageTag = languageTag,
        includeTranslations = false,
        includePronunciations = true,
    ).queryParameters().getValue("l[script]").split(',').toSet()

    @Test fun legacyIcuRetainsAllEastAsianPronunciationScripts() {
        assertEquals(setOf("ja-Hrkt", "ja-Kana", "ja-Hira", "ja-Hani"), requestedScripts("ja-JP"))
        assertEquals(setOf("ko-Hang", "ko-Hani"), requestedScripts("ko-KR"))
        assertEquals(setOf("zh-Hani", "zh-Bopo"), requestedScripts("zh-TW"))
        assertEquals(setOf("zh-Hani"), requestedScripts("zh-CN"))
    }

    @Test fun legacyIcuResolvesScriptsBeyondTheOldLanguageFallbacks() {
        assertEquals(setOf("he-Hebr"), requestedScripts("he-IL"))
        assertEquals(setOf("th-Thai"), requestedScripts("th-TH"))
        assertEquals(setOf("hi-Deva"), requestedScripts("hi-IN"))
        assertEquals(setOf("pa-Arab"), requestedScripts("pa-PK"))
        assertEquals(setOf("pa-Guru"), requestedScripts("pa-IN"))
        assertEquals(setOf("sr-Latn"), requestedScripts("sr-Latn-RS"))
    }
}
