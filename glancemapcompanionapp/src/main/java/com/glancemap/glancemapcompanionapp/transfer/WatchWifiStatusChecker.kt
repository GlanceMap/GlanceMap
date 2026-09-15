package com.glancemap.glancemapcompanionapp.transfer

import android.util.Log
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.datalayer.DataLayerPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class WatchWifiStatus(
    val wifiAvailable: Boolean,
    val reason: String,
)

internal class WatchWifiStatusChecker(
    private val sendMessage: suspend (nodeId: String, path: String, payload: ByteArray) -> Unit,
) {
    private val pendingChecks = ConcurrentHashMap<String, CompletableDeferred<WatchWifiStatus>>()

    suspend fun check(nodeId: String): WatchWifiStatus? {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<WatchWifiStatus>()
        pendingChecks[requestId] = deferred

        val payload =
            JSONObject()
                .put("id", requestId)
                .toString()
                .toByteArray(Charsets.UTF_8)

        return try {
            runCatching {
                PhoneTransferDiagnostics.log("WatchWifi", "Check requestId=$requestId")
                sendMessage(nodeId, DataLayerPaths.PATH_CHECK_WIFI_STATUS, payload)
                val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) { deferred.await() }
                if (result == null) {
                    PhoneTransferDiagnostics.warn("WatchWifi", "Check timeout requestId=$requestId")
                }
                result
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Watch Wi-Fi status check failed", error)
                PhoneTransferDiagnostics.error("WatchWifi", "Check failed requestId=$requestId", error)
                null
            }
        } finally {
            pendingChecks.remove(requestId)?.cancel()
        }
    }

    fun handleWifiStatusResult(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val requestId = json.getString("id")
            val status =
                WatchWifiStatus(
                    wifiAvailable = json.optBoolean("wifiAvailable", false),
                    reason = json.optString("reason", ""),
                )
            pendingChecks.remove(requestId)?.complete(status)
            PhoneTransferDiagnostics.log(
                "WatchWifi",
                "Check result requestId=$requestId wifiAvailable=${status.wifiAvailable} reason=${status.reason}",
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse watch Wi-Fi status result", it)
            PhoneTransferDiagnostics.error("WatchWifi", "Failed to parse status result", it)
        }
    }

    private companion object {
        const val TAG = "WatchWifiStatusChecker"
        const val CHECK_TIMEOUT_MS = 3_000L
    }
}
