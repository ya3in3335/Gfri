package com.greenaccesse.app.protocol

import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.transport.TransportConnection

data class AuthenticatedSession(
    val sessionId: String,
    val pcName: String,
    val heartbeatIntervalSeconds: Int,
    val channel: SecureChannel,
)

/**
 * Mutual authentication of an already-paired phone and PC (challenge/response with the shared device secret).
 * The phone refuses to talk to a PC that cannot prove possession of the secret established at pairing time,
 * so a random PC on the network cannot impersonate Green Access.
 */
class SessionClient(private val identity: DeviceIdentity, private val timeoutMs: Long = 10_000) {

    suspend fun authenticate(connection: TransportConnection, expectedPcId: String, deviceSecret: ByteArray): AuthenticatedSession {
        val clientNonce = Crypto.randomBytes(Protocol.NONCE_BYTES)
        FrameIo.send(connection, Connect(Protocol.VERSION, identity.deviceId, Crypto.b64(clientNonce), identity.appVersion.take(32)))
        val challenge = FrameIo.expect<AuthChallenge>(FrameIo.receive(connection, timeoutMs))
        if (challenge.pcId != expectedPcId) {
            throw ProtocolException(ErrorCodes.AUTH_FAILED, "This PC is not the paired Green Access PC")
        }
        val keys = HandshakeCrypto.deriveSessionKeys(deviceSecret, identity.deviceId, challenge.pcId, clientNonce, Crypto.unb64(challenge.serverNonce))
        FrameIo.send(connection, Auth(Crypto.b64(keys.clientProof())))
        val connected = FrameIo.expect<Connected>(FrameIo.receive(connection, timeoutMs))
        if (!Crypto.constantTimeEquals(Crypto.unb64(connected.serverProof), keys.serverProof(connected.sessionId))) {
            throw ProtocolException(ErrorCodes.AUTH_FAILED, "The PC failed authentication")
        }
        return AuthenticatedSession(
            sessionId = connected.sessionId,
            pcName = connected.pcName,
            heartbeatIntervalSeconds = connected.heartbeatIntervalSeconds,
            channel = SecureChannel.forClient(connected.sessionId, keys),
        )
    }
}
