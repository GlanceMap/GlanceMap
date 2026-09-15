package com.glancemap.glancemapwearos.core.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelClientStrategy
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.DataLayerHandlers
import com.glancemap.glancemapwearos.core.service.transfer.notifications.FGS_DATA_SYNC_TIMEOUT
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.ForegroundTransferSessionOwner
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferLockManager
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalOutcome
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.glancemapwearos.data.repository.WatchDataLayerRepository
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class DataLayerListenerService : WearableListenerService() {
    private val app by lazy { application as GlanceMapWearApp }
    private val lockManager by lazy { TransferLockManager(this) }
    private val transferSessionState by lazy { app.transferSessionState }
    private val serviceInstanceId = Integer.toHexString(System.identityHashCode(this))

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var dataLayerRepository: WatchDataLayerRepository

    private val channelReceiver = ChannelClientStrategy()

    private val transferMutex get() = transferSessionState.transferMutex

    private val fileOps by lazy { WatchFileOps(app) }

    private lateinit var handlers: DataLayerHandlers

    private val foregroundSessions = ForegroundTransferSessionOwner()
    private val foregroundStartId = AtomicInteger(NO_START_ID)

    override fun onCreate() {
        super.onCreate()
        TransferDiagnostics.log("Service", "Created instance=$serviceInstanceId")

        dataLayerRepository = WatchDataLayerRepository(this)
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()

        handlers =
            DataLayerHandlers(
                service = this,
                notificationHelper = notificationHelper,
                fileOps = fileOps,
                transferMutex = transferMutex,
                channelReceiver = channelReceiver,
                sessionState = transferSessionState,
                sendStatus = dataLayerRepository::sendStatus,
                sendAck = dataLayerRepository::sendAck,
                sendMessage = dataLayerRepository::sendMessage,
            )
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        handlers.handleMessage(messageEvent)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        foregroundStartId.set(startId)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        app.applicationScope.launch(Dispatchers.IO) {
            runCatching { handlers.handleChannelOpened(channel) }
                .onFailure { Log.e(TAG, "Channel handler failed: ${it.message}", it) }
        }
    }

    override fun onDestroy() {
        val activeTransferId = transferSessionState.activeTransferId().orEmpty()
        TransferDiagnostics.warn(
            "Service",
            "Destroy instance=$serviceInstanceId activeTransferId=$activeTransferId",
        )
        foregroundSessions.all().forEach { active ->
            active.job.cancel(CancellationException("Data layer service destroyed"))
            if (active.terminalState.tryClaim(TransferTerminalOutcome.CANCELLED)) {
                sendTerminalAfterCleanup(active, "CANCELLED", "ERROR", "Cancelled")
            }
            runCatching { notificationHelper.stopForeground(active.notificationId) }
        }
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        super.onTimeout(startId, fgsType)
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return

        val activeForStartId = foregroundSessions.activeForStartId(startId)
        val active = foregroundSessions.claim(startId, TransferTerminalOutcome.TIMEOUT)
        if (active == null) {
            val current = foregroundSessions.all().firstOrNull()
            val reason =
                if (activeForStartId == null && current != null && current.startId != startId) {
                    "stale_start_id"
                } else {
                    "terminal_already_claimed"
                }
            TransferDiagnostics.warn(
                "Service",
                "event=timeout_ignored reason=$reason timeoutStartId=$startId " +
                    "currentStartId=${current?.startId ?: "na"} " +
                    "transferId=${current?.transferId ?: activeForStartId?.transferId.orEmpty()} " +
                    "existing=${activeForStartId?.terminalState?.current() ?: "na"}",
            )
            return
        }
        TransferDiagnostics.warn(
            "Service",
            "event=fgs_timeout startId=$startId transferId=${active.transferId} " +
                "file=${active.fileName} fgsType=dataSync sdk=${Build.VERSION.SDK_INT}",
        )
        sendTerminalAfterCleanup(active, "ERROR", "ERROR", FGS_DATA_SYNC_TIMEOUT)
        active.job.cancel(CancellationException(FGS_DATA_SYNC_TIMEOUT))
        runCatching { notificationHelper.stopForeground(active.notificationId) }
        stopSelf(startId)
    }

    // ---------- used by handlers/strategies ----------

    fun appScope() = app.applicationScope

    internal fun onTransferStarted() {
        app.container.syncManager.onTransferStarted()
    }

    internal fun onTransferFinished() {
        app.container.syncManager.onTransferFinished()
    }

    /**
     * ✅ IMPORTANT: resumeOffset has a DEFAULT so old call sites compile.
     */
    @Suppress("LongParameterList")
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

    fun getPartialSize(fileName: String): Long = fileOps.getPartialSize(fileName)

    fun deletePartial(fileName: String): Boolean = fileOps.deletePartial(fileName)

    fun truncatePartial(
        fileName: String,
        expectedSize: Long,
    ): Boolean = fileOps.truncatePartial(fileName, expectedSize)

    fun computePartialFileSha256(fileName: String): String? = fileOps.computePartialFileSha256(fileName)

    suspend fun promotePartialToFinal(fileName: String): Boolean = fileOps.promotePartialToFinal(fileName)

    suspend fun deleteByName(fileName: String) {
        fileOps.deleteByName(fileName)
    }

    fun computeFinalFileSha256(fileName: String): String? = fileOps.computeFinalFileSha256(fileName)

    // ---------- Locks ----------

    fun acquireWakeLock(
        tag: String,
        timeoutMs: Long,
    ): PowerManager.WakeLock = lockManager.acquireWakeLock(tag, timeoutMs)

    fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        lockManager.releaseWakeLock(wakeLock)
    }

    fun holdPrewarmWakeLock(
        reason: String,
        timeoutMs: Long,
    ) {
        app.transferPrewarmHoldManager.hold(reason, timeoutMs)
    }

    fun releasePrewarmWakeLock(reason: String) {
        app.transferPrewarmHoldManager.release(reason)
    }

    fun acquireWifiLock(tag: String): WifiManager.WifiLock = lockManager.acquireWifiLock(tag)

    fun releaseWifiLock(wifiLock: WifiManager.WifiLock) {
        lockManager.releaseWifiLock(wifiLock)
    }

    internal fun beginForegroundTransfer(
        transferId: String,
        fileName: String,
        sourceNodeId: String,
        notificationId: Int,
        job: Job,
    ) {
        val startId = foregroundStartId.get()
        if (startId == NO_START_ID) {
            TransferDiagnostics.warn(
                "Service",
                "Foreground transfer has no service startId transferId=$transferId",
            )
        }
        foregroundSessions.begin(
            startId = startId,
            transferId = transferId,
            fileName = fileName,
            sourceNodeId = sourceNodeId,
            notificationId = notificationId,
            job = job,
        )
    }

    internal fun endForegroundTransfer(transferId: String) {
        foregroundSessions.activeForTransferId(transferId)?.let {
            foregroundSessions.end(it.startId, transferId)
        }
    }

    internal fun claimForegroundTransfer(
        transferId: String,
        outcome: TransferTerminalOutcome,
    ): Boolean = foregroundSessions.claimForTransferId(transferId, outcome)

    internal fun foregroundTransferOutcome(transferId: String): TransferTerminalOutcome? =
        foregroundSessions
            .outcomeForTransferId(transferId)

    private fun sendTerminalAfterCleanup(
        active: ForegroundTransferSessionOwner.Session,
        phase: String,
        ackStatus: String,
        detail: String,
    ) {
        active.job.invokeOnCompletion {
            app.applicationScope.launch(Dispatchers.IO) {
                TransferDiagnostics.log(
                    "Service",
                    "event=cleanup_complete transferId=${active.transferId}",
                )
                runCatching {
                    dataLayerRepository.sendStatus(active.sourceNodeId, active.transferId, phase, detail)
                }
                runCatching {
                    dataLayerRepository.sendAck(active.sourceNodeId, active.transferId, ackStatus, detail)
                }
            }
        }
    }

    // ---------- Peer logs ----------

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        Log.d(TAG, "📡 Peer connected: ${peer.displayName}")
        TransferDiagnostics.log("Peer", "Connected name=${peer.displayName}")
        EnergyDiagnostics.recordEvent(
            reason = "peer_connected",
            detail = "name=${peer.displayName}",
        )
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        Log.d(TAG, "📡 Peer disconnected: ${peer.displayName}")
        TransferDiagnostics.warn("Peer", "Disconnected name=${peer.displayName}")
        EnergyDiagnostics.recordEvent(
            reason = "peer_disconnected",
            detail = "name=${peer.displayName}",
        )
    }

    companion object {
        private const val TAG = "DataLayerListener"
        private const val NO_START_ID = -1
    }
}
