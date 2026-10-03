package com.greenaccesse.app.protocol

import com.greenaccesse.app.nfc.Uid
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.security.CryptoException
import com.greenaccesse.app.util.AppJson
import kotlinx.serialization.SerializationException

class ProtocolException(val code: String, message: String) : Exception(message)

/**
 * Encodes and decodes protocol frames. Every inbound frame is size-limited, parsed strictly and then validated;
 * malformed frames raise [ProtocolException] and are never acted upon.
 */
object ProtocolCodec {

    fun encode(message: ProtocolMessage): String = AppJson.encodeToString(ProtocolMessage.serializer(), message)

    fun decode(text: String): ProtocolMessage {
        if (text.length > Protocol.MAX_FRAME_BYTES || text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_FRAME_BYTES) {
            throw ProtocolException(ErrorCodes.MALFORMED, "Frame too large")
        }
        val message = try {
            AppJson.decodeFromString(ProtocolMessage.serializer(), text)
        } catch (e: SerializationException) {
            throw ProtocolException(ErrorCodes.MALFORMED, "Malformed message: ${e.message?.take(120)}")
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(ErrorCodes.MALFORMED, "Malformed message")
        }
        validate(message)
        return message
    }

    fun validate(message: ProtocolMessage) {
        when (message) {
            is PairRequest -> {
                requireVersion(message.protocolVersion)
                requireId(message.deviceId, "device_id")
                requireText(message.deviceName, "device_name", 64)
                requireText(message.appVersion, "app_version", 32)
                requireNonce(message.clientNonce, "client_nonce")
                requireKey(message.clientPublicKey, "client_public_key")
            }
            is PairChallenge -> {
                requireVersion(message.protocolVersion)
                requireId(message.pcId, "pc_id")
                requireText(message.pcName, "pc_name", 64)
                requireNonce(message.serverNonce, "server_nonce")
                requireKey(message.serverPublicKey, "server_public_key")
            }
            is PairConfirm -> requireMac(message.clientProof, "client_proof")
            is PairResult -> {
                if (message.success) requireMac(message.serverProof, "server_proof")
                message.errorCode?.let { requireText(it, "error_code", 64) }
                message.message?.let { requireText(it, "message", 256, allowEmpty = true) }
            }
            is Connect -> {
                requireVersion(message.protocolVersion)
                requireId(message.deviceId, "device_id")
                requireNonce(message.clientNonce, "client_nonce")
                requireText(message.appVersion, "app_version", 32)
            }
            is AuthChallenge -> {
                requireVersion(message.protocolVersion)
                requireId(message.pcId, "pc_id")
                requireNonce(message.serverNonce, "server_nonce")
            }
            is Auth -> requireMac(message.clientProof, "client_proof")
            is Connected -> {
                requireId(message.sessionId, "session_id")
                requireText(message.pcName, "pc_name", 64)
                requireMac(message.serverProof, "server_proof")
                check(message.heartbeatIntervalSeconds in 5..300, "heartbeat_interval_s out of range")
            }
            is SecureFrame -> {
                check(message.seq in 1..Long.MAX_VALUE / 2, "Invalid seq")
                val nonce = decodeB64(message.nonce, "nonce")
                check(nonce.size == Crypto.GCM_NONCE_BYTES, "Invalid nonce length")
                check(message.data.length <= Protocol.MAX_FRAME_BYTES, "data too large")
                decodeB64(message.data, "data")
            }
            is Ping -> { requireText(message.id, "id", 64); requireText(message.timestamp, "timestamp", 40) }
            is Pong -> { requireText(message.id, "id", 64); requireText(message.timestamp, "timestamp", 40) }
            is Status -> {
                check(message.role == "device" || message.role == "bridge", "Invalid role")
                message.pcName?.let { requireText(it, "pc_name", 64, allowEmpty = true) }
                message.gymName?.let { requireText(it, "gym_name", 64, allowEmpty = true) }
                message.appVersion?.let { requireText(it, "app_version", 32, allowEmpty = true) }
                message.bridgeVersion?.let { requireText(it, "bridge_version", 32, allowEmpty = true) }
            }
            is NfcScanMessage -> {
                requireVersion(message.protocolVersion)
                requireId(message.scanId, "scan_id")
                requireId(message.deviceId, "device_id")
                requireText(message.timestamp, "timestamp", 40)
                check(Uid.isValid(message.uid), "Invalid uid")
                check(message.uidLength == message.uid.length / 2, "uid_length mismatch")
                check(message.technologies.size <= 16 && message.technologies.all { it.length in 1..32 }, "Invalid technologies")
                message.ndef?.let { ndef ->
                    check(ndef.records.size <= 16, "Too many NDEF records")
                    ndef.records.forEach { r -> r.text?.let { requireText(it, "ndef.text", 512, allowEmpty = true) } }
                }
            }
            is Ack -> { requireText(message.refType, "ref_type", 32); requireText(message.refId, "ref_id", 64) }
            is NfcResponse -> {
                message.scanId?.let { requireId(it, "scan_id") }
                message.message?.let { requireText(it, "message", 256, allowEmpty = true) }
                message.result?.let { requireText(it, "result", 32) }
            }
            is Disconnect -> message.reason?.let { requireText(it, "reason", 256, allowEmpty = true) }
            is Unpair -> message.reason?.let { requireText(it, "reason", 256, allowEmpty = true) }
            is ErrorMessage -> {
                requireText(message.code, "code", 64)
                message.message?.let { requireText(it, "message", 256, allowEmpty = true) }
                message.refId?.let { requireText(it, "ref_id", 64) }
            }
        }
    }

    private fun check(condition: Boolean, reason: String) {
        if (!condition) throw ProtocolException(ErrorCodes.MALFORMED, reason)
    }

    private fun requireVersion(v: Int) {
        if (v != Protocol.VERSION) throw ProtocolException(ErrorCodes.UNSUPPORTED_VERSION, "Unsupported protocol version $v")
    }

    private val ID_REGEX = Regex("^[A-Za-z0-9_.:-]{1,64}$")

    private fun requireId(value: String, field: String) = check(ID_REGEX.matches(value), "Invalid $field")

    private fun requireText(value: String, field: String, max: Int, allowEmpty: Boolean = false) {
        check(allowEmpty || value.isNotBlank(), "Empty $field")
        check(value.length <= max, "$field too long")
        check(value.none { it.isISOControl() }, "Control characters in $field")
    }

    private fun decodeB64(value: String, field: String): ByteArray = try {
        Crypto.unb64(value)
    } catch (e: CryptoException) {
        throw ProtocolException(ErrorCodes.MALFORMED, "Invalid Base64 in $field")
    }

    private fun requireNonce(value: String, field: String) =
        check(value.length <= 64 && decodeB64(value, field).size == Protocol.NONCE_BYTES, "Invalid $field")

    private fun requireMac(value: String?, field: String) =
        check(value != null && value.length <= 64 && decodeB64(value, field).size == 32, "Invalid $field")

    private fun requireKey(value: String, field: String) =
        check(value.length <= 256 && decodeB64(value, field).size in 64..128, "Invalid $field")
}
