package dev.amatv.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class InterfaceStyle(val storedValue: String) {
    Apple("apple"),
    Classic("classic");

    companion object {
        fun fromStoredValue(value: String?): InterfaceStyle =
            entries.firstOrNull { it.storedValue == value } ?: Apple
    }
}

internal val LocalInterfaceStyle = staticCompositionLocalOf { InterfaceStyle.Apple }

/** Presentation preference only; changing it never replaces a playback session. */
internal class InterfaceStylePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("interface-style", Context.MODE_PRIVATE)
    private val mutableSelected = MutableStateFlow(InterfaceStyle.fromStoredValue(
        runCatching { preferences.getString("selected", null) }.getOrNull(),
    ))
    val selected = mutableSelected.asStateFlow()

    fun select(style: InterfaceStyle) {
        if (mutableSelected.value == style) return
        preferences.edit().putString("selected", style.storedValue).apply()
        mutableSelected.value = style
    }
}
