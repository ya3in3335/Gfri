package com.greenaccesse.app.transport

import kotlinx.coroutines.channels.ReceiveChannel

enum class TransportKind(val label: String) {
    /** WebSocket over the local Wi-Fi / LAN. Default and recommended. */
    WIFI("Local Wi-Fi"),

    /** WebSocket tunnelled over USB using `adb reverse` (see docs/troubleshooting.md). Optional. */
    USB_ADB("USB (ADB reverse)"),
}

data class Endpoint(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/**
 * A way of reaching the Green Access Bridge. Implementations only move text frames;
 * all protocol, authentication and encryption logic lives above this layer, so new transports
 * (USB accessory, Bluetooth, TLS...) can be added without touching the rest of the app.
 */
interface Transport {
    val kind: TransportKind

    /** Resolves the endpoint actually dialled for a configured host/port (e.g. USB always uses loopback). */
    fun resolve(configured: Endpoint): Endpoint = configured

    /**
     * Opens a connection. Suspends until the link is established.
     * @throws TransportException when the endpoint is unreachable or invalid.
     */
    suspend fun open(endpoint: Endpoint): TransportConnection
}

interface TransportConnection {
    /** Inbound text frames. Closed normally when the peer closes, or with a [TransportException] on failure. */
    val incoming: ReceiveChannel<String>

    /** Queues a text frame. Returns false if the connection is already closed. */
    fun send(text: String): Boolean

    /** Closes the connection. Idempotent. */
    fun close(reason: String = "closing")
}

open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

object HostValidator {
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val HOSTNAME_LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (IPV4.matches(host)) return true
        if (host.all { it.isDigit() || it == '.' }) return false // malformed IPv4
        return host.trimEnd('.').split('.').all { HOSTNAME_LABEL.matches(it) }
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun validate(endpoint: Endpoint) {
        if (!isValidHost(endpoint.host)) throw TransportException("Invalid PC address \"${endpoint.host.take(64)}\"")
        if (!isValidPort(endpoint.port)) throw TransportException("Invalid port ${endpoint.port}")
    }
}
