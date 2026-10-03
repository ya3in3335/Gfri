package com.greenaccesse.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UidTest {
    @Test fun `bytes are converted to uppercase hex without separators`() {
        val bytes = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte())
        assertEquals("04A1B2C3D4E5", Uid.toHex(bytes))
    }

    @Test fun `leading zero bytes are preserved`() {
        assertEquals("000A0B", Uid.toHex(byteArrayOf(0x00, 0x0A, 0x0B)))
    }

    @Test fun `normalize accepts common separators and lowercase`() {
        assertEquals("04A1B2C3", Uid.normalize("04:a1:b2:c3"))
        assertEquals("04A1B2C3", Uid.normalize(" 04 A1 B2 C3 "))
        assertEquals("04A1B2C3", Uid.normalize("04-a1-b2-c3"))
    }

    @Test fun `normalize rejects invalid values`() {
        assertNull(Uid.normalize(null))
        assertNull(Uid.normalize(""))
        assertNull(Uid.normalize("04A1B")) // odd length
        assertNull(Uid.normalize("04G1")) // not hex
        assertNull(Uid.normalize("04A1;rm -rf /")) // injection attempt
        assertNull(Uid.normalize("00".repeat(17))) // too long
    }

    @Test fun `isValid only accepts canonical form`() {
        assertTrue(Uid.isValid("04A1B2C3D4E5"))
        assertFalse(Uid.isValid("04a1b2c3d4e5"))
        assertFalse(Uid.isValid("04:A1"))
    }

    @Test fun `display format groups bytes`() {
        assertEquals("04 A1 B2", Uid.formatForDisplay("04A1B2"))
    }
}
