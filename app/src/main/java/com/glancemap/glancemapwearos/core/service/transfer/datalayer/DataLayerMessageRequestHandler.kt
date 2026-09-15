package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.glancemap.glancemapwearos.core.service.DataLayerListenerService
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.TransferConstants
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferSessionState
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchMapWithBounds
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchPoiWithBounds
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchRoutingSegmentWithBounds
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private fun ConnectivityManager.hasWifiTransport(network: Network?): Boolean =
    network
        ?.let { getNetworkCapabilities(it) }
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

private val ConnectivityManager.hasAvailableWifiNetwork: Boolean
    @Suppress("DEPRECATION")
    get() = allNetworks.any { network -> hasWifiTransport(network) }

private const val TAG = "DataLayerMessageReq"

internal class DataLayerMessageRequestHandler(
    private val service: DataLayerListenerService,
    private val notificationHelper: NotificationHelper,
    private val fileOps: WatchFileOps,
    transferMutex: Mutex,
    private val sessionState: TransferSessionState,
    private val sendStatus: suspend (sourceNodeId: String, transferId: String, phase: String, detail: String) -> Unit,
    private val sendAck: suspend (sourceNodeId: String, transferId: String, status: String, detail: String) -> Unit,
    private val sendMessage: suspend (sourceNodeId: String, path: String, payload: ByteArray) -> Unit,
) {
    private val appScope get() = service.appScope()

    // Checksum storage for channel transfers: transferId -> sha256
    // Populated from pre-warm messages, consumed by DataLayerChannelOpenedHandler
    private val pendingChannelChecksums = ConcurrentHashMap<String, String>()

    fun popChannelChecksum(transferId: String): String? = pendingChannelChecksums.remove(transferId)

    private val wifiRequestHandler =
        DataLayerWifiTransferRequestHandler(
            service = service,
            fileOps = fileOps,
            sessionState = sessionState,
            sendStatus = sendStatus,
            sendAck = sendAck,
        )

    private val smallFileRequestHandler =
        DataLayerSmallFileRequestHandler(
            service = service,
            notificationHelper = notificationHelper,
            fileOps = fileOps,
            transferMutex = transferMutex,
            sendAck = sendAck,
        )

    fun handleMessage(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            TransferConstants.PATH_CANCEL_TRANSFER -> {
                handleCancelRequest(messageEvent)
                return
            }

            TransferConstants.PATH_CHECK_EXISTS -> {
                handleCheckExists(messageEvent)
                return
            }

            TransferConstants.PATH_PING -> {
                handlePingRequest(messageEvent)
                return
            }

            TransferConstants.PATH_CHECK_WIFI_STATUS -> {
                handleWifiStatusRequest(messageEvent)
                return
            }

            TransferConstants.PATH_CHECK_EXISTS_BATCH -> {
                handleBatchCheckExists(messageEvent)
                return
            }

            TransferConstants.PATH_DELETE_FILE -> {
                handleDeleteFile(messageEvent)
                return
            }

            TransferConstants.PATH_LIST_MAPS -> {
                handleListMaps(messageEvent)
                return
            }

            TransferConstants.PATH_PREPARE_CHANNEL -> {
                TransferDiagnostics.log("MsgReq", "Prewarm request")
                // Extract and store checksum for the upcoming channel transfer
                runCatching {
                    val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
                    val id = json.optString("id", "")
                    val sha256 = json.optString("sha256", "")
                    if (id.isNotBlank() && sha256.isNotBlank()) {
                        pendingChannelChecksums[id] = sha256
                    }
                }
                service.holdPrewarmWakeLock(
                    reason = "prepare_channel:${messageEvent.sourceNodeId}",
                    timeoutMs = TransferConstants.PREWARM_WAKELOCK_MS,
                )
                return
            }

            TransferConstants.PATH_START_WIFI_TRANSFER -> {
                wifiRequestHandler.handle(messageEvent)
                return
            }

            else -> {
                if (messageEvent.path.startsWith(TransferConstants.PATH_SMALL_FILE_PREFIX + "/")) {
                    smallFileRequestHandler.handle(messageEvent)
                }
            }
        }
    }

    private fun handleCancelRequest(messageEvent: MessageEvent) {
        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = payload.optString("id", "")
        if (id.isBlank()) return
        val activeId = sessionState.activeTransferId()
        val cancelledById = sessionState.cancelTransferById(id, "Cancelled by phone")
        if (cancelledById) {
            Log.d(TAG, "⛔ Cancel requested for transferId=$id")
            TransferDiagnostics.warn("MsgReq", "Cancel requested id=$id")
            return
        }

        val fileName = sessionState.fileNameForTransferId(id)
        val cancelledByFile =
            if (!fileName.isNullOrBlank()) {
                sessionState.cancelTransfersForFile(
                    sourceNodeId = messageEvent.sourceNodeId,
                    fileName = fileName,
                    reason = "Cancelled by phone (file fallback)",
                )
            } else {
                0
            }

        if (cancelledByFile > 0) {
            TransferDiagnostics.warn(
                "MsgReq",
                "Cancel fallback by file id=$id file=$fileName count=$cancelledByFile",
            )
        } else {
            TransferDiagnostics.log("MsgReq", "Cancel ignored id=$id activeId=${activeId.orEmpty()}")
        }
    }

    private fun handleCheckExists(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId

        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid JSON exists request")
            TransferDiagnostics.warn("MsgReq", "Invalid exists request JSON")
            return
        }

        val requestId = payload.optString("id", "")
        val safeNameEncoded = payload.optString("name", "")

        if (requestId.isBlank() || safeNameEncoded.isBlank()) {
            Log.w(TAG, "Missing fields in exists request: $payload")
            TransferDiagnostics.warn(
                "MsgReq",
                "Missing exists fields requestIdBlank=${requestId.isBlank()} nameBlank=${safeNameEncoded.isBlank()}",
            )
            return
        }

        val decoded = runCatching { Uri.decode(safeNameEncoded) }.getOrDefault(safeNameEncoded)
        val fileName = fileOps.sanitizeFileName(decoded)
        TransferDiagnostics.log("MsgReq", "Exists request id=$requestId file=$fileName")

        appScope.launch(Dispatchers.IO) {
            val exists = runCatching { fileOps.fileBlocksIncomingTransfer(fileName) }.getOrDefault(false)

            val reply =
                JSONObject().apply {
                    put("id", requestId)
                    put("name", fileName)
                    put("exists", exists)
                }

            runCatching {
                sendMessage(
                    sourceNodeId,
                    TransferConstants.PATH_CHECK_EXISTS_RESULT,
                    reply.toString().toByteArray(Charsets.UTF_8),
                )
                TransferDiagnostics.log(
                    "MsgReq",
                    "Exists reply id=$requestId file=$fileName exists=$exists",
                )
            }.onFailure {
                Log.d(TAG, "Exists reply send failed: ${it.message}")
                TransferDiagnostics.warn(
                    "MsgReq",
                    "Exists reply send failed id=$requestId file=$fileName",
                )
            }
        }
    }

    private fun handlePingRequest(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId
        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid JSON ping request")
            TransferDiagnostics.warn("MsgReq", "Invalid ping request JSON")
            return
        }

        val requestId = payload.optString("id", "")
        if (requestId.isBlank()) {
            Log.w(TAG, "Missing request id in ping request: $payload")
            TransferDiagnostics.warn("MsgReq", "Missing ping request id")
            return
        }

        TransferDiagnostics.log("MsgReq", "Ping request id=$requestId")

        appScope.launch(Dispatchers.IO) {
            val reply =
                JSONObject()
                    .put("id", requestId)
                    .put("nodeId", sourceNodeId)
                    .put("ok", true)

            runCatching {
                sendMessage(
                    sourceNodeId,
                    TransferConstants.PATH_PING_RESULT,
                    reply.toString().toByteArray(Charsets.UTF_8),
                )
                TransferDiagnostics.log("MsgReq", "Ping reply id=$requestId")
            }.onFailure {
                Log.d(TAG, "Ping reply send failed: ${it.message}")
                TransferDiagnostics.warn("MsgReq", "Ping reply send failed id=$requestId")
            }
        }
    }

    private fun handleWifiStatusRequest(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId
        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid JSON Wi-Fi status request")
            TransferDiagnostics.warn("MsgReq", "Invalid Wi-Fi status request JSON")
            return
        }

        val requestId = payload.optString("id", "")
        if (requestId.isBlank()) {
            Log.w(TAG, "Missing request id in Wi-Fi status request: $payload")
            TransferDiagnostics.warn("MsgReq", "Missing Wi-Fi status request id")
            return
        }

        val (wifiAvailable, reason) = readWifiAvailability()
        TransferDiagnostics.log(
            "MsgReq",
            "Wi-Fi status request id=$requestId wifiAvailable=$wifiAvailable reason=$reason",
        )

        appScope.launch(Dispatchers.IO) {
            val reply =
                JSONObject()
                    .put("id", requestId)
                    .put("wifiAvailable", wifiAvailable)
                    .put("reason", reason)

            runCatching {
                sendMessage(
                    sourceNodeId,
                    TransferConstants.PATH_CHECK_WIFI_STATUS_RESULT,
                    reply.toString().toByteArray(Charsets.UTF_8),
                )
                TransferDiagnostics.log("MsgReq", "Wi-Fi status reply id=$requestId")
            }.onFailure {
                Log.d(TAG, "Wi-Fi status reply send failed: ${it.message}")
                TransferDiagnostics.warn("MsgReq", "Wi-Fi status reply send failed id=$requestId")
            }
        }
    }

    private fun readWifiAvailability(): Pair<Boolean, String> {
        val connectivityManager =
            service.getSystemService(ConnectivityManager::class.java)
        val active = connectivityManager?.activeNetwork

        val result =
            when {
                connectivityManager == null -> false to "connectivity_manager_missing"
                connectivityManager.hasWifiTransport(active) -> true to "active_wifi"
                connectivityManager.hasAvailableWifiNetwork -> true to "available_wifi"
                active == null -> false to "no_active_network"
                else -> false to "no_wifi_network"
            }
        return result
    }

    private fun handleBatchCheckExists(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId

        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid JSON batch exists request")
            TransferDiagnostics.warn("MsgReq", "Invalid batch exists request JSON")
            return
        }

        val requestId = payload.optString("id", "")
        val items = payload.optJSONArray("items")
        if (requestId.isBlank() || items == null) {
            Log.w(TAG, "Missing fields in batch exists request: $payload")
            TransferDiagnostics.warn(
                "MsgReq",
                "Missing batch exists fields requestIdBlank=${requestId.isBlank()} itemsMissing=${items == null}",
            )
            return
        }
        TransferDiagnostics.log("MsgReq", "Batch exists request id=$requestId count=${items.length()}")

        appScope.launch(Dispatchers.IO) {
            val results = org.json.JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val index = item.optInt("index", -1)
                val safeNameEncoded = item.optString("name", "")
                if (index < 0 || safeNameEncoded.isBlank()) continue

                val decoded = runCatching { Uri.decode(safeNameEncoded) }.getOrDefault(safeNameEncoded)
                val fileName = fileOps.sanitizeFileName(decoded)
                val exists = runCatching { fileOps.fileBlocksIncomingTransfer(fileName) }.getOrDefault(false)

                results.put(
                    JSONObject()
                        .put("index", index)
                        .put("exists", exists),
                )
            }

            val reply =
                JSONObject()
                    .put("id", requestId)
                    .put("count", items.length())
                    .put("items", results)

            runCatching {
                sendMessage(
                    sourceNodeId,
                    TransferConstants.PATH_CHECK_EXISTS_BATCH_RESULT,
                    reply.toString().toByteArray(Charsets.UTF_8),
                )
                TransferDiagnostics.log(
                    "MsgReq",
                    "Batch exists reply id=$requestId requested=${items.length()} returned=${results.length()}",
                )
            }.onFailure {
                Log.d(TAG, "Batch exists reply send failed: ${it.message}")
                TransferDiagnostics.warn("MsgReq", "Batch exists reply send failed id=$requestId")
            }
        }
    }

    private fun handleListMaps(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId
        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid JSON map-list request")
            return
        }

        val requestId = payload.optString("id", "")
        if (requestId.isBlank()) {
            Log.w(TAG, "Missing request id in map-list request: $payload")
            return
        }

        appScope.launch(Dispatchers.IO) {
            val reply = buildInstalledCoverageReply(requestId, fileOps)

            runCatching {
                sendMessage(
                    sourceNodeId,
                    TransferConstants.PATH_LIST_MAPS_RESULT,
                    reply.toString().toByteArray(Charsets.UTF_8),
                )
            }.onFailure {
                Log.d(TAG, "Map-list reply send failed: ${it.message}")
            }
        }
    }

    private fun handleDeleteFile(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId
        val payload = runCatching { JSONObject(String(messageEvent.data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid JSON delete-file request")
            return
        }

        val requestId = payload.optString("id", "")
        val safeNameEncoded = payload.optString("name", "")
        if (requestId.isBlank() || safeNameEncoded.isBlank()) {
            Log.w(TAG, "Missing fields in delete-file request: $payload")
            return
        }

        val decoded = runCatching { Uri.decode(safeNameEncoded) }.getOrDefault(safeNameEncoded)
        val fileName = fileOps.sanitizeFileName(decoded)

        appScope.launch(Dispatchers.IO) {
            val ok =
                runCatching {
                    fileOps.deleteByName(fileName)
                    !fileOps.fileExistsOnWatch(fileName)
                }.getOrDefault(false)

            val reply =
                JSONObject()
                    .put("id", requestId)
                    .put("name", fileName)
                    .put("ok", ok)

            runCatching {
                sendMessage(
                    sourceNodeId,
                    TransferConstants.PATH_DELETE_FILE_RESULT,
                    reply.toString().toByteArray(Charsets.UTF_8),
                )
            }.onFailure {
                Log.d(TAG, "Delete-file reply send failed: ${it.message}")
            }
        }
    }
}

private suspend fun buildInstalledCoverageReply(
    requestId: String,
    fileOps: WatchFileOps,
): JSONObject {
    val maps =
        runCatching { fileOps.listMapFilesWithBounds() }
            .getOrElse {
                Log.w(TAG, "Failed to list maps for request id=$requestId", it)
                emptyList()
            }
    val pois =
        runCatching { fileOps.listPoiFilesWithBounds() }
            .getOrElse {
                Log.w(TAG, "Failed to list POI coverage for request id=$requestId", it)
                emptyList()
            }
    val routingSegments =
        runCatching { fileOps.listRoutingSegmentsWithBounds() }
            .getOrElse {
                Log.w(TAG, "Failed to list routing coverage for request id=$requestId", it)
                emptyList()
            }

    return JSONObject()
        .put("id", requestId)
        .put("maps", mapsToJsonRows(maps))
        .put("pois", poisToJsonRows(pois))
        .put("routingTiles", routingSegmentsToJsonRows(routingSegments))
}

private fun mapsToJsonRows(maps: List<WatchMapWithBounds>): JSONArray =
    maps.toJsonRows(
        fileName = WatchMapWithBounds::fileName,
        absolutePath = WatchMapWithBounds::absolutePath,
        bbox = WatchMapWithBounds::bbox,
    )

private fun poisToJsonRows(pois: List<WatchPoiWithBounds>): JSONArray =
    pois.toJsonRows(
        fileName = WatchPoiWithBounds::fileName,
        absolutePath = WatchPoiWithBounds::absolutePath,
        bbox = WatchPoiWithBounds::bbox,
    )

private fun routingSegmentsToJsonRows(segments: List<WatchRoutingSegmentWithBounds>): JSONArray =
    segments.toJsonRows(
        fileName = WatchRoutingSegmentWithBounds::fileName,
        absolutePath = WatchRoutingSegmentWithBounds::absolutePath,
        bbox = WatchRoutingSegmentWithBounds::bbox,
    )

private fun <T> List<T>.toJsonRows(
    fileName: (T) -> String,
    absolutePath: (T) -> String,
    bbox: (T) -> String,
): JSONArray {
    val rows = JSONArray()
    forEach { item ->
        rows.put(
            JSONObject()
                .put("name", fileName(item))
                .put("path", absolutePath(item))
                .put("bbox", bbox(item)),
        )
    }
    return rows
}
