package dev.amatv.integration

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/** Captures only this player's declared session. Never captures the device mix or microphone. */
class SongAudioSpectrum(
    private val context: Context,
    private val currentAudioSessionId: () -> Int,
) {
    private val thread = HandlerThread("AMATV-song-spectrum", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var active = false
    @Volatile private var requested = false
    @Volatile private var status = SpectrumCaptureState.Disabled
    @Volatile private var released = false
    @Volatile private var frame = AudioSpectrum()
    @Volatile private var capturedAtMs = 0L
    private var capture: Visualizer? = null
    private var attachedSession = 0
    private var attachedAtMs = 0L
    private val retry = SpectrumCaptureRetry()
    private var declaredSessionLogged = 0
    private var attachedSessionLogged = 0
    private var signalSessionLogged = 0
    private var failedSessionLogged = 0
    private val sessionProbe = object : Runnable {
        override fun run() {
            if (released) return
            refreshCapture()
            // Broadcasts are quick hints, not a reliable initial-state or
            // session-change subscription on every playback route.
            handler.removeCallbacks(this)
            if (active && !released) handler.postDelayed(this, SESSION_PROBE_MS)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) != context.packageName) return
            // Query the existing renderer rather than trusting an older
            // close/open broadcast's session after a new one has begun.
            if (active) scheduleProbe()
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun captureState(): SpectrumCaptureState = status

    fun sample(): AudioSpectrum = if (active && SystemClock.elapsedRealtime() - capturedAtMs < 250L) {
        frame
    } else AudioSpectrum()

    fun setActive(enabled: Boolean) {
        if (released) return
        requested = enabled
        active = enabled && hasPermission()
        status = when {
            !enabled -> SpectrumCaptureState.Disabled
            !active -> SpectrumCaptureState.PermissionRequired
            capture == null -> SpectrumCaptureState.WaitingForSession
            else -> status
        }
        if (!active) {
            frame = AudioSpectrum()
            capturedAtMs = 0L
        }
        scheduleProbe()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun scheduleProbe() {
        if (released) return
        handler.removeCallbacks(sessionProbe)
        handler.post(sessionProbe)
    }

    /** Spectrum worker only. An absent SDK session always means silence. */
    private fun refreshCapture() {
        if (released) return
        if (active && !hasPermission()) {
            active = false
            status = SpectrumCaptureState.PermissionRequired
        }
        val desiredSession = if (active) {
            runCatching { currentAudioSessionId() }.getOrDefault(0).coerceAtLeast(0)
        } else 0
        val now = SystemClock.elapsedRealtime()
        retry.session(desiredSession)
        if (desiredSession == attachedSession && capture != null) {
            val lastData = capturedAtMs.takeIf { it > 0L } ?: attachedAtMs
            if (now - lastData < CAPTURE_STALE_MS) return
            // Some vendor effect routes attach successfully but never report a
            // callback, or stop reporting after a renderer handoff. A bounded
            // reattachment recovers those routes without spinning every 500ms.
            detachCapture()
            retry.failed(now)
            status = SpectrumCaptureState.Unavailable
            return
        }
        detachCapture()
        if (desiredSession <= 0) {
            status = when {
                !requested -> SpectrumCaptureState.Disabled
                !active -> SpectrumCaptureState.PermissionRequired
                else -> SpectrumCaptureState.WaitingForSession
            }
            return
        }
        if (!retry.canAttempt(now)) return
        if (declaredSessionLogged != desiredSession) {
            declaredSessionLogged = desiredSession
            Log.i(TAG, "Player declared audio session $desiredSession")
        }
        var created: Visualizer? = null
        try {
            var analyzer = SpectrumAnalyzer()
            var lastMs = SystemClock.elapsedRealtime()
            created = Visualizer(desiredSession)
            val visualizer = created
            val range = Visualizer.getCaptureSizeRange()
            check(visualizer.setCaptureSize(1_024.coerceIn(range[0], range[1])) == Visualizer.SUCCESS)
            val measurement = Visualizer.MeasurementPeakRms()
            var measuresLevel = runCatching {
                visualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS
            }.getOrDefault(false)
            check(visualizer.setScalingMode(if (measuresLevel) Visualizer.SCALING_MODE_NORMALIZED
                else Visualizer.SCALING_MODE_AS_PLAYED) == Visualizer.SUCCESS)
            check(visualizer.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) = Unit
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (!active || v !== capture || fft == null) return
                    val now = SystemClock.elapsedRealtime()
                    val hasLevel = measuresLevel && runCatching {
                        visualizer.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS
                    }.getOrDefault(false)
                    val switchingScaling = measuresLevel && !hasLevel
                    frame = if (switchingScaling) {
                        // A HAL may accept PEAK_RMS mode but reject its reads.
                        // Normalized FFT alone would amplify quiet noise. Switch
                        // to actual output scaling, discard this normalized frame,
                        // and analyze the next callback without synthetic energy.
                        val asPlayed = runCatching {
                            visualizer.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED) == Visualizer.SUCCESS
                        }.getOrDefault(false)
                        if (asPlayed) {
                            measuresLevel = false
                            analyzer = SpectrumAnalyzer()
                        } else {
                            // Keep playback intact when neither measurement nor
                            // as-played capture is usable on this output route.
                            frame = AudioSpectrum()
                            capturedAtMs = now
                            status = SpectrumCaptureState.Unavailable
                            handler.post {
                                if (capture === visualizer) {
                                    detachCapture()
                                    retry.failed(SystemClock.elapsedRealtime())
                                }
                            }
                            return
                        }
                        AudioSpectrum()
                    } else {
                        analyzer.analyze(fft, rate, now - lastMs, if (hasLevel) measurement.mRms else null)
                    }
                    status = if (switchingScaling) SpectrumCaptureState.WaitingForCapture else SpectrumCaptureState.Capturing
                    retry.captured()
                    capturedAtMs = now
                    lastMs = now
                    if (frame.available && signalSessionLogged != desiredSession) {
                        signalSessionLogged = desiredSession
                        Log.i(TAG, "Nonzero song FFT session=$desiredSession rate=" + rate / 1_000 + "Hz")
                    }
                }
            }, minOf(30_000, Visualizer.getMaxCaptureRate()), false, true) == Visualizer.SUCCESS)
            capture = visualizer
            attachedSession = desiredSession
            attachedAtMs = now
            status = SpectrumCaptureState.WaitingForCapture
            check(visualizer.setEnabled(true) == Visualizer.SUCCESS)
            if (attachedSessionLogged != desiredSession) {
                attachedSessionLogged = desiredSession
                Log.i(TAG, "Song FFT attached session=$desiredSession")
            }
        } catch (error: RuntimeException) {
            // Creation can fail before capture owns the new Visualizer.
            if (created !== capture) runCatching { created?.release() }
            detachCapture()
            retry.failed(now)
            status = SpectrumCaptureState.Unavailable
            // Retry with bounded backoff without affecting playback or logging
            // every retry on encoded/protected routes that do not expose PCM.
            if (failedSessionLogged != desiredSession) {
                failedSessionLogged = desiredSession
                Log.w(TAG, "Song FFT attach unavailable session=$desiredSession (" + error.javaClass.simpleName + ")")
            }
        }
    }

    private fun detachCapture() {
        val previous = capture
        capture = null
        attachedSession = 0
        attachedAtMs = 0L
        frame = AudioSpectrum()
        capturedAtMs = 0L
        runCatching { previous?.release() }
    }

    fun release() {
        if (released) return
        released = true
        active = false
        requested = false
        status = SpectrumCaptureState.Disabled
        frame = AudioSpectrum()
        capturedAtMs = 0L
        context.unregisterReceiver(receiver)
        handler.removeCallbacks(sessionProbe)
        handler.post { detachCapture() }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "AMATV-Spectrum"
        const val SESSION_PROBE_MS = 500L
        const val CAPTURE_STALE_MS = 2_000L
    }
}
