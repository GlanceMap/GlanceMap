package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import android.net.Uri
import android.os.Build
import android.util.Log
import com.glancemap.glancemapwearos.core.service.DataLayerListenerService
import com.glancemap.glancemapwearos.core.service.WatchTransferForegroundService
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.notifications.foregroundStartFailureDetail
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferSessionState
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class DataLayerWifiTransferRequestHandler(
    private val service: DataLayerListenerService,
    private val fileOps: WatchFileOps,
    private val sessionState: TransferSessionState,
    private val sendStatus: suspend (sourceNodeId: String, transferId: String, phase: String, detail: String) -> Unit,
    private val sendAck: suspend (sourceNodeId: String, transferId: String, status: String, detail: String) -> Unit,
) {
    private val appScope get() = service.appScope()

    fun handle(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId

        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid JSON Wi-Fi request")
            TransferDiagnostics.warn("WiFiReq", "Invalid START_WIFI_TRANSFER JSON")
            return
        }

        val transferId = payload.optString("id", "")
        val ip = payload.optString("ip", "")
        val port = payload.optInt("port", -1)
        val safeName = payload.optString("name", "")
        val totalSize = payload.optLong("size", -1L)
        val protocol = payload.optString("protocol", "http")
        val httpPath = payload.optString("path", "")
        val checksumSha256 = payload.optString("sha256", "").trim().ifBlank { null }
        val authToken =
            payload
                .optString(TransferDataLayerContract.HTTP_AUTH_TOKEN_JSON_KEY, "")
                .trim()
                .ifBlank { null }

        if (transferId.isBlank() || ip.isBlank() || port <= 0 || safeName.isBlank()) {
            Log.w(TAG, "Missing fields in Wi-Fi request")
            TransferDiagnostics.warn(
                "WiFiReq",
                "event=invalid_wifi_transfer_request reason=missing_required_field " +
                    "transferId=${transferId.ifBlank { "na" }} ipBlank=${ip.isBlank()} port=$port " +
                    "nameBlank=${safeName.isBlank()}",
            )
            return
        }

        Log.d(
            TAG,
            "START_WIFI_TRANSFER id=$transferId protocol=$protocol port=$port size=$totalSize",
        )

        val fileName = fileOps.sanitizeFileName(Uri.decode(safeName))
        val notificationId = transferId.hashCode()
        TransferDiagnostics.log(
            "WiFiReq",
            "START id=$transferId file=$fileName protocol=$protocol size=$totalSize",
        )

        if (protocol == "http") {
            val existingTransfer = sessionState.currentHttpTransfer(sourceNodeId, fileName)
            if (existingTransfer != null) {
                if (existingTransfer.transferId == transferId) {
                    TransferDiagnostics.warn(
                        "WiFiReq",
                        "Duplicate START ignored id=$transferId file=$fileName",
                    )
                    return
                }
                TransferDiagnostics.warn(
                    "WiFiReq",
                    "Superseding HTTP transfer oldId=${existingTransfer.transferId} newId=$transferId file=$fileName",
                )
            }
            val cancelled =
                sessionState.cancelTransfersForFile(
                    sourceNodeId = sourceNodeId,
                    fileName = fileName,
                    reason = "Superseded by newer transferId=$transferId",
                    excludeTransferId = transferId,
                )
            if (cancelled > 0) {
                TransferDiagnostics.warn(
                    "WiFiReq",
                    "Cancelled stale HTTP transfers count=$cancelled newId=$transferId file=$fileName",
                )
            }
        }

        if (protocol == "http" && checksumSha256.isNullOrBlank()) {
            TransferDiagnostics.warn(
                "WiFiReq",
                "Missing checksum for HTTP transfer id=$transferId file=$fileName",
            )
            service.releasePrewarmWakeLock("http_rejected_missing_checksum:$fileName")
            appScope.launch(Dispatchers.IO) {
                val msg = "MISSING_CHECKSUM"
                sendStatus(sourceNodeId, transferId, "ERROR", msg)
                sendAck(sourceNodeId, transferId, "ERROR", msg)
            }
            return
        }

        if (protocol == "http" && authToken.isNullOrBlank()) {
            TransferDiagnostics.warn(
                "WiFiReq",
                "Missing auth token for HTTP transfer id=$transferId file=$fileName",
            )
            service.releasePrewarmWakeLock("http_rejected_missing_token:$fileName")
            appScope.launch(Dispatchers.IO) {
                val msg = "MISSING_HTTP_TOKEN"
                sendStatus(sourceNodeId, transferId, "ERROR", msg)
                sendAck(sourceNodeId, transferId, "ERROR", msg)
            }
            return
        }

        val metadata =
            ReceiverMetadata(
                transferId = transferId,
                fileName = fileName,
                totalSize = totalSize,
                sourceNodeId = sourceNodeId,
                notificationId = notificationId,
                checksumSha256 = checksumSha256,
                authToken = authToken,
                ip = ip,
                port = port,
            )

        if (protocol == "http") {
            runCatching {
                sessionState.registerHttpTransfer(
                    TransferSessionState.ActiveHttpTransfer(
                        transferId = transferId,
                        fileName = fileName,
                        sourceNodeId = sourceNodeId,
                        job = null,
                    ),
                )
                TransferDiagnostics.log(
                    "WiFiReq",
                    "Register active HTTP transfer id=$transferId file=$fileName",
                )
                TransferDiagnostics.log(
                    "WiFiReq",
                    "Dispatch HTTP transfer via foreground service id=${metadata.transferId} file=${metadata.fileName}",
                )
                WatchTransferForegroundService.startHttpTransfer(service, metadata, httpPath)
            }.onFailure { error ->
                sessionState.clearHttpTransfer(metadata.transferId)
                service.releasePrewarmWakeLock("http_service_start_failed:${metadata.fileName}")
                val detail = foregroundStartFailureDetail(error.javaClass.name, Build.VERSION.SDK_INT, error.message)
                TransferDiagnostics.error(
                    "WiFiReq",
                    "Failed to launch foreground transfer id=${metadata.transferId} " +
                        "file=${metadata.fileName} detail=$detail",
                    error,
                )
                appScope.launch(Dispatchers.IO) {
                    val msg = detail
                    sendStatus(metadata.sourceNodeId, metadata.transferId, "ERROR", msg)
                    sendAck(metadata.sourceNodeId, metadata.transferId, "ERROR", msg)
                }
            }
        } else {
            val msg = "UNSUPPORTED_PROTOCOL:$protocol"
            Log.w(TAG, msg)
            TransferDiagnostics.warn(
                "WiFiReq",
                "Unsupported protocol id=${metadata.transferId} file=${metadata.fileName} protocol=$protocol",
            )
            service.releasePrewarmWakeLock("http_rejected_protocol:${metadata.fileName}")
            appScope.launch(Dispatchers.IO) {
                sendStatus(metadata.sourceNodeId, metadata.transferId, "ERROR", msg)
                sendAck(metadata.sourceNodeId, metadata.transferId, "ERROR", msg)
            }
        }
    }

    private companion object {
        const val TAG = "DataLayerWifiReq"
    }
}
