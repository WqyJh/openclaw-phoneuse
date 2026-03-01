package com.openclaw.phoneuse

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service to keep the Gateway WebSocket connection alive.
 * Android will kill background network connections; this prevents that.
 */
class GatewayForegroundService : Service() {

    companion object {
        private const val TAG = "GatewayFgService"
        private const val CHANNEL_ID = "openclaw_phoneuse_channel"
        private const val NOTIFICATION_ID = 1001

        var gatewayClient: GatewayClient? = null
            private set
        var keepAlive: KeepAliveManager? = null
            private set

        /**
         * Start with ConnectionParams (supports both manual host:port and URL mode).
         */
        fun start(context: Context, params: ConnectionParams) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                putExtra("url", params.wsUrl)
                putExtra("token", params.token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            gatewayClient?.disconnect()
            gatewayClient = null
            keepAlive?.releaseAll()
            keepAlive = null
            context.stopService(Intent(context, GatewayForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If service is restarted by system (START_STICKY), intent is null
        // Fall back to SharedPreferences for saved connection params
        val prefs = getSharedPreferences("gateway_config", MODE_PRIVATE)
        val url = intent?.getStringExtra("url") ?: prefs.getString("url", null)
        val token = intent?.getStringExtra("token") ?: prefs.getString("token", "") ?: ""

        if (url.isNullOrEmpty()) {
            Log.w(TAG, "No URL available (intent=$intent), stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Build connection params from URL
        val params = ConnectionParams.fromUrl(url, token)
        if (params == null) {
            Log.w(TAG, "Failed to parse URL: $url")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "Starting with URL: ${params.displayUrl} (from ${if (intent != null) "intent" else "prefs"})")

        // Show persistent notification
        val notification = buildNotification("Connecting to ${params.displayUrl}…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Acquire persistent wake locks for background operation
        val ka = KeepAliveManager(this)
        ka.acquirePersistentLocks()
        keepAlive = ka

        // Create and connect Gateway client
        val identity = DeviceIdentity(this)
        val commandHandler = CommandHandler()
        
        val listener = object : GatewayClient.ConnectionListener {
            override fun onConnecting() {
                updateNotification("Connecting to ${params.displayUrl}…")
                broadcastStatus("connecting")
            }
            override fun onConnected() {
                updateNotification("Connected — waiting for pairing…")
                broadcastStatus("connected")
            }
            override fun onPaired(deviceToken: String?) {
                updateNotification("Paired ✓ — ready for commands")
                broadcastStatus("paired")
            }
            override fun onDisconnected(reason: String) {
                updateNotification("Disconnected: $reason")
                broadcastStatus("disconnected")
            }
            override fun onCommandReceived(command: String, id: String) {
                updateNotification("Executing: $command")
                broadcastCommand(command, "received")
            }
            override fun onCommandCompleted(command: String, success: Boolean, execMs: Long, payloadBytes: Int, error: String?) {
                val status = if (success) "✓" else "✗"
                updateNotification("Last: $command $status — ready")
                val details = buildString {
                    append(if (success) "success" else "failed")
                    append(" ${execMs}ms")
                    if (payloadBytes > 0) {
                        val kb = payloadBytes / 1024
                        append(" ${if (kb > 0) "${kb}KB" else "${payloadBytes}B"}")
                    }
                    if (error != null) append(" err=$error")
                }
                broadcastCommand(command, details)
            }
            override fun onError(error: String) {
                Log.e(TAG, "Gateway error: $error")
                broadcastStatus("error:$error")
            }
        }

        val client = GatewayClient(identity, commandHandler, listener)
        gatewayClient = client
        client.connect(params)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        gatewayClient?.disconnect()
        gatewayClient = null
        keepAlive?.releaseAll()
        keepAlive = null
        Log.i(TAG, "Foreground service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "OpenClaw PhoneUse Agent connection status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent("com.openclaw.phoneuse.STATUS").apply {
            putExtra("status", status)
            setPackage(packageName)
        })
    }

    private fun broadcastCommand(command: String, state: String) {
        sendBroadcast(Intent("com.openclaw.phoneuse.COMMAND").apply {
            putExtra("command", command)
            putExtra("state", state)
            setPackage(packageName)
        })
    }
}
