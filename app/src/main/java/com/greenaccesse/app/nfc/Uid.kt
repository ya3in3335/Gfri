package com.greenaccesse.app.nfc

import java.util.Locale

/**
 * UID helpers. The canonical UID format used everywhere in Green Accesse and on the wire is
 * uppercase hexadecimal without separators, in the byte order reported by the Android NFC stack (e.g. "04A1B2C3D4E5").
 * Any conversion required by the existing Green Access database (reversed bytes, decimal, ...) is done by the Bridge.
 */
object Uid {
    const val MIN_BYTES = 1
    const val MAX_BYTES = 16

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4]); append(HEX[v and 0x0F])
        }
    }

    /**
     * Normalizes a user/externally supplied UID string ("04:a1:b2", "04 A1 B2", "04-a1-b2") to canonical form.
     * @return the canonical UID or null if the value is not a valid UID.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim().filterNot { it == ':' || it == '-' || it == ' ' }.uppercase(Locale.ROOT)
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val bytes = cleaned.length / 2
        if (bytes !in MIN_BYTES..MAX_BYTES) return null
        return cleaned
    }

    fun isValid(uid: String?): Boolean = uid != null && normalize(uid) == uid

    /** Human-friendly grouping for display only: "04 A1 B2 C3". */
    fun formatForDisplay(uid: String): String = uid.chunked(2).joinToString(" ")

    private val HEX = "0123456789ABCDEF".toCharArray()
}
