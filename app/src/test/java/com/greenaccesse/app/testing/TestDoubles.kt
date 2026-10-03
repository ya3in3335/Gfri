package com.greenaccesse.app.testing

import com.greenaccesse.app.domain.ScanFeedback
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.security.SecretCipher
import com.greenaccesse.app.util.MonotonicClock

/** AES-GCM cipher with an in-memory key, standing in for the Android Keystore in JVM tests. */
class TestCipher : SecretCipher {
    private val key = Crypto.randomBytes(32)
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = Crypto.randomBytes(12)
        return nonce + Crypto.aesGcmEncrypt(key, nonce, plaintext, ByteArray(0))
    }
    override fun decrypt(ciphertext: ByteArray): ByteArray =
        Crypto.aesGcmDecrypt(key, ciphertext.copyOfRange(0, 12), ciphertext.copyOfRange(12, ciphertext.size), ByteArray(0))
}

class FakeMonotonicClock(var now: Long = 0L) : MonotonicClock {
    override fun elapsedMillis(): Long = now
}

class RecordingFeedback : ScanFeedback {
    val events = mutableListOf<String>()
    override fun onCardDetected(sound: Boolean, vibrate: Boolean) { events += "detected" }
    override fun onResult(success: Boolean, sound: Boolean, vibrate: Boolean) { events += if (success) "success" else "failure" }
}
