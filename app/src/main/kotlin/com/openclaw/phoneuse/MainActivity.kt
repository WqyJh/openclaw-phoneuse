package com.openclaw.phoneuse

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openclaw.phoneuse.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("gateway_config", MODE_PRIVATE) }
    private val logLines = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 9002
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.openclaw.phoneuse.STATUS" -> {
                    val status = intent.getStringExtra("status") ?: return
                    runOnUiThread { updateConnectionStatus(status) }
                }
                "com.openclaw.phoneuse.COMMAND" -> {
                    val command = intent.getStringExtra("command") ?: return
                    val state = intent.getStringExtra("state") ?: return
                    runOnUiThread { appendLog("$command → $state") }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore saved config
        binding.urlInput.setText(prefs.getString("url", "ws://192.168.1.100:18789"))
        binding.tokenInput.setText(prefs.getString("token", ""))

        // Connect button
        binding.connectButton.setOnClickListener {
            val urlText = binding.urlInput.text.toString().trim()
            val token = binding.tokenInput.text.toString().trim()

            if (urlText.isEmpty()) {
                Toast.makeText(this, "Please enter Gateway URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "⚠️ Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val params = ConnectionParams.fromUrl(urlText, token)
            if (params == null) {
                Toast.makeText(this, "Invalid URL format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("url", urlText)
                .putString("token", token)
                .apply()

            GatewayForegroundService.start(this, params)
            
            binding.connectButton.isEnabled = false
            binding.disconnectButton.isEnabled = true
            appendLog("Connecting to ${params.displayUrl}…")
        }

        // Disconnect button
        binding.disconnectButton.setOnClickListener {
            GatewayForegroundService.stop(this)
            binding.connectButton.isEnabled = true
            binding.disconnectButton.isEnabled = false
            binding.statusText.text = getString(R.string.status_disconnected)
            appendLog("Disconnected")
        }

        // Enable Accessibility button
        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Enable Screen Capture button
        binding.enableScreenCaptureButton.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
        }

        // Version display
        binding.versionText.text = "v29 • ${Build.MODEL}"

        // Device ID display
        try {
            val identity = DeviceIdentity(this).getOrCreate()
            binding.deviceIdText.text = "Device ID: ${identity.deviceId}"
        } catch (e: Exception) {
            binding.deviceIdText.text = "Device ID: error - ${e.message}"
        }

        // Reset pairing button
        binding.resetPairingButton.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Reset Pairing?")
                .setMessage("This will generate a new device identity. You'll need to re-approve the device on the Gateway.\n\nCurrent connection will be disconnected.")
                .setPositiveButton("Reset") { _, _ ->
                    // Disconnect first
                    GatewayForegroundService.stop(this)
                    binding.connectButton.isEnabled = true
                    binding.disconnectButton.isEnabled = false
                    binding.statusText.text = getString(R.string.status_disconnected)

                    // Clear ALL identity prefs (covers all versions)
                    listOf("device_identity", "device_identity_v2", "device_identity_ed25519").forEach { name ->
                        getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
                    }

                    // Regenerate identity
                    try {
                        val newIdentity = DeviceIdentity(this).getOrCreate()
                        binding.deviceIdText.text = "Device ID: ${newIdentity.deviceId}"
                        appendLog("Identity reset! New ID: ${newIdentity.deviceId.take(16)}...")
                        Toast.makeText(this, "New identity generated. Reconnect and approve on Gateway.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        appendLog("Identity reset failed: ${e.message}")
                        Toast.makeText(this, "Reset failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Biz Log export - command records with metrics
        binding.exportBizLogButton.setOnClickListener {
            val sb = StringBuilder()
            sb.appendLine("===== OpenClaw PhoneUse Business Log =====")
            sb.appendLine("Time: ${dateFormat.format(java.util.Date())}")
            sb.appendLine("Device: ${Build.MODEL} (${Build.MANUFACTURER})")
            sb.appendLine("Version: 2.0.0-v29")
            sb.appendLine("Accessibility: ${PhoneUseService.instance != null}")
            sb.appendLine("Gateway: ${GatewayForegroundService.gatewayClient != null}")
            sb.appendLine()
            logLines.forEach { sb.appendLine(it) }
            shareText("OpenClaw Biz Log", sb.toString())
        }

        // Debug Log export - logcat with stacktraces
        binding.exportDebugLogButton.setOnClickListener {
            Toast.makeText(this, "Collecting debug logs...", Toast.LENGTH_SHORT).show()
            Thread {
                val sb = StringBuilder()
                sb.appendLine("===== OpenClaw PhoneUse Debug Log =====")
                sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                sb.appendLine("Device: ${Build.MODEL} (${Build.MANUFACTURER})")
                sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                sb.appendLine("Version: 2.0.0-v29")
                sb.appendLine()
                sb.appendLine("=== State ===")
                sb.appendLine("Accessibility: ${PhoneUseService.instance != null}")
                sb.appendLine("ScreenCapture: ${ScreenCaptureManager.hasPermission()}")
                sb.appendLine("Gateway: ${GatewayForegroundService.gatewayClient != null}")
                sb.appendLine("KeepAlive: ${GatewayForegroundService.keepAlive != null}")
                sb.appendLine("ScreenOn: ${GatewayForegroundService.keepAlive?.isScreenOn()}")
                sb.appendLine()
                sb.appendLine("=== Command Log ===")
                logLines.forEach { sb.appendLine(it) }
                sb.appendLine()
                sb.appendLine("=== Logcat (last 1000 lines) ===")
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1000", "--pid=${android.os.Process.myPid()}"))
                    sb.append(p.inputStream.bufferedReader().readText())
                } catch (e: Exception) {
                    sb.appendLine("Logcat failed: ${e.message}")
                }
                val text = sb.toString()
                runOnUiThread { shareText("OpenClaw Debug Log", text) }
            }.start()
        }

        // Register broadcast receivers
        val filter = IntentFilter().apply {
            addAction("com.openclaw.phoneuse.STATUS")
            addAction("com.openclaw.phoneuse.COMMAND")
        }
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)

        // Check if already connected
        if (GatewayForegroundService.gatewayClient != null) {
            binding.connectButton.isEnabled = false
            binding.disconnectButton.isEnabled = true
        }

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Request battery optimization exemption (critical for background WebSocket)
        requestBatteryOptimizationExemption()

        // Request all files access (for file.read/write on /sdcard)
        requestStoragePermission()

        // Auto-connect if we have a saved URL and accessibility is enabled
        autoConnectIfReady()
    }

    private fun autoConnectIfReady() {
        // Don't auto-connect if already connected
        if (GatewayForegroundService.gatewayClient != null) return
        
        val savedUrl = prefs.getString("url", "") ?: ""
        if (savedUrl.isEmpty()) return  // No saved URL, user hasn't connected before
        
        if (!isAccessibilityServiceEnabled()) {
            appendLog("Auto-connect skipped: Accessibility not enabled")
            return
        }
        
        val token = prefs.getString("token", "") ?: ""
        val params = ConnectionParams.fromUrl(savedUrl, token) ?: return
        
        appendLog("Auto-connecting to ${params.displayUrl}…")
        GatewayForegroundService.start(this, params)
        binding.connectButton.isEnabled = false
        binding.disconnectButton.isEnabled = true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    9003
                )
            }
        }
    }

    /**
     * Request exemption from battery optimization (Doze mode).
     * This is critical for keeping WebSocket alive during deep sleep.
     */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback for devices that don't support per-app settings
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        appendLog("Storage permission request failed")
                    }
                }
            }
        } else {
            // Pre-Android 11: request legacy permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    9004)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @Suppress("BatteryLife")
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                appendLog("Battery optimization exemption request failed: ${e.message}")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureManager.storePermission(resultCode, data)
                appendLog("Screen capture permission granted ✓")
                updateScreenCaptureStatus()
            } else {
                appendLog("Screen capture permission denied")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateScreenCaptureStatus()
        
        // Every time app comes to foreground, check if we need to reconnect
        val client = GatewayForegroundService.gatewayClient
        if (client == null) {
            // Service not running - try auto connect
            autoConnectIfReady()
        } else {
            // Service exists - update button states
            binding.connectButton.isEnabled = false
            binding.disconnectButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.accessibilityStatus.text = if (enabled) "Accessibility: Enabled ✓" else "Accessibility: Not enabled ⚠️"
        binding.accessibilityStatus.setTextColor(if (enabled) 0xFF008800.toInt() else 0xFFCC0000.toInt())
    }

    private fun updateScreenCaptureStatus() {
        val enabled = ScreenCaptureManager.hasPermission()
        binding.screenCaptureStatus.text = if (enabled) "Screen Capture: Enabled ✓" else "Screen Capture: Not enabled ⚠️"
        binding.screenCaptureStatus.setTextColor(if (enabled) 0xFF008800.toInt() else 0xFFCC0000.toInt())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == PhoneUseService::class.java.name }
    }

    private fun updateConnectionStatus(status: String) {
        binding.statusText.text = when {
            status == "connecting" -> {
                binding.connectButton.isEnabled = false
                binding.disconnectButton.isEnabled = true
                getString(R.string.status_connecting)
            }
            status == "connected" -> {
                binding.connectButton.isEnabled = false
                binding.disconnectButton.isEnabled = true
                getString(R.string.status_connected)
            }
            status == "paired" -> {
                binding.connectButton.isEnabled = false
                binding.disconnectButton.isEnabled = true
                getString(R.string.status_paired)
            }
            status == "disconnected" -> {
                binding.connectButton.isEnabled = true
                binding.disconnectButton.isEnabled = false
                getString(R.string.status_disconnected)
            }
            status.startsWith("error:") -> {
                // Don't change button state on error - auto-reconnect handles it
                "Error: ${status.removePrefix("error:")}"
            }
            else -> status
        }
        appendLog("Status: $status")
    }

    private fun shareText(subject: String, text: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(intent, subject))
    }

    private fun appendLog(message: String) {
        val timestamp = dateFormat.format(Date())
        logLines.add("[$timestamp] $message")
        if (logLines.size > 50) logLines.removeAt(0)
        binding.logText.text = logLines.joinToString("\n")
    }
}
