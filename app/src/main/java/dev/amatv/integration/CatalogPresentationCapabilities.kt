package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track

/**
 * Structural presentation gates only. Authentication/network errors never remove
 * otherwise supported songs, albums or playlists from the catalog.
 */
data class CatalogPresentationCapabilities(
    val native: NativeMediaCapabilities,
    /** Must be enabled only when the app has installed a real native video surface path. */
    val videoRendererInstalled: Boolean = false,
    /** No vocal-isolation control is exposed by the bundled Android SDK. */
    val canSing: Boolean = false,
    /** The app installed the official MusicKit JS station engine, independently of native SDK symbols. */
    val stationWebPlaybackAvailable: Boolean = false,
) {
    val canPlayStation: Boolean get() = native.catalogStations || stationWebPlaybackAvailable
    val canPlayMusicVideo: Boolean get() = native.musicVideos && videoRendererInstalled

    fun canOpen(track: Track): Boolean = when (track.catalogKind) {
        CatalogKind.Station -> canPlayStation
        CatalogKind.MusicVideo -> canPlayMusicVideo
        else -> true
    }

    fun supports(destination: Destination): Boolean = when (destination) {
        Destination.Sing -> canSing
        Destination.Videos -> canPlayMusicVideo
        Destination.Radio -> canPlayStation
        else -> true
    }

    fun canOpenResourceType(type: String): Boolean = when (type.removePrefix("library-")) {
        "music-videos" -> canPlayMusicVideo
        "stations" -> canPlayStation
        else -> true
    }

    fun filterTracks(tracks: List<Track>): List<Track> = tracks.filter(::canOpen)

    fun filterShelves(shelves: List<ContentShelf>): List<ContentShelf> = shelves.mapNotNull { shelf ->
        val supported = filterTracks(shelf.items)
        shelf.copy(items = supported).takeIf { supported.isNotEmpty() || shelf.items.isEmpty() && shelf.hasMore }
    }
}
