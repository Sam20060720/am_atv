package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.icu.lang.UScript
import android.os.Build
import com.ibm.icu.lang.UScript as LegacyUScript
import android.util.Log
import dev.amatv.model.LyricAgentType
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class LyricsLocalizationRequest(
    val languageTag: String,
    val pronunciationLanguageTag: String = "en-US",
    val includeTranslations: Boolean,
    val includePronunciations: Boolean,
) {
    val enabled: Boolean get() = includeTranslations || includePronunciations
    val locale: Locale get() = Locale.forLanguageTag(languageTag).takeUnless { it.language.isBlank() } ?: Locale.ENGLISH
    val pronunciationLocale: Locale
        get() = Locale.forLanguageTag(pronunciationLanguageTag)
            .takeUnless { it.language.isBlank() }
            ?: Locale.ENGLISH

    companion object {
        val Disabled = LyricsLocalizationRequest(
            languageTag = "und",
            pronunciationLanguageTag = "en-US",
            includeTranslations = false,
            includePronunciations = false,
        )
    }
}

internal data class TtmlDocuments(
    val canonical: String,
    val localizations: String?,
)

/** Fetches Apple Music's canonical TTML and optional localization overlay. */
class AppleMusicLyricsClient(
    private val storefrontProvider: suspend () -> String,
    private val userToken: () -> String?,
    private val webToken: AppleWebPlaybackTokenClient = AppleWebPlaybackTokenClient(),
    /** Debug builds only: raw syllable-lyrics responses are written here for inspection. */
    private val debugDumpDir: java.io.File? = null,
    private val contentCache: ContentCache? = null,
    private val httpCalls: Call.Factory? = null,
) {
    // Use the same pooled, verified TLS transport as catalog and web bootstrap.
    // A cancelled language/song request also cancels its underlying HTTP call.
    private val transport by lazy { CatalogHttpTransport(httpCalls ?: catalogHttpClient) }

    suspend fun lyricsFor(
        catalogId: String,
        localization: LyricsLocalizationRequest = LyricsLocalizationRequest.Disabled,
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        val mediaUserToken = userToken()?.takeIf(String::isNotBlank)
            ?: throw LyricsRequestException(LyricsFailureReason.Authorization, AppText.text(R.string.services_sign_in_apple_music))
        val requestStorefront = storefrontProvider().trim().lowercase(Locale.ROOT)
            .takeIf { it.matches(STOREFRONT) }
            ?: throw LyricsRequestException(
                LyricsFailureReason.Transient,
                AppText.text(R.string.services_user_storefront_missing),
            )
        fun checkAccount() {
            if (userToken() != mediaUserToken) throw CancellationException("Account changed during lyrics request")
        }
        checkAccount()
        val cacheGeneration = contentCache?.generation ?: 0L
        // The key is hashed before becoming a filename; account tokens are never written.
        val cacheKey = buildString {
            append("v2|").append(requestStorefront).append('|').append(catalogId)
            append('|').append(localization.languageTag)
            append("|pronLang=").append(localization.pronunciationLanguageTag)
            append('|').append(mediaUserToken)
            append("|trans=").append(localization.includeTranslations)
            append("|pron=").append(localization.includePronunciations)
        }
        contentCache?.readLyrics(cacheKey)?.let { cached ->
            val lines = runCatching { parseResponse(cached, localization) }.getOrNull().orEmpty()
            if (lines.isNotEmpty()) {
                checkAccount()
                return@withContext lines
            }
            contentCache.removeLyrics(cacheKey)
        }
        suspend fun request(forceBearerRefresh: Boolean): CatalogHttpReply {
            val bearer = webToken.token(forceBearerRefresh)
            currentCoroutineContext().ensureActive()
            checkAccount()
            val queryMap = localization.queryParameters()
            val query = queryMap.entries.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
            val url = "https://amp-api.music.apple.com/v1/catalog/" +
                "${requestStorefront.urlEncoded()}/songs/${catalogId.urlEncoded()}/" +
                "syllable-lyrics" + if (query.isNotEmpty()) "?$query" else ""
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $bearer")
                .header("media-user-token", mediaUserToken)
                .header("Origin", MUSIC_ORIGIN)
                .header("Referer", "$MUSIC_ORIGIN/")
                .header("User-Agent", WEB_USER_AGENT)
                .get().build()
            return try {
                transport.execute(request)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Type names distinguish certificate/protocol/transport failures
                // without logging credentials, response bodies, URLs or messages.
                val types = generateSequence<Throwable>(error) { it.cause }.take(4)
                    .joinToString(" -> ") { it.javaClass.simpleName }
                runCatching { Log.w(TAG, "Lyrics transport failed ($types)") }
                throw error
            }
        }

        val (status, body) = withCatalogRequestDeadline {
            catalogRequestWithTokenRefresh(::request)
        }
        checkAccount()
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw LyricsRequestException(LyricsFailureReason.Authorization, AppText.text(R.string.services_lyrics_auth_expired))
        }
        runCatching { Log.i(TAG, "Apple syllable-lyrics HTTP $status for catalog item $catalogId") }
        if (status !in 200..299) {
            throw LyricsRequestException(lyricsHttpFailure(status), when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    AppText.text(R.string.services_lyrics_auth_expired)
                HttpURLConnection.HTTP_FORBIDDEN ->
                    AppText.text(R.string.services_lyrics_restricted)
                HttpURLConnection.HTTP_NOT_FOUND ->
                    AppText.text(R.string.services_lyrics_unavailable)
                else -> AppText.text(R.string.services_lyrics_http_error, status)
            })
        }

        debugDumpDir?.let { dir ->
            check(dir.isDirectory || dir.mkdirs()) { AppText.text(R.string.services_lyrics_cache_folder_failed, dir) }
            java.io.File(dir, "$catalogId.json").writeText(body)
        }
        val lines = parseResponse(body, localization)
        if (lines.isNotEmpty()) contentCache?.writeLyrics(cacheKey, body, cacheGeneration)
        checkAccount()
        runCatching { Log.i(TAG, "Apple TTML lyrics: lines=${lines.size}, units=${lines.sumOf { it.words.size }}") }
        lines
    }

    private fun parseResponse(body: String, localization: LyricsLocalizationRequest): List<LyricLine> {
        val documents = extractTtmlDocuments(JSONObject(body))
        return AppleTtmlParser.parse(
            canonicalTtml = documents.canonical,
            localizationTtml = documents.localizations,
            translationLocale = localization.locale,
            pronunciationLocale = localization.pronunciationLocale,
            includeTranslations = localization.includeTranslations,
            includePronunciations = localization.includePronunciations,
        )
    }

    /** The base document always owns timing, agents and background-vocal structure. */
    internal fun extractTtmlDocuments(root: JSONObject): TtmlDocuments {
        val data = root.optJSONArray("data")
        val mediaEntity = data?.optJSONObject(0)
        val attributes = mediaEntity?.optJSONObject("attributes")
        val base = attributes?.nonBlankString("ttml")
        val localized = attributes?.nonBlankString("ttmlLocalizations")
        runCatching {
            Log.i(
                TAG,
                "TTML response data=${data?.length() ?: 0}, attributes=${attributes?.length() ?: 0}, " +
                    "baseBytes=${base?.length ?: 0}, localizationBytes=${localized?.length ?: 0}",
            )
        }
        val canonical = base ?: localized
        if (canonical == null) {
            throw LyricsRequestException(LyricsFailureReason.Unavailable, AppText.text(R.string.services_lyrics_unavailable))
        }
        return TtmlDocuments(
            canonical = canonical,
            localizations = localized?.takeUnless { base == null || it == canonical },
        )
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun JSONObject.nonBlankString(name: String): String? =
        (opt(name) as? String)?.takeIf(String::isNotBlank)

    private companion object {
        const val TAG = "AppleMusicLyrics"
        const val MUSIC_ORIGIN = "https://music.apple.com"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142 Safari/537.36"
        val STOREFRONT = Regex("[a-z]{2}")
    }
}

