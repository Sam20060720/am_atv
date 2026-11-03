package dev.amatv.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LibraryAdditionOptions(
    val addPlaylistSongs: Boolean = false,
    val addFavoriteItems: Boolean = false,
)

/** Account-action preferences; these never alter playback queue ownership. */
class LibraryAdditionPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("library-additions", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(LibraryAdditionOptions(
        addPlaylistSongs = preferences.getBoolean("add-playlist-songs", false),
        addFavoriteItems = preferences.getBoolean("add-favorite-items", false),
    ))
    val settings = mutableSettings.asStateFlow()

    fun setAddPlaylistSongs(value: Boolean) {
        preferences.edit().putBoolean("add-playlist-songs", value).apply()
        mutableSettings.value = mutableSettings.value.copy(addPlaylistSongs = value)
    }

    fun setAddFavoriteItems(value: Boolean) {
        preferences.edit().putBoolean("add-favorite-items", value).apply()
        mutableSettings.value = mutableSettings.value.copy(addFavoriteItems = value)
    }
}
