package dev.amatv.model

import androidx.compose.ui.graphics.Color
import java.text.Bidi
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

enum class Destination {
    Home,
    Browse,
    Sing,
    Videos,
    Radio,
    Library,
    Account,
    NowPlaying,
    Search,
}

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val palette: List<Color>,
    val lyrics: List<LyricLine>,
    val catalogId: String? = null,
    val catalogKind: CatalogKind = CatalogKind.Song,
    val artworkUrl: String? = null,
    /** Muted looping editorial artwork supplied by Apple when available. */
    val motionArtworkUrl: String? = null,
    val motionPreviewUrl: String? = null,
    /** Apple reports lyrics exist, even when the public Android SDK cannot return their TTML. */
    val hasLyricsAvailable: Boolean = lyrics.isNotEmpty(),
    /** Stable controller identity used to jump within the existing queue without rebuilding it. */
    val playbackQueueId: Long? = null,
    /** Recommendation provenance; playback and lyrics still use Apple catalog IDs. */
    val recommendedByYtm: Boolean = false,
    val ytmVideoId: String? = null,
    /** Original API identity; library containers can also have a separate catalog ID. */
    val resourceId: String? = null,
    val resourceType: String? = null,
    /** Collection editorial or user-authored description, when supplied by Apple. */
    val description: String? = null,
    /** Actual canonical URL returned by Apple; never reconstructed from a display name. */
    val url: String? = null,
    val motionArtworkVariants: List<EditorialMotionAsset> = emptyList(),
    /** Apple's explicit/clean release metadata; null means the source did not supply a rating. */
    val contentRating: String? = null,
)

/**
 * Compares two projections of the same live playback-queue entry.
 *
 * MusicKit can expose the now-playing item and the matching queue item with
 * different wrapper ids. The queue position is authoritative when both sides
 * have one; catalog id is only a fallback for snapshots without that position.
 */
fun Track.matchesPlaybackQueueEntry(other: Track): Boolean {
    val thisQueueId = playbackQueueId
    val otherQueueId = other.playbackQueueId
    if (thisQueueId != null && otherQueueId != null) {
        return thisQueueId == otherQueueId
    }
    val thisCatalogId = catalogId
    val otherCatalogId = other.catalogId
    if (thisCatalogId != null && otherCatalogId != null) {
        return thisCatalogId == otherCatalogId
    }
    return id == other.id
}

data class ContentShelf(
    val id: String,
    val title: String,
    val items: List<Track>,
    val featured: Boolean = false,
    /** A verified server header destination, independent of the preview items. */
    val seeAll: Track? = null,
    /** The same Home response proves additional items or an omitted child group. */
    val hasMore: Boolean = false,
)

enum class CatalogKind {
    Song,
    Album,
    Playlist,
    Station,
    MusicVideo,
    Artist,
    Page,
}

/** Mirrors `aj.a` (`SongInfo.getAvailableTiming()`) in Apple Music 6.5.3. */
enum class LyricTiming {
    None,
    Line,
    Word,
}

