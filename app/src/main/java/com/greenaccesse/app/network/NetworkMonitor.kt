package com.greenaccesse.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes local network availability (Wi-Fi / Ethernet / USB tethering) and notifies the [ConnectionManager]
 * so reconnection happens immediately when the network returns. Uses ACCESS_NETWORK_STATE only (no location).
 */
class NetworkMonitor(context: Context, private val onAvailable: () -> Unit, private val onLost: () -> Unit) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _localNetworkAvailable = MutableStateFlow(true)
    val localNetworkAvailable: StateFlow<Boolean> = _localNetworkAvailable.asStateFlow()
    private val networks = mutableSetOf<Network>()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val first = synchronized(networks) { networks.add(network); networks.size == 1 }
            _localNetworkAvailable.value = true
            if (first) onAvailable()
        }

        override fun onLost(network: Network) {
            val none = synchronized(networks) { networks.remove(network); networks.isEmpty() }
            if (none) {
                _localNetworkAvailable.value = false
                onLost()
            }
        }
    }

    fun start() {
        if (registered || cm == null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, callback)
            registered = true
        }
        // Initial state: if nothing is connected the callback never fires onAvailable.
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _localNetworkAvailable.value = caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    fun stop() {
        if (!registered || cm == null) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        synchronized(networks) { networks.clear() }
    }
}
