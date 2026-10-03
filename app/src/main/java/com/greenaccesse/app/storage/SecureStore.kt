package com.greenaccesse.app.storage

import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.security.CryptoException
import com.greenaccesse.app.security.SecretCipher

/**
 * Key-value store where every value is encrypted with a [SecretCipher] (Android Keystore in production).
 * Values that cannot be decrypted (e.g. Keystore key lost after a device reset) are treated as absent.
 */
class SecureStore(private val backing: KeyValueStore, private val cipher: SecretCipher) {

    fun getString(key: String): String? {
        val stored = backing.getString(key) ?: return null
        return try {
            String(cipher.decrypt(Crypto.unb64(stored)), Charsets.UTF_8)
        } catch (_: CryptoException) {
            backing.remove(key)
            null
        }
    }

    fun putString(key: String, value: String) {
        backing.putString(key, Crypto.b64(cipher.encrypt(value.toByteArray(Charsets.UTF_8))))
    }

    fun remove(key: String) = backing.remove(key)

    fun clear() = backing.clear()
}
