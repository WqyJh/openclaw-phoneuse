package com.openclaw.phoneuse

import android.util.Log

/**
 * Connection parameters for Gateway connection.
 */
data class ConnectionParams(
    val host: String,
    val port: Int,
    val token: String,
    val useWss: Boolean = false,
    val url: String? = null
) {
    val wsUrl: String
        get() {
            if (url != null) return url
            val scheme = if (useWss) "wss" else "ws"
            return "$scheme://$host:$port"
        }

    val displayUrl: String
        get() {
            if (url != null) return url
            return "$host:$port"
        }

    companion object {
        private const val TAG = "ConnectionParams"

        /**
         * Parse a Gateway URL (https:// or wss://) into ConnectionParams.
         * Handles Tailscale Serve URLs like https://mydevice.ts.net
         */
        fun fromUrl(urlString: String, token: String): ConnectionParams? {
            return try {
                var url = urlString.trim()
                
                // Ensure URL has a scheme
                if (!url.startsWith("http://") && !url.startsWith("https://") 
                    && !url.startsWith("ws://") && !url.startsWith("wss://")) {
                    // Check if it looks like host:port (e.g. 192.168.1.100:18789)
                    val colonIdx = url.lastIndexOf(":")
                    val afterColon = if (colonIdx > 0) url.substring(colonIdx + 1) else ""
                    if (afterColon.toIntOrNull() != null) {
                        // Bare host:port → ws://
                        url = "ws://$url"
                    } else {
                        // Domain name without port → likely Tailscale Serve → https://
                        url = "https://$url"
                    }
                }

                val uri = java.net.URI(url)
                
                // Convert http(s) to ws(s) for WebSocket
                val wsScheme = when (uri.scheme?.lowercase()) {
                    "https" -> "wss"
                    "http" -> "ws"
                    "wss", "ws" -> uri.scheme
                    else -> "wss" // default for Tailscale Serve
                }

                val port = when {
                    uri.port > 0 -> uri.port
                    wsScheme == "wss" -> 443
                    else -> 80
                }

                // Reconstruct as WebSocket URL
                val wsUrl = "$wsScheme://${uri.host}${
                    if ((wsScheme == "wss" && port == 443) || (wsScheme == "ws" && port == 80)) "" 
                    else ":$port"
                }"

                Log.i(TAG, "Parsed URL: $urlString -> $wsUrl")
                ConnectionParams(
                    host = uri.host,
                    port = port,
                    token = token,
                    useWss = wsScheme == "wss",
                    url = wsUrl
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse URL: $urlString - ${e.message}")
                null
            }
        }
    }
}
