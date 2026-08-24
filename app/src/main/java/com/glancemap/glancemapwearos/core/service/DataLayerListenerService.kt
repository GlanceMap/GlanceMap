package com.glancemap.glancemapwearos.core.service

import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelClientStrategy
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.DataLayerHandlers
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferLockManager
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.glancemapwearos.data.repository.WatchDataLayerRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.activehike.WatchLiveHikeSyncPreferences
import com.glancemap.shared.transfer.LiveHikeSyncSettingsCodec
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream

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
        if (messageEvent.path == TransferDataLayerContract.PATH_LIVE_HIKE_SYNC_SETTINGS) {
            val enabled =
                LiveHikeSyncSettingsCodec.decode(messageEvent.data) ?: run {
                    Log.w(TAG, "Ignoring malformed Live Hike sync setting")
                    return
                }
            WatchLiveHikeSyncPreferences.setEnabled(applicationContext, enabled)
            Log.d(TAG, "Live Hike sync ${if (enabled) "enabled" else "disabled"} by companion")
            return
        }
        handlers.handleMessage(messageEvent)
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
        Log.d(TAG, "📡 Peer connected: ${peer.displayName}")
        TransferDiagnostics.log("Peer", "Connected name=${peer.displayName} id=${peer.id}")
        EnergyDiagnostics.recordEvent(
            reason = "peer_connected",
            detail = "name=${peer.displayName} id=${peer.id}",
        )
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        Log.d(TAG, "📡 Peer disconnected: ${peer.displayName}")
        TransferDiagnostics.warn("Peer", "Disconnected name=${peer.displayName} id=${peer.id}")
        EnergyDiagnostics.recordEvent(
            reason = "peer_disconnected",
            detail = "name=${peer.displayName} id=${peer.id}",
        )
    }

    companion object {
        private const val TAG = "DataLayerListener"
    }
}
