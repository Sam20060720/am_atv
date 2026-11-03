package dev.amatv.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View

/**
 * UI-owned TextureView and crop only. Media3 never receives this View; raw
 * Surface attachments and native detach acknowledgments belong to its worker.
 */
internal class EditorialTextureView(context: Context) : TextureView(context) {
    private var boundLease: MotionPreviewLease? = null
    private var output: MotionPreviewOutput? = null
    private var currentTexture: TextureLifetime? = null
    private val textures = java.util.IdentityHashMap<SurfaceTexture, TextureLifetime>()
    private var fallbackAspect: Float? = null
    private var decodedAspect: Float? = null
    private val crop = Matrix()
    private var transformedWidth = 0
    private var transformedHeight = 0
    private var transformedAspect = Float.NaN

    /** One native texture can outlive multiple pending Surface clear acknowledgments. */
    private inner class TextureLifetime(val texture: SurfaceTexture) {
        var outputs = 0
        var destroyed = false
        private var released = false
        fun releaseIfSafe() {
            if (destroyed && outputs == 0 && !released) {
                released = true
                textures.remove(texture)
                MotionPreviewCoordinator.releaseTexture(texture)
            }
        }
    }

    private val textureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val lifetime = textures[texture] ?: TextureLifetime(texture).also { textures[texture] = it }
            currentTexture = lifetime
            attachOutputIfReady()
            updateCrop()
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { updateCrop() }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            val lifetime = textures[texture] ?: TextureLifetime(texture).also { textures[texture] = it }
            if (currentTexture === lifetime) {
                currentTexture = null
                detachOutput()
            }
            // Returning false transfers release ownership to this view. Never
            // let the UI free a texture while a codec still renders into it.
            lifetime.destroyed = true
            lifetime.releaseIfSafe()
            return false
        }
    }

    init {
        isOpaque = false
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = textureListener
    }

    fun bindPlayer(lease: MotionPreviewLease, sourceAspect: Float?) {
        fallbackAspect = sourceAspect?.takeIf { it.isFinite() && it > 0f }
        if (boundLease !== lease) {
            detachPlayer()
            boundLease = lease
            decodedAspect = lease.videoAspect
        }
        // A view may be rebound after availability or temporarily reattached to
        // a window; no Player listener or Player getter runs on the UI thread.
        if (currentTexture == null && isAvailable) surfaceTexture?.let { texture ->
            currentTexture = textures[texture] ?: TextureLifetime(texture).also { textures[texture] = it }
        }
        attachOutputIfReady()
        updateCrop()
    }

    private fun attachOutputIfReady() {
        if (output != null) return
        val lease = boundLease ?: return
        val lifetime = currentTexture?.takeUnless { it.destroyed } ?: return
        val surface = Surface(lifetime.texture)
        lifetime.outputs++
        output = MotionPreviewCoordinator.bindOutput(lease, surface,
            onAspect = { aspect ->
                if (boundLease === lease) {
                    decodedAspect = aspect
                    updateCrop()
                }
            },
            onCleared = {
                // This callback is posted to main ONLY after worker-side
                // clearVideoSurface has completed its renderer acknowledgment.
                lifetime.outputs--
                lifetime.releaseIfSafe()
            })
    }

    private fun detachOutput() {
        val previous = output ?: return
        output = null
        MotionPreviewCoordinator.detachOutput(previous)
    }

    /** Keep the mounted last GPU buffer/crop for the outgoing poster fade. */
    fun detachPlayer() {
        boundLease = null
        detachOutput()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateCrop()
    }

    private fun updateCrop() {
        if (width <= 0 || height <= 0) return
        val aspect = decodedAspect ?: fallbackAspect ?: width.toFloat() / height
        if (transformedWidth == width && transformedHeight == height && transformedAspect == aspect) return
        transformedWidth = width
        transformedHeight = height
        transformedAspect = aspect
        val viewportAspect = width.toFloat() / height
        crop.setScale((aspect / viewportAspect).coerceAtLeast(1f), (viewportAspect / aspect).coerceAtLeast(1f),
            width / 2f, height / 2f)
        setTransform(crop)
    }
}
