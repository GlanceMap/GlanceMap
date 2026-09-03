package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDownloadDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal object PhoneOfflineBundleDownloadRuntime {
    private val mutableState = MutableStateFlow<PhoneOfflineBundleDownloadState>(PhoneOfflineBundleDownloadState.Idle)
    val state: StateFlow<PhoneOfflineBundleDownloadState> = mutableState.asStateFlow()

    fun initialize(operation: PhoneOfflineBundleOperation?) {
        if (mutableState.value !is PhoneOfflineBundleDownloadState.Idle) return
        operation?.currentSelection()?.let { selection ->
            val progress =
                PhoneOfflineBundleProgress(
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                    detail = "Preparing ${selection.area.region}",
                )
            mutableState.value =
                when (operation.status) {
                    PhoneOfflineBundleOperationStatus.RUNNING ->
                        PhoneOfflineBundleDownloadState.Downloading(selection.area.id, progress)
                    PhoneOfflineBundleOperationStatus.PAUSED ->
                        PhoneOfflineBundleDownloadState.Paused(selection.area.id, progress)
                }
        }
    }

    fun publish(downloadState: PhoneOfflineBundleDownloadState) {
        mutableState.value = downloadState
    }
}

internal class PhoneOfflineBundleDownloadClient(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val operationStore = PhoneOfflineBundleOperationStore(applicationContext)

    init {
        PhoneOfflineBundleDownloadRuntime.initialize(operationStore.load())
    }

    fun start(selection: PhoneOfflineBundleSelection) {
        start(listOf(selection))
    }

    fun start(
        selections: List<PhoneOfflineBundleSelection>,
        refreshForces: List<PhoneOfflineBundleRefreshForces> = emptyList(),
    ) {
        if (selections.isEmpty() || PhoneOfflineBundleDownloadRuntime.state.value.isRunning()) return
        val normalizedForces =
            selections.indices.map { index ->
                refreshForces.getOrNull(index) ?: PhoneOfflineBundleRefreshForces()
            }
        val existing = operationStore.load()
        val operation =
            if (existing != null && existing.canResumeSameOperation(selections, normalizedForces)) {
                existing.copy(status = PhoneOfflineBundleOperationStatus.RUNNING)
            } else {
                PhoneOfflineBundleOperation(
                    selections = selections,
                    refreshForces = normalizedForces,
                )
            }
        operationStore.save(operation)
        PhoneOfflineBundleDownloadRuntime.publish(operation.asInitialState())
        PhoneOfflineBundleDownloadService.start(applicationContext)
    }

    fun pause() = PhoneOfflineBundleDownloadService.requestPause(applicationContext)

    fun stop() = PhoneOfflineBundleDownloadService.requestStop(applicationContext)

    fun resume() {
        val operation = operationStore.load() ?: return
        operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.RUNNING))
        PhoneOfflineBundleDownloadRuntime.publish(operation.asInitialState())
        PhoneOfflineBundleDownloadService.start(applicationContext)
    }

    fun resumeIfNeeded() {
        if (operationStore.load()?.status == PhoneOfflineBundleOperationStatus.RUNNING) {
            PhoneOfflineBundleDownloadService.start(applicationContext)
        }
    }
}

