package com.greenaccesse.app.protocol

import com.greenaccesse.app.security.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChannelTest {
    private val secret = Crypto.randomBytes(32)
    private val keys = HandshakeCrypto.deriveSessionKeys(secret, "GA-DEV", "PC-1", Crypto.randomBytes(16), Crypto.randomBytes(16))
    private val client = SecureChannel.forClient("S-1", keys)
    private val server = SecureChannel.forServer("S-1", keys)

    private fun fails(block: () -> Unit): Boolean = runCatching(block).exceptionOrNull() is ProtocolException

    @Test fun `messages flow both ways`() {
        val ping = Ping("1", "t")
        assertEquals(ping, server.open(client.seal(ping)))
        val pong = Pong("1", "t")
        assertEquals(pong, client.open(server.seal(pong)))
    }

    @Test fun `replayed frame is rejected`() {
        val frame = client.seal(Ping("1", "t"))
        server.open(frame)
        assertTrue(fails { server.open(frame) })
    }

    @Test fun `reordered frames are rejected`() {
        client.seal(Ping("1", "t"))
        val second = client.seal(Ping("2", "t"))
        assertTrue(fails { server.open(second) })
    }

    @Test fun `tampered frame is rejected`() {
        val frame = client.seal(Ping("1", "t"))
        val data = Crypto.unb64(frame.data).also { it[3] = (it[3].toInt() xor 0x55).toByte() }
        assertTrue(fails { server.open(frame.copy(data = Crypto.b64(data))) })
    }

    @Test fun `reflected frame is rejected`() {
        val frame = client.seal(Ping("1", "t"))
        assertTrue(fails { client.open(frame) })
    }

    @Test fun `frame from another session is rejected`() {
        val other = SecureChannel.forClient("S-2", keys)
        assertTrue(fails { server.open(other.seal(Ping("1", "t"))) })
    }

    @Test fun `wrong device secret cannot decrypt`() {
        val wrong = HandshakeCrypto.deriveSessionKeys(Crypto.randomBytes(32), "GA-DEV", "PC-1", Crypto.randomBytes(16), Crypto.randomBytes(16))
        assertTrue(fails { SecureChannel.forServer("S-1", wrong).open(client.seal(Ping("1", "t"))) })
    }
}
