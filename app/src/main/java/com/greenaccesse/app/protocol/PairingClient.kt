package com.greenaccesse.app.protocol

import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.transport.TransportConnection

data class PairingOutcome(
    val pcId: String,
    val pcName: String,
    val deviceSecret: ByteArray,
    /** "XXXX-XXXX": must match the code displayed by the Bridge. Lets the operator detect interception. */
    val verificationCode: String,
)

/**
 * Phone side of the pairing handshake (ECDH P-256 + pairing-code confirmation). See docs/pairing.md.
 *
 *  phone -> pair_request   (device id/name, nonce, ephemeral public key)
 *  PC    -> pair_challenge (pc id/name, nonce, ephemeral public key)
 *  phone -> pair_confirm   (HMAC proving knowledge of the pairing code shown on the PC)
 *  PC    -> pair_result    (HMAC proving the PC knows the same code, or an error)
 *
 * The pairing code itself is never transmitted.
 */
class PairingClient(private val identity: DeviceIdentity, private val timeoutMs: Long = 20_000) {

    suspend fun pair(connection: TransportConnection, pairingCode: String): PairingOutcome {
        if (!HandshakeCrypto.isValidPairingCode(pairingCode)) {
            throw ProtocolException(ErrorCodes.INVALID_CODE, "The pairing code must have ${Protocol.PAIRING_CODE_LENGTH} digits")
        }
        val keyPair = Crypto.generateEcKeyPair()
        val clientNonce = Crypto.randomBytes(Protocol.NONCE_BYTES)
        FrameIo.send(
            connection,
            PairRequest(
                protocolVersion = Protocol.VERSION,
                deviceId = identity.deviceId,
                deviceName = identity.deviceName.take(64),
                appVersion = identity.appVersion.take(32),
                clientNonce = Crypto.b64(clientNonce),
                clientPublicKey = Crypto.encodePublicKey(keyPair),
            ),
        )
        val challenge = FrameIo.expect<PairChallenge>(FrameIo.receive(connection, timeoutMs))
        val shared = Crypto.ecdh(keyPair, challenge.serverPublicKey)
        val keys = HandshakeCrypto.derivePairingKeys(
            sharedSecret = shared,
            pairingCode = pairingCode,
            deviceId = identity.deviceId,
            pcId = challenge.pcId,
            clientNonce = clientNonce,
            serverNonce = Crypto.unb64(challenge.serverNonce),
            clientPublicKeyDer = keyPair.public.encoded,
            serverPublicKeyDer = Crypto.unb64(challenge.serverPublicKey),
        )
        shared.fill(0)
        FrameIo.send(connection, PairConfirm(Crypto.b64(keys.clientProof())))
        val result = FrameIo.expect<PairResult>(FrameIo.receive(connection, timeoutMs))
        if (!result.success) {
            throw RemoteProtocolException(result.errorCode ?: ErrorCodes.INVALID_CODE, result.message ?: "Pairing rejected by the PC")
        }
        val serverProof = Crypto.unb64(result.serverProof!!)
        if (!Crypto.constantTimeEquals(serverProof, keys.serverProof())) {
            throw ProtocolException(ErrorCodes.AUTH_FAILED, "The PC could not prove it knows the pairing code")
        }
        return PairingOutcome(challenge.pcId, challenge.pcName, keys.deviceSecret, keys.verificationCode())
    }
}
