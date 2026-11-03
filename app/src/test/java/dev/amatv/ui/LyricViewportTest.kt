package dev.amatv.ui

import androidx.compose.ui.graphics.Color
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test

class LyricViewportTest {
    private fun track(id: String, catalogId: String) = Track(
        id = id,
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 1_000L,
        palette = listOf(Color.Black),
        lyrics = emptyList(),
        catalogId = catalogId,
    )

    @Test
    fun catalogIdentityKeepsLyricsAcrossBrowseAndNowPlayingWrappers() {
        val browse = track(id = "1496639586", catalogId = "1496639586")
        val nowPlaying = track(id = "apple:1496639586", catalogId = "1496639586")

        assertEquals(browse.lyricsIdentity(), nowPlaying.lyricsIdentity())
        assertEquals(
            lyricsCacheKey(browse.lyricsIdentity(), "zh-Hant"),
            lyricsCacheKey(nowPlaying.lyricsIdentity(), "zh-Hant"),
        )
    }

    @Test fun longSongFirstArrivalBuildsOnlyBoundedContextWithCurrentSingerFirst() {
        val tops = FloatArray(1_000) { 80f * it }
        val order = lyricViewportMountOrder(tops, FloatArray(1_000) { 60f },
            viewportHeight = 540f, fromScroll = 55_760f, toScroll = 55_760f,
            anchorIndex = 700, priorityIndices = listOf(700, 701))
        assertEquals(700, order.first())
        assertTrue(701 in order)
        assertTrue(order.size <= 18)
        assertTrue(order.size < 1_000)
        assertFalse(0 in order)
        val budget = LyricRowMountBudget()
        assertEquals(700, budget.next(order, emptySet(), 100L))
        assertNull(budget.next(order, setOf(700), 100L))
        assertNotNull(budget.next(order, setOf(700), 200L))
    }

    @Test fun seekRetargetsMissingRowsWithoutGrantingTwoTreesInOneFrame() {
        val tops = FloatArray(120) { it * 80f }
        val heights = FloatArray(120) { 60f }
        val before = lyricViewportMountOrder(tops, heights, 540f, 0f, 0f, 0)
        val after = lyricViewportMountOrder(tops, heights, 540f, 0f, 6_160f, 80)
        val budget = LyricRowMountBudget()
        assertEquals(0, budget.next(before, emptySet(), 1L))
        assertNull(budget.next(after, setOf(0), 1L))
        assertEquals(80, budget.next(after, setOf(0), 2L))
        assertTrue(after.size <= 18)
    }

    @Test fun refiningPrefixHeightKeepsAnchorFixedAtEveryScrollPhase() {
        val previous = floatArrayOf(200f, 280f, 360f, 440f, 520f)
        for (change in listOf(-60f, 45f, 200f)) {
            val corrected = previous.copyOf().also { for (index in 2 until it.size) it[index] += change }
            val correction = lyricAnchorHeightCorrection(previous, corrected, 3)
            for (fraction in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
                val oldScroll = 180f + (300f - 180f) * fraction
                val newScroll = (180f + correction) + ((300f + correction) - (180f + correction)) * fraction
                assertEquals(previous[3] - oldScroll, corrected[3] - newScroll, 0.001f)
            }
        }
    }

    @Test fun firstGeometryAndChangesBelowAnchorNeverCreateAScrollCorrection() {
        assertEquals(0f, lyricAnchorHeightCorrection(floatArrayOf(), floatArrayOf(200f, 300f), 1), 0f)
        assertEquals(0f, lyricAnchorHeightCorrection(floatArrayOf(200f, 300f, 400f),
            floatArrayOf(200f, 300f, 450f), 1), 0f)
    }

    @Test fun openingAtRestoredPositionUsesCurrentAnchorWithoutFirstLineScroll() {
        val tops = floatArrayOf(140f, 248f, 356f, 464f, 572f, 680f, 788f)
        // At a restored mid-song position, the current singer is row five. Its
        // first visible placement must already be at the focal anchor.
        assertEquals(500f, initialLyricScroll(tops, anchorIndex = 5, focalAnchorY = 180f, maxScroll = 500f), 0f)
        assertEquals(0f, initialLyricScroll(tops, anchorIndex = -1, focalAnchorY = 180f, maxScroll = 500f), 0f)
        assertEquals(0f, initialLyricScroll(tops, anchorIndex = 99, focalAnchorY = 180f, maxScroll = 500f), 0f)
    }

