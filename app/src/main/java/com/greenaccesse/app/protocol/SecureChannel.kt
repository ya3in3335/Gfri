package com.greenaccesse.app.protocol

import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.security.CryptoException

/**
 * Encrypted, authenticated and replay-protected message channel for an established session.
 *
 * - Each direction has its own AES-256-GCM key.
 * - Sequence numbers start at 1 and must increase by exactly one (WebSocket preserves ordering),
 *   so replayed, dropped, reordered or reflected frames are rejected.
 * - AAD binds each frame to the session and direction: "GA1|<session_id>|<direction>|<seq>".
 */
class SecureChannel(
    private val sessionId: String,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendDirection: String,
    private val receiveDirection: String,
) {
    private var sendSeq = 0L
    private var receiveSeq = 0L

    @Synchronized
    fun seal(message: ProtocolMessage): SecureFrame {
        require(message !is SecureFrame) { "Cannot nest secure frames" }
        val seq = ++sendSeq
        val nonce = Crypto.randomBytes(Crypto.GCM_NONCE_BYTES)
        val plaintext = ProtocolCodec.encode(message).toByteArray(Charsets.UTF_8)
        val ct = Crypto.aesGcmEncrypt(sendKey, nonce, plaintext, aad(sendDirection, seq))
        return SecureFrame(seq, Crypto.b64(nonce), Crypto.b64(ct))
    }

    @Synchronized
    fun open(frame: SecureFrame): ProtocolMessage {
        if (frame.seq != receiveSeq + 1) {
            throw ProtocolException(ErrorCodes.MALFORMED, "Unexpected sequence number ${frame.seq} (expected ${receiveSeq + 1})")
        }
        val plaintext = try {
            Crypto.aesGcmDecrypt(receiveKey, Crypto.unb64(frame.nonce), Crypto.unb64(frame.data), aad(receiveDirection, frame.seq))
        } catch (e: CryptoException) {
            throw ProtocolException(ErrorCodes.AUTH_FAILED, "Frame authentication failed")
        }
        val inner = ProtocolCodec.decode(String(plaintext, Charsets.UTF_8))
        if (inner is SecureFrame) throw ProtocolException(ErrorCodes.MALFORMED, "Nested secure frame")
        receiveSeq = frame.seq
        return inner
    }

    private fun aad(direction: String, seq: Long) = Crypto.utf8("GA1|$sessionId|$direction|$seq")

    companion object {
        const val C2S = "c2s"
        const val S2C = "s2c"

        fun forClient(sessionId: String, keys: HandshakeCrypto.SessionKeys) =
            SecureChannel(sessionId, keys.clientToServerKey(), keys.serverToClientKey(), C2S, S2C)

        fun forServer(sessionId: String, keys: HandshakeCrypto.SessionKeys) =
            SecureChannel(sessionId, keys.serverToClientKey(), keys.clientToServerKey(), S2C, C2S)
    }
}
