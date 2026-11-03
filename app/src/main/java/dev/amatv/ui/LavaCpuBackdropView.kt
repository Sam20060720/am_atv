package dev.amatv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.View
import dev.amatv.integration.AudioSpectrum

/**
 * Retains the same motion/palette without an extra EGL context,
 * TextureView hardware layer, or producer buffer queue. All field work is off UI.
 */
internal class LavaCpuBackdropController {
    private val worker = LavaCpuWorker()
    private var lastColors = IntArray(0)
    private var lastRunning = false

    fun update(colors: IntArray, running: Boolean, source: () -> AudioSpectrum) {
        check(Looper.myLooper() == Looper.getMainLooper())
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

    internal fun attach(output: LavaCpuOutput) = worker.attach(output)
    internal fun detach(output: LavaCpuOutput) = worker.detach(output)
    internal fun redraw(output: LavaCpuOutput) = worker.redraw(output)
    internal fun creditsAvailable() = worker.creditsAvailable()
    fun close() = worker.close()
}

/** Native output is an ordinary Canvas drawing a fixed, tiny bitmap ring. */
internal class LavaCpuBackdropView(context: Context, private val renderer: LavaCpuBackdropController) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destination = Rect()
    private var output: LavaCpuOutput? = null
    private var released = false
    private var initialized = false

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        initialized = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachOutput()
    }

    private fun attachOutput() {
        if (released || output != null || !isAttachedToWindow || windowVisibility != VISIBLE ||
            !isShown || width <= 0 || height <= 0) return
        // A new window visit gets new credits. Old, unacknowledged native
        // references are abandoned to normal Bitmap/native ref-count lifetime;
        // never recycle or force-reuse their storage after a cancelled frame.
        val current = LavaCpuOutput(this, renderer::creditsAvailable)
        output = current
        current.aspect = aspect()
        renderer.attach(current)
    }

    override fun onDetachedFromWindow() {
        retireOutput()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!initialized) return
        // A stopped window may cancel commit callbacks. Do not reinterpret a
        // missing acknowledgement as permission to overwrite a native reference.
        // Retire that visit's tiny ring and start fresh on the next visible visit.
        if (visibility == VISIBLE) attachOutput() else retireOutput()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!initialized) return
        if (isShown) attachOutput() else retireOutput()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        destination.set(0, 0, w, h)
        val current = output
        if (current == null) attachOutput() else {
            current.aspect = aspect()
            renderer.redraw(current)
        }
    }

    private fun aspect() = (width.toFloat() / height.coerceAtLeast(1)).coerceIn(.5f, 4f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = output ?: return
        val softwareCaptureOfHardwareWindow = isHardwareAccelerated && !canvas.isHardwareAccelerated
        // A software capture does not replace this hardware window's display
        // list. It must not release pixels still referenced by its RenderNode.
        val selected = current.selectForDraw(advance = !softwareCaptureOfHardwareWindow) ?: return
        canvas.drawBitmap(selected.bitmap, null, destination, paint)
        if (softwareCaptureOfHardwareWindow) return
        if (!current.registerFirstDraw(selected)) return
        if (!canvas.isHardwareAccelerated) {
            current.committed(selected)
        } else if (Build.VERSION.SDK_INT >= 29 && viewTreeObserver.isAlive) {
            // ViewRoot captures callbacks BEFORE onDraw. Registering now fences
            // the following frame, hence also this already-recorded replacement.
            // This extra frame intentionally trades latency for a safe bitmap credit.
            viewTreeObserver.registerFrameCommitCallback(selected.commit)
            postInvalidateOnAnimation()
        } else {
            // API28 has no public frame-commit acknowledgement. Preserve the
            // first picture rather than guessing a delay and racing RenderThread.
            current.freezeWithoutCommit()
        }
    }

    fun release() {
        released = true
        retireOutput()
    }

    private fun retireOutput() {
        output?.let { it.close(); renderer.detach(it) }
        output = null
    }
}

internal enum class LavaCpuCredit { Free, Writing, Ready, Current, Retired, Abandoned }

