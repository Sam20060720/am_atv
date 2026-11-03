package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One account/storefront cache for library links and ordinary catalog recordings. */
internal class CatalogSongResolver(
    private val accountToken: () -> String?,
    private val storefront: suspend () -> String,
    private val libraryCatalogId: suspend (String) -> String,
    private val equivalentCatalogId: suspend (String, String) -> String,
    private val authorizationMessage: () -> String,
    private val maxEntries: Int = 64,
) {
    private data class Scope(val accountToken: String, val storefront: String)

    private val mutex = Mutex()
    private var scope: Scope? = null
    private val resolvedIds = LinkedHashMap<String, String>(64, 0.75f, true)

    init {
        require(maxEntries > 0)
    }

    suspend fun resolve(id: String): String {
        val account = accountToken()?.takeIf(String::isNotBlank)
            ?: throw CatalogRequestException(CatalogFailureReason.Authorization, authorizationMessage())
        val currentScope = Scope(account, storefront())
        return mutex.withLock {
            ensureCurrentAccount(account)
            if (scope != currentScope) {
                resolvedIds.clear()
                scope = currentScope
            }
            resolvedIds[id]?.let { return@withLock it }
            val catalogId = if (id.startsWith("i.")) libraryCatalogId(id) else id
            val playable = resolvedIds[catalogId]
                ?: equivalentCatalogId(currentScope.storefront, catalogId)
            ensureCurrentAccount(account)
            // Keep aliases and the canonical ID together. The lyrics/prepare
            // callers and a re-keyed current track therefore share one lookup.
            resolvedIds[id] = playable
            resolvedIds[catalogId] = playable
            resolvedIds[playable] = playable
            while (resolvedIds.size > maxEntries) resolvedIds.remove(resolvedIds.keys.first())
            playable
        }
    }

    private suspend fun ensureCurrentAccount(expected: String) {
        currentCoroutineContext().ensureActive()
        if (accountToken() != expected) {
            throw CancellationException("Catalog account changed during song resolution")
        }
    }
}

/** Preserve resource provenance while adopting Apple's current playback identity. */
internal suspend fun resolvePlayableSongTrack(
    track: Track,
    resolveId: suspend (String) -> String,
): Track {
    if (track.catalogKind != CatalogKind.Song) return track
    val libraryId = track.resourceId?.takeIf { track.resourceType == "library-songs" && it.startsWith("i.") }
        ?: track.catalogId?.takeIf { it.startsWith("i.") }
    val original = track.catalogId ?: libraryId ?: throw CatalogRequestException(
        CatalogFailureReason.Unavailable, AppText.text(R.string.services_track_unavailable))
    val resolved = resolveId(libraryId ?: original)
    if (resolved == track.catalogId) return track
    return track.copy(
        id = "apple:$resolved",
        catalogId = resolved,
        resourceId = track.resourceId ?: libraryId ?: original,
        resourceType = track.resourceType ?: if (libraryId != null) "library-songs" else "songs",
    )
}
