package dev.amatv.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import dev.amatv.integration.AudioSpectrum
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Keep only the phase/envelopes while its native output is not on screen. */
internal class LavaBackdropController {
    private val worker = LavaRenderWorker()
    private var lastColors = IntArray(0)
    private var lastRunning = false

    fun update(colors: IntArray, running: Boolean, source: () -> AudioSpectrum) {
        worker.spectrum = source
        if (!colors.contentEquals(lastColors)) {
            lastColors = colors.copyOf()
            worker.palette(LavaPalette.from(colors))
        }
        if (running != lastRunning) {
            lastRunning = running
            worker.running(running)
        }
    }

    fun attach(surface: SurfaceTexture, width: Int, height: Int) = worker.attach(surface, width, height)
    fun resize(surface: SurfaceTexture, width: Int, height: Int) = worker.resize(surface, width, height)
    fun detach(surface: SurfaceTexture) = worker.detach(surface)
    fun close() = worker.close()
}

/** TextureView participates in the same composition as lyrics/focus; no separate surface hole. */
internal class LavaBackdropView(context: Context, private val renderer: LavaBackdropController) : TextureView(context), TextureView.SurfaceTextureListener {
    private var ownedSurface: SurfaceTexture? = null
    private var released = false

    init {
        isOpaque = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    private fun bufferSize(width: Int, height: Int): Pair<Int, Int> {
        // Heavy blur needs no full-HD producer texture. Keep the original aspect, and let
        // TextureView's hardware composition enlarge this bounded texture behind the UI.
        val scale = minOf(1f, 960f / width.coerceAtLeast(1))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        return w to h
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ownedSurface = surface
        if (released) return
        val (w, h) = bufferSize(width, height)
        renderer.attach(surface, w, h)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (released || ownedSurface !== surface) return
        val (w, h) = bufferSize(width, height)
        renderer.resize(surface, w, h)
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (ownedSurface === surface) {
            ownedSurface = null
            renderer.detach(surface)
        }
        // Release only after the producer thread has stopped using this surface.
        return false
    }

    fun release() {
        released = true
        // TextureView destroys its HWUI layer before invoking Destroyed. Let
        // that callback hand off the SurfaceTexture, never free the consumer
        // early merely because Compose has begun removing its AndroidView.
    }
}

private class LavaRenderWorker {
    private val thread = HandlerThread("AMATV-lava", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val choreographer by lazy { Choreographer.getInstance() }
    @Volatile var spectrum: () -> AudioSpectrum = { SILENCE }
    @Volatile private var closed = false
    private var surface: SurfaceTexture? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private val recovery = BackdropRecovery()
    private val recover = Runnable {
        if (!closed && surface != null && egl == null) createSession()
    }
    private var egl: LavaEglSession? = null
    private var enabled = false
    private var scheduled = false
    private var previousFrame = 0L
    private var settlingFrames = 0
    private val motion = BackdropMotion()
    private val palette = LavaPaletteTransition(LavaPalette.from(intArrayOf(0xFF33333B.toInt())))
    private var activeFrames = 0
    private var drawNanos = 0L
    private var maximumDrawNanos = 0L
    private var swapNanos = 0L
    private var maximumSwapNanos = 0L
    private var cadenceNanos = 0L
    private var cadenceSamples = 0
    private var maximumCadenceNanos = 0L
    private val bandMaximum = FloatArray(3)
    private var responseFrames = 0
    private var responseWindows = 0
    private val responseRange = floatArrayOf(1f, 0f, 1f, 0f)

    private val frame = Choreographer.FrameCallback { timestamp ->
        scheduled = false
        val session = egl
        if (!closed && session != null) {
            try {
                val measured = if (enabled) spectrum() else SILENCE
                if (enabled && previousFrame != 0L) {
                    val interval = timestamp - previousFrame
                    motion.advance(interval, measured)
                    if (activeFrames < 300) {
                        cadenceNanos += interval
                        maximumCadenceNanos = maxOf(maximumCadenceNanos, interval)
                        cadenceSamples++
                    }
                }
                previousFrame = if (enabled) timestamp else 0L
                val measureFrame = enabled && activeFrames < 300
                val start = if (measureFrame) System.nanoTime() else 0L
                session.draw(motion, palette.sample(timestamp), measureFrame)
                val duration = if (measureFrame) System.nanoTime() - start else 0L
                val swapStart = if (measureFrame) System.nanoTime() else 0L
                session.swap()
                val swapDuration = if (measureFrame) System.nanoTime() - swapStart else 0L
                if (measureFrame) {
                    drawNanos += duration
                    maximumDrawNanos = maxOf(maximumDrawNanos, duration)
                    swapNanos += swapDuration
                    maximumSwapNanos = maxOf(maximumSwapNanos, swapDuration)
                    bandMaximum[0] = maxOf(bandMaximum[0], motion.bass)
                    bandMaximum[1] = maxOf(bandMaximum[1], motion.mid)
                    bandMaximum[2] = maxOf(bandMaximum[2], motion.treble)
                    activeFrames++
                    if (activeFrames == 300) {
                        Log.i(TAG, "300 frames; CPU submit mean=${drawNanos / 300_000}us max=${maximumDrawNanos / 1_000}us; " +
                            "swap mean=${swapNanos / 300_000}us max=${maximumSwapNanos / 1_000}us; " +
                            "${session.gpuTiming()}; " +
                            "display callback mean=${if (cadenceSamples > 0) cadenceNanos / cadenceSamples / 1_000 else -1}us max=${maximumCadenceNanos / 1_000}us; " +
                            "measured band maxima=${bandMaximum.joinToString { (it * 100).roundToInt().toString() }}%")
                    }
                }
                if (enabled && measured.available && motion.elapsedNanos > 2_000_000_000L && responseWindows < 4) {
                    responseRange[0] = minOf(responseRange[0], measured.bass)
                    responseRange[1] = maxOf(responseRange[1], measured.bass)
                    responseRange[2] = minOf(responseRange[2], motion.bassImpact)
                    responseRange[3] = maxOf(responseRange[3], motion.bassImpact)
                    responseFrames++
                    if (responseFrames == 120) {
                        Log.i(TAG, "Bass response: measured=${(responseRange[0] * 100).roundToInt()}..${(responseRange[1] * 100).roundToInt()}%, impact=${(responseRange[2] * 100).roundToInt()}..${(responseRange[3] * 100).roundToInt()}%")
                        responseWindows++
                        responseFrames = 0
                        responseRange[0] = 1f; responseRange[1] = 0f
                        responseRange[2] = 1f; responseRange[3] = 0f
                    }
                }
                recovery.succeeded()
                settlingFrames = (settlingFrames - 1).coerceAtLeast(0)
                if (enabled || settlingFrames > 0 || palette.isChanging(timestamp)) requestFrame()
            } catch (error: Throwable) {
                // Keep the last complete texture instead of clearing/retrying every frame.
                Log.e(TAG, "Native background renderer interrupted", error)
                destroySession()
                scheduleRecovery()
            }
        }
    }

    private fun requestFrame() {
        if (!scheduled && !closed && egl != null) {
            scheduled = true
            choreographer.postFrameCallback(frame)
        }
    }

    fun palette(colors: FloatArray) { handler.post {
        if (!closed) { palette.retarget(colors, System.nanoTime()); requestFrame() }
    } }

    fun running(value: Boolean) { handler.post {
        enabled = value
        previousFrame = 0L
        if (value && egl == null && surface != null) {
            // A new visible playback visit may retry a route whose previous
            // consecutive failures exhausted the bounded recovery allowance.
            recovery.succeeded()
            handler.removeCallbacks(recover)
            createSession()
        } else requestFrame()
    } }

    private fun createSession() {
        val texture = surface ?: return
        if (closed) return
        try {
            egl = LavaEglSession(texture, surfaceWidth, surfaceHeight)
            settlingFrames = 2
            requestFrame()
        } catch (error: Throwable) {
            Log.e(TAG, "Native background initialization failed", error)
            scheduleRecovery()
        }
    }

    private fun scheduleRecovery() {
        if (closed || surface == null) return
        val delay = recovery.retryDelayMs() ?: return
        handler.removeCallbacks(recover)
        handler.postDelayed(recover, delay)
    }

    fun attach(texture: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            // The View still owns its live consumer until Destroyed; that
            // callback also releases a texture when this worker has quit.
            if (closed) return@post
            handler.removeCallbacks(recover)
            recovery.succeeded()
            destroySession()
            surface = texture
            surfaceWidth = width
            surfaceHeight = height
            createSession()
            Log.i(TAG, "Output attached ${width}x$height; retained phase=${motion.elapsedNanos}ns")
        }
    }

