package com.glancemap.glancemapcompanionapp.transfer.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.glancemap.glancemapcompanionapp.FileTransferUiState
import com.glancemap.glancemapcompanionapp.WatchNode
import com.glancemap.glancemapcompanionapp.activehike.PhoneActiveHikeSnapshot
import com.glancemap.glancemapcompanionapp.activehike.decodePhoneActiveHikeSnapshot
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.FileExistenceChecker
import com.glancemap.glancemapcompanionapp.transfer.TransferStrategyFactory
import com.glancemap.glancemapcompanionapp.transfer.WatchFileDeleteRequester
import com.glancemap.glancemapcompanionapp.transfer.WatchInstalledMapsRequester
import com.glancemap.glancemapcompanionapp.transfer.WatchWifiStatusChecker
import com.glancemap.glancemapcompanionapp.transfer.datalayer.PhoneDataLayerEvent
import com.glancemap.glancemapcompanionapp.transfer.datalayer.PhoneDataLayerRepository
import com.glancemap.glancemapcompanionapp.transfer.service.internal.AckRegistry
import com.glancemap.glancemapcompanionapp.transfer.service.internal.BatchTransferRunner
import com.glancemap.glancemapcompanionapp.transfer.service.internal.HistoryStore
import com.glancemap.glancemapcompanionapp.transfer.service.internal.UiProgressUpdater
import com.glancemap.glancemapcompanionapp.transfer.strategy.HttpTransferServer
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferResult
import com.glancemap.glancemapcompanionapp.transfer.util.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class FileTransferService : LifecycleService() {
    private val binder = LocalBinder()
    private var transferJob: Job? = null

    private val _uiState = MutableStateFlow(FileTransferUiState())
    val uiState = _uiState.asStateFlow()
    private val _activeHikeSnapshot = MutableStateFlow<PhoneActiveHikeSnapshot?>(null)
    val activeHikeSnapshot = _activeHikeSnapshot.asStateFlow()

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var existenceChecker: FileExistenceChecker
    private lateinit var deleteRequester: WatchFileDeleteRequester
    private lateinit var wifiStatusChecker: WatchWifiStatusChecker
    private lateinit var installedMapsRequester: WatchInstalledMapsRequester

    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val wifiManager by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }

    private lateinit var dataLayerRepository: PhoneDataLayerRepository

    private lateinit var ackRegistry: AckRegistry
    private lateinit var historyStore: HistoryStore
    private lateinit var uiUpdater: UiProgressUpdater
    private lateinit var batchRunner: BatchTransferRunner
    private var dataLayerObserverJob: Job? = null
    private var reconnectPauseTimeoutJob: Job? = null
    private var reconnectPauseTransferId: String? = null

    // ✅ track current transfer for cancel propagation
    @Volatile private var activeTransferId: String? = null

    @Volatile private var activeTargetNodeId: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): FileTransferService = this@FileTransferService
    }

    override fun onCreate() {
        super.onCreate()

        notificationHelper = NotificationHelper(this).apply { createNotificationChannel() }
        dataLayerRepository = PhoneDataLayerRepository(this)
        configureTransferRequesters()

        ackRegistry = AckRegistry()
        historyStore = HistoryStore(this, _uiState)
        uiUpdater = UiProgressUpdater(_uiState, notificationHelper)

        batchRunner =
            BatchTransferRunner(
                context = this,
                uiState = _uiState,
                notificationHelper = notificationHelper,
                powerManager = powerManager,
                wifiManager = wifiManager,
                existenceChecker = existenceChecker,
                deleteRequester = deleteRequester,
                wifiStatusChecker = wifiStatusChecker,
                ackRegistry = ackRegistry,
                uiUpdater = uiUpdater,
                cancelTransferOnWatch = { nodeId, transferId ->
                    dataLayerRepository.sendCancelTransfer(nodeId, transferId)
                },
                requestPauseOnWatch = { nodeId, transferId ->
                    existenceChecker.markNodeRecovering(
                        nodeId = nodeId,
                        durationMs = WATCH_RECOVERY_WINDOW_MS,
                        reason = "user_pause",
                    )
                    sendCancelToWatchBestEffort(nodeId, transferId)
                },
                setActiveTransfer = { id, nodeId ->
                    activeTransferId = id
                    activeTargetNodeId = nodeId
                },
                clearActiveTransfer = {
                    activeTransferId = null
                    activeTargetNodeId = null
                },
                addHistoryItems = { items ->
                    _uiState.update { st -> st.copy(history = (items + st.history).take(10)) }
                    historyStore.save()
                },
            )

        historyStore.load()
        observeDataLayer()
    }

    private fun configureTransferRequesters() {
        existenceChecker =
            FileExistenceChecker { nodeId, path, payload ->
                dataLayerRepository.sendMessage(nodeId, path, payload)
            }
        deleteRequester =
            WatchFileDeleteRequester { nodeId, path, payload ->
                dataLayerRepository.sendMessage(nodeId, path, payload)
            }
        wifiStatusChecker =
            WatchWifiStatusChecker { nodeId, path, payload ->
                dataLayerRepository.sendMessage(nodeId, path, payload)
            }
        installedMapsRequester =
            WatchInstalledMapsRequester { nodeId, path, payload ->
                dataLayerRepository.sendMessage(nodeId, path, payload)
            }
    }

    override fun onDestroy() {
        cancelReconnectPauseEscalation()
        dataLayerObserverJob?.cancel()
        dataLayerRepository.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        searchForWatches()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean = super.onUnbind(intent)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_MULTI -> {
                val uris: ArrayList<Uri>? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(EXTRA_FILE_URIS, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(EXTRA_FILE_URIS)
                    }

                val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                val nodeName = intent.getStringExtra(EXTRA_NODE_DISPLAY_NAME)

                if (!uris.isNullOrEmpty() && nodeId != null && nodeName != null) {
                    startBatchTransfer(uris, WatchNode(nodeId, nodeName))
                }
            }

            ACTION_CANCEL -> cancelTransfer()
            ACTION_PAUSE -> pauseTransfer()
            ACTION_RESUME -> resumeTransfer()
        }

        return START_NOT_STICKY
    }

    fun loadFilesFromUris(
        context: Context,
        uris: List<Uri>,
    ) {
        batchRunner.loadFilesFromUris(context, uris)
    }

    fun clearSelectedFiles() {
        batchRunner.clearSelectedFiles()
    }

    private fun startBatchTransfer(
        fileUris: List<Uri>,
        targetNode: WatchNode,
    ) {
        cancelReconnectPauseEscalation()
        val previousJob = transferJob
        val previousTransferId = activeTransferId
        val previousNodeId = activeTargetNodeId
        previousJob?.cancel(CancellationException("Superseded by new batch"))
        if (previousJob != null && previousTransferId != null && previousNodeId != null) {
            PhoneTransferDiagnostics.warn(
                "Service",
                "Superseding previous batch oldId=$previousTransferId target=$previousNodeId",
            )
            existenceChecker.markNodeRecovering(
                nodeId = previousNodeId,
                durationMs = WATCH_RECOVERY_WINDOW_MS,
                reason = "batch_restart",
            )
            sendCancelToWatchBestEffort(previousNodeId, previousTransferId)
        }
        val crashHandler =
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Unhandled transfer coroutine failure", throwable)
                val message =
                    when (throwable) {
                        is OutOfMemoryError ->
                            "Not enough memory for this transfer. Try smaller files or fewer files at once."
                        else ->
                            throwable.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "Unexpected transfer failure"
                    }
                batchRunner.handleError(Exception(message), null)
                activeTransferId = null
                activeTargetNodeId = null
                notificationHelper.stopForeground()
            }
        transferJob =
            lifecycleScope.launch(Dispatchers.IO + crashHandler) {
                try {
                    if (previousJob != null) {
                        PhoneTransferDiagnostics.warn("Service", "Waiting for previous batch to stop before restart")
                        runCatching { previousJob.join() }
                            .onFailure {
                                PhoneTransferDiagnostics.warn(
                                    "Service",
                                    "Previous batch join ended with ${it.message}",
                                )
                            }
                    }
                    PhoneTransferDiagnostics.log(
                        "Service",
                        "Launch batch transfer files=${fileUris.size} target=${targetNode.displayName}",
                    )
                    batchRunner.runBatch(
                        fileUris = fileUris,
                        targetNode = targetNode,
                        strategyFactory = TransferStrategyFactory,
                    )
                } catch (ce: CancellationException) {
                    PhoneTransferDiagnostics.warn("Service", "Transfer coroutine cancelled")
                    sendCancelToWatchBestEffort()
                    batchRunner.handleCancel("Batch")
                } catch (e: Exception) {
                    PhoneTransferDiagnostics.error("Service", "Transfer coroutine failed", e)
                    batchRunner.handleError(e, null)
                } catch (t: Throwable) {
                    val wrapped =
                        Exception(
                            t.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "Unexpected transfer failure",
                            t,
                        )
                    batchRunner.handleError(wrapped, null)
                } finally {
                    activeTransferId = null
                    activeTargetNodeId = null
                    notificationHelper.stopForeground()
                }
            }
    }

    fun cancelTransfer() {
        if (!batchRunner.requestCancel()) return
        PhoneTransferDiagnostics.warn("Service", "Cancel requested by user")
        cancelReconnectPauseEscalation()
        val transferId = activeTransferId
        val nodeId = activeTargetNodeId
        if (nodeId != null) {
            existenceChecker.markNodeRecovering(
                nodeId = nodeId,
                durationMs = WATCH_RECOVERY_WINDOW_MS,
                reason = "user_cancel",
            )
        }
        ackRegistry.completeAll(TransferResult(false, "Cancelled"))
        batchRunner.abortActiveTransfer()
        sendCancelToWatchBestEffort(nodeId, transferId)
        transferJob?.cancel(CancellationException("Cancelled by user"))
    }

    fun pauseTransfer() {
        if (batchRunner.pauseTransfer()) {
            Log.d(TAG, "Transfer paused by user")
            PhoneTransferDiagnostics.warn("Service", "Pause requested by user")
            cancelReconnectPauseEscalation()
        }
    }

    fun resumeTransfer() {
        if (batchRunner.resumeTransfer()) {
            Log.d(TAG, "Transfer resumed by user")
            PhoneTransferDiagnostics.log("Service", "Resume requested by user")
            cancelReconnectPauseEscalation()
        }
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        historyStore.save()
    }

    private fun sendCancelToWatchBestEffort(
        targetNodeId: String? = activeTargetNodeId,
        transferId: String? = activeTransferId,
    ) {
        val id = transferId ?: return
        val node = targetNodeId ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                PhoneTransferDiagnostics.warn("Service", "Send cancel to watch node=$node id=$id")
                dataLayerRepository.sendCancelTransfer(node, id)
            }.onFailure {
                Log.w(TAG, "Cancel propagation failed: ${it.message}")
                PhoneTransferDiagnostics.warn("Service", "Cancel propagation failed msg=${it.message}")
            }
        }
    }

    fun searchForWatches() {
        lifecycleScope.launch(Dispatchers.IO) {
            dataLayerRepository.refreshWatches()
        }
    }

    fun onWatchSelected(watch: WatchNode) {
        _uiState.update { it.copy(selectedWatch = watch) }
    }

    suspend fun requestInstalledMaps(nodeId: String): WatchInstalledMapsRequester.Result = installedMapsRequester.list(nodeId)

    private fun handleStatusUpdate(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val transferId = json.optString("id", "").trim()
            val currentTransferId = activeTransferId
            if (transferId.isNotBlank()) {
                if (currentTransferId == null) {
                    PhoneTransferDiagnostics.warn(
                        "Service",
                        "Ignoring stale watch status id=$transferId phase=${json.optString("phase", "")}",
                    )
                    return
                }
                if (transferId != currentTransferId) {
                    PhoneTransferDiagnostics.warn(
                        "Service",
                        "Ignoring watch status id=$transferId active=$currentTransferId phase=${json.optString("phase", "")}",
                    )
                    return
                }
            }
            val phase = json.optString("phase", "").uppercase(Locale.US)
            val detail = json.optString("detail", "").trim()

            when (phase) {
                "PAUSED" -> {
                    val pauseText = detail.ifBlank { "Paused (waiting for network)" }
                    val waitingForReconnect = isReconnectPauseDetail(pauseText)
                    val progressText =
                        if (waitingForReconnect) {
                            buildReconnectProgressText(_uiState.value.progressText)
                        } else {
                            mergeProgressPrefix(_uiState.value.progressText, pauseText)
                        }
                    PhoneTransferDiagnostics.warn("Service", "Watch status PAUSED detail=$pauseText")
                    _uiState.update { st ->
                        if (st.isPaused && st.canResume) {
                            st
                        } else {
                            st.copy(
                                isPaused = true,
                                canResume = false,
                                pauseReason = pauseText,
                                statusMessage = if (waitingForReconnect) "Waiting for watch reconnect…" else pauseText,
                                progressText = progressText,
                            )
                        }
                    }
                    notificationHelper.updateProgress(
                        (_uiState.value.progress * 100).toInt(),
                        progressText,
                        _uiState.value.isPaused,
                    )
                    if (waitingForReconnect) {
                        scheduleReconnectPauseEscalation(
                            transferId = transferId.ifBlank { currentTransferId },
                            pauseText = pauseText,
                        )
                    } else {
                        cancelReconnectPauseEscalation()
                    }
                }

                "RESUMED" -> {
                    cancelReconnectPauseEscalation()
                    val resumeText = detail.ifBlank { "Resuming…" }
                    val progressText = mergeProgressPrefix(_uiState.value.progressText, resumeText)
                    PhoneTransferDiagnostics.log("Service", "Watch status RESUMED detail=$resumeText")
                    _uiState.update { st ->
                        if (st.isPaused && st.canResume) {
                            st
                        } else {
                            st.copy(
                                isPaused = false,
                                canResume = false,
                                pauseReason = "",
                                statusMessage = resumeText,
                                progressText = progressText,
                            )
                        }
                    }
                    notificationHelper.updateProgress(
                        (_uiState.value.progress * 100).toInt(),
                        _uiState.value.progressText.ifBlank { progressText },
                        _uiState.value.isPaused,
                    )
                }
            }

            if (detail.isNotBlank() && phase != "PAUSED" && phase != "RESUMED") {
                cancelReconnectPauseEscalation()
                PhoneTransferDiagnostics.log("Service", "Watch status phase=$phase detail=$detail")
                val mergedText = mergeProgressPrefix(_uiState.value.progressText, detail)
                _uiState.update { it.copy(statusMessage = detail, progressText = mergedText) }
                notificationHelper.updateProgress(
                    (_uiState.value.progress * 100).toInt(),
                    mergedText,
                    _uiState.value.isPaused,
                )
            }
        }.onFailure {
            Log.w(TAG, "Status parse failed: ${it.message}")
            PhoneTransferDiagnostics.error("Service", "Status parse failed", it)
        }
    }

    private fun isReconnectPauseDetail(detail: String): Boolean {
        val normalized = detail.lowercase(Locale.US)
        return normalized.contains("waiting to resume") ||
            normalized.contains("waiting for wi-fi") ||
            normalized.contains("network lost") ||
            normalized.contains("connection interrupted")
    }

    private fun buildReconnectProgressText(existingText: String): String {
        val lines =
            existingText
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        if (lines.any { it.contains("Waiting for watch reconnect", ignoreCase = true) }) {
            return existingText
        }

        val fileLine = lines.firstOrNull { it.startsWith("File ", ignoreCase = true) }
        val metricLine = extractMetricLine(lines)

        return listOfNotNull(fileLine, "Waiting for watch reconnect…", metricLine)
            .joinToString("\n")
            .ifBlank { "Waiting for watch reconnect…" }
    }

    private fun mergeProgressPrefix(
        existingText: String,
        detail: String,
    ): String {
        if (detail.isBlank()) return existingText
        val lines =
            existingText
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        val firstLine = lines.firstOrNull { it.startsWith("File ", ignoreCase = true) }
        val metricLine = extractMetricLine(lines)

        return listOfNotNull(firstLine, detail, metricLine)
            .joinToString("\n")
            .ifBlank { detail }
    }

    private fun extractMetricLine(lines: List<String>): String? =
        lines.lastOrNull {
            it.startsWith("HTTP:", ignoreCase = true) ||
                it.startsWith("Channel:", ignoreCase = true) ||
                it.startsWith("Message:", ignoreCase = true)
        }

    @Suppress("CyclomaticComplexMethod")
    private fun observeDataLayer() {
        dataLayerObserverJob?.cancel()
        dataLayerObserverJob =
            lifecycleScope.launch {
                runCatching { dataLayerRepository.start() }
                    .onFailure {
                        Log.e(TAG, "DataLayer start failed", it)
                        PhoneTransferDiagnostics.error("Service", "DataLayer start failed", it)
                    }

                launch {
                    dataLayerRepository.watches.collect { watches ->
                        _uiState.update {
                            it.copy(
                                availableWatches = watches,
                                statusMessage = if (watches.isNotEmpty()) "Select a watch" else "No watches found.",
                            )
                        }
                    }
                }

                launch {
                    dataLayerRepository.events.collect { event ->
                        when (event) {
                            is PhoneDataLayerEvent.TransferStatus -> handleStatusUpdate(event.payload)
                            is PhoneDataLayerEvent.TransferAck -> {
                                cancelReconnectPauseEscalation()
                                ackRegistry.handleAck(event.payload)
                            }
                            is PhoneDataLayerEvent.PingResult -> existenceChecker.handlePingResult(event.payload)
                            is PhoneDataLayerEvent.WifiStatusResult ->
                                wifiStatusChecker.handleWifiStatusResult(event.payload)
                            is PhoneDataLayerEvent.ExistsResult -> existenceChecker.handleExistsResult(event.payload)
                            is PhoneDataLayerEvent.BatchExistsResult -> existenceChecker.handleBatchExistsResult(event.payload)
                            is PhoneDataLayerEvent.DeleteFileResult -> deleteRequester.handleDeleteResult(event.payload)
                            is PhoneDataLayerEvent.MapListResult -> installedMapsRequester.handleMapListResult(event.payload)
                            is PhoneDataLayerEvent.ActiveHikeSnapshot ->
                                handleActiveHikeSnapshot(
                                    sourceNodeId = event.sourceNodeId,
                                    payload = event.payload,
                                )
                            is PhoneDataLayerEvent.Error -> {
                                _uiState.update { st -> st.copy(statusMessage = event.message) }
                            }
                        }
                    }
                }
            }
    }

    private fun handleActiveHikeSnapshot(
        sourceNodeId: String,
        payload: ByteArray,
    ) {
        val incoming = decodePhoneActiveHikeSnapshot(sourceNodeId, payload)
        if (incoming == null) {
            PhoneTransferDiagnostics.warn(
                "ActiveHike",
                "Ignored invalid active-hike snapshot from node=$sourceNodeId",
            )
            return
        }
        val current = _activeHikeSnapshot.value
        val currentRecordedAtEpochMillis = current?.snapshot?.recordedAtEpochMillis ?: Long.MIN_VALUE
        if (currentRecordedAtEpochMillis > incoming.snapshot.recordedAtEpochMillis) return

        _activeHikeSnapshot.value = incoming
    }

    private fun scheduleReconnectPauseEscalation(
        transferId: String?,
        pauseText: String,
    ) {
        val id = transferId?.takeIf { it.isNotBlank() } ?: return
        if (reconnectPauseTransferId == id && reconnectPauseTimeoutJob?.isActive == true) return

        cancelReconnectPauseEscalation()
        reconnectPauseTransferId = id
        reconnectPauseTimeoutJob =
            lifecycleScope.launch(Dispatchers.IO) {
                delay(RECONNECT_PAUSE_TIMEOUT_MS)
                val state = _uiState.value
                val currentId = activeTransferId
                val stillWaitingForReconnect =
                    currentId == id &&
                        state.isPaused &&
                        !state.canResume &&
                        (
                            isReconnectPauseDetail(state.pauseReason.ifBlank { state.statusMessage }) ||
                                state.progressText.contains("Waiting for watch reconnect", ignoreCase = true)
                        )

                if (!stillWaitingForReconnect) return@launch

                PhoneTransferDiagnostics.warn(
                    "Service",
                    "Reconnect pause timed out id=$id detail=$pauseText",
                )
                val completed =
                    ackRegistry.complete(
                        id,
                        TransferResult(
                            false,
                            "${HttpTransferServer.RESULT_HTTP_RECONNECT_TIMEOUT_PREFIX} detail=$pauseText",
                        ),
                    )
                if (completed) {
                    PhoneTransferDiagnostics.warn(
                        "Service",
                        "Reconnect pause escalated to fresh retry id=$id",
                    )
                }
            }
    }

    private fun cancelReconnectPauseEscalation() {
        reconnectPauseTimeoutJob?.cancel()
        reconnectPauseTimeoutJob = null
        reconnectPauseTransferId = null
    }

    companion object {
        const val TAG = "FileTransferService"
        private const val RECONNECT_PAUSE_TIMEOUT_MS = 45_000L
        private const val WATCH_RECOVERY_WINDOW_MS = 90_000L

        const val ACTION_START_MULTI = "com.glancemap.glancemapcompanionapp.START_TRANSFER_MULTI"
        const val ACTION_CANCEL = "com.glancemap.glancemapcompanionapp.CANCEL_TRANSFER"
        const val ACTION_PAUSE = "com.glancemap.glancemapcompanionapp.PAUSE_TRANSFER"
        const val ACTION_RESUME = "com.glancemap.glancemapcompanionapp.RESUME_TRANSFER"

        const val EXTRA_FILE_URIS = "com.glancemap.glancemapcompanionapp.FILE_URIS"
        const val EXTRA_NODE_ID = "com.glancemap.glancemapcompanionapp.NODE_ID"
        const val EXTRA_NODE_DISPLAY_NAME = "com.glancemap.glancemapcompanionapp.NODE_DISPLAY_NAME"
    }
}
