package com.greenaccesse.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.greenaccesse.app.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class GreenAccesseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container
        c.appScope.launch {
            c.pairing.load()
            c.history.load()
        }
        // Connect only while an operator is logged in.
        c.appScope.launch {
            c.auth.session.map { it != null }.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) c.connection.connect() else c.connection.shutdown()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver(c))
    }
}

/**
 * Foreground: keep the Bridge connection alive and watch the network.
 * Background: after a short grace period, close the connection. NFC Reader Mode only works in the foreground,
 * so a persistent background connection (and a foreground service) would drain the battery for no benefit.
 */
private class AppLifecycleObserver(private val c: AppContainer) : DefaultLifecycleObserver {
    private var pauseJob: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        pauseJob?.cancel()
        c.networkMonitor.start()
        c.connection.onAppForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        pauseJob?.cancel()
        pauseJob = c.appScope.launch {
            delay(BACKGROUND_GRACE_MS)
            c.connection.onAppBackground()
            c.networkMonitor.stop()
        }
    }

    companion object {
        const val BACKGROUND_GRACE_MS = 60_000L
    }
}