    fun resize(texture: SurfaceTexture, width: Int, height: Int) { handler.post {
        if (closed || surface !== texture) return@post
        surfaceWidth = width
        surfaceHeight = height
        try {
            if (egl == null) {
                handler.removeCallbacks(recover)
                recovery.succeeded()
                createSession()
                return@post
            }
            egl?.resize(width, height)
            // A paused player also needs the resized producer's first complete buffers.
            settlingFrames = 2
            requestFrame()
        } catch (error: Throwable) {
            Log.e(TAG, "Native background resize failed", error)
            destroySession()
            scheduleRecovery()
        }
    } }

    fun detach(texture: SurfaceTexture) {
        if (!handler.post {
            if (surface === texture) {
                handler.removeCallbacks(recover)
                destroySession()
                surface = null
                Log.i(TAG, "Output released; retained phase=${motion.elapsedNanos}ns")
            }
            texture.release()
        }) texture.release()
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post {
            handler.removeCallbacks(recover)
            destroySession()
            surface = null
            thread.quitSafely()
        }
    }

    private fun destroySession() {
        if (scheduled) choreographer.removeFrameCallback(frame)
        scheduled = false
        previousFrame = 0L
        settlingFrames = 0
        egl?.release()
        egl = null
    }

    private companion object {
        const val TAG = "AMATV-lava"
        val SILENCE = AudioSpectrum()
    }
}

private class LavaEglSession(private val texture: SurfaceTexture, width: Int, height: Int) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var configuration: EGLConfig? = null
    private val surfaceSize = IntArray(2)
    private val window = Surface(texture)
    private var renderer: LavaGlRenderer? = null
    private var viewportSettlingFrames = 0

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            fun chooseConfig(bit: Int): EGLConfig? {
                val attributes = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, bit, EGL14.EGL_NONE,
                )
                return if (EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
            }
            var config = chooseConfig(0x40) // EGL_OPENGL_ES3_BIT_KHR, when the device supports it.
            if (config != null) {
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            }
            if (context == EGL14.EGL_NO_CONTEXT) {
                EGL14.eglGetError()
                config = checkNotNull(chooseConfig(EGL14.EGL_OPENGL_ES2_BIT))
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            }
            check(context != EGL14.EGL_NO_CONTEXT)
            configuration = checkNotNull(config)
            createWindowSurface(width, height)
            renderer = LavaGlRenderer(surfaceSize[0], surfaceSize[1])
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    private fun createWindowSurface(width: Int, height: Int) {
        texture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        eglSurface = EGL14.eglCreateWindowSurface(
            display, configuration, window, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
        check(EGL14.eglSwapInterval(display, 1))
        synchronizeViewport()
        // Some producers apply the requested buffer size only after the first swap.
        // Recheck a bounded settling window rather than trusting the requested size
        // or permanently querying the driver on every animation frame.
        viewportSettlingFrames = 8
    }

    private fun synchronizeViewport() {
        // Requested TextureView dimensions are not the EGL drawable's dimensions.
        // A 960x540 viewport in a 1920x1080 drawable paints only its lower-left quarter.
        check(EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, surfaceSize, 0))
        check(EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, surfaceSize, 1))
        check(surfaceSize[0] > 0 && surfaceSize[1] > 0)
        renderer?.resize(surfaceSize[0], surfaceSize[1])
    }

    fun resize(width: Int, height: Int) {
        // SurfaceTexture requires a new EGL window surface for a new default buffer
        // size to take effect reliably. Retain the context, shaders and last latched
        // TextureView frame so the lava keeps its phase without a black clear.
        check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
        check(EGL14.eglDestroySurface(display, eglSurface))
        eglSurface = EGL14.EGL_NO_SURFACE
        createWindowSurface(width, height)
    }

    fun draw(motion: BackdropMotion, colors: FloatArray, measureGpu: Boolean) {
        if (viewportSettlingFrames > 0) {
            synchronizeViewport()
            viewportSettlingFrames--
        }
        renderer?.draw(motion, colors, measureGpu)
    }
    fun gpuTiming(): String = renderer?.gpuTiming() ?: "GPU timer unavailable"
    fun swap() { check(EGL14.eglSwapBuffers(display, eglSurface)) { "EGL swap failed: ${EGL14.eglGetError()}" } }
    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            renderer?.release()
            renderer = null
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
        window.release()
    }
}

