package dev.amatv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.CrossfadePainter
import coil3.compose.asPainter
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import okio.Path.Companion.toOkioPath
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import android.os.Process
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.CubicBezierEasing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import dev.amatv.R
import dev.amatv.model.Track
import dev.amatv.integration.ContentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** One presentation owner can use the same enrichment for geometry and artwork. */
@Composable
internal fun rememberEditorialArtwork(track: Track, loadAllowed: Boolean): Track {
    val loader = LocalEditorialArtworkLoader.current
    var supplementalArtwork by remember(loader, track.id, track.catalogKind, track.catalogId, track.resourceType, track.resourceId) {
        // Lazy disposal drops remember state. Restore the existing metadata LRU
        // synchronously, including the matching poster, without fetching idle cards.
        mutableStateOf(if (track.canLoadEditorialArtwork()) loader?.cached?.invoke(track)?.takeIf {
            it.motionArtworkUrl != null || it.motionArtworkVariants.isNotEmpty()
        } else null)
    }
    if (loadAllowed && loader != null && track.canLoadEditorialArtwork() && supplementalArtwork == null) {
        LaunchedEffect(track.id, track.catalogKind, track.catalogId, track.resourceType, track.resourceId, loader) {
            try {
                val loaded = loader(track)
                currentCoroutineContext().ensureActive()
                val merged = track.withEditorialMotionFrom(loaded)
                if (merged.motionArtworkUrl != null || merged.motionArtworkVariants.isNotEmpty()) supplementalArtwork = merged
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive() }
        }
    }
    return remember(track, supplementalArtwork) {
        if (track.canLoadEditorialArtwork()) supplementalArtwork?.let(track::withEditorialMotionFrom) ?: track else track
    }
}

@Composable
fun MotionArtwork(
    track: Track,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    cornerRadius: androidx.compose.ui.unit.Dp = 22.dp,
    crossfadeTrackChanges: Boolean = false,
    artworkAspectRatio: Float = 1f,
    onlyWhileVisible: Boolean = false,
) {
    // Only scrollable heroes need this observer. Store a Boolean at the visibility
    // boundary, rather than geometry state that recomposes every scrolling frame.
    var visible by remember(onlyWhileVisible) { mutableStateOf(!onlyWhileVisible) }
    val visibility = if (onlyWhileVisible) Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val area = coordinates.size.width.toFloat() * coordinates.size.height
        visible = area > 0f && bounds.width * bounds.height / area >= 0.6f
    } else Modifier
    val motionEligible = animate && android.animation.ValueAnimator.areAnimatorsEnabled() &&
        LocalArtworkMotionEnabled.current && LocalFocusTargetsEnabled.current &&
        LocalWindowInfo.current.isWindowFocused && visible
    // Static/unfocused cards do not even install a lifecycle observer or start
    // a loader job. The focused browse card qualifies without an added delay.
    val motionAllowed = if (motionEligible) rememberMotionLifecycleStarted() else false
    val videoAllowed = motionAllowed && LocalBrowseMotionEnabled.current
    val presentationTrack = rememberEditorialArtwork(track, motionAllowed)
    val asset = remember(presentationTrack.motionArtworkVariants, presentationTrack.motionArtworkUrl, presentationTrack.motionPreviewUrl, artworkAspectRatio) {
        presentationTrack.motionForAspect(artworkAspectRatio)
    }
    val artwork = remember(presentationTrack, asset) {
        // Static artwork and video can have different baked-in text layouts.
        // Preserve the selected video's paired still when focus leaves it.
        val preview = asset?.previewUrl
        if (asset == null || asset.videoUrl == presentationTrack.motionArtworkUrl && preview == presentationTrack.motionPreviewUrl) presentationTrack
        else presentationTrack.copy(motionArtworkUrl = asset.videoUrl, motionPreviewUrl = preview)
    }
    if (crossfadeTrackChanges) {
        RetainedMotionArtwork(
            requestedTrack = artwork,
            modifier = modifier.then(visibility),
            animate = videoAllowed,
            cornerRadius = cornerRadius,
            sourceAspect = asset?.aspectRatio,
        )
    } else {
        ArtworkSurface(
            track = artwork,
            modifier = modifier.then(visibility),
            animate = videoAllowed,
            cornerRadius = cornerRadius,
            sourceAspect = asset?.aspectRatio,
        )
    }
}

