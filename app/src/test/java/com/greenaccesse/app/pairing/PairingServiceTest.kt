package com.greenaccesse.app.pairing

import com.greenaccesse.app.network.ConnectionManager
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.settings.AppSettings
import com.greenaccesse.app.settings.InMemorySettingsRepository
import com.greenaccesse.app.testing.InMemoryTransport
import com.greenaccesse.app.testing.TestBridge
import com.greenaccesse.app.transport.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingServiceTest {
    private val identity = DeviceIdentity("GA-0011223344556677", "Pixel Test", "1.0.0")
    private val bridge = TestBridge()
    private val transport = InMemoryTransport(bridge)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = InMemorySettingsRepository(AppSettings())
    private val repo = InMemoryPairingRepository()
    private val manager = ConnectionManager(scope, repo, settings, identity, { transport })
    private val service = PairingService(identity, repo, settings, manager, { transport })

    @After fun tearDown() {
        manager.shutdown(); scope.cancel(); transport.shutdown()
    }

    private suspend fun awaitConnected() = withTimeout(5_000) { manager.state.first { it is ConnectionState.Connected } }

    @Test fun `successful pairing stores the PC, saves the address and connects`() = runBlocking {
        val r = service.pair("192.168.1.20", 8787, TransportKind.WIFI, "583 921")
        assertTrue(r is PairingResult.Success)
        assertEquals(bridge.pcId, repo.pairedPc.value?.pcId)
        assertEquals("192.168.1.20", settings.current().serverHost)
        awaitConnected()
        Unit
    }

    @Test fun `failed pairing explains the problem and stores nothing`() = runBlocking {
        val r = service.pair("192.168.1.20", 8787, TransportKind.WIFI, "111111")
        assertEquals("Incorrect pairing code. Check the code shown on the PC.", (r as PairingResult.Failure).message)
        assertNull(repo.pairedPc.value)
        assertTrue((service.pair("not a host", 8787, TransportKind.WIFI, "583921") as PairingResult.Failure).message.contains("valid PC address"))
        assertTrue((service.pair("192.168.1.20", 8787, TransportKind.WIFI, "12") as PairingResult.Failure).message.contains("6-digit"))
    }

    @Test fun `failed re-pairing restores the existing connection`() = runBlocking {
        service.pair("192.168.1.20", 8787, TransportKind.WIFI, "583921")
        awaitConnected()
        assertTrue(service.pair("192.168.1.20", 8787, TransportKind.WIFI, "000000") is PairingResult.Failure)
        awaitConnected()
        assertEquals(bridge.pcId, repo.pairedPc.value?.pcId)
    }

    @Test fun `revoke notifies the PC and forgets the pairing`() = runBlocking {
        service.pair("192.168.1.20", 8787, TransportKind.WIFI, "583921")
        awaitConnected()
        service.revoke()
        assertNull(repo.pairedPc.value)
        withTimeout(2_000) { while (bridge.devices.isNotEmpty()) delay(20) }
    }
}
