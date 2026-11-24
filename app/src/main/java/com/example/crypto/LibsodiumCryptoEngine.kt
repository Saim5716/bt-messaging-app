package com.example.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.Sign
import java.security.MessageDigest
import java.security.SecureRandom

class LibsodiumCryptoEngine : CryptoEngine {

    private val sodium: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }
    private val secureRandom = SecureRandom()

    override fun generateIdentityKeys(): IdentityKeyBundle {
        val signPk = ByteArray(Sign.PUBLICKEYBYTES)
        val signSk = ByteArray(Sign.SECRETKEYBYTES)
        check(sodium.cryptoSignKeypair(signPk, signSk)) {
            "Failed to generate Ed25519 identity keypair"
        }

        val kxPk = ByteArray(Box.PUBLICKEYBYTES)
        val kxSk = ByteArray(Box.SECRETKEYBYTES)
        check(sodium.cryptoBoxKeypair(kxPk, kxSk)) {
            "Failed to generate X25519 key-exchange keypair"
        }

        return IdentityKeyBundle(
            signPublicKey = signPk,
            signSecretKey = signSk,
            kxPublicKey = kxPk,
            kxSecretKey = kxSk
        )
    }

    override fun generateSymmetricKey(): ByteArray = randomBytes(AEAD.XCHACHA20POLY1305_IETF_KEYBYTES)

    override fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        secureRandom.nextBytes(out)
        return out
    }

    override fun deriveSharedKey(
        myKxSecretKey: ByteArray,
        peerKxPublicKey: ByteArray,
        context: String
    ): ByteArray {
        require(myKxSecretKey.size == Box.SECRETKEYBYTES) { "Invalid X25519 secret key size" }
        require(peerKxPublicKey.size == Box.PUBLICKEYBYTES) { "Invalid X25519 public key size" }

        val sharedPoint = ByteArray(Box.BEFORENMBYTES)
        check(sodium.cryptoBoxBeforeNm(sharedPoint, peerKxPublicKey, myKxSecretKey)) {
            "X25519 key agreement failed"
        }

        val contextBytes = context.toByteArray(Charsets.UTF_8)
        val input = sharedPoint + contextBytes
        val derived = ByteArray(GenericHash.BYTES)
        check(sodium.cryptoGenericHash(derived, derived.size, input, input.size.toLong(), null, 0)) {
            "BLAKE2b key derivation failed"
        }
        sharedPoint.fill(0)
        return derived
    }

    override fun encryptAead(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        require(key.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) { "Invalid XChaCha20 key size" }
        val nonce = randomBytes(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val ciphertext = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)

        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext,
            null,
            plaintext,
            plaintext.size.toLong(),
            associatedData,
            associatedData.size.toLong(),
            null,
            nonce,
            key
        )
        check(ok) { "XChaCha20-Poly1305 encryption failed" }
        return nonce + ciphertext
    }

    override fun decryptAead(
        key: ByteArray,
        nonceAndCiphertext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        require(key.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) { "Invalid XChaCha20 key size" }
        val nonceLen = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES
        val tagLen = AEAD.XCHACHA20POLY1305_IETF_ABYTES
        require(nonceAndCiphertext.size >= nonceLen + tagLen) { "Ciphertext truncated" }

        val nonce = nonceAndCiphertext.copyOfRange(0, nonceLen)
        val ciphertext = nonceAndCiphertext.copyOfRange(nonceLen, nonceAndCiphertext.size)
        val plaintext = ByteArray(ciphertext.size - tagLen)

        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext,
            null,
            null,
            ciphertext,
            ciphertext.size.toLong(),
            associatedData,
            associatedData.size.toLong(),
            nonce,
            key
        )
        if (!ok) {
            throw SecurityException("AEAD authentication tag verification failed")
        }
        return plaintext
    }

    override fun signDetached(edSecretKey: ByteArray, message: ByteArray): ByteArray {
        require(edSecretKey.size == Sign.SECRETKEYBYTES) { "Invalid Ed25519 secret key size" }
        val sig = ByteArray(Sign.BYTES)
        check(sodium.cryptoSignDetached(sig, message, message.size.toLong(), edSecretKey)) {
            "Ed25519 signing failed"
        }
        return sig
    }

    override fun verifyDetached(
        edPublicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean {
        if (edPublicKey.size != Sign.PUBLICKEYBYTES || signature.size != Sign.BYTES) return false
        return sodium.cryptoSignVerifyDetached(signature, message, message.size, edPublicKey)
    }

    override fun blake2b256(data: ByteArray): ByteArray {
        val out = ByteArray(GenericHash.BYTES)
        check(sodium.cryptoGenericHash(out, out.size, data, data.size.toLong(), null, 0)) {
            "BLAKE2b hashing failed"
        }
        return out
    }

    override fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    override fun computeSafetyNumber(publicKeyA: ByteArray, publicKeyB: ByteArray): String {
        val aHex = toHex(publicKeyA)
        val bHex = toHex(publicKeyB)
        val ordered = if (aHex <= bHex) publicKeyA + publicKeyB else publicKeyB + publicKeyA
        val digest = blake2b256(ordered)
        return (0 until 6).joinToString(" ") { idx ->
            val offset = idx * 4
            val v = ((digest[offset].toInt() and 0xFF) shl 16) or
                ((digest[offset + 1].toInt() and 0xFF) shl 8) or
                (digest[offset + 2].toInt() and 0xFF)
            (v % 100000).toString().padStart(5, '0')
        }
    }

    override fun toBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    override fun fromBase64(base64: String): ByteArray =
        Base64.decode(base64, Base64.NO_WRAP)

    override fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
