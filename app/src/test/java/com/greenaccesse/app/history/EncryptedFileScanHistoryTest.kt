package com.greenaccesse.app.history

import com.greenaccesse.app.testing.TestCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedFileScanHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = TestCipher()

    @Test fun `records persist encrypted, are capped and can be cleared`() = runBlocking {
        val file = tmp.root.resolve("history.bin")
        val h = EncryptedFileScanHistory(file, cipher, maxEntries = 3, ioDispatcher = Dispatchers.Unconfined)
        h.load()
        repeat(5) { h.add(ScanRecord("id$it", it.toLong(), "04A1B2C$it", ScanStatus.PENDING, "Connected")) }
        h.updateStatus("id4", ScanStatus.SENT)
        assertEquals(listOf("id4", "id3", "id2"), h.records.value.map { it.id })
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("04A1B2C"))

        val reloaded = EncryptedFileScanHistory(file, cipher, ioDispatcher = Dispatchers.Unconfined).also { it.load() }
        assertEquals(ScanStatus.SENT, reloaded.records.value.first().status)

        reloaded.clear()
        assertTrue(reloaded.records.value.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `corrupted file is discarded instead of crashing`() = runBlocking {
        val file = tmp.root.resolve("history.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val h = EncryptedFileScanHistory(file, cipher, ioDispatcher = Dispatchers.Unconfined)
        h.load()
        assertTrue(h.records.value.isEmpty())
    }
}