/**
 * Owns the decoded images, rather than replacing AsyncImage request trees.
 * The visible image remains opaque while the ready replacement fades over it.
 * A subsequent decode may run during the blend, but cannot cancel it midway.
 */
@Composable
private fun RetainedMotionArtwork(
    requestedTrack: Track,
    modifier: Modifier,
    animate: Boolean,
    cornerRadius: androidx.compose.ui.unit.Dp,
    sourceAspect: Float?,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { ArtworkImages.loader(context) }
    val density = LocalDensity.current
    val requestedKey = requestedTrack.artworkVisualKey()
    var coverState by remember { mutableStateOf("loading") }
    val coverStateDescription = stringResource(when (coverState) {
        "blending" -> R.string.player_artwork_transitioning
        "ready" -> R.string.player_artwork_loaded
        else -> R.string.player_loading
    })
    BoxWithConstraints(modifier = modifier
        .automationId("player-artwork")
        .semantics {
            stateDescription = coverStateDescription
        }
        .clip(RoundedCornerShape(cornerRadius))) {
        val imageSize = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) }
        val cached = remember(imageLoader, requestedKey) {
            ArtworkImages.cachedImage(context, requestedTrack.motionPreviewUrl ?: requestedTrack.artworkUrl)?.let {
                DecodedArtwork(requestedKey, it.asPainter(context),
                    if (requestedTrack.catalogKind == dev.amatv.model.CatalogKind.Station) ContentScale.Fit else ContentScale.Crop)
            }
        }
        val ready = produceState(cached, imageLoader, requestedKey, imageSize) {
            var image: coil3.Image? = null
            for (url in listOfNotNull(requestedTrack.motionPreviewUrl, requestedTrack.artworkUrl)
                .filter { it.startsWith("https://") }.distinct()) {
                val result = imageLoader.execute(ImageRequest.Builder(context).data(url).memoryCacheKey(url).size(imageSize).build())
                if (result is SuccessResult) { image = result.image; break }
            }
            val decoded = image
            // Early upload is optional: this retained player can be mounted
            // under browsing. Constrained devices upload only on an actual draw.
            // Never decode/upload it again just to change fade ownership.
            if (decoded is BitmapImage && ArtworkImages.memoryPolicy(context).allowEarlyGpuUpload) {
                withContext(ArtworkImages.decodeDispatcher) { decoded.bitmap.prepareToDraw() }
            }
            value = DecodedArtwork(
                key = requestedKey,
                painter = decoded?.asPainter(context) ?: cached?.painter ?: SyntheticArtworkPainter(requestedTrack),
                contentScale = if (requestedTrack.catalogKind == dev.amatv.model.CatalogKind.Station) ContentScale.Fit else ContentScale.Crop,
            )
        }
        var displayed by remember { mutableStateOf(cached) }
        var incoming by remember { mutableStateOf<DecodedArtwork?>(null) }
        val fade = remember { Animatable(0f) }
        val initialPlaceholder = remember { SyntheticArtworkPainter(requestedTrack) }
        LaunchedEffect(ready) {
            snapshotFlow { ready.value }.filterNotNull().collect { next ->
                if (next.key == displayed?.key) {
                    // A cached thumbnail can show synchronously while the same
                    // cover finishes a larger decode. Keep the real cover visible.
                    displayed = next
                    coverState = "ready"
                    return@collect
                }
                if (displayed == null) {
                    displayed = next
                    coverState = "ready"
                } else {
                    // Establish alpha before publishing the new image. The
                    // previous implementation reset it one frame too late.
                    fade.snapTo(0f)
                    incoming = next
                    coverState = "blending"
                    fade.animateTo(1f, tween(360, easing = MotionTokens.KaraokeLineEasing))
                    displayed = next
                    incoming = null
                    coverState = "ready"
                }
            }
        }
        Image(
            painter = displayed?.painter ?: initialPlaceholder,
            contentDescription = stringResource(R.string.player_artwork_description, requestedTrack.title),
            contentScale = displayed?.contentScale ?: ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        incoming?.let { frame ->
            Image(
                painter = frame.painter,
                contentDescription = null,
                contentScale = frame.contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
            )
        }
        // One video surface, only for the currently requested and fully shown
        // artwork. Fading images never create hidden video decoders.
        requestedTrack.motionArtworkUrl?.let {
            EditorialMotionVideo(it, Modifier.fillMaxSize(), sourceAspect,
                enabled = animate && incoming == null && displayed?.key == requestedKey)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.29f)),
        )))
    }
}

