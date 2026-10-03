package com.greenaccesse.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.greenaccesse.app.nfc.ScanDebouncer
import com.greenaccesse.app.transport.HostValidator
import com.greenaccesse.app.transport.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "green_accesse_settings")

/** Non-secret preferences persisted with Jetpack DataStore. */
class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val store = context.applicationContext.settingsDataStore

    override val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::fromPreferences)

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = sanitize(transform(fromPreferences(prefs)))
            prefs[NFC_ENABLED] = next.nfcReaderEnabled
            prefs[DEBOUNCE] = next.debounceMillis
            prefs[SOUND] = next.soundOnScan
            prefs[VIBRATION] = next.vibrationOnScan
            prefs[KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[AUTO_RECONNECT] = next.autoReconnect
            prefs[TRANSPORT] = next.transport.name
            prefs[HOST] = next.serverHost
            prefs[PORT] = next.serverPort
            prefs[THEME] = next.themeMode.name
        }
    }

    private fun fromPreferences(p: Preferences): AppSettings {
        val d = AppSettings()
        return sanitize(
            AppSettings(
                nfcReaderEnabled = p[NFC_ENABLED] ?: d.nfcReaderEnabled,
                debounceMillis = p[DEBOUNCE] ?: d.debounceMillis,
                soundOnScan = p[SOUND] ?: d.soundOnScan,
                vibrationOnScan = p[VIBRATION] ?: d.vibrationOnScan,
                keepScreenOn = p[KEEP_SCREEN_ON] ?: d.keepScreenOn,
                autoReconnect = p[AUTO_RECONNECT] ?: d.autoReconnect,
                transport = p[TRANSPORT]?.let { runCatching { TransportKind.valueOf(it) }.getOrNull() } ?: d.transport,
                serverHost = p[HOST] ?: d.serverHost,
                serverPort = p[PORT] ?: d.serverPort,
                themeMode = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            ),
        )
    }

    private fun sanitize(s: AppSettings): AppSettings = s.copy(
        debounceMillis = s.debounceMillis.coerceIn(ScanDebouncer.MIN_WINDOW_MS, ScanDebouncer.MAX_WINDOW_MS),
        serverHost = s.serverHost.trim().take(253),
        serverPort = if (HostValidator.isValidPort(s.serverPort)) s.serverPort else AppSettings().serverPort,
    )

    private companion object {
        val NFC_ENABLED = booleanPreferencesKey("nfc_enabled")
        val DEBOUNCE = longPreferencesKey("debounce_ms")
        val SOUND = booleanPreferencesKey("sound")
        val VIBRATION = booleanPreferencesKey("vibration")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val TRANSPORT = stringPreferencesKey("transport")
        val HOST = stringPreferencesKey("server_host")
        val PORT = intPreferencesKey("server_port")
        val THEME = stringPreferencesKey("theme")
    }
}
