package com.greenaccesse.app.network

import com.greenaccesse.app.pairing.PairedPc
import com.greenaccesse.app.pairing.PairingRepository
import com.greenaccesse.app.protocol.Ack
import com.greenaccesse.app.protocol.AuthenticatedSession
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.protocol.Disconnect
import com.greenaccesse.app.protocol.ErrorCodes
import com.greenaccesse.app.protocol.ErrorMessage
import com.greenaccesse.app.protocol.NfcResponse
import com.greenaccesse.app.protocol.NfcScanMessage
import com.greenaccesse.app.protocol.Ping
import com.greenaccesse.app.protocol.Pong
import com.greenaccesse.app.protocol.ProtocolCodec
import com.greenaccesse.app.protocol.ProtocolException
import com.greenaccesse.app.protocol.ProtocolMessage
import com.greenaccesse.app.protocol.RemoteProtocolException
import com.greenaccesse.app.protocol.SecureFrame
import com.greenaccesse.app.protocol.SessionClient
import com.greenaccesse.app.protocol.Status
import com.greenaccesse.app.protocol.Unpair
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.settings.SettingsRepository
import com.greenaccesse.app.transport.Transport
import com.greenaccesse.app.transport.TransportConnection
import com.greenaccesse.app.transport.TransportException
import com.greenaccesse.app.transport.TransportKind
import com.greenaccesse.app.util.Clock
import com.greenaccesse.app.util.MonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Owns the single connection to the paired Green Access Bridge.
 *
 * Responsibilities: connect + mutual authentication, encrypted session, heartbeat, automatic reconnection with
 * exponential backoff (woken early when the network comes back), and request/response correlation for scans.
 * It never fabricates success: a scan is only reported as sent when the Bridge answers.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val pairing: PairingRepository,
    private val settings: SettingsRepository,
    private val identity: DeviceIdentity,
    private val transportFactory: (TransportKind) -> Transport,
    private val clock: Clock = Clock.SYSTEM,
    private val monotonic: MonotonicClock = MonotonicClock.SYSTEM,
    private val config: Config = Config(),
) {
    data class Config(
        val scanResponseTimeoutMs: Long = 5_000,
        val pongTimeoutMs: Long = 10_000,
        val initialBackoffMs: Long = 1_000,
        val maxBackoffMs: Long = 30_000,
        val jitterRatio: Double = 0.2,
        val handshakeTimeoutMs: Long = 10_000,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _bridgeStatus = MutableStateFlow<BridgeStatus?>(null)
    val bridgeStatus: StateFlow<BridgeStatus?> = _bridgeStatus.asStateFlow()

    private class ActiveSession(val connection: TransportConnection, val session: AuthenticatedSession)

    private class PendingScan {
        val result = CompletableDeferred<ProtocolMessage?>()
        @Volatile var acknowledged = false
    }

    @Volatile private var active: ActiveSession? = null
    private val sendMutex = Mutex()
    private val pendingScans = ConcurrentHashMap<String, PendingScan>()
    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Long>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lifecycleLock = Any()

    private var loopJob: Job? = null
    @Volatile private var userWantsConnection = false
    @Volatile private var appInForeground = true

    // ------------------------------------------------------------------------------------------ Public API

    /** Requests a connection (user action, after pairing, or after login). Resets the backoff. */
    fun connect() {
        userWantsConnection = true
        ensureLoop()
        wake.trySend(Unit)
    }

    /** User-initiated disconnect: stays disconnected until [connect] or [reconnect]. */
    fun disconnect(reason: String = "Disconnected by user") {
        userWantsConnection = false
        stopLoop(ConnectionState.Disconnected(reason), sendGoodbye = true)
    }

    /** Drops the current connection (if any) and connects again immediately. */
    fun reconnect() {
        userWantsConnection = true
        stopLoop(null, sendGoodbye = true)
        ensureLoop()
    }

    /** Called when the device regains network connectivity: retry now instead of waiting for the backoff. */
    fun onNetworkAvailable() {
        wake.trySend(Unit)
    }

    /** Called when connectivity is lost; the heartbeat will detect the dead link, this only speeds things up. */
    fun onNetworkLost() {
        val current = active ?: return
        current.connection.close("network lost")
    }

    fun onAppForeground() {
        appInForeground = true
        if (userWantsConnection) ensureLoop()
    }

    fun onAppBackground() {
        appInForeground = false
        stopLoop(ConnectionState.Disconnected("Paused while the app is in the background"), sendGoodbye = true)
    }

    /** Stops everything (logout / reset). */
    fun shutdown() {
        userWantsConnection = false
        stopLoop(ConnectionState.Disconnected(), sendGoodbye = true)
    }

    suspend fun sendScan(message: NfcScanMessage): SendResult {
        val current = active ?: return SendResult.NotConnected
        val pending = PendingScan()
        pendingScans[message.scanId] = pending
        try {
            if (!sendSecure(current, message)) return SendResult.NotConnected
            val response = withTimeoutOrNull(config.scanResponseTimeoutMs) { pending.result.await() }
            return when {
                response is NfcResponse && response.success -> SendResult.Sent(response.message, response.result)
                response is NfcResponse -> SendResult.Rejected(response.message)
                response is ErrorMessage -> SendResult.Error(response.message ?: response.code)
                pending.result.isCompleted -> SendResult.ConnectionLost // completed with null: session ended
                pending.acknowledged -> SendResult.Delivered
                else -> SendResult.Timeout
            }
        } finally {
            pendingScans.remove(message.scanId)
        }
    }

    /** Measures a real round trip to the Bridge over the authenticated channel. */
    suspend fun testConnection(): TestResult {
        val current = active ?: return TestResult.Failure("Not connected to Green Access PC")
        val rtt = ping(current) ?: return TestResult.Failure("The PC did not answer")
        return TestResult.Success(rtt, current.session.pcName)
    }

    /** Tells the Bridge to forget this phone (best effort) before local pairing data is deleted. */
    suspend fun sendUnpair(reason: String = "Pairing revoked on phone") {
        active?.let { runCatching { sendSecure(it, Unpair(reason)) } }
        userWantsConnection = false
        stopLoop(ConnectionState.NotPaired, sendGoodbye = false)
    }

    // ------------------------------------------------------------------------------------------ Loop

    private fun ensureLoop() = synchronized(lifecycleLock) {
        if (!appInForeground) return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    private fun stopLoop(finalState: ConnectionState?, sendGoodbye: Boolean) = synchronized(lifecycleLock) {
        val session = active
        active = null
        if (session != null) {
            if (sendGoodbye) {
                runCatching { session.connection.send(ProtocolCodec.encode(session.session.channel.seal(Disconnect("client_disconnect")))) }
            }
            session.connection.close("client disconnect")
        }
        loopJob?.cancel()
        loopJob = null
        failAllPending()
        _bridgeStatus.value = null
        if (finalState != null) _state.value = finalState
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (true) {
            val pc = pairing.pairedPc.value ?: pairing.load()
            if (pc == null) {
                _state.value = ConnectionState.NotPaired
                pairing.pairedPc.first { it != null }
                continue
            }
            val appSettings = settings.current()
            val configured = appSettings.endpoint
            if (configured == null) {
                _state.value = ConnectionState.NotConfigured
                settings.settings.first { it.endpoint != null }
                continue
            }
            val transport = transportFactory(appSettings.transport)
            val endpoint = transport.resolve(configured)
            attempt++
            _state.value = ConnectionState.Connecting(endpoint, transport.kind, attempt)

            var connection: TransportConnection? = null
            val failure: String = try {
                connection = transport.open(endpoint)
                val session = SessionClient(identity, config.handshakeTimeoutMs)
                    .authenticate(connection, pc.pcId, Crypto.unb64(pc.deviceSecretB64))
                val now = clock.nowMillis()
                val current = ActiveSession(connection, session)
                active = current
                attempt = 0
                _state.value = ConnectionState.Connected(session.pcName, endpoint, transport.kind, session.sessionId, now)
                _bridgeStatus.value = BridgeStatus(pcName = session.pcName)
                runCatching { pairing.markConnected(now) }
                if (session.pcName != pc.pcName) runCatching { pairing.save(pc.copy(pcName = session.pcName, lastConnectedAtMillis = now)) }
                runSession(current)
                "Connection lost"
            } catch (e: CancellationException) {
                connection?.close("cancelled")
                throw e
            } catch (e: PairingRevokedException) {
                handleRevoked(e.message ?: "Pairing revoked by the PC")
                return
            } catch (e: RemoteProtocolException) {
                if (e.code == ErrorCodes.NOT_PAIRED || e.code == ErrorCodes.AUTH_FAILED) {
                    _state.value = ConnectionState.AuthenticationFailed(
                        "The Green Access PC no longer recognises this phone. Pair it again.",
                    )
                    cleanupSession(connection)
                    return
                }
                e.message ?: "The PC reported an error"
            } catch (e: ProtocolException) {
                if (e.code == ErrorCodes.AUTH_FAILED) {
                    _state.value = ConnectionState.AuthenticationFailed(e.message ?: "Authentication failed")
                    cleanupSession(connection)
                    return
                }
                e.message ?: "Protocol error"
            } catch (e: TransportException) {
                e.message ?: "Unable to reach the PC"
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            cleanupSession(connection)

            if (!settings.current().autoReconnect) {
                _state.value = ConnectionState.Disconnected(failure)
                userWantsConnection = false
                return
            }
            val backoff = backoffFor(attempt.coerceAtLeast(1))
            _state.value = ConnectionState.Reconnecting(attempt.coerceAtLeast(1), backoff, failure)
            withTimeoutOrNull(backoff) { wake.receive() }
        }
    }

    private fun cleanupSession(connection: TransportConnection?) {
        active = null
        connection?.close("session ended")
        failAllPending()
        _bridgeStatus.value = null
    }

    private suspend fun handleRevoked(message: String) {
        cleanupSession(active?.connection)
        pairing.clear()
        userWantsConnection = false
        _state.value = ConnectionState.AuthenticationFailed(message)
    }

    internal fun backoffFor(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 16)
        val base = (config.initialBackoffMs shl exp).coerceAtMost(config.maxBackoffMs)
        val jitter = if (config.jitterRatio > 0) (base * config.jitterRatio * Random.nextDouble()).toLong() else 0L
        return base + jitter
    }

    private suspend fun runSession(current: ActiveSession) = coroutineScope {
        val heartbeat = launch {
            val interval = current.session.heartbeatIntervalSeconds * 1000L
            while (true) {
                delay(interval)
                ping(current) ?: throw TransportException("The PC stopped responding")
            }
        }
        sendSecure(current, Status(role = "device", appVersion = identity.appVersion, nfcEnabled = true))
        try {
            for (text in current.connection.incoming) {
                val message = ProtocolCodec.decode(text)
                when (message) {
                    is SecureFrame -> handleInner(current, current.session.channel.open(message))
                    is ErrorMessage -> throw RemoteProtocolException(message.code, message.message ?: message.code)
                    else -> throw ProtocolException(ErrorCodes.UNEXPECTED, "Unencrypted message after authentication")
                }
            }
        } finally {
            heartbeat.cancel()
        }
    }

    private suspend fun handleInner(current: ActiveSession, message: ProtocolMessage) {
        when (message) {
            is Ping -> sendSecure(current, Pong(message.id, Instant.ofEpochMilli(clock.nowMillis()).toString()))
            is Pong -> pendingPings.remove(message.id)?.complete(monotonic.elapsedMillis())
            is Status -> if (message.role == "bridge") {
                _bridgeStatus.value = BridgeStatus(
                    pcName = message.pcName ?: current.session.pcName,
                    gymName = message.gymName,
                    greenAccessConnected = message.greenAccessConnected,
                    bridgeVersion = message.bridgeVersion,
                )
            }
            is Ack -> if (message.refType == "nfc_scan") pendingScans[message.refId]?.acknowledged = true
            is NfcResponse -> message.scanId?.let { pendingScans[it]?.result?.complete(message) }
            is ErrorMessage -> message.refId?.let { pendingScans[it]?.result?.complete(message) }
            is Disconnect -> throw TransportException(message.reason?.let { "PC closed the session: $it" } ?: "PC closed the session")
            is Unpair -> throw PairingRevokedException(message.reason ?: "Pairing revoked on the PC")
            else -> sendSecure(current, ErrorMessage(ErrorCodes.UNEXPECTED, "Unexpected message"))
        }
    }

    private suspend fun ping(current: ActiveSession): Long? {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Long>()
        pendingPings[id] = deferred
        val sentAt = monotonic.elapsedMillis()
        return try {
            if (!sendSecure(current, Ping(id, Instant.ofEpochMilli(clock.nowMillis()).toString()))) return null
            withTimeoutOrNull(config.pongTimeoutMs) { deferred.await() }?.let { it - sentAt }
        } finally {
            pendingPings.remove(id)
        }
    }

    private suspend fun sendSecure(current: ActiveSession, message: ProtocolMessage): Boolean = sendMutex.withLock {
        if (active !== current) return@withLock false
        current.connection.send(ProtocolCodec.encode(current.session.channel.seal(message)))
    }

    private fun failAllPending() {
        pendingScans.values.forEach { it.result.complete(null) }
        pendingPings.values.forEach { it.cancel() }
        pendingPings.clear()
    }

    private class PairingRevokedException(message: String) : Exception(message)
}