    @Test fun deferredOpeningReadsGeometryPublishedAfterComposition() {
        val layout = LyricsLayoutInfo()
        // The opening effect is created while composition has no measurements.
        val locateAfterLayout = {
            layout.targetScrollFor(itemIndex = 7, focalAnchorY = 140f, viewportHeightPx = 540f)
        }
        assertEquals(0f, locateAfterLayout(), 0f)
        // Layout runs before that effect. A captured maxScroll=0 used to undo
        // the correct initial placement and latch it until the next lyric cue.
        val tops = FloatArray(8) { 140f + it * 100f }
        layout.update(tops, FloatArray(8) { 80f }, 1_200f)
        assertEquals(660f, locateAfterLayout(), 0f)
        layout.update(FloatArray(8) { tops[it] + 40f },
            FloatArray(8) { 80f }, 1_240f)
        assertEquals(700f, locateAfterLayout(), 0f)
    }

    @Test fun emptySongAndOutOfRangeSelectionStayBounded() {
        assertTrue(lyricViewportMountOrder(floatArrayOf(), floatArrayOf(), 540f, 0f, 0f, 0).isEmpty())
        assertEquals(listOf(0), lyricViewportMountOrder(floatArrayOf(200f), floatArrayOf(60f),
            540f, 0f, 0f, 999))
    }

    @Test fun estimatesHandleCjkTranslationsAndPronunciationsWithoutCreatingTextNodes() {
        val words = listOf(LyricWord("長長長長長長長長長長長長", 500L, 3_000L, pronunciationText = "chang"))
        val line = LyricLine(words, translation = "A translated line", joinWithSpaces = false,
            backgroundWords = words, backgroundTranslation = "Background", isMultiAgentSong = true,
            timing = LyricTiming.Word)
        val narrow = estimateLyricRowHeight(line, 320f, 1f, 1f)
        val wide = estimateLyricRowHeight(line, 800f, 1f, 1f)
        assertTrue(narrow.isFinite() && narrow > 0f)
        assertTrue(narrow >= wide)
        assertEquals(500L, line.startMs)
        assertEquals(3_000L, line.mainEndMs)
    }

    @Test fun pronunciationPriorityChangesWrappingWithoutChangingLyricsTimingOrPlainLyrics() {
        val words = List(12) { index -> LyricWord("字", 500L + index * 300L, 800L + index * 300L,
            pronunciationText = "pronunciation") }
        val line = LyricLine(words, timing = LyricTiming.Word)
        val lyricsFirst = estimateLyricRowHeight(line, 480f, 1f, 1f)
        val pronunciationFirst = estimateLyricRowHeight(line, 480f, 1f, 1f, pronunciationLarger = true)
        assertTrue("Larger pronunciation must affect the actual wrapping estimate", pronunciationFirst > lyricsFirst)
        val withoutPronunciation = line.forDisplay(showTranslation = true, showPronunciation = false)
        assertEquals(estimateLyricRowHeight(withoutPronunciation, 480f, 1f, 1f),
            estimateLyricRowHeight(withoutPronunciation, 480f, 1f, 1f, pronunciationLarger = true), 0f)
        assertSame(words, line.words)
        assertEquals(500L, line.startMs)
        assertEquals(4_100L, line.mainEndMs)
    }

