package com.greenaccesse.app.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolCodecTest {
    private val scan = NfcScanMessage(
        protocolVersion = 1,
        scanId = "5f0c6a0e-6f43-4f0e-9a39-1f5e2b7c9d10",
        deviceId = "GA-0011223344556677",
        timestamp = "2026-10-03T10:30:00Z",
        uid = "04A1B2C3D4E5",
        uidLength = 6,
        technologies = listOf("NfcA", "Ndef"),
    )

    @Test fun `nfc_scan uses documented snake_case wire format`() {
        val obj = Json.parseToJsonElement(ProtocolCodec.encode(scan)).jsonObject
        assertEquals("nfc_scan", obj["type"]!!.jsonPrimitive.content)
        assertEquals("1", obj["protocol_version"]!!.jsonPrimitive.content)
        assertEquals("GA-0011223344556677", obj["device_id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-03T10:30:00Z", obj["timestamp"]!!.jsonPrimitive.content)
        assertEquals("04A1B2C3D4E5", obj["uid"]!!.jsonPrimitive.content)
        assertEquals(listOf("NfcA", "Ndef"), obj["technologies"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `messages round trip`() {
        val messages = listOf(
            scan, Ping("p1", "2026-10-03T10:30:00Z"), Pong("p1", "2026-10-03T10:30:00Z"),
            Ack("nfc_scan", scan.scanId), NfcResponse(scan.scanId, true, "Scan received", "received"),
            Status(role = "bridge", pcName = "PC", gymName = "My Gym", greenAccessConnected = true),
            Disconnect("bye"), Unpair("revoked"), ErrorMessage(ErrorCodes.MALFORMED, "bad"),
        )
        messages.forEach { assertEquals(it, ProtocolCodec.decode(ProtocolCodec.encode(it))) }
    }

    @Test fun `server response from the documentation is accepted`() {
        val m = ProtocolCodec.decode("""{"type":"nfc_response","success":true,"message":"Scan received"}""")
        assertEquals(NfcResponse(null, true, "Scan received"), m)
    }

    @Test fun `unknown fields are ignored for forward compatibility`() {
        val m = ProtocolCodec.decode("""{"type":"pong","id":"x","timestamp":"t","future_field":42}""")
        assertTrue(m is Pong)
    }

    @Test fun `malformed messages are rejected`() {
        val bad = listOf(
            "not json",
            "{}",
            """{"type":"launch_missiles"}""",
            """{"type":"ping"}""",
            """{"type":"nfc_scan","protocol_version":1,"scan_id":"a","device_id":"d","timestamp":"t","uid":"04;calc.exe","uid_length":2,"technologies":[]}""",
            """{"type":"nfc_scan","protocol_version":1,"scan_id":"a","device_id":"d","timestamp":"t","uid":"04A1","uid_length":9,"technologies":[]}""",
            """{"type":"nfc_scan","protocol_version":1,"scan_id":"a b","device_id":"d","timestamp":"t","uid":"04A1","uid_length":2,"technologies":[]}""",
            """{"type":"error","code":"x","message":"line\u0000break"}""",
            """{"type":"secure","seq":1,"nonce":"AAAA","data":"AAAA"}""",
            """{"type":"pair_result","success":true}""",
        )
        bad.forEach { text ->
            try {
                ProtocolCodec.decode(text)
                fail("Accepted malformed message: $text")
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }

    @Test fun `unsupported protocol version is reported`() {
        val e = runCatching { ProtocolCodec.decode(ProtocolCodec.encode(scan.copy(protocolVersion = 2))) }.exceptionOrNull()
        assertEquals(ErrorCodes.UNSUPPORTED_VERSION, (e as ProtocolException).code)
    }

    @Test fun `oversized frames are rejected before parsing`() {
        val huge = """{"type":"error","code":"x","message":"${"a".repeat(Protocol.MAX_FRAME_BYTES)}"}"""
        val e = runCatching { ProtocolCodec.decode(huge) }.exceptionOrNull()
        assertTrue(e is ProtocolException)
    }
}
