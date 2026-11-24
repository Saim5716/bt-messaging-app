package com.example.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

interface SecretVault {
    fun wrapWithKeystore(plaintext: ByteArray): ByteArray
    fun unwrapWithKeystore(wrapped: ByteArray): ByteArray
    fun saveIdentityBundle(bundle: IdentityKeyBundle)
    fun loadIdentityBundle(): IdentityKeyBundle?
    fun clearAllSecrets()
}

class KeystoreVault(private val context: Context) : SecretVault {

    private val vaultDir = File(context.filesDir, "vault").apply { mkdirs() }
    private val identityFile = File(vaultDir, "identity.enc")
    private val json = Json { ignoreUnknownKeys = true }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    override fun wrapWithKeystore(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    override fun unwrapWithKeystore(wrapped: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(wrapped)
        val ivLength = buffer.int
        require(ivLength in 12..32) { "Invalid GCM IV length" }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun saveIdentityBundle(bundle: IdentityKeyBundle) {
        val serialized = json.encodeToString(IdentityKeyBundle.serializer(), bundle)
            .toByteArray(Charsets.UTF_8)
        val sealed = wrapWithKeystore(serialized)
        identityFile.writeBytes(sealed)
    }

    override fun loadIdentityBundle(): IdentityKeyBundle? {
        if (!identityFile.exists()) return null
        return runCatching {
            val sealed = identityFile.readBytes()
            val plaintext = unwrapWithKeystore(sealed)
            json.decodeFromString(
                IdentityKeyBundle.serializer(),
                plaintext.toString(Charsets.UTF_8)
            )
        }.getOrNull()
    }

    override fun clearAllSecrets() {
        identityFile.delete()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "offlinemesh_master_vault_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