internal fun LyricsLocalizationRequest.queryParameters(): Map<String, String> = buildMap {
    if (enabled) {
        AppleLocaleUtil.getSystemLyricsLanguage(locale).takeIf(String::isNotBlank)?.let {
            put("l[lyrics]", it)
        }
        AppleLocaleUtil.getSystemLyricsScripts(pronunciationLocale).takeIf(Array<String>::isNotEmpty)?.let {
            put("extend", "ttmlLocalizations")
            put("l[script]", it.joinToString(","))
        }
    }
}

/**
 * Exact reproduction of `com.apple.android.music.ttml.LocaleUtil`.
 */
internal object AppleLocaleUtil {
    fun getSystemLyricsLanguage(locale: Locale = Locale.getDefault()): String {
        var script = locale.script.orEmpty()
        if (script.isEmpty()) {
            script = getChineseScript(locale)
        }
        if (script.isEmpty()) {
            script = locale.country.orEmpty()
        }
        return listOf(locale.language.orEmpty(), script)
            .filter { it.isNotEmpty() }
            .joinToString("-")
    }

    fun getSystemLyricsScripts(locale: Locale = Locale.getDefault()): Array<String> {
        val shortNames = runCatching {
            val codes = if (Build.VERSION.SDK_INT >= 24) UScript.getCode(locale) else LegacyUScript.getCode(locale)
            fun shortName(code: Int): String? = if (Build.VERSION.SDK_INT >= 24)
                UScript.getShortName(code) else LegacyUScript.getShortName(code)
            val list = ArrayList<String>((codes?.size ?: 0) + 1)
            if (locale == Locale.JAPAN) {
                shortName(54)?.let(list::add)
            }
            if (codes != null) {
                for (code in codes) {
                    shortName(code)?.let(list::add)
                }
            }
            list
        }.getOrElse {
            buildList {
                if (locale == Locale.JAPAN) add("Hira")
                val fallback = locale.script.ifEmpty { getChineseScript(locale) }
                if (fallback.isNotEmpty()) add(fallback)
            }
        }
        val resolved = shortNames.ifEmpty {
            listOf(fallbackScript(locale))
        }
        return resolved
            .filter { it.isNotEmpty() }
            .distinct()
            .map { "${locale.language}-$it" }
            .toTypedArray()
    }

    private fun fallbackScript(locale: Locale): String = locale.script.ifEmpty {
        getChineseScript(locale).ifEmpty {
            when (locale.language.lowercase(Locale.ROOT)) {
                "ja" -> "Hira"
                "ko" -> "Kore"
                "ru", "uk", "bg", "sr", "mk", "be" -> "Cyrl"
                "ar", "fa", "ur" -> "Arab"
                else -> "Latn"
            }
        }
    }

    fun getChineseScript(locale: Locale): String {
        if (locale.language == Locale.CHINESE.language) {
            val country = locale.country
            if (country != null) {
                when (country) {
                    "CN", "SG" -> return "Hans"
                    "HK", "MO", "TW" -> return "Hant"
                }
            }
        }
        return ""
    }
}

/** Refreshes the public JWT shipped by music.apple.com without exposing it. */
class AppleWebPlaybackTokenClient internal constructor(private val fetch: suspend (String) -> String) {
    constructor() : this({ url -> fetchResource(url) })

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var cachedExpiryEpochSeconds: Long = 0L

    suspend fun token(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            currentCoroutineContext().ensureActive()
            val now = Instant.now().epochSecond
            if (!forceRefresh && cachedExpiryEpochSeconds > now + EXPIRY_MARGIN_SECONDS) {
                cachedToken?.let { return@withLock it }
            }

            // Anonymous bootstrap for the region-independent web bearer only.
            // User catalog/lyrics requests choose their own account storefront.
            val html = fetch("$MUSIC_ORIGIN/us/browse")
            val assetPaths = extractAssetPaths(html)
            runCatching { Log.i(TAG, "Public player bootstrap assets=${assetPaths.size}") }
            check(assetPaths.isNotEmpty()) { AppText.text(R.string.services_web_player_unavailable) }

            var found: String? = null
            for (asset in assetPaths) {
                currentCoroutineContext().ensureActive()
                val assetUrl = if (asset.startsWith("http")) asset else "$MUSIC_ORIGIN$asset"
                val candidate = runCatching { JWT.find(fetch(assetUrl))?.value }.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    currentCoroutineContext().ensureActive()
                    runCatching { Log.w(TAG, "Public player asset failed (${failure.javaClass.simpleName})") }
                    null
                }
                if (candidate != null) { found = candidate; break }
            }
            val token = found ?: run {
                runCatching { Log.w(TAG, "Public player bearer not found") }
                error(AppText.text(R.string.services_web_token_changed))
            }
            currentCoroutineContext().ensureActive()
            cachedToken = token
            cachedExpiryEpochSeconds = jwtExpiry(token) ?: (now + DEFAULT_CACHE_SECONDS)
            token
        }
    }

    /** Preserve source order/de-duplication without re-copying whole HTML for each ICU match. */
    internal fun extractAssetPaths(html: String): List<String> {
        val matcher = ASSET_SCRIPT.matcher(html)
        val paths = linkedSetOf<String>()
        while (matcher.find()) paths.add(matcher.group(1).replace("&amp;", "&"))
        return paths.toList()
    }

    private fun jwtExpiry(token: String): Long? = runCatching {
        val payload = token.split('.')[1]
        val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        JSONObject(decoded).optLong("exp").takeIf { it > 0L }
    }.getOrNull()

    private companion object {
        private suspend fun fetchResource(url: String): String {
            val transport = CatalogHttpTransport()
            var request = Request.Builder().url(url)
                .header("Accept", "text/html,application/javascript,*/*")
                .header("User-Agent", WEB_USER_AGENT).get().build()
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val reply = try {
                    transport.execute(request)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    runCatching { Log.w(TAG, "Public player transport failed (${error.javaClass.simpleName})") }
                    throw error
                }
                if (reply.status in 200..299) return reply.body
                // HttpURLConnection followed same-protocol GET redirects, including
                // relative Locations. Keep this local to the unauthenticated web GET.
                val target = if (reply.status in REDIRECT_STATUSES) {
                    reply.location?.let { request.url.resolve(it) }
                        ?.takeIf { it.scheme == request.url.scheme }
                } else null
                if (target == null) {
                    runCatching { Log.w(TAG, "Public player HTTP ${reply.status}") }
                    error(AppText.text(R.string.services_music_website_http_error, reply.status))
                }
                if (redirects++ >= MAX_REDIRECTS) throw ProtocolException("Too many redirects")
                request = request.newBuilder().url(target).build()
            }
        }

        const val TAG = "AppleWebLyricsToken"
        const val MUSIC_ORIGIN = "https://music.apple.com"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142 Safari/537.36"
        const val EXPIRY_MARGIN_SECONDS = 60L * 60L
        const val DEFAULT_CACHE_SECONDS = 60L * 60L
        const val MAX_REDIRECTS = 20
        val REDIRECT_STATUSES = setOf(300, 301, 302, 303, 307, 308)
        val ASSET_SCRIPT = Regex("""src=["']([^"']*/assets/[^"']+\.js)["']""").toPattern()
        val JWT = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    }
}

