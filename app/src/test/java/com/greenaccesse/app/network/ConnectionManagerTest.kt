package com.greenaccesse.app.network

import com.greenaccesse.app.pairing.InMemoryPairingRepository
import com.greenaccesse.app.pairing.PairedPc
import com.greenaccesse.app.protocol.Ack
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.protocol.NfcResponse
import com.greenaccesse.app.protocol.NfcScanMessage
import com.greenaccesse.app.protocol.PairingClient
import com.greenaccesse.app.protocol.Unpair
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.settings.AppSettings
import com.greenaccesse.app.settings.InMemorySettingsRepository
import com.greenaccesse.app.testing.InMemoryTransport
import com.greenaccesse.app.testing.TestBridge
import com.greenaccesse.app.transport.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ConnectionManagerTest {
    private val identity = DeviceIdentity("GA-0011223344556677", "Pixel Test", "1.0.0")
    private val bridge = TestBridge()
    private val transport = InMemoryTransport(bridge)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = InMemorySettingsRepository(AppSettings(serverHost = "192.168.1.20", serverPort = 8787))
    private val pairing = InMemoryPairingRepository()
    private val config = ConnectionManager.Config(
        scanResponseTimeoutMs = 500,
        pongTimeoutMs = 500,
        initialBackoffMs = 50,
        maxBackoffMs = 200,
        jitterRatio = 0.0,
        handshakeTimeoutMs = 2_000,
    )
    private val manager = ConnectionManager(scope, pairing, settings, identity, { transport }, config = config)

    @After fun tearDown() {
        manager.shutdown()
        scope.cancel()
        transport.shutdown()
    }

    private suspend fun pair() {
        val outcome = PairingClient(identity).pair(transport.open(Endpoint("192.168.1.20", 8787)), "583921")
        pairing.save(PairedPc(outcome.pcId, outcome.pcName, Crypto.b64(outcome.deviceSecret), 0L, outcome.verificationCode))
    }

    private suspend inline fun <reified T : ConnectionState> awaitState(): T =
        withTimeout(5_000) { manager.state.first { it is T } as T }

    private fun scan(uid: String = "04A1B2C3D4E5") = NfcScanMessage(1, UUID.randomUUID().toString(), identity.deviceId, "2026-10-03T10:30:00Z", uid, uid.length / 2, listOf("NfcA"))

    @Test fun `reports NotPaired when no PC is paired`() = runBlocking {
        manager.connect()
        awaitState<ConnectionState.NotPaired>()
        Unit
    }

    @Test fun `connects automatically once pairing is saved`() = runBlocking {
        manager.connect()
        awaitState<ConnectionState.NotPaired>()
        pair()
        val connected = awaitState<ConnectionState.Connected>()
        assertEquals("Front Desk PC", connected.pcName)
        val status = withTimeout(2_000) { manager.bridgeStatus.first { it?.gymName != null } }
        assertEquals("My Gym", status?.gymName)
        assertTrue(pairing.pairedPc.value?.lastConnectedAtMillis != null)
    }

    @Test fun `scan is sent and acknowledged by the bridge`() = runBlocking {
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        val result = manager.sendScan(scan())
        assertEquals(SendResult.Sent("Scan received", "received"), result)
        assertEquals("04A1B2C3D4E5", bridge.scans.single().uid)
    }

    @Test fun `rejected scan is reported as rejected`() = runBlocking {
        bridge.onScan = { listOf(NfcResponse(it.scanId, false, "Unknown card", "unknown_card")) }
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        assertEquals(SendResult.Rejected("Unknown card"), manager.sendScan(scan()))
    }

    @Test fun `ack without final response is Delivered and silence is Timeout`() = runBlocking {
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        bridge.onScan = { listOf(Ack("nfc_scan", it.scanId)) }
        assertEquals(SendResult.Delivered, manager.sendScan(scan()))
        bridge.onScan = { emptyList() }
        assertEquals(SendResult.Timeout, manager.sendScan(scan()))
    }

    @Test fun `sending while disconnected never pretends success`() = runBlocking {
        assertEquals(SendResult.NotConnected, manager.sendScan(scan()))
        assertTrue(bridge.scans.isEmpty())
    }

    @Test fun `test connection measures a real round trip`() = runBlocking {
        assertTrue(manager.testConnection() is TestResult.Failure)
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        assertTrue(manager.testConnection() is TestResult.Success)
        bridge.answerPings = false
        assertTrue(manager.testConnection() is TestResult.Failure)
    }

    @Test fun `reconnects automatically after the connection drops`() = runBlocking {
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        val opens = transport.openCount.get()
        transport.reachable = false
        bridge.dropConnection()
        val reconnecting = awaitState<ConnectionState.Reconnecting>()
        // StateFlow is conflated: with the short test backoff we may observe the first or a later retry.
        assertTrue(reconnecting.lastError in setOf("Connection lost", "Connection refused"))
        transport.reachable = true
        manager.onNetworkAvailable()
        awaitState<ConnectionState.Connected>()
        assertTrue(transport.openCount.get() > opens)
    }

    @Test fun `unreachable PC leads to backoff retries`() = runBlocking {
        pair()
        transport.reachable = false
        manager.connect()
        val r = awaitState<ConnectionState.Reconnecting>()
        assertEquals("Connection refused", r.lastError)
        withTimeout(5_000) { manager.state.first { it is ConnectionState.Reconnecting && it.attempt >= 3 } }
        Unit
    }

    @Test fun `without auto reconnect a failure leaves the manager disconnected`() = runBlocking {
        settings.update { it.copy(autoReconnect = false) }
        pair()
        transport.reachable = false
        manager.connect()
        // The initial state is Disconnected(null); wait for the one produced by the failed attempt.
        val d = withTimeout(5_000) { manager.state.first { it is ConnectionState.Disconnected && it.reason != null } }
        assertEquals("Connection refused", (d as ConnectionState.Disconnected).reason)
    }

    @Test fun `PC that forgot the phone stops retries and asks to re-pair`() = runBlocking {
        pair()
        bridge.devices.clear()
        manager.connect()
        awaitState<ConnectionState.AuthenticationFailed>()
        val opens = transport.openCount.get()
        Thread.sleep(300)
        assertEquals(opens, transport.openCount.get())
    }

    @Test fun `unpair from the PC clears local pairing`() = runBlocking {
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        bridge.sendSecure(Unpair("Removed by administrator"))
        awaitState<ConnectionState.AuthenticationFailed>()
        assertNull(pairing.pairedPc.value)
    }

    @Test fun `user disconnect stays disconnected`() = runBlocking {
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        manager.disconnect()
        assertTrue(manager.state.value is ConnectionState.Disconnected)
        Thread.sleep(300)
        assertTrue(manager.state.value is ConnectionState.Disconnected)
        manager.connect()
        awaitState<ConnectionState.Connected>()
        Unit
    }

    @Test fun `background pauses and foreground resumes`() = runBlocking {
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        manager.onAppBackground()
        assertTrue(manager.state.value is ConnectionState.Disconnected)
        manager.onAppForeground()
        awaitState<ConnectionState.Connected>()
        Unit
    }

    @Test fun `phone revoking pairing notifies the bridge`() = runBlocking {
        pair(); manager.connect(); awaitState<ConnectionState.Connected>()
        manager.sendUnpair()
        withTimeout(2_000) { while (bridge.devices.isNotEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(ConnectionState.NotPaired, manager.state.value)
    }

    @Test fun `backoff grows exponentially and is capped`() {
        val m = ConnectionManager(scope, pairing, settings, identity, { transport }, config = ConnectionManager.Config(initialBackoffMs = 1_000, maxBackoffMs = 30_000, jitterRatio = 0.0))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(m::backoffFor))
    }
}
