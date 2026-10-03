package com.greenaccesse.app.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

/** Minimal string key-value persistence abstraction (allows JVM unit tests without Android). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    // commit() (synchronous) is intentional: these are security-relevant values and callers already run off the main thread.
    override fun putString(key: String, value: String) {
        prefs.edit(commit = true) { putString(key, value) }
    }

    override fun remove(key: String) {
        prefs.edit(commit = true) { remove(key) }
    }

    override fun clear() {
        prefs.edit(commit = true) { clear() }
    }
}

class InMemoryStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() = map.clear()
}
