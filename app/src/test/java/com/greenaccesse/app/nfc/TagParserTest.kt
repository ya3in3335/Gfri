package com.greenaccesse.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagParserTest {
    private val uid7 = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0x80.toByte())

    @Test fun `extracts uid and maps technologies`() {
        val tag = RawTag(
            uid7,
            listOf("android.nfc.tech.NfcA", "android.nfc.tech.MifareUltralight", "android.nfc.tech.Ndef", "android.nfc.tech.NfcA"),
        )
        val scan = (TagParser.parse(tag) as TagReadResult.Success).scan
        assertEquals("04A1B2C3D4E580", scan.uid)
        assertEquals(7, scan.uidLength)
        assertEquals(listOf("NfcA", "MifareUltralight", "Ndef"), scan.technologies)
        assertNull(scan.ndef)
    }

    @Test fun `all supported technologies have short names`() {
        val all = listOf("NfcA", "NfcB", "NfcF", "NfcV", "IsoDep", "MifareClassic", "MifareUltralight", "Ndef", "NdefFormatable")
        all.forEach { assertEquals(it, TagParser.technologyName("android.nfc.tech.$it")) }
    }

    @Test fun `empty uid is a read failure`() {
        val r = TagParser.parse(RawTag(ByteArray(0), listOf("android.nfc.tech.NfcA")))
        assertEquals(TagReadError.EMPTY_UID, (r as TagReadResult.Failure).reason)
    }

    @Test fun `oversized uid is rejected`() {
        val r = TagParser.parse(RawTag(ByteArray(17) { 1 }, emptyList()))
        assertEquals(TagReadError.INVALID_UID, (r as TagReadResult.Failure).reason)
    }

    @Test fun `decodes NDEF text record`() {
        val payload = byteArrayOf(0x02) + "en".toByteArray() + "Member card".toByteArray()
        val tag = RawTag(uid7, listOf("android.nfc.tech.Ndef"), RawNdef("org.nfcforum.ndef.type2", 137, false, listOf(RawNdefRecord(1, byteArrayOf(0x54), payload))))
        val ndef = (TagParser.parse(tag) as TagReadResult.Success).scan.ndef!!
        assertTrue(ndef.available)
        assertEquals("Member card", ndef.records.single().text)
        assertEquals("T", ndef.records.single().type)
    }

    @Test fun `URI record is decoded as inert text only`() {
        val payload = byteArrayOf(0x04) + "evil.example/run.ps1".toByteArray()
        val tag = RawTag(uid7, emptyList(), RawNdef(null, 100, true, listOf(RawNdefRecord(1, byteArrayOf(0x55), payload))))
        val rec = (TagParser.parse(tag) as TagReadResult.Success).scan.ndef!!.records.single()
        assertEquals("https://evil.example/run.ps1", rec.text)
    }

    @Test fun `control characters are stripped and text is bounded`() {
        val text = "A\u0000B\nC\u001b[31m" + "x".repeat(1000)
        val payload = byteArrayOf(0x00) + text.toByteArray()
        val tag = RawTag(uid7, emptyList(), RawNdef(null, 2000, true, listOf(RawNdefRecord(1, byteArrayOf(0x54), payload))))
        val decoded = (TagParser.parse(tag) as TagReadResult.Success).scan.ndef!!.records.single().text!!
        assertTrue(decoded.startsWith("ABC[31m"))
        assertTrue(decoded.none { it.isISOControl() })
        assertEquals(TagParser.MAX_TEXT_CHARS, decoded.length)
    }

    @Test fun `record count is capped`() {
        val records = List(20) { RawNdefRecord(2, "text/plain".toByteArray(), ByteArray(3)) }
        val tag = RawTag(uid7, emptyList(), RawNdef(null, 2000, true, records))
        val ndef = (TagParser.parse(tag) as TagReadResult.Success).scan.ndef!!
        assertEquals(TagParser.MAX_NDEF_RECORDS, ndef.records.size)
        assertNull(ndef.records.first().text) // MIME payloads are never decoded
    }

    @Test fun `malformed text record does not crash`() {
        assertNull(TagParser.decodeText(byteArrayOf(0x3F)))
        assertNull(TagParser.decodeText(ByteArray(0)))
    }
}
