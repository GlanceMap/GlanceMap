@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package com.glancemap.glancemapwearos.presentation.features.download

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OamDownloadForegroundService : Service() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceClient by lazy { OamDownloadServiceClient(applicationContext) }
    private val downloader by lazy {
        val container = (application as GlanceMapWearApp).container
        OamBundleDownloader(
            context = applicationContext,
            mapRepository = container.mapRepository,
            poiRepository = container.poiRepository,
        )
    }
    private val networkMonitor by lazy { OamDownloadNetworkMonitor(applicationContext) }
    private val operationGate = OamDownloadOperationGate()
    private var operationJob: Job? = null
    private var stopRequest: OwnedStopRequest? = null
    private var foregroundStartId: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLockAcquiredAtElapsedMs: Long? = null
    private var wakeLockGeneration = 0
    private var terminalNotificationPosted = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_RUN_PERSISTED -> startPersistedOperation(startId)
            ACTION_PAUSE -> requestOwnedStop(OwnedStopRequest.PAUSE)
            ACTION_CANCEL -> requestOwnedStop(OwnedStopRequest.CANCEL)
            ACTION_PROGRESS -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Downloading offline bundle"
                val detail = intent.getStringExtra(EXTRA_DETAIL) ?: "Preparing"
                val bytesDone = intent.getLongExtra(EXTRA_BYTES_DONE, 0L)
                val totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, -1L).takeIf { it > 0L }
                startOrUpdateForeground(title, detail, bytesDone, totalBytes)
                foregroundStartId = startId
            }
            ACTION_STOP -> stopSelf()
            null -> {
                if (serviceClient.loadPlan()?.status == OamPersistedDownloadStatus.RUNNING) {
                    startPersistedOperation(startId)
                } else {
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return if (serviceClient.loadPlan()?.status == OamPersistedDownloadStatus.RUNNING) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (!terminalNotificationPosted) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        }
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        super.onTimeout(startId, fgsType)
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return
        if (operationGate.matchesStartId(startId)) {
            if (operationGate.claimTimeout(startId) != null) {
                requestOwnedStop(OwnedStopRequest.TIMEOUT, alreadyClaimed = true)
            }
            clearForegroundAndStop(startId)
        } else if (!operationGate.hasActiveSession() && foregroundStartId == startId) {
            clearForegroundAndStop(startId)
        }
    }

    private fun startPersistedOperation(startId: Int) {
        if (operationJob?.isActive == true) return
        val initialPlan = serviceClient.loadPlan()
        if (initialPlan == null || initialPlan.status != OamPersistedDownloadStatus.RUNNING) {
            stopSelf()
            return
        }
        val session = operationGate.begin(startId)
        stopRequest = null
        foregroundStartId = startId
        startOrUpdateForeground(
            title = "Downloading offline bundle",
            detail = "Preparing ${initialPlan.areaIds.size} area(s)",
            bytesDone = 0L,
            totalBytes = null,
        )
        operationJob =
            serviceScope.launch {
                runPersistedOperation(initialPlan, session)
            }
    }

    private suspend fun runPersistedOperation(
        initialPlan: OamPersistedDownloadPlan,
        session: OamDownloadOperationSession,
    ) {
        var plan = initialPlan
        val areasById = OamDownloadCatalog.areas.associateBy(OamDownloadArea::id)
        val areas = plan.areaIds.mapNotNull(areasById::get)
        if (areas.size != plan.areaIds.size) {
            if (operationGate.claim(session, OamDownloadOperationEnd.FAILURE) != null) {
                failOwnedOperation(plan, "One or more download areas are no longer available.")
            }
            operationGate.finish(session)
            return
        }
        val networkHandle = watchForWifiRecovery(networkMonitor.currentState())
        val progressThrottler = OamProgressThrottler()
        try {
            serviceClient.publish(plan.runningState(phase = "STARTING"))
            for (index in plan.nextAreaIndex until areas.size) {
                val area = areas[index]
                downloader.downloadBundle(
                    area = area,
                    selection = plan.selection,
                    extractionKeepAliveState = ::currentKeepAliveState,
                ) { progress ->
                    if (!progress.shouldShowInBundleProgress()) return@downloadBundle
                    if (!progressThrottler.shouldEmit(progress)) return@downloadBundle
                    val detail = "${index + 1}/${areas.size} ${area.region} - ${progress.detail}"
                    val state =
                        plan.runningState(
                            phase = progress.phase,
                            detail = detail,
                            bytesDone = progress.bytesDone,
                            totalBytes = progress.totalBytes,
                        )
                    serviceClient.publish(state)
                    startOrUpdateForeground(
                        title = "Downloading offline bundle",
                        detail = detail,
                        bytesDone = progress.bytesDone,
                        totalBytes = progress.totalBytes,
                    )
                }
                plan = plan.copy(nextAreaIndex = index + 1)
                serviceClient.savePlan(plan)
            }
            if (operationGate.claim(session, OamDownloadOperationEnd.COMPLETE) != null) {
                completeOwnedOperation(areas)
            } else {
                handleOwnedCancellation(plan, session)
            }
        } catch (cancelled: CancellationException) {
            handleOwnedCancellation(plan, session)
            throw cancelled
        } catch (error: Exception) {
            if (operationGate.claim(session, OamDownloadOperationEnd.FAILURE) != null) {
                failOwnedOperation(plan, error.message ?: "Download failed")
            } else {
                handleOwnedCancellation(plan, session)
            }
        } finally {
            DebugTelemetry.log(
                "OamDownload",
                "event=progress_throttle_summary owner=service " +
                    "requested=${progressThrottler.requestedCount} emitted=${progressThrottler.emittedCount} " +
                    "suppressed=${progressThrottler.suppressedCount}",
            )
            networkHandle.close()
            if (operationGate.finish(session)) operationJob = null
        }
    }

    private fun completeOwnedOperation(areas: List<OamDownloadArea>) {
        serviceClient.clearPlan()
        serviceClient.publish(
            OamOwnedDownloadState(
                status = OamOwnedDownloadStatus.COMPLETE,
                areaCount = areas.size,
                completedAreaCount = areas.size,
                phase = "READY",
                detail = "${areas.size} area(s)",
            ),
        )
        val completionDetail =
            if (areas.size == 1) {
                "${areas.first().region} installed"
            } else {
                "${areas.size} bundles installed"
            }
        showTerminalNotification(
            title = "Download complete",
            detail = completionDetail,
            category = NotificationCompat.CATEGORY_STATUS,
            timeoutAfterMs = 10_000L,
        )
    }

    private fun requestOwnedStop(
        request: OwnedStopRequest,
        alreadyClaimed: Boolean = false,
    ) {
        val activeJob = operationJob
        if (
            !alreadyClaimed &&
            activeJob?.isActive == true &&
            operationGate.claimActive(request.toOperationEnd()) == null
        ) {
            return
        }
        stopRequest = request
        val persistedPlan = serviceClient.loadPlan()
        when (request) {
            OwnedStopRequest.PAUSE,
            OwnedStopRequest.TIMEOUT,
            -> {
                persistedPlan?.copy(status = OamPersistedDownloadStatus.PAUSED)?.let(serviceClient::savePlan)
            }
            OwnedStopRequest.CANCEL -> serviceClient.clearPlan()
        }
        val current = serviceClient.state.value
        serviceClient.publish(
            current.copy(
                phase = if (request == OwnedStopRequest.CANCEL) "CANCELING" else "PAUSING",
                errorMessage = null,
            ),
        )
        activeJob?.cancel(CancellationException(request.name.lowercase()))
        downloader.abortActiveDownloads(
            reason =
                when (request) {
                    OwnedStopRequest.PAUSE -> "user_pause"
                    OwnedStopRequest.TIMEOUT -> "foreground_service_timeout"
                    OwnedStopRequest.CANCEL -> "user_cancel"
                },
        )
        if (activeJob == null && persistedPlan != null) {
            serviceScope.launch { handleOwnedCancellation(persistedPlan) }
        }
    }

    private fun handleOwnedCancellation(
        plan: OamPersistedDownloadPlan,
        session: OamDownloadOperationSession? = null,
    ) {
        if (session != null && !operationGate.owns(session)) return
        when (stopRequest) {
            OwnedStopRequest.PAUSE -> {
                val paused = plan.copy(status = OamPersistedDownloadStatus.PAUSED)
                serviceClient.savePlan(paused)
                serviceClient.publish(
                    paused.runningState(phase = "PAUSED").copy(status = OamOwnedDownloadStatus.PAUSED),
                )
                showTerminalNotification(
                    title = "Download paused",
                    detail = "${plan.areaIds.size} area(s)",
                    category = NotificationCompat.CATEGORY_STATUS,
                )
            }
            OwnedStopRequest.TIMEOUT -> {
                val paused = plan.copy(status = OamPersistedDownloadStatus.PAUSED)
                serviceClient.savePlan(paused)
                serviceClient.publish(
                    paused.runningState(phase = "PAUSED").copy(status = OamOwnedDownloadStatus.PAUSED),
                )
            }
            OwnedStopRequest.CANCEL -> {
                serviceClient.clearPlan()
                serviceClient.publish(
                    OamOwnedDownloadState(
                        status = OamOwnedDownloadStatus.CANCELED,
                        areaCount = plan.areaIds.size,
                        completedAreaCount = plan.nextAreaIndex,
                        phase = "CANCELED",
                        detail = "${plan.areaIds.size} area(s)",
                    ),
                )
                clearForegroundAndStop()
            }
            null -> {
                // Android is stopping the process/service. Keep RUNNING persisted so START_STICKY can recover it.
                serviceClient.savePlan(plan.copy(status = OamPersistedDownloadStatus.RUNNING))
                serviceClient.publish(plan.runningState(phase = "STARTING", detail = "Waiting to resume"))
            }
        }
    }

    private fun failOwnedOperation(
        plan: OamPersistedDownloadPlan,
        message: String,
    ) {
        DebugTelemetry.log(
            "OamDownload",
            "event=bundle_failed completedAreas=${plan.nextAreaIndex} totalAreas=${plan.areaIds.size} " +
                "error=${message.replace(' ', '_')}",
        )
        serviceClient.clearPlan()
        serviceClient.publish(
            OamOwnedDownloadState(
                status = OamOwnedDownloadStatus.FAILED,
                areaCount = plan.areaIds.size,
                completedAreaCount = plan.nextAreaIndex,
                phase = "FAILED",
                detail = "${plan.areaIds.size} area(s)",
                errorMessage = message,
            ),
        )
        showTerminalNotification(
            title = "Download failed",
            detail = message,
            category = NotificationCompat.CATEGORY_ERROR,
        )
    }

    private fun watchForWifiRecovery(initialState: OamDownloadNetworkState): AutoCloseable {
        var observedWithoutValidatedWifi = !initialState.isValidatedWifi
        var reconnectRequested = false
        return networkMonitor.watchNetworkState { state ->
            when {
                !state.isValidatedWifi -> {
                    observedWithoutValidatedWifi = true
                    reconnectRequested = false
                }
                observedWithoutValidatedWifi && !reconnectRequested -> {
                    reconnectRequested = true
                    observedWithoutValidatedWifi = false
                    DebugTelemetry.log(
                        "OamDownload",
                        "event=auto_reconnect_request reason=wifi_recovered ${state.telemetryFields}",
                    )
                    downloader.abortActiveDownloads(reason = "wifi_recovered")
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    @Synchronized
    private fun startOrUpdateForeground(
        title: String,
        detail: String,
        bytesDone: Long,
        totalBytes: Long?,
    ) {
        acquireLocks()
        val notification = buildProgressNotification(title, detail, bytesDone, totalBytes)
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
    }

    @SuppressLint("WakelockTimeout")
    @Synchronized
    private fun acquireLocks() {
        var wakeLockAcquired = false
        var wifiLockAcquired = false
        if (wakeLock?.isHeld != true) {
            wakeLockAcquiredAtElapsedMs = null
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock =
                powerManager
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire(WAKE_LOCK_TIMEOUT_MS)
                        wakeLockAcquired = isHeld
                        if (wakeLockAcquired) {
                            wakeLockAcquiredAtElapsedMs = SystemClock.elapsedRealtime()
                            wakeLockGeneration += 1
                        }
                    }
        }
        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock =
                wifiManager
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        runCatching { acquire() }
                        wifiLockAcquired = isHeld
                    }
        }
        if (wakeLockAcquired || wifiLockAcquired) {
            val keepAliveState = currentKeepAliveState()
            DebugTelemetry.log(
                "OamDownload",
                "event=foreground_keepalive_acquired wakeLock=$wakeLockAcquired wifiLock=$wifiLockAcquired " +
                    "wakeLockType=${keepAliveState.wakeLockType} " +
                    "wakeLockGeneration=${keepAliveState.wakeLockGeneration} " +
                    "wakeLockTimeoutMs=${keepAliveState.wakeLockTimeoutMs ?: "na"}",
            )
        }
    }

    @Synchronized
    private fun releaseLocks() {
        val wakeLockWasHeld = wakeLock?.isHeld == true
        val wifiLockWasHeld = wifiLock?.isHeld == true
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wifiLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
        wifiLock = null
        wakeLockAcquiredAtElapsedMs = null
        if (wakeLockWasHeld || wifiLockWasHeld) {
            DebugTelemetry.log(
                "OamDownload",
                "event=foreground_keepalive_released wakeLock=$wakeLockWasHeld wifiLock=$wifiLockWasHeld",
            )
        }
    }

    @Synchronized
    private fun currentKeepAliveState(): OamDownloadKeepAliveState {
        val wakeLockHeld = wakeLock?.isHeld == true
        val acquireAgeMs =
            wakeLockAcquiredAtElapsedMs?.let {
                (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
            }
        return OamDownloadKeepAliveState(
            wakeLockHeld = wakeLockHeld,
            wifiLockHeld = wifiLock?.isHeld == true,
            wakeLockType = if (wakeLock != null) "PARTIAL_WAKE_LOCK" else "none",
            wakeLockAcquireAgeMs = acquireAgeMs,
            wakeLockTimeoutMs = if (wakeLock != null) WAKE_LOCK_TIMEOUT_MS else null,
            wakeLockGeneration = wakeLockGeneration,
        )
    }

    private fun buildProgressNotification(
        title: String,
        detail: String,
        bytesDone: Long,
        totalBytes: Long?,
    ): android.app.Notification {
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(contentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val total = totalBytes?.takeIf { it > 0L }
        if (total != null) {
            val progress = ((bytesDone.coerceAtLeast(0L) * 100L) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun showTerminalNotification(
        title: String,
        detail: String,
        category: String,
        timeoutAfterMs: Long? = null,
    ) {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        releaseLocks()
        foregroundStartId = null
        terminalNotificationPosted = true
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(contentIntent())
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(category)
        timeoutAfterMs?.let(builder::setTimeoutAfter)
        runCatching { notificationManager.notify(NOTIFICATION_ID, builder.build()) }
        stopSelf()
    }

    private fun clearForegroundAndStop(startId: Int? = null) {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        releaseLocks()
        if (startId == null || foregroundStartId == startId) foregroundStartId = null
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Map downloads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun contentIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val ACTION_RUN_PERSISTED =
            "com.glancemap.glancemapwearos.action.OAM_RUN_PERSISTED_DOWNLOAD"
        private const val ACTION_PAUSE = "com.glancemap.glancemapwearos.action.OAM_PAUSE_DOWNLOAD"
        private const val ACTION_CANCEL = "com.glancemap.glancemapwearos.action.OAM_CANCEL_DOWNLOAD"
        private const val ACTION_PROGRESS = "com.glancemap.glancemapwearos.action.OAM_DOWNLOAD_PROGRESS"
        private const val ACTION_STOP = "com.glancemap.glancemapwearos.action.OAM_DOWNLOAD_STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"
        private const val EXTRA_BYTES_DONE = "bytes_done"
        private const val EXTRA_TOTAL_BYTES = "total_bytes"
        private const val CHANNEL_ID = "OamDownloadChannel"
        private const val NOTIFICATION_ID = 42_210
        private const val WAKE_LOCK_TAG = "GlanceMap:OamDownload"
        private const val WIFI_LOCK_TAG = "GlanceMap:OamDownloadWifi"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L

        fun startPersistedDownload(context: Context) {
            val intent = Intent(context, OamDownloadForegroundService::class.java).setAction(ACTION_RUN_PERSISTED)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestPause(context: Context) {
            val intent = Intent(context, OamDownloadForegroundService::class.java).setAction(ACTION_PAUSE)
            runCatching { context.startService(intent) }
        }

        fun requestCancel(context: Context) {
            val intent = Intent(context, OamDownloadForegroundService::class.java).setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
        }

        fun showProgress(
            context: Context,
            title: String,
            detail: String,
            bytesDone: Long,
            totalBytes: Long?,
        ) {
            val intent =
                Intent(context, OamDownloadForegroundService::class.java).apply {
                    action = ACTION_PROGRESS
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_DETAIL, detail)
                    putExtra(EXTRA_BYTES_DONE, bytesDone)
                    putExtra(EXTRA_TOTAL_BYTES, totalBytes ?: -1L)
                }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OamDownloadForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
            context.stopService(Intent(context, OamDownloadForegroundService::class.java))
        }
    }
}

private enum class OwnedStopRequest {
    PAUSE,
    CANCEL,
    TIMEOUT,
}

private fun OwnedStopRequest.toOperationEnd(): OamDownloadOperationEnd =
    when (this) {
        OwnedStopRequest.PAUSE -> OamDownloadOperationEnd.PAUSE
        OwnedStopRequest.CANCEL -> OamDownloadOperationEnd.CANCEL
        OwnedStopRequest.TIMEOUT -> OamDownloadOperationEnd.TIMEOUT
    }

internal data class OamDownloadOperationSession(
    val startId: Int,
    val generation: Long,
)

internal enum class OamDownloadOperationEnd {
    PAUSE,
    CANCEL,
    TIMEOUT,
    COMPLETE,
    FAILURE,
}

/** Serializes terminal decisions from the service command and Android timeout callbacks. */
internal class OamDownloadOperationGate {
    private var nextGeneration = 0L
    private var activeSession: OamDownloadOperationSession? = null
    private var terminalEnd: OamDownloadOperationEnd? = null

    @Synchronized
    fun begin(startId: Int): OamDownloadOperationSession =
        OamDownloadOperationSession(startId = startId, generation = ++nextGeneration).also {
            activeSession = it
            terminalEnd = null
        }

    @Synchronized
    fun claimTimeout(startId: Int): OamDownloadOperationSession? {
        val session = activeSession?.takeIf { it.startId == startId } ?: return null
        return claim(session, OamDownloadOperationEnd.TIMEOUT)
    }

    @Synchronized
    fun claimActive(end: OamDownloadOperationEnd): OamDownloadOperationSession? = activeSession?.let { claim(it, end) }

    @Synchronized
    fun claim(
        session: OamDownloadOperationSession,
        end: OamDownloadOperationEnd,
    ): OamDownloadOperationSession? {
        if (activeSession != session || terminalEnd != null) return null
        terminalEnd = end
        return session
    }

    @Synchronized
    fun owns(session: OamDownloadOperationSession): Boolean = activeSession == session

    @Synchronized
    fun matchesStartId(startId: Int): Boolean = activeSession?.startId == startId

    @Synchronized
    fun hasActiveSession(): Boolean = activeSession != null

    @Synchronized
    fun terminalEndFor(session: OamDownloadOperationSession): OamDownloadOperationEnd? = if (activeSession == session) terminalEnd else null

    @Synchronized
    fun finish(session: OamDownloadOperationSession): Boolean {
        if (activeSession != session) return false
        activeSession = null
        terminalEnd = null
        return true
    }
}

private fun OamPersistedDownloadPlan.runningState(
    phase: String,
    detail: String = "${areaIds.size} area(s)",
    bytesDone: Long = 0L,
    totalBytes: Long? = null,
): OamOwnedDownloadState =
    OamOwnedDownloadState(
        status = OamOwnedDownloadStatus.RUNNING,
        areaIds = areaIds,
        selection = selection,
        areaCount = areaIds.size,
        completedAreaCount = nextAreaIndex,
        phase = phase,
        detail = detail,
        bytesDone = bytesDone,
        totalBytes = totalBytes,
    )
