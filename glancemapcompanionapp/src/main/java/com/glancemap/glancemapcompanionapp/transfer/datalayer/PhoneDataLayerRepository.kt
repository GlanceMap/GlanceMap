@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package com.glancemap.glancemapcompanionapp.transfer.datalayer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.WatchNode
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the phone-side Wear Data Layer clients for the lifetime of [FileTransferService].
 *
 * Google Play services can be restarted independently from this process. Startup and discovery are
 * therefore deliberately single-flight and cancellation-safe: a stopped service must cancel work,
 * not turn a normal lifecycle event into a spurious Wear API error or a retry loop.
 */
internal class PhoneDataLayerRepository(
    context: Context,
) : CapabilityClient.OnCapabilityChangedListener {
    private val appContext = context.applicationContext
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }

    private val started = AtomicBoolean(false)
    private val listenersRegistered = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong(0L)
    private val lastRefreshAttemptElapsedMillis = AtomicLong(Long.MIN_VALUE)
    private val lastConnectionIssueElapsedMillis = AtomicLong(Long.MIN_VALUE)
    private val listenerLock = Any()
    private val startupMutex = Mutex()
    private val refreshMutex = Mutex()

    private val _watches = MutableStateFlow<List<WatchNode>>(emptyList())
    val watches = _watches.asStateFlow()

    private val _connectionIssue = MutableStateFlow<String?>(null)
    val connectionIssue = _connectionIssue.asStateFlow()

    private val _events =
        MutableSharedFlow<PhoneDataLayerEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
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

    suspend fun start() =
        withContext(Dispatchers.IO) {
            val generation = lifecycleGeneration.get()
            val didStart =
                startupMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (started.get()) return@withLock false
                    if (!isGooglePlayServicesAvailable()) return@withLock false

                    try {
                        registerListeners(generation)
                    } catch (cancellation: CancellationException) {
                        unregisterListeners()
                        throw cancellation
                    } catch (error: Throwable) {
                        unregisterListeners()
                        reportConnectionIssue("Wear OS is temporarily unavailable. Try again shortly.", error)
                        false
                    }
                }

            if (didStart) {
                refreshWatches(force = true)
            }
        }

    fun stop() {
        lifecycleGeneration.incrementAndGet()
        started.set(false)
        unregisterListeners()
    }

    /**
     * Coalesces refresh requests that commonly arrive from both service startup and screen binding.
     */
    suspend fun refreshWatches(force: Boolean = false) =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (!started.get()) return@withContext
            if (!isGooglePlayServicesAvailable()) return@withContext

            refreshMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!started.get()) return@withLock

                val now = SystemClock.elapsedRealtime()
                val previous = lastRefreshAttemptElapsedMillis.get()
                if (!force && previous != Long.MIN_VALUE && now - previous < REFRESH_COOLDOWN_MS) {
                    return@withLock
                }
                lastRefreshAttemptElapsedMillis.set(now)

                var queryFailed = false
                val info =
                    runCancellableWearableOperation(
                        operation = {
                            withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                                capabilityClient
                                    .getCapability(
                                        DataLayerPaths.WEAR_CAPABILITY,
                                        CapabilityClient.FILTER_REACHABLE,
                                    ).await()
                            }
                        },
                        onFailure = { error ->
                            queryFailed = true
                            reportConnectionIssue(
                                "Wear OS is temporarily unavailable. Try again shortly.",
                                error,
                            )
                        },
                    )

                if (info == null) {
                    if (!queryFailed) {
                        reportConnectionIssue("Wear OS is taking too long to respond. Try again shortly.")
                    }
                    return@withLock
                }

                currentCoroutineContext().ensureActive()
                if (!started.get()) return@withLock
                updateWatchList(info.nodes)
                clearConnectionIssue()
            }
        }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (!started.get()) return
        try {
            updateWatchList(capabilityInfo.nodes)
            clearConnectionIssue()
        } catch (error: Throwable) {
            Log.e(TAG, "Capability update failed", error)
            PhoneTransferDiagnostics.error("DataLayer", "Capability update failed", error)
        }
    }

    suspend fun sendMessage(
        nodeId: String,
        path: String,
        payload: ByteArray,
    ) = withContext(Dispatchers.IO) {
        requireStartedAndAvailable()
        var lastError: Throwable? = null
        PhoneTransferDiagnostics.log("DataLayer", "sendMessage path=$path node=$nodeId")
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            try {
                messageClient.sendMessage(nodeId, path, payload).await()
                clearConnectionIssue()
                if (attempt > 0) {
                    Log.d(TAG, "sendMessage recovered for path=$path node=$nodeId on attempt=${attempt + 1}")
                    PhoneTransferDiagnostics.log(
                        "DataLayer",
                        "Recovered send path=$path node=$nodeId attempt=${attempt + 1}",
                    )
                }
                return@withContext
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastError = error
                if (!isTargetNodeNotConnected(error) || attempt == MAX_SEND_ATTEMPTS - 1) {
                    reportConnectionIssueIfServiceFailure(error)
                    PhoneTransferDiagnostics.error(
                        "DataLayer",
                        "sendMessage failed path=$path node=$nodeId attempt=${attempt + 1}",
                        error,
                    )
                    throw error
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
                if (!reconnected) delay(SEND_RETRY_DELAY_MS)
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

    private fun registerListeners(generation: Long): Boolean =
        synchronized(listenerLock) {
            if (generation != lifecycleGeneration.get() || started.get()) return@synchronized false
            var messageListenerAdded = false
            try {
                messageClient.addListener(messageListener)
                messageListenerAdded = true
                capabilityClient.addListener(this, DataLayerPaths.WEAR_CAPABILITY)
                listenersRegistered.set(true)
                started.set(true)
                true
            } catch (error: Throwable) {
                if (messageListenerAdded) {
                    runCatching { messageClient.removeListener(messageListener) }
                }
                throw error
            }
        }

    private fun unregisterListeners() =
        synchronized(listenerLock) {
            if (!listenersRegistered.getAndSet(false)) return@synchronized
            try {
                messageClient.removeListener(messageListener)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to remove message listener", error)
            }
            try {
                capabilityClient.removeListener(this)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to remove capability listener", error)
            }
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
            currentCoroutineContext().ensureActive()
            val connectedNodes =
                runCancellableWearableOperation(
                    operation = {
                        withTimeoutOrNull(NODE_QUERY_TIMEOUT_MS) {
                            nodeClient.connectedNodes.await()
                        }
                    },
                    onFailure = ::reportConnectionIssueIfServiceFailure,
                )
            val connected = connectedNodes?.any { it.id == nodeId } == true
            if (connected) return true
            delay(SEND_RETRY_DELAY_MS)
        }
        return false
    }

    private fun requireStartedAndAvailable() {
        if (!started.get() || !isGooglePlayServicesAvailable()) {
            throw WearableApiUnavailableException("Wear OS is temporarily unavailable. Try again shortly.")
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean =
        try {
            val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
            if (status == ConnectionResult.SUCCESS) {
                true
            } else {
                reportConnectionIssue("Wear OS is temporarily unavailable. Try again shortly.")
                false
            }
        } catch (error: Throwable) {
            reportConnectionIssue("Wear OS is temporarily unavailable. Try again shortly.", error)
            false
        }

    private fun reportConnectionIssueIfServiceFailure(error: Throwable) {
        val message = error.message?.lowercase().orEmpty()
        val serviceFailureMarkers =
            listOf(
                "dead object",
                "binder",
                "google play services",
                "api not connected",
            )
        if (serviceFailureMarkers.any(message::contains)) {
            reportConnectionIssue("Wear OS connection was interrupted. Try again shortly.", error)
        }
    }

    private fun reportConnectionIssue(
        message: String,
        error: Throwable? = null,
    ) {
        _connectionIssue.value = message
        val now = SystemClock.elapsedRealtime()
        val previous = lastConnectionIssueElapsedMillis.get()
        if (previous != Long.MIN_VALUE && now - previous < CONNECTION_ISSUE_LOG_COOLDOWN_MS) return
        lastConnectionIssueElapsedMillis.set(now)

        if (error == null) {
            Log.w(TAG, message)
            PhoneTransferDiagnostics.warn("DataLayer", message)
        } else {
            Log.w(TAG, message, error)
            PhoneTransferDiagnostics.error("DataLayer", message, error)
        }
        _events.tryEmit(PhoneDataLayerEvent.Error(message))
    }

    private fun clearConnectionIssue() {
        _connectionIssue.value = null
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
        private const val REFRESH_TIMEOUT_MS = 10_000L
        private const val NODE_QUERY_TIMEOUT_MS = 2_000L
        private const val REFRESH_COOLDOWN_MS = 1_000L
        private const val CONNECTION_ISSUE_LOG_COOLDOWN_MS = 5_000L
    }
}

internal class WearableApiUnavailableException(
    message: String,
) : IllegalStateException(message)
