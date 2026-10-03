package com.greenaccesse.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Green Access Bridge protocol, version 1. See docs/protocol.md for the normative specification.
 *
 * Every frame is a UTF-8 JSON object carried in a WebSocket text message. The "type" field selects the message.
 * Handshake messages are sent in clear (they contain no secrets); after authentication every message is wrapped
 * in an encrypted and authenticated [SecureFrame].
 */
object Protocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 8787
    const val WS_PATH = "/green-access/v1"
    const val MAX_FRAME_BYTES = 64 * 1024
    const val NONCE_BYTES = 16
    const val PAIRING_CODE_LENGTH = 6
    const val NSD_SERVICE_TYPE = "_greenaccess._tcp."
}

@Serializable
sealed class ProtocolMessage

// ------------------------------------------------------------------------------------------- Pairing (clear)

@Serializable
@SerialName("pair_request")
data class PairRequest(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("client_nonce") val clientNonce: String,
    @SerialName("client_public_key") val clientPublicKey: String,
) : ProtocolMessage()

@Serializable
@SerialName("pair_challenge")
data class PairChallenge(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("pc_id") val pcId: String,
    @SerialName("pc_name") val pcName: String,
    @SerialName("server_nonce") val serverNonce: String,
    @SerialName("server_public_key") val serverPublicKey: String,
) : ProtocolMessage()

@Serializable
@SerialName("pair_confirm")
data class PairConfirm(
    @SerialName("client_proof") val clientProof: String,
) : ProtocolMessage()

@Serializable
@SerialName("pair_result")
data class PairResult(
    val success: Boolean,
    @SerialName("server_proof") val serverProof: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null,
) : ProtocolMessage()

// ------------------------------------------------------------------------------------------- Session auth (clear)

@Serializable
@SerialName("connect")
data class Connect(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("client_nonce") val clientNonce: String,
    @SerialName("app_version") val appVersion: String,
) : ProtocolMessage()

@Serializable
@SerialName("auth_challenge")
data class AuthChallenge(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("pc_id") val pcId: String,
    @SerialName("server_nonce") val serverNonce: String,
) : ProtocolMessage()

@Serializable
@SerialName("auth")
data class Auth(
    @SerialName("client_proof") val clientProof: String,
) : ProtocolMessage()

@Serializable
@SerialName("connected")
data class Connected(
    @SerialName("session_id") val sessionId: String,
    @SerialName("pc_name") val pcName: String,
    @SerialName("server_proof") val serverProof: String,
    @SerialName("heartbeat_interval_s") val heartbeatIntervalSeconds: Int = 15,
) : ProtocolMessage()

/** Encrypted envelope: data = AES-256-GCM(key_direction, nonce, inner JSON, aad = "GA1|session_id|direction|seq"). */
@Serializable
@SerialName("secure")
data class SecureFrame(
    val seq: Long,
    val nonce: String,
    val data: String,
) : ProtocolMessage()

// ------------------------------------------------------------------------------------------- Inner (encrypted)

@Serializable
@SerialName("ping")
data class Ping(val id: String, val timestamp: String) : ProtocolMessage()

@Serializable
@SerialName("pong")
data class Pong(val id: String, val timestamp: String) : ProtocolMessage()

@Serializable
@SerialName("status")
data class Status(
    val role: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("nfc_enabled") val nfcEnabled: Boolean? = null,
    @SerialName("pc_name") val pcName: String? = null,
    @SerialName("gym_name") val gymName: String? = null,
    @SerialName("green_access_connected") val greenAccessConnected: Boolean? = null,
    @SerialName("bridge_version") val bridgeVersion: String? = null,
) : ProtocolMessage()

@Serializable
data class NdefPayload(
    val available: Boolean,
    val type: String? = null,
    val writable: Boolean = false,
    val records: List<NdefRecordPayload> = emptyList(),
)

@Serializable
data class NdefRecordPayload(
    val tnf: Int,
    @SerialName("record_type") val recordType: String,
    @SerialName("payload_length") val payloadLength: Int,
    val text: String? = null,
)

@Serializable
@SerialName("nfc_scan")
data class NfcScanMessage(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("scan_id") val scanId: String,
    @SerialName("device_id") val deviceId: String,
    val timestamp: String,
    val uid: String,
    @SerialName("uid_length") val uidLength: Int,
    val technologies: List<String>,
    val ndef: NdefPayload? = null,
) : ProtocolMessage()

@Serializable
@SerialName("ack")
data class Ack(
    @SerialName("ref_type") val refType: String,
    @SerialName("ref_id") val refId: String,
) : ProtocolMessage()

@Serializable
@SerialName("nfc_response")
data class NfcResponse(
    @SerialName("scan_id") val scanId: String? = null,
    val success: Boolean,
    val message: String? = null,
    /** Optional, informational: "received", "granted", "denied", "unknown_card"... Decided by Green Access, never by the phone. */
    val result: String? = null,
) : ProtocolMessage()

@Serializable
@SerialName("disconnect")
data class Disconnect(val reason: String? = null) : ProtocolMessage()

@Serializable
@SerialName("unpair")
data class Unpair(val reason: String? = null) : ProtocolMessage()

@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: String,
    val message: String? = null,
    @SerialName("ref_id") val refId: String? = null,
) : ProtocolMessage()

object ErrorCodes {
    const val MALFORMED = "malformed_message"
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val NOT_PAIRED = "not_paired"
    const val AUTH_FAILED = "auth_failed"
    const val INVALID_CODE = "invalid_pairing_code"
    const val PAIRING_CLOSED = "pairing_not_active"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val UNEXPECTED = "unexpected_message"
    const val INTERNAL = "internal_error"
}
