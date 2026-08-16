package com.glancemap.glancemapwearos.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferStrategy
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferLockManager
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferRunner
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferRuntimeHost
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.glancemap.glancemapwearos.data.repository.WatchDataLayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

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
                sendAck = dataLayerRepository::sendAck,
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

        activeTransfers.incrementAndGet()
        notificationHelper.startForeground(
            request.metadata.notificationId,
            request.metadata.fileName,
            "Preparing Download…",
        )
        TransferDiagnostics.log(
            "FgService",
            "Launch HTTP transfer id=${request.metadata.transferId} file=${request.metadata.fileName} startId=$startId",
        )

        serviceScope.launch {
            try {
                transferMutex.withLock {
                    val fileName = request.metadata.fileName
                    if (fileOps.fileExistsOnWatch(fileName)) {
                        val msg = "FILE_EXISTS:$fileName"
                        releasePrewarmWakeLock("http_rejected_exists:$fileName")
                        TransferDiagnostics.warn(
                            "FgService",
                            "Target file already exists id=${request.metadata.transferId} file=$fileName",
                        )
                        notificationHelper.startForeground(
                            request.metadata.notificationId,
                            request.metadata.fileName,
                            "Already exists",
                        )
                        notificationHelper.stopForeground(request.metadata.notificationId)
                        notificationHelper.showError(request.metadata.notificationId, request.metadata.fileName, "Already exists")
                        dataLayerRepository.sendStatus(request.metadata.sourceNodeId, request.metadata.transferId, "ERROR", msg)
                        dataLayerRepository.sendAck(request.metadata.sourceNodeId, request.metadata.transferId, "ERROR", msg)
                        return@withLock
                    }

                    runner.runHttp(request.metadata, request.httpPath)
                }
            } finally {
                transferSessionState.clearHttpTransfer(request.metadata.transferId)
                if (activeTransfers.decrementAndGet() <= 0) {
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TransferDiagnostics.warn(
            "FgService",
            "Destroy instance=$serviceInstanceId activeTransferId=${transferSessionState.activeTransferId().orEmpty()}",
        )
        runCatching { httpReceiver.close() }
        serviceScope.cancel()
        super.onDestroy()
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

    private data class TransferRequest(
        val metadata: ReceiverMetadata,
        val httpPath: String,
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
