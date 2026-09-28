package com.glancemap.glancemapwearos.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelClientStrategy
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferAckOutcome
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferAdmissionGate
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferHandoff
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferHandoffRegistry
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.DataLayerChannelOpenedHandler
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.missingChannelHandoffAck
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.parseChannelPath
import com.glancemap.glancemapwearos.core.service.transfer.notifications.FGS_DATA_SYNC_TIMEOUT
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.notifications.foregroundStartFailureDetail
import com.glancemap.glancemapwearos.core.service.transfer.runtime.ForegroundTransferSessionOwner
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferLockManager
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalOutcome
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalState
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.glancemapwearos.data.repository.WatchDataLayerRepository
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Suppress("TooManyFunctions") // This service is the lifecycle adapter for the existing channel transfer handler.
class ChannelTransferForegroundService : Service() {
    private val app by lazy { application as GlanceMapWearApp }
    private val lockManager by lazy { TransferLockManager(this) }
    private val fileOps by lazy { WatchFileOps(app) }
    private val transferSessionState by lazy { app.transferSessionState }
    private val transferMutex: Mutex get() = transferSessionState.transferMutex
    private val channelAdmissionGate by lazy { ChannelTransferAdmissionGate(transferMutex) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val foregroundSessions = ChannelTransferSessionOwner()
    private val requestsByStartId = ConcurrentHashMap<Int, ChannelRequest>()
    private val requestsByTransferId = ConcurrentHashMap<String, ChannelRequest>()

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var dataLayerRepository: WatchDataLayerRepository

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()
        dataLayerRepository = WatchDataLayerRepository(this)
        TransferDiagnostics.log("ChannelFgService", "Created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val startIntent = intent?.takeIf { it.action == ACTION_START_CHANNEL_TRANSFER }
        val transferId = startIntent?.getStringExtra(EXTRA_TRANSFER_ID).orEmpty()
        val sourceNodeId = startIntent?.getStringExtra(EXTRA_SOURCE_NODE_ID).orEmpty()
        val admissionOwner = startIntent?.let { ChannelTransferHandoffRegistry.takeAdmission(transferId) }
        val handoff = startIntent?.let { ChannelTransferHandoffRegistry.take(transferId) }
        val parsed = handoff?.let { parseChannelPath(it.channel.path) }
        return when {
            startIntent == null -> stopForStartId(startId)
            transferId.isBlank() || handoff == null -> {
                TransferDiagnostics.warn("ChannelFgService", "Missing channel handoff transferId=$transferId")
                if (transferId.isNotBlank() && sourceNodeId.isNotBlank()) {
                    sendAck(missingChannelHandoffAck(sourceNodeId, transferId))
                }
                releaseAdmission(admissionOwner)
                stopForStartId(startId)
            }
            parsed == null || parsed.first != transferId -> {
                failBeforeTransfer(startId, transferId, handoff, "INVALID_CHANNEL_HANDOFF", admissionOwner)
                START_NOT_STICKY
            }
            admissionOwner == null -> {
                failBeforeTransfer(startId, transferId, handoff, "CHANNEL_ADMISSION_MISSING")
                START_NOT_STICKY
            }
            else -> startRequest(startId, transferId, handoff, parsed.second, admissionOwner)
        }
    }

    override fun onDestroy() {
        requestsByTransferId.values.forEach { request ->
            request.job.get()?.cancel(CancellationException("Channel transfer service destroyed"))
            if (request.terminalState.tryClaim(TransferTerminalOutcome.CANCELLED)) {
                sendTerminalAfterCleanup(request, "CANCELLED", "ERROR", "Cancelled")
            }
            runCatching { notificationHelper.stopForeground(request.notificationId) }
        }
        serviceScope.cancel()
        TransferDiagnostics.warn("ChannelFgService", "Destroyed")
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        super.onTimeout(startId, fgsType)
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            handleTimeout(startId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun appScope(): CoroutineScope = app.applicationScope

    internal fun onTransferStarted() {
        app.container.syncManager.onTransferStarted()
    }

    internal fun onTransferFinished() {
        app.container.syncManager.onTransferFinished()
    }

    @Suppress("LongParameterList") // Mirrors the established WatchFileOps file-commit contract.
    internal suspend fun saveFile(
        fileName: String,
        inputStream: InputStream,
        expectedSize: Long? = null,
        resumeOffset: Long = 0L,
        keepPartialOnFailure: Boolean = false,
        computeSha256: Boolean = true,
        diagnosticContext: String? = null,
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

    fun acquireWakeLock(
        tag: String,
        timeoutMs: Long,
    ): PowerManager.WakeLock = lockManager.acquireWakeLock(tag, timeoutMs)

    fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        lockManager.releaseWakeLock(wakeLock)
    }

    fun releasePrewarmWakeLock(reason: String) {
        app.transferPrewarmHoldManager.release(reason)
    }

    internal fun beginForegroundTransfer(
        transferId: String,
        fileName: String,
        sourceNodeId: String,
        notificationId: Int,
        job: Job,
    ) {
        val request = requestsByTransferId[transferId] ?: return
        foregroundSessions.begin(
            startId = request.startId,
            transferId = transferId,
            fileName = fileName,
            sourceNodeId = sourceNodeId,
            notificationId = notificationId,
            job = job,
            terminalState = request.terminalState,
        )
    }

    internal fun endForegroundTransfer(transferId: String) {
        foregroundSessions.end(transferId)
    }

    internal fun claimForegroundTransfer(
        transferId: String,
        outcome: TransferTerminalOutcome,
    ): Boolean = foregroundSessions.claim(transferId, outcome)

    internal fun foregroundTransferOutcome(
        transferId: String,
    ): TransferTerminalOutcome? = foregroundSessions.outcomeForTransferId(transferId)

    private fun channelHandler(request: ChannelRequest): DataLayerChannelOpenedHandler =
        DataLayerChannelOpenedHandler(
            service = this,
            notificationHelper = notificationHelper,
            fileOps = fileOps,
            transferMutex = transferMutex,
            channelReceiver = ChannelClientStrategy(),
            sendAck = dataLayerRepository::sendAck,
            expectedChecksum = request.handoff.expectedChecksum,
            admissionOwner = request.admissionOwner,
        )

    @Suppress("TooGenericExceptionCaught") // Android may throw different RuntimeException subclasses by API level.
    private fun startForegroundForRequest(request: ChannelRequest): String? =
        try {
            TransferDiagnostics.log(
                "Channel",
                "event=fgs_start_attempt id=${request.transferId} file=${request.fileName} " +
                    "size=-1 fgsType=dataSync",
            )
            notificationHelper.startForeground(request.notificationId, request.fileName, "Receiving (Bluetooth)…")
            TransferDiagnostics.log(
                "Channel",
                "event=fgs_start_success id=${request.transferId} file=${request.fileName}",
            )
            null
        } catch (error: RuntimeException) {
            val detail = foregroundStartFailureDetail(error.javaClass.name, Build.VERSION.SDK_INT, error.message)
            TransferDiagnostics.error(
                "ChannelFgService",
                "event=fgs_start_rejected transferId=${request.transferId} detail=$detail",
                error,
            )
            detail
        }

    private fun failBeforeTransfer(
        startId: Int,
        transferId: String,
        handoff: ChannelTransferHandoff,
        detail: String,
        admissionOwner: Any? = null,
    ) {
        requestsByStartId.remove(startId)
        requestsByTransferId.remove(transferId)
        runCatching { notificationHelper.stopForeground(transferId.hashCode()) }
        runCatching { Wearable.getChannelClient(this).close(handoff.channel) }
        app.applicationScope.launch(Dispatchers.IO) {
            runCatching { dataLayerRepository.sendAck(handoff.channel.nodeId, transferId, "ERROR", detail) }
        }
        releaseAdmission(admissionOwner)
        stopSelf(startId)
    }

    private fun releaseAdmission(owner: Any?) {
        owner?.let(channelAdmissionGate::release)
    }

    private fun releaseAdmission(
        owner: Any,
        released: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        if (released.compareAndSet(false, true)) {
            channelAdmissionGate.release(owner)
        }
    }

    private fun sendAck(outcome: ChannelTransferAckOutcome) {
        app.applicationScope.launch(Dispatchers.IO) {
            runCatching {
                dataLayerRepository.sendAck(
                    outcome.sourceNodeId,
                    outcome.transferId,
                    outcome.status,
                    outcome.detail,
                )
            }.onFailure {
                TransferDiagnostics.warn(
                    "ChannelFgService",
                    "Missing handoff ACK failed transferId=${outcome.transferId}",
                )
            }
        }
    }

    private fun sendTerminalAfterCleanup(
        request: ChannelRequest,
        phase: String,
        ackStatus: String,
        detail: String,
    ) {
        val send = {
            app.applicationScope.launch(Dispatchers.IO) {
                runCatching { dataLayerRepository.sendStatus(request.sourceNodeId, request.transferId, phase, detail) }
                runCatching { dataLayerRepository.sendAck(request.sourceNodeId, request.transferId, ackStatus, detail) }
            }
        }
        request.job.get()?.invokeOnCompletion { send() } ?: send()
    }

    private fun finishRequest(request: ChannelRequest) {
        requestsByStartId.remove(request.startId, request)
        requestsByTransferId.remove(request.transferId, request)
        runCatching { notificationHelper.stopForeground(request.notificationId) }
        releaseAdmission(request.admissionOwner, request.admissionReleased)
        stopSelf(request.startId)
        TransferDiagnostics.log("ChannelFgService", "event=cleanup_complete transferId=${request.transferId}")
    }

    private data class ChannelRequest(
        val startId: Int,
        val transferId: String,
        val fileName: String,
        val sourceNodeId: String,
        val notificationId: Int,
        val handoff: ChannelTransferHandoff,
        val admissionOwner: Any,
        val terminalState: TransferTerminalState = TransferTerminalState(transferId),
        val job: AtomicReference<Job?> = AtomicReference(null),
        val admissionReleased: AtomicBoolean = AtomicBoolean(false),
    )

    private fun stopForStartId(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    @Suppress("TooGenericExceptionCaught") // A handler failure must become one terminal transfer error, not a crash.
    private fun createTransferJob(
        request: ChannelRequest,
        handoff: ChannelTransferHandoff,
    ): Job =
        serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                channelHandler(request).handleChannelOpened(handoff.channel)
            } catch (cancelled: CancellationException) {
                if (cancelled.message != FGS_DATA_SYNC_TIMEOUT &&
                    request.terminalState.tryClaim(TransferTerminalOutcome.CANCELLED)
                ) {
                    sendTerminalAfterCleanup(request, "CANCELLED", "ERROR", "Cancelled")
                }
            } catch (error: Exception) {
                val detail = error.message ?: "Unknown error"
                TransferDiagnostics.error(
                    "ChannelFgService",
                    "Unhandled channel failure transferId=${request.transferId} detail=$detail",
                    error,
                )
                if (request.terminalState.tryClaim(TransferTerminalOutcome.ERROR)) {
                    sendTerminalAfterCleanup(request, "ERROR", "ERROR", detail)
                }
            } finally {
                transferSessionState.clearActiveTransfer(request.transferId)
                finishRequest(request)
            }
        }

    private fun startRequest(
        startId: Int,
        transferId: String,
        handoff: ChannelTransferHandoff,
        encodedFileName: String,
        admissionOwner: Any,
    ): Int {
        val fileName = fileOps.sanitizeFileName(encodedFileName)
        val request =
            ChannelRequest(
                startId = startId,
                transferId = transferId,
                fileName = fileName,
                sourceNodeId = handoff.channel.nodeId,
                notificationId = transferId.hashCode(),
                handoff = handoff,
                admissionOwner = admissionOwner,
            )
        requestsByStartId[startId] = request
        requestsByTransferId[transferId] = request

        val foregroundFailure = startForegroundForRequest(request)
        if (foregroundFailure != null) {
            failBeforeTransfer(startId, transferId, handoff, foregroundFailure, request.admissionOwner)
            return START_NOT_STICKY
        }

        val job = createTransferJob(request, handoff)
        request.job.set(job)
        beginForegroundTransfer(
            transferId = transferId,
            fileName = fileName,
            sourceNodeId = handoff.channel.nodeId,
            notificationId = request.notificationId,
            job = job,
        )
        transferSessionState.registerActiveTransfer(
            transferId = transferId,
            job = job,
            fileName = fileName,
            sourceNodeId = handoff.channel.nodeId,
        )
        job.start()
        return START_NOT_STICKY
    }

    private fun handleTimeout(startId: Int) {
        val request = requestsByStartId[startId] ?: return
        if (!request.terminalState.tryClaim(TransferTerminalOutcome.TIMEOUT)) return

        TransferDiagnostics.warn(
            "ChannelFgService",
            "event=fgs_timeout startId=$startId transferId=${request.transferId}",
        )
        sendTerminalAfterCleanup(request, "ERROR", "ERROR", FGS_DATA_SYNC_TIMEOUT)
        request.job.get()?.cancel(CancellationException(FGS_DATA_SYNC_TIMEOUT))
        runCatching { notificationHelper.stopForeground(request.notificationId) }
        stopSelf(startId)
    }

    companion object {
        private const val ACTION_START_CHANNEL_TRANSFER = "com.glancemap.glancemapwearos.action.START_CHANNEL_TRANSFER"
        private const val EXTRA_TRANSFER_ID = "transfer_id"
        private const val EXTRA_SOURCE_NODE_ID = "source_node_id"

        fun startTransfer(
            context: Context,
            transferId: String,
            sourceNodeId: String,
        ) {
            val intent =
                Intent(context, ChannelTransferForegroundService::class.java).apply {
                    action = ACTION_START_CHANNEL_TRANSFER
                    putExtra(EXTRA_TRANSFER_ID, transferId)
                    putExtra(EXTRA_SOURCE_NODE_ID, sourceNodeId)
                }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

internal class ChannelTransferSessionOwner {
    private val owner = ForegroundTransferSessionOwner()

    @Suppress("LongParameterList")
    fun begin(
        startId: Int,
        transferId: String,
        fileName: String,
        sourceNodeId: String,
        notificationId: Int,
        job: Job,
        terminalState: TransferTerminalState = TransferTerminalState(transferId),
    ): ForegroundTransferSessionOwner.Session =
        owner.begin(
            startId = startId,
            transferId = transferId,
            fileName = fileName,
            sourceNodeId = sourceNodeId,
            notificationId = notificationId,
            job = job,
            terminalState = terminalState,
        )

    fun activeForTransferId(
        transferId: String,
    ): ForegroundTransferSessionOwner.Session? = owner.activeForTransferId(transferId)

    fun claim(
        transferId: String,
        outcome: TransferTerminalOutcome,
    ): Boolean = owner.claimForTransferId(transferId, outcome)

    fun outcomeForTransferId(transferId: String): TransferTerminalOutcome? = owner.outcomeForTransferId(transferId)

    fun end(transferId: String): Boolean {
        val session = owner.activeForTransferId(transferId) ?: return false
        return owner.end(session.startId, transferId)
    }

    fun all(): List<ForegroundTransferSessionOwner.Session> = owner.all()
}
