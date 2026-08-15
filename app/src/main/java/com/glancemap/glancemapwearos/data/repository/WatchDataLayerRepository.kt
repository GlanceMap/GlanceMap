package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.TransferConstants
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class WatchDataLayerRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }

    suspend fun sendStatus(
        sourceNodeId: String,
        transferId: String,
        phase: String,
        detail: String,
    ) {
        val payload =
            JSONObject()
                .apply {
                    put("id", transferId)
                    put("phase", phase)
                    put("detail", detail)
                }.toString()
                .toByteArray(Charsets.UTF_8)

        runCatching {
            messageClient
                .sendMessage(
                    sourceNodeId,
                    TransferConstants.PATH_TRANSFER_STATUS,
                    payload,
                ).await()
        }.onFailure {
            Log.d(TAG, "Status send failed: ${it.message}")
            TransferDiagnostics.warn(
                "Ack",
                "Status send failed node=$sourceNodeId transferId=$transferId phase=$phase",
            )
        }
    }

    suspend fun sendAck(
        sourceNodeId: String,
        transferId: String,
        status: String,
        detail: String,
    ) {
        val payload =
            JSONObject()
                .apply {
                    put("id", transferId)
                    put("status", status)
                    put("detail", detail)
                }.toString()
                .toByteArray(Charsets.UTF_8)

        runCatching {
            sendReliableMessage(
                nodeId = sourceNodeId,
                path = TransferDataLayerContract.PATH_TRANSFER_ACK,
                payload = payload,
                attempts = ACK_MAX_SEND_ATTEMPTS,
                reconnectWindowMs = ACK_RETRY_WAIT_MS,
            )
        }.onFailure {
            Log.w(TAG, "Failed sending ACK to phone after retries: ${it.message}")
            TransferDiagnostics.warn(
                "Ack",
                "ACK send failed node=$sourceNodeId transferId=$transferId status=$status",
            )
        }
    }

    suspend fun sendMessage(
        sourceNodeId: String,
        path: String,
        payload: ByteArray,
    ) {
        sendReliableMessage(
            nodeId = sourceNodeId,
            path = path,
            payload = payload,
            attempts = REPLY_MAX_SEND_ATTEMPTS,
            reconnectWindowMs = REPLY_RETRY_WAIT_MS,
        )
    }

    private suspend fun sendReliableMessage(
        nodeId: String,
        path: String,
        payload: ByteArray,
        attempts: Int,
        reconnectWindowMs: Long,
    ) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                messageClient.sendMessage(nodeId, path, payload).await()
                if (attempt > 0) {
                    Log.d(TAG, "Recovered send for path=$path node=$nodeId on attempt=${attempt + 1}")
                    TransferDiagnostics.log(
                        "Ack",
                        "Recovered send path=$path node=$nodeId attempt=${attempt + 1}",
                    )
                }
                return
            } catch (t: Throwable) {
                lastError = t
                if (!isTargetNodeNotConnected(t) || attempt == attempts - 1) {
                    throw t
                }

                Log.w(
                    TAG,
                    "Target node temporarily disconnected for path=$path node=$nodeId. " +
                        "Waiting for reconnect before retry ${attempt + 2}/$attempts.",
                )
                TransferDiagnostics.warn(
                    "Ack",
                    "Node disconnected path=$path node=$nodeId retry=${attempt + 2}/$attempts",
                )
                val reconnected = awaitNodeConnection(nodeId, reconnectWindowMs)
                if (!reconnected) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: IllegalStateException("sendReliableMessage failed")
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
            delay(RETRY_DELAY_MS)
        }
        return false
    }

    private fun isTargetNodeNotConnected(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return "target node not connected" in message || "node not connected" in message
    }

    private companion object {
        const val TAG = "WatchDataLayerRepo"
        private const val RETRY_DELAY_MS = 500L
        private const val ACK_MAX_SEND_ATTEMPTS = 6
        private const val ACK_RETRY_WAIT_MS = 20_000L
        private const val REPLY_MAX_SEND_ATTEMPTS = 4
        private const val REPLY_RETRY_WAIT_MS = 8_000L
    }
}