/** Mirrors `LyricsAgent.LyricsAgentNative.b` in Apple Music 6.5.3. */
enum class LyricAgentType {
    None,
    Person,
    Character,
    Group,
    Organization,
    Other,
}

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /**
     * Sub-word syllables that are not separated by whitespace in TTML share a
     * non-null `emphasisGroupId` (`PlayerLyricsViewModel.e.f40286l`) and are
     * placed inside a non-breaking span (`lyrics_karaoke_non_breaking_span`).
     */
    val emphasisGroupId: Int? = null,
    /** Exact display string including trailing space when followed by whitespace in TTML (`A.H`). */
    val displayText: String = text,
    /** Whether this timed unit is followed by whitespace in TTML (`LyricsWord.isWhitespace()`). */
    val hasTrailingWhitespace: Boolean = true,
    /** Combined duration of the word/syllable group (`PlayerLyricsViewModel.e.f40280f`). */
    val groupDurationMs: Long = (endMs - startMs).coerceAtLeast(0L),
    /** Trimmed character length of the combined word/syllable group (`PlayerLyricsViewModel.e.f40281g`). */
    val groupCharCount: Int = text.trim().length,
    /** True when this word belongs to `ttm:role="x-bg"` background vocals (`PlayerLyricsViewModel.e.f40282h`). */
    val isBackgroundVocal: Boolean = false,
    /** Matched pronunciation text at the same `begin` timestamp (`A.H` `arrayMap6.get(begin)`). */
    val pronunciationText: String? = null,
    val pronunciationEndMs: Long? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class LyricLine(
    val words: List<LyricWord>,
    val translation: String? = null,
    val isTranslation: Boolean = false,
    /** False for scripts such as CJK where adjacent timed units have no spaces. */
    val joinWithSpaces: Boolean = true,
    /** True for static lyrics where Apple Music returns `itunes:timing="None"`. */
    val isStatic: Boolean = false,
    /** Background vocal words (`LyricsLine.getBackgroundWords()`, `ttm:role="x-bg"`). */
    val backgroundWords: List<LyricWord> = emptyList(),
    /** Background vocal translation text (`LyricsLine.getHtmlTranslatedBackgroundVocalsLineText()`). */
    val backgroundTranslation: String? = null,
    /** Full-line pronunciation text (`LyricsLine.getHtmlPronunciationLineText()`). */
    val pronunciationText: String? = null,
    /** Background full-line pronunciation text (`LyricsLine.getHtmlPronunciationBackgroundVocalsLineText()`). */
    val backgroundPronunciationText: String? = null,
    /** Word-timed pronunciation spans (`LyricsLine.getPronunciationWords()`). */
    val pronunciationWords: List<LyricWord> = emptyList(),
    /** Word-timed background pronunciation spans (`LyricsLine.getPronunciationBackgroundWords()`). */
    val backgroundPronunciationWords: List<LyricWord> = emptyList(),
    /** Explicit `<p begin="...">` timestamp from TTML when present. */
    val lineStartMs: Long? = null,
    /** Explicit `<p end="...">` timestamp from TTML when present. */
    val lineEndMs: Long? = null,
    /** TTML `ttm:agent` identifier (`LyricsTiming.getAgent()`). */
    val agentId: String? = null,
    /** True when `agentId` is non-empty and differs from the primary `Person` agent (`A.X` / `U0.A`). */
    val isOppositeAgent: Boolean = false,
    /** True when the song has multiple agents (`A.W` / `U0.z`), activating `0.85` width percent. */
    val isMultiAgentSong: Boolean = false,
    /** Timing granularity (`Word`, `Line`, or `None`) from `itunes:timing`. */
    val timing: LyricTiming = when {
        isStatic -> LyricTiming.None
        words.size <= 1 && backgroundWords.isEmpty() -> LyricTiming.Line
        else -> LyricTiming.Word
    },
    /** Songwriters metadata (`SongInfo.getSongwriters()`) attached to the song. */
    val songwriters: List<String> = emptyList(),
    /** Stable Apple `itunes:key` used only to attach localized text to the canonical line. */
    val sourceKey: String? = null,
) {
    val startMs: Long = lineStartMs
            ?: words.firstOrNull()?.startMs
            ?: backgroundWords.firstOrNull()?.startMs
            ?: 0L

    val mainEndMs: Long = (
            words.maxOfOrNull { it.endMs }
                ?: backgroundWords.maxOfOrNull { it.endMs }
                ?: lineEndMs
                ?: startMs
        ).coerceAtLeast(startMs)

    val endMs: Long = maxOf(
            lineEndMs ?: 0L,
            words.maxOfOrNull { it.endMs } ?: 0L,
            backgroundWords.maxOfOrNull { it.endMs } ?: 0L,
        ).coerceAtLeast(startMs)

    val text: String = words.joinToString(if (joinWithSpaces) " " else "") { it.text }

    val backgroundText: String = backgroundWords.joinToString(if (joinWithSpaces) " " else "") { it.text }
}

