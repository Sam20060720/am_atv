package dev.amatv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import coil3.EventListener
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Buffer
import okio.FileSystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Benchmark APK only. No accounts, playback, production loader hook or real network. */
class ArtworkCacheProbeActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var phase by mutableIntStateOf(0)
    private var report by mutableStateOf("preparing")
    private val bounds = arrayOfNulls<Rect>(3)
    private val samples = JSONArray()
    private val capturedMounts = mutableSetOf<Int>()
    private val fetches = AtomicInteger()
    private val decodes = AtomicInteger()
    private lateinit var fixtureLoader: ImageLoader
    private val loaderField by lazy {
        ArtworkImages::class.java.getDeclaredField("shared").apply { isAccessible = true }
    }
    private var originalLoader: ImageLoader? = null
    private var sharedCacheHit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Rendering probe is emulator only" }
        val url = "https://amatv-cache-probe.invalid/cover-${SystemClock.elapsedRealtimeNanos()}.png"
        fun image(color: Int) = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }
        val png = image(android.graphics.Color.rgb(32, 208, 96))
        val pairedPng = if (intent.getBooleanExtra("artwork_lifecycle_probe", false))
            image(android.graphics.Color.rgb(32, 96, 224)) else png
        fixtureLoader = ImageLoader.Builder(this)
            .allowHardware(false) // The synchronous logical-frame raster below uses a software Canvas.
            .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
            .components {
                add(object : Fetcher.Factory<Uri> {
                    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                        if (data.authority != "amatv-cache-probe.invalid") return null
                        return object : Fetcher {
                            override suspend fun fetch(): SourceFetchResult {
                                fetches.incrementAndGet()
                                delay(150)
                                val bytes = if (data.toString().contains("paired-poster")) pairedPng else png
                                return SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), "image/png", DataSource.NETWORK)
                            }
                        }
                    }
                })
            }
            .eventListener(object : EventListener() {
                override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
                    decodes.incrementAndGet()
                }
            }).build()
        originalLoader = loaderField.get(null) as? ImageLoader
        loaderField.set(null, fixtureLoader)

        if (intent.getBooleanExtra("artwork_lifecycle_probe", false)) {
            ArtworkLifecycleProbe(this, scope, fixtureLoader, url, fetches, decodes).start()
            return
        }

        val track = Track("cache-probe", "Cache probe", "Fixture", "Fixture", 6_000_000L,
            listOf(Color.Magenta, Color.Red), emptyList(), artworkUrl = url)
        val root = LogicalFrameCapture(this)
        val compose = ComposeView(this).apply {
            setContent {
                Column(Modifier.background(Color(0xFF303030)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (phase == 4) report else "phase=$phase fetches=${fetches.get()} decodes=${decodes.get()}",
                        Modifier.automationId(if (phase == 4) "artwork-cache-probe-result" else "artwork-cache-probe-status"), color = Color.White)
                    if (phase == 1 || phase == 3 || phase == 4) key(if (phase == 1) 1 else 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            repeat(3) { index ->
                                val modifier = Modifier.size(96.dp).automationId("cache-probe-artwork-$index").onGloballyPositioned {
                                    val b = it.boundsInRoot()
                                    bounds[index] = Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
                                }
                                if (index == 2) Box(modifier) { HomeCardPlaceholder(track, featured = false) }
                                else MotionArtwork(track, modifier, animate = false, crossfadeTrackChanges = index == 1)
                            }
                        }
                    } else Spacer(Modifier.height(96.dp))
                    Column(Modifier.width(300.dp)) {
                        TvPlaybackProgress(mutableStateOf(5_999_000L), 6_000_000L, true, null)
                    }
                }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.needsCapture = { phase == 2 || (phase == 1 || phase == 3) && phase !in capturedMounts }
        // A child's RenderNode may update without re-recording its parent's
        // dispatchDraw. Force only the pending capture frames to re-record this
        // benchmark wrapper; otherwise the first Compose update is never sampled.
        root.viewTreeObserver.addOnPreDrawListener {
            if (root.needsCapture()) root.invalidate()
            true
        }
        root.capture = { bitmap ->
            if ((phase == 1 || phase == 3) && phase !in capturedMounts && bounds.all { it != null }) {
                capturedMounts.add(phase)
                samples.put(JSONArray(bounds.map { rect ->
                    val b = checkNotNull(rect)
                    bitmap.getPixel(b.centerX().coerceIn(0, bitmap.width - 1),
                        (b.top + b.height() / 4).coerceIn(0, bitmap.height - 1))
                }))
                if (phase == 1) root.post { bounds.fill(null); phase = 2 }
                else root.post {
                    // Keep the remounted compositions alive while their normal Coil requests settle.
                    scope.launch {
                        delay(400)
                        report = JSONObject().put("shared_decoded_cache_hit", sharedCacheHit)
                            .put("fetches", fetches.get()).put("decodes", decodes.get())
                            .put("first_draw_pixels", samples).toString()
                        phase = 4
                    }
                }
            } else if (phase == 2) root.post { phase = 3 }
        }
        setContentView(root)
        scope.launch {
            try {
                val pixels = (96 * resources.displayMetrics.density).roundToInt()
                ArtworkImages.prefetchDecoded(this@ArtworkCacheProbeActivity, url, pixels, pixels)
                val first = ArtworkImages.cachedImage(this@ArtworkCacheProbeActivity, url)
                ArtworkImages.prefetchDecoded(this@ArtworkCacheProbeActivity, url, pixels, pixels)
                val result = fixtureLoader.execute(ImageRequest.Builder(this@ArtworkCacheProbeActivity).data(url)
                    .memoryCacheKey(url).size(pixels).build())
                sharedCacheHit = first != null && result is SuccessResult && result.dataSource == DataSource.MEMORY_CACHE && result.image === first
                phase = 1
            } catch (error: Throwable) {
                report = JSONObject().put("error", error.toString()).toString()
                phase = 4
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (::fixtureLoader.isInitialized) {
            if (loaderField.get(null) === fixtureLoader) loaderField.set(null, originalLoader)
            fixtureLoader.shutdown()
        }
        super.onDestroy()
    }

    /** Sample the content of the exact logical draw, without waiting for a later screenshot. */
    internal class LogicalFrameCapture(context: Context) : FrameLayout(context) {
        var capture: ((Bitmap) -> Unit)? = null
        var needsCapture: () -> Boolean = { false }
        private var copying = false
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (copying || width == 0 || height == 0 || !needsCapture()) return
            copying = true
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // Benchmark-only secondary raster; it must never be used for timing/GPU claims.
                super.dispatchDraw(Canvas(bitmap))
                capture?.invoke(bitmap)
            } finally {
                bitmap.recycle()
                copying = false
            }
        }
    }
}
