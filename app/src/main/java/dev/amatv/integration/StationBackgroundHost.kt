package dev.amatv.integration

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * An app-owned private window keeps the retained media WebView attached without
 * an Activity, overlay permission, or a visible display. It carries no UI input.
 */
internal class StationBackgroundHost private constructor(
    val container: FrameLayout,
    private val presentation: Presentation,
    private val display: VirtualDisplay,
    private val images: ImageReader,
) : AutoCloseable {
    override fun close() {
        container.removeAllViews()
        presentation.dismiss()
        display.release()
        images.setOnImageAvailableListener(null, null)
        images.close()
    }

    companion object {
        fun create(context: Context): StationBackgroundHost {
            val images = ImageReader.newInstance(1, 1, PixelFormat.RGBA_8888, 2)
            var display: VirtualDisplay? = null
            var presentation: Presentation? = null
            try {
                // Consume compositor output without a GL context. Leaving its
                // output queue full could otherwise block a retained WebView.
                images.setOnImageAvailableListener({ reader ->
                    runCatching { reader.acquireLatestImage()?.close() }
                }, Handler(Looper.getMainLooper()))
                display = checkNotNull(context.getSystemService(DisplayManager::class.java)
                    .createVirtualDisplay("AMATV station playback", 1, 1,
                        context.resources.displayMetrics.densityDpi.coerceAtLeast(1), images.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
                presentation = Presentation(context, display.display)
                val container = FrameLayout(presentation.context)
                presentation.setContentView(container)
                presentation.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                presentation.show()
                return StationBackgroundHost(container, presentation, display, images)
            } catch (failure: Exception) {
                presentation?.dismiss()
                display?.release()
                images.setOnImageAvailableListener(null, null)
                images.close()
                throw java.io.IOException("Station playback host unavailable", failure)
            }
        }
    }
}
