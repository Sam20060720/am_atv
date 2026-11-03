package dev.amatv.integration

import dev.amatv.model.CatalogKind

/** An SDK route is an available request shape, never proof that streaming succeeded. */
sealed interface NativeMediaRoute {
    data class Item(val itemType: Int, val requiresVideoSurface: Boolean = false) : NativeMediaRoute
    data class Container(val containerType: Int) : NativeMediaRoute
    data class Unavailable(val reason: NativeMediaUnavailable) : NativeMediaRoute
}

enum class NativeMediaUnavailable {
    SdkMissing,
    UnsupportedResource,
    StationNotExposed,
    MusicVideoNotExposed,
    VideoSurfaceMissing,
}

/** Inspect the shipped SDK instead of assuming its older online Javadocs match. */
data class NativeMediaCapabilities(
    val songItemType: Int? = null,
    val albumContainerType: Int? = null,
    val playlistContainerType: Int? = null,
    val stationContainerType: Int? = null,
    val musicVideoItemType: Int? = null,
    val hasVideoOutputSurface: Boolean = false,
    val hasVideoDimensions: Boolean = false,
) {
    val catalogStations: Boolean get() = stationContainerType != null
    val musicVideos: Boolean get() = musicVideoItemType != null && hasVideoOutputSurface && hasVideoDimensions

    fun route(kind: CatalogKind, videoSurfaceAttached: Boolean = false): NativeMediaRoute = when (kind) {
        CatalogKind.Song -> songItemType?.let { NativeMediaRoute.Item(it) }
            ?: NativeMediaRoute.Unavailable(NativeMediaUnavailable.SdkMissing)
        CatalogKind.Album -> albumContainerType?.let { NativeMediaRoute.Container(it) }
            ?: NativeMediaRoute.Unavailable(NativeMediaUnavailable.SdkMissing)
        CatalogKind.Playlist -> playlistContainerType?.let { NativeMediaRoute.Container(it) }
            ?: NativeMediaRoute.Unavailable(NativeMediaUnavailable.SdkMissing)
        CatalogKind.Station -> stationContainerType?.let { NativeMediaRoute.Container(it) }
            ?: NativeMediaRoute.Unavailable(NativeMediaUnavailable.StationNotExposed)
        CatalogKind.MusicVideo -> when {
            !musicVideos -> NativeMediaRoute.Unavailable(NativeMediaUnavailable.MusicVideoNotExposed)
            !videoSurfaceAttached -> NativeMediaRoute.Unavailable(NativeMediaUnavailable.VideoSurfaceMissing)
            else -> NativeMediaRoute.Item(checkNotNull(musicVideoItemType), requiresVideoSurface = true)
        }
        else -> NativeMediaRoute.Unavailable(NativeMediaUnavailable.UnsupportedResource)
    }

    companion object {
        fun inspect(classLoader: ClassLoader? = NativeMediaCapabilities::class.java.classLoader): NativeMediaCapabilities {
            fun type(name: String): Class<*>? = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            fun constant(owner: Class<*>?, name: String): Int? = runCatching {
                owner?.getField(name)?.getInt(null)
            }.getOrNull()
            val item = type("com.apple.android.music.playback.model.MediaItemType")
            val container = type("com.apple.android.music.playback.model.MediaContainerType")
            val controller = type("com.apple.android.music.playback.controller.MediaPlayerController")
            val methods = controller?.methods.orEmpty()
            fun has(name: String, arguments: Int = 0): Boolean = methods.any {
                it.name == name && it.parameterTypes.size == arguments
            }
            return NativeMediaCapabilities(
                songItemType = constant(item, "SONG"),
                albumContainerType = constant(container, "ALBUM"),
                playlistContainerType = constant(container, "PLAYLIST"),
                stationContainerType = constant(container, "RADIO_STATION"),
                musicVideoItemType = constant(item, "MUSIC_VIDEO"),
                hasVideoOutputSurface = methods.any {
                    it.name == "setVideoOutputSurface" && it.parameterTypes.singleOrNull()?.name == "android.view.Surface"
                },
                hasVideoDimensions = has("getVideoWidth") && has("getVideoHeight"),
            )
        }
    }
}
