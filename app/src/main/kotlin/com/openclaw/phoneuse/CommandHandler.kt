package com.openclaw.phoneuse

import android.accessibilityservice.AccessibilityService
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Dispatches Gateway node.invoke commands to PhoneUseService methods.
 * Each command returns a JSONObject result.
 */
class CommandHandler {

    companion object {
        private const val TAG = "CommandHandler"
    }

    private val service: PhoneUseService?
        get() = PhoneUseService.instance

    /**
     * Execute a phoneUse command and return the result.
     * Called from GatewayClient on IO thread.
     */
    suspend fun execute(command: String, params: JSONObject): JSONObject {
        val svc = service
            ?: return errorResult("Accessibility service not running. Enable it in Settings → Accessibility → OpenClaw PhoneUse Agent.")

        Log.i(TAG, "Executing: $command with params: $params")

        return when (command) {
            // ========== Standard OpenClaw node commands ==========
            
            "camera.snap" -> {
                // Standard screenshot - compressed for transmission
                val maxWidth = params.optInt("maxWidth", 720)
                val quality = params.optInt("quality", 60)
                captureAccessibility(svc, maxWidth, quality)
            }

            "camera.list" -> {
                // Android phones: front + back cameras
                JSONObject()
                    .put("ok", true)
                    .put("cameras", org.json.JSONArray()
                        .put(JSONObject().put("id", "screen").put("name", "Screen Capture").put("facing", "front"))
                    )
                    .put("message", "Screen capture available (use camera.snap)")
            }

            "camera.clip" -> {
                // Short video clip - return screenshot for now
                captureAccessibility(svc, 720, 60)
            }

            "screen.record" -> {
                // Return compressed screenshot (full video recording TODO)
                captureAccessibility(svc, 720, 60)
            }

            "location.get" -> {
                // Get device location
                try {
                    val locationManager = svc.getSystemService(android.location.LocationManager::class.java)
                    @Suppress("DEPRECATION")
                    val location = locationManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        ?: locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    if (location != null) {
                        JSONObject()
                            .put("ok", true)
                            .put("latitude", location.latitude)
                            .put("longitude", location.longitude)
                            .put("accuracy", location.accuracy.toDouble())
                            .put("altitude", location.altitude)
                            .put("timestamp", location.time)
                            .put("provider", location.provider)
                    } else {
                        errorResult("Location not available. Ensure location services are enabled.")
                    }
                } catch (e: SecurityException) {
                    errorResult("Location permission not granted.")
                } catch (e: Exception) {
                    errorResult("Location error: ${e.message}")
                }
            }

            "notifications.list" -> {
                // List active notifications (requires notification listener permission)
                JSONObject()
                    .put("ok", true)
                    .put("notifications", org.json.JSONArray())
                    .put("message", "Notification listing requires NotificationListenerService (not yet implemented)")
            }

            "system.run" -> {
                // Limited shell execution on Android
                val cmd = params.optString("command", "")
                if (cmd.isEmpty()) return errorResult("Missing command parameter")
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val stdout = process.inputStream.bufferedReader().readText()
                    val stderr = process.errorStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    JSONObject()
                        .put("ok", exitCode == 0)
                        .put("command", "system.run")
                        .put("stdout", stdout)
                        .put("stderr", stderr)
                        .put("exitCode", exitCode)
                        .put("success", exitCode == 0)
                } catch (e: Exception) {
                    errorResult("system.run failed: ${e.message}")
                }
            }

            "system.notify" -> {
                val title = params.optString("title", "OpenClaw")
                val body = params.optString("body", params.optString("message", ""))
                if (body.isEmpty()) return errorResult("Missing body/message parameter")
                // Show Android notification
                try {
                    val nm = svc.getSystemService(android.app.NotificationManager::class.java)
                    val channelId = "openclaw_notify"
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val channel = android.app.NotificationChannel(
                            channelId, "OpenClaw Notifications",
                            android.app.NotificationManager.IMPORTANCE_HIGH
                        )
                        nm.createNotificationChannel(channel)
                    }
                    val notification = androidx.core.app.NotificationCompat.Builder(svc, channelId)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(System.currentTimeMillis().toInt(), notification)
                    successResult("system.notify", true, "Notification shown: $title")
                } catch (e: Exception) {
                    errorResult("system.notify failed: ${e.message}")
                }
            }

            // ========== PhoneUse-specific commands ==========

            "phoneUse.tap" -> {
                val x = params.optDouble("x", -1.0).toFloat()
                val y = params.optDouble("y", -1.0).toFloat()
                if (x < 0 || y < 0) return errorResult("Missing x or y coordinates")
                val ok = svc.tap(x, y)
                successResult("tap", ok, "Tapped at ($x, $y)")
            }