internal class LavaCpuSlot(private val output: LavaCpuOutput) {
    val bitmap: Bitmap = Bitmap.createBitmap(LavaCpuOutput.WIDTH, LavaCpuOutput.HEIGHT, Bitmap.Config.ARGB_8888)
    var state = LavaCpuCredit.Free
    var readyOrder = 0L
    var presentationOrder = 0L
    var commitRegistered = false
    var ownCommitConsumed = false
    // Exactly one registration per presentation. The slot cannot be reused
    // until this callback is consumed AND a later replacement frame commits.
    val commit = Runnable { output.committed(this) }
}

/**
 * Three fixed credits, one writer. CURRENT/READY/RETIRED are immutable. A retired
 * bitmap becomes writable only after a newer recorded frame's commit fence and
 * after its own callback has run, so a late callback cannot acknowledge new work.
 */
internal class LavaCpuOutput(private val view: LavaCpuBackdropView, private val onCreditsAvailable: () -> Unit) {
    private val lock = Any()
    private val slots = Array(3) { LavaCpuSlot(this) }
    private var current: LavaCpuSlot? = null
    private var readyOrder = 0L
    private var presentationOrder = 0L
    private var committedOrder = 0L
    private var active = true
    private var frozen = false
    @Volatile var aspect = WIDTH.toFloat() / HEIGHT
    @Volatile var visible = true

    fun claim(): LavaCpuSlot? = synchronized(lock) {
        if (!active || frozen) null
        else slots.firstOrNull { it.state == LavaCpuCredit.Free }?.also { it.state = LavaCpuCredit.Writing }
    }

    fun publish(slot: LavaCpuSlot) {
        val invalidate = synchronized(lock) {
            check(slot.state == LavaCpuCredit.Writing)
            if (!active) { slot.state = LavaCpuCredit.Abandoned; false }
            else {
                slot.readyOrder = ++readyOrder
                slot.state = LavaCpuCredit.Ready
                true
            }
        }
        if (invalidate) view.postInvalidateOnAnimation()
    }

    fun selectForDraw(advance: Boolean = true): LavaCpuSlot? {
        var droppedReady = false
        val selected = synchronized(lock) {
            if (!active) return@synchronized null
            val next = if (frozen || !advance) null else slots.filterReadyNewest()
            if (next != null) {
                // Dropped READY frames have never entered a display list and may
                // return immediately; this is different from an outgoing CURRENT.
                for (slot in slots) if (slot !== next && slot.state == LavaCpuCredit.Ready) {
                    slot.state = LavaCpuCredit.Free
                    droppedReady = true
                }
                current?.state = LavaCpuCredit.Retired
                next.state = LavaCpuCredit.Current
                next.presentationOrder = ++presentationOrder
                next.commitRegistered = false
                next.ownCommitConsumed = false
                current = next
            }
            current
        }
        if (droppedReady) onCreditsAvailable()
        return selected
    }

    private fun Array<LavaCpuSlot>.filterReadyNewest(): LavaCpuSlot? {
        var selected: LavaCpuSlot? = null
        for (slot in this) if (slot.state == LavaCpuCredit.Ready &&
            (selected == null || slot.readyOrder > selected.readyOrder)) selected = slot
        return selected
    }

    fun registerFirstDraw(slot: LavaCpuSlot): Boolean = synchronized(lock) {
        if (!active || slot.commitRegistered) false
        else { slot.commitRegistered = true; true }
    }

    fun committed(slot: LavaCpuSlot) {
        val freed = synchronized(lock) {
            if (!active || slot.ownCommitConsumed) return@synchronized false
            slot.ownCommitConsumed = true
            committedOrder = maxOf(committedOrder, slot.presentationOrder)
            var releasedCredit = false
            for (candidate in slots) if (candidate.state == LavaCpuCredit.Retired &&
                candidate.ownCommitConsumed && candidate.presentationOrder < committedOrder) {
                candidate.state = LavaCpuCredit.Free
                releasedCredit = true
            }
            releasedCredit
        }
        if (freed) onCreditsAvailable()
    }

    fun freezeWithoutCommit() = synchronized(lock) { frozen = true }

