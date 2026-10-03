package com.greenaccesse.app.protocol

import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.testing.InMemoryTransport
import com.greenaccesse.app.testing.TestBridge
import com.greenaccesse.app.transport.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val identity = DeviceIdentity("GA-0011223344556677", "Pixel Test", "1.0.0")
    private val bridge = TestBridge(pairingCode = "583921")
    private val transport = InMemoryTransport(bridge)
    private val endpoint = Endpoint("192.168.1.20", 8787)

    @After fun tearDown() = transport.shutdown()

    @Test fun `pairing with the correct code establishes the same secret on both sides`() = runBlocking {
        val outcome = PairingClient(identity).pair(transport.open(endpoint), "583921")
        assertEquals(bridge.pcId, outcome.pcId)
        assertEquals("Front Desk PC", outcome.pcName)
        assertArrayEquals(bridge.devices[identity.deviceId], outcome.deviceSecret)
        assertEquals(bridge.verificationCodes.single(), outcome.verificationCode)
        assertTrue(Regex("^[0-9A-F]{4}-[0-9A-F]{4}$").matches(outcome.verificationCode))
    }

    @Test fun `wrong pairing code is rejected and nothing is stored`() = runBlocking {
        val e = runCatching { PairingClient(identity).pair(transport.open(endpoint), "000000") }.exceptionOrNull()
        assertEquals(ErrorCodes.INVALID_CODE, (e as RemoteProtocolException).code)
        assertTrue(bridge.devices.isEmpty())
    }

    @Test fun `pairing fails when the PC is not in pairing mode`() = runBlocking {
        bridge.pairingCode = null
        val e = runCatching { PairingClient(identity).pair(transport.open(endpoint), "583921") }.exceptionOrNull()
        assertEquals(ErrorCodes.PAIRING_CLOSED, (e as RemoteProtocolException).code)
    }

    @Test fun `malformed codes are refused locally`() = runBlocking {
        listOf("", "12345", "1234567", "12a456").forEach { code ->
            val e = runCatching { PairingClient(identity).pair(transport.open(endpoint), code) }.exceptionOrNull()
            assertTrue(e is ProtocolException)
        }
    }

    @Test fun `each pairing produces a fresh secret`() = runBlocking {
        val a = PairingClient(identity).pair(transport.open(endpoint), "583921").deviceSecret
        val b = PairingClient(identity).pair(transport.open(endpoint), "583921").deviceSecret
        assertTrue(!a.contentEquals(b))
    }

    @Test fun `session authentication succeeds after pairing and fails for unknown devices`() = runBlocking {
        val outcome = PairingClient(identity).pair(transport.open(endpoint), "583921")
        val session = SessionClient(identity).authenticate(transport.open(endpoint), outcome.pcId, outcome.deviceSecret)
        assertEquals("Front Desk PC", session.pcName)

        val stranger = DeviceIdentity("GA-FFFFFFFFFFFFFFFF", "Other", "1.0.0")
        val e = runCatching { SessionClient(stranger).authenticate(transport.open(endpoint), outcome.pcId, Crypto.randomBytes(32)) }.exceptionOrNull()
        assertEquals(ErrorCodes.NOT_PAIRED, (e as RemoteProtocolException).code)
    }

    @Test fun `phone refuses an impostor PC`() = runBlocking {
        val outcome = PairingClient(identity).pair(transport.open(endpoint), "583921")
        // Wrong secret on the phone side == PC that does not know the real secret.
        val e = runCatching { SessionClient(identity).authenticate(transport.open(endpoint), outcome.pcId, Crypto.randomBytes(32)) }.exceptionOrNull()
        assertTrue(e is RemoteProtocolException || e is ProtocolException)
        // A different PC id is refused before any proof is sent.
        val e2 = runCatching { SessionClient(identity).authenticate(transport.open(endpoint), "PC-OTHER", outcome.deviceSecret) }.exceptionOrNull()
        assertEquals(ErrorCodes.AUTH_FAILED, (e2 as ProtocolException).code)
    }
}
