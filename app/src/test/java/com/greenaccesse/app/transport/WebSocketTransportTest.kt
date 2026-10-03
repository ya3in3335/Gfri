package com.greenaccesse.app.transport

import com.greenaccesse.app.network.ConnectionManager
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.network.SendResult
import com.greenaccesse.app.pairing.InMemoryPairingRepository
import com.greenaccesse.app.pairing.PairedPc
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.protocol.NfcScanMessage
import com.greenaccesse.app.protocol.PairingClient
import com.greenaccesse.app.protocol.Protocol
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.settings.AppSettings
import com.greenaccesse.app.settings.InMemorySettingsRepository
import com.greenaccesse.app.testing.ServerLink
import com.greenaccesse.app.testing.TestBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** End-to-end over a real localhost WebSocket (OkHttp client <-> MockWebServer running the test Bridge). */
class WebSocketTransportTest {
    private val server = MockWebServer()
    private val bridge = TestBridge()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val identity = DeviceIdentity("GA-0011223344556677", "Pixel Test", "1.0.0")

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != Protocol.WS_PATH) return MockResponse().setResponseCode(404)
                val incoming = Channel<String>(Channel.UNLIMITED)
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val link = object : ServerLink {
                            override val incoming = incoming
                            override fun send(text: String) { webSocket.send(text) }
                            override fun close() { webSocket.close(1000, null) }
                        }
                        scope.launch { bridge.serve(link) }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) { incoming.trySend(text) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { incoming.close() }
                })
            }
        }
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun endpoint() = Endpoint(server.hostName.let { if (it == "localhost") "127.0.0.1" else it }, server.port)

    @Test fun `pair, authenticate and send a scan over a real WebSocket`() = runBlocking {
        val transport = WebSocketTransport()
        val outcome = PairingClient(identity).pair(transport.open(endpoint()), "583921")

        val pairing = InMemoryPairingRepository(PairedPc(outcome.pcId, outcome.pcName, Crypto.b64(outcome.deviceSecret), 0, outcome.verificationCode))
        val settings = InMemorySettingsRepository(AppSettings(serverHost = endpoint().host, serverPort = endpoint().port))
        val manager = ConnectionManager(scope, pairing, settings, identity, { transport })
        manager.connect()
        withTimeout(5_000) { manager.state.first { it is ConnectionState.Connected } }

        val scan = NfcScanMessage(1, UUID.randomUUID().toString(), identity.deviceId, "2026-10-03T10:30:00Z", "04A1B2C3D4E5", 6, listOf("NfcA", "Ndef"))
        assertEquals(SendResult.Sent("Scan received", "received"), manager.sendScan(scan))
        assertEquals("04A1B2C3D4E5", bridge.scans.single().uid)
        manager.shutdown()
    }

    @Test fun `unreachable endpoint raises TransportException`() = runBlocking {
        val closed = MockWebServer().apply { start() }
        val port = closed.port
        closed.shutdown()
        val e = runCatching { WebSocketTransport(connectTimeoutMs = 1_000).open(Endpoint("127.0.0.1", port)) }.exceptionOrNull()
        assertTrue(e is TransportException)
    }

    @Test fun `non bridge HTTP server is reported clearly`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        val e = runCatching { WebSocketTransport().open(endpoint()) }.exceptionOrNull()
        assertEquals("The PC answered but is not a Green Access Bridge", e?.message)
    }

    @Test fun `invalid endpoints are rejected before connecting`() = runBlocking {
        listOf(Endpoint("", 8787), Endpoint("192.168.1.300", 8787), Endpoint("pc/../evil", 8787), Endpoint("host", 0), Endpoint("host", 70000)).forEach {
            assertTrue(runCatching { WebSocketTransport().open(it) }.exceptionOrNull() is TransportException)
        }
    }

    @Test fun `host validation`() {
        assertTrue(HostValidator.isValidHost("192.168.1.20"))
        assertTrue(HostValidator.isValidHost("GYM-PC.local"))
        assertFalse(HostValidator.isValidHost("http://192.168.1.20"))
        assertFalse(HostValidator.isValidHost("1.2.3"))
        assertFalse(HostValidator.isValidHost("a b"))
    }

    @Test fun `usb transport always dials loopback`() {
        assertEquals(Endpoint("127.0.0.1", 8787), UsbAdbTransport().resolve(Endpoint("192.168.1.20", 8787)))
        assertEquals(TransportKind.USB_ADB, TransportFactory.create(TransportKind.USB_ADB).kind)
        assertEquals(TransportKind.WIFI, TransportFactory.create(TransportKind.WIFI).kind)
    }
}
