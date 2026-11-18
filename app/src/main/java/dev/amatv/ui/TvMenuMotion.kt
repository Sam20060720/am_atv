package dev.amatv.ui

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.LocalContext
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** Only visual jobs live here. Reads, writes and navigation history stay with the menu caller. */
@Stable
internal class TvMenuMotion(
    val motionEnabled: Boolean,
    private val scope: CoroutineScope,
) {
    val entrance = Animatable(if (motionEnabled) 0f else 1f)
    val paneOpacity = Animatable(1f)
    var entranceComplete by mutableStateOf(!motionEnabled)
        private set
    var closing by mutableStateOf(false)
        private set
    var transitioning by mutableStateOf(false)
        private set
    var incomingPane by mutableStateOf(false)
        private set
    var paneDirection by mutableFloatStateOf(1f)
        private set
    private var openingAnimation: Job? = null
    private var paneAnimation: Job? = null
    private var closingAnimation: Job? = null
    private var paneGeneration = 0L
    val visibility: State<Float> = object : State<Float> {
        override val value: Float get() = entrance.value
    }
    val scale: Float get() = if (motionEnabled)
        CatalogMenuStyle.StartingScale + (1f - CatalogMenuStyle.StartingScale) * entrance.value else 1f

    internal suspend fun open() {
        // Back can arrive before the opening effect is dispatched.
        if (closing) return
        val job = currentCoroutineContext()[Job]
        openingAnimation = job
        try {
            if (motionEnabled) entrance.animateTo(1f,
                tween(CatalogMenuStyle.ShowMillis, easing = CatalogMenuStyle.EaseOut))
            entranceComplete = true
        } finally { if (openingAnimation === job) openingAnimation = null }
    }

    fun dismiss(beforeExit: () -> Unit = {}, onClosed: () -> Unit): Boolean {
        if (closing) return false
        closing = true
        // Domain cancellation happens after the synchronous closing intent,
        // but before an instant/animated exit can invoke its dismissal callback.
        beforeExit()
        openingAnimation?.cancel()
        paneGeneration++
        paneAnimation?.cancel()
        transitioning = false
        closingAnimation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (motionEnabled) entrance.animateTo(0f,
                tween(CatalogMenuStyle.HideMillis, easing = CatalogMenuStyle.EaseOut))
            onClosed()
        }
        return true
    }

    fun navigate(
        goingBack: Boolean,
        replaceTransition: Boolean = false,
        changeRequired: Boolean = true,
        beforeTransition: () -> Unit = {},
        onSwap: () -> Unit,
    ): Boolean {
        if (closing || transitioning && !replaceTransition) return false
        val generation = ++paneGeneration
        paneAnimation?.cancel()
        beforeTransition()
        if (generation != paneGeneration || closing) return false
        if (!motionEnabled) { onSwap(); transitioning = false; return true }
        transitioning = true
        paneDirection = if (goingBack) -1f else 1f
        incomingPane = false
        paneAnimation = scope.launch {
            try {
                if (changeRequired) {
                    paneOpacity.animateTo(0f, tween(CatalogMenuStyle.SubmenuMillis, easing = LinearEasing))
                    if (generation != paneGeneration || closing) return@launch
                    onSwap()
                }
                incomingPane = true
                withFrameNanos { }
                paneOpacity.animateTo(1f, tween(CatalogMenuStyle.SubmenuMillis, easing = LinearEasing))
            } finally { if (generation == paneGeneration) transitioning = false }
        }
        return true
    }

    internal fun dispose() {
        openingAnimation?.cancel()
        paneGeneration++
        paneAnimation?.cancel()
        closingAnimation?.cancel()
    }
}

@Composable
internal fun rememberTvMenuMotion(lowPerformanceMode: Boolean): TvMenuMotion {
    val context = LocalContext.current
    val systemMotionEnabled = remember(context) { systemAnimationsEnabled(context) }
    val scope = rememberCoroutineScope()
    val motion = remember(scope, lowPerformanceMode, systemMotionEnabled) {
        TvMenuMotion(!lowPerformanceMode && systemMotionEnabled, scope)
    }
    LaunchedEffect(motion) { motion.open() }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}

/** Both native menu windows suppress the platform's second animation and dim layer. */
@Composable
internal fun TvMenuWindowSetup() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.setWindowAnimations(0)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
        onDispose { }
    }
}

internal fun Modifier.tvMenuScrim(motion: TvMenuMotion): Modifier = drawWithCache {
    onDrawBehind { drawRect(Color.Black.copy(alpha = .30f * motion.entrance.value)) }
}

internal fun Modifier.tvMenuEntrance(motion: TvMenuMotion, placement: CatalogMenuPlacement): Modifier = graphicsLayer {
    alpha = motion.entrance.value
    scaleX = motion.scale
    scaleY = motion.scale
    transformOrigin = placement.origin
}

internal fun Modifier.tvMenuPane(motion: TvMenuMotion): Modifier = graphicsLayer {
    alpha = motion.paneOpacity.value
    translationX = 25.dp.toPx() * motion.paneDirection *
        (if (motion.incomingPane) 1f else -1f) * (1f - motion.paneOpacity.value)
}

@Composable
@ChecksSdkIntAtLeast(api = 31)
internal fun tvMenuMaterialAvailable(source: GraphicsLayer?, lowPerformanceMode: Boolean): Boolean =
    LocalInterfaceStyle.current != InterfaceStyle.Classic && source != null &&
        Build.VERSION.SDK_INT >= 31 && !lowPerformanceMode

@Composable
@RequiresApi(31)
internal fun TvMenuBackdrop(source: GraphicsLayer, placement: CatalogMenuPlacement, motion: TvMenuMotion) {
    val density = LocalDensity.current
    val radius = tvMenuMetrics().radius
    FrostedPanel(source = source, expandedBounds = placement.bounds,
        animatedBounds = {
            if (motion.entrance.value <= 0f) Rect.Zero else scaledMenuBounds(placement, motion.scale)
        },
        animatedRadius = { with(density) { radius.toPx() } * motion.scale },
        opacity = { motion.entrance.value })
}

private fun scaledMenuBounds(placement: CatalogMenuPlacement, scale: Float): Rect {
    val b = placement.bounds
    val x = b.left + b.width * placement.origin.pivotFractionX
    val y = b.top + b.height * placement.origin.pivotFractionY
    return Rect(x + (b.left - x) * scale, y + (b.top - y) * scale,
        x + (b.right - x) * scale, y + (b.bottom - y) * scale)
}
