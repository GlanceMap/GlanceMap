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
import android.os.Handler
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
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun canContinueLiveTrackingSend(
    isPaused: Boolean,
    isStopping: Boolean,
    stop: Boolean,
    isPauseRequested: Boolean = false,
): Boolean = stop || (!isPaused && !isStopping && !isPauseRequested)

internal fun liveTrackingRefinementLocationRequest(): LocationRequest =
    LocationRequest
        .Builder(Priority.PRIORITY_HIGH_ACCURACY, BURST_UPDATE_INTERVAL_MS)
        .setMinUpdateIntervalMillis(BURST_UPDATE_INTERVAL_MS)
        .setMaxUpdateDelayMillis(0L)
        .setMaxUpdateAgeMillis(BURST_MAX_UPDATE_AGE_MS)
        .setMaxUpdates(MAX_ACCURACY_EXTRA_FIXES)
        .setDurationMillis(ACQUISITION_WINDOW_MS)
        .build()

// The service intentionally owns the complete foreground-session orchestration and its retries.
@Suppress("LargeClass")
class LiveTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var arkluzClient: ArkluzLiveTrackingClient
    private lateinit var cellularSignalMonitor: CellularSignalMonitor
    private val locationQualityGate = LiveTrackingLocationQualityGate()
    private var settings: LiveTrackingSettings? = null
    private val scheduledCycleMutex = Mutex()

    private val orchestration = LiveTrackingOrchestration<ActiveAcquisition>()
    private val diagnosticServiceId = SystemClock.elapsedRealtimeNanos()
    private var periodicDiagnosticContext: LiveTrackingDiagnosticContext? = null

    @Volatile
    private var lastLocation: Location? = null

    private var locationCallback: LocationCallback? = null
    private var sentStart = false
    private var dateId: String? = null

    @Volatile
    private var isPaused = false

    @Volatile
    private var isStopping = false

    @Volatile
    private var pauseRequested = false
    private var lastProcessedFix: LiveTrackingLocationFix? = null
    private var nextAcquisitionCycleId = 0L

    private val sendMutex = Mutex()

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
        isStopping = true
        pauseRequested = false
        stopLocationUpdates("destroy")
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
                pauseRequested = false
                orchestration.resetRescueCooldown()
                lastProcessedFix = null
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
                startLocationUpdates()?.let { generation ->
                    sendLastKnownLocationIfAvailable(generation)
                }
            }.onFailure { error ->
                LiveTrackingSessionStore.setError(error.message ?: "Live tracking start failed")
                updateNotification("Live tracking error")
            }
        }
    }

    // A stale cache must return immediately so the active high-accuracy request can provide the fix.
    @Suppress("ReturnCount", "LongMethod")
    @SuppressLint("MissingPermission")
    private suspend fun sendLastKnownLocationIfAvailable(generation: LiveTrackingGeneration) {
        if (!hasLocationPermission()) return
        val location = runCatching { locationClient.lastLocation.await() }.getOrNull()
        if (location == null) {
            LiveTrackingDiagnostics.recordLiveTrackingStartup(
                cachedLocationExists = false,
                cachedLocationAgeMillis = null,
                cachedLocationAccepted = null,
                context = trackingContext(generation),
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
            context = trackingContext(generation),
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
                context = trackingContext(generation),
                fixTimestampEpochMillis = fix.epochMilliseconds,
            )
            LiveTrackingDiagnostics.recordLocationQuality(
                decision = decision,
                gsmSignalPercent = cellularSignalMonitor.currentPercent(),
            )
            return
        }
        sendLocations(
            locations = listOf(location),
            source = LiveTrackingFixSource.CACHED_STARTUP,
            generation = generation,
            cadenceTicket = generation.admission.initialTicket(),
        )
    }

    private data class PreparedScheduledCycle(
        val id: Long,
        val intervalMillis: Long,
        val initialDecision: LiveTrackingLocationQualityDecision?,
        val initialCandidates: List<LiveTrackingCandidate<Location>>,
        val acquisition: ActiveAcquisition?,
        val confirmationBudget: LiveTrackingCycleConfirmationBudget,
        val allObservationsRejected: Boolean,
    )

    private data class TransmissionResult(
        val outcome: String,
        val selection: LiveTrackingCandidateSelection<Location>?,
    )

    private data class AcquisitionRequest(
        val generation: LiveTrackingGeneration,
        val cycleId: Long,
        val intervalMillis: Long,
        val reason: LiveTrackingAcquisitionReason,
        val initialFix: LiveTrackingLocationFix,
        val initialDecision: LiveTrackingLocationQualityDecision,
        val initialCandidates: List<LiveTrackingCandidate<Location>>,
        val trigger: String?,
        val confirmationBudget: LiveTrackingCycleConfirmationBudget,
        val rescueCooldownPending: Boolean,
    )

    private data class ActiveAcquisition(
        override val generation: LiveTrackingGeneration,
        val cycle: LiveTrackingAcquisitionCycle<Location>,
        val initialFix: LiveTrackingLocationFix,
        val initialDecision: LiveTrackingLocationQualityDecision,
        var trigger: String?,
        val startedElapsedRealtimeMillis: Long,
        val callback: LocationCallback,
        override val ownership: LiveTrackingAcquisitionOwnership = LiveTrackingAcquisitionOwnership(),
        val completion: CompletableDeferred<String> = CompletableDeferred(),
        val registrationResult: CompletableDeferred<Boolean> = CompletableDeferred(),
        @Volatile var cleanupTimedOut: Boolean = false,
        @Volatile var cleanupFailed: Boolean = false,
        var rescueWasRequested: Boolean = false,
        var rescueSkippedCooldown: Boolean = false,
        var outcome: String? = null,
        var cancellation: String? = null,
        var timedOut: Boolean = false,
        var registrationFailed: Boolean = false,
        var durationMillis: Long = 0L,
        val confirmationBudget: LiveTrackingCycleConfirmationBudget,
        var rescueCooldownPending: Boolean = false,
    ) : GenerationOwnedLiveTrackingAcquisition

    private fun trackingContext(
        generation: LiveTrackingGeneration,
        cycleId: Long? = null,
        cadenceWindow: Long? = null,
    ): LiveTrackingDiagnosticContext =
        LiveTrackingDiagnosticContext(
            diagnosticServiceId,
            generation.id,
            cycleId,
            cadenceWindow,
        )

    private fun ActiveAcquisition.trackingContext(): LiveTrackingDiagnosticContext =
        trackingContext(
            generation,
            cycle.id,
        )

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun sendLocations(
        locations: List<Location>,
        source: LiveTrackingFixSource,
        generation: LiveTrackingGeneration,
        cadenceTicket: LiveTrackingCadenceTicket,
    ) {
        val context = trackingContext(generation, cadenceWindow = cadenceTicket.windowIndex)
        if (!orchestration.isCurrent(generation)) {
            recordLiveTrackingEvent(context, "late_normal_callback_ignored")
            return
        }
        scheduledCycleMutex.withLock {
            if (!orchestration.reserveAdmission(generation, cadenceTicket)) {
                recordLiveTrackingEvent(context, "cadence_admission_denied")
                return
            }
            recordLiveTrackingEvent(context, "cadence_admission_reserved")
            val prepared =
                sendMutex.withLock {
                    orchestration.withGeneration(generation) {
                        prepareScheduledCycleLocked(locations, source, generation)
                    }
                } ?: return
            val cycleContext = context.copy(cycleId = prepared.id)
            recordLiveTrackingEvent(cycleContext, "cycle_prepared")
            if (prepared.allObservationsRejected && prepared.acquisition == null) {
                if (orchestration.releaseRejectedStartup(generation, cadenceTicket)) {
                    recordLiveTrackingEvent(cycleContext, "startup_admission_released")
                }
            }
            prepared.acquisition?.let { awaitAcquisition(it) }

            var selection: LiveTrackingCandidateSelection<Location>? = null
            var selectedTransmission = TransmissionResult("no_candidate", null)
            val sendOutcome =
                sendMutex.withLock {
                    if (!orchestration.isCurrent(generation)) {
                        "stale_request_generation"
                    } else if (
                        !canContinueLiveTrackingSend(
                            isPaused,
                            isStopping,
                            stop = false,
                            isPauseRequested = pauseRequested,
                        )
                    ) {
                        "cancelled_lifecycle"
                    } else if (generation.admission.isSuperseded(cadenceTicket)) {
                        "superseded_cadence"
                    } else {
                        val candidates =
                            prepared.acquisition?.cycle?.eligibleCandidates()
                                ?: prepared.initialCandidates
                        selection = selectLiveTrackingCandidate(candidates)
                        val activeSettings = settings
                        if (selection == null || activeSettings == null) {
                            "no_candidate"
                        } else if (
                            !canContinueLiveTrackingSend(
                                isPaused,
                                isStopping,
                                stop = false,
                                isPauseRequested = pauseRequested,
                            )
                        ) {
                            "cancelled_lifecycle"
                        } else {
                            selectedTransmission =
                                transmitSelectedLocationLocked(
                                    settings = activeSettings,
                                    candidates = candidates,
                                    generation = generation,
                                    context = cycleContext,
                                )
                            selection = selectedTransmission.selection
                            selectedTransmission.outcome
                        }
                    }
                }

            val acquisition = prepared.acquisition
            val selectedCandidate = selection?.candidate
            recordLiveTrackingAcquisition(
                LiveTrackingAcquisitionDiagnostic(
                    cycleId = prepared.id,
                    intervalMillis = acquisition?.cycle?.intervalMillis ?: prepared.intervalMillis,
                    reason = acquisition?.cycle?.reason ?: LiveTrackingAcquisitionReason.NONE,
                    initialAccuracyMeters = prepared.initialDecision?.accuracyMeters,
                    initialQualityResult = prepared.initialDecision?.result,
                    extraFixesDelivered = acquisition?.cycle?.extraFixesDelivered ?: 0,
                    extraFixesAccepted = acquisition?.cycle?.extraFixesAccepted ?: 0,
                    extraFixesRejected = acquisition?.cycle?.extraFixesRejected ?: 0,
                    extraFixesSuspect = acquisition?.cycle?.extraFixesSuspect ?: 0,
                    durationMillis = acquisition?.durationMillis ?: 0L,
                    selectedCandidateAccuracyMeters = selectedCandidate?.decision?.accuracyMeters,
                    selectedCandidateAgeMillis =
                        selectedCandidate?.fix?.let {
                            liveTrackingLocationAgeMillis(
                                it,
                                SystemClock.elapsedRealtimeNanos(),
                                System.currentTimeMillis(),
                            )
                        },
                    selectionReason = selection?.selectionReason ?: "none",
                    usedScheduledFallback = selectedCandidate?.isScheduledFallback == true,
                    earlyTargetReached = acquisition?.cycle?.earlyTargetReached == true,
                    timedOut = acquisition?.timedOut == true,
                    registrationFailed = acquisition?.registrationFailed == true,
                    cancellation = acquisition?.cancellation,
                    staleFixesIgnored = acquisition?.cycle?.staleFixesIgnored ?: 0,
                    duplicateFixesIgnored = acquisition?.cycle?.duplicateFixesIgnored ?: 0,
                    outcome = acquisitionOutcome(acquisition, sendOutcome, selectedCandidate),
                    cleanupTimedOut = acquisition?.cleanupTimedOut == true,
                    cleanupFailed = acquisition?.cleanupFailed == true,
                    context = cycleContext,
                ),
            )
            acquisition
                ?.takeIf { it.cycle.reason == LiveTrackingAcquisitionReason.SUSPECT_CONFIRMATION }
                ?.let { recordRescueOutcome(it, selectedCandidate) }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun prepareScheduledCycleLocked(
        locations: List<Location>,
        source: LiveTrackingFixSource,
        generation: LiveTrackingGeneration,
    ): PreparedScheduledCycle? {
        if (!canContinueLiveTrackingSend(isPaused, isStopping, stop = false, isPauseRequested = pauseRequested)) {
            return null
        }
        if (settings == null || locations.isEmpty()) return null

        val cycleId = ++nextAcquisitionCycleId
        val context = trackingContext(generation, cycleId)
        val intervalMillis = updateIntervalMs()
        var lastLocation: Location? = null
        var lastFix: LiveTrackingLocationFix? = null
        var initialDecision: LiveTrackingLocationQualityDecision? = null
        val candidatePool = LiveTrackingScheduledCandidatePool<Location>()
        val confirmationBudget = LiveTrackingCycleConfirmationBudget()
        var allObservationsRejected = true

        val orderedLocations =
            sortLiveTrackingFixesChronologically(
                locations.map { it to it.toLiveTrackingLocationFix() },
            )
        for ((location, fix) in orderedLocations) {
            val comparison = lastProcessedFix?.let { compareLiveTrackingFixTime(fix, it) }
            if (comparison?.let { it <= 0 } == true) {
                recordLiveTrackingEvent(
                    context,
                    if (comparison == 0) "duplicate_ignored" else "stale_ignored",
                )
                continue
            }
            val allowPendingAreaConfirmation = confirmationBudget.allowsPendingAreaConfirmation()
            val decision =
                locationQualityGate.evaluate(
                    fix = fix,
                    nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    nowEpochMilliseconds = System.currentTimeMillis(),
                    startupStaleReason =
                        if (source == LiveTrackingFixSource.CACHED_STARTUP) {
                            "stale_startup_cache"
                        } else {
                            "stale_startup_callback"
                        },
                    allowPendingAreaConfirmation = allowPendingAreaConfirmation,
                )
            confirmationBudget.record(decision)
            // Rejected startup observations must not exclude an older, valid startup cache either.
            if (decision.result != LiveTrackingLocationQualityResult.REJECT || locationQualityGate.hasFreshInitialFix) {
                lastProcessedFix = fix
            }
            if (decision.result != LiveTrackingLocationQualityResult.REJECT) allObservationsRejected = false
            lastLocation = location
            lastFix = fix
            initialDecision = decision
            candidatePool.record(
                decision,
                if (decision.result == LiveTrackingLocationQualityResult.ACCEPT) {
                    LiveTrackingCandidate(location, fix, decision)
                } else {
                    null
                },
            )
            LiveTrackingDiagnostics.recordLiveTrackingFix(
                source = source,
                decision = decision,
                androidSpeedMetersPerSecond = fix.speedMetersPerSecond,
                isMockLocation = location.isMockLocationForDiagnostics(),
                gsmSignalPercent = cellularSignalMonitor.currentPercent(),
                queueSize = diagnosticPositionQueueSize(),
                context = context,
                fixTimestampEpochMillis = fix.epochMilliseconds,
            )
            if (decision.result != LiveTrackingLocationQualityResult.ACCEPT) {
                LiveTrackingDiagnostics.recordLocationQuality(
                    decision = decision,
                    gsmSignalPercent = cellularSignalMonitor.currentPercent(),
                )
            }
        }

        val decision =
            initialDecision ?: return PreparedScheduledCycle(
                id = cycleId,
                intervalMillis = intervalMillis,
                initialDecision = null,
                initialCandidates = candidatePool.acceptedCandidates(),
                acquisition = null,
                confirmationBudget = confirmationBudget,
                allObservationsRejected = allObservationsRejected,
            )
        val location =
            lastLocation
                ?: return PreparedScheduledCycle(
                    cycleId,
                    intervalMillis,
                    decision,
                    candidatePool.acceptedCandidates(),
                    null,
                    confirmationBudget,
                    allObservationsRejected,
                )
        val fix = checkNotNull(lastFix)
        val scheduledFallbacks = candidatePool.scheduledFallbacks()
        var acquisition: ActiveAcquisition? = null
        when (decision.result) {
            LiveTrackingLocationQualityResult.ACCEPT -> {
                val eligible =
                    shouldRefineLiveTrackingAccuracy(
                        LiveTrackingAccuracyRefinementContext(
                            source = source,
                            result = decision.result,
                            intervalMillis = intervalMillis,
                            accuracyMeters = decision.accuracyMeters,
                            hasFineLocationPermission = hasFineLocationPermission(),
                            sessionActive =
                                settings != null &&
                                    !isPaused &&
                                    !isStopping &&
                                    !pauseRequested,
                            acquisitionRunning = orchestration.hasActiveAcquisition(),
                        ),
                    )
                if (eligible) {
                    acquisition =
                        createActiveAcquisition(
                            AcquisitionRequest(
                                generation = generation,
                                cycleId = cycleId,
                                intervalMillis = intervalMillis,
                                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                                initialFix = fix,
                                initialDecision = decision,
                                initialCandidates = scheduledFallbacks,
                                trigger = null,
                                confirmationBudget = confirmationBudget,
                                rescueCooldownPending = false,
                            ),
                        ).takeIf { orchestration.publishAcquisition(it) }
                }
            }
            LiveTrackingLocationQualityResult.SUSPECT -> {
                if (source == LiveTrackingFixSource.CALLBACK) {
                    LiveTrackingSessionStore.setStatus("Waiting for GPS confirmation")
                    updateNotification("Waiting for GPS confirmation")
                }
                val cooldownRemaining =
                    orchestration.rescueCooldownRemainingMillis(
                        nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        cooldownMillis = RESCUE_COOLDOWN_MILLIS,
                    )
                val canRescue =
                    source == LiveTrackingFixSource.CALLBACK && cooldownRemaining == null && hasLocationPermission()
                if (canRescue) {
                    acquisition =
                        createActiveAcquisition(
                            AcquisitionRequest(
                                generation = generation,
                                cycleId = cycleId,
                                intervalMillis = intervalMillis,
                                reason = LiveTrackingAcquisitionReason.SUSPECT_CONFIRMATION,
                                initialFix = fix,
                                initialDecision = decision,
                                initialCandidates = scheduledFallbacks,
                                trigger = decision.reason,
                                confirmationBudget = confirmationBudget,
                                rescueCooldownPending = true,
                            ),
                        ).takeIf { orchestration.publishAcquisition(it) }
                } else {
                    recordLiveTrackingRescue(
                        LiveTrackingRescueDiagnostic(
                            requested = false,
                            trigger = decision.reason,
                            context = context,
                            cooldownRemainingMillis = cooldownRemaining,
                            skippedBecauseCooldown =
                                source == LiveTrackingFixSource.CALLBACK && cooldownRemaining != null,
                            outcome =
                                if (source == LiveTrackingFixSource.CALLBACK) {
                                    "unavailable"
                                } else {
                                    "not_periodic_callback"
                                },
                        ),
                    )
                }
            }
            LiveTrackingLocationQualityResult.REJECT -> Unit
        }

        acquisition?.let {
            recordLiveTrackingEvent(it.trackingContext(), "acquisition_published_${it.cycle.reason.name}")
        }
        return PreparedScheduledCycle(
            id = cycleId,
            intervalMillis = intervalMillis,
            initialDecision = decision,
            initialCandidates = candidatePool.acceptedCandidates(),
            acquisition = acquisition,
            confirmationBudget = confirmationBudget,
            allObservationsRejected = allObservationsRejected,
        )
    }

    private fun createActiveAcquisition(request: AcquisitionRequest): ActiveAcquisition {
        val cycle =
            LiveTrackingAcquisitionCycle(
                request.cycleId,
                request.reason,
                request.intervalMillis,
                request.initialCandidates,
            )
        lateinit var acquisition: ActiveAcquisition
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val locations = result.locations.toList()
                    if (locations.isEmpty()) return
                    serviceScope.launch { handleAcquisitionResult(acquisition, locations) }
                }
            }
        acquisition =
            ActiveAcquisition(
                generation = request.generation,
                cycle = cycle,
                initialFix = request.initialFix,
                initialDecision = request.initialDecision,
                trigger = request.trigger,
                startedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                callback = callback,
                confirmationBudget = request.confirmationBudget,
                rescueCooldownPending = request.rescueCooldownPending,
            )
        return acquisition
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private suspend fun handleAcquisitionResult(
        acquisition: ActiveAcquisition,
        locations: List<Location>,
    ) {
        var completionReason: String? = null
        sendMutex.withLock {
            val handled =
                orchestration.withAcquisition(acquisition) {
                    val ordered =
                        sortLiveTrackingFixesChronologically(
                            locations.map { it to it.toLiveTrackingLocationFix() },
                        )
                    for ((location, fix) in ordered) {
                        if (
                            !canContinueLiveTrackingSend(
                                isPaused,
                                isStopping,
                                stop = false,
                                isPauseRequested = pauseRequested,
                            )
                        ) {
                            return@withAcquisition
                        }
                        if (!acquisition.cycle.consumeDeliveredFix()) {
                            recordLiveTrackingEvent(acquisition.trackingContext(), "update_budget_exhausted")
                            continue
                        }
                        val initialComparison = compareLiveTrackingFixTime(fix, acquisition.initialFix)
                        val previousComparison = lastProcessedFix?.let { compareLiveTrackingFixTime(fix, it) }
                        if (initialComparison <= 0 || previousComparison?.let { it <= 0 } == true) {
                            val duplicate = initialComparison == 0 || previousComparison == 0
                            acquisition.cycle.recordIgnoredFix(duplicate)
                            recordLiveTrackingEvent(
                                acquisition.trackingContext(),
                                if (duplicate) "duplicate_ignored" else "stale_ignored",
                            )
                            continue
                        }
                        if (acquisition.rescueCooldownPending && !beginRescueActivity(acquisition)) {
                            completionReason = "confirmation_cooldown_blocked"
                            break
                        }
                        lastProcessedFix = fix
                        val mayAdvanceConfirmation = acquisition.confirmationBudget.allowsPendingAreaConfirmation()
                        val decision =
                            locationQualityGate.evaluate(
                                fix = fix,
                                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                                nowEpochMilliseconds = System.currentTimeMillis(),
                                startupStaleReason = "stale_refinement_fix",
                                allowPendingAreaConfirmation = mayAdvanceConfirmation,
                            )
                        acquisition.confirmationBudget.record(decision)
                        val candidate =
                            if (decision.result == LiveTrackingLocationQualityResult.ACCEPT) {
                                LiveTrackingCandidate(location, fix, decision)
                            } else {
                                null
                            }
                        acquisition.cycle.recordDecision(decision, candidate)
                        LiveTrackingDiagnostics.recordLiveTrackingFix(
                            source = LiveTrackingFixSource.EXPLICIT_FRESH,
                            decision = decision,
                            androidSpeedMetersPerSecond = fix.speedMetersPerSecond,
                            isMockLocation = location.isMockLocationForDiagnostics(),
                            gsmSignalPercent = cellularSignalMonitor.currentPercent(),
                            queueSize = diagnosticPositionQueueSize(),
                            context = acquisition.trackingContext(),
                            fixTimestampEpochMillis = fix.epochMilliseconds,
                        )
                        if (decision.result != LiveTrackingLocationQualityResult.ACCEPT) {
                            LiveTrackingDiagnostics.recordLocationQuality(
                                decision,
                                cellularSignalMonitor.currentPercent(),
                            )
                        }
                        if (decision.result == LiveTrackingLocationQualityResult.SUSPECT &&
                            acquisition.cycle.reason == LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT
                        ) {
                            completionReason = handOffRefinementToSuspectConfirmation(acquisition, decision)
                            if (completionReason != null) break
                        }
                        if (acquisition.cycle.reason == LiveTrackingAcquisitionReason.SUSPECT_CONFIRMATION &&
                            decision.result == LiveTrackingLocationQualityResult.ACCEPT
                        ) {
                            completionReason = "suspect_confirmed"
                        }
                        if (completionReason == null && acquisition.cycle.hasReachedTarget()) {
                            completionReason = "target_reached"
                        }
                        if (completionReason != null) break
                    }
                    if (completionReason == null && acquisition.cycle.extraFixesDelivered >= MAX_ACCURACY_EXTRA_FIXES) {
                        completionReason = "budget_exhausted"
                    }
                    completionReason?.let { completeAcquisitionLocked(acquisition, it) }
                    true
                }
            if (handled == null) {
                recordLiveTrackingEvent(acquisition.trackingContext(), "late_callback_ignored")
            }
        }
        if (completionReason != null) removeAcquisitionCallback(acquisition)
    }

    @Suppress("ReturnCount")
    private fun handOffRefinementToSuspectConfirmation(
        acquisition: ActiveAcquisition,
        decision: LiveTrackingLocationQualityDecision,
    ): String? {
        acquisition.trigger = decision.reason
        val now = SystemClock.elapsedRealtimeNanos()
        val cooldownRemaining =
            orchestration.rescueCooldownRemainingMillis(
                nowElapsedRealtimeNanos = now,
                cooldownMillis = RESCUE_COOLDOWN_MILLIS,
            )
        when (
            handoffLiveTrackingAcquisitionToConfirmation(
                acquisition.cycle,
                cooldownRemaining,
                hasLocationPermission(),
            )
        ) {
            LiveTrackingConfirmationHandoff.UNAVAILABLE -> return "confirmation_unavailable"
            LiveTrackingConfirmationHandoff.COOLDOWN_BLOCKED -> {
                acquisition.rescueSkippedCooldown = true
                return "confirmation_cooldown_blocked"
            }
            LiveTrackingConfirmationHandoff.NOT_APPLICABLE -> return null
            LiveTrackingConfirmationHandoff.CONTINUE -> Unit
        }
        return if (beginRescueActivity(acquisition)) null else "confirmation_cooldown_blocked"
    }

    @SuppressLint("MissingPermission")
    // Keep registration, bounded waiting and cleanup together to preserve lifecycle ordering.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun awaitAcquisition(acquisition: ActiveAcquisition) {
        try {
            val request = liveTrackingRefinementLocationRequest()
            val registrationTask =
                orchestration.registerAcquisition(
                    acquisition = acquisition,
                    sessionActive = { canRegisterAcquisition(acquisition) },
                    register = {
                        recordLiveTrackingEvent(acquisition.trackingContext(), "registration_requested")
                        locationClient
                            .requestLocationUpdates(request, acquisition.callback, mainLooper)
                            .also { task ->
                                task.addOnCompleteListener { completedTask ->
                                    onAcquisitionRegistrationComplete(acquisition, completedTask)
                                }
                            }
                    },
                )
            if (registrationTask == null) {
                completeAcquisitionLocked(acquisition, "cancelled_before_registration")
                return
            }
            val remainingBeforeRegistration = acquisitionRemainingMillis(acquisition)
            val registered =
                withTimeoutOrNull(remainingBeforeRegistration) {
                    select<Boolean?> {
                        acquisition.registrationResult.onAwait { it }
                        acquisition.completion.onAwait { null }
                    }
                }
            if (registered == null && !acquisition.cycle.completed) {
                acquisition.timedOut = true
                completeAcquisitionLocked(acquisition, "timeout")
            } else if (registered == false && !acquisition.cycle.completed) {
                completeAcquisitionLocked(acquisition, "registration_cancelled")
            } else if (registered == true && !isAcquisitionActive(acquisition)) {
                requestAcquisitionCallbackRemoval(acquisition, "late_registration_cleanup")
            } else if (registered == true) {
                val completed =
                    withTimeoutOrNull(acquisitionRemainingMillis(acquisition)) {
                        acquisition.completion.await()
                    }
                if (completed == null && !acquisition.cycle.completed) {
                    acquisition.timedOut = true
                    completeAcquisitionLocked(acquisition, "timeout")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (!acquisition.cycle.completed) {
                acquisition.registrationFailed = true
                completeAcquisitionLocked(acquisition, "registration_failure")
            }
        } finally {
            removeAcquisitionCallback(acquisition)
            acquisition.durationMillis =
                (SystemClock.elapsedRealtime() - acquisition.startedElapsedRealtimeMillis).coerceAtLeast(0L)
        }
    }

    private fun completeAcquisitionLocked(
        acquisition: ActiveAcquisition,
        outcome: String,
    ) {
        orchestration.finishAcquisition(acquisition) {
            acquisition.outcome = outcome
            acquisition.cycle.complete()
            recordLiveTrackingEvent(acquisition.trackingContext(), "acquisition_completed_$outcome")
            acquisition.completion.complete(outcome)
        }
    }

    private fun recordCancelledAcquisition(
        acquisition: ActiveAcquisition,
        reason: String,
    ) {
        acquisition.cancellation = reason
        acquisition.outcome = "cancelled_$reason"
        acquisition.cycle.complete()
        acquisition.completion.complete(checkNotNull(acquisition.outcome))
        recordLiveTrackingEvent(acquisition.trackingContext(), "cancelled_$reason")
        requestAcquisitionCallbackRemoval(acquisition, "cancel_cleanup")
    }

    private suspend fun removeAcquisitionCallback(acquisition: ActiveAcquisition) {
        withContext(NonCancellable) {
            recordLiveTrackingEvent(acquisition.trackingContext(), "callback_removal_requested")
            val removal =
                runCatching { locationClient.removeLocationUpdates(acquisition.callback) }
                    .getOrElse {
                        acquisition.cleanupFailed = true
                        recordLiveTrackingEvent(acquisition.trackingContext(), "callback_cleanup_failure")
                        return@withContext
                    }
            // Observe late acknowledgements even if the bounded cleanup wait has already timed out.
            removal.addOnCompleteListener { task ->
                recordLiveTrackingEvent(
                    acquisition.trackingContext(),
                    if (task.isSuccessful) "callback_removal_acknowledged" else "callback_removal_ack_failed",
                )
            }
            val finished =
                runCatching {
                    withTimeoutOrNull(ACQUISITION_CLEANUP_TIMEOUT_MS) {
                        removal.await()
                        true
                    } ?: false
                }.getOrElse {
                    acquisition.cleanupFailed = true
                    recordLiveTrackingEvent(acquisition.trackingContext(), "callback_cleanup_failure")
                    false
                }
            if (!finished && !acquisition.cleanupFailed) {
                acquisition.cleanupTimedOut = true
                recordLiveTrackingEvent(acquisition.trackingContext(), "callback_cleanup_timeout")
            }
        }
    }

    private fun canRegisterAcquisition(acquisition: ActiveAcquisition): Boolean =
        orchestration.isAcquisitionActive(acquisition) &&
            !acquisition.cycle.completed &&
            settings != null &&
            !isPaused &&
            !pauseRequested &&
            !isStopping &&
            (
                acquisition.cycle.reason != LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT ||
                    hasFineLocationPermission()
            )

    private fun isAcquisitionActive(acquisition: ActiveAcquisition): Boolean =
        orchestration.isAcquisitionActive(acquisition) &&
            settings != null &&
            !isPaused &&
            !pauseRequested &&
            !isStopping

    private fun acquisitionRemainingMillis(acquisition: ActiveAcquisition): Long =
        (
            ACQUISITION_WINDOW_MS -
                (SystemClock.elapsedRealtime() - acquisition.startedElapsedRealtimeMillis).coerceAtLeast(0L)
        ).coerceAtLeast(0L)

    @Suppress("ReturnCount")
    private fun onAcquisitionRegistrationComplete(
        acquisition: ActiveAcquisition,
        task: Task<Void>,
    ) {
        if (!task.isSuccessful) {
            recordLiveTrackingEvent(acquisition.trackingContext(), "registration_ack_failed")
            acquisition.registrationResult.completeExceptionally(
                task.exception ?: IllegalStateException("Location callback registration failed"),
            )
            return
        }
        val retained =
            orchestration.acknowledgeRegistration(
                acquisition = acquisition,
                sessionActive = { canRegisterAcquisition(acquisition) },
                removeLateRegistration = {
                    requestAcquisitionCallbackRemoval(acquisition, "late_registration_cleanup")
                },
            )
        if (!retained) {
            recordLiveTrackingEvent(acquisition.trackingContext(), "registration_ack_cleanup_only")
            acquisition.registrationResult.complete(false)
            return
        }
        recordLiveTrackingEvent(acquisition.trackingContext(), "registration_ack_owned")
        val registered =
            orchestration.withAcquisition(acquisition) {
                if (acquisition.rescueCooldownPending && !beginRescueActivity(acquisition)) {
                    completeAcquisitionLocked(acquisition, "confirmation_cooldown_blocked")
                    requestAcquisitionCallbackRemoval(acquisition, "cooldown_cleanup")
                    false
                } else {
                    true
                }
            } ?: false
        acquisition.registrationResult.complete(registered)
    }

    private fun beginRescueActivity(acquisition: ActiveAcquisition): Boolean =
        orchestration.withAcquisition(acquisition) {
            orchestration
                .beginRescueActivity(
                    acquisition = acquisition,
                    nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    cooldownMillis = RESCUE_COOLDOWN_MILLIS,
                ) {
                    acquisition.rescueCooldownPending = false
                    acquisition.rescueWasRequested = true
                    recordLiveTrackingEvent(
                        acquisition.trackingContext(),
                        "rescue_cooldown_initialized",
                        RESCUE_COOLDOWN_MILLIS,
                    )
                    recordLiveTrackingRescue(
                        LiveTrackingRescueDiagnostic(
                            requested = true,
                            trigger = acquisition.trigger ?: acquisition.initialDecision.reason,
                            context = acquisition.trackingContext(),
                            cooldownRemainingMillis = RESCUE_COOLDOWN_MILLIS,
                        ),
                    )
                }.also { allowed ->
                    recordLiveTrackingEvent(
                        acquisition.trackingContext(),
                        if (allowed) "rescue_cooldown_allowed" else "rescue_cooldown_blocked",
                        orchestration.rescueCooldownRemainingMillis(
                            SystemClock.elapsedRealtimeNanos(),
                            RESCUE_COOLDOWN_MILLIS,
                        ),
                    )
                    if (!allowed) acquisition.rescueSkippedCooldown = true
                }
        } ?: false

    private fun requestAcquisitionCallbackRemoval(
        acquisition: ActiveAcquisition,
        event: String,
    ) {
        recordLiveTrackingEvent(acquisition.trackingContext(), event)
        val removal =
            runCatching { locationClient.removeLocationUpdates(acquisition.callback) }
                .getOrElse {
                    acquisition.cleanupFailed = true
                    recordLiveTrackingEvent(acquisition.trackingContext(), "callback_cleanup_failure")
                    return
                }
        val cleanupTimeoutHandler = if (event == "late_registration_cleanup") Handler(mainLooper) else null
        val cleanupTimeout =
            Runnable {
                if (!removal.isComplete) {
                    acquisition.cleanupTimedOut = true
                    recordLiveTrackingEvent(acquisition.trackingContext(), "callback_cleanup_timeout")
                }
            }
        cleanupTimeoutHandler?.postDelayed(cleanupTimeout, ACQUISITION_CLEANUP_TIMEOUT_MS)
        removal.addOnCompleteListener { task ->
            cleanupTimeoutHandler?.removeCallbacks(cleanupTimeout)
            if (!task.isSuccessful) {
                acquisition.cleanupFailed = true
                recordLiveTrackingEvent(acquisition.trackingContext(), "callback_cleanup_failure")
            } else {
                recordLiveTrackingEvent(acquisition.trackingContext(), "callback_removal_acknowledged")
            }
        }
    }

    private fun acquisitionOutcome(
        acquisition: ActiveAcquisition?,
        sendOutcome: String,
        selected: LiveTrackingCandidate<Location>?,
    ): String =
        liveTrackingAcquisitionOutcome(
            acquisitionOutcome = acquisition?.outcome,
            transmissionOutcome = sendOutcome,
            hasCandidate = selected != null,
            usesScheduledFallback = selected?.isScheduledFallback == true,
        )

    private fun recordRescueOutcome(
        acquisition: ActiveAcquisition,
        selected: LiveTrackingCandidate<Location>?,
    ) {
        val decision = selected?.decision
        recordLiveTrackingRescue(
            LiveTrackingRescueDiagnostic(
                requested = acquisition.rescueWasRequested,
                trigger = acquisition.trigger ?: acquisition.initialDecision.reason,
                context = acquisition.trackingContext(),
                skippedBecauseCooldown = acquisition.rescueSkippedCooldown,
                resultAgeMillis = decision?.fixAgeMillis,
                accuracyMeters = decision?.accuracyMeters,
                speedMetersPerSecond = selected?.fix?.speedMetersPerSecond,
                outcome =
                    when {
                        acquisition.rescueSkippedCooldown -> "cooldown_blocked"
                        !acquisition.rescueWasRequested -> acquisition.outcome ?: "unavailable"
                        selected == null -> acquisition.outcome ?: "inconclusive"
                        selected.isScheduledFallback -> "fallback_after_inconclusive"
                        decision?.suspectResolution == LiveTrackingSuspectResolution.REJECTED ->
                            "returned_previous_area"
                        else -> "confirmed_candidate"
                    },
            ),
        )
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "ReturnCount",
        "TooGenericExceptionCaught",
        "NestedBlockDepth",
        "LoopWithTooManyJumpStatements",
    )
    private suspend fun transmitSelectedLocationLocked(
        settings: LiveTrackingSettings,
        candidates: List<LiveTrackingCandidate<Location>>,
        generation: LiveTrackingGeneration,
        context: LiveTrackingDiagnosticContext,
    ): TransmissionResult {
        if (
            !orchestration.isCurrent(generation) ||
            !canContinueLiveTrackingSend(isPaused, isStopping, stop = false, isPauseRequested = pauseRequested)
        ) {
            return TransmissionResult("cancelled_lifecycle", null)
        }
        var attemptedUpdate: ArkluzLocationUpdate? = null
        var attemptedSelection: LiveTrackingCandidateSelection<Location>? = null
        var sentStartInRequest = false
        var queueSizeBeforeTransmission: Int? = null
        try {
            flushPendingSessionControlsLocked()
            if (
                !orchestration.isCurrent(generation) ||
                !canContinueLiveTrackingSend(isPaused, isStopping, stop = false, isPauseRequested = pauseRequested)
            ) {
                return TransmissionResult("cancelled_lifecycle", null)
            }
            while (true) {
                val selection =
                    selectFreshLiveTrackingCandidate(
                        candidates,
                        SystemClock.elapsedRealtimeNanos(),
                        System.currentTimeMillis(),
                    ) ?: return TransmissionResult("stale_no_candidate", null)
                val selected = selection.candidate
                val age =
                    liveTrackingLocationAgeMillis(
                        selected.fix,
                        SystemClock.elapsedRealtimeNanos(),
                        System.currentTimeMillis(),
                    ) ?: continue
                val qualityDecision = selected.decision.copy(fixAgeMillis = age)
                sentStartInRequest = !sentStart
                val update =
                    arkluzClient.buildLocationUpdate(
                        settings = settings,
                        location = selected.value,
                        start = sentStartInRequest,
                        stop = false,
                        locationDiagnostics = qualityDecision.toDiagnostics(),
                    )
                if (
                    !orchestration.isCurrent(generation) ||
                    !canContinueLiveTrackingSend(
                        isPaused,
                        isStopping,
                        stop = false,
                        isPauseRequested = pauseRequested,
                    )
                ) {
                    return TransmissionResult("cancelled_lifecycle", null)
                }
                val afterBuild =
                    selectFreshLiveTrackingCandidate(
                        candidates,
                        SystemClock.elapsedRealtimeNanos(),
                        System.currentTimeMillis(),
                    )
                if (afterBuild?.candidate !== selected) continue

                queueSizeBeforeTransmission = diagnosticPositionQueueSize()
                attemptedSelection = selection
                attemptedUpdate = update
                lastLocation = selected.value
                LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                    isCatchUp = false,
                    fixTimestampEpochMillis = update.epochMilliseconds,
                    fixAgeMillis =
                        liveTrackingLocationAgeMillis(
                            selected.fix,
                            SystemClock.elapsedRealtimeNanos(),
                            System.currentTimeMillis(),
                        ),
                    gsmSignalPercent = update.gsmSignalPercent,
                    queueSizeBefore = queueSizeBeforeTransmission,
                    queueSizeAfter = queueSizeBeforeTransmission,
                    outcome = "attempt",
                    context = context,
                )
                val result = arkluzClient.sendLocationUpdate(update)
                if (sentStartInRequest) {
                    sentStart = true
                    result.dateId?.let { dateId = it }
                    persistActiveSession()
                }
                val serverMessage = result.message.takeUnless { it == "Server accepted request" }
                val status = serverMessage ?: if (sentStartInRequest) "Started and position sent" else "Position sent"
                val replay = runCatching { replayStoredGpsPointsLocked() }
                replay
                    .onSuccess { replayedCount ->
                        LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                            isCatchUp = update.isCatchUp,
                            fixTimestampEpochMillis = update.epochMilliseconds,
                            fixAgeMillis = qualityDecision.fixAgeMillis,
                            gsmSignalPercent = update.gsmSignalPercent,
                            queueSizeBefore = queueSizeBeforeTransmission,
                            queueSizeAfter = diagnosticPositionQueueSize(),
                            outcome = "success",
                            context = context,
                        )
                        val replayStatus =
                            if (replayedCount > 0) {
                                "$status; replayed $replayedCount stored GPS point" +
                                    if (replayedCount == 1) "" else "s"
                            } else {
                                status
                            }
                        LiveTrackingSessionStore.setSent(replayStatus)
                        updateNotification(replayStatus)
                    }.onFailure { error ->
                        LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                            isCatchUp = update.isCatchUp,
                            fixTimestampEpochMillis = update.epochMilliseconds,
                            fixAgeMillis = qualityDecision.fixAgeMillis,
                            gsmSignalPercent = update.gsmSignalPercent,
                            queueSizeBefore = queueSizeBeforeTransmission,
                            queueSizeAfter = diagnosticPositionQueueSize(),
                            outcome = "success_with_pending_catch_up",
                            context = context,
                        )
                        val message =
                            "Position sent; stored GPS points still waiting (${error.toLiveTrackingErrorText()})"
                        LiveTrackingSessionStore.setError(message)
                        updateNotification("Stored GPS points still waiting")
                    }
                return TransmissionResult(
                    "sent",
                    selection.copy(candidate = selected.copy(decision = qualityDecision)),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!orchestration.isCurrent(generation)) return TransmissionResult("stale_request_generation", null)
            val eligibleNow =
                selectFreshLiveTrackingCandidate(
                    candidates,
                    SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis(),
                ) ?: return TransmissionResult("stale_no_candidate", null)
            val location = eligibleNow.candidate.value
            val qualityDecision =
                eligibleNow.candidate.decision.copy(
                    fixAgeMillis =
                        liveTrackingLocationAgeMillis(
                            eligibleNow.candidate.fix,
                            SystemClock.elapsedRealtimeNanos(),
                            System.currentTimeMillis(),
                        ),
                )
            lastLocation = location
            val failedUpdate =
                attemptedUpdate.takeIf { attemptedSelection?.candidate === eligibleNow.candidate }
                    ?: arkluzClient.buildLocationUpdate(
                        settings = settings,
                        location = location,
                        start = attemptedUpdate?.start == true,
                        stop = false,
                    )
            val startWasAlreadyPending = LiveTrackingControlQueue.load(this).any { it.start }
            if (failedUpdate.start && !startWasAlreadyPending) {
                LiveTrackingControlQueue.enqueue(this, failedUpdate)
                serviceScope.launch { retryPendingStartUntilConfirmed() }
            }
            val controlsPending = LiveTrackingControlQueue.load(this).isNotEmpty()
            if (error.isRetryableArkluzFailure()) {
                val queueSize = LiveTrackingPositionQueue.enqueue(this, failedUpdate)
                LiveTrackingDiagnostics.recordLiveTrackingPositionQueued(queueSize)
                LiveTrackingDiagnostics.recordLiveTrackingTransmission(
                    isCatchUp = false,
                    fixTimestampEpochMillis = failedUpdate.epochMilliseconds,
                    fixAgeMillis = qualityDecision.fixAgeMillis,
                    gsmSignalPercent = failedUpdate.gsmSignalPercent,
                    queueSizeBefore = queueSizeBeforeTransmission,
                    queueSizeAfter = queueSize,
                    outcome = "queued",
                    context = context,
                )
                if (!sentStart && controlsPending) {
                    LiveTrackingSessionStore.setStartPending(
                        "Waiting for network to start tracking; GPS stored for retry ($queueSize waiting)",
                    )
                    updateNotification("Waiting for network to start tracking")
                } else if (controlsPending) {
                    LiveTrackingSessionStore.setActive(
                        status = "GPS stored for retry ($queueSize waiting)",
                        serverSyncPending = true,
                    )
                    updateNotification("Tracking active; Arkluz notification pending")
                } else {
                    LiveTrackingSessionStore.setError(
                        "GPS stored for retry ($queueSize waiting): ${error.toLiveTrackingErrorText()}",
                    )
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
                    fixAgeMillis = qualityDecision.fixAgeMillis,
                    gsmSignalPercent = update.gsmSignalPercent,
                    queueSizeBefore = queueSizeBeforeTransmission,
                    queueSizeAfter = diagnosticPositionQueueSize(),
                    outcome = if (error.isRetryableArkluzFailure()) "network_retry" else "failed",
                    context = context,
                )
            }
            return TransmissionResult(
                if (error.isRetryableArkluzFailure()) "queued" else "failed",
                eligibleNow.copy(candidate = eligibleNow.candidate.copy(decision = qualityDecision)),
            )
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
    private fun startLocationUpdates(): LiveTrackingGeneration? {
        if (!hasLocationPermission()) return null
        stopLocationUpdates()
        val intervalMillis = updateIntervalMs()
        val generation =
            orchestration.startGeneration(intervalMillis, SystemClock.elapsedRealtime()) {
                canContinueLiveTrackingSend(isPaused, isStopping, stop = false, isPauseRequested = pauseRequested)
            }
        generation?.let { registerPeriodicLocationUpdates(it, intervalMillis) }
        return generation
    }

    @SuppressLint("MissingPermission")
    private fun registerPeriodicLocationUpdates(
        generation: LiveTrackingGeneration,
        intervalMillis: Long,
    ) {
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
                .setMinUpdateIntervalMillis(intervalMillis)
                .setMaxUpdateDelayMillis(intervalMillis)
                .build()
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val locations = result.locations.toList()
                    if (locations.isEmpty()) return
                    val ticket =
                        orchestration.withGeneration(generation) {
                            generation.admission.observe(SystemClock.elapsedRealtime())
                        } ?: run {
                            recordLiveTrackingEvent(trackingContext(generation), "late_normal_callback_ignored")
                            return
                        }
                    serviceScope.launch {
                        sendLocations(locations, LiveTrackingFixSource.CALLBACK, generation, ticket)
                    }
                }
            }
        orchestration.withGeneration(generation) {
            if (canContinueLiveTrackingSend(isPaused, isStopping, stop = false, isPauseRequested = pauseRequested)) {
                val context = trackingContext(generation)
                periodicDiagnosticContext = context
                recordLiveTrackingEvent(context, "generation_started")
                locationCallback = callback
                recordLiveTrackingEvent(context, "periodic_registration_requested")
                locationClient.requestLocationUpdates(request, callback, mainLooper)
            }
        }
    }

    private fun stopLocationUpdates(reason: String = "request_replaced") {
        orchestration.invalidateGeneration(
            onCancelled = { recordCancelledAcquisition(it, reason) },
            stopPeriodicUpdates = {
                periodicDiagnosticContext?.let { recordLiveTrackingEvent(it, "generation_invalidated_$reason") }
                periodicDiagnosticContext = null
                val callback = locationCallback
                locationCallback = null
                if (callback != null) runCatching { locationClient.removeLocationUpdates(callback) }
            },
        )
    }

    private fun pauseTracking() {
        if (isPaused || isStopping || !sentStart) return
        pauseRequested = true
        stopLocationUpdates("pause")
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
                isPaused = true
                pauseRequested = false
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
            if (!pauseStarted) {
                pauseRequested = false
                return@launch
            }
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
        pauseRequested = false
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
        normalLiveTrackingIntervalMs(
            settings?.updateIntervalSeconds ?: DEFAULT_UPDATE_INTERVAL_SECONDS,
        )

    private fun stopTracking() {
        if (isStopping) return
        isStopping = true
        pauseRequested = false
        isPaused = false
        stopLocationUpdates("stop")
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
        isStopping = true
        pauseRequested = false
        stopLocationUpdates("stop")
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

    private fun hasFineLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
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
        private const val ACQUISITION_CLEANUP_TIMEOUT_MS = 500L

        // Two minutes prevents repeated suspect callbacks from becoming fast GPS polling.
        private const val RESCUE_COOLDOWN_MILLIS = 2 * 60 * 1000L
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
