package com.greenaccesse.app.di

import android.content.Context
import com.greenaccesse.app.BuildConfig
import com.greenaccesse.app.auth.AuthService
import com.greenaccesse.app.auth.LocalAuthService
import com.greenaccesse.app.domain.ScanCoordinator
import com.greenaccesse.app.history.EncryptedFileScanHistory
import com.greenaccesse.app.history.ScanHistoryRepository
import com.greenaccesse.app.network.BridgeDiscovery
import com.greenaccesse.app.network.ConnectionManager
import com.greenaccesse.app.network.NetworkMonitor
import com.greenaccesse.app.nfc.ScanDebouncer
import com.greenaccesse.app.pairing.PairingRepository
import com.greenaccesse.app.pairing.PairingService
import com.greenaccesse.app.pairing.SecurePairingRepository
import com.greenaccesse.app.presentation.AndroidScanFeedback
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.security.KeystoreSecretCipher
import com.greenaccesse.app.settings.DataStoreSettingsRepository
import com.greenaccesse.app.settings.SettingsRepository
import com.greenaccesse.app.storage.SecureStore
import com.greenaccesse.app.storage.SharedPreferencesStore
import com.greenaccesse.app.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency injection: one instance of each long-lived component per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Application-wide scope; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val cipher = KeystoreSecretCipher()
    private val plainPrefs = SharedPreferencesStore(appContext.getSharedPreferences("green_accesse", Context.MODE_PRIVATE))
    private val securePrefs = SecureStore(
        SharedPreferencesStore(appContext.getSharedPreferences("green_accesse_secure", Context.MODE_PRIVATE)),
        cipher,
    )

    val identity: DeviceIdentity = DeviceIdentityStore.load(plainPrefs, BuildConfig.VERSION_NAME)
    val settings: SettingsRepository = DataStoreSettingsRepository(appContext)
    val auth: AuthService = LocalAuthService(securePrefs)
    val pairing: PairingRepository = SecurePairingRepository(securePrefs)
    val history: ScanHistoryRepository = EncryptedFileScanHistory(File(appContext.filesDir, "scan_history.bin"), cipher)
    val discovery = BridgeDiscovery(appContext)

    val connection = ConnectionManager(
        scope = appScope,
        pairing = pairing,
        settings = settings,
        identity = identity,
        transportFactory = TransportFactory::create,
    )

    val pairingService = PairingService(identity, pairing, settings, connection, TransportFactory::create)

    val networkMonitor = NetworkMonitor(appContext, connection::onNetworkAvailable, connection::onNetworkLost)

    val scans = ScanCoordinator(
        scope = appScope,
        connection = connection,
        history = history,
        settings = settings,
        identity = identity,
        debouncer = ScanDebouncer(),
        feedback = AndroidScanFeedback(appContext),
    )

    /** Forgot-password reset for the local account: wipes credentials, pairing and history so nothing can be reused. */
    suspend fun wipeAllLocalData() {
        connection.shutdown()
        pairing.clear()
        history.clear()
        auth.resetAccount()
    }
}
