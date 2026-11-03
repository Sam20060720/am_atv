package dev.amatv.model

enum class CatalogOpenAction { Collection, Station, Play, Artist, Page }

val Track.openAction: CatalogOpenAction
    get() = when (catalogKind) {
        CatalogKind.Album, CatalogKind.Playlist -> CatalogOpenAction.Collection
        CatalogKind.Station -> CatalogOpenAction.Station
        CatalogKind.Song, CatalogKind.MusicVideo -> CatalogOpenAction.Play
        CatalogKind.Artist -> CatalogOpenAction.Artist
        CatalogKind.Page -> CatalogOpenAction.Page
    }