/**
 * Namespace-aware TTML parser reproducing Apple Music 6.5.3's native `SongInfoParser` +
 * `LyricsLineParser` (`libandroidappmusic.so`) and `com.apple.android.music.player.A.H`
 * sub-word syllable grouping.
 */
internal object AppleTtmlParser {
    private data class ParsedTranslation(
        val mainText: String?,
        val backgroundText: String?,
    )

    private data class ParsedPronunciation(
        val mainText: String?,
        val backgroundText: String?,
        val mainWords: List<LyricWord>,
        val backgroundWords: List<LyricWord>,
    )

    private data class RawToken(
        val text: String,
        val startMs: Long?,
        val endMs: Long?,
        val isWhitespace: Boolean,
        val explicitGroupId: Int? = null,
    )

    fun parse(ttml: String, includeTranslations: Boolean = false): List<LyricLine> =
        parseDocument(
            ttml = ttml,
            translationLocale = Locale.getDefault(),
            pronunciationLocale = Locale.getDefault(),
            includeTranslations = includeTranslations,
            includePronunciations = true,
        )

    fun parse(
        canonicalTtml: String,
        localizationTtml: String?,
        translationLocale: Locale,
        pronunciationLocale: Locale = translationLocale,
        includeTranslations: Boolean,
        includePronunciations: Boolean,
    ): List<LyricLine> {
        val canonical = parseDocument(
            ttml = canonicalTtml,
            translationLocale = translationLocale,
            pronunciationLocale = pronunciationLocale,
            includeTranslations = includeTranslations,
            includePronunciations = includePronunciations,
        )
        if (canonical.isEmpty() || localizationTtml.isNullOrBlank() ||
            (!includeTranslations && !includePronunciations)
        ) return canonical

        val localizedLines = parseDocument(
            ttml = localizationTtml,
            translationLocale = translationLocale,
            pronunciationLocale = pronunciationLocale,
            includeTranslations = includeTranslations,
            includePronunciations = includePronunciations,
        )
        val localizedByKey = localizedLines.mapNotNull { line ->
            line.sourceKey?.let { it to line }
        }.toMap()
        val localizedByStart = localizedLines.groupBy(LyricLine::startMs)

        val localizedRoot = parseRoot(localizationTtml)
        val translations = if (includeTranslations) {
            parseTranslations(localizedRoot, translationLocale, allowParagraphFallback = true)
        } else emptyMap()
        val pronunciations = if (includePronunciations) {
            parsePronunciations(localizedRoot, pronunciationLocale, allowParagraphFallback = true)
        } else emptyMap()

        return canonical.mapIndexed { index, line ->
            val key = line.sourceKey
            val localizedLine = key?.let(localizedByKey::get)
                ?: localizedByStart[line.startMs]?.singleOrNull()
                ?: localizedLines.getOrNull(index)?.takeIf { localizedLines.size == canonical.size }
            val translation = key?.let(translations::get)
            val pronunciation = key?.let(pronunciations::get)
            line.copy(
                words = mergePronunciation(
                    line.words,
                    pronunciation?.mainWords.orEmpty().ifEmpty {
                        localizedLine?.pronunciationWords.orEmpty()
                    },
                ),
                backgroundWords = mergePronunciation(
                    line.backgroundWords,
                    pronunciation?.backgroundWords.orEmpty().ifEmpty {
                        localizedLine?.backgroundPronunciationWords.orEmpty()
                    },
                ),
                translation = translation?.mainText
                    ?.takeUnless { it.normalizedLyricText() == line.text.normalizedLyricText() }
                    ?: localizedLine?.translation
                    ?: line.translation,
                backgroundTranslation = translation?.backgroundText
                    ?.takeUnless { it.normalizedLyricText() == line.backgroundText.normalizedLyricText() }
                    ?: localizedLine?.backgroundTranslation
                    ?: line.backgroundTranslation,
                pronunciationText = pronunciation?.mainText
                    ?.takeUnless { it.normalizedLyricText() == line.text.normalizedLyricText() }
                    ?: localizedLine?.pronunciationText
                    ?: line.pronunciationText,
                backgroundPronunciationText = pronunciation?.backgroundText
                    ?.takeUnless { it.normalizedLyricText() == line.backgroundText.normalizedLyricText() }
                    ?: localizedLine?.backgroundPronunciationText
                    ?: line.backgroundPronunciationText,
                pronunciationWords = pronunciation?.mainWords.orEmpty()
                    .ifEmpty { localizedLine?.pronunciationWords.orEmpty() }
                    .ifEmpty { line.pronunciationWords },
                backgroundPronunciationWords = pronunciation?.backgroundWords.orEmpty()
                    .ifEmpty { localizedLine?.backgroundPronunciationWords.orEmpty() }
                    .ifEmpty { line.backgroundPronunciationWords },
            )
        }
    }

