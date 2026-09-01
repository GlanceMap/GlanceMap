package com.glancemap.glancemapwearos.core.service

import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.DataLayerEventContext
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.ScreenOffActivityDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelClientStrategy
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.DataLayerHandlers
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferLockManager
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.glancemapwearos.data.repository.WatchDataLayerRepository
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

class DataLayerListenerService : WearableListenerService() {
    private val app by lazy { application as GlanceMapWearApp }
    private val lockManager by lazy { TransferLockManager(this) }
    private val transferSessionState by lazy { app.transferSessionState }
    private val serviceInstanceId = Integer.toHexString(System.identityHashCode(this))
    private val createdAtElapsedMs = SystemClock.elapsedRealtime()
    private val messageCallbackCount = AtomicLong()
    private val channelOpenedCallbackCount = AtomicLong()
    private val peerConnectedCallbackCount = AtomicLong()
    private val peerDisconnectedCallbackCount = AtomicLong()

    private val notificationHelper by lazy { NotificationHelper(this) }
    private val dataLayerRepository by lazy { WatchDataLayerRepository(this) }

    private val channelReceiver = ChannelClientStrategy()

    private val transferMutex get() = transferSessionState.transferMutex

    private val fileOps by lazy { WatchFileOps(app) }

    private val handlers by lazy {
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

    override fun onCreate() {
        super.onCreate()
        if (DebugTelemetry.isEnabled()) {
            TransferDiagnostics.log("Service", "Created instance=$serviceInstanceId")
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (DebugTelemetry.isEnabled()) {
            messageCallbackCount.incrementAndGet()
            recordFullDataLayerEvent(type = "Message", path = messageEvent.path)
        }
        ScreenOffActivityDiagnostics.dataLayer.recordMessage()
        handlers.handleMessage(messageEvent)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (DebugTelemetry.isEnabled()) {
            channelOpenedCallbackCount.incrementAndGet()
            recordFullDataLayerEvent(type = "ChannelOpened", path = channel.path)
        }
        ScreenOffActivityDiagnostics.dataLayer.recordChannelOpened()
        app.applicationScope.launch(Dispatchers.IO) {
            runCatching { handlers.handleChannelOpened(channel) }
                .onFailure { Log.e(TAG, "Channel handler failed: ${it.message}", it) }
        }
    }

    override fun onDestroy() {
        if (DebugTelemetry.isEnabled()) {
            val activeTransferId = transferSessionState.activeTransferId().orEmpty()
            val lifetimeMs = SystemClock.elapsedRealtime() - createdAtElapsedMs
            TransferDiagnostics.warn(
                "Service",
                "Destroy instance=$serviceInstanceId lifetimeMs=$lifetimeMs " +
                    "messages=${messageCallbackCount.get()} channels=${channelOpenedCallbackCount.get()} " +
                    "peerConnect=${peerConnectedCallbackCount.get()} " +
                    "peerDisconnect=${peerDisconnectedCallbackCount.get()} " +
                    "activeTransferId=$activeTransferId",
            )
        }
        super.onDestroy()
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
    internal suspend fun saveFile(
        fileName: String,
        inputStream: InputStream,
        expectedSize: Long? = null,
        resumeOffset: Long = 0L,
        keepPartialOnFailure: Boolean = false,
        computeSha256: Boolean = true,
        onProgress: (Long) -> Unit,
    ): String? =
        fileOps.saveFile(
            fileName = fileName,
            inputStream = inputStream,
            expectedSize = expectedSize,
            resumeOffset = resumeOffset,
            keepPartialOnFailure = keepPartialOnFailure,
            computeSha256 = computeSha256,
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

    // ---------- Peer logs ----------

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        if (DebugTelemetry.isEnabled()) {
            peerConnectedCallbackCount.incrementAndGet()
            recordFullDataLayerEvent(type = "Connected")
            Log.d(TAG, "📡 Peer connected: ${peer.displayName}")
            EnergyDiagnostics.recordEvent(
                reason = "peer_connected",
                detail = "name=${peer.displayName} id=${peer.id}",
            )
        }
        ScreenOffActivityDiagnostics.dataLayer.recordPeerConnected()
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        if (DebugTelemetry.isEnabled()) {
            peerDisconnectedCallbackCount.incrementAndGet()
            recordFullDataLayerEvent(type = "Disconnected")
            Log.d(TAG, "📡 Peer disconnected: ${peer.displayName}")
            EnergyDiagnostics.recordEvent(
                reason = "peer_disconnected",
                detail = "name=${peer.displayName} id=${peer.id}",
            )
        }
        ScreenOffActivityDiagnostics.dataLayer.recordPeerDisconnected()
    }

    private fun recordFullDataLayerEvent(
        type: String,
        path: String? = null,
    ) {
        if (!DebugTelemetry.isEnabled()) return

        val activeTransferId = transferSessionState.activeTransferId()
        val displayInteractive = getSystemService(PowerManager::class.java)?.isInteractive
        ScreenOffActivityDiagnostics.dataLayer.recordLastEvent(
            DataLayerEventContext(
                type = type,
                path = path,
                displayInteractive = displayInteractive,
                transferActive = activeTransferId != null,
                activeTransferId = activeTransferId,
            ),
        )
        TransferDiagnostics.log(
            if (type == "Connected" || type == "Disconnected") "Peer" else "Service",
            "$type${path?.let { " path=$it" }.orEmpty()} interactive=${displayInteractive ?: "na"} " +
                "transferActive=${activeTransferId != null} activeTransferId=${activeTransferId.orEmpty()}",
        )
    }

    companion object {
        private const val TAG = "DataLayerListener"
    }
}
