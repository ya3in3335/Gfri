package com.greenaccesse.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.greenaccesse.app.protocol.Protocol
import com.greenaccesse.app.transport.HostValidator
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.Executors

data class DiscoveredBridge(val serviceName: String, val host: String, val port: Int, val pcName: String?)

/**
 * Finds Green Access Bridges on the local network via DNS-SD / mDNS (Android NsdManager), service type
 * "_greenaccess._tcp". This only discovers addresses; trust is established exclusively by pairing.
 */
class BridgeDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    fun discover(): Flow<List<DiscoveredBridge>> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val found = linkedMapOf<String, DiscoveredBridge>()
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val lock = Any()
        val executor = Executors.newSingleThreadExecutor()

        fun publish() = trySend(synchronized(lock) { found.values.toList() })

        fun resolveNext() {
            val next = synchronized(lock) {
                if (resolving || resolveQueue.isEmpty()) return
                resolving = true
                resolveQueue.removeFirst()
            }
            @Suppress("DEPRECATION")
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = hostOf(info)
                    if (host != null && HostValidator.isValidHost(host) && HostValidator.isValidPort(info.port)) {
                        val pcName = info.attributes["pc_name"]?.let { String(it, Charsets.UTF_8) }
                            ?.filter { !it.isISOControl() }?.take(64)
                        synchronized(lock) {
                            found[info.serviceName] = DiscoveredBridge(info.serviceName.take(64), host, info.port, pcName)
                        }
                        publish()
                    }
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("Network discovery failed ($errorCode)"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { resolveQueue.add(serviceInfo) }
                executor.execute { resolveNext() }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { found.remove(serviceInfo.serviceName) }
                publish()
            }
        }
        trySend(emptyList())
        manager.discoverServices(Protocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            executor.shutdown()
        }
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())
            ?.hostAddress?.takeIf { !it.contains(':') }
    }
}
