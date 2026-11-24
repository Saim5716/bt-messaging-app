package com.example.identity

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.model.Contact
import com.example.core.model.LocalIdentity
import com.example.core.model.QrIdentityPayload
import com.example.crypto.CryptoEngine
import com.example.crypto.IdentityKeyBundle
import com.example.crypto.SecretVault
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.json.Json

class IdentityManager(
    private val crypto: CryptoEngine,
    private val vault: SecretVault
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedKeys: IdentityKeyBundle? = null

    fun ensureKeyBundle(): IdentityKeyBundle {
        cachedKeys?.let { return it }
        val loaded = vault.loadIdentityBundle()
        if (loaded != null) {
            cachedKeys = loaded
            return loaded
        }
        val generated = crypto.generateIdentityKeys()
        vault.saveIdentityBundle(generated)
        cachedKeys = generated
        return generated
    }

    fun getLocalIdentity(displayName: String): LocalIdentity {
        val keys = ensureKeyBundle()
        val nodeId = deriveNodeId(keys.signPublicKey)
        val fingerprint = formatFingerprint(crypto.blake2b256(keys.signPublicKey))
        return LocalIdentity(
            nodeId = nodeId,
            displayName = displayName,
            signPublicKeyBase64 = crypto.toBase64(keys.signPublicKey),
            kxPublicKeyBase64 = crypto.toBase64(keys.kxPublicKey),
            safetyFingerprint = fingerprint,
            createdAt = System.currentTimeMillis()
        )
    }

    fun deriveNodeId(signPublicKey: ByteArray): String {
        val hash = crypto.blake2b256(signPublicKey)
        return crypto.toHex(hash.copyOfRange(0, 8))
    }

    fun formatFingerprint(hash: ByteArray): String {
        return hash.take(12).chunked(2).joinToString(":") { pair ->
            pair.joinToString("") { "%02X".format(it) }
        }
    }

    fun buildSignedQrUri(displayName: String): String {
        val keys = ensureKeyBundle()
        val nodeId = deriveNodeId(keys.signPublicKey)
        val signPkB64 = crypto.toBase64(keys.signPublicKey)
        val kxPkB64 = crypto.toBase64(keys.kxPublicKey)
        val timestamp = System.currentTimeMillis()
        val canonical = canonicalQrString(nodeId, displayName, signPkB64, kxPkB64, timestamp)
        val sig = crypto.signDetached(keys.signSecretKey, canonical.toByteArray(Charsets.UTF_8))

        val payload = QrIdentityPayload(
            version = 1,
            nodeId = nodeId,
            displayName = displayName,
            signPublicKeyBase64 = signPkB64,
            kxPublicKeyBase64 = kxPkB64,
            timestamp = timestamp,
            signatureBase64 = crypto.toBase64(sig)
        )
        return QR_PREFIX + json.encodeToString(QrIdentityPayload.serializer(), payload)
    }

    fun verifyAndParseQrUri(rawQr: String, selfDisplayName: String): Result<Contact> = runCatching {
        val jsonBody = if (rawQr.startsWith(QR_PREFIX)) {
            rawQr.removePrefix(QR_PREFIX)
        } else {
            rawQr
        }
        val payload = json.decodeFromString(QrIdentityPayload.serializer(), jsonBody)
        val signPk = crypto.fromBase64(payload.signPublicKeyBase64)
        val sig = crypto.fromBase64(payload.signatureBase64)
        val expectedNodeId = deriveNodeId(signPk)
        require(expectedNodeId == payload.nodeId) { "QR Node ID does not match public key" }

        val canonical = canonicalQrString(
            payload.nodeId,
            payload.displayName,
            payload.signPublicKeyBase64,
            payload.kxPublicKeyBase64,
            payload.timestamp
        )
        val validSig = crypto.verifyDetached(signPk, canonical.toByteArray(Charsets.UTF_8), sig)
        require(validSig) { "Invalid Ed25519 QR identity signature" }

        val selfKeys = ensureKeyBundle()
        require(!signPk.contentEquals(selfKeys.signPublicKey)) { "Scanned your own identity QR code" }

        val safetyNumber = crypto.computeSafetyNumber(selfKeys.signPublicKey, signPk)
        Contact(
            nodeId = payload.nodeId,
            displayName = payload.displayName.ifBlank { "Node ${payload.nodeId.take(6)}" },
            signPublicKeyBase64 = payload.signPublicKeyBase64,
            kxPublicKeyBase64 = payload.kxPublicKeyBase64,
            isVerified = true,
            safetyNumber = safetyNumber,
            lastSeenAt = System.currentTimeMillis()
        )
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    fun computeSafetyNumberWith(peerSignPublicKeyBase64: String): String {
        val selfKeys = ensureKeyBundle()
        val peerPk = crypto.fromBase64(peerSignPublicKeyBase64)
        return crypto.computeSafetyNumber(selfKeys.signPublicKey, peerPk)
    }

    fun resetIdentity() {
        cachedKeys = null
        vault.clearAllSecrets()
    }

    private fun canonicalQrString(
        nodeId: String,
        displayName: String,
        signPkB64: String,
        kxPkB64: String,
        timestamp: Long
    ): String = "$nodeId|$displayName|$signPkB64|$kxPkB64|$timestamp"

    companion object {
        const val QR_PREFIX = "offlinemesh://id/v1/"
    }
}
