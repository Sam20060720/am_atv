package dev.amatv.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.amatv.integration.PublicEditorialMotionClient
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Deterministic lifecycle evidence only; never a production route or a timing benchmark. */
internal class ArtworkLifecycleProbe(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val imageLoader: ImageLoader,
    private val catalogPoster: String,
    private val fetches: AtomicInteger,
    private val decodes: AtomicInteger,
) {
    private val pairedPoster = "${catalogPoster.substringBeforeLast('/')}/paired-poster.png"
    private val track = Track("lifecycle-album", "Lifecycle album", "Fixture", "Fixture", 0,
        listOf(Color.Magenta, Color.Red), emptyList(), catalogId = "42", catalogKind = CatalogKind.Album,
        artworkUrl = catalogPoster, resourceId = "42", resourceType = "albums")
    private val metadataGate = CompletableDeferred<Unit>()
    private var metadataReads by mutableIntStateOf(0)
    private val metadata = PublicEditorialMotionClient({ "tw" }, { "zh-TW" }, { _, _ ->
        withContext(Dispatchers.Main.immediate) { metadataReads++ }
        metadataGate.await()
        metadataPage()
    })
    private val list = LazyListState()
    private val root = ArtworkCacheProbeActivity.LogicalFrameCapture(activity)
    private val frames = JSONObject()
    private val output = File(checkNotNull(activity.getExternalFilesDir(null)),
        "artwork-lifecycle/${activity.intent.getStringExtra("run_id")?.also {
            require(it.matches(Regex("[A-Za-z0-9_.-]+")))
        } ?: SystemClock.elapsedRealtimeNanos().toString()}").also { check(!it.exists() && it.mkdirs()) }
    private var phase by mutableStateOf("preparing")
    private var animate by mutableStateOf(false)
    private var motionEnabled by mutableStateOf(true)
    private var mounts by mutableIntStateOf(0)
    private var disposals by mutableIntStateOf(0)
    private val rememberedInstances = AtomicInteger()
    private var reportRevision by mutableIntStateOf(0)
    private var mounted = false
    private var lateResult = false
    private var busy = false
    private var error: String? = null
    private var coverBounds: Rect? = null
    private var captureLabel: String? = null
    private var capturedLabel: String? = null

    fun start() {
        val compose = ComposeView(activity).apply {
            setContent {
                val focus = remember { FocusRequester() }
                val loader = remember { EditorialArtworkLoader(
                    cached = { metadata.cachedArtwork(it, "tw") }, load = metadata::artwork) }
                // Metadata hydration uses the real path; suppress the fixture's
                // decorative decoder so it cannot try its deliberately invalid URL.
                reportRevision
                Column(Modifier.background(Color(0xFF303030)).padding(24.dp)
                    .focusRequester(focus).onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.keyCode != KeyEvent.KEYCODE_DPAD_CENTER) false
                        else {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0 && !busy) next()
                            true
                        }
                    }.focusable(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(report().toString(), Modifier.height(72.dp).automationId("artwork-lifecycle-report"),
                        color = Color.White, fontSize = 10.sp, maxLines = 4)
                    Text("Center advances the deterministic fixture", color = Color.White)
                    CompositionLocalProvider(LocalEditorialArtworkLoader provides loader,
                        LocalArtworkMotionEnabled provides motionEnabled,
                        LocalBrowseMotionEnabled provides false) {
                        LazyColumn(state = list, modifier = Modifier.height(240.dp).fillMaxWidth(), userScrollEnabled = false) {
                            items((0 until 32).toList(), key = { "lifecycle-$it" }) { index ->
                                if (index == 0) {
                                    val rememberedInstance = remember { rememberedInstances.incrementAndGet() }
                                    DisposableEffect(rememberedInstance) {
                                        mounted = true
                                        mounts++
                                        onDispose { mounted = false; disposals++; coverBounds = null }
                                    }
                                    MotionArtwork(track, Modifier.size(96.dp).automationId("lifecycle-cover")
                                        .onGloballyPositioned { coordinates ->
                                            val b = coordinates.boundsInRoot()
                                            coverBounds = Rect(b.left.roundToInt(), b.top.roundToInt(),
                                                b.right.roundToInt(), b.bottom.roundToInt())
                                        }, animate = animate)
                                } else Box(Modifier.height(104.dp).fillMaxWidth().background(Color.DarkGray)) {
                                    Text("Lazy item $index", color = Color.White)
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // A remounted child may update its own RenderNode without recording the
        // parent. Arm invalidation before its first placement supplies bounds;
        // waiting for bounds here can leave the pending capture with no frame.
        root.needsCapture = { captureLabel != null && capturedLabel != captureLabel }
        root.viewTreeObserver.addOnPreDrawListener { if (root.needsCapture()) root.invalidate(); true }
        root.capture = { bitmap ->
            val label = captureLabel
            val b = coverBounds
            if (label != null && capturedLabel != label && b != null) {
                capturedLabel = label
                val pixel = bitmap.getPixel(b.centerX().coerceIn(0, bitmap.width - 1),
                    (b.top + b.height() / 4).coerceIn(0, bitmap.height - 1))
                frames.put(label, JSONObject().put("pixel", pixel).put("mounts", mounts)
                    .put("disposals", disposals).put("image_fetches", fetches.get()).put("image_decodes", decodes.get()))
                File(output, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                root.post { captureLabel = null; reportRevision++ }
            }
        }
        activity.setContentView(root)
        scope.launch {
            try {
                // Warm only the two image bytes, not optional metadata. A known
                // paired poster can then be distinguished on the first remount draw.
                val coverPixels = (96 * activity.resources.displayMetrics.density).roundToInt()
                for (url in listOf(catalogPoster, pairedPoster)) {
                    check(imageLoader.execute(ImageRequest.Builder(activity).data(url).memoryCacheKey(url).size(coverPixels).build()) is SuccessResult)
                }
                delay(220)
                capture("initial-catalog")
                phase = "ready"
            } catch (failure: Throwable) { fail(failure) }
        }
    }

    private fun next() {
        busy = true
        scope.launch {
            try {
                when (phase) {
                    "ready" -> { animate = true; await { metadataReads == 1 }; phase = "metadata-blocked" }
                    "metadata-blocked" -> { animate = false; leave(); phase = "disposed-before-result" }
                    "disposed-before-result" -> {
                        check(!mounted && disposals > 0)
                        metadataGate.complete(Unit)
                        await { metadata.cachedArtwork(track, "tw").motionArtworkUrl != null }
                        lateResult = !mounted
                        phase = "late-result-cached"
                    }
                    "late-result-cached" -> { captureLabel = "idle-remount"; list.scrollToItem(0); awaitCaptured("idle-remount"); phase = "remounted" }
                    "remounted" -> { motionEnabled = false; capture("motion-disabled"); phase = "motion-disabled" }
                    "motion-disabled" -> { motionEnabled = true; capture("motion-enabled"); phase = "motion-enabled" }
                    "motion-enabled" -> {
                        leave()
                        imageLoader.memoryCache?.clear()
                        captureLabel = "evicted-first-draw"
                        list.scrollToItem(0)
                        awaitCaptured("evicted-first-draw")
                        await { ArtworkImages.cachedImage(activity, pairedPoster) != null }
                        delay(300) // Allow the normal 180ms Coil image blend to finish.
                        capture("evicted-settled")
                        phase = "complete"
                    }
                }
                reportRevision++
                File(output, "result.json").writeText(report().toString(2))
            } catch (failure: Throwable) { fail(failure) }
            finally { busy = false }
        }
    }

    private suspend fun leave() {
        val before = disposals
        list.scrollToItem(24)
        await { !mounted && disposals > before }
    }

    private suspend fun capture(label: String) {
        captureLabel = label
        root.invalidate()
        awaitCaptured(label)
    }
    private suspend fun awaitCaptured(label: String) = await { frames.has(label) && captureLabel != label }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }
    private fun report() = JSONObject().put("phase", phase).put("metadata_reads", metadataReads)
        .put("mounts", mounts).put("disposals", disposals).put("late_result_completed_while_disposed", lateResult)
        .put("remembered_instances", rememberedInstances.get())
        .put("frames", frames).put("output_directory", output.absolutePath).also { result -> error?.let { result.put("error", it) } }
    private fun fail(failure: Throwable) {
        error = failure.toString()
        phase = "failed"
        reportRevision++
        File(output, "result.json").writeText(report().toString(2))
    }

    private fun metadataPage(): String {
        fun descriptor() = JSONObject().put("kind", "album").put("identifiers", JSONObject().put("storeAdamID", "42"))
        val video = JSONObject().put("motionDetailSquare", JSONObject()
            .put("video", "https://amatv-cache-probe.invalid/fixture.m3u8")
            .put("previewFrame", JSONObject().put("width", 512).put("height", 512).put("url", pairedPoster)))
        val hero = JSONObject().put("contentDescriptor", descriptor()).put("videoArtwork", JSONObject().put("dictionary", video))
        val intent = JSONObject().put("\$kind", "AlbumDetailPageIntent")
            .put("contentDescriptor", descriptor().put("locale", JSONObject().put("storefront", "tw")))
        val root = JSONObject().put("data", JSONArray().put(JSONObject().put("intent", intent)
            .put("data", JSONObject().put("sections", JSONArray().put(JSONObject().put("itemKind", "containerDetailHeaderLockup")
                .put("items", JSONArray().put(hero)))))))
        return "<script type='application/json' id='serialized-server-data'>$root</script>"
    }
}
