package com.greenaccesse.app.settings

import com.greenaccesse.app.nfc.ScanDebouncer
import com.greenaccesse.app.protocol.Protocol
import com.greenaccesse.app.transport.Endpoint
import com.greenaccesse.app.transport.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class AppSettings(
    // NFC
    val nfcReaderEnabled: Boolean = true,
    val debounceMillis: Long = ScanDebouncer.DEFAULT_WINDOW_MS,
    val soundOnScan: Boolean = true,
    val vibrationOnScan: Boolean = true,
    val keepScreenOn: Boolean = false,
    // Connection
    val autoReconnect: Boolean = true,
    val transport: TransportKind = TransportKind.WIFI,
    val serverHost: String = "",
    val serverPort: Int = Protocol.DEFAULT_PORT,
    // Appearance
    val themeMode: ThemeMode = ThemeMode.DARK,
) {
    val endpoint: Endpoint? get() = serverHost.takeIf { it.isNotBlank() }?.let { Endpoint(it, serverPort) }
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()
    override suspend fun current(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}
