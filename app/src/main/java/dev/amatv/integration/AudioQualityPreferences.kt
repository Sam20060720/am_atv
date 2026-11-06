package dev.amatv.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioQuality { Efficient, High }

data class AudioQualitySelection(
    val quality: AudioQuality = AudioQuality.High,
    val atmosEnabled: Boolean = false,
)

/** A playback preparation captures this choice; changing it never interrupts the current song. */
class AudioQualityPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("audio-quality", Context.MODE_PRIVATE)
    private val mutableSelection = MutableStateFlow(AudioQualitySelection(
        quality = runCatching { preferences.getString("quality", null) }.getOrNull()
            ?.let { stored -> AudioQuality.entries.firstOrNull { it.name == stored } } ?: AudioQuality.High,
        atmosEnabled = runCatching { preferences.getBoolean("atmos-enabled", false) }.getOrDefault(false),
    ))
    val selection: StateFlow<AudioQualitySelection> = mutableSelection.asStateFlow()

    @Synchronized
    fun setQuality(value: AudioQuality) {
        preferences.edit().putString("quality", value.name).apply()
        mutableSelection.value = mutableSelection.value.copy(quality = value)
    }

    @Synchronized
    fun setAtmosEnabled(value: Boolean) {
        preferences.edit().putBoolean("atmos-enabled", value).apply()
        mutableSelection.value = mutableSelection.value.copy(atmosEnabled = value)
    }
}
