package com.example.crypto

import kotlinx.serialization.Serializable

@Serializable
data class IdentityKeyBundle(
    val signPublicKey: ByteArray,
    val signSecretKey: ByteArray,
    val kxPublicKey: ByteArray,
    val kxSecretKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityKeyBundle) return false
        return signPublicKey.contentEquals(other.signPublicKey) &&
            signSecretKey.contentEquals(other.signSecretKey) &&
            kxPublicKey.contentEquals(other.kxPublicKey) &&
            kxSecretKey.contentEquals(other.kxSecretKey)
    }

    override fun hashCode(): Int {
        var result = signPublicKey.contentHashCode()
        result = 31 * result + signSecretKey.contentHashCode()
        result = 31 * result + kxPublicKey.contentHashCode()
        result = 31 * result + kxSecretKey.contentHashCode()
        return result
    }
}

interface CryptoEngine {
    fun generateIdentityKeys(): IdentityKeyBundle
    fun generateSymmetricKey(): ByteArray
    fun randomBytes(size: Int): ByteArray
    fun deriveSharedKey(myKxSecretKey: ByteArray, peerKxPublicKey: ByteArray, context: String): ByteArray
    fun encryptAead(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decryptAead(key: ByteArray, nonceAndCiphertext: ByteArray, associatedData: ByteArray): ByteArray
    fun signDetached(edSecretKey: ByteArray, message: ByteArray): ByteArray
    fun verifyDetached(edPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    fun blake2b256(data: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun computeSafetyNumber(publicKeyA: ByteArray, publicKeyB: ByteArray): String
    fun toBase64(bytes: ByteArray): String
    fun fromBase64(base64: String): ByteArray
    fun toHex(bytes: ByteArray): String
}
