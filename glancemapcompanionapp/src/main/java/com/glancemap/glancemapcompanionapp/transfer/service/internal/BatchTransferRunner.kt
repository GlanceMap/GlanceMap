package com.glancemap.glancemapcompanionapp.transfer.service.internal

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.FileTransferHistoryItem
import com.glancemap.glancemapcompanionapp.FileTransferUiState
import com.glancemap.glancemapcompanionapp.WatchNode
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.FileExistenceChecker
import com.glancemap.glancemapcompanionapp.transfer.TransferStrategyFactory
import com.glancemap.glancemapcompanionapp.transfer.TransferStrategyKind
import com.glancemap.glancemapcompanionapp.transfer.WatchFileDeleteRequester
import com.glancemap.glancemapcompanionapp.transfer.WatchWifiStatus
import com.glancemap.glancemapcompanionapp.transfer.WatchWifiStatusChecker
import com.glancemap.glancemapcompanionapp.transfer.strategy.ChannelClientStrategy
import com.glancemap.glancemapcompanionapp.transfer.strategy.HttpTransferServer
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferMetadata
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferResult
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferStrategy
import com.glancemap.glancemapcompanionapp.transfer.util.NotificationHelper
import com.glancemap.glancemapcompanionapp.transfer.util.TransferUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Suppress("LongParameterList")
internal class BatchTransferRunner(
    private val context: Context,
    private val uiState: MutableStateFlow<FileTransferUiState>,
    private val notificationHelper: NotificationHelper,
    private val powerManager: PowerManager,
    private val wifiManager: WifiManager,
    private val existenceChecker: FileExistenceChecker,
    private val deleteRequester: WatchFileDeleteRequester,
    private val wifiStatusChecker: WatchWifiStatusChecker,
    private val ackRegistry: AckRegistry,
    private val uiUpdater: UiProgressUpdater,
    private val cancelTransferOnWatch: suspend (nodeId: String, transferId: String) -> Unit,
    private val requestPauseOnWatch: (nodeId: String, transferId: String) -> Unit,
    private val setActiveTransfer: (transferId: String, nodeId: String) -> Unit,
    private val clearActiveTransfer: () -> Unit,
    private val addHistoryItems: (List<FileTransferHistoryItem>) -> Unit,
) {
    private val paused = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val httpPauseRequested = AtomicBoolean(false)
    private val historyIdCounter = AtomicLong(System.currentTimeMillis())
    private val freshHttpRetryCounts = mutableMapOf<String, Int>()
    private val freshHttpRetryBestSentBytes = mutableMapOf<String, Long>()

    @Volatile private var activeStrategy: TransferStrategy? = null

    @Volatile private var sharedHttpStrategyRef: HttpTransferServer? = null

    @Volatile private var currentTransferIdInternal: String? = null

    @Volatile private var currentTargetNodeIdInternal: String? = null

    @Volatile private var currentFileNameInternal: String? = null

    fun loadFilesFromUris(
        ctx: Context,
        uris: List<Uri>,
    ) {
        if (uris.isEmpty()) {
            clearSelectedFiles(clearStatusMessage = true)
            return
        }

        val supportedItems =
            uris.mapNotNull { uri ->
                val details = TransferUtils.getTransferFileDetails(ctx, uri) ?: return@mapNotNull null
                val (rawName, size) = details
                val name = rawName.ifBlank { "file.bin" }
                if (!isSupportedTransferFileName(name)) return@mapNotNull null
                FileItem(uri = uri, displayName = name, size = size)
            }

        val skippedCount = (uris.size - supportedItems.size).coerceAtLeast(0)

        if (supportedItems.isEmpty()) {
            clearSelectedFiles(statusMessage = "No compatible files selected (.gpx, .map, .poi, .rd5, .hgt, .hgt.gz).")
            return
        }

        val status =
            if (skippedCount > 0) {
                "Selected ${supportedItems.size} file(s); skipped $skippedCount unsupported."
            } else {
                "Selected ${supportedItems.size} file(s)"
            }

        uiState.update {
            it.copy(
                selectedFileUris = supportedItems.map { item -> item.uri },
                selectedFileDisplayNames = supportedItems.map { item -> item.displayName },
                statusMessage = status,
            )
        }
    }

    fun clearSelectedFiles(
        statusMessage: String? = null,
        clearStatusMessage: Boolean = false,
    ) {
        uiState.update {
            it.copy(
                selectedFileUris = emptyList(),
                selectedFileDisplayNames = emptyList(),
                statusMessage =
                    when {
                        statusMessage != null -> statusMessage
                        clearStatusMessage -> ""
                        else -> it.statusMessage
                    },
            )
        }
    }

    suspend fun runBatch(
        fileUris: List<Uri>,
        targetNode: WatchNode,
        strategyFactory: TransferStrategyFactory,
    ) {
        val batchStartMs = SystemClock.elapsedRealtime()
        paused.set(false)
        cancelRequested.set(false)
        httpPauseRequested.set(false)
        freshHttpRetryCounts.clear()
        freshHttpRetryBestSentBytes.clear()
        activeStrategy = null
        sharedHttpStrategyRef = null
        uiUpdater.reset()
        PhoneTransferDiagnostics.log(
            "Batch",
            "Start batch files=${fileUris.size} targetNode=${targetNode.displayName}(${targetNode.id})",
        )

        uiState.update {
            it.copy(
                isTransferring = true,
                isPaused = false,
                canResume = false,
                pauseReason = "",
                statusMessage = "Preparing…",
                progress = 0f,
                progressText = "Starting…",
            )
        }
        notificationHelper.startForeground("Preparing…")

        val buildStartMs = SystemClock.elapsedRealtime()
        val items = buildFileItems(fileUris)
        Log.d(TAG, "Built ${items.size} transfer item(s) in ${SystemClock.elapsedRealtime() - buildStartMs}ms")
        PhoneTransferDiagnostics.log(
            "Batch",
            "Built ${items.size} item(s) in ${SystemClock.elapsedRealtime() - buildStartMs}ms",
        )
        uiUpdater.update(0f, "Checking existing files on watch…")

        val preflightStartMs = SystemClock.elapsedRealtime()
        val (conflicts, itemsToTransfer) =
            preflightFilterExistingFiles(
                nodeId = targetNode.id,
                items = items,
            )
        Log.d(
            TAG,
            "Preflight finished in ${SystemClock.elapsedRealtime() - preflightStartMs}ms " +
                "(conflicts=${conflicts.size}, transfer=${itemsToTransfer.size})",
        )
        PhoneTransferDiagnostics.log(
            "Batch",
            "Preflight finished in ${SystemClock.elapsedRealtime() - preflightStartMs}ms conflicts=${conflicts.size} transfer=${itemsToTransfer.size}",
        )

        if (conflicts.isNotEmpty()) {
            val skippedHistory =
                conflicts.map {
                    FileTransferHistoryItem(nextHistoryId(), it.displayName, "Skipped (already exists)", false)
                }
            addHistoryItems(skippedHistory)
        }

        if (itemsToTransfer.isEmpty()) {
            val msg =
                if (conflicts.size == 1) {
                    "'${conflicts.first().displayName}' already exists on the watch. Nothing to transfer."
                } else {
                    "${conflicts.size} files already exist on the watch. Nothing to transfer."
                }

            uiState.update {
                it.copy(
                    statusMessage = msg,
                    progressText = "",
                    isTransferring = false,
                    isPaused = false,
                    canResume = false,
                    pauseReason = "",
                    progress = 0f,
                )
            }
            notificationHelper.cancelForeground()
            return
        }

        if (conflicts.isNotEmpty()) {
            uiUpdater.update(0f, "Skipped ${conflicts.size} file(s) already on watch. Transferring ${itemsToTransfer.size}…")
        }

        var selectionContext =
            strategyFactory.buildSelectionContext(
                context = context,
                totalSizesBytes = itemsToTransfer.map { it.size },
            )
        var watchWifiStatus: WatchWifiStatus? = null
        val initialTransferPlans =
            itemsToTransfer.map { item ->
                PlannedTransfer(
                    item = item,
                    strategyKind = strategyFactory.decide(item.size, selectionContext),
                )
            }

        if (
            selectionContext.wifiAvailable &&
            initialTransferPlans.any { TransferStrategyFactory.usesWifi(it.strategyKind) }
        ) {
            uiUpdater.update(0f, "Checking watch Wi-Fi…")
            watchWifiStatus = wifiStatusChecker.check(targetNode.id)
            when {
                watchWifiStatus == null -> {
                    PhoneTransferDiagnostics.warn(
                        "Batch",
                        "Watch Wi-Fi preflight unavailable; keeping HTTP eligible",
                    )
                }

                !watchWifiStatus.wifiAvailable -> {
                    PhoneTransferDiagnostics.warn(
                        "Batch",
                        "Watch Wi-Fi unavailable reason=${watchWifiStatus.reason}; disabling HTTP for this batch",
                    )
                    selectionContext =
                        selectionContext.copy(
                            wifiAvailable = false,
                            preferSharedHttpForBatch = false,
                        )
                    uiUpdater.update(0f, "Watch Wi-Fi is off. Using Bluetooth when possible…")
                }

                else -> {
                    PhoneTransferDiagnostics.log(
                        "Batch",
                        "Watch Wi-Fi preflight ok reason=${watchWifiStatus.reason}",
                    )
                }
            }
        }

        val transferPlans =
            itemsToTransfer.map { item ->
                PlannedTransfer(
                    item = item,
                    strategyKind = strategyFactory.decide(item.size, selectionContext),
                )
            }
        val strategyCounts = transferPlans.groupingBy { it.strategyKind }.eachCount()
        Log.d(
            TAG,
            "Strategy plan for batch: wifiAvailable=${selectionContext.wifiAvailable}, " +
                "preferSharedHttp=${selectionContext.preferSharedHttpForBatch}, counts=$strategyCounts",
        )
        PhoneTransferDiagnostics.log(
            "Batch",
            "Strategy plan wifiAvailable=${selectionContext.wifiAvailable} preferSharedHttp=${selectionContext.preferSharedHttpForBatch} counts=$strategyCounts",
        )

        val anyRequiresWifi = transferPlans.any { TransferStrategyFactory.usesWifi(it.strategyKind) }

        val wakeLock =
            powerManager
                .newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "GlanceMap:FileTransferBatch",
                ).apply { setReferenceCounted(false) }

        val wifiLock =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GlanceMap:WifiTransferBatch")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GlanceMap:WifiTransferBatch")
            }.apply { setReferenceCounted(false) }
        var sharedHttpStrategy: HttpTransferServer? = null
        var preferChannelForRemainingBatch = false
        sharedHttpStrategyRef = null

        try {
            wakeLock.acquire(2 * 60 * 60 * 1000L)
            if (anyRequiresWifi) wifiLock.acquire()

            if (anyRequiresWifi) {
                if (!selectionContext.wifiAvailable) {
                    throw IllegalStateException(wifiRequiredMessage(watchWifiStatus))
                }
                val ip = TransferUtils.getWifiIpAddress(context)
                if (ip.isNullOrBlank()) {
                    throw IllegalStateException(wifiRequiredMessage(watchWifiStatus))
                }
            }

            // ✅ sequential transfer (same behavior as before)
            for ((index, plan) in transferPlans.withIndex()) {
                val item = plan.item
                val n = transferPlans.size
                val prefix = "File ${index + 1}/$n: ${item.displayName}"
                val fileStartMs = SystemClock.elapsedRealtime()
                val effectiveStrategyKind =
                    if (
                        preferChannelForRemainingBatch &&
                        plan.strategyKind == TransferStrategyKind.HTTP &&
                        item.size <= TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES
                    ) {
                        TransferStrategyKind.CHANNEL
                    } else {
                        plan.strategyKind
                    }

                val transferId = UUID.randomUUID().toString()
                val safeFileName = Uri.encode(item.displayName)
                val isMapLikeFile = isMapLikeTransferFile(item.displayName)
                throwIfCancellationRequested()
                var strategy: TransferStrategy =
                    when (effectiveStrategyKind) {
                        TransferStrategyKind.HTTP ->
                            sharedHttpStrategy ?: HttpTransferServer().also {
                                sharedHttpStrategy = it
                                sharedHttpStrategyRef = it
                            }

                        else -> strategyFactory.create(effectiveStrategyKind)
                    }
                val strategyReason =
                    when {
                        effectiveStrategyKind != plan.strategyKind ->
                            "override=preferChannelForRemainingBatch plannedWas=${plan.strategyKind}"
                        item.size <= 0L ->
                            if (selectionContext.wifiAvailable) "unknownSize+wifi→HTTP" else "unknownSize+noWifi→CHANNEL"
                        TransferStrategyFactory.isMessageClientEligible(item.size) ->
                            "size≤80KB→MESSAGE"
                        selectionContext.preferSharedHttpForBatch &&
                            TransferStrategyFactory.isSharedHttpBatchEligible(item.size) ->
                            "batchHTTP(wifi+multiFile+≥1MB)"
                        item.size <= TransferStrategyFactory.CHANNEL_PREFERRED_MAX_BYTES ->
                            "size≤2MB→CHANNEL"
                        selectionContext.wifiAvailable ->
                            "size>2MB+wifi→HTTP"
                        item.size <= TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES ->
                            "size>2MB+noWifi≤50MB→CHANNEL"
                        else ->
                            "size>50MB+noWifi→HTTP(noFallback)"
                    }

                @Suppress("DEPRECATION")
                val wifiInfo = if (effectiveStrategyKind == TransferStrategyKind.HTTP) wifiManager.connectionInfo else null
                val wifiRssi = wifiInfo?.rssi
                val wifiLinkSpeed = wifiInfo?.linkSpeed

                Log.d(
                    TAG,
                    "Starting ${item.displayName} via $effectiveStrategyKind " +
                        "(size=${item.size} bytes, batchIndex=${index + 1}/$n, reason=$strategyReason)",
                )
                PhoneTransferDiagnostics.log(
                    "Batch",
                    "File ${index + 1}/$n start name=${item.displayName} strategy=$effectiveStrategyKind " +
                        "reason=$strategyReason size=${item.size}" +
                        (if (wifiRssi != null) " rssi=${wifiRssi}dBm linkSpeed=${wifiLinkSpeed}Mbps" else ""),
                )
                val hashPhaseWeight =
                    when {
                        effectiveStrategyKind == TransferStrategyKind.HTTP && isMapLikeFile -> 0.12f
                        effectiveStrategyKind == TransferStrategyKind.HTTP -> 0.05f
                        effectiveStrategyKind == TransferStrategyKind.MESSAGE -> 0f // in-memory, instant
                        else -> 0.03f // Channel: small files, fast hash
                    }
                val fileSha256 =
                    if (effectiveStrategyKind == TransferStrategyKind.MESSAGE) {
                        // Message: checksum is computed inline from the in-memory bytes
                        awaitIfPaused()
                        null
                    } else {
                        uiUpdater.update(
                            (index.toFloat() / n.toFloat()).coerceIn(0f, 1f),
                            "$prefix\nPreparing checksum…",
                        )
                        TransferUtils.computeSha256Cached(
                            context = context,
                            uri = item.uri,
                            knownDisplayName = item.displayName,
                            knownSize = item.size,
                            awaitIfPaused = { awaitIfPaused() },
                            onProgress = { hashProgress, hashText ->
                                val p = hashProgress.coerceIn(0f, 1f)
                                val overall =
                                    (
                                        index.toFloat() + (hashPhaseWeight * p)
                                    ) / n.toFloat()
                                uiUpdater.update(
                                    overall.coerceIn(0f, 1f),
                                    "$prefix\n$hashText",
                                )
                            },
                        ) ?: throw IllegalStateException("Failed to compute SHA-256 for ${item.displayName}")
                    }

                var metadata =
                    TransferMetadata(
                        transferId = transferId,
                        safeFileName = safeFileName,
                        displayFileName = item.displayName,
                        totalSize = item.size,
                        isMapFile = isMapLikeFile,
                        checksumSha256 = fileSha256,
                    )

                val progressReporter: (Float, String) -> Unit = { fileProg, text ->
                    val p = fileProg.coerceIn(0f, 1f)
                    val weighted = hashPhaseWeight + ((1f - hashPhaseWeight) * p)
                    val overall = ((index.toFloat() + weighted) / n.toFloat()).coerceIn(0f, 1f)
                    uiUpdater.update(overall, "$prefix\n$text")
                }
                var result: TransferResult
                var fileAttemptCount = 0
                val fileStrategiesAttempted = mutableListOf<String>()
                while (true) {
                    fileAttemptCount++
                    fileStrategiesAttempted += effectiveStrategyKind.name
                    result =
                        runTransferAttempt(
                            strategy = strategy,
                            fileUri = item.uri,
                            targetNodeId = targetNode.id,
                            metadata = metadata,
                            onProgress = progressReporter,
                        )
                    throwIfCancellationRequested()

                    if (isManualHttpPauseResult(result)) {
                        PhoneTransferDiagnostics.warn(
                            "Batch",
                            "HTTP pause acknowledged file=${item.displayName}",
                        )
                        uiUpdater.update(
                            uiState.value.progress.coerceIn(0f, 1f),
                            buildManualPauseProgressText(prefix, uiState.value.progressText),
                        )
                        awaitIfPaused()
                        throwIfCancellationRequested()

                        PhoneTransferDiagnostics.log(
                            "Batch",
                            "Restart paused HTTP transfer file=${item.displayName}",
                        )
                        existenceChecker.markNodeRecovering(
                            nodeId = targetNode.id,
                            durationMs = MANUAL_HTTP_RESUME_RECOVERY_WINDOW_MS,
                            reason = "manual_http_resume:${item.displayName}",
                        )
                        uiUpdater.update(
                            uiState.value.progress.coerceIn(0f, 1f),
                            "$prefix\nResuming current file from partial…",
                        )
                        existenceChecker.awaitResponsive(
                            nodeId = targetNode.id,
                            timeoutMs = MANUAL_HTTP_RESUME_WAIT_MS,
                            reason = "manual_http_resume:${item.displayName}",
                        )
                        delay(MANUAL_HTTP_RESUME_DELAY_MS)
                        runCatching { sharedHttpStrategy?.close() }
                        sharedHttpStrategy = null
                        val freshHttpStrategy = HttpTransferServer()
                        sharedHttpStrategy = freshHttpStrategy
                        sharedHttpStrategyRef = freshHttpStrategy
                        strategy = freshHttpStrategy
                        metadata = metadata.copy(transferId = UUID.randomUUID().toString())
                        continue
                    }

                    if (shouldRetryHttpWithFreshEndpoint(strategy, item, result)) {
                        supersedeWatchTransfer(
                            targetNodeId = targetNode.id,
                            transferId = metadata.transferId,
                            fileName = item.displayName,
                            reason = "fresh_http_retry",
                        )
                        existenceChecker.markNodeRecovering(
                            nodeId = targetNode.id,
                            durationMs = WATCH_RECOVERY_WINDOW_MS,
                            reason = "fresh_http_retry:${item.displayName}",
                        )
                        Log.d(
                            TAG,
                            "Retrying ${item.displayName} with a fresh HTTP endpoint. " +
                                "Reason=${result.message}",
                        )
                        PhoneTransferDiagnostics.warn(
                            "Batch",
                            "Retry with fresh HTTP endpoint file=${item.displayName} reason=${result.message}",
                        )
                        uiUpdater.update(
                            (index.toFloat() / n.toFloat()).coerceIn(0f, 1f),
                            "$prefix\n${buildHttpRetryText(result)}",
                        )
                        uiUpdater.update(
                            (index.toFloat() / n.toFloat()).coerceIn(0f, 1f),
                            "$prefix\nWaiting for watch to reconnect before retrying…",
                        )
                        val watchResponsive =
                            existenceChecker.awaitResponsive(
                                nodeId = targetNode.id,
                                timeoutMs = WATCH_RECOVERY_WAIT_MS,
                                reason = "fresh_http_retry:${item.displayName}",
                            )
                        delay(FRESH_HTTP_RETRY_DELAY_MS)
                        incrementFreshHttpRetryCount(item)

                        runCatching { sharedHttpStrategy?.close() }
                        sharedHttpStrategy = null

                        if (!watchResponsive) {
                            PhoneTransferDiagnostics.warn(
                                "Batch",
                                "Watch still not responsive before HTTP retry file=${item.displayName}",
                            )
                            result =
                                TransferResult(
                                    success = false,
                                    message = "${HttpTransferServer.RESULT_HTTP_RECONNECT_TIMEOUT_PREFIX} detail=Watch did not answer after reconnect for ${item.displayName}",
                                )
                            break
                        }

                        val freshHttpStrategy = HttpTransferServer()
                        sharedHttpStrategy = freshHttpStrategy
                        sharedHttpStrategyRef = freshHttpStrategy
                        strategy = freshHttpStrategy
                        metadata = metadata.copy(transferId = UUID.randomUUID().toString())
                        continue
                    }

                    if (shouldFallbackToChannel(strategy, item.size, result)) {
                        supersedeWatchTransfer(
                            targetNodeId = targetNode.id,
                            transferId = metadata.transferId,
                            fileName = item.displayName,
                            reason = "channel_fallback",
                        )
                        if (
                            effectiveStrategyKind == TransferStrategyKind.HTTP &&
                            shouldPreferChannelForRemainingBatch(result)
                        ) {
                            preferChannelForRemainingBatch = true
                            Log.d(
                                TAG,
                                "HTTP became unfavorable for this batch after ${item.displayName}; " +
                                    "remaining eligible files will use Bluetooth. Reason=${result.message}",
                            )
                            PhoneTransferDiagnostics.warn(
                                "Batch",
                                "Prefer channel for remaining batch after file=${item.displayName} reason=${result.message}",
                            )
                        }
                        uiUpdater.update(
                            (index.toFloat() / n.toFloat()).coerceIn(0f, 1f),
                            "$prefix\nHTTP failed, retrying via Bluetooth…",
                        )
                        val retryTransferId = UUID.randomUUID().toString()
                        val retryMetadata = metadata.copy(transferId = retryTransferId)
                        fileAttemptCount++
                        fileStrategiesAttempted += "CHANNEL(fallback)"
                        result =
                            normalizeRetryFileExists(
                                runTransferAttempt(
                                    strategy = ChannelClientStrategy(),
                                    fileUri = item.uri,
                                    targetNodeId = targetNode.id,
                                    metadata = retryMetadata,
                                    onProgress = progressReporter,
                                ),
                            )
                    }
                    break
                }
                throwIfCancellationRequested()
                result = normalizeRetryFileExists(result)

                if (!result.success) throw IllegalStateException(toUserFacingTransferError(result))
                val fileDurationMs = SystemClock.elapsedRealtime() - fileStartMs
                val fileSizeMB = item.size / (1024.0 * 1024.0)
                val fileSpeedMBps = if (fileDurationMs > 0) fileSizeMB / (fileDurationMs / 1000.0) else 0.0
                Log.d(
                    TAG,
                    "Completed ${item.displayName} via $effectiveStrategyKind in " +
                        "${fileDurationMs}ms (${String.format("%.2f", fileSpeedMBps)} MB/s)",
                )
                PhoneTransferDiagnostics.log(
                    "Batch",
                    "Summary file=${item.displayName} strategy=$effectiveStrategyKind size=${item.size} " +
                        "durationMs=$fileDurationMs speedMBps=${String.format("%.2f", fileSpeedMBps)} " +
                        "attempts=$fileAttemptCount strategiesUsed=${fileStrategiesAttempted.joinToString("→")}",
                )

                addHistoryItems(
                    listOf(
                        FileTransferHistoryItem(
                            nextHistoryId(),
                            item.displayName,
                            result.message.ifBlank { "Transfer complete" },
                            true,
                        ),
                    ),
                )
                clearFreshHttpRetryTracking(item)
            }

            val sent = itemsToTransfer.size
            val skipped = conflicts.size

            uiState.update {
                it.copy(
                    statusMessage =
                        if (skipped > 0) {
                            "Transfer Complete ($sent sent, $skipped skipped)"
                        } else {
                            "Transfer Complete ($sent file(s))"
                        },
                    isTransferring = false,
                    isPaused = false,
                    canResume = false,
                    pauseReason = "",
                    progress = 1f,
                    progressText = "",
                    selectedFileUris = emptyList(),
                    selectedFileDisplayNames = emptyList(),
                )
            }

            notificationHelper.showCompletion(
                if (skipped > 0) "$sent sent, $skipped skipped" else "$sent file(s)",
                targetNode.displayName,
            )
            Log.d(TAG, "Batch completed in ${SystemClock.elapsedRealtime() - batchStartMs}ms")
            PhoneTransferDiagnostics.log(
                "Batch",
                "Batch complete sent=$sent skipped=$skipped durationMs=${SystemClock.elapsedRealtime() - batchStartMs}",
            )
        } finally {
            paused.set(false)
            cancelRequested.set(false)
            httpPauseRequested.set(false)
            activeStrategy = null
            currentTransferIdInternal = null
            currentTargetNodeIdInternal = null
            currentFileNameInternal = null
            clearActiveTransfer()
            runCatching { sharedHttpStrategy?.close() }
            sharedHttpStrategyRef = null
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
            notificationHelper.stopForeground()
        }
    }

    fun requestCancel(): Boolean {
        val state = uiState.value
        if (!state.isTransferring && !state.isPaused) return false
        cancelRequested.set(true)
        paused.set(false)
        PhoneTransferDiagnostics.warn("Batch", "Cancel requested by user")
        uiState.update {
            it.copy(
                isPaused = false,
                canResume = false,
                pauseReason = "",
                statusMessage = "Cancelling…",
                progressText = "Stopping current transfer…",
            )
        }
        notificationHelper.updateProgress(
            (uiState.value.progress * 100f).toInt(),
            "Stopping current transfer…",
            false,
        )
        return true
    }

    fun abortActiveTransfer() {
        runCatching { sharedHttpStrategyRef?.close() }
        runCatching { (activeStrategy as? AutoCloseable)?.close() }
    }

    fun handleCancel(label: String?) {
        paused.set(false)
        cancelRequested.set(false)
        activeStrategy = null
        sharedHttpStrategyRef = null
        PhoneTransferDiagnostics.warn("Batch", "Cancelled label=${label ?: "Batch"}")
        uiState.update {
            it.copy(
                statusMessage = "Cancelled",
                isTransferring = false,
                isPaused = false,
                canResume = false,
                pauseReason = "",
                progressText = "",
            )
        }
        notificationHelper.cancelForeground()

        addHistoryItems(
            listOf(
                FileTransferHistoryItem(
                    nextHistoryId(),
                    label ?: "Transfer",
                    "Cancelled",
                    false,
                ),
            ),
        )
    }

    fun handleError(
        e: Exception,
        fileName: String?,
    ) {
        Log.e("BatchTransferRunner", "Transfer failed", e)
        PhoneTransferDiagnostics.error(
            "Batch",
            "Transfer failed file=${fileName ?: "Batch"}",
            e,
        )
        paused.set(false)
        cancelRequested.set(false)
        activeStrategy = null
        sharedHttpStrategyRef = null
        val errorMessage =
            e.localizedMessage?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "Unknown transfer error"

        addHistoryItems(
            listOf(
                FileTransferHistoryItem(
                    nextHistoryId(),
                    fileName ?: "Batch",
                    "Failed: ${e.message}",
                    false,
                ),
            ),
        )

        uiState.update {
            it.copy(
                statusMessage = "Error: $errorMessage",
                isTransferring = false,
                isPaused = false,
                canResume = false,
                pauseReason = "",
            )
        }
        notificationHelper.showError(message = errorMessage)
    }

    fun pauseTransfer(): Boolean {
        val state = uiState.value
        if (!state.isTransferring || state.isPaused) return false

        val httpStrategy = activeStrategy as? HttpTransferServer
        val transferId = currentTransferIdInternal
        val nodeId = currentTargetNodeIdInternal
        val fileName = currentFileNameInternal
        if (httpStrategy != null && !transferId.isNullOrBlank() && !nodeId.isNullOrBlank()) {
            paused.set(true)
            httpPauseRequested.set(true)
            PhoneTransferDiagnostics.warn(
                "Batch",
                "Manual HTTP pause requested file=${fileName.orEmpty()} id=$transferId",
            )
            httpStrategy.requestPause(transferId)
            requestPauseOnWatch(nodeId, transferId)
            val progressText =
                buildManualPauseProgressText(
                    filePrefix =
                        uiState.value.progressText
                            .lineSequence()
                            .firstOrNull { it.startsWith("File ", ignoreCase = true) }
                            ?: fileName?.let { "File: $it" }
                            ?: "",
                    existingText = uiState.value.progressText,
                )
            uiState.update {
                it.copy(
                    isPaused = true,
                    canResume = true,
                    pauseReason = "Paused by user",
                    statusMessage = "Paused",
                    progressText = progressText,
                )
            }
            notificationHelper.updateProgress(
                (uiState.value.progress * 100f).toInt(),
                progressText,
                true,
            )
            return true
        }

        paused.set(true)
        PhoneTransferDiagnostics.warn("Batch", "Paused by user")
        uiState.update {
            it.copy(
                isPaused = true,
                canResume = true,
                pauseReason = "Paused by user",
                statusMessage = "Paused",
                progressText = "Paused by user",
            )
        }
        notificationHelper.updateProgress((uiState.value.progress * 100f).toInt(), "Paused by user", true)
        return true
    }

    fun resumeTransfer(): Boolean {
        val state = uiState.value
        if (!state.isPaused) return false

        paused.set(false)
        httpPauseRequested.set(false)
        PhoneTransferDiagnostics.log("Batch", "Resume requested by user")
        uiState.update {
            it.copy(
                isPaused = false,
                canResume = false,
                pauseReason = "",
                statusMessage = "Resuming…",
                progressText = "Resuming…",
            )
        }
        notificationHelper.updateProgress((uiState.value.progress * 100f).toInt(), "Resuming…", false)
        return true
    }

    private suspend fun awaitIfPaused() {
        throwIfCancellationRequested()
        while (paused.get()) {
            throwIfCancellationRequested()
            delay(200L)
        }
        throwIfCancellationRequested()
    }

    private fun nextHistoryId(): Long = historyIdCounter.incrementAndGet()

    private suspend fun buildFileItems(fileUris: List<Uri>): List<FileItem> {
        val items = mutableListOf<FileItem>()
        for (uri in fileUris) {
            val details =
                TransferUtils.getTransferFileDetails(context, uri)
                    ?: throw IllegalStateException("Could not read file details")

            val (rawName, size) = details
            val name = rawName.ifBlank { "file.bin" }

            val okType = isSupportedTransferFileName(name)
            if (!okType) throw IllegalStateException("Unsupported file type: $name")

            items.add(FileItem(uri = uri, displayName = name, size = size))
        }
        return items
    }

    private suspend fun preflightFilterExistingFiles(
        nodeId: String,
        items: List<FileItem>,
    ): Pair<List<FileItem>, List<FileItem>> {
        val existsResults =
            existenceChecker.checkBatch(
                nodeId = nodeId,
                fileNames = items.map { it.displayName },
            )
        if (existsResults == null || existsResults.size != items.size) {
            throw IllegalStateException(
                "Could not verify existing files. The watch did not answer in time.",
            )
        }

        val conflicts = mutableListOf<FileItem>()
        val toTransfer = mutableListOf<FileItem>()

        for ((index, item) in items.withIndex()) {
            val exists = existsResults[index]
            if (exists == false) {
                toTransfer += item
                continue
            }

            if (isReplaceableTransferFileName(item.displayName)) {
                toTransfer += item
                continue
            }

            if (shouldDeleteBeforeTransfer(item)) {
                val deleted = deleteRequester.delete(nodeId, item.displayName)
                if (deleted == true) {
                    toTransfer += item
                    continue
                }
            }

            conflicts += item
        }
        return conflicts to toTransfer
    }

    private suspend fun runTransferAttempt(
        strategy: TransferStrategy,
        fileUri: Uri,
        targetNodeId: String,
        metadata: TransferMetadata,
        onProgress: (Float, String) -> Unit,
    ): TransferResult {
        val ackDeferred: CompletableDeferred<TransferResult> = ackRegistry.register(metadata.transferId)
        activeStrategy = strategy
        currentTransferIdInternal = metadata.transferId
        currentTargetNodeIdInternal = targetNodeId
        currentFileNameInternal = metadata.displayFileName
        setActiveTransfer(metadata.transferId, targetNodeId)
        return try {
            throwIfCancellationRequested()
            strategy.transfer(
                context = context,
                fileUri = fileUri,
                targetNodeId = targetNodeId,
                metadata = metadata,
                ackDeferred = ackDeferred,
                awaitIfPaused = { awaitIfPaused() },
                onProgress = onProgress,
            )
        } finally {
            activeStrategy = null
            currentTransferIdInternal = null
            currentTargetNodeIdInternal = null
            currentFileNameInternal = null
            ackRegistry.remove(metadata.transferId)
            clearActiveTransfer()
        }
    }

    private fun throwIfCancellationRequested() {
        if (cancelRequested.get()) {
            throw CancellationException("Cancelled by user")
        }
    }

    private fun shouldRetryHttpWithFreshEndpoint(
        strategy: TransferStrategy,
        item: FileItem,
        result: TransferResult,
    ): Boolean {
        if (result.success) return false
        if (strategy !is HttpTransferServer) return false
        if (isLikelyDifferentSubnetHttpFailure(result.message)) return false

        val retryKey = freshHttpRetryKey(item)
        val currentRetryCount = freshHttpRetryCount(item)
        val currentSentBytes = extractTransferredBytesFromRetryMessage(result.message)
        val previousBestSentBytes = freshHttpRetryBestSentBytes[retryKey]
        val progressedMeaningfully =
            hasMeaningfulFreshHttpRetryProgress(
                previousSentBytes = previousBestSentBytes,
                currentSentBytes = currentSentBytes,
                totalSize = item.size,
            )
        if (currentSentBytes != null && currentSentBytes > (previousBestSentBytes ?: Long.MIN_VALUE)) {
            freshHttpRetryBestSentBytes[retryKey] = currentSentBytes
        }

        if (
            isExplicitPhoneHttpUnreachableFailure(result.message) &&
            currentSentBytes == null &&
            currentRetryCount >= MAX_UNREACHABLE_HTTP_ENDPOINT_RETRIES_PER_FILE
        ) {
            PhoneTransferDiagnostics.warn(
                "Batch",
                "Stop fresh HTTP retries after repeated unreachable endpoint file=${item.displayName} retries=$currentRetryCount",
            )
            return false
        }

        if (currentRetryCount >= MAX_FRESH_HTTP_RETRIES_PER_FILE) {
            if (!progressedMeaningfully) return false
            freshHttpRetryCounts[retryKey] = (MAX_FRESH_HTTP_RETRIES_PER_FILE - 1).coerceAtLeast(0)
            Log.d(
                TAG,
                "Extending HTTP retry budget for ${item.displayName} after forward progress " +
                    "(previousSent=$previousBestSentBytes, sent=$currentSentBytes)",
            )
            PhoneTransferDiagnostics.log(
                "Batch",
                "Extend HTTP retry budget file=${item.displayName} previousSent=${previousBestSentBytes ?: -1} sent=${currentSentBytes ?: -1}",
            )
        }

        val msg = result.message.lowercase()
        if (msg.contains("file_exists")) return false
        if (msg.contains("missing_checksum")) return false
        if (msg.contains("missing_http_token")) return false
        if (msg.contains("checksum_mismatch")) return false
        if (msg.contains("unauthorized")) return false
        if (msg.contains(HttpTransferServer.RESULT_HTTP_PAUSED_PREFIX.lowercase())) return false
        if (msg.contains(HttpTransferServer.RESULT_HTTP_NO_FIRST_REQUEST_PREFIX.lowercase())) return false
        if (msg.contains("cancelled")) return false

        return msg.contains(HttpTransferServer.RESULT_HTTP_STALLED_PREFIX.lowercase()) ||
            msg.contains(HttpTransferServer.RESULT_HTTP_SLOW_PREFIX.lowercase()) ||
            msg.contains(HttpTransferServer.RESULT_HTTP_RECONNECT_TIMEOUT_PREFIX.lowercase()) ||
            msg.contains("cannot reach phone http server") ||
            msg.contains("failed to connect to phone http server") ||
            msg.contains("connection interrupted") ||
            msg.contains("network lost") ||
            msg.contains("no wi-fi network after") ||
            msg.contains("transfer paused for more than") ||
            msg.contains("transfer timed out") ||
            msg.contains("timeout") ||
            msg.contains("target node not connected")
    }

    private fun freshHttpRetryKey(item: FileItem): String = item.uri.toString()

    private fun freshHttpRetryCount(item: FileItem): Int = freshHttpRetryCounts[freshHttpRetryKey(item)] ?: 0

    private fun incrementFreshHttpRetryCount(item: FileItem) {
        val key = freshHttpRetryKey(item)
        freshHttpRetryCounts[key] = freshHttpRetryCount(item) + 1
    }

    private fun clearFreshHttpRetryTracking(item: FileItem) {
        val key = freshHttpRetryKey(item)
        freshHttpRetryCounts.remove(key)
        freshHttpRetryBestSentBytes.remove(key)
    }

    private suspend fun supersedeWatchTransfer(
        targetNodeId: String,
        transferId: String,
        fileName: String,
        reason: String,
    ) {
        PhoneTransferDiagnostics.warn(
            "Batch",
            "Supersede watch transfer file=$fileName oldId=$transferId reason=$reason",
        )
        runCatching {
            cancelTransferOnWatch(targetNodeId, transferId)
        }.onFailure {
            PhoneTransferDiagnostics.warn(
                "Batch",
                "Supersede cancel failed file=$fileName oldId=$transferId reason=$reason msg=${it.message}",
            )
        }
        delay(SUPERSEDE_CANCEL_SETTLE_MS)
    }

    private fun shouldDeleteBeforeTransfer(item: FileItem): Boolean {
        if (!item.displayName.endsWith(".poi", ignoreCase = true)) return false
        val authority = item.uri.authority.orEmpty()
        return authority == "${context.packageName}.fileprovider"
    }

    private fun wifiRequiredMessage(watchWifiStatus: WatchWifiStatus?): String =
        if (watchWifiStatus?.wifiAvailable == false) {
            "Watch Wi-Fi is off. Files over 50 MB require Wi-Fi on the watch, " +
                "or connect the watch to this phone’s hotspot."
        } else {
            "Wi-Fi required for this transfer batch. Connect the watch to the same Wi-Fi or to this phone’s hotspot."
        }

    private companion object {
        const val TAG = "BatchTransferRunner"
        const val FRESH_HTTP_RETRY_DELAY_MS = 1_200L
        const val SUPERSEDE_CANCEL_SETTLE_MS = 350L
        const val MAX_FRESH_HTTP_RETRIES_PER_FILE = 3
        const val MAX_UNREACHABLE_HTTP_ENDPOINT_RETRIES_PER_FILE = 1
        const val WATCH_RECOVERY_WINDOW_MS = 90_000L
        const val WATCH_RECOVERY_WAIT_MS = 60_000L
        const val MANUAL_HTTP_RESUME_DELAY_MS = 500L
        const val MANUAL_HTTP_RESUME_WAIT_MS = 15_000L
        const val MANUAL_HTTP_RESUME_RECOVERY_WINDOW_MS = 30_000L
    }
}
