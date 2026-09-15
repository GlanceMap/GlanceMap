package com.glancemap.glancemapwearos.core.service.transfer.http

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.util.TransferUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

internal class HttpTransferNetworkSession(
    private val connectivityManager: ConnectivityManager,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun acquireWifi(
        timeoutMs: Long,
        transferId: String = "",
        startupDeadlineElapsedMs: Long? = null,
    ): Network? {
        val acquireStartMs = SystemClock.elapsedRealtime()
        val existing = findWifiNetwork()
        TransferDiagnostics.log(
            "HttpWifi",
            "event=wifi_acquire_start transferId=${transferId.ifBlank { "na" }} " +
                "wifiAlreadyActive=${existing != null} initialTransport=${currentTransportLabel()}",
        )
        return when {
            existing != null -> {
                TransferDiagnostics.log(
                    "HttpWifi",
                    "event=wifi_acquire_success transferId=${transferId.ifBlank { "na" }} " +
                        "wifiAlreadyActive=true wifiAcquireMs=${SystemClock.elapsedRealtime() - acquireStartMs} " +
                        "remainingStartupBudgetMs=${remainingBudget(startupDeadlineElapsedMs)} " +
                        "remainingAfterWifiMs=${remainingBudget(startupDeadlineElapsedMs)}",
                )
                existing
            }
            timeoutMs <= 0L -> {
                TransferDiagnostics.warn(
                    "HttpWifi",
                    "event=wifi_acquire_failure transferId=${transferId.ifBlank { "na" }} " +
                        "wifiAlreadyActive=false wifiAcquireMs=${SystemClock.elapsedRealtime() - acquireStartMs} " +
                        "remainingStartupBudgetMs=${remainingBudget(startupDeadlineElapsedMs)} " +
                        "remainingAfterWifiMs=${remainingBudget(startupDeadlineElapsedMs)} reason=budget_exhausted",
                )
                null
            }
            else -> {
                Log.d(TAG, "Requesting Wi-Fi...")
                val deferred = CompletableDeferred<Network?>()
                callback = TransferUtils.requestWifiNetwork(connectivityManager, deferred)
                val network = withTimeoutOrNull(timeoutMs) { deferred.await() }
                if (network == null) close()
                val acquireMs = SystemClock.elapsedRealtime() - acquireStartMs
                if (network != null) {
                    TransferDiagnostics.log(
                        "HttpWifi",
                        "event=wifi_acquire_success transferId=${transferId.ifBlank { "na" }} " +
                            "wifiAlreadyActive=false wifiAcquireMs=$acquireMs " +
                            "remainingStartupBudgetMs=${remainingBudget(startupDeadlineElapsedMs)} " +
                            "remainingAfterWifiMs=${remainingBudget(startupDeadlineElapsedMs)}",
                    )
                } else {
                    TransferDiagnostics.warn(
                        "HttpWifi",
                        "event=wifi_acquire_failure transferId=${transferId.ifBlank { "na" }} " +
                            "wifiAlreadyActive=false wifiAcquireMs=$acquireMs " +
                            "remainingStartupBudgetMs=${remainingBudget(startupDeadlineElapsedMs)} " +
                            "remainingAfterWifiMs=${remainingBudget(startupDeadlineElapsedMs)} reason=timeout",
                    )
                }
                network
            }
        }
    }

    fun findWifiNetwork(): Network? {
        val active = connectivityManager.activeNetwork ?: return null
        val caps = connectivityManager.getNetworkCapabilities(active)
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            active
        } else {
            null
        }
    }

    fun isNetworkUsable(network: Network?): Boolean {
        if (network == null) return false
        val caps = connectivityManager.getNetworkCapabilities(network)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    suspend fun waitForWifiReconnect(
        timeoutMs: Long,
        recheckMs: Long,
    ): Network? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val wifi = findWifiNetwork()
            if (wifi != null) return wifi
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            delay(recheckMs.coerceAtMost(remainingMs))
        }
        return null
    }

    fun bindToNetwork(network: Network) {
        runCatching {
            @Suppress("DEPRECATION")
            connectivityManager.bindProcessToNetwork(network)
        }.onSuccess {
            Log.d(TAG, "✅ Bound process to Wi-Fi for HTTP transfer")
        }.onFailure {
            Log.w(TAG, "⚠️ bindProcessToNetwork failed: ${it.message}")
        }
    }

    fun close() {
        callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        callback = null

        runCatching {
            @Suppress("DEPRECATION")
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    private fun currentTransportLabel(): String {
        val capabilities =
            connectivityManager.activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        return when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }

    private fun remainingBudget(deadlineElapsedMs: Long?): Long? = deadlineElapsedMs?.let(::remainingBudgetAt)

    private fun remainingBudgetAt(deadlineElapsedMs: Long): Long {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        return (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    }

    private companion object {
        const val TAG = "HttpNetworkSession"
    }
}
