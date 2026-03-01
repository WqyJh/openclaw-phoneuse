package com.openclaw.phoneuse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class CameraResult(
    val base64: String,
    val width: Int,
    val height: Int,
    val format: String = "jpg",
    val facing: String
)

class CameraCapture(private val context: Context) {
    companion object {
        private const val TAG = "CameraCapture"
    }

    /**
     * List available cameras.
     */
    fun listCameras(): List<Map<String, String>> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.map { id ->
            val chars = manager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                else -> "external"
            }
            mapOf("id" to id, "facing" to facing)
        }
    }

    /**
     * Capture a photo from the specified camera.
     * @param facing "front" or "back"
     * @param maxWidth max image width (default 1600)
     * @param quality JPEG quality 0-100 (default 85)
     */
    suspend fun capture(
        facing: String = "back",
        maxWidth: Int = 1600,
        quality: Int = 85
    ): CameraResult? = suspendCancellableCoroutine { cont ->
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        // Find camera by facing direction
        val targetFacing = when (facing.lowercase()) {
            "front" -> CameraCharacteristics.LENS_FACING_FRONT
            "back" -> CameraCharacteristics.LENS_FACING_BACK
            else -> CameraCharacteristics.LENS_FACING_BACK
        }
        
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
        }
        
        if (cameraId == null) {
            Log.e(TAG, "No $facing camera found")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        
        // Pick best resolution up to maxWidth
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: run {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val targetSize = sizes
            .filter { it.width <= maxWidth }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: run {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
        
        val handlerThread = HandlerThread("CameraCapture").apply { start() }
        val handler = Handler(handlerThread.looper)
        
        val imageReader = ImageReader.newInstance(
            targetSize.width, targetSize.height, ImageFormat.JPEG, 1
        )
        
        var resumed = false
        
        fun cleanup() {
            try {
                imageReader.close()
                handlerThread.quitSafely()
            } catch (_: Exception) {}
        }
        
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                
                // Resize if needed
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val finalBitmap = if (bitmap.width > maxWidth) {
                    val ratio = maxWidth.toFloat() / bitmap.width
                    val newHeight = (bitmap.height * ratio).toInt()
                    Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                } else bitmap
                
                val out = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                
                val result = CameraResult(
                    base64 = base64,
                    width = finalBitmap.width,
                    height = finalBitmap.height,
                    facing = facing
                )
                finalBitmap.recycle()
                cleanup()
                if (!resumed) {
                    resumed = true
                    cont.resume(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image: ${e.message}")
                cleanup()
                if (!resumed) {
                    resumed = true
                    cont.resume(null)
                }
            }
        }, handler)
        
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    // Add a short delay for auto-exposure to settle
                                    handler.postDelayed({
                                        try {
                                            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                                    // Image will be delivered via ImageReader listener
                                                    handler.postDelayed({ camera.close() }, 500)
                                                }
                                                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                                                    Log.e(TAG, "Capture failed: ${failure.reason}")
                                                    camera.close()
                                                    cleanup()
                                                    if (!resumed) { resumed = true; cont.resume(null) }
                                                }
                                            }, handler)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Capture error: ${e.message}")
                                            camera.close()
                                            cleanup()
                                            if (!resumed) { resumed = true; cont.resume(null) }
                                        }
                                    }, 500) // 500ms for AE/AF to settle
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Camera session config failed")
                                    camera.close()
                                    cleanup()
                                    if (!resumed) { resumed = true; cont.resume(null) }
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating capture session: ${e.message}")
                        camera.close()
                        cleanup()
                        if (!resumed) { resumed = true; cont.resume(null) }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cleanup()
                    if (!resumed) { resumed = true; cont.resume(null) }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cleanup()
                    if (!resumed) { resumed = true; cont.resume(null) }
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied: ${e.message}")
            cleanup()
            if (!resumed) { resumed = true; cont.resume(null) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
            cleanup()
            if (!resumed) { resumed = true; cont.resume(null) }
        }
        
        // Timeout after 10 seconds
        handler.postDelayed({
            if (!resumed) {
                Log.e(TAG, "Camera capture timed out")
                resumed = true
                cleanup()
                cont.resume(null)
            }
        }, 10000)
    }
}