    private fun parseDocument(
        ttml: String,
        translationLocale: Locale,
        pronunciationLocale: Locale,
        includeTranslations: Boolean,
        includePronunciations: Boolean,
    ): List<LyricLine> {
        val root = parseRoot(ttml)
        val declaredTiming = when (root.attributeByLocalName("timing")?.lowercase()) {
            "word" -> LyricTiming.Word
            "line" -> LyricTiming.Line
            "none" -> LyricTiming.None
            else -> null
        }

        val agents = parseAgents(root)
        val primaryPersonAgentId = agents.entries
            .firstOrNull { it.value == LyricAgentType.Person }?.key
            ?: agents.keys.firstOrNull()
        val isMultiAgentSong = agents.keys.any { it != primaryPersonAgentId }

        val songwriters = parseSongwriters(root)
        val translations = if (includeTranslations) parseTranslations(root, translationLocale) else emptyMap()
        val pronunciations = if (includePronunciations) parsePronunciations(root, pronunciationLocale) else emptyMap()

        val document = root.ownerDocument
        val paragraphs = document.getElementsByTagNameNS("*", "p")
        val rawLines = buildList {
            for (index in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(index) as? Element ?: continue
                if (paragraph.isInsideMetadata() || paragraph.isInsideLocalizationContainer()) continue
                parseLine(
                    paragraph = paragraph,
                    declaredTiming = declaredTiming,
                    primaryPersonAgentId = primaryPersonAgentId,
                    isMultiAgentSong = isMultiAgentSong,
                    songwriters = songwriters,
                    translations = translations,
                    pronunciations = pronunciations,
                    includeTranslations = includeTranslations,
                    includePronunciations = includePronunciations,
                    translationLocale = translationLocale,
                    pronunciationLocale = pronunciationLocale,
                )?.let(::add)
            }
        }
        val hasTimed = rawLines.any { !it.isStatic && it.endMs > 0L }
        return if (hasTimed) rawLines.sortedBy(LyricLine::startMs) else rawLines
    }

    private fun parseRoot(ttml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(
            InputSource(StringReader(ttml.trimStart('\uFEFF'))),
        ).documentElement
    }