private class DecodedArtwork(val key: String, val painter: Painter, val contentScale: ContentScale)

private fun Track.artworkVisualKey(): String =
    motionPreviewUrl ?: artworkUrl ?: "synthetic:$id:${palette.hashCode()}"

@Composable
private fun ArtworkSurface(
    track: Track,
    modifier: Modifier,
    animate: Boolean,
    cornerRadius: androidx.compose.ui.unit.Dp,
    sourceAspect: Float?,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { ArtworkImages.loader(context) }
    val preview = track.motionPreviewUrl?.takeIf { it.startsWith("https://") }
    val original = track.artworkUrl?.takeIf { it.startsWith("https://") }
    var previewFailed by remember(preview, original) { mutableStateOf(false) }
    val primary = preview ?: original
    val url = if (previewFailed) original?.takeUnless { it == primary } else primary
    val placeholder = remember(track.id, track.palette) { SyntheticArtworkPainter(track) }
    val contentScale = if (track.catalogKind == dev.amatv.model.CatalogKind.Station) ContentScale.Fit else ContentScale.Crop
    // Metadata can arrive after the catalog cover. Keep its decoded picture
    // opaque while the matching video poster loads and fades over it.
    var lastPoster by remember(track.id) { mutableStateOf<Painter?>(null) }
    val request = remember(context, url) {
        ImageRequest.Builder(context).data(url).memoryCacheKey(url)
            // Coil reads this synchronously, before any dispatcher/size work.
            // Reuse a smaller cover too while a larger destination decodes.
            .placeholderMemoryCacheKey(url).crossfade(false).build()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(track.palette)),
    ) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = stringResource(R.string.player_artwork_description, track.title),
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            // AsyncImage's placeholder= transform replaces Loading.painter,
            // including Coil's synchronous placeholderMemoryCacheKey result.
            // Keep that real cover; synthesize only a genuinely cold request.
            transform = { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading ->
                        if (state.painter == null) state.copy(painter = lastPoster ?: placeholder) else state
                    is AsyncImagePainter.State.Success -> if (state.result.dataSource == DataSource.MEMORY_CACHE) state
                        else state.copy(painter = CrossfadePainter(
                            start = lastPoster ?: placeholder,
                            end = state.painter,
                            contentScale = contentScale,
                            duration = 180.milliseconds,
                            // Fading both layers exposes the card background on a
                            // cold load. Keep the old cover opaque beneath the new.
                            fadeStart = false,
                        ))
                    is AsyncImagePainter.State.Error -> state.copy(painter = lastPoster ?: placeholder)
                    else -> state
                }
            },
            onState = { state ->
                if (state is AsyncImagePainter.State.Loading && state.painter != null) lastPoster = state.painter
                else if (state is AsyncImagePainter.State.Success) lastPoster = state.painter
                else if (state is AsyncImagePainter.State.Error && !previewFailed &&
                    original != null && preview != null && preview != original) previewFailed = true
            },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f))),
            ),
        )

        // The outgoing picture fades as a still frame; its decoder is returned
        // immediately, so fast DPAD navigation never adds video players.
        track.motionArtworkUrl?.let { url ->
            EditorialMotionVideo(url = url, modifier = Modifier.fillMaxSize(), sourceAspect = sourceAspect, enabled = animate)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.14f)),
                    ),
                ),
        )
    }
}

