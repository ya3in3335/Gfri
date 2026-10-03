package com.greenaccesse.app.presentation

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.greenaccesse.app.GreenAccesseApp
import com.greenaccesse.app.nfc.NfcReaderController
import com.greenaccesse.app.nfc.NfcStatus
import com.greenaccesse.app.presentation.theme.GreenAccesseTheme
import com.greenaccesse.app.settings.AppSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

val LocalNfcStatus = staticCompositionLocalOf { NfcStatus.UNAVAILABLE }

class MainActivity : ComponentActivity() {
    private lateinit var nfc: NfcReaderController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GreenAccesseApp).container
        nfc = NfcReaderController(
            activity = this,
            onTag = container.scans::onTagDiscovered,
            onError = container.scans::onReadFailure,
        )

        // Reader Mode is active only while resumed, logged in, and enabled in settings.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(container.auth.session, container.settings.settings, nfc.status) { session, settings, status ->
                    Triple(session != null, settings, status)
                }.collect { (loggedIn, settings, status) ->
                    nfc.setReaderActive(loggedIn && settings.nfcReaderEnabled && status == NfcStatus.ENABLED)
                    if (settings.keepScreenOn && loggedIn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val nfcStatus by nfc.status.collectAsStateWithLifecycle()
            GreenAccesseTheme(settings.themeMode) {
                CompositionLocalProvider(LocalNfcStatus provides nfcStatus) {
                    AppRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfc.onResume()
    }

    override fun onPause() {
        nfc.onPause()
        super.onPause()
    }
}
