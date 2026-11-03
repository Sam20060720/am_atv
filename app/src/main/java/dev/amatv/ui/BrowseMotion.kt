package dev.amatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amatv.model.Track

/** Also gates queue/player previews that do not use the browse focus helper. */
internal val LocalArtworkMotionEnabled = staticCompositionLocalOf { true }

/** Optional metadata for the one qualified visible artwork, never a shelf-wide fetch. */
internal class EditorialArtworkLoader(
    val cached: (Track) -> Track,
    private val load: suspend (Track) -> Track,
) {
    suspend operator fun invoke(track: Track): Track = load(track)
}
internal val LocalEditorialArtworkLoader = staticCompositionLocalOf<EditorialArtworkLoader?> { null }

/** Hidden browse content and the low-performance setting suppress all card previews. */
internal val LocalBrowseMotionEnabled = staticCompositionLocalOf { true }
internal val LocalBrowseContentVisible = staticCompositionLocalOf { true }

/** The focused artwork qualifies immediately; hidden/inactive content stays gated. */
@Composable
internal fun rememberBrowseMotionReady(focused: Boolean): Boolean {
    if (!focused || !LocalBrowseMotionEnabled.current || !LocalArtworkMotionEnabled.current) return false
    return rememberMotionLifecycleStarted()
}

/** Observe only mounted motion candidates, rather than every idle card in a shelf. */
@Composable
internal fun rememberMotionLifecycleStarted(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return started
}