private class SyntheticArtworkPainter(private val track: Track) : Painter() {
    override val intrinsicSize = Size.Unspecified
    private val variant = (track.id.hashCode() and Int.MAX_VALUE) % 5
    private val background = Brush.linearGradient(track.palette)
    private val vignette = Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))
    private val polygons = when (variant) {
        0 -> listOf(
            floatArrayOf(-0.2f, 0.0f, 0.65f, 0.48f, 0.04f, 1.12f),
            floatArrayOf(1.18f, -0.14f, 0.66f, 0.48f, 0.0f, 0.02f),
            floatArrayOf(1.0f, 1.04f, 0.66f, 0.48f, 0.0f, 1.0f),
        )
        1 -> listOf(
            floatArrayOf(0.52f, -0.05f, 0.18f, 0.45f, 0.72f, 0.45f, 0.35f, 1.06f, 0.88f, 0.36f, 0.35f, 0.36f),
            floatArrayOf(0.0f, 0.58f, 0.38f, 0.58f, 0.0f, 1.0f),
        )
        4 -> listOf(
            floatArrayOf(0.0f, 0.0f, 0.98f, 0.0f, 0.22f, 0.95f),
            floatArrayOf(1.0f, 0.18f, 1.0f, 1.0f, 0.1f, 1.0f),
            floatArrayOf(0.3f, 0.2f, 0.95f, 0.26f, 0.58f, 0.85f),
        )
        else -> emptyList()
    }
    private var geometrySize = Size.Unspecified
    private var paths: List<Path> = emptyList()
    private var highlight: Brush? = null

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        // Coil crossfades redraw this painter every frame. Geometry and shader
        // inputs depend on the destination size, never on fade/focus progress.
        if (geometrySize != size) {
            geometrySize = size
            paths = polygons.map { points -> Path().apply {
                moveTo(points[0] * w, points[1] * h)
                for (index in 2 until points.size step 2) lineTo(points[index] * w, points[index + 1] * h)
                close()
            } }
            if (variant == 2) highlight = Brush.radialGradient(
                listOf(Color(0xFFF1A7D0), track.palette[1], Color.Transparent),
                center = Offset(w * 0.54f, h * 0.42f), radius = w * 0.58f,
            )
        }
        drawRect(
            brush = background,
        )
        when (variant) {
            0 -> {
                drawPath(
                    paths[0],
                    color = track.palette.first().copy(alpha = 0.84f),
                )
                drawPath(
                    paths[1],
                    color = Color(0xFF25B7B0).copy(alpha = 0.62f),
                )
                drawPath(
                    paths[2],
                    color = track.palette.last().copy(alpha = 0.58f),
                )
            }
            1 -> {
                drawPath(
                    paths[0],
                    color = Color(0xFFD5F65A).copy(alpha = 0.9f),
                )
                drawPath(
                    paths[1],
                    color = Color(0xFF2DDBB9).copy(alpha = 0.82f),
                )
            }
            2 -> {
                drawCircle(
                    brush = checkNotNull(highlight),
                    radius = w * 0.62f,
                    center = Offset(w * 0.54f, h * 0.42f),
                )
                drawCircle(
                    color = track.palette.first().copy(alpha = 0.56f),
                    radius = w * 0.27f,
                    center = Offset(w * 0.54f, h * 0.42f),
                )
            }
            3 -> {
                drawCircle(
                    color = Color(0xFFFF5A69).copy(alpha = 0.68f),
                    radius = w * 0.74f,
                    center = Offset(w * 0.65f, h * 0.62f),
                )
                drawCircle(
                    color = track.palette.first().copy(alpha = 0.78f),
                    radius = w * 0.48f,
                    center = Offset(w * 0.65f, h * 0.62f),
                )
                drawCircle(
                    color = Color(0xFFFFC8A9).copy(alpha = 0.36f),
                    radius = w * 0.20f,
                    center = Offset(w * 0.65f, h * 0.62f),
                )
            }
            else -> {
                drawPath(
                    paths[0],
                    color = track.palette[1].copy(alpha = 0.7f),
                )
                drawPath(
                    paths[1],
                    color = Color(0xFFB7DA50).copy(alpha = 0.75f),
                )
                drawPath(
                    paths[2],
                    color = track.palette.first().copy(alpha = 0.5f),
                )
            }
        }
        drawRect(vignette)
    }
}

