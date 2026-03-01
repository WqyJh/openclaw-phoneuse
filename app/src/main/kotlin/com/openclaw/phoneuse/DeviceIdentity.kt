package com.openclaw.phoneuse

import android.content.Context
import android.os.Build
import android.util.Log
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

/**
 * Manages device identity for OpenClaw Gateway protocol.
 * Uses Ed25519 (via net.i2p.crypto.eddsa) for device authentication.
 *
 * Key format (matching Gateway):
 * - Public key: raw 32 bytes, base64url-encoded
 * - Device ID: SHA-256 hex of raw 32-byte public key
 * - Signature: Ed25519 signature, base64url-encoded
 * - Signature payload: v3 format
 */
class DeviceIdentity(private val context: Context) {

    companion object {
        private const val TAG = "DeviceIdentity"
        private val ED25519_SPEC = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    }

    private val prefs = context.getSharedPreferences("device_identity_ed25519", Context.MODE_PRIVATE)

    data class Identity(
        val deviceId: String,
        val publicKey: String,          // base64url raw 32 bytes
        val privateKeySeed: ByteArray   // 32-byte seed
    )

    fun getOrCreate(): Identity {
        val existing = loadExisting()
        if (existing != null) return existing
        return generate().also { save(it) }
    }

    fun sign(payload: String): String {
        return try {
            val identity = getOrCreate()
            val privKeySpec = EdDSAPrivateKeySpec(identity.privateKeySeed, ED25519_SPEC)
            val privKey = EdDSAPrivateKey(privKeySpec)
            
            val engine = EdDSAEngine(MessageDigest.getInstance(ED25519_SPEC.hashAlgorithm))
            engine.initSign(privKey)
            engine.update(payload.toByteArray(Charsets.UTF_8))
            val sig = engine.sign()
            
            base64UrlEncode(sig)
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519 sign failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Build v3 signature payload and return (payload, signedAt).
     */
    fun buildSignaturePayload(
        nonce: String,
        token: String,
        role: String = "node",
        scopes: List<String> = emptyList(),
        signedAt: Long = System.currentTimeMillis()
    ): Pair<String, Long> {
        val identity = getOrCreate()
        // Match Gateway's normalizeDeviceMetadataForAuth: trim + lowercase
        val platform = "android"
        val deviceFamily = Build.MODEL.trim().lowercase()
        
        val payload = listOf(
            "v3",
            identity.deviceId,
            "openclaw-android",
            "node",
            role,
            scopes.joinToString(","),
            signedAt.toString(),
            token,
            nonce,
            platform,
            deviceFamily
        ).joinToString("|")
        return Pair(payload, signedAt)
    }

    private fun generate(): Identity {
        val kpg = KeyPairGenerator()
        val keyPair = kpg.generateKeyPair()
        
        val pubKey = keyPair.public as EdDSAPublicKey
        val privKey = keyPair.private as EdDSAPrivateKey
        
        // Raw 32-byte public key (A point encoding)
        val rawPublicKey = pubKey.abyte
        
        // 32-byte seed for private key
        val seed = privKey.seed
        
        // Device ID = SHA-256 hex of raw public key
        val digest = MessageDigest.getInstance("SHA-256")
        val deviceId = bytesToHex(digest.digest(rawPublicKey))
        
        val publicKeyB64Url = base64UrlEncode(rawPublicKey)

        Log.i(TAG, "Generated Ed25519 identity: deviceId=$deviceId, pubKeyLen=${rawPublicKey.size}")
        return Identity(deviceId, publicKeyB64Url, seed)
    }

    private fun save(identity: Identity) {
        prefs.edit()
            .putString("device_id", identity.deviceId)
            .putString("public_key", identity.publicKey)
            .putString("private_key_seed", base64UrlEncode(identity.privateKeySeed))
            .apply()
    }

    private fun loadExisting(): Identity? {
        val deviceId = prefs.getString("device_id", null) ?: return null
        val publicKey = prefs.getString("public_key", null) ?: return null
        val seedStr = prefs.getString("private_key_seed", null) ?: return null
        val seed = base64UrlDecode(seedStr)
        
        // Verify: regenerate public key from seed and check device ID
        try {
            val privKeySpec = EdDSAPrivateKeySpec(seed, ED25519_SPEC)
            val privKey = EdDSAPrivateKey(privKeySpec)
            val pubKeySpec = EdDSAPublicKeySpec(privKey.a, ED25519_SPEC)
            val pubKey = EdDSAPublicKey(pubKeySpec)
            
            val rawPubKey = pubKey.abyte
            val digest = MessageDigest.getInstance("SHA-256")
            val derivedId = bytesToHex(digest.digest(rawPubKey))
            
            if (derivedId != deviceId) {
                Log.w(TAG, "Device ID mismatch on load, regenerating")
                return null
            }
            
            // Also verify stored public key matches
            val storedPubKey = base64UrlDecode(publicKey)
            if (!storedPubKey.contentEquals(rawPubKey)) {
                Log.w(TAG, "Public key mismatch on load, regenerating")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Identity verification failed: ${e.message}")
            return null
        }
        
        return Identity(deviceId, publicKey, seed)
    }

    var deviceToken: String?
        get() = prefs.getString("device_token", null)
        set(value) { prefs.edit().putString("device_token", value).apply() }

    // ========== Encoding Utilities ==========

    private fun base64UrlEncode(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .trimEnd('=')
    }

    private fun base64UrlDecode(input: String): ByteArray {
        val normalized = input.replace("-", "+").replace("_", "/")
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.NO_WRAP)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
