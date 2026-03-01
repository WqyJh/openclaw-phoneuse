package com.openclaw.phoneuse

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Manages device wake state for background operation.
 *
 * Strategy:
 * - PARTIAL_WAKE_LOCK: keeps CPU running (screen off OK)
 * - WIFI_LOCK: keeps WiFi alive in deep sleep
 * - SCREEN_WAKE: temporary screen wake for screenshot/gesture commands
 *
 * The phone can sleep normally with screen off.
 * When a command arrives that needs the screen (screenshot, tap), 
 * we briefly wake the screen, execute, then let it sleep again.
 */
class KeepAliveManager(private val context: Context) {

    companion object {
        private const val TAG = "KeepAlive"
        private const val SCREEN_WAKE_TIMEOUT_MS = 10_000L  // 10 seconds
    }

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    /**
     * Acquire persistent locks for background operation.
     * Call when Foreground Service starts.
     */
    fun acquirePersistentLocks() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // CPU wake lock - keeps processor running, screen can be off
        if (cpuWakeLock == null) {
            cpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OpenClawPhoneUse::CpuWake"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "CPU wake lock acquired")
        }

        // WiFi lock - keeps WiFi alive during deep sleep
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null && wifiLock == null) {
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "OpenClawPhoneUse::WifiWake"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WiFi lock acquired")
        }
    }

    /**
     * Temporarily wake the screen for commands that need it.
     * Screen will auto-turn-off after timeout.
     */
    fun wakeScreenTemporarily() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // Release previous screen wake if any
        releaseScreenWake()
        
        @Suppress("DEPRECATION")
        screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "OpenClawPhoneUse::ScreenWake"
        ).apply {
            acquire(SCREEN_WAKE_TIMEOUT_MS)
        }
        Log.d(TAG, "Screen woken for ${SCREEN_WAKE_TIMEOUT_MS}ms")
    }

    /**
     * Release temporary screen wake.
     * Call after screenshot/gesture is done.
     */
    fun releaseScreenWake() {
        screenWakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Screen wake released")
            }
        }
        screenWakeLock = null
    }

    /**
     * Check if screen is currently on.
     */
    fun isScreenOn(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /**
     * Release all locks. Call when service stops.
     */
    fun releaseAll() {
        releaseScreenWake()
        
        cpuWakeLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "CPU wake lock released")
        }
        cpuWakeLock = null
        
        wifiLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "WiFi lock released")
        }
        wifiLock = null
    }
}
