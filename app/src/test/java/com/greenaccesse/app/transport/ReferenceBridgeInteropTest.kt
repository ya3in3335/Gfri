package com.greenaccesse.app.transport

import com.greenaccesse.app.network.ConnectionManager
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.network.SendResult
import com.greenaccesse.app.network.TestResult
import com.greenaccesse.app.pairing.InMemoryPairingRepository
import com.greenaccesse.app.pairing.PairedPc
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.protocol.NfcScanMessage
import com.greenaccesse.app.protocol.PairingClient
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.settings.AppSettings
import com.greenaccesse.app.settings.InMemorySettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Interoperability check against the independent Python reference Bridge (bridge/reference).
 * Skipped unless GA_INTEROP_BRIDGE=host:port is set and the Bridge was started with `--pair-code 583921`.
 * See docs/building.md ("Interoperability test").
 */
class ReferenceBridgeInteropTest {
    @Test fun `phone pairs, connects and sends a scan to the reference bridge`() = runBlocking {
        val target = System.getenv("GA_INTEROP_BRIDGE")
        assumeTrue("GA_INTEROP_BRIDGE not set", !target.isNullOrBlank())
        val (host, port) = target!!.split(":").let { it[0] to it[1].toInt() }
        val endpoint = Endpoint(host, port)
        val identity = DeviceIdentity("GA-" + Crypto.hex(Crypto.randomBytes(8)), "Interop Test Phone", "1.0.0")
        val transport = WebSocketTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val outcome = PairingClient(identity).pair(transport.open(endpoint), "583921")
            println("Paired with ${outcome.pcName} (${outcome.pcId}), security code ${outcome.verificationCode}")
            val pairing = InMemoryPairingRepository(PairedPc(outcome.pcId, outcome.pcName, Crypto.b64(outcome.deviceSecret), 0, outcome.verificationCode))
            val manager = ConnectionManager(scope, pairing, InMemorySettingsRepository(AppSettings(serverHost = host, serverPort = port)), identity, { transport })
            manager.connect()
            withTimeout(5_000) { manager.state.first { it is ConnectionState.Connected } }
            assertTrue(manager.testConnection() is TestResult.Success)

            val scan = NfcScanMessage(1, UUID.randomUUID().toString(), identity.deviceId, "2026-10-03T10:30:00Z", "04A1B2C3D4E5", 6, listOf("NfcA", "Ndef"))
            val result = manager.sendScan(scan)
            assertEquals(SendResult.Sent("Scan received", "received"), result)
            // Retrying the same scan_id must not be processed twice by the Bridge (idempotency).
            assertEquals(SendResult.Sent("Scan received", "received"), manager.sendScan(scan))
            manager.sendUnpair()
        } finally {
            scope.cancel()
        }
    }
}
