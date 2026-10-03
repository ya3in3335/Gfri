package com.greenaccesse.app.domain

import com.greenaccesse.app.history.ScanHistoryRepository
import com.greenaccesse.app.history.ScanRecord
import com.greenaccesse.app.history.ScanStatus
import com.greenaccesse.app.network.ConnectionManager
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.network.SendResult
import com.greenaccesse.app.nfc.NfcScan
import com.greenaccesse.app.nfc.RawTag
import com.greenaccesse.app.nfc.ScanDebouncer
import com.greenaccesse.app.nfc.TagParser
import com.greenaccesse.app.nfc.TagReadError
import com.greenaccesse.app.nfc.TagReadResult
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.protocol.NdefPayload
import com.greenaccesse.app.protocol.NdefRecordPayload
import com.greenaccesse.app.protocol.NfcScanMessage
import com.greenaccesse.app.protocol.Protocol
import com.greenaccesse.app.settings.SettingsRepository
import com.greenaccesse.app.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** What the scanner UI shows. */
sealed interface ScannerState {
    data object Ready : ScannerState
    data class Sending(val uid: String, val atMillis: Long) : ScannerState
    data class Sent(
        val uid: String,
        val atMillis: Long,
        val status: ScanStatus,
        val message: String?,
        /** Informational result reported by Green Access ("granted", "denied", ...). Never computed on the phone. */
        val result: String? = null,
    ) : ScannerState
    data class Failed(val uid: String, val atMillis: Long, val status: ScanStatus, val message: String) : ScannerState
    data class ReadError(val message: String, val atMillis: Long) : ScannerState
}

/** Sound / vibration feedback; implemented on Android by [com.greenaccesse.app.presentation.AndroidScanFeedback]. */
interface ScanFeedback {
    fun onCardDetected(sound: Boolean, vibrate: Boolean)
    fun onResult(success: Boolean, sound: Boolean, vibrate: Boolean)
}

/**
 * The single "READ NFC -> SEND EVENT" pipeline:
 * parse tag -> debounce -> record -> send over the authenticated channel -> record outcome.
 *
 * Offline policy: when the PC is unreachable the scan is recorded as failed and is NOT queued for later replay,
 * because replaying old scans could create unexpected attendance entries. Each scan carries a unique scan_id so a
 * future opt-in offline queue can be added safely (the Bridge de-duplicates by scan_id).
 */
class ScanCoordinator(
    private val scope: CoroutineScope,
    private val connection: ConnectionManager,
    private val history: ScanHistoryRepository,
    private val settings: SettingsRepository,
    private val identity: DeviceIdentity,
    private val debouncer: ScanDebouncer,
    private val feedback: ScanFeedback,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val _state = MutableStateFlow<ScannerState>(ScannerState.Ready)
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    init {
        scope.launch { settings.settings.collect { debouncer.windowMillis = it.debounceMillis } }
    }

    /** Entry point from the NFC reader (any thread). */
    fun onTagDiscovered(tag: RawTag) {
        when (val result = TagParser.parse(tag)) {
            is TagReadResult.Failure -> onReadFailure(result.reason)
            is TagReadResult.Success -> onScan(result.scan)
        }
    }

    fun onReadFailure(error: TagReadError) {
        _state.value = ScannerState.ReadError(error.message, clock.nowMillis())
        scope.launch {
            val s = settings.current()
            feedback.onResult(false, s.soundOnScan, s.vibrationOnScan)
        }
    }

    fun resetToReady() {
        _state.value = ScannerState.Ready
    }

    private fun onScan(scan: NfcScan) {
        if (!debouncer.shouldAccept(scan.uid)) return
        val now = clock.nowMillis()
        _state.value = ScannerState.Sending(scan.uid, now)
        scope.launch {
            val s = settings.current()
            feedback.onCardDetected(s.soundOnScan, s.vibrationOnScan)
            val scanId = UUID.randomUUID().toString()
            val connState = connection.state.value
            history.add(ScanRecord(scanId, now, scan.uid, ScanStatus.PENDING, connectionLabel(connState)))

            val result = if (connState.isConnected) connection.sendScan(toMessage(scanId, now, scan)) else SendResult.NotConnected
            val reportedResult = (result as? SendResult.Sent)?.result
            val (status, message) = when (result) {
                is SendResult.Sent -> ScanStatus.SENT to (result.message ?: "Sent successfully")
                SendResult.Delivered -> ScanStatus.DELIVERED to "Delivered to PC"
                is SendResult.Rejected -> ScanStatus.REJECTED to (result.message ?: "Rejected by the PC")
                SendResult.NotConnected -> ScanStatus.FAILED_DISCONNECTED to "PC disconnected. Unable to send scan."
                SendResult.Timeout -> ScanStatus.FAILED_TIMEOUT to "The PC did not respond."
                SendResult.ConnectionLost -> ScanStatus.FAILED_TIMEOUT to "Connection lost before confirmation."
                is SendResult.Error -> ScanStatus.FAILED_ERROR to result.message
            }
            history.updateStatus(scanId, status)
            _state.value = if (status.isSuccess) {
                ScannerState.Sent(scan.uid, now, status, message, reportedResult)
            } else {
                ScannerState.Failed(scan.uid, now, status, message)
            }
            feedback.onResult(status.isSuccess, s.soundOnScan, s.vibrationOnScan)
        }
    }

    private fun toMessage(scanId: String, atMillis: Long, scan: NfcScan) = NfcScanMessage(
        protocolVersion = Protocol.VERSION,
        scanId = scanId,
        deviceId = identity.deviceId,
        timestamp = Instant.ofEpochMilli(atMillis).toString(),
        uid = scan.uid,
        uidLength = scan.uidLength,
        technologies = scan.technologies,
        ndef = scan.ndef?.let { n ->
            NdefPayload(
                available = n.available,
                type = n.type,
                writable = n.writable,
                records = n.records.map { NdefRecordPayload(it.tnf, it.type, it.payloadLength, it.text) },
            )
        },
    )

    companion object {
        fun connectionLabel(state: ConnectionState): String = when (state) {
            is ConnectionState.Connected -> "Connected"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Reconnecting -> "Reconnecting"
            ConnectionState.NotPaired -> "Not paired"
            ConnectionState.NotConfigured -> "Not configured"
            is ConnectionState.Disconnected -> "Disconnected"
            is ConnectionState.AuthenticationFailed -> "Authentication failed"
        }
    }
}