private val ArtworkBlendEasing = CubicBezierEasing(0.25f, 0f, 0.25f, 1f)

private data class RetainedMotionLayer(val lease: MotionPreviewLease, val alpha: Float)

@Composable
private fun EditorialMotionVideo(
    url: String,
    modifier: Modifier = Modifier,
    sourceAspect: Float? = null,
    enabled: Boolean = true,
) {
    if (!url.startsWith("https://")) return
    var keepLayer by remember(url) { mutableStateOf(false) }
    if (!enabled && !keepLayer) return
    SideEffect { if (enabled) keepLayer = true }
    val context = LocalContext.current
    val owner = remember(url) { Any() }
    val opacity = remember(owner) { Animatable(0f) }
    var retained by remember(owner) { mutableStateOf(emptyList<RetainedMotionLayer>()) }
    val attachedView = remember(owner) { arrayOfNulls<EditorialTextureView>(1) }
    DisposableEffect(owner) {
        onDispose {
            // A departing view relinquishes its surface before the coordinator
            // can attach this same renderer to the next owner.
            attachedView[0]?.detachPlayer()
            attachedView[0] = null
            MotionPreviewCoordinator.lease?.takeIf { it.owner === owner }?.retainSurface = null
            MotionPreviewCoordinator.withdraw(owner)
        }
    }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val bounds = with(density) {
            motionDecodeBounds(maxWidth.toPx() * 1.25f, maxHeight.toPx() * 1.25f, sourceAspect)
        }
        SideEffect {
            // A second interruption can freeze both sides of a partial blend.
            // Finish that short poster fade before starting another surface;
            // repeated reversals must not grow a stack of retained textures.
            if (enabled && retained.size < 2) MotionPreviewCoordinator.request(owner, context, url, bounds.width, bounds.height)
            else MotionPreviewCoordinator.withdraw(owner)
        }
        val active = MotionPreviewCoordinator.lease?.takeIf { it.owner === owner && it.url == url }
        val ready = enabled && active?.firstFrameReady == true && !active.failed
        val videoBlend = remember(active?.mediaId) { Animatable(0f) }
        LaunchedEffect(ready, active?.mediaId) {
            if (ready) {
                if (retained.isEmpty()) videoBlend.snapTo(1f)
                coroutineScope {
                    launch { opacity.animateTo(1f, tween(420, easing = ArtworkBlendEasing)) }
                    if (retained.isNotEmpty()) videoBlend.animateTo(1f, tween(420, easing = ArtworkBlendEasing))
                }
                retained = emptyList()
                android.util.Log.i("AMATV-Motion", "Editorial blend finished mediaId=${active?.mediaId}")
            } else if (retained.isNotEmpty()) {
                val outgoing = retained.last().lease.mediaId
                android.util.Log.i("AMATV-Motion", "Editorial poster blend started mediaId=$outgoing")
                opacity.animateTo(0f, tween(420, easing = ArtworkBlendEasing))
                retained = emptyList()
                android.util.Log.i("AMATV-Motion", "Editorial poster blend finished mediaId=$outgoing")
                if (!enabled) keepLayer = false
            } else {
                opacity.snapTo(0f)
                if (!enabled) keepLayer = false
            }
        }
        val visible = (retained.map { it.lease } + listOfNotNull(active)).distinctBy { it.mediaId }
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = opacity.value
            // A single TextureView needs no extra Compose offscreen target just
            // to fade. During an interrupted two-texture blend retain group
            // compositing so the combined outgoing/incoming opacity stays exact.
            compositingStrategy = if (visible.size <= 1) CompositingStrategy.ModulateAlpha else CompositingStrategy.Auto
        }) {
            visible.forEach { candidate -> key(candidate.mediaId) {
                val incoming = candidate === active
                val retainedAlpha = retained.firstOrNull { it.lease === candidate }?.alpha ?: 1f
                AndroidView(
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (incoming) videoBlend.value else retainedAlpha },
                    factory = { viewContext ->
                        EditorialTextureView(viewContext).apply {
                            tag = candidate.mediaId
                            if (incoming) bindPlayer(candidate, sourceAspect)
                        }
                    },
                    update = { view ->
                        if (incoming) {
                            attachedView[0] = view
                            view.bindPlayer(candidate, sourceAspect)
                            candidate.retainSurface = {
                                if (candidate.firstFrameReady && opacity.value > 0f && videoBlend.value > 0f && view.isAvailable &&
                                    view.tag == candidate.mediaId) {
                                    // Keep this keyed TextureView mounted. Detaching
                                    // its player returns the ONLY decoder. The
                                    // still-mounted TextureView retains its last
                                    // GPU buffer and crop transform for the fade,
                                    // without readback, Bitmap or re-upload.
                                    view.detachPlayer()
                                    if (attachedView[0] === view) attachedView[0] = null
                                    // Keep the actual composite when focus leaves
                                    // mid-blend. Promoting this partial incoming
                                    // texture to alpha=1 and dropping its base
                                    // produces a visible flash on rapid reversal.
                                    retained = (retained + RetainedMotionLayer(candidate, videoBlend.value))
                                        .distinctBy { it.lease.mediaId }
                                    if (retained.size == 2) android.util.Log.i("AMATV-Motion",
                                        "Editorial interrupted blend retained layers=2 incomingAlpha=${videoBlend.value}")
                                }
                            }
                        } else {
                            // An outgoing view is decorative retained GPU content;
                            // it must never rebind the shared playback renderer.
                            view.detachPlayer()
                            candidate.retainSurface = null
                        }
                    },
                    onRelease = { view ->
                        view.detachPlayer()
                        if (attachedView[0] === view) attachedView[0] = null
                        candidate.retainSurface = null
                    },
                )
            } }
        }
    }
}

