package dev.amatv.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Locale

internal const val SearchHistoryLimit = 20
internal const val SearchResultCacheLimit = 6

/** Ordered newest first. Record explicit intent, never the live text input stream. */
internal fun normalizedSearchHistory(queries: List<String>): List<String> =
    queries.asSequence().map(String::trim).filter { it.isNotBlank() && it.length <= 200 }
        .distinctBy { it.lowercase(Locale.ROOT) }.take(SearchHistoryLimit).toList()

internal fun searchHistoryStorageKey(scopeKey: String): String = MessageDigest.getInstance("SHA-256")
    .digest(scopeKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal interface SearchHistoryStorage {
    suspend fun read(scopeKey: String): List<String>
    suspend fun write(scopeKey: String, queries: List<String>)
}

/** Preferences use an atomic backing-file replacement; all access and commits happen on IO. */
internal class SharedPreferencesSearchHistory(context: Context) : SearchHistoryStorage {
    private val context = context.applicationContext
    private val mutations = Mutex()
    override suspend fun read(scopeKey: String): List<String> = withContext(Dispatchers.IO) {
        mutations.withLock {
            val raw = context.getSharedPreferences("search-history-v1", Context.MODE_PRIVATE)
                .getString(searchHistoryStorageKey(scopeKey), null) ?: return@withLock emptyList()
            runCatching {
                val json = JSONArray(raw)
                normalizedSearchHistory((0 until minOf(json.length(), SearchHistoryLimit)).mapNotNull {
                    json.optString(it).takeUnless { value -> value == "null" }
                })
            }.getOrDefault(emptyList())
        }
    }
    override suspend fun write(scopeKey: String, queries: List<String>) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val values = normalizedSearchHistory(queries)
            val edit = context.getSharedPreferences("search-history-v1", Context.MODE_PRIVATE).edit()
            val key = searchHistoryStorageKey(scopeKey)
            if (values.isEmpty()) edit.remove(key) else edit.putString(key, JSONArray(values).toString())
            check(edit.commit()) { "Search history could not be saved" }
            Unit
        }
    }
}
