package dev.amatv.integration

import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Conservative YTM-to-Apple song matching. Unknown and ambiguous results are skipped. */
class CrossCatalogResolver(private val apple: AppleMusicApi) {
    private val requests = Semaphore(3)
    private val cpu = Dispatchers.Default.limitedParallelism(2)
    private val cache = object : LinkedHashMap<String, Track?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track?>) = size > 256
    }

    suspend fun resolve(song: YtmSong): Track? = apple.withCurrentAuthorization {
        val scope = apple.resolutionCacheScope()
        val key = "$scope:${song.videoId}:${song.title}:${song.artist}:${song.durationMs}"
        synchronized(cache) {
            if (cache.containsKey(key)) return@withCurrentAuthorization cache[key]?.asYtm(song)
        }
        val result = requests.withPermit {
            val query = "${song.title} ${song.artist}".take(150)
            val candidates = apple.searchSongs(query)
            withContext(cpu) { select(song, candidates) }
        }
        if (scope != apple.resolutionCacheScope()) throw kotlinx.coroutines.CancellationException("Catalog scope changed")
        synchronized(cache) { cache[key] = result }
        result?.asYtm(song)
    }

    private fun Track.asYtm(song: YtmSong): Track = copy(
        recommendedByYtm = true,
        ytmVideoId = song.videoId,
    )

    internal fun select(song: YtmSong, candidates: List<Track>): Track? {
        val targetTitle = normalize(song.title)
        val targetArtist = normalize(song.artist)
        if (targetTitle.isBlank() || targetArtist.isBlank()) return null
        val ranked = candidates.mapNotNull { candidate ->
            if (candidate.catalogId == null || candidate.durationMs <= 0L) return@mapNotNull null
            if (song.durationMs != null && abs(candidate.durationMs - song.durationMs) > 10_000L) {
                return@mapNotNull null
            }
            if (editionMismatch(song.title, candidate.title)) return@mapNotNull null
            val titleScore = similarity(targetTitle, normalize(candidate.title))
            val artistScore = similarity(targetArtist, normalize(candidate.artist))
            if (titleScore < 0.86 || artistScore < 0.72) return@mapNotNull null
            val durationScore = song.durationMs?.let {
                1.0 - (abs(candidate.durationMs - it) / 10_000.0).coerceIn(0.0, 1.0)
            } ?: 0.5
            candidate to (titleScore * 0.58 + artistScore * 0.32 + durationScore * 0.10)
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second < 0.86) return null
        if (ranked.size > 1 && best.first.catalogId != ranked[1].first.catalogId &&
            best.second - ranked[1].second < 0.015
        ) {
            val alternative = ranked[1].first
            // The same recording often has separate album/single catalog IDs.
            // Those are safe alternatives; differing metadata or duration is not.
            val equivalentRelease = normalize(best.first.title) == normalize(alternative.title) &&
                normalize(best.first.artist) == normalize(alternative.artist) &&
                abs(best.first.durationMs - alternative.durationMs) <= 1_000L
            if (!equivalentRelease) return null
        }
        return best.first
    }

    private fun editionMismatch(a: String, b: String): Boolean {
        return !SongIdentity.isSameEdition(a, b)
    }

    internal fun normalize(value: String): String {
        val folded = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*(?:feat|ft\\.|featuring|remaster(?:ed)?)[^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*(?:feat|ft\\.|featuring|remaster(?:ed)?)[^]]*]"), " ")
            .replace(Regex("\\b(?:feat(?:uring)?|ft)\\.?\\s+.*$"), " ")
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return folded.replace(Regex("\\s+"), " ")
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val left = a.split(' ').toSet()
        val right = b.split(' ').toSet()
        val token = left.intersect(right).size.toDouble() / left.union(right).size
        val edit = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        return max(token, edit * 0.95)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.length]
    }
}
