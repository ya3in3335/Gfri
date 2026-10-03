package com.greenaccesse.app.network

import com.greenaccesse.app.transport.Endpoint
import com.greenaccesse.app.transport.TransportKind

sealed interface ConnectionState {
    /** No Green Access PC has been paired yet. */
    data object NotPaired : ConnectionState

    /** Paired, but no PC address is configured. */
    data object NotConfigured : ConnectionState

    /** Idle: user disconnected, app paused in background, or auto-reconnect is off after a failure. */
    data class Disconnected(val reason: String? = null) : ConnectionState

    data class Connecting(val endpoint: Endpoint, val transport: TransportKind, val attempt: Int) : ConnectionState

    data class Connected(
        val pcName: String,
        val endpoint: Endpoint,
        val transport: TransportKind,
        val sessionId: String,
        val sinceMillis: Long,
    ) : ConnectionState

    /** Connection lost: waiting [retryInMillis] before the next automatic attempt. */
    data class Reconnecting(val attempt: Int, val retryInMillis: Long, val lastError: String) : ConnectionState

    /** The PC refused this phone (pairing revoked or secret mismatch). Re-pairing is required; no automatic retries. */
    data class AuthenticationFailed(val message: String) : ConnectionState

    val isConnected: Boolean get() = this is Connected
}

/** Information the Bridge reports about itself. Contains no member data. */
data class BridgeStatus(
    val pcName: String? = null,
    val gymName: String? = null,
    val greenAccessConnected: Boolean? = null,
    val bridgeVersion: String? = null,
)

sealed interface SendResult {
    /** Final response from the Bridge (Green Access processed or accepted the scan). */
    data class Sent(val message: String?, val result: String?) : SendResult

    /** The Bridge acknowledged receipt but did not send a final response in time. */
    data object Delivered : SendResult

    /** The Bridge answered success=false. */
    data class Rejected(val message: String?) : SendResult
    data object NotConnected : SendResult
    data object Timeout : SendResult
    data object ConnectionLost : SendResult
    data class Error(val message: String) : SendResult
}

sealed interface TestResult {
    data class Success(val roundTripMillis: Long, val pcName: String) : TestResult
    data class Failure(val message: String) : TestResult
}
