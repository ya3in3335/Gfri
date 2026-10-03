package com.greenaccesse.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM cipher whose key lives in the Android Keystore (hardware-backed when the device supports it).
 * The key material never leaves the Keystore, so secrets stored on disk are useless if copied off the device.
 *
 * Format of the ciphertext: [version=1][12-byte IV][ciphertext+tag]
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION) + iv + ct
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 1 + IV_SIZE + 16 || ciphertext[0] != VERSION) {
            throw CryptoException("Unsupported encrypted data format")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext, 1, IV_SIZE))
            cipher.doFinal(ciphertext, 1 + IV_SIZE, ciphertext.size - 1 - IV_SIZE)
        } catch (e: Exception) {
            throw CryptoException("Unable to decrypt stored secret", e)
        }
    }

    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "green_accesse_master_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val VERSION: Byte = 1
    }
}