/** One loader for thumbnails and palette requests, with bounded background decoding. */
internal object ArtworkImages {
    val decodeDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "AMATV-artwork").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    @Volatile private var shared: ImageLoader? = null

    fun memoryPolicy(context: android.content.Context): ArtworkMemoryPolicy = artworkMemoryPolicy(
        Runtime.getRuntime().maxMemory(), Process.is64Bit(),
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.isLowRamDevice == true,
    )

    // No separate bitmap map: every consumer and preloader shares Coil's LRU.
    // Its strong-cache budget is not a total-memory cap: active painters can
    // retain evicted images, and weak entries remain collectable until GC.
    fun cachedImage(context: android.content.Context, url: String?): coil3.Image? =
        url?.let { loader(context).memoryCache?.get(MemoryCache.Key(it))?.image }

    /** The next queue covers are useful immediately on the playback screen. */
    suspend fun prefetch(context: android.content.Context, url: String) =
        prefetchDecoded(context, url, 640, 640)

    suspend fun prefetchDecoded(context: android.content.Context, url: String, widthPx: Int, heightPx: Int) {
        if (!url.startsWith("https://")) return
        val cache = ContentCache.get(context)
        if (!cache.settings.value.let { it.enabled && it.prefetchNextTwo && !it.clearing }) return
        if (!withContext(Dispatchers.IO) { cache.canWrite() }) return
        val result = loader(context).execute(ImageRequest.Builder(context).data(url)
            .memoryCacheKey(url).size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1)).build())
        if (result is SuccessResult && result.image is BitmapImage && memoryPolicy(context).allowEarlyGpuUpload) {
            withContext(decodeDispatcher) { (result.image as BitmapImage).bitmap.prepareToDraw() }
        }
    }

    suspend fun clear(context: android.content.Context) = withContext(Dispatchers.IO) {
        loader(context).diskCache?.clear()
        loader(context).memoryCache?.clear()
        synchronized(paletteCacheLock) { paletteCache.evictAll() }
        MotionVideoCache.clear(context)
    }

    fun diskBytes(context: android.content.Context): Long =
        (loader(context).diskCache?.size ?: 0L) + MotionVideoCache.diskBytes(context)

    fun loader(context: android.content.Context): ImageLoader = shared ?: synchronized(this) {
        shared ?: run {
            val policy = memoryPolicy(context)
            ImageLoader.Builder(context.applicationContext)
            // On constrained TVs retain software decodes in the shared LRU;
            // only an actual draw asks HWUI for a GPU texture. Prefetch must not
            // pin hardware buffers ahead of the visible page.
            .allowHardware(policy.allowHardware)
            .components { add(ArtworkCachePolicy(ContentCache.get(context)) { shared }) }
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(policy.decodedCacheBytes).build()
            }
            .diskCache {
                DiskCache.Builder().directory(context.applicationContext.cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(ContentCache.get(context).artworkCapacityBytes).build()
            }
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(4))
            .decoderCoroutineContext(decodeDispatcher)
            .build().also { shared = it }
        }
    }
}

