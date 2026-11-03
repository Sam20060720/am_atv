package dev.amatv.ui

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** A short Press/Release pair must survive Compose's same-frame state batching. */
internal open class TvPressInteractionSource(
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by delegate {
    var pressEpoch by mutableLongStateOf(0L)
        private set

    override suspend fun emit(interaction: Interaction) {
        delegate.emit(interaction)
        record(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        if (!delegate.tryEmit(interaction)) return false
        record(interaction)
        return true
    }

    protected open fun record(interaction: Interaction) {
        if (interaction is PressInteraction.Press) pressEpoch++
    }
}
