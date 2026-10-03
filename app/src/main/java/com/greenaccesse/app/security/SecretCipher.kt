package com.greenaccesse.app.security

/** Encrypts small secrets at rest. Production implementation is backed by the Android Keystore. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    /** @throws CryptoException if the data was tampered with or the key is no longer available. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}
