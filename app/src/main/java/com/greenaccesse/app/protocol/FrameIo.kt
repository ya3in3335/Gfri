package com.greenaccesse.app.protocol

import com.greenaccesse.app.transport.TransportConnection
import com.greenaccesse.app.transport.TransportException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout

/** Error reported by the Bridge through an "error" message. */
class RemoteProtocolException(val code: String, message: String) : Exception(message)

internal object FrameIo {
    fun send(connection: TransportConnection, message: ProtocolMessage) {
        if (!connection.send(ProtocolCodec.encode(message))) throw TransportException("Connection closed")
    }

    /** Receives and validates the next clear-text frame. Remote "error" frames are raised as [RemoteProtocolException]. */
    suspend fun receive(connection: TransportConnection, timeoutMs: Long): ProtocolMessage {
        val text = try {
            withTimeout(timeoutMs) { connection.incoming.receive() }
        } catch (e: TimeoutCancellationException) {
            throw TransportException("The PC did not answer in time")
        } catch (e: ClosedReceiveChannelException) {
            throw TransportException("The PC closed the connection")
        }
        val message = ProtocolCodec.decode(text)
        if (message is ErrorMessage) throw RemoteProtocolException(message.code, message.message ?: message.code)
        return message
    }

    inline fun <reified T : ProtocolMessage> expect(message: ProtocolMessage): T =
        message as? T ?: throw ProtocolException(ErrorCodes.UNEXPECTED, "Unexpected message from PC")
}
