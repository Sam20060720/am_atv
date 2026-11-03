package dev.amatv.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent from interface animation quality and the protected audio player. */
class ArtworkMotionPreferences private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("artwork-motion", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = mutableEnabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        mutableEnabled.value = value
        android.util.Log.i("AMATV-UI", "Artwork motion enabled=$value")
    }

    companion object {
        @Volatile private var instance: ArtworkMotionPreferences? = null
        fun get(context: Context): ArtworkMotionPreferences = instance ?: synchronized(this) {
            instance ?: ArtworkMotionPreferences(context).also { instance = it }
        }
    }
}