    fun close() = synchronized(lock) {
        active = false
        visible = false
        for (slot in slots) if (slot.state != LavaCpuCredit.Writing) slot.state = LavaCpuCredit.Abandoned
        // No Bitmap.recycle(): native display-list references can outlive View detach.
    }

    companion object {
        const val WIDTH = 96
        const val HEIGHT = 54
    }
}

private class LavaCpuWorker {
    private val thread = HandlerThread("AMATV-lava-cpu", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val handler = Handler(thread.looper)
    private val choreographer by lazy { Choreographer.getInstance() }
    private val field = LavaCpuField(LavaCpuOutput.WIDTH, LavaCpuOutput.HEIGHT)
    private val pixels = IntArray(LavaCpuOutput.WIDTH * LavaCpuOutput.HEIGHT)
    private val motion = BackdropMotion()
    private val palette = LavaPaletteTransition(LavaPalette.from(intArrayOf(0xFF33333B.toInt())))
    private var output: LavaCpuOutput? = null
    private var running = false
    private var scheduled = false
    private var pendingFrame = false
    private var previousFrame = 0L
    private var rendered = 0
    private var blocked = 0
    private var totalNanos = 0L
    private var maximumNanos = 0L
    @Volatile private var closed = false
    @Volatile var spectrum: () -> AudioSpectrum = { SILENCE }

    private val creditWake = Runnable {
        if (!closed && pendingFrame) request()
    }

    private val frame = Choreographer.FrameCallback { timestamp ->
        scheduled = false
        val target = output
        if (!closed && target != null && target.visible) {
            pendingFrame = true
            if (running) motion.advance(if (previousFrame == 0L) 0L else timestamp - previousFrame, spectrum())
            previousFrame = if (running) timestamp else 0L
            val slot = target.claim()
            if (slot != null) {
                val start = System.nanoTime()
                field.setAspect(target.aspect)
                field.render(motion.elapsedNanos, motion.bass, motion.bassImpact, motion.mid, motion.treble,
                    palette.sample(timestamp), pixels)
                slot.bitmap.setPixels(pixels, 0, LavaCpuOutput.WIDTH, 0, 0, LavaCpuOutput.WIDTH, LavaCpuOutput.HEIGHT)
                target.publish(slot)
                pendingFrame = false
                val elapsed = System.nanoTime() - start
                if (rendered < 120) {
                    totalNanos += elapsed; maximumNanos = maxOf(maximumNanos, elapsed); rendered++
                    if (rendered == 120) Log.i(TAG, "120 computed frames; worker field+copy mean=${totalNanos / 120_000}us max=${maximumNanos / 1_000}us; credit-blocked callbacks=$blocked; not UI/GPU timing")
                }
            } else if (rendered < 120) blocked++
            // Keep the final paused/palette frame pending when all credits are
            // held. A real retirement callback resumes it; never poll forever
            // or substitute a timer for a missing RenderThread acknowledgement.
            if (slot != null && (running || palette.isChanging(timestamp))) request()
        }
    }

    private fun request() {
        if (!closed && !scheduled && output?.visible == true) {
            scheduled = true
            choreographer.postFrameCallback(frame)
        }
    }

    fun palette(colors: FloatArray) { handler.post {
        if (!closed) { palette.retarget(colors, System.nanoTime()); request() }
    } }

    fun running(value: Boolean) { handler.post {
        running = value; previousFrame = 0L; request()
    } }

    fun attach(value: LavaCpuOutput) { handler.post {
        if (!closed) { output = value; previousFrame = 0L; request() }
    } }

    fun detach(value: LavaCpuOutput) { handler.post {
        if (output === value) {
            output = null; previousFrame = 0L
            if (scheduled) choreographer.removeFrameCallback(frame)
            scheduled = false
        }
    } }

    fun redraw(value: LavaCpuOutput) { handler.post { if (output === value) request() } }

    fun creditsAvailable() { handler.post(creditWake) }

    fun close() {
        if (closed) return
        closed = true
        handler.post {
            if (scheduled) choreographer.removeFrameCallback(frame)
            scheduled = false; pendingFrame = false; output = null; thread.quitSafely()
        }
    }

    companion object {
        const val TAG = "AMATV-lava-cpu"
        val SILENCE = AudioSpectrum()
    }
}