object LyricTimeline {
    /**
     * Exact CJK / split-on-chars UnicodeBlocks from `com.apple.android.music.utils.E0.f43150e`
     * and `com.apple.android.music.player.A.f38942k0`.
     */
    val splitOnCharsBlocks: Set<Character.UnicodeBlock> = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
        Character.UnicodeBlock.KANA_SUPPLEMENT,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
    )

    /**
     * Exact cursive/Indic/Hangul scripts excluded from character-split emphasis in
     * `com.apple.android.music.player.A.f38943l0`.
     */
    val excludedEmphasisScriptBlocks: Set<Character.UnicodeBlock> = setOf(
        Character.UnicodeBlock.ARABIC,
        Character.UnicodeBlock.ARABIC_EXTENDED_A,
        Character.UnicodeBlock.ARABIC_MATHEMATICAL_ALPHABETIC_SYMBOLS,
        Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
        Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B,
        Character.UnicodeBlock.ARABIC_SUPPLEMENT,
        Character.UnicodeBlock.SYRIAC,
        Character.UnicodeBlock.THAANA,
        Character.UnicodeBlock.MANDAIC,
        Character.UnicodeBlock.SAMARITAN,
        Character.UnicodeBlock.NKO,
        Character.UnicodeBlock.DEVANAGARI,
        Character.UnicodeBlock.DEVANAGARI_EXTENDED,
        Character.UnicodeBlock.BENGALI,
        Character.UnicodeBlock.GURMUKHI,
        Character.UnicodeBlock.GUJARATI,
        Character.UnicodeBlock.ORIYA,
        Character.UnicodeBlock.TAMIL,
        Character.UnicodeBlock.TELUGU,
        Character.UnicodeBlock.KANNADA,
        Character.UnicodeBlock.MALAYALAM,
        Character.UnicodeBlock.SINHALA,
        Character.UnicodeBlock.THAI,
        Character.UnicodeBlock.LAO,
        Character.UnicodeBlock.TIBETAN,
        Character.UnicodeBlock.MYANMAR,
        Character.UnicodeBlock.MYANMAR_EXTENDED_A,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A,
        Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B,
    )

    const val DEFAULT_LINE_OFFSET_MS = -500L
    const val INSTRUMENTAL_LINE_OFFSET_MS = -1_300L
    /** Render a syllable against its authored timestamp, without a premature sweep. */
    const val KARAOKE_WORD_OFFSET_MS = 0L
    /** A completed line may hand off just before the next onset. */
    const val LINE_LOOKAHEAD_MS = 180L
    const val INSTRUMENTAL_MIN_GAP_MS = 7_000L
    const val INSTRUMENTAL_DURATION_TRIM_MS = 800L
    const val INSTRUMENTAL_DOT_FINAL_SEGMENT_MS = 750L

    data class CharacterEmphasisTiming(
        val animationDurationMs: Long,
        val riseStartMs: Long,
        val settleStartMs: Long,
        val maximumScale: Float,
    )

    data class InstrumentalGap(
        val beforeLineIndex: Int,
        val startMs: Long,
        val endMs: Long,
        val rawGapDurationMs: Long,
        /** `this.w = duration - 800` in `A.l` / `U0.a`. */
        val adjustedDurationMs: Long,
    )

    /**
     * Mirrors `com.apple.android.music.utils.D0.a(String)`:
     * `!new Bidi(str, -2).baseIsLeftToRight()`.
     */
    fun isRightToLeft(text: CharSequence): Boolean {
        val str = text.toString()
        if (str.isEmpty()) return false
        return !Bidi(str, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
    }

    /** Mirrors `com.apple.android.music.utils.E0.a.a(CharSequence, Set<UnicodeBlock>)`. */
    fun containsSplitOnCharsScript(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            if (Character.UnicodeBlock.of(text[i]) in splitOnCharsBlocks) return true
        }
        return false
    }

    fun containsExcludedEmphasisScript(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            val block = Character.UnicodeBlock.of(text[i])
            if (block in splitOnCharsBlocks || block in excludedEmphasisScriptBlocks) return true
        }
        return false
    }

    /**
     * Mirrors `PlayerLyricsViewFragment.f.invoke` (lines 420-431):
     * Clamps the inter-line gap `[currentLine.endMs, nextLine.startMs]` to `[200, 750]`
     * and linearly interpolates `[480, 750]` ms. Kept as a reference for the
     * source animation; it is deliberately not used for early line activation.
     */
    fun dynamicKaraokeLineAdvanceMs(lines: List<LyricLine>, lineIndex: Int): Int {
        return dynamicKaraokeClusterAdvanceMs(lines, lineIndex, lineIndex + 1)
    }

    /** Scroll duration from the end of every singer in one cluster to the next. */
    fun dynamicKaraokeClusterAdvanceMs(
        lines: List<LyricLine>,
        clusterStartIndex: Int,
        nextClusterStartIndex: Int,
    ): Int {
        if (clusterStartIndex !in lines.indices || nextClusterStartIndex !in lines.indices ||
            nextClusterStartIndex <= clusterStartIndex
        ) return 750
        val next = lines[nextClusterStartIndex]
        if (lines[clusterStartIndex].isStatic || next.isStatic) return 750
        val clusterEndMs = lines.subList(clusterStartIndex, nextClusterStartIndex)
            .maxOf(LyricLine::mainEndMs)
        val gap = (next.startMs - clusterEndMs).coerceIn(200L, 750L)
        val fraction = (gap - 200f) / 550f
        return (480f + (750f - 480f) * fraction).roundToInt()
    }

    private data class VocalCluster(val startIndex: Int, val endMs: Long)

    /** Brief release tails must not transitively join a whole verse into one duet. */
    private fun startsNewCluster(previous: LyricLine, next: LyricLine): Boolean {
        if (next.startMs >= previous.mainEndMs) return true
        val overlap = previous.mainEndMs - next.startMs
        val shorterDuration = minOf(
            previous.mainEndMs - previous.startMs,
            next.mainEndMs - next.startMs,
        )
        return overlap <= 250L && overlap <= shorterDuration * 0.30f
    }

    private fun vocalClusters(lines: List<LyricLine>): List<VocalCluster> = buildList {
        var startIndex = -1
        var lastFinishingLine: LyricLine? = null
        for (index in lines.indices) {
            val line = lines[index]
            if (line.isStatic || line.endMs <= 0L) continue
            val previous = lastFinishingLine
            if (previous == null || startsNewCluster(previous, line)) {
                if (previous != null) add(VocalCluster(startIndex, previous.mainEndMs))
                startIndex = index
                lastFinishingLine = line
            } else if (line.mainEndMs >= previous.mainEndMs) {
                // Use the actual last singer's duration, not the whole cluster's
                // envelope, when deciding whether the next overlap is only a tail.
                lastFinishingLine = line
            }
        }
        lastFinishingLine?.let { add(VocalCluster(startIndex, it.mainEndMs)) }
    }

    data class Snapshot(
        val activeLineIndices: List<Int>,
        val anchorLineIndex: Int,
        val instrumentalGap: InstrumentalGap?,
    ) {
        /** A silent slot may take scroll focus as soon as the previous vocals end. */
        val instrumentalScrollAnchor: InstrumentalGap?
            get() = instrumentalGap?.takeIf { anchorLineIndex < it.beforeLineIndex }

        /** Visual scrolling is independent of which lyric is currently being sung. */
        val scrollAnchorLineIndex: Int
            get() = instrumentalScrollAnchor?.beforeLineIndex ?: anchorLineIndex
    }

    /** Per-song timings; equal timing intervals share their immutable visual snapshot. */
    class Schedule(lines: List<LyricLine>) {
        private val starts = LongArray(lines.size) { lines[it].startMs }
        private val ends = LongArray(lines.size) { lines[it].endMs }
        private val timed = BooleanArray(lines.size) { !lines[it].isStatic }
        private val clusters = vocalClusters(lines)
        private val clusterStarts = IntArray(clusters.size) { clusters[it].startIndex }
        val finalClusterStartIndex: Int = clusterStarts.lastOrNull() ?: -1
        private val cueTimes = LongArray(clusters.size) { index ->
            val startMs = starts[clusterStarts[index]]
            startMs - clusterLeadOffsetMs(startMs, clusters.getOrNull(index - 1)?.endMs)
        }

        val instrumentalGaps: List<InstrumentalGap> = buildList {
            var previousEndMs = 0L
            for (index in lines.indices) {
                if (!timed[index]) continue
                val rawGap = starts[index] - previousEndMs
                if (rawGap >= INSTRUMENTAL_MIN_GAP_MS) {
                    add(InstrumentalGap(
                        beforeLineIndex = index,
                        startMs = previousEndMs,
                        endMs = starts[index],
                        rawGapDurationMs = rawGap,
                        adjustedDurationMs = (rawGap - INSTRUMENTAL_DURATION_TRIM_MS).coerceAtLeast(1L),
                    ))
                }
                previousEndMs = maxOf(previousEndMs, ends[index])
            }
        }

        // Nothing in Snapshot can change between these authored boundaries.
        // snapshotFlow still runs for the word clock, so avoid rescanning the
        // entire song and allocating an equal list/Snapshot on every frame.
        private val snapshotBoundaries = buildList {
            for (time in starts) add(time)
            for (time in ends) add(time)
            for (time in cueTimes) add(time)
            instrumentalGaps.forEach { gap ->
                add(gap.startMs)
                add(gap.endMs + INSTRUMENTAL_LINE_OFFSET_MS + 1_000L)
            }
        }.distinct().sorted().toLongArray()
        private data class IntervalSnapshot(val interval: Int, val snapshot: Snapshot)
        @Volatile private var cachedInterval: IntervalSnapshot? = null

        fun snapshotAt(positionMs: Long): Snapshot {
            var low = 0
            var high = snapshotBoundaries.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (positionMs >= snapshotBoundaries[middle]) low = middle + 1 else high = middle
            }
            cachedInterval?.takeIf { it.interval == low }?.let { return it.snapshot }
            var anchorIndex = -1
            for (index in cueTimes.indices) {
                if (positionMs >= cueTimes[index]) anchorIndex = clusterStarts[index]
            }
            val singing = ArrayList<Int>(2)
            for (index in starts.indices) {
                if (timed[index] && positionMs >= starts[index] && positionMs < ends[index]) {
                    singing.add(index)
                }
            }
            if (anchorIndex >= 0 && positionMs < starts[anchorIndex] && positionMs < ends[anchorIndex]) {
                singing.add(anchorIndex)
            }
            val gap = instrumentalGaps.firstOrNull {
                positionMs >= it.startMs &&
                    positionMs < it.endMs + INSTRUMENTAL_LINE_OFFSET_MS + 1_000L
            }
            return Snapshot(singing, anchorIndex, gap).also { cachedInterval = IntervalSnapshot(low, it) }
        }

        fun nextClusterStartIndex(lineIndex: Int): Int {
            for (index in clusterStarts) if (index > lineIndex) return index
            return -1
        }
    }

    /** Index after a genuine overlap group, allowing brief sequential tail overlaps. */
    fun nextClusterStartIndex(lines: List<LyricLine>, clusterStartIndex: Int): Int {
        if (clusterStartIndex !in lines.indices) return -1
        return vocalClusters(lines).firstOrNull { it.startIndex > clusterStartIndex }?.startIndex ?: -1
    }

    private fun clusterLeadOffsetMs(startMs: Long, previousEndMs: Long?): Long {
        if (previousEndMs == null || startMs - previousEndMs >= INSTRUMENTAL_MIN_GAP_MS) {
            return -DEFAULT_LINE_OFFSET_MS
        }
        return startMs - maxOf(previousEndMs, startMs - LINE_LOOKAHEAD_MS)
    }

    /**
     * Effective lead for a new vocal cluster. Overlapping lines share the same
     * scroll focus, so an additional singer has no separate handoff. The first
     * cluster and long instrumental gaps retain their 500 ms cue. Other clusters
     * hand off at `max(previous cluster main vocal end, next start - 180 ms)`.
     */
    fun lineLeadOffsetMs(lines: List<LyricLine>, lineIndex: Int): Long {
        if (lineIndex !in lines.indices) return -DEFAULT_LINE_OFFSET_MS
        if (lines[lineIndex].isStatic) return 0L
        val clusters = vocalClusters(lines)
        val clusterIndex = clusters.indexOfFirst { it.startIndex == lineIndex }
        if (clusterIndex < 0) return 0L
        return clusterLeadOffsetMs(
            lines[lineIndex].startMs,
            clusters.getOrNull(clusterIndex - 1)?.endMs,
        )
    }

    /**
     * Scroll focus at [positionMs]: the first line in the current overlap
     * cluster. When lead vocals ("大字") complete, handoff to the next cluster is
     * allowed even if background vocals ("小字") are still trailing.
     * When [applyTimeProcessorOffsets] is true, uses the bounded cluster handoff.
     */
    fun activeLineAt(
        lines: List<LyricLine>,
        positionMs: Long,
        applyTimeProcessorOffsets: Boolean = false,
    ): Int {
        if (lines.isEmpty()) return -1
        var focusedIndex = -1
        var previousEndMs: Long? = null
        val clusters = vocalClusters(lines)
        for (cluster in clusters) {
            val line = lines[cluster.startIndex]
            val leadMs = if (applyTimeProcessorOffsets) {
                clusterLeadOffsetMs(line.startMs, previousEndMs)
            } else 0L
            if (positionMs >= line.startMs - leadMs) focusedIndex = cluster.startIndex
            previousEndMs = cluster.endMs
        }
        return if (applyTimeProcessorOffsets) focusedIndex
        else focusedIndex.coerceAtLeast(clusters.firstOrNull()?.startIndex ?: -1)
    }

    /** A sung line retains its light until its rendered row actually moves upward. */
    fun completedLinePresence(
        line: LyricLine,
        positionMs: Long,
        rowTop: Float,
        focalAnchorY: Float,
        fadeDistance: Float,
        completionMs: Long = line.mainEndMs,
        terminalReleaseMs: Long? = null,
    ): Float {
        if (line.isStatic || positionMs < completionMs) return 0f
        // The last vocal cluster has no following cue to move it upward.
        if (terminalReleaseMs != null && positionMs >= terminalReleaseMs) return 0f
        return (1f - (focalAnchorY - rowTop) / fadeDistance.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** A sung word keeps its final lift, then returns continuously with the row's fade. */
    fun wordLiftFraction(
        positionMs: Long,
        riseStartMs: Long,
        vocalEndMs: Long,
        activePresence: Float,
        dampingRatio: Float = 0.93f,
        stiffness: Float = 25f,
    ): Float {
        val elapsedMs = (minOf(positionMs, vocalEndMs) - riseStartMs).coerceAtLeast(0L)
        if (elapsedMs == 0L) return 0f
        val damping = dampingRatio.toDouble()
        val naturalFrequency = sqrt(stiffness.toDouble())
        val dampedFrequency = naturalFrequency * sqrt(1.0 - damping * damping)
        val seconds = elapsedMs / 1_000.0
        val response = 1.0 - exp(-damping * naturalFrequency * seconds) * (
            cos(dampedFrequency * seconds) +
                damping * naturalFrequency / dampedFrequency * sin(dampedFrequency * seconds)
            )
        val exitPresence = if (positionMs < vocalEndMs) 1f else activePresence.coerceIn(0f, 1f)
        return response.toFloat().coerceIn(0f, 1.02f) * exitPresence
    }

    /** Long-note scale/spread finishes its own wave while the row's presence controls its exit. */
    fun characterEmphasisAmountAt(
        timing: CharacterEmphasisTiming,
        positionMs: Long,
        vocalEndMs: Long,
        activePresence: Float,
        easing: (Float) -> Float,
    ): Float {
        val duration = timing.animationDurationMs.coerceAtLeast(1L).toFloat()
        val riseFraction = ((positionMs - timing.riseStartMs) / duration).coerceIn(0f, 1f)
        if (riseFraction <= 0f) return 0f
        val settleFraction = ((positionMs - timing.settleStartMs) / duration).coerceIn(0f, 1f)
        val amount = if (settleFraction <= 0f) easing(riseFraction) else {
            val riseAtSettle = easing(
                ((timing.settleStartMs - timing.riseStartMs) / duration).coerceIn(0f, 1f),
            )
            riseAtSettle * (1f - easing(settleFraction))
        }
        val exitPresence = if (positionMs < vocalEndMs) 1f else activePresence.coerceIn(0f, 1f)
        return amount.coerceAtLeast(0f) * exitPresence
    }

    /**
     * Computes per-character emphasis timings for a word/syllable group in `A.java`
     * (lines 2078-2491, `z0` character split).
     *
     * In `A.java`:
     * - Eligible when `!isBgVocal && !line.isTranslation && wordDuration >= 1000 && wordLength <= 7`
     *   and no characters in `f38942k0` or `f38943l0`.
     * - `clampedSec = clamp(wordDuration / 1000f, 1f, 2f)`
     * - `maxScale = lerp(1.0f, 1.14f, clampedSec - 1.0f)`
     * - `animDuration = min(wordDuration, 3000L)`
     * - `stepDelay = min((0.4f * animDuration) / charCount, 400f)`
     * - `returnOffset = animDuration / (charCount / 2.0f)`
     */
    fun characterEmphasisTimings(
        line: LyricLine,
        wordIndex: Int,
        wordOffsetMs: Long = 0L,
    ): List<CharacterEmphasisTiming> {
        val word = line.words.getOrNull(wordIndex) ?: return emptyList()
        if (word.isBackgroundVocal || line.isTranslation) return emptyList()

        val groupId = word.emphasisGroupId
        val groupIndices = if (groupId != null) {
            line.words.indices.filter { line.words[it].emphasisGroupId == groupId }
        } else {
            listOf(wordIndex)
        }
        val groupStartMs = groupIndices.minOf { line.words[it].startMs } + wordOffsetMs
        val groupEndMs = groupIndices.maxOf { line.words[it].endMs } + wordOffsetMs
        val groupDurationMs = (groupEndMs - groupStartMs).coerceAtLeast(0L)
        val groupText = groupIndices.joinToString("") { line.words[it].text.trim() }
        val charCount = groupText.length

        if (
            groupDurationMs < 1_000L ||
            charCount !in 1..7 ||
            containsExcludedEmphasisScript(groupText)
        ) {
            return emptyList()
        }

        val durationRatio = (groupDurationMs / 1_000f).coerceIn(1f, 2f)
        val maximumScale = 1f + 0.14f * (durationRatio - 1f)
        val animDurationMs = groupDurationMs.coerceAtMost(3_000L)
        val stepDelayMs = ((0.4f * animDurationMs) / charCount).coerceAtMost(400f)
        val returnOffsetMs = animDurationMs.toFloat() / (charCount / 2.0f)

        return List(charCount) { charIndex ->
            val riseStart = groupStartMs + (stepDelayMs * charIndex).roundToLong()
            val settleStart = (groupStartMs + stepDelayMs * charIndex + returnOffsetMs).roundToLong()
            CharacterEmphasisTiming(
                animationDurationMs = animDurationMs,
                riseStartMs = riseStart,
                settleStartMs = settleStart,
                maximumScale = maximumScale,
            )
        }
    }

    /**
     * Exact reproduction of the `TimeAnimator` horizontal spread in `A.java` (lines 2294-2385).
     * [halfExpansions] is `(scaleX - 1.0f) * viewWidth * 0.5f` (`arrayList3` in `A.java`).
     */
    fun symmetricExpansionOffsets(
        halfExpansions: List<Float>,
        isRtl: Boolean,
    ): List<Float> {
        if (halfExpansions.size <= 1) return List(halfExpansions.size) { 0f }

        val result = MutableList(halfExpansions.size) { 0f }
        val centre = if (halfExpansions.size % 2 == 0) {
            halfExpansions.size / 2f - 0.5f
        } else {
            (halfExpansions.size / 2).toFloat()
        }
        val centreIsInteger = centre == ceil(centre.toDouble()).toFloat()
        val leftStart = if (centreIsInteger) centre.toInt() - 1
        else floor(centre.toDouble()).toInt()
        val rightStart = if (centreIsInteger) centre.toInt() + 1
        else ceil(centre.toDouble()).toInt()

        for (index in leftStart downTo 0) {
            var displacement = halfExpansions[index]
            val next = index + 1
            if (next <= centre) displacement += halfExpansions[next]
            if (next < centre) displacement += abs(result[next])
            result[index] = displacement * if (isRtl) 1f else -1f
        }
        for (index in rightStart until halfExpansions.size) {
            var displacement = halfExpansions[index]
            val previous = index - 1
            if (previous >= centre) displacement += halfExpansions[previous]
            if (previous > centre) displacement += abs(result[previous])
            result[index] = displacement * if (isRtl) -1f else 1f
        }
        return result
    }
}
