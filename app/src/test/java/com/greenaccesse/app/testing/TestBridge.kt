package com.greenaccesse.app.testing

import com.greenaccesse.app.protocol.Ack
import com.greenaccesse.app.protocol.Auth
import com.greenaccesse.app.protocol.AuthChallenge
import com.greenaccesse.app.protocol.Connect
import com.greenaccesse.app.protocol.Connected
import com.greenaccesse.app.protocol.Disconnect
import com.greenaccesse.app.protocol.ErrorCodes
import com.greenaccesse.app.protocol.ErrorMessage
import com.greenaccesse.app.protocol.HandshakeCrypto
import com.greenaccesse.app.protocol.NfcResponse
import com.greenaccesse.app.protocol.NfcScanMessage
import com.greenaccesse.app.protocol.PairChallenge
import com.greenaccesse.app.protocol.PairConfirm
import com.greenaccesse.app.protocol.PairRequest
import com.greenaccesse.app.protocol.PairResult
import com.greenaccesse.app.protocol.Ping
import com.greenaccesse.app.protocol.Pong
import com.greenaccesse.app.protocol.Protocol
import com.greenaccesse.app.protocol.ProtocolCodec
import com.greenaccesse.app.protocol.ProtocolMessage
import com.greenaccesse.app.protocol.SecureChannel
import com.greenaccesse.app.protocol.SecureFrame
import com.greenaccesse.app.protocol.Status
import com.greenaccesse.app.protocol.Unpair
import com.greenaccesse.app.security.Crypto
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Server end of a link, independent of the transport (in-memory channel or real WebSocket). */
interface ServerLink {
    val incoming: ReceiveChannel<String>
    fun send(text: String)
    fun close()
}

/**
 * Protocol-accurate implementation of the Bridge (server) side, used to test the phone end-to-end.
 * It mirrors what the Windows Green Access Bridge must do (see docs/protocol.md and bridge/reference).
 */
class TestBridge(
    val pcId: String = "PC-TEST-01",
    val pcName: String = "Front Desk PC",
    @Volatile var pairingCode: String? = "583921",
    val gymName: String = "My Gym",
) {
    val devices = ConcurrentHashMap<String, ByteArray>()
    val scans = CopyOnWriteArrayList<NfcScanMessage>()
    val verificationCodes = CopyOnWriteArrayList<String>()

    /** How to answer an nfc_scan. Return null to stay silent. */
    @Volatile var onScan: (NfcScanMessage) -> List<ProtocolMessage> = { scan ->
        listOf(Ack("nfc_scan", scan.scanId), NfcResponse(scan.scanId, true, "Scan received", "received"))
    }
    @Volatile var answerPings = true
    @Volatile var activeLink: ServerLink? = null
    @Volatile private var activeChannel: SecureChannel? = null

    suspend fun serve(link: ServerLink) {
        try {
            val first = ProtocolCodec.decode(link.incoming.receive())
            when (first) {
                is PairRequest -> handlePairing(link, first)
                is Connect -> handleSession(link, first)
                else -> link.send(ProtocolCodec.encode(ErrorMessage(ErrorCodes.UNEXPECTED, "Expected pair_request or connect")))
            }
        } catch (_: Exception) {
        } finally {
            link.close()
        }
    }

    private suspend fun handlePairing(link: ServerLink, req: PairRequest) {
        val code = pairingCode
        if (code == null) {
            link.send(ProtocolCodec.encode(ErrorMessage(ErrorCodes.PAIRING_CLOSED, "Pairing is not active")))
            return
        }
        val kp = Crypto.generateEcKeyPair()
        val serverNonce = Crypto.randomBytes(Protocol.NONCE_BYTES)
        link.send(ProtocolCodec.encode(PairChallenge(Protocol.VERSION, pcId, pcName, Crypto.b64(serverNonce), Crypto.encodePublicKey(kp))))
        val confirm = ProtocolCodec.decode(link.incoming.receive()) as PairConfirm
        val keys = HandshakeCrypto.derivePairingKeys(
            Crypto.ecdh(kp, req.clientPublicKey), code, req.deviceId, pcId,
            Crypto.unb64(req.clientNonce), serverNonce, Crypto.unb64(req.clientPublicKey), kp.public.encoded,
        )
        if (!Crypto.constantTimeEquals(Crypto.unb64(confirm.clientProof), keys.clientProof())) {
            link.send(ProtocolCodec.encode(PairResult(false, errorCode = ErrorCodes.INVALID_CODE, message = "Invalid pairing code")))
            return
        }
        devices[req.deviceId] = keys.deviceSecret
        verificationCodes += keys.verificationCode()
        link.send(ProtocolCodec.encode(PairResult(true, serverProof = Crypto.b64(keys.serverProof()))))
    }

    private suspend fun handleSession(link: ServerLink, connect: Connect) {
        val secret = devices[connect.deviceId]
        if (secret == null) {
            link.send(ProtocolCodec.encode(ErrorMessage(ErrorCodes.NOT_PAIRED, "Unknown device")))
            return
        }
        val serverNonce = Crypto.randomBytes(Protocol.NONCE_BYTES)
        link.send(ProtocolCodec.encode(AuthChallenge(Protocol.VERSION, pcId, Crypto.b64(serverNonce))))
        val auth = ProtocolCodec.decode(link.incoming.receive()) as Auth
        val keys = HandshakeCrypto.deriveSessionKeys(secret, connect.deviceId, pcId, Crypto.unb64(connect.clientNonce), serverNonce)
        if (!Crypto.constantTimeEquals(Crypto.unb64(auth.clientProof), keys.clientProof())) {
            link.send(ProtocolCodec.encode(ErrorMessage(ErrorCodes.AUTH_FAILED, "Authentication failed")))
            return
        }
        val sessionId = "S-" + UUID.randomUUID().toString().take(8)
        val channel = SecureChannel.forServer(sessionId, keys)
        // Publish the session before the phone learns it is connected, so tests can act on it immediately.
        activeChannel = channel
        activeLink = link
        link.send(ProtocolCodec.encode(Connected(sessionId, pcName, Crypto.b64(keys.serverProof(sessionId)), 5)))
        sendSecure(Status(role = "bridge", pcName = pcName, gymName = gymName, greenAccessConnected = true, bridgeVersion = "test"))
        for (text in link.incoming) {
            val inner = channel.open(ProtocolCodec.decode(text) as SecureFrame)
            when (inner) {
                is Ping -> if (answerPings) sendSecure(Pong(inner.id, inner.timestamp))
                is NfcScanMessage -> {
                    scans += inner
                    onScan(inner).forEach { sendSecure(it) }
                }
                is Unpair -> {
                    devices.remove(connect.deviceId)
                    return
                }
                is Disconnect -> return
                else -> Unit
            }
        }
    }

    @Synchronized
    fun sendSecure(message: ProtocolMessage) {
        val channel = activeChannel ?: return
        activeLink?.send(ProtocolCodec.encode(channel.seal(message)))
    }

    fun dropConnection() {
        activeLink?.close()
    }
}
