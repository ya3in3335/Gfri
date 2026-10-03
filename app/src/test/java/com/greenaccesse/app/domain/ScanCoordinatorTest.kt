package com.greenaccesse.app.domain

import com.greenaccesse.app.history.InMemoryScanHistory
import com.greenaccesse.app.history.ScanStatus
import com.greenaccesse.app.network.ConnectionManager
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.nfc.RawTag
import com.greenaccesse.app.nfc.ScanDebouncer
import com.greenaccesse.app.pairing.InMemoryPairingRepository
import com.greenaccesse.app.pairing.PairedPc
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.protocol.PairingClient
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.settings.AppSettings
import com.greenaccesse.app.settings.InMemorySettingsRepository
import com.greenaccesse.app.testing.FakeMonotonicClock
import com.greenaccesse.app.testing.InMemoryTransport
import com.greenaccesse.app.testing.RecordingFeedback
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
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCoordinatorTest {
    private val identity = DeviceIdentity("GA-0011223344556677", "Pixel Test", "1.0.0")
    private val bridge = TestBridge()
    private val transport = InMemoryTransport(bridge)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = InMemorySettingsRepository(AppSettings(serverHost = "192.168.1.20"))
    private val pairing = InMemoryPairingRepository()
    private val history = InMemoryScanHistory()
    private val clock = FakeMonotonicClock(5_000)
    private val feedback = RecordingFeedback()
    private val manager = ConnectionManager(scope, pairing, settings, identity, { transport })
    private val coordinator = ScanCoordinator(scope, manager, history, settings, identity, ScanDebouncer(1000, clock), feedback)

    private val tag = RawTag(byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()), listOf("android.nfc.tech.NfcA"))

    @After fun tearDown() {
        manager.shutdown(); scope.cancel(); transport.shutdown()
    }

    private suspend fun awaitFinal(): ScannerState = withTimeout(5_000) {
        coordinator.state.first { it is ScannerState.Sent || it is ScannerState.Failed }
    }

    @Test fun `scan while disconnected is recorded as failed and not sent`() = runBlocking {
        coordinator.onTagDiscovered(tag)
        val s = awaitFinal() as ScannerState.Failed
        assertEquals(ScanStatus.FAILED_DISCONNECTED, s.status)
        val record = history.records.value.single()
        assertEquals("04A1B2C3", record.uid)
        assertEquals(ScanStatus.FAILED_DISCONNECTED, record.status)
        assertTrue(bridge.scans.isEmpty())
    }

    @Test fun `scan while connected reaches the bridge once despite repeated detections`() = runBlocking {
        val o = PairingClient(identity).pair(transport.open(Endpoint("192.168.1.20", 8787)), "583921")
        pairing.save(PairedPc(o.pcId, o.pcName, Crypto.b64(o.deviceSecret), 0, o.verificationCode))
        manager.connect()
        withTimeout(5_000) { manager.state.first { it is ConnectionState.Connected } }

        coordinator.onTagDiscovered(tag)
        clock.now += 200
        coordinator.onTagDiscovered(tag) // card still on the phone
        clock.now += 200
        coordinator.onTagDiscovered(tag)
        val s = awaitFinal() as ScannerState.Sent
        assertEquals("04A1B2C3", s.uid)
        assertEquals(ScanStatus.SENT, history.records.value.single().status)
        assertEquals("Connected", history.records.value.single().connectionStatus)
        assertEquals(1, bridge.scans.size)
        assertEquals(listOf("NfcA"), bridge.scans.single().technologies)
        assertTrue(feedback.events.contains("detected"))
    }

    @Test fun `unreadable tag shows a read error`() = runBlocking {
        coordinator.onTagDiscovered(RawTag(ByteArray(0), emptyList()))
        assertTrue(coordinator.state.value is ScannerState.ReadError)
        assertTrue(history.records.value.isEmpty())
    }
}
