package com.droidlink.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import java.io.ByteArrayOutputStream

/**
 * Captures the screen through MediaProjection (the user already granted the official system consent)
 * and emits JPEG frames. Quality and frame rate adapt to network back-pressure; rotation is handled
 * by resizing the virtual display (creating a second one on the same projection is not allowed on Android 14+).
 */
class ScreenCaptureEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val controller: AdaptiveStreamController,
    private val backlog: () -> Long,
    private val onFrame: (jpeg: ByteArray, width: Int, height: Int) -> Unit,
    private val onStopped: () -> Unit,
) {
    @Volatile var scalePercent: Int = 60
    private val thread = HandlerThread("droidlink-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var padded: Bitmap? = null
    private var lastFrameAt = 0L
    @Volatile private var stopped = false
    @Volatile private var lastJpeg: ByteArray? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture from the system UI (status bar chip / notification shade).
            if (!stopped) {
                release()
                onStopped()
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) handler.post { resizeIfNeeded() }
        }
    }

    fun start() {
        // Android 14+: a callback must be registered before creating the virtual display.
        projection.registerCallback(projectionCallback, handler)
        displayManager.registerDisplayListener(displayListener, handler)
        handler.post { createDisplay() }
    }

    /** Re-sends the latest frame (a newly connected viewer would otherwise wait for the screen to change). */
    fun resendLastFrame() {
        handler.post { lastJpeg?.let { onFrame(it, width, height) } }
    }

    fun applySettings(fps: Int, quality: Int, scale: Int?) {
        controller.configure(fps, quality)
        if (scale != null) {
            scalePercent = scale.coerceIn(25, 100)
            handler.post { resizeIfNeeded(force = true) }
        }
    }

    private fun realSize(): Point {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        return p
    }

    private fun targetSize(): Pair<Int, Int> {
        val real = realSize()
        val f = scalePercent / 100f
        val w = ((real.x * f).toInt() and 1.inv()).coerceAtLeast(64)
        val h = ((real.y * f).toInt() and 1.inv()).coerceAtLeast(64)
        return w to h
    }

    private fun createDisplay() {
        if (stopped) return
        val (w, h) = targetSize()
        width = w
        height = h
        val r = newReader(w, h)
        reader = r
        virtualDisplay = projection.createVirtualDisplay(
            "DroidLink",
            w,
            h,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            handler,
        )
    }

    private fun resizeIfNeeded(force: Boolean = false) {
        if (stopped) return
        val (w, h) = targetSize()
        if (!force && w == width && h == height) return
        width = w
        height = h
        val old = reader
        val r = newReader(w, h)
        reader = r
        virtualDisplay?.resize(w, h, context.resources.displayMetrics.densityDpi)
        virtualDisplay?.surface = r.surface
        old?.close()
        padded?.recycle()
        padded = null
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ reader -> handleImage(reader) }, handler)
        return r
    }

    private fun handleImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            val pending = backlog()
            if (now - lastFrameAt < controller.frameIntervalMs() || controller.shouldDrop(pending)) {
                controller.onBacklog(pending)
                return
            }
            controller.onBacklog(pending)
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val paddedWidth = plane.rowStride / pixelStride
            var bmp = padded
            if (bmp == null || bmp.width != paddedWidth || bmp.height != image.height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded = bmp
            }
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            val source = if (paddedWidth != image.width) Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp
            val out = ByteArrayOutputStream(48 * 1024)
            source.compress(Bitmap.CompressFormat.JPEG, controller.quality, out)
            if (source !== bmp) source.recycle()
            val jpeg = out.toByteArray()
            lastJpeg = jpeg
            lastFrameAt = now
            onFrame(jpeg, image.width, image.height)
        } finally {
            image.close()
        }
    }

    fun stop() {
        if (stopped) return
        release()
    }

    private fun release() {
        stopped = true
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) { }
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        padded?.recycle()
        padded = null
        try { projection.stop() } catch (_: Exception) { }
        thread.quitSafely()
    }
}
