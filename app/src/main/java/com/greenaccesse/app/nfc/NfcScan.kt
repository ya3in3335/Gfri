package com.greenaccesse.app.nfc

/**
 * Platform-independent snapshot of a detected tag. Created by [AndroidTagReader] from an android.nfc.Tag,
 * and by tests directly. All values are untrusted data read from the card.
 */
data class RawTag(
    val id: ByteArray,
    /** Fully-qualified Android tech class names, e.g. "android.nfc.tech.NfcA". */
    val techList: List<String>,
    val ndef: RawNdef? = null,
    val ndefError: String? = null,
)

data class RawNdef(
    val type: String?,
    val maxSize: Int,
    val writable: Boolean,
    val records: List<RawNdefRecord>,
)

data class RawNdefRecord(val tnf: Short, val type: ByteArray, val payload: ByteArray)

/** Parsed, validated scan ready to be dispatched. */
data class NfcScan(
    val uid: String,
    val uidLength: Int,
    val technologies: List<String>,
    val ndef: NdefInfo?,
)

data class NdefInfo(
    val available: Boolean,
    val type: String?,
    val writable: Boolean,
    val records: List<NdefRecordInfo>,
)

/** NDEF record summary. [text] is only filled for well-known Text/URI records and is display data only, never executed or opened. */
data class NdefRecordInfo(val tnf: Int, val type: String, val payloadLength: Int, val text: String?)

sealed interface TagReadResult {
    data class Success(val scan: NfcScan) : TagReadResult
    data class Failure(val reason: TagReadError) : TagReadResult
}

enum class TagReadError(val message: String) {
    EMPTY_UID("The card did not report a UID."),
    INVALID_UID("The card reported an invalid UID."),
    TAG_LOST("The card was removed too quickly. Hold it still and try again."),
    UNSUPPORTED("This card type is not supported by this phone."),
    IO_ERROR("Unable to read the card."),
}
