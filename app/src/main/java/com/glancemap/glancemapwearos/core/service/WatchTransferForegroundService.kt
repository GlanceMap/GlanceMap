package com.glancemap.glancemapwearos.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferStrategy
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferTerminalResult
import com.glancemap.glancemapwearos.core.service.transfer.notifications.FGS_DATA_SYNC_TIMEOUT
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.notifications.foregroundStartFailureDetail
import com.glancemap.glancemapwearos.core.service.transfer.runtime.ForegroundTransferOwner
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TerminalResultDelivery
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferLockManager
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferRunner
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferRuntimeHost
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalOutcome
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalState
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.glancemapwearos.data.repository.WatchDataLayerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WatchTransferForegroundService :
    Service(),
    TransferRuntimeHost {
    override val context: Context
        get() = this

    override val appScope: CoroutineScope
        get() = serviceScope

    private val app by lazy { application as GlanceMapWearApp }
    private val lockManager by lazy { TransferLockManager(this) }
    private val transferSessionState by lazy { app.transferSessionState }
    private val transferMutex get() = transferSessionState.transferMutex
    private val fileOps by lazy { WatchFileOps(app) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTransfers = AtomicInteger(0)
    private val activeRequestsByStartId = ConcurrentHashMap<Int, TransferRequest>()
    private val foregroundLock = Any()
    private val foregroundOwner = ForegroundTransferOwner()
    private val serviceInstanceId = Integer.toHexString(System.identityHashCode(this))

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var dataLayerRepository: WatchDataLayerRepository
    private val httpReceiver = HttpTransferStrategy()
    private lateinit var runner: TransferRunner

    override fun onCreate() {
        super.onCreate()
        TransferDiagnostics.log("FgService", "Created instance=$serviceInstanceId")

        dataLayerRepository = WatchDataLayerRepository(this)
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()
        runner =
            TransferRunner(
                host = this,
                notificationHelper = notificationHelper,
                httpReceiver = httpReceiver,
                sessionState = transferSessionState,
                sendStatus = dataLayerRepository::sendStatus,
                claimTerminal = ::claimTerminal,
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action != ACTION_START_HTTP_TRANSFER) {
            stopIfIdle()
            return START_NOT_STICKY
        }

        val request = TransferRequest.fromIntent(intent)
        if (request == null) {
            Log.w(TAG, "Missing HTTP transfer extras")
            stopIfIdle()
            return START_NOT_STICKY
        }

        val ownsInitialForeground =
            synchronized(foregroundLock) {
                activeRequestsByStartId[startId] = request
                activeTransfers.incrementAndGet()
                foregroundOwner.reserveIfAvailable(startId)
            }
        if (ownsInitialForeground) {
            val failure = startForegroundForRequest(request)
            if (failure != null) {
                synchronized(foregroundLock) {
                    foregroundOwner.clearIfOwner(startId)
                }
                finishRejectedRequest(request, startId, failure)
                return START_NOT_STICKY
            }
        }

        launchTransfer(request, startId)

        return START_NOT_STICKY
    }

    private fun launchTransfer(
        request: TransferRequest,
        startId: Int,
    ) {
        TransferDiagnostics.log(
            "FgService",
            "Launch HTTP transfer id=${request.metadata.transferId} file=${request.metadata.fileName} startId=$startId",
        )

        val transferJob =
            serviceScope.launch {
                try {
                    transferMutex.withLock {
                        if (request.terminalState.current() != null) return@withLock
                        if (!ensureForegroundForRequest(request, startId)) return@withLock

                        val fileName = request.metadata.fileName
                        if (fileOps.fileExistsOnWatch(fileName)) {
                            val msg = "FILE_EXISTS:$fileName"
                            if (request.terminalState.tryClaim(TransferTerminalOutcome.ERROR)) {
                                releasePrewarmWakeLock("http_rejected_exists:$fileName")
                                TransferDiagnostics.warn(
                                    "FgService",
                                    "Target file already exists id=${request.metadata.transferId} file=$fileName",
                                )
                                notificationHelper.updateForeground(
                                    request.metadata.notificationId,
                                    request.metadata.fileName,
                                    "Already exists",
                                    -1,
                                )
                                notificationHelper.showError(request.metadata.notificationId, request.metadata.fileName, "Already exists")
                                request.terminalResult.record(
                                    HttpTransferTerminalResult(
                                        phase = "ERROR",
                                        ackStatus = "ERROR",
                                        detail = msg,
                                    ),
                                )
                            }
                            return@withLock
                        }

                        runner.runHttp(request.metadata, request.httpPath) { result ->
                            request.terminalResult.record(result)
                        }
                    }
                } finally {
                    request.terminalResult.deliverAfterCleanup(
                        cleanup = {
                            transferSessionState.clearHttpTransfer(request.metadata.transferId)
                            finishRequest(request, startId)
                            TransferDiagnostics.log(
                                "FgService",
                                "event=cleanup_complete transferId=${request.metadata.transferId}",
                            )
                        },
                        deliver = { result -> sendTerminalResult(request, result) },
                    )
                }
            }
        request.job.set(transferJob)
    }

    override fun onDestroy() {
        TransferDiagnostics.warn(
            "FgService",
            "Destroy instance=$serviceInstanceId activeTransferId=${transferSessionState.activeTransferId().orEmpty()}",
        )
        activeRequestsByStartId.values.forEach { request ->
            request.job.get()?.cancel(CancellationException("Transfer service destroyed"))
            if (request.terminalState.tryClaim(TransferTerminalOutcome.CANCELLED)) {
                request.terminalResult.record(
                    HttpTransferTerminalResult(
                        phase = "CANCELLED",
                        ackStatus = "ERROR",
                        detail = "Cancelled",
                    ),
                )
            }
        }
        runCatching { httpReceiver.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    // Keep stale-owner validation and the atomic terminal claim in one timeout sequence.
    @Suppress("ReturnCount")
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        super.onTimeout(startId, fgsType)
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return

        val request = activeRequestsByStartId[startId]
        val currentStartId = synchronized(foregroundLock) { foregroundOwner.currentStartId() }
        val transferId = request?.metadata?.transferId
        if (request == null || currentStartId != startId) {
            val current = activeRequestsByStartId[currentStartId]
            TransferDiagnostics.log(
                "FgService",
                "event=timeout_ignored reason=stale_start_id timeoutStartId=$startId " +
                    "currentStartId=$currentStartId transferId=${current?.metadata?.transferId.orEmpty()}",
            )
            return
        }
        TransferDiagnostics.warn(
            "FgService",
            "event=fgs_timeout startId=$startId transferId=${transferId.orEmpty()} " +
                "fgsType=dataSync sdk=${Build.VERSION.SDK_INT}",
        )
        if (!request.terminalState.tryClaim(TransferTerminalOutcome.TIMEOUT)) {
            TransferDiagnostics.log(
                "FgService",
                "event=timeout_ignored reason=terminal_already_claimed timeoutStartId=$startId " +
                    "currentStartId=$currentStartId transferId=${request.metadata.transferId} " +
                    "existing=${request.terminalState.current()}",
            )
            return
        }

        request.terminalResult.record(
            HttpTransferTerminalResult(
                phase = "ERROR",
                ackStatus = "ERROR",
                detail = FGS_DATA_SYNC_TIMEOUT,
            ),
        )
        request.job.get()?.cancel(CancellationException(FGS_DATA_SYNC_TIMEOUT))
        transferSessionState.cancelTransferById(request.metadata.transferId, FGS_DATA_SYNC_TIMEOUT)
        demoteForegroundIfOwner(startId, request.metadata.notificationId)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTransferStarted() {
        app.container.syncManager.onTransferStarted()
    }

    override fun onTransferFinished() {
        app.container.syncManager.onTransferFinished()
    }

    override suspend fun saveFile(
        fileName: String,
        inputStream: InputStream,
        expectedSize: Long?,
        resumeOffset: Long,
        keepPartialOnFailure: Boolean,
        computeSha256: Boolean,
        diagnosticContext: String?,
        onProgress: (Long) -> Unit,
    ): String? =
        fileOps.saveFile(
            fileName = fileName,
            inputStream = inputStream,
            expectedSize = expectedSize,
            resumeOffset = resumeOffset,
            keepPartialOnFailure = keepPartialOnFailure,
            computeSha256 = computeSha256,
            diagnosticContext = diagnosticContext,
            onProgress = onProgress,
        )

    override fun getPartialSize(fileName: String): Long = fileOps.getPartialSize(fileName)

    override fun deletePartial(fileName: String): Boolean = fileOps.deletePartial(fileName)

    override fun truncatePartial(
        fileName: String,
        expectedSize: Long,
    ): Boolean = fileOps.truncatePartial(fileName, expectedSize)

    override fun computePartialFileSha256(fileName: String): String? = fileOps.computePartialFileSha256(fileName)

    override suspend fun promotePartialToFinal(fileName: String): Boolean = fileOps.promotePartialToFinal(fileName)

    override suspend fun deleteByName(fileName: String) {
        fileOps.deleteByName(fileName)
    }

    override fun computeFinalFileSha256(
        fileName: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?,
    ): String? = fileOps.computeFinalFileSha256(fileName, onProgress)

    override fun acquireWakeLock(
        tag: String,
        timeoutMs: Long,
    ): PowerManager.WakeLock = lockManager.acquireWakeLock(tag, timeoutMs)

    override fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        lockManager.releaseWakeLock(wakeLock)
    }

    override fun holdPrewarmWakeLock(
        reason: String,
        timeoutMs: Long,
    ) {
        app.transferPrewarmHoldManager.hold(reason, timeoutMs)
    }

    override fun releasePrewarmWakeLock(reason: String) {
        app.transferPrewarmHoldManager.release(reason)
    }

    override fun acquireWifiLock(tag: String): WifiManager.WifiLock = lockManager.acquireWifiLock(tag)

    override fun releaseWifiLock(wifiLock: WifiManager.WifiLock) {
        lockManager.releaseWifiLock(wifiLock)
    }

    private fun stopIfIdle() {
        if (activeTransfers.get() <= 0) {
            stopSelf()
        }
    }

    private fun sendTerminalResult(
        request: TransferRequest,
        result: HttpTransferTerminalResult,
    ) {
        val metadata = request.metadata
        app.applicationScope.launch(Dispatchers.IO) {
            runCatching {
                dataLayerRepository.sendStatus(metadata.sourceNodeId, metadata.transferId, result.phase, result.detail)
            }.onFailure {
                TransferDiagnostics.warn(
                    "FgService",
                    "Terminal status failed id=${metadata.transferId} phase=${result.phase}",
                )
            }
            runCatching {
                dataLayerRepository.sendAck(metadata.sourceNodeId, metadata.transferId, result.ackStatus, result.detail)
            }.onFailure {
                TransferDiagnostics.warn(
                    "FgService",
                    "Terminal ACK failed id=${metadata.transferId} status=${result.ackStatus}",
                )
            }
        }
    }

    private fun claimTerminal(
        transferId: String,
        outcome: TransferTerminalOutcome,
    ): Boolean =
        activeRequestsByStartId.values
            .firstOrNull { it.metadata.transferId == transferId }
            ?.terminalState
            ?.tryClaim(outcome)
            ?: false

    private fun ensureForegroundForRequest(
        request: TransferRequest,
        startId: Int,
    ): Boolean {
        var foregroundReady = isForegroundOwner(startId)
        if (!foregroundReady) {
            val previousOwner =
                synchronized(foregroundLock) {
                    val previous = foregroundOwner.replace(startId)
                    previous
                }
            val failure = startForegroundForRequest(request)
            if (failure == null) {
                foregroundReady = true
            } else {
                synchronized(foregroundLock) {
                    foregroundOwner.restoreIfOwner(startId, previousOwner)
                }
                if (request.terminalState.tryClaim(TransferTerminalOutcome.ERROR)) {
                    releasePrewarmWakeLock("http_fgs_start_failed:${request.metadata.fileName}")
                    request.terminalResult.record(
                        HttpTransferTerminalResult(
                            phase = "ERROR",
                            ackStatus = "ERROR",
                            detail = failure,
                        ),
                    )
                }
            }
        }
        return foregroundReady
    }

    private fun finishRejectedRequest(
        request: TransferRequest,
        startId: Int,
        detail: String,
    ) {
        if (request.terminalState.tryClaim(TransferTerminalOutcome.ERROR)) {
            releasePrewarmWakeLock("http_fgs_start_failed:${request.metadata.fileName}")
            request.terminalResult.record(
                HttpTransferTerminalResult(
                    phase = "ERROR",
                    ackStatus = "ERROR",
                    detail = detail,
                ),
            )
        }
        request.terminalResult.deliverAfterCleanup(
            cleanup = {
                finishRequest(request, startId)
                TransferDiagnostics.log(
                    "FgService",
                    "event=cleanup_complete transferId=${request.metadata.transferId}",
                )
            },
            deliver = { result -> sendTerminalResult(request, result) },
        )
    }

    private fun finishRequest(
        request: TransferRequest,
        startId: Int,
    ) {
        val remainingTransfers: Int
        val shouldStopForeground: Boolean
        synchronized(foregroundLock) {
            activeRequestsByStartId.remove(startId)
            remainingTransfers = activeTransfers.decrementAndGet()
            shouldStopForeground = foregroundOwner.release(startId, remainingTransfers)
        }
        if (shouldStopForeground) {
            runCatching { notificationHelper.stopForeground(request.metadata.notificationId) }
        }
        if (remainingTransfers <= 0) stopSelf(startId)
    }

    private fun isForegroundOwner(startId: Int): Boolean =
        synchronized(foregroundLock) {
            foregroundOwner.isOwner(startId)
        }

    private fun demoteForegroundIfOwner(
        startId: Int,
        notificationId: Int,
    ) {
        val isOwner =
            synchronized(foregroundLock) {
                foregroundOwner.clearIfOwner(startId)
            }
        if (isOwner) {
            runCatching { notificationHelper.stopForeground(notificationId) }
        }
    }

    // Platform foreground promotion failures are runtime exceptions on supported API levels.
    @Suppress("TooGenericExceptionCaught")
    private fun startForegroundForRequest(
        request: TransferRequest,
    ): String? {
        try {
            TransferDiagnostics.log(
                "FgService",
                "event=fgs_start_attempt id=${request.metadata.transferId} file=${request.metadata.fileName} " +
                    "size=${request.metadata.totalSize} fgsType=dataSync sdk=${Build.VERSION.SDK_INT}",
            )
            notificationHelper.startForeground(
                request.metadata.notificationId,
                request.metadata.fileName,
                "Preparing Download…",
            )
            TransferDiagnostics.log(
                "FgService",
                "event=fgs_start_success id=${request.metadata.transferId} file=${request.metadata.fileName}",
            )
            return null
        } catch (error: RuntimeException) {
            val detail = foregroundStartFailureDetail(error.javaClass.name, Build.VERSION.SDK_INT, error.message)
            TransferDiagnostics.error(
                "FgService",
                "event=fgs_start_rejected id=${request.metadata.transferId} file=${request.metadata.fileName} " +
                    "size=${request.metadata.totalSize} fgsType=dataSync sdk=${Build.VERSION.SDK_INT} " +
                    "exception=${error.javaClass.simpleName} detail=$detail",
                error,
            )
            return detail
        }
    }

    private data class TransferRequest(
        val metadata: ReceiverMetadata,
        val httpPath: String,
        val terminalState: TransferTerminalState = TransferTerminalState(metadata.transferId),
        val job: AtomicReference<Job?> = AtomicReference(null),
        val terminalResult: TerminalResultDelivery<HttpTransferTerminalResult> = TerminalResultDelivery(),
    ) {
        companion object {
            fun fromIntent(intent: Intent): TransferRequest? {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID).orEmpty()
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                val totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, -1L)
                val sourceNodeId = intent.getStringExtra(EXTRA_SOURCE_NODE_ID).orEmpty()
                val hasNotificationId = intent.hasExtra(EXTRA_NOTIFICATION_ID)
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                val checksumSha256 = intent.getStringExtra(EXTRA_CHECKSUM_SHA256)?.ifBlank { null }
                val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN)?.ifBlank { null }
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                val httpPath = intent.getStringExtra(EXTRA_HTTP_PATH).orEmpty()

                if (
                    transferId.isBlank() ||
                    fileName.isBlank() ||
                    sourceNodeId.isBlank() ||
                    !hasNotificationId ||
                    ip.isBlank() ||
                    port <= 0 ||
                    httpPath.isBlank()
                ) {
                    return null
                }

                return TransferRequest(
                    metadata =
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
                        ),
                    httpPath = httpPath,
                )
            }
        }
    }

    companion object {
        private const val TAG = "WatchTransferFgSvc"
        private const val ACTION_START_HTTP_TRANSFER = "com.glancemap.glancemapwearos.action.START_HTTP_TRANSFER"
        private const val EXTRA_TRANSFER_ID = "transfer_id"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_TOTAL_SIZE = "total_size"
        private const val EXTRA_SOURCE_NODE_ID = "source_node_id"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_CHECKSUM_SHA256 = "checksum_sha256"
        private const val EXTRA_AUTH_TOKEN = "auth_token"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_HTTP_PATH = "http_path"

        fun startHttpTransfer(
            context: Context,
            metadata: ReceiverMetadata,
            httpPath: String,
        ) {
            val intent =
                Intent(context, WatchTransferForegroundService::class.java).apply {
                    action = ACTION_START_HTTP_TRANSFER
                    putExtra(EXTRA_TRANSFER_ID, metadata.transferId)
                    putExtra(EXTRA_FILE_NAME, metadata.fileName)
                    putExtra(EXTRA_TOTAL_SIZE, metadata.totalSize)
                    putExtra(EXTRA_SOURCE_NODE_ID, metadata.sourceNodeId)
                    putExtra(EXTRA_NOTIFICATION_ID, metadata.notificationId)
                    putExtra(EXTRA_CHECKSUM_SHA256, metadata.checksumSha256)
                    putExtra(EXTRA_AUTH_TOKEN, metadata.authToken)
                    putExtra(EXTRA_IP, metadata.ip)
                    putExtra(EXTRA_PORT, metadata.port)
                    putExtra(EXTRA_HTTP_PATH, httpPath)
                }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
