package dev.amatv.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A newly opened playlist captures this choice; an existing queue keeps its order. */
class PlaylistOrderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playlist-order", Context.MODE_PRIVATE)
    private val mutableReversed = MutableStateFlow(preferences.getBoolean("reversed", false))
    val reversed = mutableReversed.asStateFlow()

    fun setReversed(value: Boolean) {
        preferences.edit().putBoolean("reversed", value).apply()
        mutableReversed.value = value
    }
}
