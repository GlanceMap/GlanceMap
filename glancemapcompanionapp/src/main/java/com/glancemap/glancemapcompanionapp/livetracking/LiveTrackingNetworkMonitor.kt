package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

internal enum class LiveTrackingNetworkState {
    UNKNOWN,
    UNAVAILABLE,
    VALIDATED,
}

internal class LiveTrackingNetworkStateTracker<N> {
    private val mutableState = MutableStateFlow(LiveTrackingNetworkState.UNKNOWN)
    val state = mutableState.asStateFlow()
    private var currentNetwork: N? = null
    private var stopped = false

    @Synchronized
    fun initialize(
        network: N?,
        validated: Boolean,
    ) {
        if (stopped) return
        currentNetwork = network
        mutableState.value = availability(network != null && validated)
    }

    @Synchronized
    fun available(network: N) {
        if (stopped || currentNetwork == network) return
        currentNetwork = network
        // Availability alone does not establish internet access; wait for capabilities.
        mutableState.value = LiveTrackingNetworkState.UNAVAILABLE
    }

    @Synchronized
    fun capabilitiesChanged(
        network: N,
        validated: Boolean,
    ) {
        if (stopped || currentNetwork != network) return
        mutableState.value = availability(validated)
    }

    @Synchronized
    fun lost(network: N) {
        if (stopped || currentNetwork != network) return
        currentNetwork = null
        mutableState.value = LiveTrackingNetworkState.UNAVAILABLE
    }

    @Synchronized
    fun monitoringUnavailable() {
        if (!stopped) mutableState.value = LiveTrackingNetworkState.UNKNOWN
    }

    @Synchronized
    fun stop() {
        stopped = true
        currentNetwork = null
        mutableState.value = LiveTrackingNetworkState.UNKNOWN
    }

    private fun availability(validated: Boolean): LiveTrackingNetworkState =
        if (validated) {
            LiveTrackingNetworkState.VALIDATED
        } else {
            LiveTrackingNetworkState.UNAVAILABLE
        }
}

internal class LiveTrackingNetworkMonitor(
    context: Context,
) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val tracker = LiveTrackingNetworkStateTracker<Network>()
    val state = tracker.state
    private var registered = false
    private var stopped = false
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = tracker.available(network)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                tracker.capabilitiesChanged(network, capabilities.hasValidatedInternet())
            }

            override fun onLost(network: Network) = tracker.lost(network)
        }

    @Synchronized
    fun start() {
        if (registered || stopped) return
        val connectivityManager = manager ?: return
        registered =
            runCatching {
                // This initial snapshot runs outside callbacks, before main-thread callback delivery.
                val network = connectivityManager.activeNetwork
                val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
                tracker.initialize(network, capabilities?.hasValidatedInternet() == true)
                connectivityManager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            }.isSuccess
        if (!registered) tracker.monitoringUnavailable()
    }

    @Synchronized
    fun stop() {
        stopped = true
        tracker.stop()
        if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
    }
}

// If monitoring is unavailable, retain HTTP error handling rather than permanently blocking tracking.
internal fun LiveTrackingNetworkState.canAttemptUpload(): Boolean = this != LiveTrackingNetworkState.UNAVAILABLE

private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

internal class LiveTrackingOfflineException : IOException("Waiting for a validated internet connection")
