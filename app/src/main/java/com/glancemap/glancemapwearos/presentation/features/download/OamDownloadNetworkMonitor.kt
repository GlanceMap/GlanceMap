package com.glancemap.glancemapwearos.presentation.features.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry

data class OamDownloadNetworkState(
    val isWifi: Boolean,
    val isBluetooth: Boolean,
    val isCellular: Boolean,
    val isEthernet: Boolean,
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isUnmetered: Boolean,
    val isMetered: Boolean,
) {
    val isValidatedWifi: Boolean
        get() = isWifi && hasInternet && isValidated

    val transportLabel: String
        get() =
            when {
                isWifi -> "wifi"
                isBluetooth -> "bluetooth"
                isCellular -> "cellular"
                isEthernet -> "ethernet"
                isVpn -> "vpn"
                else -> "none"
            }

    @get:StringRes val userLabelRes: Int
        get() =
            when {
                isValidatedWifi -> R.string.download_wi_fi_ready
                isWifi && hasInternet -> R.string.download_wi_fi_connecting
                isWifi -> R.string.download_wi_fi_unavailable
                hasInternet -> R.string.download_not_on_wi_fi
                else -> R.string.download_no_internet
            }

    val telemetryFields: String
        get() =
            "transport=$transportLabel " +
                "wifi=$isWifi bluetooth=$isBluetooth cellular=$isCellular ethernet=$isEthernet vpn=$isVpn " +
                "internet=$hasInternet validated=$isValidated unmetered=$isUnmetered metered=$isMetered"
}

class OamDownloadNetworkMonitor(
    context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun currentState(): OamDownloadNetworkState {
        val network = connectivityManager?.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        return capabilities.toDownloadNetworkState(isMetered = connectivityManager?.isActiveNetworkMetered)
    }

    fun logSnapshot(event: String) {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=$event ${currentState().telemetryFields}",
        )
    }

    fun watchNetworkState(onChanged: (OamDownloadNetworkState) -> Unit): AutoCloseable {
        val manager = connectivityManager ?: return AutoCloseable {}
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    notifyState("network_capabilities_changed", networkCapabilities, onChanged)
                }

                override fun onLost(network: Network) {
                    notifyState("network_lost", null, onChanged)
                }
            }
        manager.registerDefaultNetworkCallback(callback)
        return AutoCloseable {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    private fun notifyState(
        event: String,
        capabilities: NetworkCapabilities?,
        onChanged: (OamDownloadNetworkState) -> Unit,
    ) {
        val state = capabilities.toDownloadNetworkState()
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=$event ${state.telemetryFields}",
        )
        onChanged(state)
    }

    private companion object {
        private const val OAM_DOWNLOAD_TELEMETRY_TAG = "OamDownload"
    }
}

internal class OamDownloadNetworkRecoveryObserver(
    initialState: OamDownloadNetworkState,
    private val onWifiRecovered: (OamDownloadNetworkState) -> Unit,
) {
    private var observedWithoutValidatedWifi = !initialState.isValidatedWifi

    fun onChanged(state: OamDownloadNetworkState) {
        if (!state.isValidatedWifi) {
            observedWithoutValidatedWifi = true
        } else if (observedWithoutValidatedWifi) {
            observedWithoutValidatedWifi = false
            onWifiRecovered(state)
        }
    }
}

private fun NetworkCapabilities?.toDownloadNetworkState(isMetered: Boolean? = null): OamDownloadNetworkState {
    val hasInternet = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val isUnmetered = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    return OamDownloadNetworkState(
        isWifi = this?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        isBluetooth = this?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true,
        isCellular = this?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
        isEthernet = this?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
        isVpn = this?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        hasInternet = hasInternet,
        isValidated = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        isUnmetered = isUnmetered,
        isMetered = isMetered ?: (hasInternet && !isUnmetered),
    )
}