internal class PhoneOfflineBundleDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var operationStore: PhoneOfflineBundleOperationStore
    private lateinit var bundleStore: PhoneOfflineBundleStore
    private lateinit var downloader: PhoneOfflineBundleDownloader
    private lateinit var notificationManager: NotificationManager
    private var operationJob: Job? = null
    private var stopRequest: StopRequest? = null
    private var foregroundStarted = false
    private var terminalStop = false
    private var destroyed = false
    private var lastNotificationUpdateAtMillis = 0L
    private var lastNotificationProgress = -1
    private var lastNotificationPhase: PhoneOfflineBundlePhase? = null
    private var lastNotificationDetail = ""

    override fun onCreate() {
        super.onCreate()
        operationStore = PhoneOfflineBundleOperationStore(applicationContext)
        bundleStore = PhoneOfflineBundleStore(applicationContext)
        downloader = PhoneOfflineBundleDownloader(applicationContext, bundleStore = bundleStore)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_PAUSE -> requestStop(StopRequest.PAUSE)
            ACTION_STOP -> requestStop(StopRequest.STOP)
            ACTION_START,
            ACTION_RESUME,
            null,
            -> {
                if (intent?.action == ACTION_START || intent?.action == ACTION_RESUME) {
                    operationStore.load()?.let { operation ->
                        operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.RUNNING))
                    }
                }
                startOperation()
            }
        }
        return if (operationJob?.isActive == true || operationStore.load()?.status == PhoneOfflineBundleOperationStatus.RUNNING) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?) = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        super.onTimeout(startId, fgsType)
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return
        PhoneDownloadDiagnostics.warn(
            "Bundle",
            "Foreground service time limit reached; preserving partial files and pausing.",
        )
        requestStop(StopRequest.PAUSE)
    }

    override fun onDestroy() {
        destroyed = true
        operationStore.load()?.let { operation ->
            if (stopRequest == null && !terminalStop) {
                operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.RUNNING))
            }
        }
        downloader.cancelActiveDownloads()
        serviceScope.cancel()
        if (foregroundStarted) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        }
        super.onDestroy()
    }

    private fun startOperation() {
        if (operationJob?.isActive == true) return
        val operation =
            operationStore.load() ?: run {
                stopSelf()
                return
            }
        if (operation.status != PhoneOfflineBundleOperationStatus.RUNNING) {
            stopSelf()
            return
        }
        val selection =
            operation.currentSelection() ?: run {
                operationStore.clear()
                stopSelf()
                return
            }
        stopRequest = null
        terminalStop = false
        val initialProgress =
            PhoneOfflineBundleProgress(
                phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                detail = "Preparing ${selection.area.region}",
            )
        if (!startForegroundFor(selection.area.region, initialProgress)) {
            handleFailure(operation, selection.area.id, PhoneOfflineBundleFailure.STORAGE)
            return
        }
        PhoneOfflineBundleDownloadRuntime.publish(
            PhoneOfflineBundleDownloadState.Downloading(selection.area.id, initialProgress),
        )
        operationJob = serviceScope.launch { runOperation(operation) }
    }

    private suspend fun runOperation(initialOperation: PhoneOfflineBundleOperation) {
        var operation = initialOperation
        try {
            for (index in initialOperation.nextSelectionIndex until initialOperation.selections.size) {
                val selection = initialOperation.selections[index]
                val area = selection.area
                val initialProgress =
                    PhoneOfflineBundleProgress(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                        detail = "Preparing ${area.region}",
                    )
                PhoneOfflineBundleDownloadRuntime.publish(
                    PhoneOfflineBundleDownloadState.Downloading(area.id, initialProgress),
                )
                updateNotification(area.region, initialProgress)
                val outcome =
                    downloader.download(
                        selection = selection,
                        forces = operation.forcesFor(index),
                    ) { progress ->
                        PhoneOfflineBundleDownloadRuntime.publish(
                            PhoneOfflineBundleDownloadState.Downloading(area.id, progress),
                        )
                        updateNotification(area.region, progress)
                    }
                when (outcome) {
                    is PhoneOfflineBundleOutcome.Success -> {
                        operation =
                            operation.copy(
                                nextSelectionIndex = index + 1,
                                status = PhoneOfflineBundleOperationStatus.RUNNING,
                            )
                        operationStore.save(operation)
                    }
                    is PhoneOfflineBundleOutcome.Failure -> {
                        handleFailure(operation, area.id, outcome.reason)
                        return
                    }
                }
            }
            completeOperation(initialOperation.selections)
        } catch (exception: CancellationException) {
            handleCancellation(operation)
        } catch (exception: Exception) {
            PhoneDownloadDiagnostics.error("Bundle", "Foreground bundle operation failed", exception)
            val areaId = operation.currentSelection()?.area?.id
            if (areaId != null) {
                handleFailure(operation, areaId, PhoneOfflineBundleFailure.STORAGE)
            } else {
                operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.PAUSED))
                stopSelf()
            }
        } finally {
            operationJob = null
        }
    }

    private fun completeOperation(selections: List<PhoneOfflineBundleSelection>) {
        val lastBundle = selections.lastOrNull()?.let { selection -> bundleStore.find(selection.area.id) }
        operationStore.clear()
        PhoneOfflineBundleDownloadRuntime.publish(
            lastBundle?.let(PhoneOfflineBundleDownloadState::Completed)
                ?: PhoneOfflineBundleDownloadState.Idle,
        )
        showTerminalNotification(
            title = "Offline bundle download complete",
            detail = selections.lastOrNull()?.area?.region ?: "Bundle ready",
            includeResumeAction = false,
        )
    }

    private fun requestStop(request: StopRequest) {
        val operation =
            operationStore.load() ?: run {
                stopSelf()
                return
            }
        stopRequest = request
        operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.PAUSED))
        downloader.cancelActiveDownloads()
        val activeJob = operationJob
        if (activeJob?.isActive == true) {
            activeJob.cancel(CancellationException(request.name.lowercase()))
        } else {
            publishStoppedState(operation, request)
        }
    }

    private fun handleCancellation(operation: PhoneOfflineBundleOperation) {
        if (destroyed) return
        val request = stopRequest
        if (request == null) {
            operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.RUNNING))
            PhoneOfflineBundleDownloadRuntime.publish(operation.asInitialState())
            return
        }
        operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.PAUSED))
        publishStoppedState(operation, request)
    }

    private fun publishStoppedState(
        operation: PhoneOfflineBundleOperation,
        request: StopRequest,
    ) {
        val selection =
            operation.currentSelection() ?: run {
                stopSelf()
                return
            }
        val progress =
            when (val state = PhoneOfflineBundleDownloadRuntime.state.value) {
                is PhoneOfflineBundleDownloadState.Downloading -> state.progress
                is PhoneOfflineBundleDownloadState.Paused -> state.progress
                is PhoneOfflineBundleDownloadState.Stopped -> state.progress
                else ->
                    PhoneOfflineBundleProgress(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                        detail = "Partial files saved for ${selection.area.region}",
                    )
            }
        PhoneOfflineBundleDownloadRuntime.publish(
            if (request == StopRequest.PAUSE) {
                PhoneOfflineBundleDownloadState.Paused(selection.area.id, progress)
            } else {
                PhoneOfflineBundleDownloadState.Stopped(selection.area.id, progress)
            },
        )
        showTerminalNotification(
            title =
                if (request == StopRequest.PAUSE) {
                    "Offline bundle download paused"
                } else {
                    "Offline bundle download stopped"
                },
            detail = "Partial files saved for ${selection.area.region}",
            includeResumeAction = true,
        )
    }

    private fun handleFailure(
        operation: PhoneOfflineBundleOperation,
        areaId: String,
        reason: PhoneOfflineBundleFailure,
    ) {
        operationStore.save(operation.copy(status = PhoneOfflineBundleOperationStatus.PAUSED))
        PhoneOfflineBundleDownloadRuntime.publish(
            PhoneOfflineBundleDownloadState.Failed(reason = reason, areaId = areaId),
        )
        showTerminalNotification(
            title = "Offline bundle download failed",
            detail = "${failureLabel(reason)} Partial files saved; retry to resume.",
            includeResumeAction = true,
        )
    }

    @Synchronized
    private fun startForegroundFor(
        areaLabel: String,
        progress: PhoneOfflineBundleProgress,
    ): Boolean {
        val started =
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildProgressNotification(areaLabel, progress),
                    foregroundServiceType(),
                )
                foregroundStarted = true
            }.onFailure { error ->
                PhoneDownloadDiagnostics.error("Bundle", "Unable to start download foreground notification", error)
            }.isSuccess
        if (started) rememberNotification(progress)
        return started
    }

    @Synchronized
    private fun updateNotification(
        areaLabel: String,
        progress: PhoneOfflineBundleProgress,
    ) {
        if (!foregroundStarted || !canPostNotifications()) return
        val now = SystemClock.elapsedRealtime()
        val notificationProgress = notificationProgress(progress)
        val phaseChanged = progress.phase != lastNotificationPhase
        val detailChanged = progress.detail != lastNotificationDetail
        val percentChanged =
            notificationProgress != null &&
                (lastNotificationProgress < 0 || notificationProgress != lastNotificationProgress)
        val intervalElapsed = now - lastNotificationUpdateAtMillis >= MIN_NOTIFICATION_UPDATE_INTERVAL_MILLIS
        if (!phaseChanged && !detailChanged && !percentChanged && !intervalElapsed) return
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(areaLabel, progress))
        }.onSuccess {
            rememberNotification(progress, now)
        }.onFailure { error ->
            PhoneDownloadDiagnostics.warn(
                "Bundle",
                "Unable to update download notification: ${error::class.java.simpleName}",
            )
        }
    }

    private fun buildProgressNotification(
        areaLabel: String,
        progress: PhoneOfflineBundleProgress,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                .setContentTitle("Downloading offline bundle")
                .setContentText("$areaLabel · ${progress.detail.ifBlank { phaseLabel(progress.phase) }}")
                .setContentIntent(contentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setStyle(NotificationCompat.BigTextStyle().bigText(progress.detail))
                .addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    serviceAction(ACTION_PAUSE, REQUEST_PAUSE),
                ).addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    serviceAction(ACTION_STOP, REQUEST_STOP),
                )
        val percent = progress.percent
        val totalBytes = progress.totalBytes
        when {
            percent != null -> builder.setProgress(100, percent.coerceIn(0, 100), false)
            totalBytes != null && totalBytes > 0L ->
                builder.setProgress(
                    100,
                    ((progress.bytesDownloaded.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100),
                    false,
                )
            else -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    @Synchronized
    private fun showTerminalNotification(
        title: String,
        detail: String,
        includeResumeAction: Boolean,
    ) {
        terminalStop = true
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (includeResumeAction) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                serviceAction(ACTION_RESUME, REQUEST_RESUME),
            )
        }
        if (canPostNotifications()) {
            runCatching { notificationManager.notify(NOTIFICATION_ID, builder.build()) }
                .onFailure { error ->
                    PhoneDownloadDiagnostics.warn(
                        "Bundle",
                        "Unable to post terminal download notification: ${error::class.java.simpleName}",
                    )
                }
        } else {
            PhoneDownloadDiagnostics.warn("Bundle", "POST_NOTIFICATIONS is not granted; download notification hidden")
        }
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Offline map downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivityMobile::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun serviceAction(
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PhoneOfflineBundleDownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun rememberNotification(
        progress: PhoneOfflineBundleProgress,
        now: Long = SystemClock.elapsedRealtime(),
    ) {
        lastNotificationUpdateAtMillis = now
        lastNotificationProgress = notificationProgress(progress) ?: -1
        lastNotificationPhase = progress.phase
        lastNotificationDetail = progress.detail
    }

    private enum class StopRequest {
        PAUSE,
        STOP,
    }

    companion object {
        const val ACTION_START = "com.glancemap.glancemapcompanionapp.action.START_OFFLINE_BUNDLE_DOWNLOAD"
        const val ACTION_RESUME = "com.glancemap.glancemapcompanionapp.action.RESUME_OFFLINE_BUNDLE_DOWNLOAD"
        const val ACTION_PAUSE = "com.glancemap.glancemapcompanionapp.action.PAUSE_OFFLINE_BUNDLE_DOWNLOAD"
        const val ACTION_STOP = "com.glancemap.glancemapcompanionapp.action.STOP_OFFLINE_BUNDLE_DOWNLOAD"
        const val CHANNEL_ID = "phone_offline_bundle_downloads"
        const val NOTIFICATION_ID = 42_230
        const val REQUEST_OPEN_APP = 42_231
        const val REQUEST_PAUSE = 42_232
        const val REQUEST_STOP = 42_233
        const val REQUEST_RESUME = 42_234
        const val MIN_NOTIFICATION_UPDATE_INTERVAL_MILLIS = 750L

        fun start(context: Context) {
            val intent = Intent(context, PhoneOfflineBundleDownloadService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestPause(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PhoneOfflineBundleDownloadService::class.java).setAction(ACTION_PAUSE),
                )
            }
        }

        fun requestStop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PhoneOfflineBundleDownloadService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}

private fun notificationProgress(progress: PhoneOfflineBundleProgress): Int? =
    progress.percent?.coerceIn(0, 100)
        ?: progress.totalBytes
            ?.takeIf { it > 0L }
            ?.let { total ->
                ((progress.bytesDownloaded.coerceAtLeast(0L).coerceAtMost(total) * 100L) / total)
                    .toInt()
                    .coerceIn(0, 100)
            }

private fun PhoneOfflineBundleOperation.currentSelection(): PhoneOfflineBundleSelection? = selections.getOrNull(nextSelectionIndex)

private fun PhoneOfflineBundleOperation.asInitialState(): PhoneOfflineBundleDownloadState {
    val selection =
        currentSelection()
            ?: return PhoneOfflineBundleDownloadState.Idle
    val progress =
        PhoneOfflineBundleProgress(
            phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
            detail = "Preparing ${selection.area.region}",
        )
    return when (status) {
        PhoneOfflineBundleOperationStatus.RUNNING ->
            PhoneOfflineBundleDownloadState.Downloading(selection.area.id, progress)
        PhoneOfflineBundleOperationStatus.PAUSED ->
            PhoneOfflineBundleDownloadState.Paused(selection.area.id, progress)
    }
}

internal fun PhoneOfflineBundleOperation.canResumeSameOperation(
    selections: List<PhoneOfflineBundleSelection>,
    refreshForces: List<PhoneOfflineBundleRefreshForces>,
): Boolean =
    nextSelectionIndex < this.selections.size &&
        this.selections == selections &&
        selections.indices.all { index -> forcesFor(index) == refreshForces[index] }

private fun PhoneOfflineBundleDownloadState.isRunning(): Boolean = this is PhoneOfflineBundleDownloadState.Downloading

private fun phaseLabel(phase: PhoneOfflineBundlePhase): String =
    when (phase) {
        PhoneOfflineBundlePhase.DOWNLOADING_MAP -> "Downloading map"
        PhoneOfflineBundlePhase.INSTALLING_MAP -> "Installing map"
        PhoneOfflineBundlePhase.DOWNLOADING_POI -> "Downloading POI"
        PhoneOfflineBundlePhase.INSTALLING_POI -> "Installing POI"
        PhoneOfflineBundlePhase.DOWNLOADING_ROUTING -> "Downloading routing"
        PhoneOfflineBundlePhase.DOWNLOADING_DEM -> "Downloading elevation"
        PhoneOfflineBundlePhase.DOWNLOADING_REFUGES -> "Downloading Refuges.info"
    }

private fun failureLabel(reason: PhoneOfflineBundleFailure): String =
    when (reason) {
        PhoneOfflineBundleFailure.NETWORK -> "Network error."
        PhoneOfflineBundleFailure.HTTP -> "The download server rejected the file."
        PhoneOfflineBundleFailure.STORAGE -> "Storage error."
        PhoneOfflineBundleFailure.ARCHIVE -> "The archive was invalid."
        PhoneOfflineBundleFailure.INVALID_MAP -> "The map was invalid."
        PhoneOfflineBundleFailure.INVALID_POI -> "The POI database was invalid."
        PhoneOfflineBundleFailure.INVALID_REFUGES_INFO -> "The Refuges.info database was invalid."
        PhoneOfflineBundleFailure.CANCELLED -> "The download was cancelled."
    }
