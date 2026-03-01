package com.openclaw.phoneuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Core Accessibility Service for OpenClaw PhoneUse.
 * Handles all phone UI control commands.
 */
class PhoneUseService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneUseService"
        var instance: PhoneUseService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "PhoneUse Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to events proactively;
        // all actions are triggered by Gateway commands.
    }

    override fun onInterrupt() {
        Log.w(TAG, "PhoneUse Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "PhoneUse Accessibility Service destroyed")
    }

    // ========== Gesture Commands ==========

    /**
     * Tap at coordinates (x, y).
     */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGestureSync(gesture)
    }

    /**
     * Double tap at coordinates (x, y).
     */
    fun doubleTap(x: Float, y: Float): Boolean {
        val ok1 = tap(x, y)
        if (!ok1) return false
        Thread.sleep(80)
        return tap(x, y)
    }

    /**
     * Long tap at coordinates (x, y).
     */
    fun longTap(x: Float, y: Float, durationMs: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGestureSync(gesture)
    }

    /**
     * Swipe from (x1,y1) to (x2,y2).
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGestureSync(gesture)
    }

    /**
     * Pinch gesture (zoom in/out) centered at (cx, cy).
     * @param zoomIn true = pinch out (zoom in), false = pinch in (zoom out)
     * @param span distance in pixels for the pinch
     */
    fun pinch(cx: Float, cy: Float, zoomIn: Boolean, span: Float = 200f, durationMs: Long = 500): Boolean {
        val halfSpan = span / 2f

        val (startOffset, endOffset) = if (zoomIn) {
            // Pinch out: fingers move apart
            Pair(30f, halfSpan)
        } else {
            // Pinch in: fingers move together
            Pair(halfSpan, 30f)
        }

        val path1 = Path().apply {
            moveTo(cx - startOffset, cy)
            lineTo(cx - endOffset, cy)
        }
        val path2 = Path().apply {
            moveTo(cx + startOffset, cy)
            lineTo(cx + endOffset, cy)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, durationMs))
            .build()
        return dispatchGestureSync(gesture)
    }

    // ========== Text Input ==========

    /**
     * Find a node by text and click it.
     */
    fun findAndClick(text: String, timeoutMs: Long = 3000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = rootInActiveWindow ?: continue
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    if (node.isClickable) {
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node.recycle()
                        root.recycle()
                        return result
                    }
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            node.recycle()
                            root.recycle()
                            return result
                        }
                        val grandparent = parent.parent
                        parent.recycle()
                        parent = grandparent
                    }
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    node.recycle()
                    root.recycle()
                    return tap(
                        (bounds.left + bounds.right) / 2f,
                        (bounds.top + bounds.bottom) / 2f
                    )
                }
                node.recycle()
            }
            root.recycle()
            Thread.sleep(200)
        }
        return false
    }

    /**
     * Find a node by resource ID and click it.
     */
    fun findByIdAndClick(resourceId: String, timeoutMs: Long = 3000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = rootInActiveWindow ?: continue
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    if (node.isClickable) {
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node.recycle()
                        root.recycle()
                        return result
                    }
                    // Fall back to coordinate tap
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    node.recycle()
                    root.recycle()
                    return tap(
                        (bounds.left + bounds.right) / 2f,
                        (bounds.top + bounds.bottom) / 2f
                    )
                }
                node.recycle()
            }
            root.recycle()
            Thread.sleep(200)
        }
        return false
    }

    /**
     * Set text in the currently focused editable field, or find one and set it.
     */
    fun setText(text: String, fieldIndex: Int = 0): Boolean {
        val root = rootInActiveWindow ?: return false
        val editables = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, editables)

        if (editables.isEmpty()) {
            root.recycle()
            return false
        }

        val target = if (fieldIndex < editables.size) editables[fieldIndex] else editables.last()
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        editables.forEach { it.recycle() }
        root.recycle()
        return result
    }

    /**
     * Type text character by character using InputConnection simulation.
     * Works for apps that block ACTION_SET_TEXT.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            root.recycle()
            return false
        }

        // Use clipboard-based paste as fallback
        val clipboardManager = applicationContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("text", text)
        clipboardManager.setPrimaryClip(clip)
        
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        focusedNode.recycle()
        root.recycle()
        return result
    }

    /**
     * Wait for an element to appear on screen.
     */
    fun waitForElement(text: String, resourceId: String, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = rootInActiveWindow ?: continue
            
            val found = if (text.isNotEmpty()) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                val visible = nodes.any { it.isVisibleToUser }
                nodes.forEach { it.recycle() }
                visible
            } else if (resourceId.isNotEmpty()) {
                val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
                val visible = nodes.any { it.isVisibleToUser }
                nodes.forEach { it.recycle() }
                visible
            } else false

            root.recycle()
            if (found) return true
            Thread.sleep(200)
        }
        return false
    }

    /**
     * Send a key event (e.g., KEYCODE_ENTER = 66, KEYCODE_DEL = 67).
     */
    fun inputKeyEvent(keyCode: Int): Boolean {
        return try {
            val instrumentation = android.app.Instrumentation()
            instrumentation.sendKeyDownUpSync(keyCode)
            true
        } catch (e: Exception) {
            // Fallback: use soft keyboard action for ENTER
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                val root = rootInActiveWindow ?: return false
                val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                    }
                    // Try IME action
                    val ok = focusedNode.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                    focusedNode.recycle()
                    root.recycle()
                    ok
                } else {
                    root.recycle()
                    false
                }
            } else {
                Log.e(TAG, "Key event failed: ${e.message}")
                false
            }
        }
    }

    // ========== Screenshot ==========

    /**
     * Take a screenshot via Accessibility API and return as base64 JPEG.
     * Requires API 30+ (Android 11).
     */
    /**
     * Take screenshot, resize + compress, return as base64 JPEG.
     * @param maxWidth Max width in pixels (height scales proportionally). Default 720.
     * @param quality JPEG quality 1-100. Default 60.
     */
    fun takeScreenshotBase64(maxWidth: Int = 720, quality: Int = 60, callback: (ScreenshotData?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        var bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        screenshot.hardwareBuffer.close()
                        if (bitmap == null) { callback(null); return }

                        val origW = bitmap.width
                        val origH = bitmap.height

                        // Resize if wider than maxWidth
                        if (origW > maxWidth) {
                            val scale = maxWidth.toFloat() / origW
                            val newH = (origH * scale).toInt()
                            val resized = Bitmap.createScaledBitmap(bitmap, maxWidth, newH, true)
                            if (resized !== bitmap) bitmap.recycle()
                            bitmap = resized
                        }

                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                        val bytes = stream.toByteArray()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val w = bitmap.width
                        val h = bitmap.height
                        bitmap.recycle()

                        Log.i(TAG, "Screenshot: ${origW}x${origH} → ${w}x${h}, ${bytes.size / 1024}KB")
                        callback(ScreenshotData(base64, w, h, origW, origH, bytes.size))
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot processing failed: ${e.message}")
                        callback(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    callback(null)
                }
            }
        )
    }

    data class ScreenshotData(
        val base64: String,
        val width: Int,
        val height: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val sizeBytes: Int
    )

    // ========== UI Tree & Info ==========

    /**
     * Get the current UI tree as JSON.
     */
    fun getUITree(interactiveOnly: Boolean = false): JSONObject {
        val root = rootInActiveWindow
        return if (interactiveOnly) {
            JSONObject()
                .put("package", root?.packageName?.toString() ?: "unknown")
                .put("elements", UITreeSerializer.serializeInteractiveElements(root))
                .put("timestamp", System.currentTimeMillis())
        } else {
            UITreeSerializer.serialize(root)
        }
    }

    /**
     * Get screen dimensions and device info.
     */
    fun getScreenInfo(): JSONObject {
        val metrics = resources.displayMetrics
        return JSONObject()
            .put("widthPx", metrics.widthPixels)
            .put("heightPx", metrics.heightPixels)
            .put("density", metrics.density.toDouble())
            .put("densityDpi", metrics.densityDpi)
            .put("scaledDensity", metrics.scaledDensity.toDouble())
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("android", Build.VERSION.RELEASE)
            .put("hasScreenCapture", ScreenCaptureManager.hasPermission())
    }

    // ========== Navigation ==========

    /**
     * Launch an app by package name or display name.
     */
    fun launchApp(nameOrPackage: String): Boolean {
        return try {
            val pm = applicationContext.packageManager
            var intent = pm.getLaunchIntentForPackage(nameOrPackage)

            if (intent == null) {
                val packages = pm.getInstalledApplications(0)
                for (appInfo in packages) {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    if (label.equals(nameOrPackage, ignoreCase = true) ||
                        label.contains(nameOrPackage, ignoreCase = true)) {
                        intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                        break
                    }
                }
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch app failed: ${e.message}")
            false
        }
    }

    // ========== Unlock ==========

    /**
     * Dismiss the lock screen. Works directly for swipe/none locks.
     * For PIN/password, brings up the input screen.
     * Requires API 28+ (Android 9).
     */
    fun dismissLockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // GLOBAL_ACTION_DISMISS_LOCK_SCREEN = 8, added in API 28
            performGlobalAction(8)
        } else {
            Log.w(TAG, "dismissLockScreen requires API 28+")
            false
        }
    }

    /**
     * Unlock with PIN by dismissing keyguard then tapping digits.
     * @return true if all steps completed
     */
    suspend fun unlockWithPin(pin: String): Boolean {
        // Step 1: Dismiss lock screen (shows PIN input for secured locks)
        dismissLockScreen()
        kotlinx.coroutines.delay(500)

        // Check if we're already unlocked (swipe/none lock)
        val root1 = rootInActiveWindow
        val pkg1 = root1?.packageName?.toString() ?: ""
        root1?.recycle()
        if (pkg1 != "com.android.systemui") {
            Log.i(TAG, "Already unlocked (no PIN needed)")
            return true
        }

        // Step 2: Try to find and tap PIN digits
        for (digit in pin) {
            val digitStr = digit.toString()
            // Try finding the digit button by text
            val found = findAndClick(digitStr, 1500)
            if (!found) {
                Log.w(TAG, "Could not find digit button: $digitStr")
                return false
            }
            kotlinx.coroutines.delay(100)
        }

        // Step 3: Try pressing Enter/OK to confirm
        kotlinx.coroutines.delay(200)
        // Many PIN screens auto-confirm after all digits, but try Enter anyway
        val enterFound = findAndClick("OK", 500) ||
            findAndClick("确认", 500) ||
            findAndClick("Enter", 500)
        // Some PINs (4-6 digit) auto-unlock, so not finding Enter is OK

        kotlinx.coroutines.delay(500)

        // Verify unlock
        val root2 = rootInActiveWindow
        val pkg2 = root2?.packageName?.toString() ?: ""
        root2?.recycle()
        val unlocked = pkg2 != "com.android.systemui"
        Log.i(TAG, "Unlock result: $unlocked (foreground: $pkg2)")
        return unlocked
    }

    // ========== Scroll ==========

    fun scrollDown(): Boolean {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        return swipe(centerX, metrics.heightPixels * 0.7f, centerX, metrics.heightPixels * 0.3f, 300)
    }

    fun scrollUp(): Boolean {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        return swipe(centerX, metrics.heightPixels * 0.3f, centerX, metrics.heightPixels * 0.7f, 300)
    }

    fun scrollLeft(): Boolean {
        val metrics = resources.displayMetrics
        val centerY = metrics.heightPixels / 2f
        return swipe(metrics.widthPixels * 0.8f, centerY, metrics.widthPixels * 0.2f, centerY, 300)
    }

    fun scrollRight(): Boolean {
        val metrics = resources.displayMetrics
        val centerY = metrics.heightPixels / 2f
        return swipe(metrics.widthPixels * 0.2f, centerY, metrics.widthPixels * 0.8f, centerY, 300)
    }

    // ========== Helpers ==========

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result = false
                latch.countDown()
            }
        }, null)

        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable && node.isVisibleToUser) {
            @Suppress("DEPRECATION")
            result.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableNodes(child, result)
            child.recycle()
        }
    }
}
