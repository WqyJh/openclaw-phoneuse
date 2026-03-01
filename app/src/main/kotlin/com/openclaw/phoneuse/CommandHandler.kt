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

        /**
         * Save binary data to a file in cache dir, return the absolute path.
         * Files are organized: cache/phoneuse/{type}/filename
         */
        fun saveToFile(context: android.content.Context, data: ByteArray, type: String, ext: String): String {
            val dir = java.io.File(context.cacheDir, "phoneuse/$type")
            dir.mkdirs()
            val filename = "${type}_${System.currentTimeMillis()}.$ext"
            val file = java.io.File(dir, filename)
            file.writeBytes(data)
            return file.absolutePath
        }
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

        // Commands that need the screen on — wake it temporarily
        val needsScreen = command.startsWith("phoneUse.") || 
            command == "camera.snap" || command == "camera.clip" || command == "screen.record"
        if (needsScreen) {
            GatewayForegroundService.keepAlive?.wakeScreenTemporarily()
            // Small delay to let screen actually turn on
            if (GatewayForegroundService.keepAlive?.isScreenOn() != true) {
                kotlinx.coroutines.delay(500)
            }
        }

        return when (command) {
            // ========== Standard OpenClaw node commands ==========
            
            "camera.snap" -> {
                // Real camera capture via Camera2 API
                val facing = params.optString("facing", "back")
                val maxWidth = params.optInt("maxWidth", 1600)
                val quality = (params.optDouble("quality", 0.85) * 100).toInt().coerceIn(1, 100)
                
                val camera = CameraCapture(svc.applicationContext)
                val result = camera.capture(facing, maxWidth, quality)
                
                if (result != null) {
                    JSONObject()
                        .put("format", "jpg")
                        .put("base64", result.base64)
                        .put("width", result.width)
                        .put("height", result.height)
                        .put("facing", result.facing)
                } else {
                    errorResult("Camera capture failed. Check CAMERA permission is granted.")
                }
            }

            "camera.list" -> {
                val camera = CameraCapture(svc.applicationContext)
                val cameras = camera.listCameras()
                val arr = org.json.JSONArray()
                cameras.forEach { cam ->
                    arr.put(JSONObject()
                        .put("id", cam["id"])
                        .put("name", "${cam["facing"]?.replaceFirstChar { it.uppercase() }} Camera")
                        .put("facing", cam["facing"])
                    )
                }
                JSONObject().put("ok", true).put("cameras", arr)
            }

            "camera.clip" -> {
                // Short video clip via MediaProjection
                val durationMs = params.optLong("durationMs", 2000)
                val fps = params.optInt("fps", 15)
                val maxWidth = params.optInt("maxWidth", 720)

                if (!ScreenCaptureManager.hasPermission()) {
                    // Fallback to accessibility screenshot
                    return captureForGateway(svc, maxWidth, 60)
                }

                val recorder = ScreenRecorder(svc.applicationContext)
                val result = recorder.record(ScreenRecorder.RecordingParams(durationMs = durationMs, fps = fps, maxWidth = maxWidth))
                if (result == null) {
                    return captureForGateway(svc, maxWidth, 60)
                }

                val clipResponse = JSONObject()
                    .put("format", result.format)
                    .put("durationMs", result.durationMs)
                    .put("sizeBytes", result.sizeBytes)
                if (result.sizeBytes <= 10 * 1024 * 1024) {
                    try {
                        val bytes = java.io.File(result.filePath).readBytes()
                        clipResponse.put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    } catch (_: Exception) {}
                }
                clipResponse
            }

            "screen.record" -> {
                // Real MP4 screen recording via MediaProjection + MediaRecorder
                val durationMs = params.optLong("durationMs", 5000)
                val fps = params.optInt("fps", 15)
                val bitrate = params.optInt("bitrate", 2_000_000)
                val maxWidth = params.optInt("maxWidth", 720)
                val inlineThreshold = params.optInt("inlineThreshold", 10 * 1024 * 1024) // 10MB

                if (!ScreenCaptureManager.hasPermission()) {
                    return errorResult("MediaProjection not authorized. Open the app and tap 'Enable Screen Capture' first.")
                }

                val recorder = ScreenRecorder(svc.applicationContext)
                val result = recorder.record(ScreenRecorder.RecordingParams(
                    durationMs = durationMs,
                    fps = fps,
                    bitrate = bitrate,
                    maxWidth = maxWidth
                ))

                if (result == null) {
                    return errorResult("Screen recording failed. Check logcat for details.")
                }

                val response = JSONObject()
                    .put("format", result.format)
                    .put("durationMs", result.durationMs)
                    .put("width", result.width)
                    .put("height", result.height)
                    .put("sizeBytes", result.sizeBytes)
                    .put("path", result.filePath)

                // Include base64 for Gateway to write to server file
                if (result.sizeBytes <= inlineThreshold) {
                    try {
                        val bytes = java.io.File(result.filePath).readBytes()
                        response.put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read recording: ${e.message}")
                    }
                }
                response
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
                // Returns base64 — Gateway writes to server file via nodes tool
                captureForGateway(svc, maxWidth, quality)
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

            // ========== File Operations (General Purpose) ==========

            "file.read" -> {
                val path = params.optString("path", params.optString("id", ""))
                val offset = params.optLong("offset", 0)
                val size = params.optInt("size", 2 * 1024 * 1024) // 2MB chunks
                if (path.isEmpty()) return errorResult("Missing path")

                try {
                    val file = java.io.File(path)
                    if (!file.exists()) return errorResult("File not found: $path")
                    if (!file.canRead()) return errorResult("Permission denied: $path")
                    val total = file.length()
                    val actualOffset = offset.coerceIn(0, total)
                    val remaining = (total - actualOffset).toInt().coerceAtMost(size)
                    val buffer = ByteArray(remaining)
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(actualOffset)
                        raf.readFully(buffer)
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("base64", android.util.Base64.encodeToString(buffer, android.util.Base64.NO_WRAP))
                        .put("path", path)
                        .put("offset", actualOffset)
                        .put("size", remaining)
                        .put("total", total)
                        .put("done", actualOffset + remaining >= total)
                } catch (e: Exception) {
                    errorResult("file.read failed: ${e.message}")
                }
            }

            "file.write" -> {
                val path = params.optString("path", "")
                val base64 = params.optString("base64", "")
                val append = params.optBoolean("append", false)
                if (path.isEmpty()) return errorResult("Missing path")
                if (base64.isEmpty()) return errorResult("Missing base64 data")

                try {
                    val file = java.io.File(path)
                    file.parentFile?.mkdirs()
                    val data = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    if (append) {
                        file.appendBytes(data)
                    } else {
                        file.writeBytes(data)
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("path", path)
                        .put("size", file.length())
                        .put("written", data.size)
                } catch (e: Exception) {
                    errorResult("file.write failed: ${e.message}")
                }
            }

            "file.info" -> {
                val path = params.optString("path", "")
                if (path.isEmpty()) return errorResult("Missing path")
                try {
                    val file = java.io.File(path)
                    if (!file.exists()) return errorResult("Not found: $path")
                    JSONObject()
                        .put("ok", true)
                        .put("path", file.absolutePath)
                        .put("name", file.name)
                        .put("size", file.length())
                        .put("isDirectory", file.isDirectory)
                        .put("isFile", file.isFile)
                        .put("canRead", file.canRead())
                        .put("canWrite", file.canWrite())
                        .put("lastModified", file.lastModified())
                } catch (e: Exception) {
                    errorResult("file.info failed: ${e.message}")
                }
            }

            "file.list" -> {
                val path = params.optString("path", 
                    params.optString("dir", "/sdcard"))
                try {
                    val dir = java.io.File(path)
                    if (!dir.exists()) return errorResult("Not found: $path")
                    if (!dir.isDirectory) return errorResult("Not a directory: $path")
                    val entries = dir.listFiles()?.sortedWith(
                        compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name }
                    )?.map { f ->
                        JSONObject()
                            .put("name", f.name)
                            .put("path", f.absolutePath)
                            .put("size", if (f.isFile) f.length() else 0)
                            .put("isDirectory", f.isDirectory)
                            .put("lastModified", f.lastModified())
                    } ?: emptyList()
                    JSONObject()
                        .put("ok", true)
                        .put("path", dir.absolutePath)
                        .put("count", entries.size)
                        .put("entries", org.json.JSONArray(entries))
                } catch (e: java.lang.SecurityException) {
                    errorResult("Permission denied: $path. Grant 'All files access' in app settings.")
                } catch (e: Exception) {
                    errorResult("file.list failed: ${e.message}")
                }
            }

            "file.delete" -> {
                val path = params.optString("path", params.optString("id", ""))
                if (path.isEmpty()) return errorResult("Missing path")
                val file = java.io.File(path)
                val deleted = file.exists() && file.delete()
                successResult("file.delete", deleted, if (deleted) "Deleted: $path" else "Not found: $path")
            }

            // ========== System Info & Apps ==========

            "phoneUse.listApps" -> {
                val pm = svc.applicationContext.packageManager
                val includeSystem = params.optBoolean("includeSystem", false)
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
                val apps = pm.queryIntentActivities(intent, 0)
                    .filter { includeSystem || (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .sortedBy { it.loadLabel(pm).toString().lowercase() }
                    .map { ri ->
                        JSONObject()
                            .put("name", ri.loadLabel(pm).toString())
                            .put("package", ri.activityInfo.packageName)
                    }
                JSONObject()
                    .put("ok", true)
                    .put("count", apps.size)
                    .put("apps", org.json.JSONArray(apps))
            }

            "phoneUse.getForegroundApp" -> {
                val root = svc.rootInActiveWindow
                val pkg = root?.packageName?.toString() ?: "unknown"
                root?.recycle()
                JSONObject()
                    .put("ok", true)
                    .put("package", pkg)
            }

            "phoneUse.openUrl" -> {
                val url = params.optString("url", "")
                if (url.isEmpty()) return errorResult("Missing url")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    svc.applicationContext.startActivity(intent)
                    successResult("openUrl", true, "Opened: $url")
                } catch (e: Exception) {
                    errorResult("openUrl failed: ${e.message}")
                }
            }

            "phoneUse.startActivity" -> {
                val action = params.optString("action", "")
                val uri = params.optString("uri", "")
                val pkg = params.optString("package", "")
                val className = params.optString("class", "")
                try {
                    val intent = android.content.Intent().apply {
                        if (action.isNotEmpty()) setAction(action)
                        if (uri.isNotEmpty()) data = android.net.Uri.parse(uri)
                        if (pkg.isNotEmpty()) setPackage(pkg)
                        if (pkg.isNotEmpty() && className.isNotEmpty()) setClassName(pkg, className)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    svc.applicationContext.startActivity(intent)
                    successResult("startActivity", true, "Started: action=$action uri=$uri pkg=$pkg")
                } catch (e: Exception) {
                    errorResult("startActivity failed: ${e.message}")
                }
            }

            "phoneUse.clipboard" -> {
                val cm = svc.applicationContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val setText = params.optString("set", "")
                if (setText.isNotEmpty()) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("text", setText))
                    JSONObject().put("ok", true).put("action", "set").put("text", setText)
                } else {
                    val clip = cm.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
                    JSONObject().put("ok", true).put("action", "get").put("text", text)
                }
            }

            "phoneUse.getDeviceStatus" -> {
                val ctx = svc.applicationContext
                val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val isCharging = bm.isCharging
                
                val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val wifiEnabled = wm?.isWifiEnabled ?: false
                @Suppress("DEPRECATION")
                val wifiName = try { wm?.connectionInfo?.ssid?.replace("\"", "") ?: "" } catch (_: Exception) { "" }
                
                val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
                val freeGB = stat.availableBytes / (1024 * 1024 * 1024)
                val totalGB = stat.totalBytes / (1024 * 1024 * 1024)

                JSONObject()
                    .put("ok", true)
                    .put("battery", batteryLevel)
                    .put("isCharging", isCharging)
                    .put("wifiEnabled", wifiEnabled)
                    .put("wifiName", wifiName)
                    .put("storageFreeGB", freeGB)
                    .put("storageTotalGB", totalGB)
                    .put("screenOn", GatewayForegroundService.keepAlive?.isScreenOn() ?: false)
                    .put("model", android.os.Build.MODEL)
                    .put("androidVersion", android.os.Build.VERSION.RELEASE)
                    .put("apiLevel", android.os.Build.VERSION.SDK_INT)
            }

            "phoneUse.openAllApps" -> {
                // GLOBAL_ACTION_ALL_APPS = 14, API 31+
                val ok = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    svc.performGlobalAction(14)
                } else {
                    false
                }
                if (ok) {
                    successResult("openAllApps", true, "App drawer opened")
                } else {
                    errorResult("openAllApps requires Android 12+ (API 31)")
                }
            }

            "phoneUse.queryIntents" -> {
                val action = params.optString("action", "")
                val category = params.optString("category", "")
                val pkg = params.optString("package", "")
                val pm = svc.applicationContext.packageManager
                
                if (pkg.isNotEmpty()) {
                    // List exported activities for a package
                    try {
                        @Suppress("DEPRECATION")
                        val pkgInfo = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES)
                        val activities = pkgInfo.activities?.filter { it.exported }?.map { ai ->
                            JSONObject()
                                .put("name", ai.name)
                                .put("label", try { ai.loadLabel(pm).toString() } catch (_: Exception) { ai.name })
                                .put("exported", ai.exported)
                        } ?: emptyList()
                        JSONObject()
                            .put("ok", true)
                            .put("package", pkg)
                            .put("count", activities.size)
                            .put("activities", org.json.JSONArray(activities))
                    } catch (e: Exception) {
                        errorResult("Package not found: $pkg")
                    }
                } else if (action.isNotEmpty()) {
                    val intent = android.content.Intent(action)
                    if (category.isNotEmpty()) intent.addCategory(category)
                    val results = pm.queryIntentActivities(intent, 0).take(50).map { ri ->
                        JSONObject()
                            .put("package", ri.activityInfo.packageName)
                            .put("activity", ri.activityInfo.name)
                            .put("label", ri.loadLabel(pm).toString())
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("action", action)
                        .put("count", results.size)
                        .put("results", org.json.JSONArray(results))
                } else {
                    errorResult("Provide 'action' or 'package' parameter")
                }
            }

            // ========== Screen Lock ==========

            "phoneUse.unlock" -> {
                val pin = params.optString("pin", "")
                
                // Wake screen and hold it on for the entire unlock process
                GatewayForegroundService.keepAlive?.wakeScreenTemporarily()
                // Wait for screen to fully wake up
                var screenReady = false
                for (attempt in 1..10) {
                    kotlinx.coroutines.delay(300)
                    if (GatewayForegroundService.keepAlive?.isScreenOn() == true) {
                        screenReady = true
                        break
                    }
                    // Re-acquire wake lock if needed
                    GatewayForegroundService.keepAlive?.wakeScreenTemporarily()
                }
                if (!screenReady) {
                    errorResult("Failed to wake screen after 3 seconds")
                } else if (pin.isEmpty()) {
                    // No PIN — just dismiss lock screen (swipe/none lock)
                    val ok = svc.dismissLockScreen()
                    if (ok) {
                        kotlinx.coroutines.delay(500)
                        successResult("unlock", true, "Lock screen dismissed (no PIN)")
                    } else {
                        errorResult("Failed to dismiss lock screen. Requires Android 9+ (API 28).")
                    }
                } else {
                    // PIN unlock — swipe up first to show PIN pad
                    val displayMetrics = svc.resources.displayMetrics
                    val screenW = displayMetrics.widthPixels
                    val screenH = displayMetrics.heightPixels
                    svc.swipe(screenW / 2f, screenH * 3f / 4f, screenW / 2f, screenH / 4f, 300)
                    kotlinx.coroutines.delay(800)
                    
                    val ok = svc.unlockWithPin(pin)
                    if (ok) {
                        successResult("unlock", true, "Unlocked with PIN")
                    } else {
                        errorResult("PIN unlock failed. Check PIN or screen state.")
                    }
                }
            }

            "phoneUse.lockScreen" -> {
                val ok = svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                successResult("lockScreen", ok, "Screen locked")
            }

            "phoneUse.wakeScreen" -> {
                GatewayForegroundService.keepAlive?.wakeScreenTemporarily()
                kotlinx.coroutines.delay(300)
                val isOn = GatewayForegroundService.keepAlive?.isScreenOn() ?: false
                JSONObject()
                    .put("ok", true)
                    .put("command", "wakeScreen")
                    .put("screenOn", isOn)
                    .put("message", if (isOn) "Screen is on" else "Wake requested")
            }

            "phoneUse.isScreenOn" -> {
                val isOn = GatewayForegroundService.keepAlive?.isScreenOn() ?: false
                JSONObject()
                    .put("ok", true)
                    .put("command", "isScreenOn")
                    .put("screenOn", isOn)
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

    /**
     * Capture screenshot, save to file, return path (no base64 in response).
     * Gateway-compatible: also includes format, width, height for parseCameraSnapPayload.
     */
    private suspend fun captureForGateway(svc: PhoneUseService, maxWidth: Int = 720, quality: Int = 60): JSONObject {
        return suspendCancellableCoroutine { cont ->
            svc.takeScreenshotBase64(maxWidth, quality) { data ->
                if (data != null) {
                    val bytes = android.util.Base64.decode(data.base64, android.util.Base64.NO_WRAP)
                    val path = saveToFile(svc.applicationContext, bytes, "screenshot", "jpg")
                    // Include base64 for Gateway compatibility (parseCameraSnapPayload needs it)
                    cont.resume(JSONObject()
                        .put("format", "jpg")
                        .put("base64", data.base64)
                        .put("width", data.width)
                        .put("height", data.height)
                        .put("path", path)
                        .put("sizeBytes", bytes.size))
                } else {
                    cont.resume(errorResult("Screenshot failed. Ensure Accessibility Service is enabled."))
                }
            }
        }
    }

    /**
     * Screen recording: capture multiple frames via Accessibility API.
     * Returns base64-encoded MJPEG (concatenated JPEG frames).
     * Gateway parseScreenRecordPayload expects: {format, base64, durationMs?, fps?}
     */
    private suspend fun captureScreenRecord(
        svc: PhoneUseService, durationMs: Long, fps: Int, maxWidth: Int, quality: Int
    ): JSONObject {
        val frameInterval = (1000L / fps.coerceIn(1, 30))
        val frameCount = ((durationMs / frameInterval).toInt()).coerceIn(1, 150) // Max 150 frames
        val startTime = System.currentTimeMillis()

        // Capture frames sequentially
        val frames = mutableListOf<ByteArray>()
        for (i in 0 until frameCount) {
            val frameData = suspendCancellableCoroutine<PhoneUseService.ScreenshotData?> { cont ->
                svc.takeScreenshotBase64(maxWidth, quality) { data -> cont.resume(data) }
            }
            if (frameData != null) {
                frames.add(android.util.Base64.decode(frameData.base64, android.util.Base64.NO_WRAP))
            }
            // Wait for next frame timing
            val elapsed = System.currentTimeMillis() - startTime
            val nextFrameTime = (i + 1) * frameInterval
            val sleepMs = nextFrameTime - elapsed
            if (sleepMs > 0 && i < frameCount - 1) {
                kotlinx.coroutines.delay(sleepMs)
            }
        }

        if (frames.isEmpty()) {
            return errorResult("Screen recording failed: no frames captured")
        }

        val actualDuration = System.currentTimeMillis() - startTime

        // For single frame, return as JPEG
        if (frames.size == 1) {
            val base64 = android.util.Base64.encodeToString(frames[0], android.util.Base64.NO_WRAP)
            return JSONObject()
                .put("format", "jpg")
                .put("base64", base64)
                .put("durationMs", actualDuration)
                .put("fps", 1)
                .put("frameCount", 1)
        }

        // Multiple frames: concatenate as MJPEG (JPEG sequence with boundary markers)
        // Each frame is a complete JPEG, separated by boundary
        val boundary = "--frame--"
        val output = java.io.ByteArrayOutputStream()
        for ((idx, frame) in frames.withIndex()) {
            if (idx > 0) output.write(boundary.toByteArray())
            output.write(frame)
        }
        val base64 = android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        val totalSizeKB = output.size() / 1024

        Log.i(TAG, "Screen record: ${frames.size} frames, ${actualDuration}ms, ${totalSizeKB}KB")

        return JSONObject()
            .put("format", "mjpeg")
            .put("base64", base64)
            .put("durationMs", actualDuration)
            .put("fps", frames.size * 1000 / actualDuration.coerceAtLeast(1))
            .put("frameCount", frames.size)
            .put("sizeBytes", output.size())
    }

    /**
     * Capture screenshot, save to file, return path only (no base64).
     * For phoneUse.screenshot — avoids polluting agent context with image data.
     */
    private suspend fun captureToFile(svc: PhoneUseService, maxWidth: Int = 720, quality: Int = 60): JSONObject {
        return suspendCancellableCoroutine { cont ->
            svc.takeScreenshotBase64(maxWidth, quality) { data ->
                if (data != null) {
                    val bytes = android.util.Base64.decode(data.base64, android.util.Base64.NO_WRAP)
                    val path = saveToFile(svc.applicationContext, bytes, "screenshot", "jpg")
                    cont.resume(JSONObject()
                        .put("ok", true)
                        .put("path", path)
                        .put("format", "jpg")
                        .put("width", data.width)
                        .put("height", data.height)
                        .put("sizeBytes", bytes.size)
                        .put("message", "Screenshot saved: ${data.width}x${data.height} (${bytes.size / 1024}KB)"))
                } else {
                    cont.resume(errorResult("Screenshot failed. Ensure Accessibility Service is enabled."))
                }
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
