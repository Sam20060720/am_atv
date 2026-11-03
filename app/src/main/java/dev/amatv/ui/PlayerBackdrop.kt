package dev.amatv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.amatv.integration.AudioSpectrum
import dev.amatv.BuildConfig
import dev.amatv.model.Track

/** Retain motion across visits; native GPU output exists only while the player is visible. */
@Composable
fun PlayerBackdrop(
    track: Track,
    animate: Boolean,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    audioSpectrum: () -> AudioSpectrum = { AudioSpectrum() },
) {
    val colors = rememberArtworkPalette(track.artworkUrl, track.palette)
    // Build-time ownership probe. Keep the native controller and its thread
    // entirely outside this branch, rather than merely pausing an EGL output.
    if (!BuildConfig.NATIVE_PLAYER_BACKDROP) {
        if (visible) {
            val gradient = remember(colors) {
                Brush.verticalGradient(if (colors.size >= 2) colors
                    else List(2) { colors.firstOrNull() ?: Color(0xFF171722) })
            }
            Box(modifier.background(gradient))
        }
        return
    }
    val palette = remember(colors) { colors.map { it.toArgb() }.toIntArray() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val started = rememberMotionLifecycleStarted()
    val context = LocalContext.current
    val useCpu = remember(context) {
        Build.VERSION.SDK_INT >= 29 && ArtworkImages.memoryPolicy(context).constrained
    }
    val running = visible && animate && started && windowFocused &&
        android.animation.ValueAnimator.areAnimatorsEnabled()
    // Parent alpha=0 does not detach an AndroidView. Drop only its output after
    // the player's exit fade; either controller retains motion/palette on its
    // single worker while output buffers and native surfaces leave the window.
    if (useCpu) {
        // This branch never constructs a native controller, EGL session or
        // TextureView. CPU credits are retired only by real frame-commit ACKs.
        val renderer = remember { LavaCpuBackdropController() }
        DisposableEffect(renderer) { onDispose { renderer.close() } }
        SideEffect { renderer.update(palette, running, audioSpectrum) }
        if (visible) AndroidView(
            factory = { LavaCpuBackdropView(it, renderer) },
            onRelease = { it.release() },
            modifier = modifier.background(Color(0xFF171722)),
        )
    } else {
        val renderer = remember { LavaBackdropController() }
        DisposableEffect(renderer) { onDispose { renderer.close() } }
        SideEffect { renderer.update(palette, running, audioSpectrum) }
        if (visible) AndroidView(
            factory = { LavaBackdropView(it, renderer) },
            onRelease = { it.release() },
            modifier = modifier.background(Color(0xFF171722)),
        )
    }
}