    private fun parseLine(
        paragraph: Element,
        declaredTiming: LyricTiming?,
        primaryPersonAgentId: String?,
        isMultiAgentSong: Boolean,
        songwriters: List<String>,
        translations: Map<String, ParsedTranslation>,
        pronunciations: Map<String, ParsedPronunciation>,
        includeTranslations: Boolean = false,
        includePronunciations: Boolean,
        translationLocale: Locale,
        pronunciationLocale: Locale,
    ): LyricLine? {
        val lineStart = paragraph.time("begin")
        val lineEnd = paragraph.time("end")
        val lineKey = paragraph.lineKey()
        val agentId = paragraph.resolveAgentId()
        val isOppositeAgent = !agentId.isNullOrBlank() &&
            primaryPersonAgentId != null &&
            agentId != primaryPersonAgentId

        val translation = if (includeTranslations) lineKey?.let(translations::get) else null
        val inlineTranslation = if (includeTranslations) {
            paragraph.extractInlineRoleText("x-translation", translationLocale, preferScript = false)
        } else null
        val inlinePronunciation = if (includePronunciations) {
            paragraph.extractInlineRoleText("x-roman", pronunciationLocale, preferScript = true)
        } else null
        val pronunciation = lineKey?.let(pronunciations::get)

        val mainPronunciationByBegin = pronunciation?.mainWords
            ?.associateBy { it.startMs }
            .orEmpty()
        val bgPronunciationByBegin = pronunciation?.backgroundWords
            ?.associateBy { it.startMs }
            .orEmpty()

        val mainRawTokens = mutableListOf<RawToken>()
        val bgRawTokens = mutableListOf<RawToken>()
        var keepBgParenthesis = false
        var explicitGroupCounter = 10_000

        fun collectTokens(parent: Node, target: MutableList<RawToken>, inheritedGroupId: Int?) {
            var child = parent.firstChild
            while (child != null) {
                when (child.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                        val value = child.nodeValue.orEmpty()
                        if (value.isNotEmpty()) {
                            target += RawToken(
                                text = value,
                                startMs = null,
                                endMs = null,
                                isWhitespace = true,
                            )
                        }
                    }
                    Node.ELEMENT_NODE -> {
                        val el = child as Element
                        val role = el.attributeByLocalName("role")?.lowercase().orEmpty()
                        when {
                            role == "x-bg" -> {
                                if (el.attributeByLocalName("parenthesis")?.lowercase() == "keep") {
                                    keepBgParenthesis = true
                                }
                                val bgTimedLeaves = el.timedLeafSpans(includeBg = true)
                                if (bgTimedLeaves.isNotEmpty()) {
                                    collectTokens(el, bgRawTokens, null)
                                } else {
                                    val bgStart = el.time("begin") ?: lineStart
                                    val bgEnd = el.timeEnd(bgStart) ?: lineEnd
                                    val bgText = el.mainText(includeBg = true).trim()
                                    if (bgText.isNotEmpty() && bgStart != null && bgEnd != null) {
                                        bgRawTokens += RawToken(
                                            text = bgText,
                                            startMs = bgStart,
                                            endMs = bgEnd.coerceAtLeast(bgStart + 1L),
                                            isWhitespace = false,
                                        )
                                    }
                                }
                            }
                            el.isAuxiliaryText() -> Unit
                            el.localTag == "span" -> {
                                val directTimedChildren = el.childElements()
                                    .filter { it.localTag == "span" && !it.isAuxiliaryText() && !it.isBgSpan() }
                                if (directTimedChildren.size > 1) {
                                    val gid = explicitGroupCounter++
                                    collectTokens(el, target, gid)
                                } else if (directTimedChildren.size == 1) {
                                    collectTokens(el, target, inheritedGroupId)
                                } else {
                                    val start = el.time("begin")
                                    val end = el.timeEnd(start)
                                    val text = el.directText().ifEmpty { el.textContent.orEmpty() }
                                    if (start != null && end != null && text.isNotEmpty()) {
                                        target += RawToken(
                                            text = text,
                                            startMs = start,
                                            endMs = end.coerceAtLeast(start + 1L),
                                            isWhitespace = false,
                                            explicitGroupId = inheritedGroupId,
                                        )
                                    } else if (text.isNotEmpty()) {
                                        target += RawToken(
                                            text = text,
                                            startMs = null,
                                            endMs = null,
                                            isWhitespace = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                child = child.nextSibling
            }
        }

        collectTokens(paragraph, mainRawTokens, null)

        val fullMainText = paragraph.mainText(includeBg = false)
        val joinWithSpaces = fullMainText.any(Char::isWhitespace)

        val mainWords = buildWordsFromRawTokens(
            rawTokens = mainRawTokens,
            lineStart = lineStart,
            lineEnd = lineEnd,
            isBackgroundVocal = false,
            stripOuterParentheses = false,
            pronunciationByBegin = mainPronunciationByBegin,
        )
        val backgroundWords = buildWordsFromRawTokens(
            rawTokens = bgRawTokens,
            lineStart = lineStart,
            lineEnd = lineEnd,
            isBackgroundVocal = true,
            stripOuterParentheses = !keepBgParenthesis,
            pronunciationByBegin = bgPronunciationByBegin,
        )

        val resolvedTranslation = (translation?.mainText ?: inlineTranslation)
            ?.takeUnless { it.normalizedLyricText() == fullMainText.normalizedLyricText() }
        val resolvedBgTranslation = translation?.backgroundText
        val resolvedPronunciationText = (pronunciation?.mainText ?: inlinePronunciation)
            ?.takeUnless { it.normalizedLyricText() == fullMainText.normalizedLyricText() }
        val resolvedBgPronunciationText = pronunciation?.backgroundText

        if (mainWords.isEmpty() && backgroundWords.isEmpty()) {
            val text = fullMainText.trim()
            if (text.isBlank()) return null
            val isStaticLine = declaredTiming == LyricTiming.None || lineStart == null
            val start = lineStart ?: 0L
            val end = lineEnd?.coerceAtLeast(start + 1L) ?: (if (isStaticLine) 0L else start + 1L)
            return LyricLine(
                words = listOf(
                    LyricWord(
                        text = text,
                        displayText = text,
                        startMs = start,
                        endMs = end,
                        hasTrailingWhitespace = false,
                    ),
                ),
                translation = resolvedTranslation,
                backgroundTranslation = resolvedBgTranslation,
                pronunciationText = resolvedPronunciationText,
                backgroundPronunciationText = resolvedBgPronunciationText,
                pronunciationWords = pronunciation?.mainWords.orEmpty(),
                backgroundPronunciationWords = pronunciation?.backgroundWords.orEmpty(),
                lineStartMs = lineStart,
                lineEndMs = lineEnd,
                agentId = agentId,
                isOppositeAgent = isOppositeAgent,
                isMultiAgentSong = isMultiAgentSong,
                timing = if (isStaticLine) LyricTiming.None else LyricTiming.Line,
                songwriters = songwriters,
                sourceKey = lineKey,
                joinWithSpaces = false,
                isStatic = isStaticLine,
            )
        }

        val effectiveTiming = when {
            declaredTiming != null -> declaredTiming
            mainWords.size > 1 || backgroundWords.isNotEmpty() -> LyricTiming.Word
            else -> LyricTiming.Line
        }

        return LyricLine(
            words = mainWords,
            backgroundWords = backgroundWords,
            translation = resolvedTranslation,
            backgroundTranslation = resolvedBgTranslation,
            pronunciationText = resolvedPronunciationText,
            backgroundPronunciationText = resolvedBgPronunciationText,
            pronunciationWords = pronunciation?.mainWords.orEmpty(),
            backgroundPronunciationWords = pronunciation?.backgroundWords.orEmpty(),
            lineStartMs = lineStart,
            lineEndMs = lineEnd,
            agentId = agentId,
            isOppositeAgent = isOppositeAgent,
            isMultiAgentSong = isMultiAgentSong,
            timing = effectiveTiming,
            songwriters = songwriters,
            sourceKey = lineKey,
            joinWithSpaces = joinWithSpaces,
            isStatic = effectiveTiming == LyricTiming.None,
        )
    }

    /**
     * Reproduces `com.apple.android.music.player.A.H` (lines 3396-3780):
     * 1. Merges leading/trailing non-timed punctuation (`LyricsWord.isWhitespace()`) into the
     *    adjacent timed word and records whether the timed word is followed by whitespace (`z13`).
     * 2. Appends trailing `" "` to `displayText` when `hasTrailingWhitespace` is true on
     *    non-final words (`sb3.append(lyricsWordNative3.getHtmlLineText())`).
     * 3. Groups consecutive timed syllables that are NOT separated by whitespace (`!z13 && !z28`)
     *    in non-CJK scripts (`!LyricTimeline.containsSplitOnCharsScript`) into a shared
     *    `emphasisGroupId` with combined `groupDurationMs` and `groupCharCount`.
     */
    private fun buildWordsFromRawTokens(
        rawTokens: List<RawToken>,
        lineStart: Long?,
        lineEnd: Long?,
        isBackgroundVocal: Boolean,
        stripOuterParentheses: Boolean,
        pronunciationByBegin: Map<Long, LyricWord>,
    ): List<LyricWord> {
        val timedIndices = rawTokens.indices.filter { !rawTokens[it].isWhitespace && rawTokens[it].startMs != null }
        if (timedIndices.isEmpty()) return emptyList()

        data class MergedSyllable(
            val trimmedText: String,
            val displayText: String,
            val startMs: Long,
            val endMs: Long,
            val hasTrailingWhitespace: Boolean,
            val explicitGroupId: Int?,
        )

        val merged = ArrayList<MergedSyllable>(timedIndices.size)
        var i = 0
        val size = rawTokens.size
        while (i < size) {
            val token = rawTokens[i]
            val sb = StringBuilder()
            var timedToken: RawToken? = null
            var hasTrailingSpace = false

            if (token.isWhitespace) {
                if (token.text.trim().isEmpty()) {
                    i++
                    continue
                }
                sb.append(token.text)
                var j = i + 1
                var consumedTimed = false
                while (j < size) {
                    val next = rawTokens[j]
                    if (next.isWhitespace) {
                        if (next.text.trim().isEmpty()) {
                            if (consumedTimed) {
                                if (j < size - 1) sb.append(next.text)
                                hasTrailingSpace = true
                                i = j
                                break
                            }
                        } else {
                            sb.append(next.text)
                            if (consumedTimed) hasTrailingSpace = true
                        }
                    } else if (!consumedTimed) {
                        timedToken = next
                        sb.append(next.text)
                        consumedTimed = true
                    } else {
                        break
                    }
                    i = j
                    j++
                }
            } else {
                timedToken = token
                sb.append(token.text)
                var j = i + 1
                while (j < size) {
                    val next = rawTokens[j]
                    if (next.isWhitespace) {
                        if (next.text.trim().isEmpty()) {
                            if (j < size - 1) sb.append(next.text)
                            hasTrailingSpace = true
                            i = j
                            break
                        } else {
                            sb.append(next.text)
                            hasTrailingSpace = true
                            i = j
                            j++
                        }
                    } else {
                        break
                    }
                }
            }
            i++

            val resolvedTimed = timedToken ?: continue
            val rawDisplay = sb.toString()
            val trimmed = rawDisplay.trim()
            if (trimmed.isEmpty()) continue
            val start = resolvedTimed.startMs ?: continue
            val end = (resolvedTimed.endMs ?: (start + 1L)).coerceAtLeast(start + 1L)
            merged += MergedSyllable(
                trimmedText = trimmed,
                displayText = rawDisplay,
                startMs = start,
                endMs = end,
                hasTrailingWhitespace = hasTrailingSpace,
                explicitGroupId = resolvedTimed.explicitGroupId,
            )
        }

        if (merged.isEmpty()) return emptyList()

        val cleaned = if (stripOuterParentheses && merged.isNotEmpty()) {
            merged.mapIndexed { idx, item ->
                var trimmed = item.trimmedText
                var display = item.displayText
                if (idx == 0 && trimmed.startsWith("(")) {
                    trimmed = trimmed.removePrefix("(")
                    display = display.replaceFirst("(", "")
                }
                if (idx == merged.lastIndex && trimmed.endsWith(")")) {
                    trimmed = trimmed.removeSuffix(")")
                    val lastParen = display.lastIndexOf(')')
                    if (lastParen >= 0) {
                        display = display.removeRange(lastParen, lastParen + 1)
                    }
                }
                item.copy(trimmedText = trimmed, displayText = display)
            }.filter { it.trimmedText.isNotEmpty() }
        } else {
            merged
        }

        if (cleaned.isEmpty()) return emptyList()

        val fallbackStart = lineStart ?: cleaned.first().startMs
        val fallbackEnd = maxOf(lineEnd ?: 0L, cleaned.last().endMs)

        // Group adjacent non-whitespace syllables in non-CJK scripts (`A.H` lines 3549-3840)
        val groupIds = arrayOfNulls<Int>(cleaned.size)
        val groupDurations = LongArray(cleaned.size)
        val groupCharCounts = IntArray(cleaned.size)
        var nextGroupId = 1
        var pendingIndices = mutableListOf<Int>()

        for (idx in cleaned.indices) {
            val item = cleaned[idx]
            val isLast = idx == cleaned.lastIndex
            val isSplitOnChars = LyricTimeline.containsSplitOnCharsScript(item.trimmedText)
            val nextHasSameExplicitGroup = !isLast &&
                item.explicitGroupId != null &&
                item.explicitGroupId == cleaned[idx + 1].explicitGroupId

            if ((!item.hasTrailingWhitespace || nextHasSameExplicitGroup) && !isLast && !isSplitOnChars) {
                pendingIndices += idx
            } else if (pendingIndices.isNotEmpty()) {
                pendingIndices += idx
                val gid = item.explicitGroupId ?: nextGroupId++
                val totalDuration = pendingIndices.sumOf { (cleaned[it].endMs - cleaned[it].startMs).coerceAtLeast(0L) }
                val totalChars = pendingIndices.sumOf { cleaned[it].trimmedText.length }
                for (memberIdx in pendingIndices) {
                    groupIds[memberIdx] = gid
                    groupDurations[memberIdx] = totalDuration
                    groupCharCounts[memberIdx] = totalChars
                }
                pendingIndices = mutableListOf()
            } else {
                groupIds[idx] = item.explicitGroupId
                groupDurations[idx] = (item.endMs - item.startMs).coerceAtLeast(0L)
                groupCharCounts[idx] = item.trimmedText.length
            }
        }

        return cleaned.mapIndexed { idx, item ->
            val clampedStart = item.startMs.coerceAtLeast(fallbackStart)
            val clampedEnd = item.endMs.coerceAtMost(fallbackEnd).coerceAtLeast(clampedStart + 1L)
            val matchedPronunciation = pronunciationByBegin[item.startMs]
                ?: pronunciationByBegin[clampedStart]
            val distinctPronunciation = matchedPronunciation?.takeUnless {
                it.text.normalizedLyricText() == item.trimmedText.normalizedLyricText()
            }
            LyricWord(
                text = item.trimmedText,
                displayText = item.displayText,
                startMs = clampedStart,
                endMs = clampedEnd,
                emphasisGroupId = groupIds[idx],
                hasTrailingWhitespace = item.hasTrailingWhitespace || idx == cleaned.lastIndex,
                groupDurationMs = groupDurations[idx],
                groupCharCount = groupCharCounts[idx],
                isBackgroundVocal = isBackgroundVocal,
                pronunciationText = distinctPronunciation?.text,
                pronunciationEndMs = distinctPronunciation?.endMs,
            )
        }
    }

    private fun parseAgents(root: Element): Map<String, LyricAgentType> {
        val output = linkedMapOf<String, LyricAgentType>()
        val agentNodes = root.getElementsByTagNameNS("*", "agent")
        for (index in 0 until agentNodes.length) {
            val el = agentNodes.item(index) as? Element ?: continue
            val id = el.attributeByLocalName("id") ?: continue
            val type = when (el.attributeByLocalName("type")?.lowercase()) {
                "person" -> LyricAgentType.Person
                "character" -> LyricAgentType.Character
                "group" -> LyricAgentType.Group
                "organization" -> LyricAgentType.Organization
                "other" -> LyricAgentType.Other
                else -> LyricAgentType.None
            }
            output[id] = type
        }
        return output
    }

    private fun parseSongwriters(root: Element): List<String> {
        val output = mutableListOf<String>()
        val nodes = root.getElementsByTagNameNS("*", "songwriter")
        for (index in 0 until nodes.length) {
            val el = nodes.item(index) as? Element ?: continue
            val name = el.textContent?.trim().orEmpty()
            if (name.isNotEmpty()) output += name
        }
        return output
    }

    private fun parseTranslations(
        root: Element,
        targetLocale: Locale,
        allowParagraphFallback: Boolean = false,
    ): Map<String, ParsedTranslation> {
        val output = linkedMapOf<String, ParsedTranslation>()
        val translationGroups = root.descendants("translation")
        val containers = if (translationGroups.isNotEmpty()) {
            listOfNotNull(selectLocalizedElement(translationGroups, targetLocale, preferScript = false))
        } else {
            root.descendants("translations").let { groups ->
                listOfNotNull(selectLocalizedElement(groups, targetLocale, preferScript = false))
            }
        }
        for (container in containers) {
            val texts = container.descendants("text").filterNot { it.isInsidePronunciation() }
            for (textEl in selectLocalizedElements(texts, targetLocale, preferScript = false)) {
                val key = textEl.attributeByLocalName("for") ?: continue
                val mainText = textEl.mainText(includeBg = false).trim().takeIf { it.isNotEmpty() }
                val bgText = textEl.extractBgText()?.trim()?.removeSurrounding("(", ")")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (mainText != null || bgText != null) {
                    output.putIfAbsent(key, ParsedTranslation(mainText, bgText))
                }
            }
        }
        if (output.isEmpty()) {
            val texts = root.descendants("text").filterNot { it.isInsidePronunciation() }
            for (element in selectLocalizedElements(texts, targetLocale, preferScript = false)) {
                val key = element.attributeByLocalName("for") ?: continue
                val mainText = element.mainText(includeBg = false).trim().takeIf { it.isNotEmpty() }
                val bgText = element.extractBgText()?.trim()?.removeSurrounding("(", ")")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (mainText != null || bgText != null) {
                    output.putIfAbsent(key, ParsedTranslation(mainText, bgText))
                }
            }
        }
        if (output.isEmpty() && allowParagraphFallback) {
            val paragraphs = root.descendants("p").filter {
                !it.isInsidePronunciation() && (it.lineKey() != null || it.attributeByLocalName("for") != null)
            }
            for (element in selectLocalizedElements(paragraphs, targetLocale, preferScript = false)) {
                val key = element.lineKey() ?: element.attributeByLocalName("for") ?: continue
                val mainText = element.mainText(includeBg = false).trim().takeIf(String::isNotEmpty)
                val bgText = element.extractBgText()?.trim()?.removeSurrounding("(", ")")?.trim()
                    ?.takeIf(String::isNotEmpty)
                if (mainText != null || bgText != null) {
                    output.putIfAbsent(key, ParsedTranslation(mainText, bgText))
                }
            }
        }
        return output
    }

    private fun parsePronunciations(
        root: Element,
        targetLocale: Locale,
        allowParagraphFallback: Boolean = false,
    ): Map<String, ParsedPronunciation> {
        val output = linkedMapOf<String, ParsedPronunciation>()
        val transliterationGroups = root.descendants("transliteration")
        val containers = if (transliterationGroups.isNotEmpty()) {
            listOfNotNull(selectLocalizedElement(transliterationGroups, targetLocale, preferScript = true))
        } else {
            root.descendants("transliterations").let { groups ->
                listOfNotNull(selectLocalizedElement(groups, targetLocale, preferScript = true))
            }
        }
        for (container in containers) {
            for (textEl in selectLocalizedElements(container.descendants("text"), targetLocale, preferScript = true)) {
                val key = textEl.attributeByLocalName("for") ?: continue
                val mainText = textEl.mainText(includeBg = false).trim().takeIf { it.isNotEmpty() }
                val bgText = textEl.extractBgText()?.trim()?.removeSurrounding("(", ")")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val mainWords = textEl.timedLeafSpans(includeBg = false).mapNotNull { span ->
                    val start = span.time("begin") ?: return@mapNotNull null
                    val end = span.timeEnd(start) ?: return@mapNotNull null
                    val txt = span.textContent?.trim().orEmpty()
                    if (txt.isEmpty()) null else LyricWord(txt, start, end.coerceAtLeast(start + 1L))
                }
                val bgWords = textEl.childElements()
                    .filter { it.isBgSpan() }
                    .flatMap { it.timedLeafSpans(includeBg = true) }
                    .mapNotNull { span ->
                        val start = span.time("begin") ?: return@mapNotNull null
                        val end = span.timeEnd(start) ?: return@mapNotNull null
                        val txt = span.textContent?.trim()?.removeSurrounding("(", ")")?.trim().orEmpty()
                        if (txt.isEmpty()) null else LyricWord(
                            text = txt,
                            startMs = start,
                            endMs = end.coerceAtLeast(start + 1L),
                            isBackgroundVocal = true,
                        )
                    }
                if (mainText != null || bgText != null || mainWords.isNotEmpty() || bgWords.isNotEmpty()) {
                    output.putIfAbsent(
                        key,
                        ParsedPronunciation(
                            mainText = mainText,
                            backgroundText = bgText,
                            mainWords = mainWords,
                            backgroundWords = bgWords,
                        ),
                    )
                }
            }
        }
        if (output.isEmpty() && allowParagraphFallback) {
            val paragraphs = root.descendants("p").filter {
                it.isInsideTag("transliterations") || it.isInsideTag("transliteration")
            }
            for (element in selectLocalizedElements(paragraphs, targetLocale, preferScript = true)) {
                val key = element.lineKey() ?: element.attributeByLocalName("for") ?: continue
                val mainText = element.mainText(includeBg = false).trim().takeIf(String::isNotEmpty)
                val bgText = element.extractBgText()?.trim()?.removeSurrounding("(", ")")?.trim()
                    ?.takeIf(String::isNotEmpty)
                if (mainText != null || bgText != null) {
                    output.putIfAbsent(
                        key,
                        ParsedPronunciation(mainText, bgText, emptyList(), emptyList()),
                    )
                }
            }
        }
        return output
    }

    private fun mergePronunciation(
        canonicalWords: List<LyricWord>,
        localizedWords: List<LyricWord>,
    ): List<LyricWord> {
        if (canonicalWords.isEmpty() || localizedWords.isEmpty()) return canonicalWords
        val byStart = localizedWords.associateBy(LyricWord::startMs)
        var changed = false
        val merged = canonicalWords.map { word ->
            val localized = byStart[word.startMs] ?: localizedWords.minByOrNull {
                kotlin.math.abs(it.startMs - word.startMs)
            }?.takeIf { kotlin.math.abs(it.startMs - word.startMs) <= 20L }
            if (localized == null || localized.text.isBlank() ||
                localized.text.normalizedLyricText() == word.text.normalizedLyricText()
            ) {
                word
            } else {
                changed = true
                word.copy(
                    pronunciationText = localized.text,
                    pronunciationEndMs = localized.endMs,
                )
            }
        }
        return if (changed) merged else canonicalWords
    }

    private fun String.normalizedLyricText(): String =
        lowercase(Locale.ROOT).filterNot(Char::isWhitespace).trim('(', ')', '（', '）')

    private data class LocaleParts(val language: String, val scripts: Set<String>, val region: String)

    private fun localeParts(tag: String): LocaleParts {
        val locale = Locale.forLanguageTag(tag)
        return localeParts(locale)
    }

    private fun localeParts(locale: Locale): LocaleParts = LocaleParts(
        language = locale.language.lowercase(Locale.ROOT),
        scripts = when {
            locale.script.isNotEmpty() -> setOf(locale.script)
            AppleLocaleUtil.getChineseScript(locale).isNotEmpty() -> setOf(AppleLocaleUtil.getChineseScript(locale))
            locale.language == "zh" || locale.language.isEmpty() -> emptySet()
            else -> AppleLocaleUtil.getSystemLyricsScripts(locale).map { it.substringAfter('-') }.toSet()
        }.map { it.lowercase(Locale.ROOT) }.toSet(),
        region = locale.country.lowercase(Locale.ROOT),
    )

    private fun selectLocalizedElement(
        candidates: List<Element>,
        targetLocale: Locale,
        preferScript: Boolean,
        languageTag: (Element) -> String? = { it.resolvedLanguageTag() },
    ): Element? {
        if (candidates.isEmpty()) return null
        val target = localeParts(targetLocale)
        val allowUntagged = candidates.none { !languageTag(it).isNullOrBlank() }
        return candidates.withIndex().filter {
            it.value.isCompatibleWith(targetLocale, preferScript, allowUntagged, languageTag(it.value))
        }.maxWithOrNull(
            compareBy<IndexedValue<Element>> { indexed ->
                val tag = languageTag(indexed.value).orEmpty()
                if (tag.isBlank()) return@compareBy 1
                val candidate = localeParts(tag)
                if (candidate == target) return@compareBy 1_000
                val language = if (candidate.language.isNotEmpty() && candidate.language == target.language) 100 else 0
                val script = if (candidate.scripts.any { it in target.scripts }) 60 else 0
                val region = if (candidate.region.isNotEmpty() && candidate.region == target.region) 20 else 0
                if (preferScript) script * 2 + language + region else language * 2 + script + region
            }.thenBy { -it.index },
        )?.value
    }

    private fun Element.isInsidePronunciation(): Boolean =
        isInsideTag("transliteration") || isInsideTag("transliterations")

    private fun selectLocalizedElements(
        candidates: List<Element>,
        targetLocale: Locale,
        preferScript: Boolean,
        languageTag: (Element) -> String? = { it.resolvedLanguageTag() },
    ): List<Element> {
        val best = selectLocalizedElement(candidates, targetLocale, preferScript, languageTag) ?: return emptyList()
        val tag = languageTag(best)
        return candidates.filter { languageTag(it).equals(tag, ignoreCase = true) }
    }

    private fun Element.isCompatibleWith(targetLocale: Locale, preferScript: Boolean, allowUntagged: Boolean, tag: String?): Boolean {
        if (tag.isNullOrBlank()) return allowUntagged
        val candidate = localeParts(tag)
        val target = localeParts(targetLocale)
        val sameLanguage = candidate.language.isNotEmpty() && candidate.language == target.language
        val sameScript = candidate.scripts.any { it in target.scripts }
        // Pronunciation describes the source song in the requested script (e.g.
        // ja-Latn for the Latin option), rather than translating it into English.
        if (preferScript) return sameScript || (sameLanguage && candidate.scripts.isEmpty())
        return sameLanguage && (candidate.scripts.isEmpty() || target.scripts.isEmpty() || sameScript)
    }

    private fun Element.resolvedLanguageTag(): String? {
        var current: Node? = this
        while (current is Element) {
            current.attributeByLocalName("lang")?.takeIf(String::isNotBlank)?.let { return it }
            current = current.parentNode
        }
        return null
    }

    private fun Element.descendants(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return buildList(nodes.length) {
            for (index in 0 until nodes.length) {
                (nodes.item(index) as? Element)?.let(::add)
            }
        }
    }

    private fun Element.timedLeafSpans(includeBg: Boolean): List<Element> {
        val children = childElements().filter {
            it.localTag == "span" && !it.isAuxiliaryText() && (includeBg || !it.isBgSpan())
        }
        val nested = children.flatMap { child -> child.timedLeafSpans(includeBg) }
        if (nested.isNotEmpty()) return nested
        return if (time("begin") != null && (time("end") != null || attributeByLocalName("dur") != null)) {
            listOf(this)
        } else emptyList()
    }

    private fun Element.mainText(includeBg: Boolean): String = buildString {
        fun appendNode(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> append(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    if (!element.isAuxiliaryText() && (includeBg || !element.isBgSpan())) {
                        var child = element.firstChild
                        while (child != null) {
                            appendNode(child)
                            child = child.nextSibling
                        }
                    }
                }
            }
        }
        var child = firstChild
        while (child != null) {
            appendNode(child)
            child = child.nextSibling
        }
    }

    private fun Element.extractBgText(): String? {
        val bgSpans = childElements().filter { it.isBgSpan() }
        if (bgSpans.isEmpty()) return null
        return bgSpans.joinToString(" ") { it.mainText(includeBg = true).trim() }
            .takeIf { it.isNotEmpty() }
    }

    private fun Element.extractInlineRoleText(targetRole: String, targetLocale: Locale, preferScript: Boolean): String? {
        val candidates = childElements().filter {
            it.attributeByLocalName("role")?.lowercase() == targetRole
        }
        // The canonical paragraph's language describes the sung text, not an
        // untagged inline translation/romanization. Only its own tag labels it.
        val spans = selectLocalizedElements(candidates, targetLocale, preferScript) { it.attributeByLocalName("lang") }
        if (spans.isEmpty()) return null
        return spans.joinToString(" ") { it.textContent?.trim().orEmpty() }
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun Element.directText(): String = buildString {
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                append(child.nodeValue.orEmpty())
            }
            child = child.nextSibling
        }
    }

    private fun Element.childElements(): List<Element> = buildList {
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
            child = child.nextSibling
        }
    }

    private fun Element.isBgSpan(): Boolean =
        attributeByLocalName("role")?.lowercase() == "x-bg"

    private fun Element.isAuxiliaryText(): Boolean {
        val role = attributeByLocalName("role")?.lowercase().orEmpty()
        val ruby = attributeByLocalName("ruby")?.lowercase().orEmpty()
        return role == "x-translation" || role == "x-roman" ||
            ruby == "text" || ruby == "textcontainer"
    }

    private fun Element.isInsideMetadata(): Boolean =
        isInsideTag("metadata") || isInsideTag("iTunesMetadata") || isInsideTag("head")

    private fun Element.isInsideLocalizationContainer(): Boolean =
        isInsideTag("translations") || isInsideTag("translation") ||
            isInsideTag("transliterations") || isInsideTag("transliteration")

    private fun Element.isInsideTag(tag: String): Boolean {
        var current: Node? = parentNode
        while (current is Element) {
            if (current.localTag.equals(tag, ignoreCase = true)) return true
            current = current.parentNode
        }
        return false
    }

    private fun Element.resolveAgentId(): String? {
        var current: Node? = this
        while (current is Element) {
            current.attributeByLocalName("agent")?.let { return it }
            current = current.parentNode
        }
        return null
    }

    private fun Element.lineKey(): String? = attributeByLocalName("key")

    private fun Element.time(name: String): Long? = attributeByLocalName(name)?.let(::parseTimeMs)

    private fun Element.timeEnd(startMs: Long?): Long? {
        time("end")?.let { return it }
        val dur = attributeByLocalName("dur")?.let(::parseTimeMs) ?: return null
        return (startMs ?: 0L) + dur
    }

    private fun Element.attributeByLocalName(name: String): String? {
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if ((attribute.localName ?: attribute.nodeName.substringAfter(':')) == name) {
                return attribute.nodeValue?.takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private val Element.localTag: String
        get() = localName ?: tagName.substringAfter(':')

    internal fun parseTimeMs(raw: String): Long? {
        val value = raw.trim().replace(',', '.')
        if (value.isBlank()) return null
        if (value.endsWith("ms")) {
            return value.dropLast(2).toDoubleOrNull()?.toLong()
        }
        val seconds = if (value.contains(':')) {
            value.removeSuffix("s").split(':').fold(0.0) { total, part ->
                total * 60.0 + (part.toDoubleOrNull() ?: return null)
            }
        } else {
            value.removeSuffix("s").toDoubleOrNull() ?: return null
        }
        return (seconds * 1_000.0).toLong()
    }
}