/** Four bounded GPU passes: field, horizontal blur, vertical blur, output-resolution dither. */
private class LavaGlRenderer(private var width: Int, private var height: Int) {
    private val vertices = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)); position(0)
    }
    private val field = program(FIELD)
    private val blur = program(BLUR)
    private val output = program(OUTPUT)
    private val textures = IntArray(2)
    private val targets = IntArray(2)
    private val timeLocation = GLES20.glGetUniformLocation(field, "t")
    private val aspectLocation = GLES20.glGetUniformLocation(field, "aspect")
    private val colorsLocation = GLES20.glGetUniformLocation(field, "colors[0]")
    private val energyLocation = GLES20.glGetUniformLocation(field, "energy")
    private val bassImpactLocation = GLES20.glGetUniformLocation(field, "bassImpact")
    private val blurStepLocation = GLES20.glGetUniformLocation(blur, "stepSize")
    private var bufferWidth = 0
    private var bufferHeight = 0
    private val halfFloatType: Int
    private val halfFloatFormat: Int
    private val gpuTimer: LavaGpuTimer?

    init {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glGenTextures(2, textures, 0)
        GLES20.glGenFramebuffers(2, targets, 0)
        for (texture in textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val es3 = GLES20.glGetString(GLES20.GL_VERSION).orEmpty().contains("OpenGL ES 3")
        gpuTimer = if (es3 && extensions.split(' ').contains("GL_EXT_disjoint_timer_query")) LavaGpuTimer.create() else null
        halfFloatType = when {
            es3 -> 0x140B // GL_HALF_FLOAT
            listOf("GL_OES_texture_half_float", "GL_OES_texture_half_float_linear", "GL_EXT_color_buffer_half_float").all(extensions::contains) -> 0x8D61
            else -> 0
        }
        halfFloatFormat = if (es3) 0x881A else GLES20.GL_RGBA // GL_RGBA16F on ES3
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, vertices)
        Log.i("AMATV-lava", "Native ${GLES20.glGetString(GLES20.GL_VERSION)} ${GLES20.glGetString(GLES20.GL_RENDERER)}; half-float candidate=${halfFloatType != 0}; nonblocking GPU timer=${gpuTimer != null}")
    }

    fun resize(w: Int, h: Int) { width = w.coerceAtLeast(1); height = h.coerceAtLeast(1) }

    private fun allocate(type: Int, format: Int = GLES20.GL_RGBA): Boolean {
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, bufferWidth, bufferHeight, 0, GLES20.GL_RGBA, type, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return false
        }
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    fun draw(motion: BackdropMotion, palette: FloatArray, measureGpu: Boolean) {
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val lowHeight = 128
        val lowWidth = (lowHeight * aspect).roundToInt().coerceIn(64, 512)
        if (lowWidth != bufferWidth || lowHeight != bufferHeight) {
            bufferWidth = lowWidth; bufferHeight = lowHeight
            val highPrecision = halfFloatType != 0 && allocate(halfFloatType, halfFloatFormat)
            if (!highPrecision) {
                repeat(8) { GLES20.glGetError() }
                check(allocate(GLES20.GL_UNSIGNED_BYTE)) { "Unable to allocate lava targets" }
            }
            Log.i("AMATV-lava", "Field ${bufferWidth}x$bufferHeight; intermediate=${if (highPrecision) "RGBA16F" else "RGBA8"}; output=${width}x$height")
        }
        // The diagnostic window is bounded too: no query polling once its report
        // has been emitted. Unsupported devices use exactly the normal draw path.
        if (measureGpu) gpuTimer?.begin()
        GLES20.glUseProgram(field)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[0])
        GLES20.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES20.glUniform1f(timeLocation, motion.elapsedNanos / 1_000_000_000f * 2.4f)
        GLES20.glUniform1f(aspectLocation, aspect)
        GLES20.glUniform3fv(colorsLocation, 5, palette, 0)
        GLES20.glUniform3f(energyLocation, motion.bass, motion.mid, motion.treble)
        GLES20.glUniform1f(bassImpactLocation, motion.bassImpact)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        // Heavy blur in height-normalized units, independent of display resolution.
        GLES20.glUseProgram(blur)
        GLES20.glUniform2f(blurStepLocation, .02475f / aspect, 0f)
        sample(blur, textures[0], targets[1], bufferWidth, bufferHeight)
        GLES20.glUniform2f(blurStepLocation, 0f, .02475f)
        sample(blur, textures[1], targets[0], bufferWidth, bufferHeight)
        sample(output, textures[0], 0, width, height)
        if (measureGpu) gpuTimer?.end()
    }

    fun gpuTiming(): String = gpuTimer?.report() ?: "GPU timer unavailable"

    private fun sample(program: Int, texture: Int, destination: Int, w: Int, h: Int) {
        GLES20.glUseProgram(program)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destination)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        // Samplers default to unit zero. No uniform lookup or new objects on the frame path.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    fun release() {
        gpuTimer?.release()
        GLES20.glDeleteFramebuffers(2, targets, 0)
        GLES20.glDeleteTextures(2, textures, 0)
        GLES20.glDeleteProgram(field); GLES20.glDeleteProgram(blur); GLES20.glDeleteProgram(output)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun program(fragment: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val shader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, shader)
        GLES20.glBindAttribLocation(program, 0, "a")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(shader)
        return program
    }

    private companion object {
        const val VERTEX = "attribute vec2 a; varying vec2 uv; void main(){uv=a*.5+.5;gl_Position=vec4(a,0.,1.);}"
        val FIELD = """
            precision highp float;
            varying vec2 uv; uniform float t; uniform float aspect; uniform vec3 colors[5]; uniform vec3 energy; uniform float bassImpact;
            float ellipse(vec2 p, vec2 c, vec2 r){return (length((p-c)/r)-1.)*min(r.x,r.y);}
            float merge(float a,float b,float k){float h=clamp(.5+.5*(b-a)/k,0.,1.);return mix(b,a,h)-k*h*(1.-h);}
            void main(){
              vec2 p=vec2(uv.x*aspect,1.-uv.y);float x=p.x;
              p.x+=.06*sin(p.y*5.+t*.16)+.03*sin(p.y*9.-t*.11);
              p.y+=.045*sin(x*3.8-t*.12);
              float center=aspect*.61;
              float hit=bassImpact;
              float g=ellipse(p,vec2(center+.19*sin(t*.17)-.07*hit,.07+.15*sin(t*.12)),vec2(.165+.06*energy.x+.14*hit,.49+.025*energy.x+.04*hit));
              g=merge(g,ellipse(p,vec2(center+.21*sin(t*.13+.8)+.09*hit,.43+.14*sin(t*.18)),vec2(.18+.055*energy.x+.12*hit,.30-.025*hit)),.12);
              g=merge(g,ellipse(p,vec2(center-.08+.25*sin(t*.11+2.),.91+.20*cos(t*.17)-.07*hit),vec2(.12+.035*energy.x+.08*hit,.15)),.10);
              float side=ellipse(p,vec2(-.13+.18*sin(t*.12+.7),.79+.18*sin(t*.10)),vec2(.34+.04*hit,.61));
              side=merge(side,ellipse(p,vec2(aspect+.14+.07*cos(t*.13),.24+.12*cos(t*.11)),vec2(.27,.70)),.08);
              float m=ellipse(p,vec2(aspect*.34+.18*sin(t*.1)-.05*hit,1.07+.18*cos(t*.14)),vec2(.31-.025*hit,.45));
              m=merge(m,ellipse(p,vec2(aspect*.85+.16*sin(t*.11+2.),.17+.16*cos(t*.15)),vec2(.16,.57)),.08);
              m=merge(m,ellipse(p,vec2(aspect*.94+.14*cos(t*.09),1.19+.10*sin(t*.12)),vec2(.37,.43)),.1);
              float gm=1.-smoothstep(-.012,.018,g),sm=1.-smoothstep(-.012,.018,side),mm=1.-smoothstep(-.012,.018,m);
              vec3 ink=colors[0]*(.96+.045*sin(p.y*2.+p.x)+.012*energy.y+.006*energy.z);
              vec3 edgeColor=mix(colors[1],mix(colors[3],colors[4],smoothstep(0.,aspect,p.x)),.24);
              vec3 color=mix(ink,edgeColor,sm*.9);
              color=mix(color,colors[1]*(.84+.16*(1.-smoothstep(-.15,.05,g))),gm);
              color=mix(color,colors[2]*(.84+.16*(1.-smoothstep(-.18,.05,m))),mm*(1.-gm*.65));
              gl_FragColor=vec4(color,1.);
            }
        """.trimIndent()
        val BLUR = """
            precision mediump float; varying vec2 uv; uniform sampler2D image; uniform vec2 stepSize;
            void main(){vec3 color=texture2D(image,uv).rgb*.227027027;
              color+=(texture2D(image,uv+stepSize).rgb+texture2D(image,uv-stepSize).rgb)*.194594595;
              color+=(texture2D(image,uv+stepSize*2.).rgb+texture2D(image,uv-stepSize*2.).rgb)*.121621622;
              color+=(texture2D(image,uv+stepSize*3.).rgb+texture2D(image,uv-stepSize*3.).rgb)*.054054054;
              color+=(texture2D(image,uv+stepSize*4.).rgb+texture2D(image,uv-stepSize*4.).rgb)*.016216216;
              gl_FragColor=vec4(color,1.);}
        """.trimIndent()
        val OUTPUT = """
            precision mediump float; varying vec2 uv; uniform sampler2D image;
            void main(){vec3 color=texture2D(image,uv).rgb;
              float n=fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))))-.5;
              gl_FragColor=vec4(clamp(color+n/255.,0.,1.),1.);}
        """.trimIndent()
    }
}

