package com.greenaccesse.app.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives used by Green Accesse.
 *
 * Only standard JCA algorithms are used (available on Android API 26+ and on every desktop JVM / .NET),
 * so the Windows Green Access Bridge can reproduce every operation:
 *  - ECDH over NIST P-256 (secp256r1), public keys encoded as X.509 SubjectPublicKeyInfo (DER, Base64)
 *  - HKDF-SHA256 (RFC 5869)
 *  - HMAC-SHA256
 *  - AES-256-GCM with 96-bit nonces and 128-bit tags
 *  - PBKDF2-HMAC-SHA256 for local password hashing
 */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF length" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    /** Constant-time comparison to avoid timing side channels when verifying proofs. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---------------------------------------------------------------- ECDH (P-256)

    fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        return generator.generateKeyPair()
    }

    fun encodePublicKey(keyPair: KeyPair): String = b64(keyPair.public.encoded)

    /** Performs ECDH with a peer public key (Base64 X.509 SPKI). Throws [CryptoException] on invalid keys. */
    fun ecdh(own: KeyPair, peerPublicKeyB64: String): ByteArray = try {
        val peerKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(peerPublicKeyB64)))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(own.private)
        agreement.doPhase(peerKey, true)
        agreement.generateSecret()
    } catch (e: Exception) {
        throw CryptoException("Invalid peer public key", e)
    }

    // ---------------------------------------------------------------- AES-GCM

    const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    } catch (e: Exception) {
        throw CryptoException("Message authentication failed", e)
    }

    // ---------------------------------------------------------------- PBKDF2

    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, lengthBytes: Int = 32): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, lengthBytes * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ---------------------------------------------------------------- Encoding

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw CryptoException("Invalid Base64", e)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * Length-prefixed concatenation used for all transcripts so that field boundaries are unambiguous.
     * Each field: 4-byte big-endian length followed by the bytes.
     */
    fun transcript(vararg fields: ByteArray): ByteArray {
        val total = fields.sumOf { 4 + it.size }
        val out = ByteArray(total)
        var o = 0
        for (f in fields) {
            out[o++] = (f.size ushr 24).toByte()
            out[o++] = (f.size ushr 16).toByte()
            out[o++] = (f.size ushr 8).toByte()
            out[o++] = f.size.toByte()
            System.arraycopy(f, 0, out, o, f.size)
            o += f.size
        }
        return out
    }
}

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
