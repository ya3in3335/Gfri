package com.greenaccesse.app.transport

import com.greenaccesse.app.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket transport (OkHttp). Connects to ws://host:port/green-access/v1.
 *
 * The link itself is plain WebSocket on the local network; confidentiality and integrity are provided by the
 * application-layer handshake + AES-GCM secure channel (see docs/protocol.md), so no certificate setup is needed
 * on the gym PC. Any system HTTP proxy is bypassed: traffic never leaves the LAN.
 */
open class WebSocketTransport(
    override val kind: TransportKind = TransportKind.WIFI,
    private val connectTimeoutMs: Long = 6_000,
    client: OkHttpClient? = null,
) : Transport {

    private val client: OkHttpClient = client ?: SHARED_CLIENT.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        /** One shared client so all transports reuse the same dispatcher and thread pools. */
        val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    override suspend fun open(endpoint: Endpoint): TransportConnection {
        HostValidator.validate(endpoint)
        val url = "http://${endpoint.host}:${endpoint.port}${Protocol.WS_PATH}"
        val request = Request.Builder().url(url).header("User-Agent", "GreenAccesse").build()
        val connection = OkHttpConnection()
        val socket = client.newWebSocket(request, connection.listener)
        connection.socket = socket
        try {
            withTimeout(connectTimeoutMs + 1_000) { connection.opened.await() }
        } catch (e: TimeoutCancellationException) {
            socket.cancel()
            throw TransportException("Timed out connecting to $endpoint")
        } catch (e: TransportException) {
            socket.cancel()
            throw e
        } catch (e: Throwable) {
            socket.cancel()
            throw e
        }
        return connection
    }

    private class OkHttpConnection : TransportConnection {
        @Volatile var socket: WebSocket? = null
        val opened = CompletableDeferred<Unit>()
        private val channel = Channel<String>(capacity = 256)
        private val closed = AtomicBoolean(false)
        override val incoming: ReceiveChannel<String> get() = channel

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > Protocol.MAX_FRAME_BYTES) {
                    fail(webSocket, TransportException("Frame too large"))
                    return
                }
                if (channel.trySend(text).isFailure) fail(webSocket, TransportException("Receive buffer overflow"))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                fail(webSocket, TransportException("Binary frames are not supported"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed.set(true)
                channel.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.set(true)
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = when {
                    response != null && response.code == 404 -> TransportException("The PC answered but is not a Green Access Bridge")
                    response != null -> TransportException("Bridge rejected the connection (HTTP ${response.code})")
                    else -> TransportException(t.message ?: "Connection failed", t)
                }
                closed.set(true)
                opened.completeExceptionally(error)
                channel.close(error)
            }
        }

        private fun fail(webSocket: WebSocket, error: TransportException) {
            closed.set(true)
            webSocket.close(1009, error.message?.take(100))
            channel.close(error)
        }

        override fun send(text: String): Boolean = !closed.get() && socket?.send(text) == true

        override fun close(reason: String) {
            if (closed.compareAndSet(false, true)) {
                socket?.close(1000, reason.take(100))
            }
            channel.close()
        }
    }
}

/**
 * USB transport built on Android Debug Bridge port reversal: after `adb reverse tcp:8787 tcp:8787` on the PC,
 * the phone reaches the Bridge at 127.0.0.1 through the USB cable. Requires USB debugging; Wi-Fi remains the
 * primary transport. A future version can replace this with an Android Open Accessory implementation behind the same interface.
 */
class UsbAdbTransport(client: OkHttpClient? = null) : WebSocketTransport(TransportKind.USB_ADB, client = client) {
    override fun resolve(configured: Endpoint): Endpoint = Endpoint(LOOPBACK, configured.port)

    companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

object TransportFactory {
    fun create(kind: TransportKind): Transport = when (kind) {
        TransportKind.WIFI -> WebSocketTransport(TransportKind.WIFI)
        TransportKind.USB_ADB -> UsbAdbTransport()
    }
}