/** Check space before every request, not only when the loader was constructed. */
private class ArtworkCachePolicy(
    private val cache: ContentCache,
    private val imageLoader: () -> ImageLoader?,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult = withContext(Dispatchers.IO) {
        val generation = cache.generation
        val settings = cache.settings.value
        val policy = when {
            !settings.enabled || settings.clearing -> CachePolicy.DISABLED
            !cache.canWrite() -> if (chain.request.diskCachePolicy.readEnabled) CachePolicy.READ_ONLY else CachePolicy.DISABLED
            else -> chain.request.diskCachePolicy
        }
        val result = chain.withRequest(chain.request.newBuilder().diskCachePolicy(policy).build()).proceed()
        if (generation != cache.generation && result is SuccessResult) {
            result.diskCacheKey?.let { imageLoader()?.diskCache?.remove(it) }
            result.memoryCacheKey?.let { imageLoader()?.memoryCache?.remove(it) }
        }
        result
    }
}

private val paletteCacheLock = Any()
private val paletteCache = object : android.util.LruCache<String, List<Color>>(64) {}

@Composable
internal fun rememberArtworkPalette(url: String?, fallback: List<Color>): List<Color> {
    val context = LocalContext.current.applicationContext
    val cache = ContentCache.get(context)
    val default = remember(fallback) {
        ArtworkPalette.extract(fallback.map { it.toArgb() }.toIntArray()).map { Color(it) }
            .ifEmpty { List(5) { Color(0xFF233645) } }
    }
    return produceState(initialValue = url?.let(paletteCache::get) ?: default, url, default) {
        // produceState retains its previous value when a key changes. Establish
        // the new artwork's own fallback before a decode can fail or suspend.
        value = url?.let(paletteCache::get) ?: default
        if (url == null || !url.startsWith("https://")) {
            value = default
            return@produceState
        }
        paletteCache.get(url)?.let { value = it; return@produceState }
        val requestGeneration = cache.generation
        val request = ImageRequest.Builder(context).data(url)
            .size(48).allowHardware(false).memoryCacheKey("palette:$url").build()
        val image = ArtworkImages.loader(context).execute(request).image ?: return@produceState
        val colors = withContext(ArtworkImages.decodeDispatcher) {
            val bitmap = image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArtworkPalette.extract(pixels).map { Color(it) }
        }
        if (colors.isNotEmpty()) synchronized(paletteCacheLock) {
            if (requestGeneration == cache.generation) {
                val settings = cache.settings.value
                if (settings.enabled && !settings.clearing) paletteCache.put(url, colors)
                // Disabling cache storage must not freeze the current artwork's colors.
                value = colors
            }
        }
    }.value
}
