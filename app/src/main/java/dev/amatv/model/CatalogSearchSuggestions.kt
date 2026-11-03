package dev.amatv.model

/** Public search terms and actual top-result resources; no invented artist/name matches. */
data class CatalogSearchTerm(val displayTerm: String, val searchTerm: String)

data class CatalogSearchSuggestions(
    val terms: List<CatalogSearchTerm> = emptyList(),
    val topResults: List<Track> = emptyList(),
)