    @Test fun readableHierarchyTracksStaticTimedCompactAndBackgroundGeometryAtFontScales() {
        val base = LyricLine(listOf(LyricWord("短句", 1_000L, 3_000L, pronunciationText = "duan ju")),
            translation = "Translation", pronunciationText = "duan ju", backgroundWords = listOf(
                LyricWord("和聲", 1_500L, 3_500L, pronunciationText = "he sheng", isBackgroundVocal = true)),
            backgroundTranslation = "Background", backgroundPronunciationText = "he sheng", timing = LyricTiming.Word)
        for (line in listOf(base, base.copy(timing = LyricTiming.Line),
            base.copy(isStatic = true, timing = LyricTiming.None))) {
            for (background in if (line.isStatic) listOf(false) else listOf(false, true)) {
                val normal = lyricTextSizes(line, false, background)
                val chosen = lyricTextSizes(line, true, background)
                assertTrue(normal.lyric > normal.pronunciation)
                assertTrue(chosen.pronunciation > chosen.lyric)
                assertTrue(chosen.lyricLine >= chosen.lyric)
                assertTrue(chosen.pronunciationLine >= chosen.pronunciation)
            }
            for (fontScale in listOf(1f, 1.4f, 2f)) {
                val height = estimateLyricRowHeight(line, 320f, 1.5f, fontScale, true)
                assertTrue(height.isFinite() && height > 0f)
                assertTrue(height >= estimateLyricRowHeight(line, 800f, 1.5f, fontScale, true))
            }
        }
    }

    @Test fun oversizedNonBreakingGroupFallsBackToBoundedWordWrapping() {
        val rows = lyricFlexRows(
            groupIds = listOf(7, 7, 7, 7),
            wordWidths = intArrayOf(58, 42, 55, 45),
            maxWidth = 100,
        )

        assertEquals(2, rows.size)
        assertArrayEquals(intArrayOf(0, 1), rows[0])
        assertArrayEquals(intArrayOf(2, 3), rows[1])
        assertTrue(rows.all { row -> row.sumOf { intArrayOf(58, 42, 55, 45)[it] } <= 100 })
    }

    @Test fun ordinaryNonBreakingGroupRemainsAtomicAtTheWrapBoundary() {
        val rows = lyricFlexRows(
            groupIds = listOf(null, 9, 9, null),
            wordWidths = intArrayOf(35, 30, 30, 35),
            maxWidth = 100,
        )

        assertEquals(2, rows.size)
        assertArrayEquals(intArrayOf(0, 1, 2), rows[0])
        assertArrayEquals(intArrayOf(3), rows[1])
    }

    @Test fun translationDisplayNeverReplacesBackgroundVocals() {
        val line = LyricLine(
            words = listOf(LyricWord("Lead", 1_000, 2_000)),
            translation = "主唱翻譯",
            backgroundWords = listOf(
                LyricWord("Harmony", 1_200, 2_200, isBackgroundVocal = true),
            ),
            backgroundTranslation = "和聲翻譯",
        )

        val localized = line.forDisplay(showTranslation = true, showPronunciation = false)

        assertEquals("主唱翻譯", localized.translation)
        assertEquals("Harmony", localized.backgroundText)
        assertEquals("和聲翻譯", localized.backgroundTranslation)
    }

