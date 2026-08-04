package com.glancemap.glancemapcompanionapp.transfer.datalayer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.WatchNode
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal class PhoneDataLayerRepository(
    context: Context,
) : CapabilityClient.OnCapabilityChangedListener {
    private val appContext = context.applicationContext
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }

    private val started = AtomicBoolean(false)

    private val _watches = MutableStateFlow<List<WatchNode>>(emptyList())
    val watches = _watches.asStateFlow()

    private val _events =
        MutableSharedFlow<PhoneDataLayerEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events = _events.asSharedFlow()

    private val messageListener =
        MessageClient.OnMessageReceivedListener { event ->
            val parsed =
                when (event.path) {
                    DataLayerPaths.PATH_TRANSFER_STATUS -> PhoneDataLayerEvent.TransferStatus(event.data)
                    DataLayerPaths.PATH_TRANSFER_ACK -> PhoneDataLayerEvent.TransferAck(event.data)
                    DataLayerPaths.PATH_PING_RESULT -> PhoneDataLayerEvent.PingResult(event.data)
                    DataLayerPaths.PATH_CHECK_WIFI_STATUS_RESULT -> PhoneDataLayerEvent.WifiStatusResult(event.data)
                    DataLayerPaths.PATH_CHECK_EXISTS_RESULT -> PhoneDataLayerEvent.ExistsResult(event.data)
                    DataLayerPaths.PATH_CHECK_EXISTS_BATCH_RESULT -> PhoneDataLayerEvent.BatchExistsResult(event.data)
                    DataLayerPaths.PATH_DELETE_FILE_RESULT -> PhoneDataLayerEvent.DeleteFileResult(event.data)
                    DataLayerPaths.PATH_LIST_MAPS_RESULT -> PhoneDataLayerEvent.MapListResult(event.data)
                    DataLayerPaths.PATH_ACTIVE_HIKE_SNAPSHOT ->
                        PhoneDataLayerEvent.ActiveHikeSnapshot(
                            sourceNodeId = event.sourceNodeId,
                            payload = event.data,
                        )
                    else -> null
                } ?: return@OnMessageReceivedListener

            _events.tryEmit(parsed)
        }

    suspend fun start() {
        if (!started.compareAndSet(false, true)) return

        messageClient.addListener(messageListener)
        capabilityClient.addListener(this, DataLayerPaths.WEAR_CAPABILITY)
        refreshWatches()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { messageClient.removeListener(messageListener) }
        runCatching { capabilityClient.removeListener(this) }
    }

    suspend fun refreshWatches() {
        runCatching {
            val info =
                capabilityClient
                    .getCapability(DataLayerPaths.WEAR_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .await()
            updateWatchList(info.nodes)
        }.onFailure { err ->
            Log.e(TAG, "Wear API query failed", err)
            PhoneTransferDiagnostics.error("DataLayer", "Wear API query failed", err)
            _events.tryEmit(PhoneDataLayerEvent.Error("Wear OS API unavailable. Is Wear OS app installed?"))
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        runCatching {
            updateWatchList(capabilityInfo.nodes)
        }.onFailure { err ->
            Log.e(TAG, "Capability update failed", err)
            PhoneTransferDiagnostics.error("DataLayer", "Capability update failed", err)
        }
    }

    suspend fun sendMessage(
        nodeId: String,
        path: String,
        payload: ByteArray,
    ) {
        var lastError: Throwable? = null
        PhoneTransferDiagnostics.log("DataLayer", "sendMessage path=$path node=$nodeId")
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            try {
                messageClient.sendMessage(nodeId, path, payload).await()
                if (attempt > 0) {
                    Log.d(TAG, "sendMessage recovered for path=$path node=$nodeId on attempt=${attempt + 1}")
                    PhoneTransferDiagnostics.log(
                        "DataLayer",
                        "Recovered send path=$path node=$nodeId attempt=${attempt + 1}",
                    )
                }
                return
            } catch (t: Throwable) {
                lastError = t
                if (!isTargetNodeNotConnected(t) || attempt == MAX_SEND_ATTEMPTS - 1) {
                    PhoneTransferDiagnostics.error(
                        "DataLayer",
                        "sendMessage failed path=$path node=$nodeId attempt=${attempt + 1}",
                        t,
                    )
                    throw t
                }

                Log.w(
                    TAG,
                    "Target node temporarily disconnected for path=$path node=$nodeId. " +
                        "Waiting for reconnect before retry ${attempt + 2}/$MAX_SEND_ATTEMPTS.",
                )
                PhoneTransferDiagnostics.warn(
                    "DataLayer",
                    "Node disconnected path=$path node=$nodeId retry=${attempt + 2}/$MAX_SEND_ATTEMPTS",
                )
                val reconnected = awaitNodeConnection(nodeId, SEND_RETRY_WAIT_MS)
                if (!reconnected) {
                    delay(SEND_RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: IllegalStateException("sendMessage failed")
    }

    suspend fun sendCancelTransfer(
        nodeId: String,
        transferId: String,
    ) {
        val payload =
            JSONObject()
                .apply { put("id", transferId) }
                .toString()
                .toByteArray(Charsets.UTF_8)
        sendMessage(nodeId, DataLayerPaths.PATH_CANCEL_TRANSFER, payload)
    }

    private fun updateWatchList(nodes: Set<Node>) {
        val mapped =
            nodes
                .mapNotNull { node ->
                    val nodeId =
                        runCatching { node.id }
                            .getOrNull()
                            ?.trim()
                            .orEmpty()
                    if (nodeId.isBlank()) return@mapNotNull null

                    val displayName =
                        runCatching { node.displayName }
                            .getOrNull()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: "Wear device"

                    WatchNode(id = nodeId, displayName = displayName)
                }.distinctBy { it.id }
                .sortedBy { it.displayName.lowercase() }
        _watches.value = mapped
    }

    private suspend fun awaitNodeConnection(
        nodeId: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val connected =
                runCatching { nodeClient.connectedNodes.await() }
                    .getOrDefault(emptyList())
                    .any { it.id == nodeId }
            if (connected) return true
            delay(SEND_RETRY_DELAY_MS)
        }
        return false
    }

    private fun isTargetNodeNotConnected(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return "target node not connected" in message || "node not connected" in message
    }

    private companion object {
        const val TAG = "PhoneDataLayerRepo"
        private const val MAX_SEND_ATTEMPTS = 4
        private const val SEND_RETRY_WAIT_MS = 8_000L
        private const val SEND_RETRY_DELAY_MS = 500L
    }
}
