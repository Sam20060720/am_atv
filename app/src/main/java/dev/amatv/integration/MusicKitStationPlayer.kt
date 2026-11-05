package dev.amatv.integration

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Official MusicKit JS station playback, kept separate from the song-only Android
 * SDK. A WebView/provider is a request route, not proof of streaming/DRM support.
 * No authorization popup, extracted media URL, or preview-as-full-playback fallback.
 */
internal class MusicKitStationPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    @Volatile private var requested: Track? = null
    @Volatile private var desiredPlaying = false
    @Volatile private var latest: PlaybackSnapshot? = null
    private var listener: ((PlaybackSnapshot) -> Unit)? = null
    @Volatile private var webView: WebView? = null
    private var backgroundHost: StationBackgroundHost? = null
    private var ready = false
    private var failedEpoch = -1L
    private var timeout: Runnable? = null
    private var lastLoggedSignature: String? = null
    private var observedPlaying = false
    private val loggedConsoleCategories = mutableSetOf<String>()
    private var lastLoggedPositionMs = -1L
    private val progressWatchdog = StationProgressWatchdog()
    @Volatile private var spectrumRequested = false
    private data class SpectrumFrame(val epoch: Long, val capturedAtMs: Long, val value: AudioSpectrum,
        val state: SpectrumCaptureState)
    @Volatile private var spectrumFrame = SpectrumFrame(0L, 0L, AudioSpectrum(), SpectrumCaptureState.Disabled)
    private var loggedSpectrumState: SpectrumCaptureState? = null
    private var loggedSpectrumSignalEpoch = -1L
    private var loggedSpectrumReason = ""

    fun audioSpectrum(): AudioSpectrum {
        val frame = spectrumFrame
        return if (spectrumRequested && desiredPlaying && frame.epoch == generation.get() &&
            SystemClock.elapsedRealtime() - frame.capturedAtMs in 0L..<250L) frame.value else AudioSpectrum()
    }

    fun spectrumCaptureState(): SpectrumCaptureState {
        if (!spectrumRequested || !desiredPlaying) return SpectrumCaptureState.Disabled
        val frame = spectrumFrame
        return if (frame.epoch != generation.get() || frame.state == SpectrumCaptureState.Capturing &&
            SystemClock.elapsedRealtime() - frame.capturedAtMs >= 250L) SpectrumCaptureState.WaitingForCapture else frame.state
    }

    fun setSpectrumActive(enabled: Boolean) {
        if (spectrumRequested == enabled) return
        spectrumRequested = enabled
        spectrumFrame = SpectrumFrame(generation.get(), 0L, AudioSpectrum(),
            if (enabled) SpectrumCaptureState.WaitingForCapture else SpectrumCaptureState.Disabled)
        val epoch = generation.get()
        main.post { if (epoch == generation.get()) sendSpectrumIntent() }
    }

    private fun sendSpectrumIntent() {
        webView?.evaluateJavascript("window.amatvSetSpectrumActive && window.amatvSetSpectrumActive(" + spectrumRequested + ");", null)
    }

    /** WebView bridge thread only; never sends FFT frames through playback/state callbacks. */
    private fun acceptSpectrum(raw: String) {
        if (raw.length > 512 || !spectrumRequested || !desiredPlaying) return
        val data = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val epoch = data.optLong("epoch", -1L)
        if (epoch != generation.get()) return
        val state = when (data.optString("status")) {
            "capturing" -> SpectrumCaptureState.Capturing
            "waiting" -> SpectrumCaptureState.WaitingForCapture
            "unavailable" -> SpectrumCaptureState.Unavailable
            "disabled" -> SpectrumCaptureState.Disabled
            else -> return
        }
        fun level(key: String): Float? = (data.opt(key) as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }?.toFloat()
        val bass = level("bass") ?: return
        val mid = level("mid") ?: return
        val treble = level("treble") ?: return
        val captured = data.opt("captured") as? Boolean ?: return
        val available = data.opt("available") as? Boolean ?: return
        val rate = (data.opt("rate") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return
        if (captured && (state != SpectrumCaptureState.Capturing || rate !in 8_000.0..384_000.0)) return
        if (available && (!captured || maxOf(bass, mid, treble) <= 0f)) return
        val value = if (captured) AudioSpectrum(bass, mid, treble, available, captured = true) else AudioSpectrum()
        if (!spectrumRequested || !desiredPlaying || epoch != generation.get()) return
        spectrumFrame = SpectrumFrame(epoch, SystemClock.elapsedRealtime(), value, state)
        val reason = if (BuildConfig.BUILD_TYPE == "benchmark") data.optString("reason")
            .takeIf { it.matches(Regex("[a-z_]{1,32}")) }.orEmpty() else ""
        val details = if (BuildConfig.BUILD_TYPE == "benchmark") {
            val error = data.optString("errorName").takeIf { it.matches(Regex("[A-Za-z0-9_]{0,48}")) }.orEmpty()
            val contextState = data.optString("contextState").takeIf { it in setOf("none", "running", "suspended", "closed", "interrupted") }.orEmpty()
            " reason=$reason captureApi=${data.optBoolean("captureApi")} contextApi=${data.optBoolean("contextApi")} " +
                "mediaKeys=${data.optBoolean("mediaKeys")} sameOriginBlob=${data.optBoolean("sameOriginBlob")} " +
                "graphMode=${data.optString("graphMode").takeIf { it in setOf("capture_stream", "media_element") }.orEmpty()} contextState=$contextState error=$error"
        } else ""
        if (loggedSpectrumState != state || available && loggedSpectrumSignalEpoch != epoch || reason != loggedSpectrumReason) {
            Log.i(TAG, "Station spectrum generation=$epoch state=$state captured=$captured signal=$available rate=${rate.toInt()}$details")
            loggedSpectrumState = state
            loggedSpectrumReason = reason
            if (available) loggedSpectrumSignalEpoch = epoch
        }
    }
    // Country-to-numeric storefront protocol IDs from Apple's official
    // availableStoreFronts table. One tiny local read; never delay playback on mapping network I/O.
    private val storefrontNumericIds: Map<String, String> by lazy {
        runCatching {
            val data = context.applicationContext.assets.open("apple-storefront-ids.json").bufferedReader().use { JSONObject(it.readText()) }
            data.keys().asSequence().mapNotNull { country ->
                val numeric = data.optString(country)
                if (country.matches(Regex("[a-z]{2}")) && numeric.matches(Regex("[1-9][0-9]{2,9}"))) country to numeric else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    // Structural route availability must not depend on whether a new Activity
    // has reached its attach effect yet. Starting a new view still requires a host.
    val available: Boolean get() = runCatching {
        WebView.getCurrentWebViewPackage() != null
    }.getOrDefault(false)

    fun preparePlayback(track: Track, developerToken: String, userToken: String,
        positionMs: Long = 0L, playWhenReady: Boolean = true,
        storefront: String): Long {
        require(track.catalogKind == CatalogKind.Station)
        val id = track.resourceId ?: track.catalogId
        require(!id.isNullOrBlank())
        check(available) { AppText.text(R.string.services_station_unsupported) }
        val epoch = generation.incrementAndGet()
        requested = track
        latest = null
        desiredPlaying = playWhenReady
        spectrumFrame = SpectrumFrame(epoch, 0L, AudioSpectrum(), if (spectrumRequested) SpectrumCaptureState.WaitingForCapture else SpectrumCaptureState.Disabled)
        main.post {
            if (epoch != generation.get()) return@post
            ready = false
            lastLoggedSignature = null
            lastLoggedPositionMs = -1L
            observedPlaying = false
            loggedConsoleCategories.clear()
            Log.i(TAG, "Preparing station generation=$epoch")
            failedEpoch = -1L
            timeout?.let(main::removeCallbacks)
            publish(epoch, JSONObject().put("buffering", true).put("prepared", false))
            try {
                val view = webView ?: createView().also { webView = it }
                // Navigation destroys the previous JS queue and its audio element.
                // A stale setQueue promise cannot replace this request's station.
                view.stopLoading()
                view.loadDataWithBaseURL(ORIGIN, stationPage(epoch, id, developerToken, userToken,
                    positionMs.coerceAtLeast(0L), storefront), "text/html", "UTF-8", null)
                armStartupDeadline(epoch)
            } catch (failure: Exception) {
                fail(epoch, if (failure is java.io.IOException) PlaybackFailureKind.Network else PlaybackFailureKind.Unexpected)
            }
        }
        return epoch
    }

    /** Retained playback always has a window, including retry after Activity finish. */
    fun attachHost(owner: Activity?) {
        val attach = {
            webView?.let { view ->
                val parent = view.parent as? ViewGroup
                val target = try { hostContainer(owner) } catch (_: java.io.IOException) {
                    fail(generation.get(), PlaybackFailureKind.Network)
                    return@let
                }
                if (parent !== target) {
                    parent?.removeView(view)
                    target.addView(view, ViewGroup.LayoutParams(1, 1))
                }
                if (owner != null && target !== backgroundHost?.container) releaseBackgroundHost()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) attach() else main.post { attach() }
    }

    fun play() {
        desiredPlaying = true
        val epoch = generation.get()
        main.post {
            if (epoch == generation.get() && epoch != failedEpoch && desiredPlaying) armStartupDeadline(epoch)
        }
        command("play")
    }
    fun pause() {
        desiredPlaying = false
        main.post { if (!desiredPlaying) { timeout?.let(main::removeCallbacks); timeout = null } }
        spectrumFrame = SpectrumFrame(generation.get(), 0L, AudioSpectrum(), SpectrumCaptureState.Disabled)
        command("pause")
    }
    fun seekTo(positionMs: Long) {
        if (playbackSnapshot()?.canSeek == true) command("seek", positionMs.coerceAtLeast(0L) / 1000.0)
    }
    fun skipToNext() { if (playbackSnapshot()?.canSkipNext == true) command("next") }
    fun skipToPrevious() { if (playbackSnapshot()?.canSkipPrevious == true) command("previous") }
    fun playbackSnapshot(): PlaybackSnapshot? = latest?.takeIf { it.requestGeneration == generation.get() }
    fun setPlaybackListener(value: ((PlaybackSnapshot) -> Unit)?) {
        main.post { listener = value }
    }

    fun release() {
        val epoch = generation.incrementAndGet()
        desiredPlaying = false
        spectrumRequested = false
        spectrumFrame = SpectrumFrame(epoch, 0L, AudioSpectrum(), SpectrumCaptureState.Disabled)
        requested = null
        latest = null
        main.post {
            if (epoch != generation.get()) return@post
            ready = false
            timeout?.let(main::removeCallbacks)
            timeout = null
            destroyView()
        }
    }

    private fun command(action: String, value: Double = 0.0) {
        val epoch = generation.get()
        main.post {
            if (epoch != generation.get() || epoch == failedEpoch || !ready) return@post
            // A later Pause/Play owns intent even if an older command was queued
            // before the native main handler could dispatch it to JavaScript.
            if ((action == "play" && !desiredPlaying) || (action == "pause" && desiredPlaying)) return@post
            Log.i(TAG, "Station command generation=$epoch action=$action desiredPlaying=$desiredPlaying")
            webView?.evaluateJavascript("window.amatvStationCommand && window.amatvStationCommand(" +
                JSONObject.quote(action) + "," + value + ");", null)
        }
    }

    private fun activity(): Activity? {
        var candidate = context
        val visited = mutableSetOf<Context>()
        while (candidate !is Activity && candidate is ContextWrapper && visited.add(candidate)) {
            candidate = candidate.baseContext
        }
        return candidate as? Activity
    }

    private fun hostContainer(owner: Activity? = activity()): ViewGroup =
        (owner?.takeUnless { it.isFinishing || it.isDestroyed }?.window?.decorView as? ViewGroup)
            ?: (backgroundHost ?: StationBackgroundHost.create(context.applicationContext)
                .also { backgroundHost = it }).container

    private fun releaseBackgroundHost() {
        backgroundHost?.close()
        backgroundHost = null
    }

    private fun armStartupDeadline(epoch: Long) {
        timeout?.let(main::removeCallbacks)
        observedPlaying = false
        timeout = Runnable {
            if (epoch == generation.get() && (!ready || (desiredPlaying && !observedPlaying))) {
                Log.w(TAG, "Station startup timeout generation=$epoch ready=$ready desiredPlaying=$desiredPlaying")
                fail(epoch, PlaybackFailureKind.Network)
            }
        }.also { main.postDelayed(it, PREPARE_TIMEOUT_MS) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        val host = hostContainer()
        // The mutable wrapper is rebound to the app when its UI detaches, so
        // the retained media element cannot hold a destroyed Activity context.
        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0f
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(object {
                @JavascriptInterface fun spectrum(raw: String) { acceptSpectrum(raw) }
                @JavascriptInterface fun report(raw: String) {
                    if (raw.length > MAX_REPORT_CHARS) return
                    val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
                    val epoch = message.optLong("epoch", -1L)
                    main.post {
                        if (epoch != generation.get() || epoch == failedEpoch) return@post
                        when (message.optString("event")) {
                            "prepared" -> {
                                ready = true
                                sendSpectrumIntent()
                                publish(epoch, message)
                                Log.i(TAG, "Station prepared callback generation=$epoch desiredPlaying=$desiredPlaying")
                                if (desiredPlaying) command("play")
                            }
                            "diagnostic" -> {
                                val stage = message.optString("stage").takeIf { it.matches(Regex("[a-z_]{1,32}")) }.orEmpty()
                                Log.i(TAG, "Station JS generation=$epoch stage=$stage " + diagnosticFields(message))
                            }
                            "error" -> {
                                val name = message.optString("name").takeIf { it.matches(Regex("[A-Za-z_]{1,48}")) }.orEmpty()
                                val reason = message.optString("sdkReason").takeIf { it.matches(Regex("[A-Z_]{1,48}")) }.orEmpty()
                                Log.w(TAG, "Station JS rejected generation=$epoch code=${message.optInt("code", 0)} name=$name sdkReason=$reason")
                                fail(epoch, when (message.optString("reason")) {
                                    "authorization" -> PlaybackFailureKind.Authorization
                                    "network" -> PlaybackFailureKind.Network
                                    "unavailable" -> PlaybackFailureKind.TrackUnavailable
                                    else -> PlaybackFailureKind.Unexpected
                                })
                            }
                            else -> publish(epoch, message)
                        }
                    }
                }
            }, BRIDGE)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() !in setOf(ConsoleMessage.MessageLevel.ERROR, ConsoleMessage.MessageLevel.WARNING)) return true
                    val content = message.message().orEmpty().lowercase()
                    val category = when {
                        "content security policy" in content || "refused to" in content -> "csp"
                        "notallowed" in content || "autoplay" in content || "user gesture" in content -> "autoplay"
                        "not supported" in content || "notsupported" in content || "unsupported" in content -> "unsupported"
                        "name_not_resolved" in content || "getaddrinfo" in content -> "dns"
                        "webassembly" in content || "wasm" in content -> "wasm"
                        "widevine" in content || "keysystem" in content || "license" in content -> "eme"
                        "secure context" in content || "insecure" in content -> "secure_context"
                        "network" in content || "failed to fetch" in content || "err_connection" in content -> "network"
                        "promise" in content || "unhandled" in content -> "promise"
                        else -> "script"
                    }
                    if (loggedConsoleCategories.add(category)) Log.w(TAG, "Station console category=$category level=${message.messageLevel()}")
                    return true
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    // The selected station may require Widevine EME. Never grant
                    // microphone, camera, or permissions from an arbitrary frame.
                    val ours = request.origin.scheme == "https" && request.origin.host == "amatv.invalid"
                    if (ours && PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    } else request.deny()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Station playback never opens account/login/browser windows.
                    if (request?.isForMainFrame != true) return false
                    return request.url.toString() != ORIGIN && request.url.toString() != "about:blank"
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) fail(generation.get(), PlaybackFailureKind.Network)
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (view === webView) {
                        fail(generation.get(), PlaybackFailureKind.Unexpected)
                        destroyView()
                    }
                    return true
                }
            }
            // Attached and VISIBLE keeps WebView's media lifecycle valid. It owns
            // no pixels, TV focus, or accessibility nodes; music controls stay native.
            host.addView(this, ViewGroup.LayoutParams(1, 1))
        }
    }

    private fun publish(epoch: Long, data: JSONObject) {
        if (epoch != generation.get()) return
        val source = requested ?: return
        val live = data.optBoolean("live", false)
        // A live HLS duration is the sliding buffer window, not a finite
        // programme/song duration. Never expose it as a seekable end time.
        val duration = if (live) 0L else data.optLong("durationMs", 0L).coerceAtLeast(0L)
        val current = StationNowPlayingMetadata.fromReport(data).present(source, duration)
        val snapshot = PlaybackSnapshot(currentTrack = current,
            hasPlayerCurrentTrack = data.optBoolean("hasItem", false),
            positionMs = data.optLong("positionMs", 0L).coerceAtLeast(0L), durationMs = duration,
            isPlaying = data.optBoolean("playing", false), isBuffering = data.optBoolean("buffering", false),
            canSkipNext = data.optBoolean("next", false), canSkipPrevious = data.optBoolean("previous", false),
            capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            isEnded = data.optBoolean("ended", false), requestGeneration = epoch,
            isPrepared = data.optBoolean("prepared", ready), isLiveStream = live,
            canSeek = !live && duration > 0L && data.optBoolean("seek", false))
        if (progressWatchdog.observe(epoch, snapshot.isPrepared, desiredPlaying, snapshot.isPlaying,
                snapshot.isBuffering, snapshot.positionMs, snapshot.capturedAtElapsedRealtimeMs)) {
            fail(epoch, PlaybackFailureKind.Network)
            return
        }
        latest = snapshot
        if (snapshot.isPlaying && snapshot.isPrepared && data.optBoolean("desired", false)) {
            observedPlaying = true
            timeout?.let(main::removeCallbacks)
            timeout = null
        }
        val signature = "${snapshot.isPrepared}:${snapshot.isPlaying}:${snapshot.isBuffering}:${data.optInt("state", -1)}:" +
            "${data.optInt("queueCount", -1)}:${snapshot.hasPlayerCurrentTrack}:${data.opt("drmSupported")}:${data.optBoolean("desired", false)}"
        if (lastLoggedSignature != signature ||
            (snapshot.isPlaying && snapshot.positionMs - lastLoggedPositionMs >= 5_000L)) {
            Log.i(TAG, "Station generation=$epoch prepared=${snapshot.isPrepared} playing=${snapshot.isPlaying} " +
                "buffering=${snapshot.isBuffering} positionMs=${snapshot.positionMs} durationMs=${snapshot.durationMs} " +
                "live=${snapshot.isLiveStream} " + diagnosticFields(data))
            lastLoggedSignature = signature
            lastLoggedPositionMs = snapshot.positionMs
        }
        listener?.invoke(snapshot)
    }

    private fun diagnosticFields(data: JSONObject): String {
        val drm = if (data.opt("drmSupported") is Boolean) data.optBoolean("drmSupported").toString() else "unknown"
        fun country(key: String): String = data.optString(key).takeIf { it.matches(Regex("[a-z]{2}")) }.orEmpty()
        val basic = "state=${data.optInt("state", -1)} queueCount=${data.optInt("queueCount", -1)} " +
            "hasNowPlaying=${data.optBoolean("hasItem", false)} drmSupported=$drm desired=${data.optBoolean("desired", false)} " +
            "storefront=${country("storefront")} userStorefront=${country("userStorefront")}"
        if (BuildConfig.BUILD_TYPE != "benchmark" || !data.has("metadataFields")) return basic
        fun safe(key: String, limit: Int = 512): String = data.optString(key).take(limit)
            .takeIf { it.matches(Regex("[A-Za-z0-9_,.-]*")) }.orEmpty()
        return basic + " metadataFields=${safe("metadataFields")} idCountries=${safe("idCountries")} " +
            "country=${safe("country", 16)} idShape=${safe("idShape", 16)} matchingId=${data.optBoolean("matchingId")} " +
            "lookupStatus=${data.optInt("lookupStatus", -1)} lookupError=${safe("lookupError", 48)} " +
            "itemTitleMatches=${data.optBoolean("itemTitleMatches")} itemArtistMatches=${data.optBoolean("itemArtistMatches")} " +
            "itemArtworkPresent=${data.optBoolean("itemArtworkPresent")}"
    }

    private fun fail(epoch: Long, kind: PlaybackFailureKind) {
        if (epoch != generation.get()) return
        Log.w(TAG, "Station failure generation=$epoch kind=$kind")
        desiredPlaying = false
        failedEpoch = epoch
        ready = false
        timeout?.let(main::removeCallbacks)
        timeout = null
        // A terminal failure must end the backing audio even if an SDK Stop
        // promise rejected. Destroying this page also cancels stale Play work.
        destroyView()
        val source = requested ?: return
        val message = AppText.text(when (kind) {
            PlaybackFailureKind.Authorization -> R.string.services_playback_auth_required
            PlaybackFailureKind.Network -> R.string.services_apple_connection_failed
            PlaybackFailureKind.TrackUnavailable -> R.string.services_track_unavailable
            else -> R.string.services_playback_failed
        })
        val snapshot = (playbackSnapshot() ?: PlaybackSnapshot(source, positionMs = 0L,
            durationMs = 0L, isPlaying = false, isBuffering = false, canSkipNext = false,
            canSkipPrevious = false))
            .copy(isPlaying = false, isBuffering = false, errorMessage = message,
                isPrepared = false, hasPlayerCurrentTrack = false,
                canSeek = false, canSkipNext = false, canSkipPrevious = false,
                failureKind = kind, requestGeneration = epoch, capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime())
        latest = snapshot
        listener?.invoke(snapshot)
    }

    private fun destroyView() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeJavascriptInterface(BRIDGE)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        releaseBackgroundHost()
    }

    private fun stationPage(epoch: Long, id: String, developer: String, user: String, positionMs: Long, storefront: String): String {
        val country = storefront.trim().lowercase(java.util.Locale.ROOT).also { require(it.matches(Regex("[a-z]{2}"))) }
        val configuration = JSONObject().put("epoch", epoch).put("station", id)
            .put("developer", developer).put("user", user).put("position", positionMs / 1000.0)
            .put("build", BuildConfig.VERSION_NAME)
            .put("storefront", country)
            .put("storefrontNumericId", storefrontNumericIds[country].orEmpty())
            .put("spectrumActive", spectrumRequested)
            .put("metadataDiagnostics", BuildConfig.BUILD_TYPE == "benchmark").toString().replace("<", "\\u003c")
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src https: blob: data:; script-src https://js-cdn.music.apple.com 'unsafe-inline'; connect-src https:; media-src https: blob:; worker-src 'self' blob: https://js-cdn.music.apple.com; frame-src https:; object-src 'none'; base-uri 'none'">
            </head><body><script>
            (function(){
              'use strict';
              var config=$configuration, music=null, prepared=false, desired=false, terminal=false;
              var intentReceived=false, commandVersion=0, stationWasLive=false, stoppedLive=false;
              var resumePosition=config.position;
              var stopPromise=null, stoppedForVersion=-1, lastObservedPlaying=false;
              var timedMetadata=null, timedCatalog=null, timedCatalogKey=null, metadataVersion=0, metadataFetch=null, metadataActive=false;
              var freshStreamItem=null;
              var metadataCache=Object.create(null), metadataCacheKeys=[];
              function send(data){ data.epoch=config.epoch; $BRIDGE.report(JSON.stringify(data)); }
              function finite(value){ return typeof value==='number' && isFinite(value) && value>=0 ? value : 0; }
              function diagnostic(stage){
                var drm=null;try{drm=music && typeof music.browserSupportsVideoDrm==='boolean' ? music.browserSupportsVideoDrm : null;}catch(ignored){}
                send({event:'diagnostic',stage:stage,state:music ? music.playbackState : -1,
                  queueCount:music && music.queue ? music.queue.length : 0,hasItem:!!(music && music.nowPlayingItem),
                  drmSupported:drm,desired:desired,storefront:config.storefront,
                  userStorefront:music ? String(music.storefrontCountryCode || '').toLowerCase() : ''});
              }
              function metadataDiagnostic(stage,metadata,country,ids,matching,status,error){
                if(!config.metadataDiagnostics) return;
                var item=music && music.nowPlayingItem, itemAttributes=item && item.attributes || {};
                var fields=Object.keys(metadata || {}).filter(function(key){return /^[A-Za-z0-9_]{1,32}$/.test(key);}).slice(0,16);
                var countries=ids && typeof ids==='object' ? Object.keys(ids).filter(function(key){return /^[A-Za-z0-9_.-]{1,16}$/.test(key);}).slice(0,32) : [];
                send({event:'diagnostic',stage:stage,metadataFields:fields.join(','),idCountries:countries.join(','),
                  country:/^[a-z]{2}$/.test(country || '') ? country : 'invalid',
                  idShape:Array.isArray(ids) ? 'array' : typeof ids,matchingId:!!matching,
                  lookupStatus:typeof status==='number' ? status : -1,
                  lookupError:error && /^[A-Za-z0-9_]{1,48}$/.test(error.name || '') ? error.name : '',
                  itemTitleMatches:!!(metadata && metadata.title && itemAttributes.name && String(metadata.title).trim()===String(itemAttributes.name).trim()),
                  itemArtistMatches:!!(metadata && metadata.performer && itemAttributes.artistName && String(metadata.performer).trim()===String(itemAttributes.artistName).trim()),
                  itemArtworkPresent:!!(itemAttributes.artwork || item && item.artwork)});
              }
              // Capture a copy of this one media element. Never reroute the
              // original through createMediaElementSource or capture a device mix.
              var spectrumEnabled=!!config.spectrumActive, spectrumElement=null, spectrumStream=null;
              var spectrumContext=null, spectrumSource=null, spectrumAnalyser=null, spectrumTimer=null;
              var spectrumRetry=null, spectrumRetries=0, spectrumBlocked=false, spectrumBins=null, spectrumWave=null;
              var spectrumLevels=[0,0,0], spectrumGate=false, spectrumLast=0;
              var spectrumReason='waiting', spectrumErrorName='', spectrumLastContextState='none';
              var spectrumGraphMode='capture_stream', spectrumCurrentGraph=null;
              var elementSpectrumGraphs=new WeakMap(), elementSpectrumGraphRefs=typeof WeakRef==='function' ? new Set() : null;
              function sameOriginBlob(){
                try{var source=new URL(spectrumElement && spectrumElement.currentSrc || '');return source.protocol==='blob:' && source.origin===location.origin;}
                catch(ignored){return false;}
              }
              function spectrumSend(status,captured,available,rate,reason){
                if(typeof $BRIDGE.spectrum!=='function') return;
                function level(value){return Math.round(Math.max(0,Math.min(1,value || 0))*1000)/1000;}
                var bass=captured ? level(spectrumLevels[0]) : 0, mid=captured ? level(spectrumLevels[1]) : 0, treble=captured ? level(spectrumLevels[2]) : 0;
                var data={epoch:config.epoch,status:status,captured:!!captured,
                  available:!!available && Math.max(bass,mid,treble)>0,rate:rate || 0,bass:bass,mid:mid,treble:treble};
                if(config.metadataDiagnostics){
                  data.reason=reason || (status==='capturing' ? data.available ? 'signal' : 'zero' : spectrumReason);
                  data.captureApi=!!(spectrumElement && (typeof spectrumElement.captureStream==='function' || typeof spectrumElement.mozCaptureStream==='function'));
                  data.contextApi=typeof (window.AudioContext || window.webkitAudioContext)==='function';
                  data.mediaKeys=!!(spectrumElement && spectrumElement.mediaKeys);
                  data.sameOriginBlob=sameOriginBlob();data.graphMode=spectrumGraphMode;
                  data.contextState=spectrumContext ? spectrumContext.state : spectrumLastContextState;
                  data.errorName=spectrumErrorName;
                }
                $BRIDGE.spectrum(JSON.stringify(data));
              }
              function stopSpectrum(status){
                if(spectrumTimer!==null) window.clearInterval(spectrumTimer);
                if(spectrumRetry!==null) window.clearTimeout(spectrumRetry);
                spectrumTimer=null;spectrumRetry=null;
                if(spectrumGraphMode==='media_element' && spectrumContext){
                  // A MediaElementSource permanently owns the element's routing.
                  // Stop analysis only; preserve its audible passthrough until page destruction.
                  spectrumLevels=[0,0,0];spectrumGate=false;spectrumLast=0;
                  spectrumSend(status || 'disabled',false,false,0,status==='disabled' ? 'disabled' : undefined);
                  return;
                }
                if(spectrumSource)try{spectrumSource.disconnect();}catch(ignored){}
                if(spectrumAnalyser)try{spectrumAnalyser.disconnect();}catch(ignored){}
                if(spectrumStream) spectrumStream.getTracks().forEach(function(track){try{track.stop();}catch(ignored){}});
                var context=spectrumContext;
                if(context)spectrumLastContextState=context.state;
                spectrumStream=null;spectrumSource=null;spectrumAnalyser=null;spectrumContext=null;
                spectrumBins=null;spectrumWave=null;spectrumLevels=[0,0,0];spectrumGate=false;spectrumLast=0;
                if(context)try{Promise.resolve(context.close()).catch(function(){});}catch(ignored){}
                spectrumSend(status || 'disabled',false,false,0,status==='disabled' ? 'disabled' : undefined);
              }
              function unavailableSpectrum(reason,error){
                spectrumReason=reason || 'capture_throw';
                spectrumErrorName=error && /^[A-Za-z0-9_]{1,48}$/.test(error.name || '') ? error.name : '';
                spectrumBlocked=true;stopSpectrum('unavailable');
              }
              function sampleSpectrum(){
                var context=spectrumContext, analyser=spectrumAnalyser;
                if(!spectrumEnabled || terminal || !music || music.playbackState!==MusicKit.PlaybackStates.playing){stopSpectrum('disabled');return;}
                if(!context || !analyser || context.state!=='running'){spectrumSend('waiting',false,false,0,'context_not_running');return;}
                var tracks=spectrumStream && spectrumStream.getAudioTracks() || [];
                if(spectrumGraphMode!=='media_element' && !tracks.some(function(track){return track.readyState==='live' && !track.muted && track.enabled!==false;})){
                  spectrumSend('unavailable',false,false,context.sampleRate,'tracks_muted');return;
                }
                try{
                  analyser.getFloatFrequencyData(spectrumBins);analyser.getFloatTimeDomainData(spectrumWave);
                  var sum=0;for(var i=0;i<spectrumWave.length;i++)sum+=spectrumWave[i]*spectrumWave[i];
                  var rms=Math.sqrt(sum/spectrumWave.length);
                  spectrumGate=rms>Math.pow(10,(spectrumGate ? -64 : -58)/20);
                  var powers=[0,0,0], hzPerBin=context.sampleRate/analyser.fftSize;
                  for(var bin=1;bin<spectrumBins.length;bin++){
                    var hz=bin*hzPerBin, band=hz>=40 && hz<250 ? 0 : hz>=250 && hz<2000 ? 1 : hz>=2000 && hz<=10000 ? 2 : -1;
                    if(band<0)continue;
                    var db=spectrumBins[bin];if(isFinite(db))powers[band]+=Math.pow(10,db/10);
                  }
                  var now=typeof performance!=='undefined' ? performance.now() : Date.now();
                  var dt=Math.max(1,Math.min(250,spectrumLast ? now-spectrumLast : 50));spectrumLast=now;
                  var available=false;
                  for(var band=0;band<3;band++){
                    // AnalyserNode returns a 1/N FFT after a Blackman window.
                    // Integrate the band's energy: averaging over every bin
                    // dilutes mid/treble simply because their bands are wider.
                    // Restore negative-frequency energy and window mean-square
                    // (0.42^2 + 0.5^2/2 + 0.08^2/2), yielding actual PCM RMS.
                    var amplitude=Math.sqrt(2*powers[band]/0.3046);
                    var target=spectrumGate ? Math.max(0,Math.min(1,Math.log(1+64*amplitude)/Math.log(65))) : 0;
                    if(target>0.001)available=true;
                    var decay=band===0 ? 115 : band===1 ? 100 : 75;
                    spectrumLevels[band]+=(target-spectrumLevels[band])*(1-Math.exp(-dt/(target>spectrumLevels[band] ? 28 : decay)));
                  }
                  spectrumSend('capturing',true,available,context.sampleRate);
                }catch(error){unavailableSpectrum('sample_throw',error);}
              }
              function closeElementSpectrumGraph(graph){
                if(!graph || graph.closed)return;
                graph.closed=true;++graph.intent;
                try{graph.source.disconnect();}catch(ignored){}
                try{graph.analyser.disconnect();}catch(ignored){}
                try{Promise.resolve(graph.context.close()).catch(function(){});}catch(ignored){}
              }
              function destroySpectrum(){
                stopSpectrum('disabled');
                closeElementSpectrumGraph(spectrumCurrentGraph);
                if(elementSpectrumGraphRefs){
                  elementSpectrumGraphRefs.forEach(function(reference){closeElementSpectrumGraph(reference.deref());});
                  elementSpectrumGraphRefs.clear();
                }else if(typeof document.querySelectorAll==='function'){
                  Array.prototype.forEach.call(document.querySelectorAll('audio,video'),function(element){
                    closeElementSpectrumGraph(elementSpectrumGraphs.get(element));
                  });
                }
                spectrumCurrentGraph=null;spectrumContext=null;spectrumSource=null;spectrumAnalyser=null;
                spectrumBins=null;spectrumWave=null;spectrumGraphMode='capture_stream';
              }
              function resumeElementSpectrumGraph(graph){
                if(!graph || graph.closed || terminal || graph.element.paused || graph.element.readyState<2)return;
                var intent=++graph.intent;
                try{Promise.resolve(graph.context.resume()).then(function(){
                  if(graph.closed || terminal)return;
                  if(intent!==graph.intent || graph.element.paused || graph.element.readyState<2){
                    if(graph.element.paused || graph.element.readyState<2)suspendElementSpectrumGraph(graph);
                    return;
                  }
                  // Resuming passthrough is mandatory even when visualization is off.
                  if(spectrumCurrentGraph!==graph || !spectrumEnabled)return;
                  if(!graph.analyser || !graph.bins || !graph.wave){unavailableSpectrum('element_analyser_unavailable');return;}
                  if(graph.context.state!=='running'){unavailableSpectrum('context_not_running');return;}
                  if(spectrumTimer===null){spectrumTimer=window.setInterval(sampleSpectrum,50);sampleSpectrum();}
                },function(error){
                  if(spectrumCurrentGraph===graph && !graph.closed)unavailableSpectrum('element_resume_throw',error);
                });}catch(error){if(spectrumCurrentGraph===graph)unavailableSpectrum('element_resume_throw',error);}
              }
              function suspendElementSpectrumGraph(graph){
                if(!graph || graph.closed)return;
                ++graph.intent;
                if(spectrumCurrentGraph===graph)stopSpectrum('disabled');
                try{Promise.resolve(graph.context.suspend()).then(function(){
                  // A rapid Playing after Pause can overtake the asynchronous suspend.
                  if(!graph.closed && !terminal && !graph.element.paused && graph.element.readyState>=2)resumeElementSpectrumGraph(graph);
                }).catch(function(){});}catch(ignored){}
              }
              function useElementSpectrumGraph(graph){
                spectrumCurrentGraph=graph;spectrumGraphMode='media_element';
                spectrumContext=graph.context;spectrumSource=graph.source;spectrumAnalyser=graph.analyser;
                spectrumBins=graph.bins;spectrumWave=graph.wave;
              }
              function startElementSpectrum(){
                // Public source analysis for same-origin MSE only. Do not detach
                // MediaKeys, change src/CORS, or read/decode protected media URLs.
                if(!sameOriginBlob())return false;
                var graph=elementSpectrumGraphs.get(spectrumElement);
                if(graph){
                  if(graph.closed){unavailableSpectrum('element_graph_closed');return true;}
                  useElementSpectrumGraph(graph);resumeElementSpectrumGraph(graph);return true;
                }
                var Context=window.AudioContext || window.webkitAudioContext;
                if(typeof Context!=='function')return false;
                var phase='element_context';
                try{
                  var context=new Context();spectrumContext=context;spectrumGraphMode='media_element';
                  phase='element_create';spectrumSource=context.createMediaElementSource(spectrumElement);
                  graph={element:spectrumElement,context:context,source:spectrumSource,analyser:null,
                    bins:null,wave:null,closed:false,intent:0,audibleRouted:false};
                  // Memoize immediately after the irreversible public source bind,
                  // including failed analyser setup: never bind this element twice.
                  elementSpectrumGraphs.set(spectrumElement,graph);spectrumCurrentGraph=graph;
                  if(elementSpectrumGraphRefs){
                    elementSpectrumGraphRefs.forEach(function(reference){if(!reference.deref())elementSpectrumGraphRefs.delete(reference);});
                    elementSpectrumGraphRefs.add(new WeakRef(graph));
                  }
                  spectrumElement.addEventListener('playing',function(){resumeElementSpectrumGraph(graph);});
                  ['pause','ended','emptied'].forEach(function(event){graph.element.addEventListener(event,function(){suspendElementSpectrumGraph(graph);});});
                  phase='element_analyser';spectrumAnalyser=context.createAnalyser();
                  spectrumAnalyser.fftSize=1024;spectrumAnalyser.smoothingTimeConstant=0;
                  graph.analyser=spectrumAnalyser;graph.bins=new Float32Array(spectrumAnalyser.frequencyBinCount);graph.wave=new Float32Array(1024);
                  spectrumSource.connect(spectrumAnalyser);spectrumAnalyser.connect(context.destination);graph.audibleRouted=true;
                  useElementSpectrumGraph(graph);
                  spectrumSend('waiting',false,false,context.sampleRate,'element_resume');
                  resumeElementSpectrumGraph(graph);
                }catch(error){
                  if(graph){
                    if(!graph.audibleRouted)try{graph.source.connect(graph.context.destination);graph.audibleRouted=true;}catch(ignored){}
                    resumeElementSpectrumGraph(graph);
                  }else if(spectrumContext){
                    try{Promise.resolve(spectrumContext.close()).catch(function(){});}catch(ignored){}
                    spectrumContext=null;
                  }
                  unavailableSpectrum(phase+'_throw',error);
                }
                return true;
              }
              function startSpectrum(){
                if(!spectrumEnabled || spectrumBlocked || spectrumRetry!==null || terminal || !spectrumElement || !music ||
                    music.playbackState!==MusicKit.PlaybackStates.playing || spectrumElement.paused || spectrumElement.readyState<2) return;
                if(spectrumContext){if(spectrumCurrentGraph && spectrumTimer===null)resumeElementSpectrumGraph(spectrumCurrentGraph);return;}
                var capture=spectrumElement.captureStream || spectrumElement.mozCaptureStream;
                var Context=window.AudioContext || window.webkitAudioContext;
                if((typeof capture!=='function' || spectrumElement.mediaKeys) && startElementSpectrum())return;
                if(typeof capture!=='function'){unavailableSpectrum('missing_capture');return;}
                if(typeof Context!=='function'){unavailableSpectrum('missing_context');return;}
                if(spectrumElement.mediaKeys){unavailableSpectrum('media_keys');return;}
                var phase='capture';
                try{
                  spectrumStream=capture.call(spectrumElement);
                  if(!spectrumStream.getAudioTracks().length){
                    spectrumReason='missing_tracks';stopSpectrum('waiting');
                    if(spectrumRetries++<4) spectrumRetry=window.setTimeout(function(){spectrumRetry=null;startSpectrum();},250);
                    else unavailableSpectrum('missing_tracks');
                    return;
                  }
                  phase='context';var context=new Context();spectrumContext=context;
                  phase='source';spectrumSource=context.createMediaStreamSource(spectrumStream);
                  phase='analyser';spectrumAnalyser=context.createAnalyser();spectrumAnalyser.fftSize=1024;spectrumAnalyser.smoothingTimeConstant=0;
                  spectrumBins=new Float32Array(spectrumAnalyser.frequencyBinCount);spectrumWave=new Float32Array(1024);
                  spectrumSource.connect(spectrumAnalyser); // Analyser output intentionally unconnected.
                  spectrumSend('waiting',false,false,context.sampleRate,'context_resume');
                  phase='resume';Promise.resolve(context.resume()).then(function(){
                    if(context!==spectrumContext || !spectrumEnabled || terminal)return;
                    if(context.state!=='running'){unavailableSpectrum('context_not_running');return;}
                    spectrumTimer=window.setInterval(sampleSpectrum,50);sampleSpectrum();
                  },function(error){if(context===spectrumContext)unavailableSpectrum('resume_throw',error);});
                }catch(error){unavailableSpectrum(phase+'_throw',error);}
              }
              function rememberSpectrumElement(element){
                if(!element || typeof element.addEventListener!=='function')return;
                if(spectrumElement===element){resumeElementSpectrumGraph(elementSpectrumGraphs.get(element));startSpectrum();return;}
                var oldGraph=spectrumCurrentGraph;
                stopSpectrum(spectrumEnabled ? 'waiting' : 'disabled');
                if(oldGraph && (oldGraph.element.paused || oldGraph.element.readyState<2))suspendElementSpectrumGraph(oldGraph);
                spectrumCurrentGraph=null;spectrumContext=null;spectrumSource=null;spectrumAnalyser=null;
                spectrumBins=null;spectrumWave=null;spectrumGraphMode='capture_stream';
                spectrumElement=element;spectrumRetries=0;spectrumBlocked=false;
                element.addEventListener('playing',function(){if(spectrumElement===element){spectrumBlocked=false;startSpectrum();}});element.addEventListener('loadeddata',startSpectrum);
                ['pause','ended','emptied'].forEach(function(event){element.addEventListener(event,function(){
                  if(spectrumElement===element)stopSpectrum('disabled');
                });});
                resumeElementSpectrumGraph(elementSpectrumGraphs.get(element));startSpectrum();
              }
              window.amatvSetSpectrumActive=function(enabled){
                var nextEnabled=enabled===true;
                if(nextEnabled===spectrumEnabled && (spectrumContext || spectrumBlocked))return;
                spectrumEnabled=nextEnabled;
                if(!spectrumEnabled){stopSpectrum('disabled');return;}
                spectrumRetries=0;spectrumBlocked=false;
                if(!spectrumElement && typeof document.getElementById==='function')rememberSpectrumElement(document.getElementById('apple-music-player'));
                startSpectrum();
              };
              if(typeof window.addEventListener==='function')window.addEventListener('pagehide',destroySpectrum);
              function artworkURL(artwork){
                if(!artwork) return '';
                try{return MusicKit.formatArtworkURL(artwork,600,600) || '';}catch(ignored){return '';}
              }
              function clearTimedMetadata(resetCache){
                ++metadataVersion;timedMetadata=null;timedCatalog=null;timedCatalogKey=null;
                freshStreamItem=null;
                if(metadataFetch){metadataFetch.abort();metadataFetch=null;}
                if(resetCache){metadataCache=Object.create(null);metadataCacheKeys=[];}
              }
              function currentSongItem(item){
                var a=item && item.attributes || {};
                return item && !item.isLiveRadioStation && (item.type==='song' || item.type==='songs') &&
                  /^[1-9][0-9]{0,19}$/.test(String(item.id || '')) && typeof a.name==='string' && a.name.trim() ? item : null;
              }
              function metadataMatchesItem(metadata,item){
                if(!metadata || !item) return false;
                var country=String(config.storefront || '').toLowerCase(), ids=metadata.storefrontAdamIds || {};
                var id=ids[country.toUpperCase()] || ids[country] || ids[String(config.storefrontNumericId || '')];
                if(id && String(id)===String(item.id)) return true;
                var a=item.attributes || {}, title=String(metadata.title || '').trim(), artist=String(metadata.performer || '').trim();
                return !!title && !!artist && title===String(a.name || '').trim() && artist===String(a.artistName || '').trim();
              }
              function receiveNowPlayingItem(event){
                if(!music || terminal || !metadataActive) return;
                var current=music.nowPlayingItem;
                var item=event && Object.prototype.hasOwnProperty.call(event,'item') ? event.item : current;
                // A late callback cannot replace the SDK's newer current item.
                if(item && (!current || String(item.id)!==String(current.id) || item.type!==current.type)) return;
                var next=currentSongItem(item);
                if(!metadataMatchesItem(timedMetadata,next)) clearTimedMetadata();
                // Only an actual current-item notification owns this fallback.
                // Polling a retained SDK item after Stop/Play is never fresh.
                freshStreamItem=next;
                report();
              }
              function receiveTimedMetadata(event){
                if(!music || terminal || !metadataActive) return;
                var metadata=event && (event.metadata || event.detail && event.detail.metadata || event.detail || event);
                if(!metadata || typeof metadata!=='object') {clearTimedMetadata();report();return;}
                // The native caller has already resolved the authorized account's
                // storefront. JS can still report its default US store at startup.
                var country=String(config.storefront || '').toLowerCase();
                var numericStore=/^[1-9][0-9]{2,9}$/.test(String(config.storefrontNumericId || '')) ? String(config.storefrontNumericId) : '';
                var ids=metadata.storefrontAdamIds || {}, id=ids[country.toUpperCase()] || ids[country] || (numericStore && ids[numericStore]);
                var key=/^[a-z]{2}$/.test(country) && /^[1-9][0-9]{0,19}$/.test(String(id || '')) ? country+':'+id : null;
                metadataDiagnostic(key ? 'metadata_exact_id' : 'metadata_missing_id',metadata,country,ids,!!key);
                // Repeated ID3 updates for one song must not temporarily clear
                // its cover or restart an in-flight exact-ID lookup. A genuine
                // new song still clears the previous song's art immediately.
                var previousItem=freshStreamItem;
                if(!key || key!==timedCatalogKey) clearTimedMetadata();
                timedMetadata=metadata;timedCatalogKey=key;
                freshStreamItem=metadataMatchesItem(metadata,previousItem) ? previousItem : null;
                if(key && metadataCache[key]) timedCatalog=metadataCache[key];
                report();
                if(!key || timedCatalog || metadataFetch) return;
                // These are the actual ID3 title/performer/album fields, not a
                // guessed search match. An ad/jingle may legitimately have none.
                var version=metadataVersion;
                // One cancellable metadata request; no media downloads, search,
                // arbitrary URLs, or song-queue substitution for the station.
                metadataDiagnostic('metadata_lookup_start',metadata,country,ids,true);
                var controller=new AbortController();metadataFetch=controller;
                var timeout=window.setTimeout(function(){controller.abort();},6000);
                fetch('https://api.music.apple.com/v1/catalog/'+country+'/songs/'+id,
                  {headers:{Authorization:'Bearer '+config.developer},signal:controller.signal,cache:'no-store'})
                .then(function(response){
                  metadataDiagnostic('metadata_lookup_http',metadata,country,ids,true,response.status);
                  if(!response.ok) throw new Error('MetadataUnavailable');return response.json();
                })
                .then(function(response){
                  if(terminal || version!==metadataVersion) return;
                  var song=response && response.data && response.data[0];
                  if(!song || song.type!=='songs' || String(song.id)!==String(id) || !song.attributes) {
                    metadataDiagnostic('metadata_lookup_mismatch',metadata,country,ids,true);return;
                  }
                  var attributes=song.attributes;
                  metadataCache[key]=attributes;metadataCacheKeys.push(key);
                  while(metadataCacheKeys.length>8) delete metadataCache[metadataCacheKeys.shift()];
                  timedCatalog=attributes;report();
                  metadataDiagnostic(artworkURL(attributes.artwork) ? 'metadata_lookup_artwork' : 'metadata_lookup_no_art',metadata,country,ids,true);
                }).catch(function(error){
                  if(version===metadataVersion) metadataDiagnostic('metadata_lookup_failed',metadata,country,ids,true,-1,error);
                  /* Optional artwork must never stop audio. */
                })
                .then(function(){window.clearTimeout(timeout);if(metadataFetch===controller) metadataFetch=null;});
              }
              function report(event){
                if(!music || terminal) return;
                var item=music.nowPlayingItem, a=item && item.attributes || {}, ps=MusicKit.PlaybackStates;
                var state=music.playbackState, live=!!(item && item.isLiveRadioStation || a.isLive || stationWasLive);
                if(spectrumEnabled && state===ps.playing) {
                  if(!spectrumElement && typeof document.getElementById==='function')rememberSpectrumElement(document.getElementById('apple-music-player'));
                  startSpectrum();
                } else if(spectrumTimer!==null || spectrumRetry!==null || spectrumGraphMode==='capture_stream' && spectrumContext) stopSpectrum('disabled');
                if(live) stationWasLive=true;
                // setQueue can report the previous Stopped state before its
                // new live media element starts. Do not erase reconnect readiness.
                if(live && state===ps.stopped && !desired) prepared=false;
                if(item && state===ps.playing) prepared=true;
                var startedPlaying=state===ps.playing && !lastObservedPlaying;
                lastObservedPlaying=state===ps.playing;
                // ContinuousPlaybackController.pause() intentionally does nothing
                // for live stations. An actual incoming Play after user Pause
                // must be stopped; keep reporting the real state until Stop wins.
                if(intentReceived && !desired && state===ps.playing && live && !stopPromise &&
                    (stoppedForVersion!==commandVersion || startedPlaying)) stopLive();
                if(item && MusicKit.PlaybackType && item.playbackType===MusicKit.PlaybackType.preview){fail({code:403});return;}
                var duration=finite(music.currentPlaybackDuration), queue=music.queue, drm=null;
                // setQueue resolves before nowPlayingItem/duration necessarily
                // exist. Restore a finite episode once its actual item is ready,
                // including a paused restoration that only loads on later Play.
                // A live buffer is never a resumable programme cursor.
                if(resumePosition>0 && item && (live || duration>0)){
                  var savedPosition=resumePosition;resumePosition=0;
                  if(!live && savedPosition<duration){
                    try{Promise.resolve(music.seekToTime(savedPosition)).then(function(){report();},fail);}
                    catch(error){fail(error);return;}
                  }
                }
                // Live streams may publish either ID3 or a new song MediaItem.
                // Ignore an old polled item; use only a current-item notification
                // received during this connection, cleared again on Stop/Play.
                var presentation=live ? timedMetadata ? {
                  name:timedMetadata.title || timedCatalog && timedCatalog.name || '',
                  artistName:timedMetadata.performer || timedCatalog && timedCatalog.artistName || '',
                  albumName:timedMetadata.album || timedCatalog && timedCatalog.albumName || '',
                  artwork:timedCatalog && timedCatalog.artwork || freshStreamItem &&
                    (freshStreamItem.attributes.artwork || freshStreamItem.artwork)
                } : freshStreamItem ? freshStreamItem.attributes : {} : a;
                try{drm=music.browserSupportsVideoDrm;}catch(ignored){}
                send({event:event || 'state', prepared:prepared, hasItem:!!item,state:state,
                  queueCount:queue ? queue.length : 0,drmSupported:typeof drm==='boolean' ? drm : null,desired:desired,
                  title:presentation.name || '',artist:presentation.artistName || '',album:presentation.albumName || '',
                  artworkUrl:artworkURL(presentation.artwork || (!live && item && item.artwork)),
                  positionMs:Math.round(finite(music.currentPlaybackTime)*1000),durationMs:live ? 0 : Math.round(duration*1000),
                  liveWindowDurationMs:live ? Math.round(duration*1000) : 0,
                  playing:state===ps.playing,buffering:state===ps.loading || state===ps.waiting || state===ps.stalled || state===ps.seeking,
                  live:live,seek:!live && duration>0, next:!!queue && queue.nextPlayableItemIndex>=0,
                  previous:!!queue && queue.previousPlayableItemIndex>=0,ended:state===ps.completed});
              }
              function fail(event){
                if(terminal) return;
                var error=event && (event.error || event.detail && (event.detail.error || event.detail)) || event;
                diagnostic('failure');terminal=true; desired=false;clearTimedMetadata();destroySpectrum();
                if(music) Promise.resolve(music.stop()).catch(function(){});
                var code=Number(error && (error.status || error.statusCode || error.code ||
                  error.data && (error.data.status || error.data.statusCode)));
                var name=error && error.name || '';
                var network=code===408 || code===429 || code>=500 && code<=599 || name==='NetworkError' ||
                  name==='NETWORK_ERROR' || error && (error.reason==='NETWORK_ERROR' || error.type==='NETWORK_ERROR') ||
                  name==='MediaError' && code===2 || typeof navigator!=='undefined' && navigator.onLine===false;
                send({event:'error',reason:code===401 || code===403 ? 'authorization' : code===404 ? 'unavailable' : network ? 'network' : 'playback',
                  code:typeof code==='number' && isFinite(code) ? code : 0,name:error && error.name || '',
                  sdkReason:error && error.reason || ''});
              }
              function stopLive(){
                stoppedLive=true;
                if(stopPromise) return stopPromise;
                metadataActive=false;clearTimedMetadata(true);
                stoppedForVersion=commandVersion;
                diagnostic('live_stop_requested');
                // Set the promise before calling the library: synchronous state
                // events cannot recursively issue another Stop.
                stopPromise=Promise.resolve().then(function(){return music.stop();});
                var pending=stopPromise;
                report();
                pending.then(function(){
                  if(stopPromise===pending) stopPromise=null;
                  diagnostic('live_stop_resolved');
                  if(music.playbackState===MusicKit.PlaybackStates.playing){
                    fail({name:'LiveStopNotConfirmed',code:0});return;
                  }
                  prepared=false;
                  report();
                },function(error){
                  if(stopPromise===pending) stopPromise=null;
                  fail(error);
                });
                return pending;
              }
              function pauseCurrent(){
                var item=music.nowPlayingItem;
                var live=stationWasLive || !!(item && item.isLiveRadioStation || item && item.attributes && item.attributes.isLive);
                if(live) return stopLive();
                return Promise.resolve(music.pause());
              }
              function playCurrent(version){
                return Promise.resolve(stopPromise).then(function(){
                  if(terminal || !desired || version!==commandVersion) return;
                  if(!stoppedLive) return music.play();
                  // Stop clears the live media element/current item. Reconnect
                  // the same station at its live edge, never seek an old stream.
                  diagnostic('live_reconnect_requested');clearTimedMetadata(true);metadataActive=true;
                  report();
                  return music.setQueue({station:config.station,startPlaying:false}).then(function(){
                    if(version!==commandVersion)return;
                    if(terminal || !desired){return stopLive();}
                    if(!music.queue || music.queue.length===0) throw {name:'EmptyStationQueue',code:0};
                    stoppedLive=false;
                    prepared=true;
                    diagnostic('live_reconnect_ready');
                    return music.play();
                  });
                });
              }
              window.amatvStationCommand=function(action,value){
                if(!music || terminal) return;
                try{
                  var work, version;
                  if(action==='pause'){
                    intentReceived=true;desired=false;version=++commandVersion;work=pauseCurrent();
                  } else if(action==='play'){
                    intentReceived=true;desired=true;version=++commandVersion;work=playCurrent(version);
                  } else if(action==='seek'){
                    resumePosition=0;
                    if(!stationWasLive && !(music.nowPlayingItem && music.nowPlayingItem.isLiveRadioStation)) work=music.seekToTime(value);
                  } else if(action==='next'){resumePosition=0;work=music.skipToNextItem();}
                  else if(action==='previous'){resumePosition=0;work=music.skipToPreviousItem();}
                  diagnostic('command_'+action);
                  Promise.resolve(work).then(function(){
                    if(action==='play' && version!==commandVersion)return;
                    diagnostic(action+'_resolved');
                    if(terminal) music.stop();
                    else if(intentReceived && !desired && !stationWasLive) music.pause();
                    report();
                  },function(error){
                    if(action==='play' && version!==commandVersion)return;
                    fail(error);
                  });
                }catch(error){fail(error);}
              };
              document.addEventListener('musickitloaded',function(){
                diagnostic('script_loaded');
                try{
                  Promise.resolve(MusicKit.configure({developerToken:config.developer, storefrontId:config.storefront,
                    app:{name:'AM for TV',build:config.build}}))
                  .then(function(instance){
                    music=instance || MusicKit.getInstance();
                    music.musicUserToken=config.user;
                    music.addEventListener('mediaElementCreated',rememberSpectrumElement);
                    diagnostic('configured');
                    ['playbackStateDidChange','queueItemsDidChange','playbackDurationDidChange','metadataDidChange']
                      .forEach(function(event){music.addEventListener(event,function(){report();});});
                    music.addEventListener('nowPlayingItemDidChange',receiveNowPlayingItem);
                    music.addEventListener('timedMetadataDidChange',receiveTimedMetadata);
                    music.addEventListener('mediaPlaybackError',fail);
                    // The global DRM capability is not required by unencrypted
                    // live radio. A real preview item is rejected by report().
                    music.addEventListener('drmUnsupported',function(){diagnostic('drm_unsupported');});
                    // Assigning a token does not resolve the SDK's account country.
                    // me() lets MusicKit refresh that state before setQueue's
                    // storefront check; storefrontId alone only configures the API.
                    return music.me();
                  }).then(function(){
                    diagnostic('account_ready');
                    if(String(music.storefrontCountryCode || '').toLowerCase()!==config.storefront)
                      throw {name:'StationStorefrontMismatch',reason:'CONTENT_EQUIVALENT'};
                    metadataActive=true;
                    return music.setQueue({station:config.station,startPlaying:false});
                  }).then(function(){
                    diagnostic('queue_resolved');
                    if(!music.queue || music.queue.length===0) throw {name:'EmptyStationQueue',code:0};
                    prepared=true;
                  }).then(function(){report('prepared');window.setInterval(function(){report();},250);},fail);
                }catch(error){fail(error);}
              });
              window.amatvStationScriptFailed=function(){terminal=true;destroySpectrum();send({event:'error',reason:'network'});};
            })();
            </script><script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js" onerror="amatvStationScriptFailed()"></script></body></html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "AMATV-Station"
        private const val ORIGIN = "https://amatv.invalid/station/"
        private const val BRIDGE = "AmAtvStationPlayback"
        private const val MAX_REPORT_CHARS = 16_384
        private const val PREPARE_TIMEOUT_MS = 30_000L
    }
}
