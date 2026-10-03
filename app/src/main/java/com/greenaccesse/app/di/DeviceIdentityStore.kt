package com.greenaccesse.app.di

import android.os.Build
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.storage.KeyValueStore

/** Creates (once) and returns this installation's random device identifier. No hardware identifiers are used. */
object DeviceIdentityStore {
    private const val KEY = "device.id"

    fun load(store: KeyValueStore, appVersion: String): DeviceIdentity {
        val id = store.getString(KEY) ?: ("GA-" + Crypto.hex(Crypto.randomBytes(8))).also { store.putString(KEY, it) }
        val name = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .filter { !it.isISOControl() }
            .take(64)
            .ifBlank { "Android phone" }
        return DeviceIdentity(id, name, appVersion)
    }
}
