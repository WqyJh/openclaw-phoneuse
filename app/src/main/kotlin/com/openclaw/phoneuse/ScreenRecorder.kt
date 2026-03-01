package com.openclaw.phoneuse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Screen recorder using MediaProjection + MediaRecorder for real H.264 MP4 recording.
 *
 * Uses the same permission grant stored by ScreenCaptureManager.
 *
 * Usage:
 * ```
 * val recorder = ScreenRecorder(context)
 * val result = recorder.record(RecordingParams(durationMs = 5000))
 * // result is either RecordingResult on success, or null on error
 * recorder.release()
 * ```
 *
 * Thread-safety: only one recording at a time; concurrent calls return null.
 */
class ScreenRecorder(private val context: Context) {

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val RECORDINGS_DIR = "recordings"
    }

    data class RecordingParams(
        val durationMs: Long = 5000L,
        val fps: Int = 15,
        val bitrate: Int = 2_000_000,
        val maxWidth: Int = 720,
        val includeAudio: Boolean = false
    )

    data class RecordingResult(
        val filePath: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val format: String = "mp4"
    )

    private val recording = AtomicBoolean(false)

    private val handlerThread = HandlerThread("ScreenRecorder").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * Record the screen for the specified duration.
     *
     * Suspends until recording is complete (or an error occurs).
     * Returns [RecordingResult] on success, null on failure.
     * Only one recording can be active at a time; concurrent calls return null immediately.
     */
    suspend fun record(params: RecordingParams = RecordingParams()): RecordingResult? {
        // Thread-safety: reject concurrent recordings
        if (!recording.compareAndSet(false, true)) {
            Log.w(TAG, "Recording already in progress, rejecting concurrent request")
            return null
        }

        return try {
            recordInternal(params)
        } finally {
            recording.set(false)
        }
    }

    private suspend fun recordInternal(params: RecordingParams): RecordingResult? {
        // Check permission
        if (!ScreenCaptureManager.hasPermission()) {
            Log.e(TAG, "No screen capture permission available")
            return null
        }

        // Get screen metrics
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // Calculate scaled dimensions (maintain aspect ratio, fit within maxWidth)
        val (videoWidth, videoHeight) = calculateDimensions(
            metrics.widthPixels, metrics.heightPixels, params.maxWidth
        )
        val density = metrics.densityDpi

        // Prepare output file
        val outputFile = createOutputFile() ?: run {
            Log.e(TAG, "Failed to create output file")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            handler.post {
                var mediaProjection: MediaProjection? = null
                var mediaRecorder: MediaRecorder? = null
                var virtualDisplay: VirtualDisplay? = null
                var stopped = false

                fun cleanup() {
                    if (stopped) return
                    stopped = true
                    try { virtualDisplay?.release() } catch (e: Exception) { Log.w(TAG, "VirtualDisplay release: ${e.message}") }
                    try { mediaRecorder?.stop() } catch (e: Exception) { Log.w(TAG, "MediaRecorder stop: ${e.message}") }
                    try { mediaRecorder?.reset() } catch (e: Exception) { Log.w(TAG, "MediaRecorder reset: ${e.message}") }
                    try { mediaRecorder?.release() } catch (e: Exception) { Log.w(TAG, "MediaRecorder release: ${e.message}") }
                    try { mediaProjection?.stop() } catch (e: Exception) { Log.w(TAG, "MediaProjection stop: ${e.message}") }
                    virtualDisplay = null
                    mediaRecorder = null
                    mediaProjection = null
                }

                try {
                    // Create MediaProjection (fresh each time, clone intent to avoid reuse issues)
                    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(
                        ScreenCaptureManager.permissionResultCode,
                        ScreenCaptureManager.permissionData!!.clone() as Intent
                    )

                    // Register projection callback for unexpected stops
                    mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped unexpectedly")
                            if (!stopped) {
                                cleanup()
                                if (continuation.isActive) {
                                    // Check if we got any data
                                    val result = buildResult(outputFile, params.durationMs, videoWidth, videoHeight)
                                    continuation.resume(result)
                                }
                            }
                        }
                    }, handler)

                    // Configure MediaRecorder
                    @Suppress("DEPRECATION")
                    mediaRecorder = MediaRecorder().apply {
                        // Video source from Surface (MediaProjection will render to it)
                        setVideoSource(MediaRecorder.VideoSource.SURFACE)

                        if (params.includeAudio) {
                            try {
                                setAudioSource(MediaRecorder.AudioSource.MIC)
                            } catch (e: Exception) {
                                Log.w(TAG, "Audio source not available, recording video only: ${e.message}")
                            }
                        }

                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                        setVideoSize(videoWidth, videoHeight)
                        setVideoFrameRate(params.fps)
                        setVideoEncodingBitRate(params.bitrate)

                        if (params.includeAudio) {
                            try {
                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                setAudioEncodingBitRate(128_000)
                                setAudioSamplingRate(44100)
                            } catch (e: Exception) {
                                Log.w(TAG, "Audio encoder setup failed: ${e.message}")
                            }
                        }

                        setOutputFile(outputFile.absolutePath)

                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }

                        prepare()
                    }

                    // Create VirtualDisplay that renders to MediaRecorder's surface
                    virtualDisplay = mediaProjection!!.createVirtualDisplay(
                        "OpenClawRecorder",
                        videoWidth,
                        videoHeight,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mediaRecorder!!.surface,
                        null,
                        handler
                    )

                    // Start recording
                    mediaRecorder!!.start()
                    Log.i(TAG, "Recording started: ${videoWidth}x${videoHeight} @ ${params.fps}fps, " +
                            "bitrate=${params.bitrate}, duration=${params.durationMs}ms, file=${outputFile.absolutePath}")

                    // Schedule auto-stop after duration
                    handler.postDelayed({
                        if (!stopped) {
                            Log.i(TAG, "Recording duration reached, stopping")
                            cleanup()
                            if (continuation.isActive) {
                                val result = buildResult(outputFile, params.durationMs, videoWidth, videoHeight)
                                Log.i(TAG, "Recording complete: ${result?.filePath ?: "failed"}, " +
                                        "${result?.sizeBytes ?: 0} bytes")
                                continuation.resume(result)
                            }
                        }
                    }, params.durationMs)

                } catch (e: Exception) {
                    Log.e(TAG, "Recording setup failed: ${e.message}", e)
                    cleanup()
                    // Clean up the empty file
                    try { outputFile.delete() } catch (_: Exception) {}
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }

                // Handle coroutine cancellation
                continuation.invokeOnCancellation {
                    Log.i(TAG, "Recording cancelled")
                    handler.post {
                        cleanup()
                        try { outputFile.delete() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * Force-stop a recording in progress (if any).
     */
    fun forceStop() {
        if (recording.get()) {
            Log.i(TAG, "Force-stopping recording")
            // The cleanup will happen via the handler thread
            // We just set the flag to prevent new recordings
            recording.set(false)
        }
    }

    /**
     * Release resources. Call when done with the recorder.
     */
    fun release() {
        handlerThread.quitSafely()
    }

    /**
     * Calculate video dimensions that fit within maxWidth while maintaining aspect ratio.
     * Ensures both dimensions are even (required by H.264 encoder).
     */
    private fun calculateDimensions(screenWidth: Int, screenHeight: Int, maxWidth: Int): Pair<Int, Int> {
        val scale = if (screenWidth > maxWidth) {
            maxWidth.toFloat() / screenWidth.toFloat()
        } else {
            1.0f
        }

        // H.264 requires even dimensions
        var w = (screenWidth * scale).toInt()
        var h = (screenHeight * scale).toInt()
        w = w and 0x7FFFFFFE  // round down to even
        h = h and 0x7FFFFFFE  // round down to even

        return Pair(w.coerceAtLeast(2), h.coerceAtLeast(2))
    }

    /**
     * Create the output file in cache/recordings directory.
     */
    private fun createOutputFile(): File? {
        return try {
            val dir = File(context.cacheDir, RECORDINGS_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create recordings directory: ${dir.absolutePath}")
                return null
            }
            val timestamp = System.currentTimeMillis()
            File(dir, "recording_${timestamp}.mp4")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create output file: ${e.message}", e)
            null
        }
    }

    /**
     * Build the recording result from the output file.
     * Returns null if the file doesn't exist or is empty.
     */
    private fun buildResult(
        file: File,
        requestedDurationMs: Long,
        width: Int,
        height: Int
    ): RecordingResult? {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Recording file missing or empty: ${file.absolutePath}")
            try { file.delete() } catch (_: Exception) {}
            return null
        }

        return RecordingResult(
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            durationMs = requestedDurationMs,
            width = width,
            height = height,
            format = "mp4"
        )
    }
}
