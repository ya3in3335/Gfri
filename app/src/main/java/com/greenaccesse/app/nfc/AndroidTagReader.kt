package com.greenaccesse.app.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.util.Log
import java.io.IOException

/**
 * Reads the public, non-protected information of a tag using the official Android NFC APIs:
 * UID, technology list, and the NDEF message when the tag exposes one.
 *
 * It never authenticates to protected sectors, never tries keys, and never writes to the tag.
 */
object AndroidTagReader {
    private const val TAG = "AndroidTagReader"

    fun read(tag: Tag): RawTag {
        val id = tag.id ?: ByteArray(0)
        val techs = tag.techList?.toList().orEmpty()
        var ndefError: String? = null
        val ndef = if (techs.contains(Ndef::class.java.name)) {
            try {
                readNdef(tag)
            } catch (e: TagLostException) {
                ndefError = TagReadError.TAG_LOST.message
                null
            } catch (e: Exception) {
                Log.w(TAG, "NDEF read failed: ${e.javaClass.simpleName}")
                ndefError = TagReadError.IO_ERROR.message
                null
            }
        } else null
        return RawTag(id, techs, ndef, ndefError)
    }

    private fun readNdef(tag: Tag): RawNdef? {
        val ndef = Ndef.get(tag) ?: return null
        // The cached message was read by the platform during discovery; no I/O needed.
        var message: NdefMessage? = ndef.cachedNdefMessage
        if (message == null) {
            try {
                ndef.connect()
                message = ndef.ndefMessage
            } catch (e: FormatException) {
                message = null
            } catch (e: IOException) {
                throw e
            } finally {
                runCatching { ndef.close() }
            }
        }
        val records = message?.records?.map { RawNdefRecord(it.tnf, it.type ?: ByteArray(0), it.payload ?: ByteArray(0)) }.orEmpty()
        return RawNdef(ndef.type, ndef.maxSize, ndef.isWritable, records)
    }
}
