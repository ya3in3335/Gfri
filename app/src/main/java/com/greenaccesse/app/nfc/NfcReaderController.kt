package com.greenaccesse.app.nfc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NfcStatus { UNAVAILABLE, DISABLED, ENABLED }

/**
 * Wraps Android NFC Reader Mode for one Activity.
 *
 * Reader Mode (NfcAdapter.enableReaderMode) gives the foreground activity exclusive access to tags, disables
 * Android Beam/HCE interference and lets us poll every technology. Android only delivers tags to a resumed
 * activity in this mode, so the controller is enabled in onResume and always disabled in onPause (no leaks).
 */
class NfcReaderController(
    private val activity: Activity,
    private val onTag: (RawTag) -> Unit,
    private val onError: (TagReadError) -> Unit,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<NfcStatus> = _status.asStateFlow()

    private var readerEnabled = false
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) refreshStatus()
        }
    }

    private val callback = NfcAdapter.ReaderCallback { tag: Tag ->
        // Called on a binder thread: safe for blocking tag I/O.
        try {
            onTag(AndroidTagReader.read(tag))
        } catch (e: SecurityException) {
            onError(TagReadError.TAG_LOST) // tag went out of range ("Tag is out of date")
        } catch (e: Exception) {
            Log.w(TAG, "Tag read failed: ${e.javaClass.simpleName}")
            onError(TagReadError.IO_ERROR)
        }
    }

    fun onResume() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                activity,
                stateReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        refreshStatus()
    }

    fun onPause() {
        disableReader()
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
    }

    fun setReaderActive(active: Boolean) {
        if (active) enableReader() else disableReader()
    }

    private fun enableReader() {
        val nfc = adapter ?: return
        if (readerEnabled || !nfc.isEnabled) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS) }
        try {
            nfc.enableReaderMode(activity, callback, flags, extras)
            readerEnabled = true
        } catch (e: Exception) {
            Log.w(TAG, "enableReaderMode failed: ${e.javaClass.simpleName}")
        }
    }

    private fun disableReader() {
        if (!readerEnabled) return
        runCatching { adapter?.disableReaderMode(activity) }
        readerEnabled = false
    }

    private fun refreshStatus() {
        val s = currentStatus()
        _status.value = s
        if (s != NfcStatus.ENABLED) readerEnabled = false
    }

    private fun currentStatus(): NfcStatus = when {
        adapter == null -> NfcStatus.UNAVAILABLE
        adapter.isEnabled -> NfcStatus.ENABLED
        else -> NfcStatus.DISABLED
    }

    private companion object {
        const val TAG = "NfcReaderController"
        const val PRESENCE_CHECK_DELAY_MS = 750
    }
}