/** Optional diagnostics: consume only completed queries; never wait for the GPU or call glFinish. */
private class LavaGpuTimer private constructor() {
    private val queries = IntArray(4)
    private val pending = BooleanArray(4)
    private val valid = BooleanArray(4)
    private val completed = BooleanArray(4)
    private val value = IntArray(1)
    private var next = 0
    private var active = -1
    private var samples = 0
    private var totalNanos = 0L
    private var maximumNanos = 0L

    init { GLES30.glGenQueries(queries.size, queries, 0) }

    fun begin() {
        // A query older than this frame may still be queued behind other GPU work.
        // Leave it alone until availability is true rather than serializing rendering.
        for (index in queries.indices) {
            completed[index] = false
            if (pending[index]) {
                GLES30.glGetQueryObjectuiv(queries[index], GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
                completed[index] = value[0] != 0
            }
        }
        GLES20.glGetIntegerv(GPU_DISJOINT, value, 0)
        if (value[0] != 0) valid.fill(false)
        for (index in queries.indices) {
            if (!completed[index]) continue
            GLES30.glGetQueryObjectuiv(queries[index], GLES30.GL_QUERY_RESULT, value, 0)
            pending[index] = false
            // The Java ES3 API exposes the 32-bit getter. A saturated result is
            // not a usable sub-frame duration, and must not be reported as one.
            if (valid[index] && value[0] != 0 && value[0] != -1) {
                val duration = value[0].toLong() and 0xffffffffL
                totalNanos += duration
                maximumNanos = maxOf(maximumNanos, duration)
                samples++
            }
        }
        if (!pending[next]) {
            active = next
            valid[active] = true
            GLES30.glBeginQuery(TIME_ELAPSED, queries[next])
            next = (next + 1) % queries.size
        }
    }

    fun end() {
        if (active < 0) return
        GLES30.glEndQuery(TIME_ELAPSED)
        pending[active] = true
        active = -1
    }

    fun release() { GLES30.glDeleteQueries(queries.size, queries, 0) }

    fun report(): String = if (samples == 0) "GPU timer unavailable: no positive valid results"
        else "GPU samples=$samples mean=${totalNanos / samples / 1_000}us max=${maximumNanos / 1_000}us"

    companion object {
        const val TIME_ELAPSED = 0x88BF // GL_TIME_ELAPSED_EXT
        const val GPU_DISJOINT = 0x8FBB // GL_GPU_DISJOINT_EXT
        private const val QUERY_COUNTER_BITS = 0x8864 // GL_QUERY_COUNTER_BITS_EXT

        fun create(): LavaGpuTimer? {
            val bits = IntArray(1)
            GLES30.glGetQueryiv(TIME_ELAPSED, QUERY_COUNTER_BITS, bits, 0)
            // EXT allows a zero-bit counter, which cannot provide timings.
            return if (bits[0] >= 30) LavaGpuTimer() else null
        }
    }
}
