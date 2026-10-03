package com.greenaccesse.app.protocol

import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.security.Crypto.utf8

/**
 * Key derivation for pairing and session authentication (protocol v1). Both the phone and the Bridge execute
 * exactly these steps; docs/protocol.md describes them byte-for-byte.
 */
object HandshakeCrypto {

    // --------------------------------------------------------------------------------------- Pairing

    class PairingKeys(
        val transcriptHash: ByteArray,
        val confirmKey: ByteArray,
        val deviceSecret: ByteArray,
    ) {
        fun clientProof(): ByteArray = Crypto.hmacSha256(confirmKey, utf8("GA-PAIR-CLIENT"))
        fun serverProof(): ByteArray = Crypto.hmacSha256(confirmKey, utf8("GA-PAIR-SERVER"))

        /** Short code both screens can display so the operator can detect a man-in-the-middle ("XXXX-XXXX"). */
        fun verificationCode(): String {
            val h = Crypto.hex(Crypto.sha256(utf8("GA-VERIFY-V1"), transcriptHash)).take(8)
            return h.substring(0, 4) + "-" + h.substring(4)
        }
    }

    /**
     * transcript_hash = SHA256(LP("GA-PAIR-V1") | LP(device_id) | LP(pc_id) | LP(client_nonce) | LP(server_nonce)
     *                          | LP(client_public_key_der) | LP(server_public_key_der))
     * ikm             = ecdh_shared_secret | UTF8(pairing_code)
     * confirm_key     = HKDF-SHA256(ikm, salt = transcript_hash, info = "GA-PAIR-CONFIRM-V1", 32)
     * device_secret   = HKDF-SHA256(ikm, salt = transcript_hash, info = "GA-DEVICE-SECRET-V1", 32)
     * (LP = 4-byte big-endian length prefix)
     */
    fun derivePairingKeys(
        sharedSecret: ByteArray,
        pairingCode: String,
        deviceId: String,
        pcId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientPublicKeyDer: ByteArray,
        serverPublicKeyDer: ByteArray,
    ): PairingKeys {
        val transcriptHash = Crypto.sha256(
            Crypto.transcript(
                utf8("GA-PAIR-V1"), utf8(deviceId), utf8(pcId), clientNonce, serverNonce, clientPublicKeyDer, serverPublicKeyDer,
            ),
        )
        val ikm = sharedSecret + utf8(pairingCode)
        return PairingKeys(
            transcriptHash = transcriptHash,
            confirmKey = Crypto.hkdfSha256(ikm, transcriptHash, utf8("GA-PAIR-CONFIRM-V1"), 32),
            deviceSecret = Crypto.hkdfSha256(ikm, transcriptHash, utf8("GA-DEVICE-SECRET-V1"), 32),
        )
    }

    fun isValidPairingCode(code: String): Boolean =
        code.length == Protocol.PAIRING_CODE_LENGTH && code.all { it in '0'..'9' }

    // --------------------------------------------------------------------------------------- Session auth

    class SessionKeys(val authHash: ByteArray, private val deviceSecret: ByteArray) {
        fun clientProof(): ByteArray = Crypto.hmacSha256(deviceSecret, utf8("GA-AUTH-CLIENT"), authHash)
        fun serverProof(sessionId: String): ByteArray =
            Crypto.hmacSha256(deviceSecret, utf8("GA-AUTH-SERVER"), authHash, utf8(sessionId))

        fun clientToServerKey(): ByteArray = Crypto.hkdfSha256(deviceSecret, authHash, utf8("GA-SESSION-C2S-V1"), 32)
        fun serverToClientKey(): ByteArray = Crypto.hkdfSha256(deviceSecret, authHash, utf8("GA-SESSION-S2C-V1"), 32)
    }

    /** auth_hash = SHA256(LP("GA-AUTH-V1") | LP(device_id) | LP(pc_id) | LP(client_nonce) | LP(server_nonce)) */
    fun deriveSessionKeys(
        deviceSecret: ByteArray,
        deviceId: String,
        pcId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): SessionKeys {
        val authHash = Crypto.sha256(Crypto.transcript(utf8("GA-AUTH-V1"), utf8(deviceId), utf8(pcId), clientNonce, serverNonce))
        return SessionKeys(authHash, deviceSecret)
    }
}
