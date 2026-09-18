@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.livetracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

// The service intentionally owns the complete foreground-session orchestration and its retries.
@Suppress("LargeClass")
class LiveTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var arkluzClient: ArkluzLiveTrackingClient
    private lateinit var cellularSignalMonitor: CellularSignalMonitor
    private val locationQualityGate = LiveTrackingLocationQualityGate()
    private var settings: LiveTrackingSettings? = null

    @Volatile
    private var lastLocation: Location? = null
    private var sentStart = false
    private var dateId: String? = null
    private var isPaused = false
    private var isStopping = false
    private val sendMutex = Mutex()

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                serviceScope.launch {
                    sendLocation(
                        location = location,
                        startRequested = true,
                        stop = false,
                        source = LiveTrackingFixSource.CALLBACK,
                    )
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        cellularSignalMonitor = CellularSignalMonitor(this)
        arkluzClient = ArkluzLiveTrackingClient(this, cellularSignalMonitor::currentPercent)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int =
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (restoreActiveSessionIfNeeded()) pauseTracking()
                START_REDELIVER_INTENT
            }

            ACTION_RESUME -> {
                if (restoreActiveSessionIfNeeded()) resumeTracking()
                START_REDELIVER_INTENT
            }

            ACTION_UPDATE_ALERT_SETTINGS -> {
                if (!restoreActiveSessionIfNeeded()) {
                    stopSelf(startId)
                    START_NOT_STICKY
                } else {
                    updateAlertSettings(intent)
                    START_REDELIVER_INTENT
                }
            }

            ACTION_STOP -> {
                stopTracking()
                START_NOT_STICKY
            }

            else -> startOrRestoreTracking(intent)
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { locationClient.removeLocationUpdates(locationCallback) }
        cellularSignalMonitor.stop()
        LiveTrackingDiagnostics.finishLiveTrackingSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startOrRestoreTracking(intent: Intent?): Int =
        if (restoreActiveSessionIfNeeded()) {
            START_REDELIVER_INTENT
        } else {
            val parsedSettings = intent?.toLiveTrackingSettings()
            if (parsedSettings == null) {
                LiveTrackingSessionStore.setStopped("Missing live tracking settings")
                stopSelf()
                START_NOT_STICKY
            } else {
                settings = parsedSettings
                sentStart = false
                dateId = null
                isPaused = false
                isStopping = false
                LiveTrackingControlQueue.clear(this)
                persistActiveSession()
                LiveTrackingSessionStore.setStarting()
                startForegroundNotification("Starting live tracking")
                startTracking()
                START_REDELIVER_INTENT
            }
        }

    private fun restoreActiveSessionIfNeeded(): Boolean =
        if (settings != null) {
            true
        } else {
            val session = LiveTrackingActiveSessionStore.load(this)
            if (session == null) {
                false
            } else {
                settings = session.settings
                isPaused = session.isPaused
                sentStart = session.sentStart
                dateId = session.dateId
                isStopping = false
                if (isPaused) {
                    startForegroundNotification("Live tracking paused")
                    LiveTrackingSessionStore.setPaused()
                } else {
                    startForegroundNotification("Restoring live tracking")
                    LiveTrackingSessionStore.setActive(status = "Restoring GPS tracking")
                    startTracking()
                }
                true
            }
        }

    private fun updateAlertSettings(intent: Intent) {
        val notificationEmails = intent.getStringExtra(EXTRA_NOTIFICATION_EMAILS).orEmpty()
        val alertEmails = intent.getStringExtra(EXTRA_ALERT_EMAILS).orEmpty()
        val stuckAlarmMinutes = intent.getStringExtra(EXTRA_STUCK_ALARM_MINUTES).orEmpty()
        serviceScope.launch {
            sendMutex.withLock {
                settings =
                    settings?.copy(
                        notificationEmails = notificationEmails,
                        alertEmails = alertEmails,
                        stuckAlarmMinutes = stuckAlarmMinutes,
                    )
                persistActiveSession()
            }
        }
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            finishStopped("Location permission is required")
            return
        }

        val activeSettings = settings ?: return
        LiveTrackingDiagnostics.beginLiveTrackingSession(
            trackingUrl = activeSettings.trackingUrl,
            updateIntervalSeconds = activeSettings.updateIntervalSeconds,
            cellularMonitorAvailable = cellularSignalMonitor.isAvailable(),
        )
        cellularSignalMonitor.start()
        serviceScope.launch {
            runCatching {
                LiveTrackingSessionStore.setStatus("Waiting for GPS fix")
                updateNotification("Waiting for GPS fix")
                startLocationUpdates()
                sendLastKnownLocationIfAvailable()
            }.onFailure { error ->
                LiveTrackingSessionStore.setError(error.message ?: "Live tracking start failed")
                updateNotification("Live tracking error")
            }
        }
    }

    // A stale cache must return immediately so the active high-accuracy request can provide the fix.
    @Suppress("ReturnCount", "LongMethod")
    @SuppressLint("MissingPermission")
    private suspend fun sendLastKnownLocationIfAvailable() {
        if (!hasLocationPermission()) return
        val location = runCatching { locationClient.lastLocation.await() }.getOrNull()
        if (location == null) {
            LiveTrackingDiagnostics.recordLiveTrackingStartup(
                cachedLocationExists = false,
                cachedLocationAgeMillis = null,
                cachedLocationAccepted = null,
            )
            return
        }
        val fix = location.toLiveTrackingLocationFix()
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val nowEpochMilliseconds = System.currentTimeMillis()
        val ageMillis =
            liveTrackingLocationAgeMillis(
                fix = fix,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                nowEpochMilliseconds = nowEpochMilliseconds,
            )
        val isFresh =
            isFreshLiveTrackingCachedLocation(
                fix = fix,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                nowEpochMilliseconds = nowEpochMilliseconds,
            )
        LiveTrackingDiagnostics.recordLiveTrackingStartup(
            cachedLocationExists = true,
            cachedLocationAgeMillis = ageMillis,
            cachedLocationAccepted = isFresh,
        )
        if (!isFresh) {
            val decision =
                LiveTrackingLocationQualityDecision(
                    result = LiveTrackingLocationQualityResult.REJECT,
                    reason = if (ageMillis == null) "unknown_startup_cache_age" else "stale_startup_cache",
                    accuracyMeters = fix.accuracyMeters,
                    fixAgeMillis = ageMillis,
                    distanceFromPreviousMeters = null,
                    impliedSpeedMetersPerSecond = null,
                )
            LiveTrackingDiagnostics.recordLiveTrackingFix(
                source = LiveTrackingFixSource.CACHED_STARTUP,
                decision = decision,
                androidSpeedMetersPerSecond = fix.speedMetersPerSecond,
                isMockLocation = location.isMockLocationForDiagnostics(),
                gsmSignalPercent = cellularSignalMonitor.currentPercent(),
                queueSize = diagnosticPositionQueueSize(),
            )
            LiveTrackingDiagnostics.recordLocationQuality(
                decision = decision,
                gsmSignalPercent = cellularSignalMonitor.currentPercent(),
            )
            return
        }
        sendLocation(
            location = location,
            startRequested = true,
            stop = false,
            source = LiveTrackingFixSource.CACHED_STARTUP,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun sendLocation(
        location: Location,
        startRequested: Boolean,
        stop: Boolean,
        source: LiveTrackingFixSource = LiveTrackingFixSource.CALLBACK,
    ) {
        if (isPaused && !stop) return
        sendMutex.withLock {
            if ((isPaused || isStopping) && !stop) return@withLock
            val activeSettings = settings ?: return@withLock
            val fix = location.toLiveTrackingLocationFix()
            val qualityDecision =
                if (stop) {
                    null
                } else {
                    locationQualityGate
                        .evaluate(
                            fix = fix,
                            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                            nowEpochMilliseconds = System.currentTimeMillis(),
                            startupStaleReason =
                                if (source == LiveTrackingFixSource.CACHED_STARTUP) {
                                    "stale_startup_cache"
                                } else {
                                    "stale_startup_callback"
                                },
                        ).also { decision ->
                            LiveTrackingDiagnostics.recordLiveTrackingFix(
                                source = source,
                                decision = decision,
                                androidSpeedMetersPerSecond = fix.speedMetersPerSecond,
                                isMockLocation = location.isMockLocationForDiagnostics(),
                                gsmSignalPercent = cellularSignalMonitor.currentPercent(),
                                queueSize = diagnosticPositionQueueSize(),
                            )
                            if (decision.result != LiveTrackingLocationQualityResult.ACCEPT) {
                                LiveTrackingDiagnostics.recordLocationQuality(
                                    decision = decision,
                                    gsmSignalPercent = cellularSignalMonitor.currentPercent(),
                                )
                            }
                        }
                }
            if (qualityDecision != null &&
                qualityDecision.result != LiveTrackingLocationQualityResult.ACCEPT
            ) {
                if (qualityDecision.result == LiveTrackingLocationQualityResult.SUSPECT) {
                    LiveTrackingSessionStore.setStatus("Waiting for GPS confirmation")
                    updateNotification("Waiting for GPS confirmation")
                }
                return@withLock
            }
            if (!stop) lastLocation = location
            var attemptedUpdate: ArkluzLocationUpdate? = null
            var sentStartInRequest = false
            var queueSizeBeforeTransmission: Int? = null
            runCatching {
                flushPendingSessionControlsLocked()
                queueSizeBeforeTransmission = diagnosticPositionQueueSize()
                // The last-known location and callback can arrive before the first request completes.
                sentStartInRequest = startRequested && !sentStart
                attemptedUpdate =
                    arkluzClient.buildLocationUpdate(
                        settings = activeSettings,
                        location = location,
                        start = sentStartInRequest,
                        stop = stop,
                        locationDiagnostics = qualityDecision?.toDiagnostics(),
                    )
                arkluzClient.sendLocationUpdate(checkNotNull(attemptedUpdate))
            }.onSuccess { result ->
                if (sentStartInRequest) {
                    sentStart = true
                    result.dateId?.let { dateId = it }
                    persistActiveSession()
                }
                val serverMessage = result.message.takeUnless { it == "Server accepted request" }
                val status =
                    serverMessage ?: when {
                        stop -> "Stop sent"
                        sentStartInRequest -> "Started and position sent"
                        else -> "Position sent"
                    }
                val replayResult =
                    if (stop) {
                        Result.success(0)
                    } else {
                        runCatching { replayStoredGpsPointsLocked() }
                    }
                replayResult
                    .onSuccess { replayedCount ->
                        attemptedUpdate?.let { update ->
                            LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                                isCatchUp = update.isCatchUp,
                                fixTimestampEpochMillis = update.epochMilliseconds,
                                fixAgeMillis = qualityDecision?.fixAgeMillis,
                                gsmSignalPercent = update.gsmSignalPercent,
                                queueSizeBefore = queueSizeBeforeTransmission,
                                queueSizeAfter = diagnosticPositionQueueSize(),
                                outcome = "success",
                            )
                        }
                        val replayStatus =
                            if (replayedCount > 0) {
                                "$status; replayed $replayedCount stored GPS point${if (replayedCount == 1) "" else "s"}"
                            } else {
                                status
                            }
                        LiveTrackingSessionStore.setSent(replayStatus)
                        updateNotification(replayStatus)
                    }.onFailure { error ->
                        attemptedUpdate?.let { update ->
                            LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                                isCatchUp = update.isCatchUp,
                                fixTimestampEpochMillis = update.epochMilliseconds,
                                fixAgeMillis = qualityDecision?.fixAgeMillis,
                                gsmSignalPercent = update.gsmSignalPercent,
                                queueSizeBefore = queueSizeBeforeTransmission,
                                queueSizeAfter = diagnosticPositionQueueSize(),
                                outcome = "success_with_pending_catch_up",
                            )
                        }
                        val message = "Position sent; stored GPS points still waiting (${error.toLiveTrackingErrorText()})"
                        LiveTrackingSessionStore.setError(message)
                        updateNotification("Stored GPS points still waiting")
                    }
            }.onFailure { error ->
                val failedUpdate =
                    attemptedUpdate
                        ?: arkluzClient.buildLocationUpdate(
                            settings = activeSettings,
                            location = location,
                            start = false,
                            stop = stop,
                        )
                val startWasAlreadyPending =
                    LiveTrackingControlQueue.load(this@LiveTrackingService).any { it.start }
                if (failedUpdate.start && !startWasAlreadyPending) {
                    LiveTrackingControlQueue.enqueue(this@LiveTrackingService, failedUpdate)
                    serviceScope.launch { retryPendingStartUntilConfirmed() }
                }
                val controlsPending = LiveTrackingControlQueue.load(this@LiveTrackingService).isNotEmpty()
                if (!stop && error.isRetryableArkluzFailure()) {
                    val queueSize = LiveTrackingPositionQueue.enqueue(this@LiveTrackingService, failedUpdate)
                    LiveTrackingDiagnostics.recordLiveTrackingPositionQueued(queueSize)
                    if (!sentStart && controlsPending) {
                        val message = "Waiting for network to start tracking; GPS stored for retry ($queueSize waiting)"
                        LiveTrackingSessionStore.setStartPending(message)
                        updateNotification("Waiting for network to start tracking")
                    } else if (controlsPending) {
                        LiveTrackingSessionStore.setActive(
                            status = "GPS stored for retry ($queueSize waiting)",
                            serverSyncPending = true,
                        )
                        updateNotification("Tracking active; Arkluz notification pending")
                    } else {
                        val message = "GPS stored for retry ($queueSize waiting): ${error.toLiveTrackingErrorText()}"
                        LiveTrackingSessionStore.setError(message)
                        updateNotification("GPS stored for retry ($queueSize waiting)")
                    }
                } else {
                    LiveTrackingSessionStore.setError(error.message ?: "Unable to send position")
                    updateNotification("Unable to send position")
                }
                attemptedUpdate?.let { update ->
                    LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                        isCatchUp = update.isCatchUp,
                        fixTimestampEpochMillis = update.epochMilliseconds,
                        fixAgeMillis = qualityDecision?.fixAgeMillis,
                        gsmSignalPercent = update.gsmSignalPercent,
                        queueSizeBefore = queueSizeBeforeTransmission,
                        queueSizeAfter = diagnosticPositionQueueSize(),
                        outcome = if (error.isRetryableArkluzFailure()) "network_retry" else "failed",
                    )
                }
            }
        }
    }

    private suspend fun retryPendingStartUntilConfirmed() {
        while (!isStopping && !sentStart && LiveTrackingControlQueue.load(this).any { it.start }) {
            delay(CONTROL_RETRY_DELAY_MS)
            if (isStopping || sentStart) return
            runCatching { flushPendingSessionControls() }
            updateControlSyncStatus()
        }
    }

    private suspend fun replayStoredGpsPointsLocked(): Int {
        var remaining = LiveTrackingPositionQueue.load(this)
        var replayedCount = 0
        for (update in remaining) {
            val updateToSend = update.asCatchUpPoint().withCurrentAlertSettings()
            val queueSizeBefore = remaining.size
            val result =
                runCatching {
                    arkluzClient.sendLocationUpdate(updateToSend)
                }
            result
                .onSuccess {
                    replayedCount += 1
                    remaining = remaining.drop(1)
                    LiveTrackingPositionQueue.replaceAll(this, remaining)
                    LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                        isCatchUp = true,
                        fixTimestampEpochMillis = updateToSend.epochMilliseconds,
                        fixAgeMillis = diagnosticFixAgeMillis(updateToSend.epochMilliseconds),
                        gsmSignalPercent = updateToSend.gsmSignalPercent,
                        queueSizeBefore = queueSizeBefore,
                        queueSizeAfter = remaining.size,
                        outcome = "success",
                    )
                    LiveTrackingDiagnostics.recordLiveTrackingCatchUpReplay(remaining.size)
                }.onFailure { error ->
                    if (error.isRetryableArkluzFailure()) {
                        LiveTrackingPositionQueue.replaceAll(this, remaining)
                        LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                            isCatchUp = true,
                            fixTimestampEpochMillis = updateToSend.epochMilliseconds,
                            fixAgeMillis = diagnosticFixAgeMillis(updateToSend.epochMilliseconds),
                            gsmSignalPercent = updateToSend.gsmSignalPercent,
                            queueSizeBefore = queueSizeBefore,
                            queueSizeAfter = remaining.size,
                            outcome = "network_retry",
                        )
                        throw error
                    }
                    remaining = remaining.drop(1)
                    LiveTrackingPositionQueue.replaceAll(this, remaining)
                    LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                        isCatchUp = true,
                        fixTimestampEpochMillis = updateToSend.epochMilliseconds,
                        fixAgeMillis = diagnosticFixAgeMillis(updateToSend.epochMilliseconds),
                        gsmSignalPercent = updateToSend.gsmSignalPercent,
                        queueSizeBefore = queueSizeBefore,
                        queueSizeAfter = remaining.size,
                        outcome = "failed",
                    )
                    LiveTrackingSessionStore.setError(error.message ?: "Stored GPS point could not be replayed")
                }
        }
        return replayedCount
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        runCatching { locationClient.removeLocationUpdates(locationCallback) }
        isPaused = false
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMs())
                .setMinUpdateIntervalMillis(updateIntervalMs())
                .setMaxUpdateDelayMillis(updateIntervalMs())
                .build()
        locationClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun pauseTracking() {
        if (isPaused || isStopping || !sentStart) return
        serviceScope.launch {
            var pauseStarted = false
            sendMutex.withLock {
                if (isPaused || isStopping || !sentStart) return@withLock
                val activeSettings = settings ?: return@withLock
                val location =
                    lastLocation ?: run {
                        LiveTrackingSessionStore.setError("Wait for the first GPS position before pausing")
                        return@withLock
                    }
                runCatching { locationClient.removeLocationUpdates(locationCallback) }
                isPaused = true
                persistActiveSession()
                LiveTrackingControlQueue.enqueue(
                    this@LiveTrackingService,
                    buildSessionControl(
                        settings = activeSettings,
                        location = location,
                        pause = true,
                    ),
                )
                pauseStarted = true
            }
            if (!pauseStarted) return@launch
            cellularSignalMonitor.stop()
            LiveTrackingSessionStore.setPaused(serverSyncPending = true)
            updateNotification("Pause pending; a no-movement alert may still be sent")
            runCatching { flushPendingSessionControls() }
            updateControlSyncStatus()
            retryPendingControlsWhilePaused()
        }
    }

    private fun resumeTracking() {
        val activeSettings = settings ?: return
        val location = lastLocation ?: return
        if (!isPaused || isStopping) return
        if (!hasLocationPermission()) {
            finishStopped("Location permission is required")
            return
        }
        cellularSignalMonitor.start()
        isPaused = false
        persistActiveSession()
        LiveTrackingControlQueue.enqueue(
            this,
            buildSessionControl(
                settings = activeSettings,
                location = location,
                resume = true,
            ),
        )
        LiveTrackingSessionStore.setActive(
            status = "Waiting for GPS fix",
            serverSyncPending = true,
        )
        updateNotification("Tracking resumed; notifying Arkluz")
        startLocationUpdates()
        serviceScope.launch {
            runCatching { flushPendingSessionControls() }
            updateControlSyncStatus()
        }
    }

    private fun buildSessionControl(
        settings: LiveTrackingSettings,
        location: Location,
        pause: Boolean = false,
        resume: Boolean = false,
    ): ArkluzLocationUpdate =
        arkluzClient.buildLocationUpdate(
            settings = settings,
            location = location,
            start = false,
            stop = false,
            pause = pause,
            resume = resume,
            dateId = dateId,
        )

    private suspend fun flushPendingSessionControls() {
        sendMutex.withLock {
            flushPendingSessionControlsLocked()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun flushPendingSessionControlsLocked(): Int {
        var sentCount = 0
        while (true) {
            val update = LiveTrackingControlQueue.load(this).firstOrNull() ?: break
            val updateToSend = update.withCurrentAlertSettings()
            val queueSizeBefore = diagnosticPositionQueueSize()
            val result =
                try {
                    arkluzClient.sendLocationUpdate(updateToSend)
                } catch (error: Throwable) {
                    LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                        isCatchUp = false,
                        fixTimestampEpochMillis = updateToSend.epochMilliseconds,
                        fixAgeMillis = null,
                        gsmSignalPercent = updateToSend.gsmSignalPercent,
                        queueSizeBefore = queueSizeBefore,
                        queueSizeAfter = diagnosticPositionQueueSize(),
                        outcome = if (error.isRetryableArkluzFailure()) "network_retry" else "failed",
                    )
                    throw error
                }
            LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                isCatchUp = false,
                fixTimestampEpochMillis = updateToSend.epochMilliseconds,
                fixAgeMillis = null,
                gsmSignalPercent = updateToSend.gsmSignalPercent,
                queueSizeBefore = queueSizeBefore,
                queueSizeAfter = diagnosticPositionQueueSize(),
                outcome = "success",
            )
            if (update.start) {
                sentStart = true
                result.dateId?.let { dateId = it }
            }
            LiveTrackingControlQueue.removeFirst(this)
            sentCount += 1
        }
        return sentCount
    }

    private fun ArkluzLocationUpdate.withCurrentAlertSettings(): ArkluzLocationUpdate {
        val activeSettings = settings ?: return this
        return copy(
            notificationEmails = activeSettings.notificationEmails,
            alertEmails = activeSettings.alertEmails,
            stuckAlarmMinutes = activeSettings.stuckAlarmMinutes,
        )
    }

    private suspend fun retryPendingControlsWhilePaused() {
        while (isPaused && !isStopping && LiveTrackingControlQueue.load(this).isNotEmpty()) {
            delay(CONTROL_RETRY_DELAY_MS)
            if (!isPaused || isStopping) return
            runCatching { flushPendingSessionControls() }
            updateControlSyncStatus()
        }
    }

    private fun updateControlSyncStatus() {
        val pendingControls = LiveTrackingControlQueue.load(this)
        val serverSyncPending = pendingControls.isNotEmpty()
        if (isPaused) {
            LiveTrackingSessionStore.setPaused(serverSyncPending)
            updateNotification(
                if (serverSyncPending) {
                    "Pause pending; a no-movement alert may still be sent"
                } else {
                    "Live tracking paused"
                },
            )
        } else if (pendingControls.any { it.start }) {
            LiveTrackingSessionStore.setStartPending()
            updateNotification("Waiting for network to start tracking")
        } else {
            LiveTrackingSessionStore.setActive(
                status = "Tracking active",
                serverSyncPending = serverSyncPending,
            )
            updateNotification(
                if (serverSyncPending) {
                    "Tracking active; Arkluz notification pending"
                } else {
                    "Live tracking active"
                },
            )
        }
    }

    private fun updateIntervalMs(): Long =
        (settings?.updateIntervalSeconds ?: DEFAULT_UPDATE_INTERVAL_SECONDS)
            .coerceIn(MIN_UPDATE_INTERVAL_SECONDS, MAX_UPDATE_INTERVAL_SECONDS)
            .toLong() * 1000L

    private fun stopTracking() {
        if (isStopping) return
        runCatching { locationClient.removeLocationUpdates(locationCallback) }
        isPaused = false
        isStopping = true
        serviceScope.launch {
            val location = sendMutex.withLock { lastLocation }
            if (location == null) {
                finishStopped(
                    status = "Stopped",
                    clearPlannedDraft = true,
                )
                return@launch
            }
            retryStopUntilConfirmed(location)
        }
    }

    private suspend fun retryStopUntilConfirmed(location: Location) {
        var attempt = 1
        while (true) {
            LiveTrackingSessionStore.setStopping("Live tracking stopped, waiting for server confirmation")
            updateNotification("Live tracking stopped, waiting for server confirmation")
            val result = runCatching { sendStopConfirmation(location) }
            result
                .onSuccess {
                    finishStopped(
                        status = "Stopped",
                        clearPlannedDraft = true,
                    )
                    return
                }.onFailure { error ->
                    if (!error.isRetryableArkluzFailure()) {
                        LiveTrackingSessionStore.setStoppedWithError(
                            status = "Stopped, confirmation failed",
                            message = error.message ?: "Stop confirmation failed",
                        )
                        updateNotification("Stop confirmation failed")
                        LiveTrackingDiagnostics.finishLiveTrackingSession()
                        ServiceCompat.stopForeground(this@LiveTrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return
                    }
                    val retryMessage = "Live tracking stopped, waiting for server confirmation (retry $attempt)"
                    LiveTrackingSessionStore.setStopping(retryMessage)
                    updateNotification(retryMessage)
                    attempt += 1
                    delay(STOP_RETRY_DELAY_MS)
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendStopConfirmation(location: Location) {
        sendMutex.withLock {
            val activeSettings = settings ?: return@withLock
            val update =
                arkluzClient.buildLocationUpdate(
                    settings = activeSettings,
                    location = location,
                    start = false,
                    stop = true,
                )
            flushPendingSessionControlsLocked()
            val queueSizeBefore = diagnosticPositionQueueSize()
            try {
                val result = arkluzClient.sendLocationUpdate(update)
                LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                    isCatchUp = false,
                    fixTimestampEpochMillis = update.epochMilliseconds,
                    fixAgeMillis = diagnosticFixAgeMillis(update.epochMilliseconds),
                    gsmSignalPercent = update.gsmSignalPercent,
                    queueSizeBefore = queueSizeBefore,
                    queueSizeAfter = diagnosticPositionQueueSize(),
                    outcome = "success",
                )
                result
            } catch (error: Throwable) {
                LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                    isCatchUp = false,
                    fixTimestampEpochMillis = update.epochMilliseconds,
                    fixAgeMillis = diagnosticFixAgeMillis(update.epochMilliseconds),
                    gsmSignalPercent = update.gsmSignalPercent,
                    queueSizeBefore = queueSizeBefore,
                    queueSizeAfter = diagnosticPositionQueueSize(),
                    outcome = if (error.isRetryableArkluzFailure()) "network_retry" else "failed",
                )
                throw error
            }
        }
    }

    private fun finishStopped(
        status: String,
        clearPlannedDraft: Boolean = false,
    ) {
        cellularSignalMonitor.stop()
        LiveTrackingDiagnostics.finishLiveTrackingSession()
        LiveTrackingControlQueue.clear(this)
        LiveTrackingActiveSessionStore.clear(this)
        if (clearPlannedDraft) {
            LiveTrackingPreferences.clearDraft(this)
        }
        LiveTrackingSessionStore.setStopped(status)
        ServiceCompat.stopForeground(this@LiveTrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification(text: String) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text).build(),
            type,
        )
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text).build())
    }

    private fun buildNotification(text: String): NotificationCompat.Builder {
        val openIntent =
            PendingIntent.getActivity(
                this,
                REQ_OPEN_APP,
                Intent(this, MainActivityMobile::class.java)
                    .setAction(LiveTrackingOpenIntentContract.ACTION_OPEN_LIVE_TRACKING)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                REQ_STOP,
                Intent(this, LiveTrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pauseResumeIntent =
            PendingIntent.getService(
                this,
                if (isPaused) REQ_RESUME else REQ_PAUSE,
                Intent(this, LiveTrackingService::class.java)
                    .setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pauseResumeLabel = if (isPaused) "Resume" else "Pause"
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                .setContentTitle(if (isStopping) "Live tracking stopped" else "Live tracking running")
                .setContentText(text)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent)
        if (!isStopping) {
            builder
                .addAction(0, pauseResumeLabel, pauseResumeIntent)
                .addAction(0, "Stop live tracking", stopIntent)
        }
        return builder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live Tracking", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun diagnosticPositionQueueSize(): Int? =
        if (PhoneDebugCapture.isActive()) {
            LiveTrackingPositionQueue.load(this).size
        } else {
            null
        }

    private fun diagnosticFixAgeMillis(epochMilliseconds: Long): Long? {
        if (!PhoneDebugCapture.isActive() || epochMilliseconds <= 0L) return null
        return (System.currentTimeMillis() - epochMilliseconds).takeIf { it >= 0L }
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun persistActiveSession() {
        val activeSettings = settings ?: return
        LiveTrackingActiveSessionStore.save(
            context = this,
            settings = activeSettings,
            isPaused = isPaused,
            sentStart = sentStart,
            dateId = dateId,
        )
    }

    companion object {
        private const val CHANNEL_ID = "live_tracking_channel"
        private const val NOTIFICATION_ID = 42
        private const val REQ_OPEN_APP = 4201
        private const val REQ_STOP = 4202
        private const val REQ_PAUSE = 4203
        private const val REQ_RESUME = 4204
        private const val DEFAULT_UPDATE_INTERVAL_SECONDS = 60
        private const val MIN_UPDATE_INTERVAL_SECONDS = 15
        private const val MAX_UPDATE_INTERVAL_SECONDS = 600
        private const val STOP_RETRY_DELAY_MS = 30_000L
        private const val CONTROL_RETRY_DELAY_MS = 30_000L
        private const val ACTION_STOP = "com.glancemap.glancemapcompanionapp.livetracking.STOP"
        private const val ACTION_PAUSE = "com.glancemap.glancemapcompanionapp.livetracking.PAUSE"
        private const val ACTION_RESUME = "com.glancemap.glancemapcompanionapp.livetracking.RESUME"
        private const val ACTION_UPDATE_ALERT_SETTINGS =
            "com.glancemap.glancemapcompanionapp.livetracking.UPDATE_ALERT_SETTINGS"

        private const val EXTRA_GROUP = "group"
        private const val EXTRA_TRACKING_URL = "tracking_url"
        private const val EXTRA_UPDATE_INTERVAL_SECONDS = "update_interval_seconds"
        private const val EXTRA_PARTICIPANT_PASSWORD = "participant_password"
        private const val EXTRA_FOLLOWER_PASSWORD = "follower_password"
        private const val EXTRA_USER_NAME = "user_name"
        private const val EXTRA_NOTIFICATION_EMAILS = "notification_emails"
        private const val EXTRA_ALERT_EMAILS = "alert_emails"
        private const val EXTRA_STUCK_ALARM_MINUTES = "stuck_alarm_minutes"
        private const val EXTRA_COMMENTS = "comments"
        private const val EXTRA_GPX_URI = "gpx_uri"
        private const val EXTRA_GPX_NAME = "gpx_name"

        fun start(
            context: Context,
            settings: LiveTrackingSettings,
        ) {
            // A user-initiated start supersedes any session that Android was eligible to restore.
            LiveTrackingActiveSessionStore.clear(context)
            val intent =
                Intent(context, LiveTrackingService::class.java)
                    .putExtra(EXTRA_TRACKING_URL, settings.trackingUrl)
                    .putExtra(EXTRA_UPDATE_INTERVAL_SECONDS, settings.updateIntervalSeconds)
                    .putExtra(EXTRA_GROUP, settings.group)
                    .putExtra(EXTRA_PARTICIPANT_PASSWORD, settings.participantPassword)
                    .putExtra(EXTRA_FOLLOWER_PASSWORD, settings.followerPassword)
                    .putExtra(EXTRA_USER_NAME, settings.userName)
                    .putExtra(EXTRA_NOTIFICATION_EMAILS, settings.notificationEmails)
                    .putExtra(EXTRA_ALERT_EMAILS, settings.alertEmails)
                    .putExtra(EXTRA_STUCK_ALARM_MINUTES, settings.stuckAlarmMinutes)
                    .putExtra(EXTRA_COMMENTS, settings.comments)
                    .putExtra(EXTRA_GPX_URI, settings.gpxUri?.toString())
                    .putExtra(EXTRA_GPX_NAME, settings.gpxName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveTrackingService::class.java).setAction(ACTION_STOP),
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, LiveTrackingService::class.java).setAction(ACTION_PAUSE),
            )
        }

        fun resume(context: Context) {
            context.startService(
                Intent(context, LiveTrackingService::class.java).setAction(ACTION_RESUME),
            )
        }

        fun updateAlertSettings(
            context: Context,
            notificationEmails: String,
            alertEmails: String,
            stuckAlarmMinutes: String,
        ) {
            context.startService(
                Intent(context, LiveTrackingService::class.java)
                    .setAction(ACTION_UPDATE_ALERT_SETTINGS)
                    .putExtra(EXTRA_NOTIFICATION_EMAILS, notificationEmails)
                    .putExtra(EXTRA_ALERT_EMAILS, alertEmails)
                    .putExtra(EXTRA_STUCK_ALARM_MINUTES, stuckAlarmMinutes),
            )
        }

        private fun Intent.toLiveTrackingSettings(): LiveTrackingSettings? {
            val group = getStringExtra(EXTRA_GROUP).orEmpty()
            val pass = getStringExtra(EXTRA_PARTICIPANT_PASSWORD).orEmpty()
            val user = getStringExtra(EXTRA_USER_NAME).orEmpty()
            if (group.isBlank() || pass.isBlank() || user.isBlank()) return null
            val gpxUri = getStringExtra(EXTRA_GPX_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            return LiveTrackingSettings(
                trackingUrl =
                    getStringExtra(EXTRA_TRACKING_URL)
                        .orEmpty()
                        .ifBlank { ArkluzTrackingEndpoint.defaultUrl },
                updateIntervalSeconds =
                    getIntExtra(
                        EXTRA_UPDATE_INTERVAL_SECONDS,
                        DEFAULT_UPDATE_INTERVAL_SECONDS,
                    ).coerceIn(MIN_UPDATE_INTERVAL_SECONDS, MAX_UPDATE_INTERVAL_SECONDS),
                group = group,
                participantPassword = pass,
                followerPassword = getStringExtra(EXTRA_FOLLOWER_PASSWORD).orEmpty(),
                userName = user,
                notificationEmails = getStringExtra(EXTRA_NOTIFICATION_EMAILS).orEmpty(),
                alertEmails = getStringExtra(EXTRA_ALERT_EMAILS).orEmpty(),
                stuckAlarmMinutes = getStringExtra(EXTRA_STUCK_ALARM_MINUTES).orEmpty(),
                comments = getStringExtra(EXTRA_COMMENTS).orEmpty(),
                gpxUri = gpxUri,
                gpxName = getStringExtra(EXTRA_GPX_NAME).orEmpty(),
            )
        }
    }
}

private fun Throwable.toLiveTrackingErrorText(): String = toArkluzFailureDetail()

@Suppress("NewApi")
private fun Location.isMockLocationForDiagnostics(): Boolean? =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> isMock
        else -> null
    }