            "phoneUse.doubleTap" -> {
                val x = params.optDouble("x", -1.0).toFloat()
                val y = params.optDouble("y", -1.0).toFloat()
                if (x < 0 || y < 0) return errorResult("Missing x or y coordinates")
                val ok = svc.doubleTap(x, y)
                successResult("doubleTap", ok, "Double tapped at ($x, $y)")
            }

            "phoneUse.longTap" -> {
                val x = params.optDouble("x", -1.0).toFloat()
                val y = params.optDouble("y", -1.0).toFloat()
                val duration = params.optLong("duration", 1000)
                if (x < 0 || y < 0) return errorResult("Missing x or y coordinates")
                val ok = svc.longTap(x, y, duration)
                successResult("longTap", ok, "Long tapped at ($x, $y) for ${duration}ms")
            }

            "phoneUse.swipe" -> {
                val x1 = params.optDouble("x1", -1.0).toFloat()
                val y1 = params.optDouble("y1", -1.0).toFloat()
                val x2 = params.optDouble("x2", -1.0).toFloat()
                val y2 = params.optDouble("y2", -1.0).toFloat()
                val duration = params.optLong("duration", 500)
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                    return errorResult("Missing swipe coordinates (x1, y1, x2, y2)")
                }
                val ok = svc.swipe(x1, y1, x2, y2, duration)
                successResult("swipe", ok, "Swiped from ($x1,$y1) to ($x2,$y2)")
            }

            "phoneUse.pinch" -> {
                val centerX = params.optDouble("x", -1.0).toFloat()
                val centerY = params.optDouble("y", -1.0).toFloat()
                val zoomIn = params.optBoolean("zoomIn", true)
                val span = params.optDouble("span", 200.0).toFloat()
                val duration = params.optLong("duration", 500)
                if (centerX < 0 || centerY < 0) return errorResult("Missing x or y coordinates")
                val ok = svc.pinch(centerX, centerY, zoomIn, span, duration)
                successResult("pinch", ok, "Pinch ${if (zoomIn) "in" else "out"} at ($centerX, $centerY)")
            }

            "phoneUse.setText" -> {
                val text = params.optString("text", "")
                val index = params.optInt("fieldIndex", 0)
                if (text.isEmpty()) return errorResult("Missing text parameter")
                val ok = svc.setText(text, index)
                successResult("setText", ok, "Set text: '$text'")
            }

            "phoneUse.typeText" -> {
                val text = params.optString("text", "")
                if (text.isEmpty()) return errorResult("Missing text parameter")
                val ok = svc.typeText(text)
                successResult("typeText", ok, "Typed text: '$text'")
            }

            "phoneUse.findAndClick" -> {
                val text = params.optString("text", "")
                val resourceId = params.optString("resourceId", "")
                val timeout = params.optLong("timeout", 3000)
                
                val ok = when {
                    text.isNotEmpty() -> svc.findAndClick(text, timeout)
                    resourceId.isNotEmpty() -> svc.findByIdAndClick(resourceId, timeout)
                    else -> return errorResult("Missing text or resourceId parameter")
                }
                successResult("findAndClick", ok, 
                    if (text.isNotEmpty()) "Find and click: '$text'" 
                    else "Find and click ID: '$resourceId'")
            }

            "phoneUse.screenshot" -> {
                val quality = params.optInt("quality", 60)
                val maxWidth = params.optInt("maxWidth", 720)
                // Always use Accessibility API - it's the most reliable path
                // MediaProjection has too many issues (permission loss, process restart, ROM quirks)
                captureAccessibility(svc, maxWidth, quality)
            }

            "phoneUse.requestScreenCapture" -> {
                // Not needed - screenshots use Accessibility API (canTakeScreenshot=true)
                // MediaProjection is not required
                JSONObject()
                    .put("ok", true)
                    .put("command", "requestScreenCapture")
                    .put("message", "Screenshots use Accessibility API (no MediaProjection needed). Use phoneUse.screenshot or camera.snap directly.")
                    .put("accessibilityEnabled", PhoneUseService.instance != null)
            }

            "phoneUse.getUITree" -> {
                val interactiveOnly = params.optBoolean("interactiveOnly", false)
                val tree = svc.getUITree(interactiveOnly)
                JSONObject()
                    .put("ok", true)
                    .put("command", "getUITree")
                    .put("data", tree)
            }

            "phoneUse.getScreenInfo" -> {
                val info = svc.getScreenInfo()
                JSONObject()
                    .put("ok", true)
                    .put("command", "getScreenInfo")
                    .put("data", info)
            }

            "phoneUse.launch" -> {
                val appName = params.optString("app", "")
                val packageName = params.optString("package", "")
                val target = appName.ifEmpty { packageName }
                if (target.isEmpty()) return errorResult("Missing app name or package")
                val ok = svc.launchApp(target)
                successResult("launch", ok, "Launch app: '$target'")
            }

            "phoneUse.back" -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                successResult("back", ok, "Back button pressed")
            }

            "phoneUse.home" -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                successResult("home", ok, "Home button pressed")
            }

            "phoneUse.recents" -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                successResult("recents", ok, "Recent apps opened")
            }

            "phoneUse.openNotifications" -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                successResult("openNotifications", ok, "Notification shade opened")
            }

            "phoneUse.openQuickSettings" -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                successResult("openQuickSettings", ok, "Quick settings opened")
            }

            "phoneUse.scrollDown" -> {
                val ok = svc.scrollDown()
                successResult("scrollDown", ok, "Scrolled down")
            }

            "phoneUse.scrollUp" -> {
                val ok = svc.scrollUp()
                successResult("scrollUp", ok, "Scrolled up")
            }

            "phoneUse.scrollLeft" -> {
                val ok = svc.scrollLeft()
                successResult("scrollLeft", ok, "Scrolled left")
            }

            "phoneUse.scrollRight" -> {
                val ok = svc.scrollRight()
                successResult("scrollRight", ok, "Scrolled right")
            }

            "phoneUse.waitForElement" -> {
                val text = params.optString("text", "")
                val resourceId = params.optString("resourceId", "")
                val timeout = params.optLong("timeout", 5000)
                if (text.isEmpty() && resourceId.isEmpty()) {
                    return errorResult("Missing text or resourceId parameter")
                }
                val found = svc.waitForElement(text, resourceId, timeout)
                JSONObject()
                    .put("ok", found)
                    .put("command", "waitForElement")
                    .put("found", found)
                    .put("message", if (found) "Element found" else "Element not found within ${timeout}ms")
            }

            "phoneUse.inputKey" -> {
                val keyCode = params.optInt("keyCode", -1)
                if (keyCode < 0) return errorResult("Missing keyCode parameter")
                val ok = svc.inputKeyEvent(keyCode)
                successResult("inputKey", ok, "Key event $keyCode sent")
            }

            else -> errorResult("Unknown command: $command")
        }
    }

    private suspend fun captureMediaProjection(svc: PhoneUseService, quality: Int, scale: Float): JSONObject {
        val capture = ScreenCaptureManager(svc.applicationContext)
        return suspendCancellableCoroutine { cont ->
            capture.captureWithInfo(quality, scale) { result ->
                if (result != null) {
                    cont.resume(JSONObject()
                        .put("ok", true)
                        .put("command", "screenshot")
                        .put("method", "mediaprojection")
                        .put("format", "jpeg")
                        .put("width", result.width)
                        .put("height", result.height)
                        .put("realWidth", result.realWidth)
                        .put("realHeight", result.realHeight)
                        .put("scale", result.scale.toDouble())
                        .put("base64", result.base64)
                        .put("message", "Screenshot captured via MediaProjection (${result.width}x${result.height})"))
                } else {
                    cont.resume(errorResult("MediaProjection screenshot failed. Run phoneUse.requestScreenCapture first."))
                }
                capture.release()
            }
        }
    }

    private suspend fun captureAccessibility(svc: PhoneUseService, maxWidth: Int = 720, quality: Int = 60): JSONObject {
        return suspendCancellableCoroutine { cont ->
            svc.takeScreenshotBase64(maxWidth, quality) { data ->
                if (data != null) {
                    cont.resume(JSONObject()
                        .put("ok", true)
                        .put("command", "screenshot")
                        .put("method", "accessibility")
                        .put("format", "jpeg")
                        .put("width", data.width)
                        .put("height", data.height)
                        .put("originalWidth", data.originalWidth)
                        .put("originalHeight", data.originalHeight)
                        .put("sizeBytes", data.sizeBytes)
                        .put("base64", data.base64)
                        .put("message", "Screenshot ${data.originalWidth}x${data.originalHeight} → ${data.width}x${data.height} (${data.sizeBytes / 1024}KB)"))
                } else {
                    cont.resume(errorResult("Accessibility screenshot failed. Requires Android 11+ and canTakeScreenshot=true."))
                }
            }
        }
    }

    private fun successResult(command: String, ok: Boolean, message: String): JSONObject {
        return JSONObject()
            .put("ok", ok)
            .put("command", command)
            .put("message", if (ok) message else "FAILED: $message")
    }

    private fun errorResult(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
    }
}
