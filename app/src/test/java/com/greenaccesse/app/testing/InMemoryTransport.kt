package com.greenaccesse.app.testing

import com.greenaccesse.app.transport.Endpoint
import com.greenaccesse.app.transport.Transport
import com.greenaccesse.app.transport.TransportConnection
import com.greenaccesse.app.transport.TransportException
import com.greenaccesse.app.transport.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Transport whose "network" is a pair of in-memory channels connected to a [TestBridge]. */
class InMemoryTransport(private val bridge: TestBridge) : Transport {
    override val kind = TransportKind.WIFI
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var reachable = true
    val openCount = AtomicInteger()

    override suspend fun open(endpoint: Endpoint): TransportConnection {
        openCount.incrementAndGet()
        if (!reachable) throw TransportException("Connection refused")
        val c2s = Channel<String>(Channel.UNLIMITED)
        val s2c = Channel<String>(Channel.UNLIMITED)
        val client = object : TransportConnection {
            override val incoming: ReceiveChannel<String> = s2c
            override fun send(text: String): Boolean = c2s.trySend(text).isSuccess
            override fun close(reason: String) { c2s.close(); s2c.close() }
        }
        val server = object : ServerLink {
            override val incoming: ReceiveChannel<String> = c2s
            override fun send(text: String) { s2c.trySend(text) }
            override fun close() { s2c.close(); c2s.close() }
        }
        scope.launch { bridge.serve(server) }
        return client
    }

    fun shutdown() = scope.cancel()
}
