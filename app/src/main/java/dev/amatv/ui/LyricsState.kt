package dev.amatv.ui

import dev.amatv.integration.LyricsLocalizationRequest
import java.util.Locale

/** Identifies only the requested overlays; disabling one does not invalidate the other. */
internal val LyricsLocalizationRequest.variantKey: String
    get() = "$languageTag|trans=$includeTranslations|pron=$includePronunciations|pronLang=$pronunciationLanguageTag"

private fun String.lyricsVariantRequest(): LyricsLocalizationRequest? {
    val parts = split('|')
    if (parts.size != 4 || !parts[1].startsWith("trans=") ||
        !parts[2].startsWith("pron=") || !parts[3].startsWith("pronLang=")) return null
    return LyricsLocalizationRequest(
        languageTag = parts[0],
        pronunciationLanguageTag = parts[3].removePrefix("pronLang="),
        includeTranslations = parts[1].removePrefix("trans=").toBooleanStrictOrNull() ?: return null,
        includePronunciations = parts[2].removePrefix("pron=").toBooleanStrictOrNull() ?: return null,
    )
}

private fun sameLyricsLanguage(first: String, second: String): Boolean =
    Locale.forLanguageTag(first).toLanguageTag().equals(Locale.forLanguageTag(second).toLanguageTag(), ignoreCase = true)

internal fun ActiveLyricsState.translationReadyFor(requested: LyricsLocalizationRequest): Boolean =
    resolvedVariant?.lyricsVariantRequest()?.let {
        it.includeTranslations && sameLyricsLanguage(it.languageTag, requested.languageTag)
    } == true

internal fun ActiveLyricsState.pronunciationReadyFor(requested: LyricsLocalizationRequest): Boolean =
    resolvedVariant?.lyricsVariantRequest()?.let {
        it.includePronunciations && sameLyricsLanguage(it.pronunciationLanguageTag, requested.pronunciationLanguageTag)
    } == true

/** A subset of material already parsed is immediately usable, without another request. */
internal fun ActiveLyricsState.projectResolvedVariant(
    identity: String,
    requested: LyricsLocalizationRequest,
): ActiveLyricsState? {
    if (trackIdentity != identity || lines.isEmpty() || resolvedVariant == null) return null
    if (requested.includeTranslations && !translationReadyFor(requested)) return null
    if (requested.includePronunciations && !pronunciationReadyFor(requested)) return null
    return copy(
        lines = lines.map { it.forDisplay(requested.includeTranslations, requested.includePronunciations) },
        resolvedVariant = requested.variantKey,
        phase = LyricsLoadPhase.Ready,
    )
}

/** Keep only overlays with matching provenance while another overlay is being fetched. */
internal fun ActiveLyricsState.pendingVariant(
    identity: String,
    requested: LyricsLocalizationRequest,
): ActiveLyricsState {
    val sourceMatches = trackIdentity == identity
    val translation = sourceMatches && requested.includeTranslations && translationReadyFor(requested)
    val pronunciation = sourceMatches && requested.includePronunciations && pronunciationReadyFor(requested)
    val available = requested.copy(includeTranslations = translation, includePronunciations = pronunciation)
    return ActiveLyricsState(
        trackIdentity = identity,
        lines = if (sourceMatches) lines.map { it.forDisplay(translation, pronunciation) } else emptyList(),
        resolvedVariant = available.variantKey.takeIf { sourceMatches && lines.isNotEmpty() },
        phase = LyricsLoadPhase.Loading,
    )
}
