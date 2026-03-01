package com.openclaw.phoneuse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Manages MediaProjection-based screen capture.
 * More reliable than AccessibilityService.takeScreenshot() and works on more devices.
 *
 * Flow:
 * 1. MainActivity requests screen capture permission → stores resultCode + data
 * 2. ScreenCaptureManager uses that to create MediaProjection on demand
 * 3. Each capture: create VirtualDisplay → grab frame from ImageReader → tear down
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCapture"
        
        // Store permission result from MainActivity
        var permissionResultCode: Int = Activity.RESULT_CANCELED
            private set
        var permissionData: Intent? = null
            private set
        
        fun storePermission(resultCode: Int, data: Intent?) {
            permissionResultCode = resultCode
            permissionData = data
        }
        
        fun hasPermission(): Boolean = permissionResultCode == Activity.RESULT_OK && permissionData != null
    }

    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val handlerThread = HandlerThread("ScreenCapture").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /**
     * Capture the current screen and return as base64-encoded JPEG.
     * @param quality JPEG quality (1-100)
     * @param scale Scale factor (0.0-1.0) to reduce image size
     */
    fun captureBase64(quality: Int = 60, scale: Float = 0.5f, callback: (String?) -> Unit) {
        if (!hasPermission()) {
            Log.w(TAG, "No screen capture permission")
            callback(null)
            return
        }

        handler.post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)

                val width = (metrics.widthPixels * scale).toInt()
                val height = (metrics.heightPixels * scale).toInt()
                val density = metrics.densityDpi

                // Create fresh MediaProjection each time (reuse causes issues on some devices)
                val projection = projectionManager.getMediaProjection(
                    permissionResultCode, permissionData!!.clone() as Intent
                )

                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                
                val display = projection.createVirtualDisplay(
                    "OpenClawCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler
                )

                // Wait a frame then capture
                handler.postDelayed({
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            // Crop padding if any
                            val croppedBitmap = if (rowPadding > 0) {
                                Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                                    if (it !== bitmap) bitmap.recycle()
                                }
                            } else {
                                bitmap
                            }

                            val stream = ByteArrayOutputStream()
                            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            croppedBitmap.recycle()

                            // Cleanup
                            display.release()
                            reader.close()
                            projection.stop()

                            Log.i(TAG, "Screenshot captured: ${width}x${height}, ${stream.size()} bytes")
                            callback(base64)
                        } else {
                            display.release()
                            reader.close()
                            projection.stop()
                            Log.w(TAG, "No image acquired")
                            callback(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture failed: ${e.message}", e)
                        try { display.release() } catch (_: Exception) {}
                        try { reader.close() } catch (_: Exception) {}
                        try { projection.stop() } catch (_: Exception) {}
                        callback(null)
                    }
                }, 150) // 150ms delay for frame to render

            } catch (e: Exception) {
                Log.e(TAG, "Setup failed: ${e.message}", e)
                callback(null)
            }
        }
    }

    /**
     * Capture and return screen dimensions along with the screenshot.
     */
    fun captureWithInfo(quality: Int = 80, scale: Float = 1.0f, callback: (CaptureResult?) -> Unit) {
        if (!hasPermission()) {
            callback(null)
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        captureBase64(quality, scale) { base64 ->
            if (base64 != null) {
                callback(CaptureResult(
                    base64 = base64,
                    width = (metrics.widthPixels * scale).toInt(),
                    height = (metrics.heightPixels * scale).toInt(),
                    realWidth = metrics.widthPixels,
                    realHeight = metrics.heightPixels,
                    density = metrics.densityDpi,
                    scale = scale
                ))
            } else {
                callback(null)
            }
        }
    }

    fun release() {
        handlerThread.quitSafely()
    }

    data class CaptureResult(
        val base64: String,
        val width: Int,
        val height: Int,
        val realWidth: Int,
        val realHeight: Int,
        val density: Int,
        val scale: Float
    )
}
