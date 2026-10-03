package com.greenaccesse.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `HKDF matches RFC 5869 test case 1`() {
        val okm = Crypto.hkdfSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3CB25F25FAACD57A90434F64D0362F2A2D2D0A90CF1A5A4C5DB02D56ECC4C5BF34007208D5B887185865",
            Crypto.hex(okm),
        )
    }

    @Test fun `ECDH yields the same secret on both sides`() {
        val a = Crypto.generateEcKeyPair()
        val b = Crypto.generateEcKeyPair()
        assertArrayEquals(Crypto.ecdh(a, Crypto.encodePublicKey(b)), Crypto.ecdh(b, Crypto.encodePublicKey(a)))
    }

    @Test(expected = CryptoException::class)
    fun `ECDH rejects garbage keys`() {
        Crypto.ecdh(Crypto.generateEcKeyPair(), Crypto.b64(ByteArray(91) { 7 }))
    }

    @Test fun `AES-GCM round trip and tamper detection`() {
        val key = Crypto.randomBytes(32)
        val nonce = Crypto.randomBytes(12)
        val ct = Crypto.aesGcmEncrypt(key, nonce, "hello".toByteArray(), "aad".toByteArray())
        assertEquals("hello", String(Crypto.aesGcmDecrypt(key, nonce, ct, "aad".toByteArray())))
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertTrue(runCatching { Crypto.aesGcmDecrypt(key, nonce, ct, "aad".toByteArray()) }.exceptionOrNull() is CryptoException)
    }

    @Test fun `transcript fields are length prefixed`() {
        assertEquals("00000001410000000142", Crypto.hex(Crypto.transcript("A".toByteArray(), "B".toByteArray())))
        // "AB" + "" must differ from "A" + "B"
        assertTrue(!Crypto.transcript("AB".toByteArray(), ByteArray(0)).contentEquals(Crypto.transcript("A".toByteArray(), "B".toByteArray())))
    }
}
