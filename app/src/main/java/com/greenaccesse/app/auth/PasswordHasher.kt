package com.greenaccesse.app.auth

import com.greenaccesse.app.security.Crypto
import kotlinx.serialization.Serializable

/** PBKDF2-HMAC-SHA256 password hashing with a random per-account salt. Plaintext passwords are never stored. */
class PasswordHasher(private val iterations: Int = DEFAULT_ITERATIONS) {

    fun hash(password: CharArray): PasswordHash {
        val salt = Crypto.randomBytes(16)
        val hash = Crypto.pbkdf2(password, salt, iterations)
        return PasswordHash(Crypto.b64(salt), Crypto.b64(hash), iterations)
    }

    fun verify(password: CharArray, stored: PasswordHash): Boolean {
        val candidate = Crypto.pbkdf2(password, Crypto.unb64(stored.saltB64), stored.iterations)
        return Crypto.constantTimeEquals(candidate, Crypto.unb64(stored.hashB64))
    }

    companion object {
        const val DEFAULT_ITERATIONS = 120_000
    }
}

@Serializable
data class PasswordHash(val saltB64: String, val hashB64: String, val iterations: Int)
