package com.glancemap.glancemapwearos.core.service.transfer.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Retains Wi-Fi for the operation and observes the app default network for fallback/VPN routing. */
internal class DownloadNetworkLease(
    context: Context,
    private val onEvent: (event: String, detail: String) -> Unit,
    private val onWifiReady: () -> Unit = {},
) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = MutableStateFlow<DownloadNetworkCandidate<Network>?>(null)
    private val fallback = MutableStateFlow<DownloadNetworkCandidate<Network>?>(null)
    private val wifiHandoff = DownloadWifiHandoff()
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    @Volatile private var closed = false

    @Volatile private var generation = 0L

    val currentNetwork: Network?
        get() = selectDownloadNetwork(wifi.value, fallback.value)

    @Synchronized
    fun start() {
        if (callbacks.isNotEmpty()) return
        val connectivity = manager ?: return
        closed = false
        wifiHandoff.reset()
        generation++
        requestNetwork(connectivity, wifiOnly = false, state = fallback)
        // Wi-Fi is preferred, while Bluetooth/cellular remains usable if no saved Wi-Fi is available.
        runCatching { requestNetwork(connectivity, wifiOnly = true, state = wifi) }
            .onFailure { error ->
                onEvent("network_request_failed", "transport=wifi error=${error.javaClass.simpleName}")
            }
    }

    suspend fun awaitNetwork(timeoutMs: Long): Network? =
        withTimeoutOrNull(timeoutMs) {
            combine(wifi, fallback) { preferred, other -> selectDownloadNetwork(preferred, other) }
                .filterNotNull()
                .first()
        }

    private fun requestNetwork(
        connectivity: ConnectivityManager,
        wifiOnly: Boolean,
        state: MutableStateFlow<DownloadNetworkCandidate<Network>?>,
    ) {
        val transport = if (wifiOnly) "wifi" else "default"
        val requestGeneration = generation
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val recovered =
                        synchronized(this@DownloadNetworkLease) {
                            if (closed || requestGeneration != generation) return
                            state.value = null
                            updateWifiReadiness()
                        }
                    onEvent("network_available", "transport=$transport")
                    if (recovered) onWifiReady()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    val candidate =
                        DownloadNetworkCandidate(
                            network = network,
                            validated =
                                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                            vpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            wifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                            transport = downloadTransport(networkCapabilities),
                        )
                    val recovered =
                        synchronized(this@DownloadNetworkLease) {
                            if (closed || requestGeneration != generation) return
                            state.value = candidate
                            updateWifiReadiness()
                        }
                    onEvent(
                        "network_capabilities",
                        "transport=$transport validated=${candidate.validated} vpn=${candidate.vpn}",
                    )
                    if (recovered) onWifiReady()
                }

                override fun onLost(network: Network) {
                    val recovered =
                        synchronized(this@DownloadNetworkLease) {
                            if (closed || requestGeneration != generation) return
                            if (state.value?.network == network) state.value = null
                            updateWifiReadiness()
                        }
                    onEvent("network_lost", "transport=$transport")
                    if (recovered) onWifiReady()
                }
            }
        if (wifiOnly) {
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
            connectivity.requestNetwork(request, callback)
        } else {
            connectivity.registerDefaultNetworkCallback(callback)
        }
        callbacks += callback
    }

    private fun updateWifiReadiness(): Boolean =
        wifiHandoff.onChanged(
            selectDownloadNetworkCandidate(wifi.value, fallback.value)?.let { it.wifi && !it.vpn },
        )

    fun describeNetwork(network: Network): String {
        val candidate = listOfNotNull(wifi.value, fallback.value).firstOrNull { it.network == network }
        return "networkId=${network.networkHandle} transport=${candidate?.transport ?: "unknown"} " +
            "validated=${candidate?.validated ?: false}"
    }

    @Synchronized
    override fun close() {
        closed = true
        wifiHandoff.reset()
        generation++
        callbacks.forEach { callback -> runCatching { manager?.unregisterNetworkCallback(callback) } }
        callbacks.clear()
        wifi.value = null
        fallback.value = null
    }
}

private fun downloadTransport(capabilities: NetworkCapabilities): String =
    when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
