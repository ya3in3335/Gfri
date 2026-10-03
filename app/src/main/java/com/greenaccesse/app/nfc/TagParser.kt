package com.greenaccesse.app.nfc

/**
 * Converts a [RawTag] into an [NfcScan]. Pure Kotlin so it is fully unit-testable.
 *
 * Security: NFC content is untrusted. This parser only extracts bounded, inert data (hex UID, technology names,
 * truncated text). Nothing read from a tag is ever executed, opened, or interpreted as a command.
 */
object TagParser {
    const val MAX_NDEF_RECORDS = 8
    const val MAX_TEXT_CHARS = 256

    private val TECH_NAMES = mapOf(
        "android.nfc.tech.NfcA" to "NfcA",
        "android.nfc.tech.NfcB" to "NfcB",
        "android.nfc.tech.NfcF" to "NfcF",
        "android.nfc.tech.NfcV" to "NfcV",
        "android.nfc.tech.IsoDep" to "IsoDep",
        "android.nfc.tech.MifareClassic" to "MifareClassic",
        "android.nfc.tech.MifareUltralight" to "MifareUltralight",
        "android.nfc.tech.Ndef" to "Ndef",
        "android.nfc.tech.NdefFormatable" to "NdefFormatable",
        "android.nfc.tech.NfcBarcode" to "NfcBarcode",
    )

    fun technologyName(androidClass: String): String =
        TECH_NAMES[androidClass] ?: androidClass.substringAfterLast('.').filter { it.isLetterOrDigit() }.take(32)

    fun parse(tag: RawTag): TagReadResult {
        if (tag.id.isEmpty()) return TagReadResult.Failure(TagReadError.EMPTY_UID)
        if (tag.id.size > Uid.MAX_BYTES) return TagReadResult.Failure(TagReadError.INVALID_UID)
        val uid = Uid.toHex(tag.id)
        val techs = tag.techList.map(::technologyName).filter { it.isNotEmpty() }.distinct()
        val ndefInfo = tag.ndef?.let { ndef ->
            NdefInfo(
                available = true,
                type = ndef.type?.let(::sanitize),
                writable = ndef.writable,
                records = ndef.records.take(MAX_NDEF_RECORDS).map(::recordInfo),
            )
        }
        return TagReadResult.Success(NfcScan(uid, tag.id.size, techs, ndefInfo))
    }

    private fun recordInfo(r: RawNdefRecord): NdefRecordInfo {
        val type = sanitize(String(r.type, Charsets.US_ASCII))
        val text = when {
            r.tnf == TNF_WELL_KNOWN && r.type.contentEquals(RTD_TEXT) -> decodeText(r.payload)
            r.tnf == TNF_WELL_KNOWN && r.type.contentEquals(RTD_URI) -> decodeUri(r.payload)
            else -> null
        }
        return NdefRecordInfo(r.tnf.toInt(), type, r.payload.size, text)
    }

    /** NFC Forum Text RTD: status byte (bit7 = UTF-16, bits0-5 = language length), language, text. */
    internal fun decodeText(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt()
        val langLen = status and 0x3F
        if (1 + langLen > payload.size) return null
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        return sanitize(String(payload, 1 + langLen, payload.size - 1 - langLen, charset))
    }

    /** NFC Forum URI RTD: prefix code + remainder. Returned as plain text for display; never opened. */
    internal fun decodeUri(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val code = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrElse(code) { "" }
        return sanitize(prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8))
    }

    /** Removes control characters and bounds the length. */
    internal fun sanitize(text: String): String =
        text.filter { !it.isISOControl() }.take(MAX_TEXT_CHARS)

    private const val TNF_WELL_KNOWN: Short = 0x01
    private val RTD_TEXT = byteArrayOf(0x54) // "T"
    private val RTD_URI = byteArrayOf(0x55) // "U"
    private val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
        "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://",
        "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )
}