    @Test fun singleActiveLineFittingOnScreenKeepsBaseAnchor() {
        val tops = floatArrayOf(100f, 250f, 400f, 550f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        val anchor = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(0, anchor)
    }

    @Test fun multipleActiveLinesWithinVisibleBoundsKeepBaseAnchor() {
        val tops = floatArrayOf(100f, 250f, 400f, 550f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        val anchor = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0, 1),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(0, anchor)
    }

    @Test fun overflowingBottomLineUsesEarliestActiveAnchorThatFitsAllLines() {
        val tops = floatArrayOf(100f, 250f, 500f, 650f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        // With anchor 0, scroll = 0. Line 2 bottom is 500 + 120 = 620 > viewport bottom (600).
        // Moving only to line 1 makes every active line fit, so line 2 must not be moved to the top.
        val anchor = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0, 1, 2),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(1, anchor)
    }

    @Test fun subsequentOverflowAdvancesAnchorFurther() {
        val tops = floatArrayOf(100f, 250f, 400f, 650f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        val maxScroll = 500f
        // Active lines are 1, 2, 3.
        // Under anchor 1, line 1 and 2 fit, but line 3 bottom is (650 - 100) + 120 = 670 > 600.
        // Anchor 2 is the earliest active line that lets line 3 fit; line 3 must not jump to the top.
        val anchor3 = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(1, 2, 3),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
            maxScroll = maxScroll,
        )
        assertEquals(2, anchor3)

        // If lines 0, 1, 2 are active, and line 2 fits under anchor 0, keep anchor 0.
        val anchor2 = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0, 1, 2),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
            maxScroll = maxScroll,
        )
        assertEquals(0, anchor2)
    }

    @Test fun emptyOrInvalidInputsSafelyReturnBase() {
        val tops = floatArrayOf(100f, 250f)
        val heights = floatArrayOf(120f, 120f)
        assertEquals(1, resolveActiveLyricAnchor(1, emptyList(), tops, heights, 150f, 600f))
        assertEquals(-1, resolveActiveLyricAnchor(-1, listOf(0, 1), tops, heights, 150f, 600f))
        assertEquals(0, resolveActiveLyricAnchor(0, listOf(0, 1), floatArrayOf(), floatArrayOf(), 150f, 600f))
    }

    @Test fun edgeFadeDoesNotMoveLinesThatStillFitOnScreen() {
        val tops = floatArrayOf(100f, 250f, 400f)
        val heights = floatArrayOf(115f, 115f, 115f)
        val active = listOf(0, 1, 2)
        assertEquals(0, resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        ))
    }

    @Test fun stageOffsetUsesTheActualRenderedBottom() {
        val tops = floatArrayOf(100f, 250f, 500f)
        val heights = floatArrayOf(115f, 115f, 115f)
        val active = listOf(0, 1, 2)
        assertEquals(1, resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        ))
        assertEquals(0, resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
            stageOffsetPx = -20f,
        ))
    }

    @Test fun overflowAnchorDoesNotMoveBackwardWithinCluster() {
        val tops = floatArrayOf(100f, 250f, 500f)
        val heights = floatArrayOf(115f, 115f, 115f)
        val active = listOf(0, 1, 2)
        val advanced = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(1, advanced)

        val retained = retainedLyricAnchorBase(
            clusterStartIndex = 0,
            previousAnchorIndex = advanced,
            nextClusterStartIndex = -1,
        )
        assertEquals(1, resolveActiveLyricAnchor(
            baseAnchorIndex = retained,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        ))
    }

    @Test fun retainedOverflowAnchorResetsAcrossForwardAndBackwardSeeks() {
        assertEquals(4, retainedLyricAnchorBase(4, 2, 8))
        assertEquals(0, retainedLyricAnchorBase(0, 6, 4))
    }

    @Test fun hidingLocalizationNeverChangesTimingSingerOrBackgroundVocals() {
        val line = LyricLine(
            words = listOf(
                LyricWord("主唱", 1_000, 2_000, pronunciationText = "zhu chang"),
            ),
            translation = "Lead",
            backgroundWords = listOf(
                LyricWord("和聲", 1_500, 2_500, isBackgroundVocal = true, pronunciationText = "he sheng"),
            ),
            backgroundTranslation = "Harmony",
            pronunciationText = "zhu chang",
            backgroundPronunciationText = "he sheng",
            lineStartMs = 1_000,
            lineEndMs = 2_500,
            agentId = "duet",
            isOppositeAgent = true,
            isMultiAgentSong = true,
            sourceKey = "L1",
        )

        val originalOnly = line.forDisplay(showTranslation = false, showPronunciation = false)

        assertEquals(line.startMs, originalOnly.startMs)
        assertEquals(line.endMs, originalOnly.endMs)
        assertEquals(line.agentId, originalOnly.agentId)
        assertEquals(line.isOppositeAgent, originalOnly.isOppositeAgent)
        assertEquals(line.isMultiAgentSong, originalOnly.isMultiAgentSong)
        assertEquals(line.backgroundText, originalOnly.backgroundText)
        assertEquals(null, originalOnly.translation)
        assertEquals(null, originalOnly.backgroundTranslation)
        assertEquals(null, originalOnly.words.single().pronunciationText)
        assertEquals(null, originalOnly.backgroundWords.single().pronunciationText)
        assertEquals("L1", originalOnly.sourceKey)
    }
}
