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
import com.glancemap.glancemapcompanionapp.R
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
import com.glancemap.glancemapcompanionapp.transfer.strategy.WifiUploadStrategy
import com.glancemap.glancemapcompanionapp.transfer.util.NotificationHelper
import com.glancemap.glancemapcompanionapp.transfer.util.Sha256Options
import com.glancemap.glancemapcompanionapp.transfer.util.Sha256TransferUtils
import com.glancemap.glancemapcompanionapp.transfer.util.TransferUtils
import com.glancemap.glancemapcompanionapp.transfer.wifi.WifiWatchEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
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

    fun loadSelectedFiles(
        supportedItems: List<FileItem>,
        skippedCount: Int,
    ) {
        if (supportedItems.isEmpty()) {
            clearSelectedFiles(
                statusMessage = context.getString(R.string.transfer_status_no_compatible_files),
            )
            return
        }

        val status =
            if (skippedCount > 0) {
                context.getString(
                    R.string.transfer_status_selected_files_skipped,
                    supportedItems.size,
                    skippedCount,
                )
            } else {
                context.getString(R.string.transfer_status_selected_files, supportedItems.size)
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
        val batchId = UUID.randomUUID().toString()
        val wifiEndpoint = WifiWatchEndpoint.parse(targetNode.id)
        Log.d(TAG, "Batch target id=${targetNode.id} wifi=${wifiEndpoint != null}")
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
            "event=batch_start batchId=$batchId files=${fileUris.size} target=${targetNode.displayName}",
        )

        uiState.update {
            it.copy(
                isTransferring = true,
                isPaused = false,
                canResume = false,
                pauseReason = "",
                statusMessage = context.getString(R.string.transfer_status_preparing),
                progress = 0f,
                progressText = context.getString(R.string.transfer_status_starting),
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
        uiUpdater.update(0f, context.getString(R.string.transfer_status_checking_existing))

        val preflightStartMs = SystemClock.elapsedRealtime()
        val (conflicts, itemsToTransfer, nonGpxSkipped) =
            if (wifiEndpoint != null) {
                val (gpxItems, otherItems) =
                    items.partition { it.displayName.endsWith(".gpx", ignoreCase = true) }
                Triple<List<FileItem>, List<FileItem>, List<FileItem>>(emptyList(), gpxItems, otherItems)
            } else {
                val (existingConflicts, toTransfer) =
                    preflightFilterExistingFiles(
                        nodeId = targetNode.id,
                        items = items,
                    )
                Triple<List<FileItem>, List<FileItem>, List<FileItem>>(
                    existingConflicts,
                    toTransfer,
                    emptyList(),
                )
            }
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
                    FileTransferHistoryItem(
                        nextHistoryId(),
                        it.displayName,
                        context.getString(R.string.transfer_status_skipped_exists),
                        false,
                    )
                }
            addHistoryItems(skippedHistory)
        }

        if (nonGpxSkipped.isNotEmpty()) {
            addHistoryItems(
                nonGpxSkipped.map {
                    FileTransferHistoryItem(
                        nextHistoryId(),
                        it.displayName,
                        context.getString(R.string.transfer_wifi_skipped_non_gpx),
                        false,
                    )
                },
            )
        }

        if (itemsToTransfer.isEmpty()) {
            val msg =
                if (wifiEndpoint != null && nonGpxSkipped.isNotEmpty()) {
                    context.getString(R.string.transfer_wifi_gpx_only)
                } else if (conflicts.size == 1) {
                    context.getString(
                        R.string.transfer_status_already_exists_one,
                        conflicts.first().displayName,
                    )
                } else {
                    context.getString(
                        R.string.transfer_status_already_exists_many,
                        conflicts.size,
                    )
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
            PhoneTransferDiagnostics.log(
                "Batch",
                "event=batch_complete batchId=$batchId sent=0 skipped=${conflicts.size + nonGpxSkipped.size} " +
                    "durationMs=${SystemClock.elapsedRealtime() - batchStartMs}",
            )
            return
        }

        val skippedTotal = conflicts.size + nonGpxSkipped.size
        if (skippedTotal > 0) {
            uiUpdater.update(
                0f,
                context.getString(
                    R.string.transfer_status_skipped_count,
                    skippedTotal,
                    itemsToTransfer.size,
                ),
            )
        }

        var selectionContext =
            strategyFactory.buildSelectionContext(
                context = context,
                totalSizesBytes = itemsToTransfer.map { it.size },
            )
        if (wifiEndpoint != null) {
            if (TransferUtils.getWifiIpAddress(context).isNullOrBlank()) {
                throw IllegalStateException(context.getString(R.string.transfer_wifi_required))
            }
            selectionContext =
                selectionContext.copy(
                    wifiAvailable = true,
                    preferSharedHttpForBatch = false,
                )
        }
        var watchWifiStatus: WatchWifiStatus? = null
        val initialTransferPlans =
            itemsToTransfer.map { item ->
                PlannedTransfer(
                    item = item,
                    strategyKind =
                        if (wifiEndpoint != null) {
                            TransferStrategyKind.WIFI_UPLOAD
                        } else {
                            strategyFactory.decide(item.size, selectionContext)
                        },
                )
            }

        if (
            wifiEndpoint == null &&
            selectionContext.wifiAvailable &&
            initialTransferPlans.any { TransferStrategyFactory.usesWifi(it.strategyKind) }
        ) {
            uiUpdater.update(0f, context.getString(R.string.transfer_status_checking_watch_wifi))
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
                    uiUpdater.update(
                        0f,
                        context.getString(R.string.transfer_status_watch_wifi_off),
                    )
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
                    strategyKind =
                        if (wifiEndpoint != null) {
                            TransferStrategyKind.WIFI_UPLOAD
                        } else {
                            strategyFactory.decide(item.size, selectionContext)
                        },
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
                val prefix = context.getString(R.string.transfer_file_prefix, index + 1, n, item.displayName)
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

                        TransferStrategyKind.WIFI_UPLOAD -> WifiUploadStrategy()
                        else -> strategyFactory.create(effectiveStrategyKind)
                    }
                val strategyReason =
                    when {
                        wifiEndpoint != null ->
                            "wifi-watch→WIFI_UPLOAD"
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
                    "event=file_start batchId=$batchId transferId=$transferId fileIndex=${index + 1} fileCount=$n " +
                        "attempt=1 name=${item.displayName} strategy=$effectiveStrategyKind " +
                        "reason=$strategyReason size=${item.size}" +
                        (if (wifiRssi != null) " rssi=${wifiRssi}dBm linkSpeed=${wifiLinkSpeed}Mbps" else ""),
                )
                val hashPhaseWeight =
                    when {
                        effectiveStrategyKind == TransferStrategyKind.HTTP && isMapLikeFile -> 0.12f
                        effectiveStrategyKind == TransferStrategyKind.HTTP -> 0.05f
                        effectiveStrategyKind == TransferStrategyKind.WIFI_UPLOAD -> 0.05f
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
                            "$prefix\n${context.getString(R.string.transfer_status_preparing_checksum)}",
                        )
                        Sha256TransferUtils.computeSha256Cached(
                            context = context,
                            uri = item.uri,
                            knownDisplayName = item.displayName,
                            hashOptions =
                                Sha256Options(
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
                                    diagnosticContext = "transferId=$transferId file=$safeFileName",
                                ),
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
                        diagnosticBatchId = batchId,
                        fileIndex = index + 1,
                        fileCount = n,
                    )

                val progressReporter: (Float, String) -> Unit = { fileProg, text ->
                    val p = fileProg.coerceIn(0f, 1f)
                    uiUpdater.update(
                        p,
                        "$prefix\n$text",
                        isActiveTransferProgress = text.startsWith("HTTP:") || text.startsWith("Wi-Fi:"),
                    )
                }
                var result: TransferResult
                var fileAttemptCount = 0
                val fileStrategiesAttempted = mutableListOf<String>()
                var finalStrategyKind = effectiveStrategyKind
                while (true) {
                    fileAttemptCount++
                    metadata = metadata.copy(attempt = fileAttemptCount)
                    fileStrategiesAttempted +=
                        if (strategy is HttpTransferServer) {
                            TransferStrategyKind.HTTP.name
                        } else if (strategy is ChannelClientStrategy) {
                            TransferStrategyKind.CHANNEL.name
                        } else if (strategy is WifiUploadStrategy) {
                            TransferStrategyKind.WIFI_UPLOAD.name
                        } else {
                            TransferStrategyKind.MESSAGE.name
                        }
                    PhoneTransferDiagnostics.log(
                        "Batch",
                        "event=transfer_attempt batchId=$batchId transferId=${metadata.transferId} " +
                            "fileIndex=${index + 1} fileCount=$n attempt=$fileAttemptCount " +
                            "strategy=${fileStrategiesAttempted.last()}",
                    )
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

                    val fallbackAllowed = shouldFallbackToChannel(strategy, item.size, result)
                    if (strategy is HttpTransferServer && !result.success) {
                        PhoneTransferDiagnostics.log(
                            "Batch",
                            "event=fallback_decision batchId=$batchId transferId=${metadata.transferId} " +
                                "from=HTTP to=CHANNEL allowed=$fallbackAllowed " +
                                "reason=${fallbackDecisionReason(item.size, result)}",
                        )
                    }
                    if (fallbackAllowed) {
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
                        fileAttemptCount++
                        metadata = metadata.copy(transferId = retryTransferId, attempt = fileAttemptCount)
                        finalStrategyKind = TransferStrategyKind.CHANNEL
                        fileStrategiesAttempted += "CHANNEL(fallback)"
                        result =
                            normalizeRetryFileExists(
                                runTransferAttempt(
                                    strategy = ChannelClientStrategy(),
                                    fileUri = item.uri,
                                    targetNodeId = targetNode.id,
                                    metadata = metadata,
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
                val fileSizeMiB = item.size / (1024.0 * 1024.0)
                val fileSpeedMiBps = if (fileDurationMs > 0) fileSizeMiB / (fileDurationMs / 1000.0) else 0.0
                Log.d(
                    TAG,
                    "Completed ${item.displayName} via $finalStrategyKind in " +
                        "${fileDurationMs}ms (${String.format(Locale.US, "%.2f", fileSpeedMiBps)} MiB/s)",
                )
                PhoneTransferDiagnostics.log(
                    "Batch",
                    "event=file_complete batchId=$batchId transferId=${metadata.transferId} fileIndex=${index + 1} " +
                        "fileCount=$n file=${item.displayName} strategy=$finalStrategyKind size=${item.size} " +
                        "durationMs=$fileDurationMs speedMiBps=${String.format(Locale.US, "%.2f", fileSpeedMiBps)} " +
                        "attempts=$fileAttemptCount strategiesUsed=${fileStrategiesAttempted.joinToString("→")}",
                )

                addHistoryItems(
                    listOf(
                        FileTransferHistoryItem(
                            nextHistoryId(),
                            item.displayName,
                            result.message.ifBlank {
                                context.getString(R.string.transfer_status_transfer_complete_file)
                            },
                            true,
                        ),
                    ),
                )
                clearFreshHttpRetryTracking(item)
            }

            val sent = itemsToTransfer.size
            val skipped = conflicts.size + nonGpxSkipped.size

            uiState.update {
                it.copy(
                    statusMessage =
                        if (skipped > 0) {
                            context.getString(R.string.transfer_status_complete, sent, skipped)
                        } else {
                            context.getString(R.string.transfer_status_complete_simple, sent)
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
                if (skipped > 0) {
                    context.getString(R.string.transfer_notification_complete_skipped, sent, skipped)
                } else {
                    context.getString(R.string.transfer_notification_complete, sent)
                },
                targetNode.displayName,
            )
            Log.d(TAG, "Batch completed in ${SystemClock.elapsedRealtime() - batchStartMs}ms")
            PhoneTransferDiagnostics.log(
                "Batch",
                "event=batch_complete batchId=$batchId sent=$sent skipped=$skipped " +
                    "durationMs=${SystemClock.elapsedRealtime() - batchStartMs}",
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
                statusMessage = context.getString(R.string.transfer_status_cancelling),
                progressText = context.getString(R.string.transfer_status_stopping),
            )
        }
        notificationHelper.updateProgress(
            (uiState.value.progress * 100f).toInt(),
            context.getString(R.string.transfer_status_stopping),
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
                statusMessage = context.getString(R.string.transfer_status_cancelled),
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
                    label ?: context.getString(R.string.transfer_history_transfer),
                    context.getString(R.string.transfer_status_cancelled),
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
                ?: context.getString(R.string.transfer_error_unknown)

        addHistoryItems(
            listOf(
                FileTransferHistoryItem(
                    nextHistoryId(),
                    fileName ?: context.getString(R.string.transfer_history_batch),
                    context.getString(
                        R.string.transfer_history_failed,
                        e.message.orEmpty(),
                    ),
                    false,
                ),
            ),
        )

        uiState.update {
            it.copy(
                statusMessage = context.getString(R.string.transfer_status_error, errorMessage),
                isTransferring = false,
                isPaused = false,
                canResume = false,
                pauseReason = "",
                progressText = terminalTransferErrorProgressText(errorMessage),
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
            val currentProgressText = uiState.value.progressText
            val filePrefix =
                currentProgressText.lineSequence().firstOrNull {
                    it.startsWith("File ", ignoreCase = true) || it.startsWith("文件")
                } ?: fileName?.let { "File: $it" } ?: ""
            val progressText = buildManualPauseProgressText(filePrefix, currentProgressText)
            uiState.update {
                it.copy(
                    isPaused = true,
                    canResume = true,
                    pauseReason = context.getString(R.string.transfer_status_paused_by_user),
                    statusMessage = context.getString(R.string.transfer_status_paused),
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
                pauseReason = context.getString(R.string.transfer_status_paused_by_user),
                statusMessage = context.getString(R.string.transfer_status_paused),
                progressText = context.getString(R.string.transfer_status_paused_by_user),
            )
        }
        notificationHelper.updateProgress(
            (uiState.value.progress * 100f).toInt(),
            context.getString(R.string.transfer_status_paused_by_user),
            true,
        )
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
                statusMessage = context.getString(R.string.transfer_status_resuming),
                progressText = context.getString(R.string.transfer_status_resuming),
            )
        }
        notificationHelper.updateProgress(
            (uiState.value.progress * 100f).toInt(),
            context.getString(R.string.transfer_status_resuming),
            false,
        )
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
        if (strategy !is HttpTransferServer) return false
        if (!shouldConsiderFreshHttpRetry(result)) return false

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
