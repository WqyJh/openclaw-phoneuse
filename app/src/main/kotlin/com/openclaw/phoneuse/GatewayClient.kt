package com.openclaw.phoneuse

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenClaw Gateway WebSocket Protocol client with:
 * - Exponential backoff reconnect (reset on manual connect)
 * - Concurrency-safe single connection
 * - node.invoke.request event → node.invoke.result response
 */
class GatewayClient(
    private val identity: DeviceIdentity,
    private val commandHandler: CommandHandler,
    private val listener: ConnectionListener
) {
    companion object {
        private const val TAG = "GatewayClient"
        private const val PROTOCOL_VERSION = 3
        private const val INITIAL_BACKOFF_MS = 2000L
        private const val MAX_BACKOFF_MS = 60000L
    }

    interface ConnectionListener {
        fun onConnecting()
        fun onConnected()
        fun onPaired(deviceToken: String?)
        fun onDisconnected(reason: String)
        fun onCommandReceived(command: String, id: String)
        fun onCommandCompleted(command: String, success: Boolean, execMs: Long = 0, payloadBytes: Int = 0, error: String? = null)
        fun onError(error: String)
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var scope: CoroutineScope? = null
    private var gatewayToken: String = ""
    private var isConnected = false
    private var shouldReconnect = false
    private var connectionParams: ConnectionParams? = null
    private var myNodeId: String? = null
    
    // Concurrency guard - prevents duplicate connections
    private val connecting = AtomicBoolean(false)
    private val connectGeneration = AtomicInteger(0)
    
    // Exponential backoff
    private var currentBackoffMs = INITIAL_BACKOFF_MS

    private val supportedCommands = listOf(
        "camera.snap", "camera.list", "camera.clip", "screen.record",
        "location.get", "notifications.list", "system.run", "system.notify",
        "phoneUse.tap", "phoneUse.doubleTap", "phoneUse.longTap",
        "phoneUse.swipe", "phoneUse.pinch", "phoneUse.setText",
        "phoneUse.typeText", "phoneUse.findAndClick", "phoneUse.screenshot",
        "phoneUse.requestScreenCapture", "phoneUse.getUITree",
        "phoneUse.getScreenInfo", "phoneUse.launch", "phoneUse.back",
        "phoneUse.home", "phoneUse.recents", "phoneUse.openNotifications",
        "phoneUse.openQuickSettings", "phoneUse.scrollDown", "phoneUse.scrollUp",
        "phoneUse.scrollLeft", "phoneUse.scrollRight",
        "phoneUse.waitForElement", "phoneUse.inputKey",
        "phoneUse.unlock", "phoneUse.lockScreen",
        "phoneUse.wakeScreen", "phoneUse.isScreenOn",
        "file.read", "file.write", "file.info", "file.list", "file.delete"
    )

    /**
     * Connect (manual) — resets backoff.
     */
    fun connect(params: ConnectionParams) {
        currentBackoffMs = INITIAL_BACKOFF_MS  // Reset backoff on manual connect
        connectInternal(params)
    }

    private fun connectInternal(params: ConnectionParams) {
        // Prevent concurrent connect attempts
        if (!connecting.compareAndSet(false, true)) {
            Log.w(TAG, "Connect already in progress, skipping")
            return
        }
        
        val gen = connectGeneration.incrementAndGet()
        
        // Clean up previous connection
        webSocket?.close(1000, "reconnecting")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        
        gatewayToken = params.token
        connectionParams = params
        shouldReconnect = true
        isConnected = false
        
        if (scope?.isActive != true) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        listener.onConnecting()

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(params.wsUrl)
            .header("User-Agent", "OpenClawPhoneUse/2.0.25")
            .build()

        Log.i(TAG, "Connecting to ${params.wsUrl} (gen=$gen, backoff=${currentBackoffMs}ms)")

        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != connectGeneration.get()) return  // Stale connection
                Log.i(TAG, "WebSocket opened (gen=$gen)")
                connecting.set(false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != connectGeneration.get()) return
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed (gen=$gen): $code $reason")
                connecting.set(false)
                isConnected = false
                listener.onDisconnected("Closed: $reason")
                scheduleReconnect(gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure (gen=$gen): ${t.message}")
                connecting.set(false)
                isConnected = false
                listener.onError(t.message ?: "Connection failed")
                listener.onDisconnected("Error: ${t.message}")
                scheduleReconnect(gen)
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        connectGeneration.incrementAndGet()  // Invalidate any pending reconnect
        connecting.set(false)
        scope?.cancel()
        scope = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        isConnected = false
        currentBackoffMs = INITIAL_BACKOFF_MS
    }

    private fun scheduleReconnect(generation: Int) {
        if (!shouldReconnect) return
        if (generation != connectGeneration.get()) return  // Outdated
        val params = connectionParams ?: return
        
        val delay = currentBackoffMs
        currentBackoffMs = (currentBackoffMs * 1.5).toLong().coerceAtMost(MAX_BACKOFF_MS)
        
        Log.i(TAG, "Reconnecting in ${delay}ms (backoff=${currentBackoffMs}ms, gen=$generation)")
        
        // Ensure we have a live scope for the reconnect timer
        val activeScope = scope?.takeIf { it.isActive }
            ?: CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope = it }
        
        activeScope.launch {
            delay(delay)
            if (shouldReconnect && generation == connectGeneration.get()) {
                connecting.set(false)  // Allow new connect
                connectInternal(params)
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "event" -> handleEvent(msg)
                "req" -> handleRequest(msg)
                "res" -> handleResponse(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message handling failed: ${e.message}", e)
        }
    }

    private fun handleEvent(msg: JSONObject) {
        val event = msg.optString("event")
        val payload = msg.optJSONObject("payload")

        when (event) {
            "connect.challenge" -> {
                try {
                    sendConnect(payload?.optString("nonce") ?: return)
                } catch (e: Exception) {
                    Log.e(TAG, "sendConnect failed: ${e.message}", e)
                    listener.onError("Handshake failed: ${e.message}")
                }
            }
            "node.invoke.request" -> {
                val requestId = payload?.optString("id") ?: return
                val nodeId = payload.optString("nodeId", "")
                val command = payload.optString("command", "")
                val paramsJSON = payload.optString("paramsJSON", null)

                Log.i(TAG, "Invoke: $command (id=${requestId.take(8)})")
                listener.onCommandReceived(command, requestId)

                val cmdParams = paramsJSON?.let {
                    try { JSONObject(it) } catch (_: Exception) { JSONObject() }
                } ?: JSONObject()

                scope?.launch {
                    val startMs = System.currentTimeMillis()
                    try {
                        val result = commandHandler.execute(command, cmdParams)
                        val execMs = System.currentTimeMillis() - startMs
                        val payloadJson = result.toString()
                        val payloadBytes = payloadJson.length
                        sendInvokeResult(requestId, nodeId, true, result)
                        val totalMs = System.currentTimeMillis() - startMs
                        Log.i(TAG, "CMD OK: $command exec=${execMs}ms send=${totalMs - execMs}ms payload=${payloadBytes}B")
                        listener.onCommandCompleted(command, true, execMs, payloadBytes)
                    } catch (e: Exception) {
                        val execMs = System.currentTimeMillis() - startMs
                        Log.e(TAG, "CMD FAIL: $command exec=${execMs}ms error=${e.message}", e)
                        sendInvokeResult(requestId, nodeId, false, null,
                            JSONObject().put("code", "EXECUTION_ERROR").put("message", e.message))
                        listener.onCommandCompleted(command, false, execMs, 0, e.message)
                    }
                }
            }
        }
    }

    private fun handleResponse(msg: JSONObject) {
        val ok = msg.optBoolean("ok", false)
        val payload = msg.optJSONObject("payload")

        if (ok && payload?.optString("type") == "hello-ok") {
            isConnected = true
            currentBackoffMs = INITIAL_BACKOFF_MS  // Reset backoff on successful connect
            listener.onConnected()

            payload.optJSONObject("auth")?.optString("deviceToken")?.let { token ->
                identity.deviceToken = token
                listener.onPaired(token)
            }
        } else if (!ok) {
            listener.onError(msg.optJSONObject("error")?.optString("message") ?: "Connect rejected")
        }
    }

    private fun handleRequest(msg: JSONObject) {
        val id = msg.optString("id")
        when (msg.optString("method")) {
            "node.ping" -> sendResponse(id, true, JSONObject().put("pong", true))
        }
    }

    private fun sendInvokeResult(requestId: String, nodeId: String, ok: Boolean, payload: JSONObject?, error: JSONObject? = null) {
        val params = JSONObject()
            .put("id", requestId)
            .put("nodeId", nodeId.ifEmpty { myNodeId ?: identity.getOrCreate().deviceId })
            .put("ok", ok)
        payload?.let { params.put("payloadJSON", it.toString()) }
        error?.let { params.put("error", it) }

        send(JSONObject()
            .put("type", "req")
            .put("id", UUID.randomUUID().toString())
            .put("method", "node.invoke.result")
            .put("params", params))
    }

    private fun sendConnect(nonce: String) {
        val id = identity.getOrCreate()
        myNodeId = id.deviceId
        val authToken = identity.deviceToken ?: gatewayToken

        val (signPayload, signedAt) = identity.buildSignaturePayload(
            nonce = nonce, token = authToken, role = "node"
        )

        val commandsArray = JSONArray().apply { supportedCommands.forEach { put(it) } }
        val deviceFamily = Build.MODEL.trim().lowercase()


        send(JSONObject()
            .put("type", "req")
            .put("id", UUID.randomUUID().toString())
            .put("method", "connect")
            .put("params", JSONObject()
                .put("minProtocol", PROTOCOL_VERSION)
                .put("maxProtocol", PROTOCOL_VERSION)
                .put("client", JSONObject()
                    .put("id", "openclaw-android")
                    .put("version", "2.0.25")
                    .put("platform", "android")
                    .put("mode", "node")
                    .put("deviceFamily", deviceFamily))
                .put("role", "node")
                .put("scopes", JSONArray())
                .put("caps", JSONArray().put("phoneUse").put("accessibility")
                    .put("screen").put("camera").put("notifications").put("location"))
                .put("commands", commandsArray)
                .put("permissions", JSONObject()
                    .put("accessibility", true)
                    .put("screenCapture", PhoneUseService.instance != null))
                .put("auth", JSONObject().put("token", authToken))
                .put("locale", "zh-CN")
                .put("userAgent", "openclaw-phoneuse/2.0.25 (${Build.MODEL})")
                .put("device", JSONObject()
                    .put("id", id.deviceId)
                    .put("publicKey", id.publicKey)
                    .put("signature", identity.sign(signPayload))
                    .put("signedAt", signedAt)
                    .put("nonce", nonce))))
    }

    private fun sendResponse(requestId: String, ok: Boolean, payload: JSONObject) {
        send(JSONObject().put("type", "res").put("id", requestId).put("ok", ok).put("payload", payload))
    }

    private fun send(json: JSONObject) {
        try { webSocket?.send(json.toString()) } catch (e: Exception) { Log.e(TAG, "Send failed: ${e.message}") }
    }
}
