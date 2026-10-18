package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.MainActivityMobile
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.glancemapcompanionapp.routes.activityStatistics
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceAlertOutcome
import com.glancemap.trailcore.guidance.TrailGuidanceAlertSample
import com.glancemap.trailcore.guidance.TrailGuidanceAlertTracker
import com.glancemap.trailcore.guidance.TrailGuidanceCommand
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.guidance.TrailGuidanceEtaProjection
import com.glancemap.trailcore.guidance.TrailGuidanceMode
import com.glancemap.trailcore.guidance.TrailGuidanceOffRouteConfirmationState
import com.glancemap.trailcore.guidance.TrailGuidanceSession
import com.glancemap.trailcore.guidance.TrailGuidanceStartDecision
import com.glancemap.trailcore.guidance.TrailGuidanceState
import com.glancemap.trailcore.guidance.TrailGuidanceTuning
import com.glancemap.trailcore.guidance.buildTrailGuidanceEtaProjection
import com.glancemap.trailcore.guidance.computeTrailGuidanceState
import com.glancemap.trailcore.guidance.evaluateTrailGuidanceStartDecision
import com.glancemap.trailcore.guidance.isGuidanceStartReached
import com.glancemap.trailcore.guidance.resolveTrailGuidanceGpsIntervalMs
import com.glancemap.trailcore.guidance.shouldAlertForTurn
import com.glancemap.trailcore.guidance.updateGuidanceOffRouteConfirmation
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.recording.FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1
import com.glancemap.trailcore.recording.RecordedTraceSummary
import com.glancemap.trailcore.recording.RecordingCallbackGapTracker
import com.glancemap.trailcore.recording.RecordingDelayedDeliveryEvidence
import com.glancemap.trailcore.recording.RecordingDistanceInput
import com.glancemap.trailcore.recording.RecordingElevationProfilePoint
import com.glancemap.trailcore.recording.RecordingFixQualityGate
import com.glancemap.trailcore.recording.RecordingFixSample
import com.glancemap.trailcore.recording.RecordingHybridElevationFilter
import com.glancemap.trailcore.recording.RecordingHybridElevationInput
import com.glancemap.trailcore.recording.RecordingMotionSample
import com.glancemap.trailcore.recording.RecordingMovementConfidenceGate
import com.glancemap.trailcore.recording.RecordingPoint
import com.glancemap.trailcore.recording.RecordingPressureSample
import com.glancemap.trailcore.recording.RecordingProgressVibrationTracker
import com.glancemap.trailcore.recording.RecordingProgressVibrationTrigger
import com.glancemap.trailcore.recording.RecordingSegmentStartReason
import com.glancemap.trailcore.recording.RecordingSessionAppendContext
import com.glancemap.trailcore.recording.RecordingSessionLifecycle
import com.glancemap.trailcore.recording.RecordingSessionLifecycleState
import com.glancemap.trailcore.recording.RecordingSessionPhase
import com.glancemap.trailcore.recording.RecordingSettings
import com.glancemap.trailcore.recording.RecordingTerminalLocationRefreshInput
import com.glancemap.trailcore.recording.RecordingWatchGpsDistanceGeometry
import com.glancemap.trailcore.recording.appendRecordingSessionPoint
import com.glancemap.trailcore.recording.buildRecordingElevationProfileCumulative
import com.glancemap.trailcore.recording.effectiveRecordingAutoPauseGpsIntervalMillis
import com.glancemap.trailcore.recording.effectiveRecordingAutoPauseStartMillis
import com.glancemap.trailcore.recording.encodeRecordedTraceAsGpx
import com.glancemap.trailcore.recording.estimateRecordingCalories
import com.glancemap.trailcore.recording.estimateRecordingDistanceDelta
import com.glancemap.trailcore.recording.fastestRecordingPointSegmentSpeedMps
import com.glancemap.trailcore.recording.flushCanonicalRecordingTail
import com.glancemap.trailcore.recording.orderRecordingFixBatch
import com.glancemap.trailcore.recording.recordingAccuracyProvenance
import com.glancemap.trailcore.recording.recordingAutoPauseAddedMillisAtResume
import com.glancemap.trailcore.recording.recordingGpsIntervalMillis
import com.glancemap.trailcore.recording.recordingHaversineMeters
import com.glancemap.trailcore.recording.recordingStrictFreshFixMaxAgeMillis
import com.glancemap.trailcore.recording.resolveRecordingAbsoluteElevation
import com.glancemap.trailcore.recording.shouldRequestTerminalRecordingLocationRefresh
import com.glancemap.trailcore.recording.shouldStartRecordingGpsGapSegment
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class PhoneRecordingUiState(
    val session: RecordingSessionLifecycleState = RecordingSessionLifecycleState(),
    val activityProfile: PhoneActivityProfile = PhoneActivityProfile.HIKE,
    val points: List<RecordingPoint> = emptyList(),
    val pointsRevision: Long = 0L,
    val pointsBaseRevision: Long = 0L,
    val pointsChangedFromIndex: Int = 0,
    val segmentStartIndices: List<Int> = emptyList(),
    val distanceMeters: Double = 0.0,
    val latestLocation: PhoneMapLocation? = null,
    val locationUpdatesActive: Boolean = false,
    val settings: PhoneRecordingSettings = PhoneRecordingSettings(),
    val message: PhoneRecordingMessage = PhoneRecordingMessage.IDLE,
    val rejectedFixCount: Int = 0,
    val completedRecording: PhoneCompletedRecording? = null,
) {
    val isActive: Boolean get() = session.isActive
}

internal data class PhoneCompletedRecording(
    val id: String,
    val title: String,
    val statistics: RecordedActivityStatistics,
)

internal enum class PhoneRecordingMessage {
    IDLE,
    RECORDING,
    PAUSED,
    SAVING,
    SAVE_FAILED,
    SAVED,
    PERMISSION_REQUIRED,
    LOCATION_UNAVAILABLE,
}

/** The service owns recording state; the UI only acknowledges completed recaps. */
internal object PhoneRecordingSessionStore {
    private val mutableState = MutableStateFlow(PhoneRecordingUiState())
    val state: StateFlow<PhoneRecordingUiState> = mutableState.asStateFlow()

    fun publish(value: PhoneRecordingUiState) {
        mutableState.value = value
    }

    fun dismissCompletedRecording(id: String) {
        mutableState.update { current ->
            if (current.completedRecording?.id == id) current.copy(completedRecording = null) else current
        }
    }
}

private data class PhoneRecordingDraft(
    val startedAtMillis: Long,
    val pausedAtMillis: Long?,
    val accumulatedPausedMillis: Long,
    val paused: Boolean,
    val autoPaused: Boolean,
    val points: List<RecordingPoint>,
    val lastAcceptedElapsedRealtimeMillis: Long?,
    val distanceMeters: Double,
    val settings: PhoneRecordingSettings,
    val activityProfile: String? = null,
    val recordingId: String? = null,
    val autoPauseStartedAtElapsedRealtimeMillis: Long? = null,
    val gpsActiveDurationMillis: Long = 0L,
    val sessionStepCount: Int? = null,
)

private fun safeRecordingId(value: String?): String =
    runCatching { UUID.fromString(value ?: "") }
        .getOrElse { UUID.randomUUID() }
        .toString()

private data class PhoneRecordingLocationBatch(
    val generation: Long,
    val intervalMillis: Long,
    val deliveryIntervalMillis: Long,
    val locations: List<Location>,
)

private class PhoneRecordingDraftStore(
    context: Context,
) {
    private val file = File(context.applicationContext.filesDir, "recording/phone-rec-draft.json")
    private val gson = Gson()
    private val draftType = object : TypeToken<PhoneRecordingDraft>() {}.type

    fun load(): PhoneRecordingDraft? =
        runCatching { file.takeIf(File::isFile)?.reader()?.use { gson.fromJson<PhoneRecordingDraft>(it, draftType) } }
            .getOrNull()

    fun save(draft: PhoneRecordingDraft) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                val writer = output.writer(Charsets.UTF_8)
                gson.toJson(draft, writer)
                writer.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not replace recording draft" }
        } finally {
            temporary.delete()
        }
    }

    fun clear() {
        file.delete()
    }
}

/** Service-owned session survives panel disposal, navigation, activity recreation, and screen-off. */
@Suppress("TooManyFunctions", "LargeClass")
internal class PhoneRecordingService :
    Service(),
    SensorEventListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionLifecycle = RecordingSessionLifecycle()
    private val locationLock = Mutex()
    private val locationBatchQueue = Channel<PhoneRecordingLocationBatch>(Channel.UNLIMITED)
    private val saveLock = Mutex()
    private val draftLock = Mutex()
    private val fixQualityGate = RecordingFixQualityGate()
    private val movementGate = RecordingMovementConfidenceGate()
    private val callbackGapTracker = RecordingCallbackGapTracker(initialEffectiveIntervalMillis = 0L)
    private val distanceGeometry = RecordingWatchGpsDistanceGeometry()
    private val elevationFilter = RecordingHybridElevationFilter()
    private val vibrationTracker = RecordingProgressVibrationTracker()
    private val callbackGeneration = AtomicLong(0L)
    private val libraryRepository by lazy { RouteLibraryRepository(applicationContext) }
    private val draftStore by lazy { PhoneRecordingDraftStore(applicationContext) }
    private val guidanceDraftStore by lazy { PhoneGuidanceDraftStore(applicationContext) }
    private val guidanceFeedbackPreferences by lazy { PhoneGuidanceFeedbackPreferences(applicationContext) }
    private val settingsPreferences by lazy { PhoneRecordingSettingsPreferences(applicationContext) }
    private val generalSettingsPreferences by lazy { PhoneGeneralSettingsPreferences(applicationContext) }
    private val elevationRepository by lazy { PhoneElevationRepository(applicationContext, logTileFailures = false) }
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val guidanceTuning = TrailGuidanceTuning(offRouteDistanceMeters = 40.0)
    private var locationCallback: LocationCallback? = null
    private var guidanceRoute: PhoneGuidanceRouteDraft? = null
    private var guidanceSession: com.glancemap.trailcore.guidance.TrailGuidanceSession? = null
    private var guidanceProgress = PhoneGuidanceProgressDraft()
    private var guidanceOffRoute = TrailGuidanceOffRouteConfirmationState()
    private var guidanceAlertTracker = TrailGuidanceAlertTracker()
    private var guidanceLastFixElapsedRealtimeMillis: Long? = null
    private var guidanceLocationExpiryJob: Job? = null
    private var locationDeliveryIntervalMillis = 0L
    private var guidanceRequestedIntervalMillis = Long.MAX_VALUE
    private var guidanceCadenceJob: Job? = null
    private var guidanceOffRouteFeedbackJob: Job? = null
    private var guidanceEtaProjectionKey: GuidanceEtaProjectionKey? = null
    private var guidanceEtaProjection: TrailGuidanceEtaProjection? = null
    private var guidanceSpeech: TextToSpeech? = null
    private var guidanceVoiceAlertedInstructionKey: String? = null
    private var guidanceVoiceFailureLoggedInstructionKey: String? = null

    @Volatile private var guidanceSpeechReady = false

    @Volatile private var activeLocationGeneration = 0L
    private var lastAcceptedElapsedRealtimeMillis: Long? = null
    private var autoPauseStartedAtElapsedRealtimeMillis: Long? = null
    private var autoPauseCadenceJob: Job? = null

    @Volatile private var latestStepCount: Int? = null

    @Volatile private var sessionStepCount: Int? = null
    private var lastSensorStepCount: Int? = null
    private var gpsActiveDurationMillis = 0L
    private var effectiveIntervalMillis = 0L
    private var timeVibrationJob: Job? = null

    @Volatile private var screenIsOn = true
    private var summaryJob: Job? = null
    private var draftJob: Job? = null
    private var screenReceiverRegistered = false
    private var profile = PhoneActivityProfile.HIKE
    private var recordingId = UUID.randomUUID().toString()
    private var settings = PhoneRecordingSettings()
    private var counters = PhoneRecordingCounters()

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                screenIsOn = intent?.action != Intent.ACTION_SCREEN_OFF
                serviceScope.launch {
                    locationLock.withLock {
                        PhoneGuidanceSessionStore.state.value.guidance
                            ?.let { updateGuidanceGpsInterval(it, null) }
                        configureLocationUpdates()
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            for (batch in locationBatchQueue) {
                locationLock.withLock {
                    if (batch.generation != activeLocationGeneration) {
                        counters = counters.copy(obsolete = counters.obsolete + batch.locations.size)
                    } else {
                        batch.locations.forEach { location ->
                            processLocation(
                                location,
                                batch.generation,
                                batch.intervalMillis,
                                deliveryIntervalMillis = batch.deliveryIntervalMillis,
                            )
                        }
                    }
                }
            }
        }
        createNotificationChannel()
        guidanceSpeech =
            TextToSpeech(this) { status ->
                guidanceSpeechReady = status == TextToSpeech.SUCCESS
                if (guidanceSpeechReady) guidanceSpeech?.language = Locale.getDefault()
            }
        PhoneDebugCapture.log(TAG, "event=service_created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount") // Permission failures must stop before starting a foreground service.
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!PhoneMapLocationContext.hasPermission(this)) {
                    publish(PhoneRecordingUiState(message = PhoneRecordingMessage.PERMISSION_REQUIRED))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startForegroundNotification()) return START_NOT_STICKY
                serviceScope.launch { startOrRecover() }
            }
            ACTION_GUIDANCE_START -> {
                if (!PhoneMapLocationContext.hasPermission(this)) {
                    publishGuidanceFailure(PhoneGuidanceMessage.FAILED)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startForegroundNotification()) {
                    publishGuidanceFailure(PhoneGuidanceMessage.FAILED)
                    return START_NOT_STICKY
                }
                serviceScope.launch { locationLock.withLock { startGuidanceLocked() } }
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_SAVE -> serviceScope.launch { saveRecording() }
            ACTION_DISCARD -> discardRecording()
            ACTION_GUIDANCE_PAUSE -> serviceScope.launch { locationLock.withLock { pauseGuidanceLocked() } }
            ACTION_GUIDANCE_RESUME ->
                serviceScope.launch {
                    locationLock.withLock {
                        if (guidanceRoute == null) restoreGuidanceLocked(startForegroundIfActive = true)
                        resumeGuidanceLocked()
                    }
                }
            ACTION_GUIDANCE_RETRY ->
                serviceScope.launch {
                    locationLock.withLock {
                        val guidance = PhoneGuidanceSessionStore.state.value
                        if (guidance.session != null &&
                            !guidance.paused &&
                            guidance.guidance?.mode != TrailGuidanceMode.FINISHED
                        ) {
                            logEvent("event=guidance_location_retry")
                            startLocationUpdates()
                        }
                    }
                }
            ACTION_GUIDANCE_STOP -> serviceScope.launch { locationLock.withLock { stopGuidanceLocked() } }
            ACTION_GUIDANCE_ACCEPT_START_HERE ->
                serviceScope.launch {
                    locationLock.withLock { acceptGuidanceDecision(TrailGuidanceStartDecision.START_HERE) }
                }
            ACTION_GUIDANCE_ACCEPT_REVERSE ->
                serviceScope.launch {
                    locationLock.withLock { acceptGuidanceDecision(TrailGuidanceStartDecision.REVERSE_ROUTE) }
                }
            ACTION_GUIDANCE_DISMISS_DECISION -> dismissGuidanceDecision()
            ACTION_RESTORE,
            null,
            ->
                serviceScope.launch {
                    locationLock.withLock {
                        val recordingRecovered =
                            sessionLifecycle.state.isActive || restoreDraft(startForegroundIfActive = true)
                        val guidanceRecovered =
                            guidanceRoute != null || restoreGuidanceLocked(startForegroundIfActive = true)
                        if (!recordingRecovered && !guidanceRecovered) {
                            stopSelf(startId)
                        }
                    }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates("service_destroyed")
        unregisterRecordingSensors()
        unregisterScreenReceiver()
        summaryJob?.cancel()
        timeVibrationJob?.cancel()
        guidanceCadenceJob?.cancel()
        guidanceSpeech?.stop()
        guidanceSpeech?.shutdown()
        draftJob?.cancel()
        autoPauseCadenceJob?.cancel()
        locationBatchQueue.close()
        serviceScope.cancel()
        PhoneDebugCapture.log(TAG, "event=service_destroyed")
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE ->
                event.values.firstOrNull()?.let { pressure ->
                    elevationFilter.observePressure(
                        RecordingPressureSample(
                            pressureHpa = pressure.toDouble(),
                            elapsedRealtimeMillis = event.timestamp / NANOS_PER_MILLISECOND,
                        ),
                    )
                }
            Sensor.TYPE_STEP_COUNTER ->
                event.values
                    .firstOrNull()
                    ?.takeIf(Float::isFinite)
                    ?.let { rawCount ->
                        val count = rawCount.toInt().coerceAtLeast(0)
                        val previous = lastSensorStepCount
                        if (PhoneRecordingSessionStore.state.value.session.acceptsFixes && previous != null) {
                            sessionStepCount = (sessionStepCount ?: 0) + (count - previous).coerceAtLeast(0)
                        }
                        latestStepCount = count
                        lastSensorStepCount = count
                    }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    private suspend fun startOrRecover() {
        locationLock.withLock { startOrRecoverLocked() }
    }

    private suspend fun startOrRecoverLocked() {
        if (sessionLifecycle.state.isActive) {
            if (sessionLifecycle.state.acceptsFixes || canMonitorAutoResume(sessionLifecycle.state)) {
                startLocationUpdates()
            }
            return
        }
        if (restoreDraft(startForegroundIfActive = false)) return
        profile = generalSettingsPreferences.load().activityProfile
        val savedSettings = settingsPreferences.load()
        settings = savedSettings.withGpsPreset(profile, savedSettings.gpsUsageProfile)
        recordingId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val lifecycleState = sessionLifecycle.start(now)
        screenIsOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        resetProcessingState()
        vibrationTracker.start(settings.progressVibrationSettings())
        publish(
            PhoneRecordingUiState(
                session = lifecycleState,
                activityProfile = profile,
                settings = settings,
                message = PhoneRecordingMessage.RECORDING,
            ),
        )
        startForegroundNotification()
        registerRecordingSensors()
        registerScreenReceiver()
        startLocationUpdates()
        persistDraftNow()
        logEvent("session_started generation=${lifecycleState.generation} profile=${profile.storageValue}")
        startSummaryTimer()
        startTimeVibrationTimer()
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    private suspend fun restoreDraft(startForegroundIfActive: Boolean): Boolean {
        val draft = withContext(Dispatchers.IO) { draftStore.load() } ?: return false
        if (draft.points.isEmpty()) {
            withContext(Dispatchers.IO) { draftStore.clear() }
            return false
        }
        if (!PhoneMapLocationContext.hasPermission(this)) {
            publish(PhoneRecordingUiState(message = PhoneRecordingMessage.PERMISSION_REQUIRED))
            stopSelf()
            return true
        }
        screenIsOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        settings = draft.settings.normalized()
        val configuredProfile = generalSettingsPreferences.load().activityProfile
        profile = PhoneActivityProfile.fromStorageValue(draft.activityProfile ?: configuredProfile.storageValue)
        recordingId = safeRecordingId(draft.recordingId)
        val state =
            sessionLifecycle.recover(
                startedAtMillis = draft.startedAtMillis,
                pausedAtMillis = draft.pausedAtMillis,
                accumulatedPausedMillis = draft.accumulatedPausedMillis,
                paused = draft.paused,
                autoPaused = draft.autoPaused,
            )
        val currentBootElapsedMillis = SystemClock.elapsedRealtime()
        autoPauseStartedAtElapsedRealtimeMillis =
            if (canMonitorAutoResume(state)) {
                draft.autoPauseStartedAtElapsedRealtimeMillis
                    ?.takeIf { it > 0L && it <= currentBootElapsedMillis + REBOOT_TIMESTAMP_TOLERANCE_MILLIS }
                    ?: currentBootElapsedMillis
            } else {
                null
            }
        // Recovered monotonic timestamps may belong to another boot.
        lastAcceptedElapsedRealtimeMillis = null
        callbackGapTracker.reset()
        gpsActiveDurationMillis = draft.gpsActiveDurationMillis
        sessionStepCount = draft.sessionStepCount
        counters = PhoneRecordingCounters(accepted = draft.points.size)
        val recoveredState =
            PhoneRecordingUiState(
                session = state,
                activityProfile = profile,
                points = draft.points,
                pointsRevision = draft.points.size.toLong(),
                pointsBaseRevision = draft.points.size.toLong(),
                pointsChangedFromIndex = draft.points.size,
                segmentStartIndices =
                    draft.points.mapIndexedNotNull { index, point -> index.takeIf { point.startsNewSegment } },
                distanceMeters = draft.distanceMeters,
                settings = settings,
                message =
                    if (state.phase == RecordingSessionPhase.PAUSED) {
                        PhoneRecordingMessage.PAUSED
                    } else {
                        PhoneRecordingMessage.RECORDING
                    },
            )
        publish(recoveredState)
        vibrationTracker.rebase(
            settings = settings.progressVibrationSettings(),
            distanceMeters = draft.distanceMeters,
            activeDurationMillis = state.elapsedMillis(System.currentTimeMillis()),
        )
        if (startForegroundIfActive || state.isActive) {
            if (!startForegroundNotification()) {
                sessionLifecycle.discard()
                publish(PhoneRecordingUiState(message = PhoneRecordingMessage.LOCATION_UNAVAILABLE))
                stopSelf()
                return true
            }
            registerRecordingSensors()
            registerScreenReceiver()
            if (state.acceptsFixes || canMonitorAutoResume(state)) {
                startLocationUpdates()
                if (canMonitorAutoResume(state)) scheduleAutoPauseCadenceChange()
            }
            startSummaryTimer()
            startTimeVibrationTimer()
        }
        logEvent("session_recovered generation=${state.generation} points=${draft.points.size}")
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun refreshTerminalLocation() {
        val state = PhoneRecordingSessionStore.state.value
        val now = SystemClock.elapsedRealtime()
        val input =
            RecordingTerminalLocationRefreshInput(
                active = state.isActive,
                paused = state.session.phase == RecordingSessionPhase.PAUSED,
                saving = state.session.phase == RecordingSessionPhase.SAVING,
                activityProfile = profile.storageValue,
                hasRecordedPoint = state.points.isNotEmpty(),
                previousSpeedMps = state.points.lastOrNull()?.speedMps,
                acceptedPointAgeMillis = lastAcceptedElapsedRealtimeMillis?.let { (now - it).coerceAtLeast(0L) } ?: -1L,
                latestLocationAgeMillis =
                    state.latestLocation?.fixElapsedRealtimeMillis?.let { (now - it).coerceAtLeast(0L) } ?: -1L,
                freshLocationMaxAgeMillis = recordingStrictFreshFixMaxAgeMillis(effectiveIntervalMillis),
            )
        if (!shouldRequestTerminalRecordingLocationRefresh(input) ||
            !PhoneMapLocationContext.hasPermission(this)
        ) {
            return
        }
        val cancellation = CancellationTokenSource()
        try {
            val fresh =
                withTimeoutOrNull(8_000L) {
                    val request =
                        CurrentLocationRequest
                            .Builder()
                            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                            .setMaxUpdateAgeMillis(0L)
                            .setDurationMillis(8_000L)
                            .build()
                    locationClient.getCurrentLocation(request, cancellation.token).await()
                }
            if (fresh != null && fresh.elapsedRealtimeNanos / NANOS_PER_MILLISECOND >= now) {
                processLocation(fresh, activeLocationGeneration, effectiveIntervalMillis, terminalRefresh = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            logEvent("terminal_location_refresh_failed category=provider")
        } finally {
            cancellation.cancel()
        }
    }

    private fun pauseRecording() {
        serviceScope.launch { locationLock.withLock { pauseRecordingLocked() } }
    }

    private suspend fun pauseRecordingLocked() {
        if (!sessionLifecycle.state.acceptsFixes) return
        refreshTerminalLocation()
        val current = PhoneRecordingSessionStore.state.value
        if (!current.session.acceptsFixes) return
        clearAutoPauseCadence()
        stationarySinceElapsedMillis = null
        movingSinceElapsedMillis = null
        val state = sessionLifecycle.pause(System.currentTimeMillis())
        val finalized = flushTrackTail(current.copy(session = state))
        publish(finalized.copy(session = state, message = PhoneRecordingMessage.PAUSED))
        configureLocationUpdates()
        if (canMonitorAutoResume(state)) scheduleAutoPauseCadenceChange()
        updateNotification()
        persistDraftNow()
        logEvent("session_paused generation=${state.generation}")
    }

    private fun resumeRecording() {
        serviceScope.launch { locationLock.withLock { resumeRecordingLocked() } }
    }

    private fun resumeRecordingLocked() {
        val current = PhoneRecordingSessionStore.state.value
        if (current.session.phase != RecordingSessionPhase.PAUSED) return
        clearAutoPauseCadence()
        val state = sessionLifecycle.resume(System.currentTimeMillis())
        fixQualityGate.reset()
        movementGate.reset()
        distanceGeometry.reset()
        lastAcceptedElapsedRealtimeMillis = null
        callbackGapTracker.reset()
        stationarySinceElapsedMillis = null
        movingSinceElapsedMillis = null
        publish(current.copy(session = state, message = PhoneRecordingMessage.RECORDING))
        startForegroundNotification()
        startLocationUpdates()
        updateNotification()
        persistDraftSoon()
        logEvent("session_resumed generation=${state.generation}")
    }

    private fun discardRecording() {
        serviceScope.launch { locationLock.withLock { discardRecordingLocked() } }
    }

    private suspend fun discardRecordingLocked() {
        val current = PhoneRecordingSessionStore.state.value
        if (!current.session.isActive) return
        val oldGeneration = current.session.generation
        clearAutoPauseCadence()
        val discarded = sessionLifecycle.discard()
        unregisterRecordingSensors()
        draftJob?.cancelAndJoin()
        draftLock.withLock { withContext(Dispatchers.IO) { draftStore.clear() } }
        publish(PhoneRecordingUiState(session = discarded, settings = settings))
        if (PhoneGuidanceSessionStore.state.value.session != null) {
            configureLocationUpdates()
            updateNotification()
        } else {
            stopLocationUpdates("discarded")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        logEvent("session_discarded generation=$oldGeneration")
    }

    private suspend fun saveRecording() {
        saveLock.withLock {
            locationLock.withLock { saveRecordingLocked() }
        }
    }

    // Keep finalization, library save and draft cleanup in one guarded operation.
    @Suppress("ReturnCount", "LongMethod")
    private suspend fun saveRecordingLocked() {
        if (!sessionLifecycle.state.isActive ||
            PhoneRecordingSessionStore.state.value.points.size < MIN_SAVE_POINTS
        ) {
            return
        }
        refreshTerminalLocation()
        val current = PhoneRecordingSessionStore.state.value
        val generation = current.session.generation
        val saveState = sessionLifecycle.beginSave()
        autoPauseCadenceJob?.cancel()
        autoPauseCadenceJob = null
        val finalizedState = flushTrackTail(current.copy(session = saveState))
        val immutablePoints = finalizedState.points.toList()
        publish(finalizedState.copy(session = saveState, message = PhoneRecordingMessage.SAVING))
        configureLocationUpdates()
        updateNotification()
        logEvent("save_started generation=$generation points=${immutablePoints.size}")
        val (summary, bytes) =
            try {
                withContext(Dispatchers.Default) {
                    val summary = buildRecordedTraceSummary(finalizedState, immutablePoints)
                    summary to
                        encodeRecordedTraceAsGpx(
                            title = getString(R.string.phone_rec_activity_name),
                            points = immutablePoints,
                            summary = summary,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                completeSaveFailure(generation, finalizedState, "gpx_encode")
                return
            }
        try {
            withContext(Dispatchers.IO) {
                libraryRepository.saveRecordedGpx(
                    displayName = getString(R.string.phone_rec_activity_name),
                    gpxBytes = bytes,
                    recordingId = recordingId,
                )
            }
            if (!sessionLifecycle.isCurrent(generation)) return
            sessionLifecycle.saveSucceeded(generation)
            clearAutoPauseCadence()
            draftJob?.cancelAndJoin()
            draftLock.withLock { withContext(Dispatchers.IO) { draftStore.clear() } }
            publish(
                PhoneRecordingUiState(
                    session = sessionLifecycle.state,
                    settings = settings,
                    message = PhoneRecordingMessage.SAVED,
                    completedRecording =
                        PhoneCompletedRecording(
                            id = recordingId,
                            title = getString(R.string.phone_rec_activity_name),
                            statistics = summary.activityStatistics(),
                        ),
                ),
            )
            unregisterRecordingSensors()
            if (PhoneGuidanceSessionStore.state.value.session != null) {
                configureLocationUpdates()
                updateNotification()
            } else {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            logEvent("save_succeeded generation=$generation point_count=${immutablePoints.size} bytes=${bytes.size}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            completeSaveFailure(generation, finalizedState, "library_write")
        }
    }

    private fun completeSaveFailure(
        generation: Long,
        previous: PhoneRecordingUiState,
        category: String,
    ) {
        if (!sessionLifecycle.isCurrent(generation)) return
        val restored = sessionLifecycle.saveFailed(generation)
        publish(previous.copy(session = restored, message = PhoneRecordingMessage.SAVE_FAILED))
        if (restored.acceptsFixes || canMonitorAutoResume(restored)) {
            startLocationUpdates()
            if (canMonitorAutoResume(restored)) scheduleAutoPauseCadenceChange()
        }
        updateNotification()
        persistDraftSoon()
        logEvent("save_failed generation=$generation category=$category")
    }

    private fun canMonitorAutoResume(state: RecordingSessionLifecycleState): Boolean =
        state.isPausedForAutoResumeMonitoring(
            settings.autoPauseMode(profile) != RecordingSettings.RECORDING_AUTO_PAUSE_OFF,
        )

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!PhoneMapLocationContext.hasPermission(this)) {
            logEvent("location_start_failed category=permission")
            stopLocationUpdates("permission")
            publishLocationFailure(
                recordingMessage = PhoneRecordingMessage.PERMISSION_REQUIRED,
                guidanceMessage = PhoneGuidanceMessage.PERMISSION_REQUIRED,
            )
            return
        }
        configureLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    @Suppress("LongMethod") // Keep subscription generation and its guarded failure handling together.
    private fun configureLocationUpdates() {
        val lifecycle = sessionLifecycle.state
        val canMonitorAutoPause = canMonitorAutoResume(lifecycle)
        val recordingNeedsLocation = lifecycle.acceptsFixes || canMonitorAutoPause
        val guidanceState = PhoneGuidanceSessionStore.state.value
        val guidanceNeedsLocation =
            guidanceState.session != null &&
                !guidanceState.paused &&
                guidanceState.guidance?.mode != TrailGuidanceMode.FINISHED
        if (!recordingNeedsLocation && !guidanceNeedsLocation) {
            stopLocationUpdates("inactive_or_permission")
            return
        }
        if (!PhoneMapLocationContext.hasPermission(this)) {
            stopLocationUpdates("permission")
            publishLocationFailure(
                recordingMessage = PhoneRecordingMessage.PERMISSION_REQUIRED,
                guidanceMessage = PhoneGuidanceMessage.PERMISSION_REQUIRED,
            )
            return
        }
        val recordingIntervalMillis =
            if (!recordingNeedsLocation) {
                Long.MAX_VALUE
            } else {
                val configuredSeconds = if (screenIsOn) settings.screenOnIntervalSeconds else settings.screenOffIntervalSeconds
                val seconds =
                    if (!screenIsOn && configuredSeconds == RecordingSettings.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS) {
                        settings.screenOnIntervalSeconds
                    } else {
                        configuredSeconds
                    }
                var interval = recordingGpsIntervalMillis(seconds)
                if (canMonitorAutoPause) {
                    val startedAt =
                        autoPauseStartedAtElapsedRealtimeMillis ?: SystemClock.elapsedRealtime().also {
                            autoPauseStartedAtElapsedRealtimeMillis = it
                        }
                    interval =
                        effectiveRecordingAutoPauseGpsIntervalMillis(
                            recordingIntervalMillis = interval,
                            autoPausedDurationMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                        )
                }
                interval
            }
        val guidanceIntervalMillis = if (guidanceNeedsLocation) guidanceRequestedIntervalMillis else Long.MAX_VALUE
        val intervalMillis = minOf(recordingIntervalMillis, guidanceIntervalMillis)
        if (intervalMillis == Long.MAX_VALUE) {
            stopLocationUpdates("gps_interval_disabled")
            return
        }
        effectiveIntervalMillis = recordingIntervalMillis.takeIf { it != Long.MAX_VALUE } ?: intervalMillis
        callbackGapTracker.updateEffectiveInterval(intervalMillis)
        val oldGeneration = callbackGeneration.incrementAndGet()
        activeLocationGeneration = oldGeneration
        locationCallback?.let(locationClient::removeLocationUpdates)
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val locations =
                        orderRecordingFixBatch(result.locations) { location -> location.elapsedRealtimeNanos }
                    locationBatchQueue.trySend(
                        PhoneRecordingLocationBatch(
                            generation = oldGeneration,
                            intervalMillis = recordingIntervalMillis,
                            deliveryIntervalMillis = intervalMillis,
                            locations = locations,
                        ),
                    )
                }
            }
        locationCallback = callback
        locationDeliveryIntervalMillis = intervalMillis
        scheduleGuidanceLocationExpiry(oldGeneration, intervalMillis)
        publish(PhoneRecordingSessionStore.state.value)
        try {
            locationClient
                .requestLocationUpdates(
                    LocationRequest
                        .Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
                        .setMinUpdateIntervalMillis(max(MIN_LOCATION_INTERVAL_MILLIS, intervalMillis / 2L))
                        .build(),
                    callback,
                    Looper.getMainLooper(),
                ).addOnFailureListener {
                    serviceScope.launch {
                        locationLock.withLock {
                            if (activeLocationGeneration == oldGeneration) {
                                logEvent("event=location_provider_failed category=request_updates")
                                stopLocationUpdates("provider_failure")
                                publishLocationFailure(
                                    recordingMessage = PhoneRecordingMessage.LOCATION_UNAVAILABLE,
                                    guidanceMessage = PhoneGuidanceMessage.GPS_UNAVAILABLE,
                                )
                            }
                        }
                    }
                }
        } catch (_: SecurityException) {
            logEvent("event=location_provider_failed category=permission")
            stopLocationUpdates("permission")
            publishLocationFailure(
                recordingMessage = PhoneRecordingMessage.PERMISSION_REQUIRED,
                guidanceMessage = PhoneGuidanceMessage.PERMISSION_REQUIRED,
            )
        }
        logEvent(
            "location_subscription_started generation=$oldGeneration interval_ms=$intervalMillis screen_on=$screenIsOn",
        )
    }

    private suspend fun startGuidanceLocked() {
        if (guidanceRoute != null || PhoneGuidanceSessionStore.state.value.session != null) return
        val preparedRoute =
            try {
                withContext(Dispatchers.IO) { guidanceDraftStore.loadRoute() }
            } catch (failure: Exception) {
                if (failure !is CancellationException) logGuidancePersistenceFailure("load_route")
                throw failure
            }
        if (preparedRoute == null || preparedRoute.points.size < 2) {
            publishGuidanceFailure(PhoneGuidanceMessage.FAILED)
            if (!sessionLifecycle.state.isActive) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val route = preparedRoute.copy(sessionActive = true)
        try {
            withContext(Dispatchers.IO) { guidanceDraftStore.saveRoute(route) }
        } catch (_: Exception) {
            logGuidancePersistenceFailure("save_route")
            publishGuidanceFailure(PhoneGuidanceMessage.FAILED)
            if (!sessionLifecycle.state.isActive) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        guidanceRoute = route
        guidanceProgress =
            try {
                withContext(Dispatchers.IO) { guidanceDraftStore.loadProgress() }
            } catch (failure: Exception) {
                if (failure !is CancellationException) logGuidancePersistenceFailure("load_progress")
                throw failure
            }
        guidanceOffRoute =
            TrailGuidanceOffRouteConfirmationState(
                offRoute = guidanceProgress.offRoute,
                outsideSampleCount = guidanceProgress.outsideSampleCount,
                recoverySampleCount = guidanceProgress.recoverySampleCount,
            )
        guidanceSession = buildPhoneGuidanceSession(route, guidanceProgress)
        guidanceAlertTracker = TrailGuidanceAlertTracker()
        guidanceVoiceAlertedInstructionKey = null
        guidanceVoiceFailureLoggedInstructionKey = null
        guidanceLastFixElapsedRealtimeMillis = null
        screenIsOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        val state = computeTrailGuidanceState(guidanceSession, null, guidanceTuning)
        updateGuidanceGpsInterval(state, null)
        publishGuidance(
            PhoneGuidanceUiState(
                session = guidanceSession,
                guidance = state,
                activityProfile = PhoneActivityProfile.fromStorageValue(route.activityProfile),
                paused = false,
                message = PhoneGuidanceMessage.ACTIVE,
            ),
        )
        registerScreenReceiver()
        startLocationUpdates()
        persistGuidanceProgress()
        updateNotification()
        logEvent("event=guidance_started")
    }

    private suspend fun restoreGuidanceLocked(startForegroundIfActive: Boolean): Boolean {
        if (guidanceRoute != null) return true
        val route =
            try {
                withContext(Dispatchers.IO) { guidanceDraftStore.loadRoute() }
            } catch (failure: Exception) {
                if (failure !is CancellationException) logGuidancePersistenceFailure("load_route")
                throw failure
            } ?: return false
        if (!route.sessionActive || route.points.size < 2 || !PhoneMapLocationContext.hasPermission(this)) return false
        guidanceRoute = route
        guidanceProgress =
            try {
                withContext(Dispatchers.IO) { guidanceDraftStore.loadProgress() }
            } catch (failure: Exception) {
                if (failure !is CancellationException) logGuidancePersistenceFailure("load_progress")
                throw failure
            }
        guidanceOffRoute =
            TrailGuidanceOffRouteConfirmationState(
                offRoute = guidanceProgress.offRoute,
                outsideSampleCount = guidanceProgress.outsideSampleCount,
                recoverySampleCount = guidanceProgress.recoverySampleCount,
            )
        guidanceSession = buildPhoneGuidanceSession(route, guidanceProgress)
        guidanceAlertTracker = TrailGuidanceAlertTracker()
        guidanceVoiceAlertedInstructionKey = null
        guidanceVoiceFailureLoggedInstructionKey = null
        guidanceLastFixElapsedRealtimeMillis = null
        val guidance = computeTrailGuidanceState(guidanceSession, null, guidanceTuning)
        screenIsOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        updateGuidanceGpsInterval(guidance, null)
        val state =
            PhoneGuidanceUiState(
                session = guidanceSession,
                guidance = guidance,
                activityProfile = PhoneActivityProfile.fromStorageValue(route.activityProfile),
                paused = guidanceProgress.paused,
                message = if (guidanceProgress.paused) PhoneGuidanceMessage.PAUSED else PhoneGuidanceMessage.ACTIVE,
            )
        publishGuidance(state)
        if (startForegroundIfActive) startForegroundNotification()
        registerScreenReceiver()
        if (!guidanceProgress.paused) startLocationUpdates() else configureLocationUpdates()
        updateNotification()
        logEvent("event=guidance_recovered paused=${guidanceProgress.paused}")
        return true
    }

    private fun pauseGuidanceLocked() {
        val current = PhoneGuidanceSessionStore.state.value
        if (current.session == null || current.paused) return
        guidanceProgress = guidanceProgress.copy(paused = true)
        guidanceOffRouteFeedbackJob?.cancel()
        guidanceOffRouteFeedbackJob = null
        persistGuidanceProgress()
        publishGuidance(current.copy(paused = true, message = PhoneGuidanceMessage.PAUSED))
        configureLocationUpdates()
        updateNotification()
        logEvent("event=guidance_paused")
    }

    private suspend fun resumeGuidanceLocked() {
        val current = PhoneGuidanceSessionStore.state.value
        if (current.session == null || !current.paused) return
        guidanceProgress = guidanceProgress.copy(paused = false)
        persistGuidanceProgress()
        current.guidance?.let { updateGuidanceGpsInterval(it, null) }
        publishGuidance(current.copy(paused = false, message = PhoneGuidanceMessage.ACTIVE))
        if (current.guidance?.offRoute == true) startOffRouteFeedback()
        startForegroundNotification()
        startLocationUpdates()
        updateNotification()
        logEvent("event=guidance_resumed")
    }

    private suspend fun stopGuidanceLocked() {
        val wasActive = guidanceRoute != null || PhoneGuidanceSessionStore.state.value.session != null
        guidanceCadenceJob?.cancel()
        guidanceOffRouteFeedbackJob?.cancel()
        guidanceOffRouteFeedbackJob = null
        guidanceRoute = null
        guidanceSession = null
        guidanceProgress = PhoneGuidanceProgressDraft()
        guidanceOffRoute = TrailGuidanceOffRouteConfirmationState()
        guidanceLastFixElapsedRealtimeMillis = null
        guidanceRequestedIntervalMillis = Long.MAX_VALUE
        guidanceAlertTracker = TrailGuidanceAlertTracker()
        guidanceVoiceAlertedInstructionKey = null
        guidanceVoiceFailureLoggedInstructionKey = null
        guidanceEtaProjectionKey = null
        guidanceEtaProjection = null
        try {
            withContext(Dispatchers.IO) { guidanceDraftStore.clear() }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logGuidancePersistenceFailure("clear")
            throw failure
        }
        publishGuidance(PhoneGuidanceUiState())
        configureLocationUpdates()
        if (!sessionLifecycle.state.isActive) stopSelf()
        if (wasActive) logEvent("event=guidance_stopped")
    }

    private fun acceptGuidanceDecision(decision: TrailGuidanceStartDecision) {
        val current = PhoneGuidanceSessionStore.state.value
        val route = guidanceRoute ?: return
        val session = guidanceSession ?: return
        if (current.pendingDecision == null) return
        logEvent("event=guidance_start_decision decision=${decision.name.lowercase()}")
        guidanceProgress =
            when (decision) {
                TrailGuidanceStartDecision.START_HERE ->
                    guidanceProgress.copy(
                        startReached = true,
                        distanceFromStartMeters =
                            current.guidance?.distanceFromStartMeters ?: guidanceProgress.distanceFromStartMeters,
                        stableMidRouteSampleCount = 0,
                        dismissedDecisionKey = null,
                    )
                TrailGuidanceStartDecision.REVERSE_ROUTE ->
                    guidanceProgress.copy(
                        reversed = !session.reversed,
                        startReached = false,
                        paused = false,
                        distanceFromStartMeters = null,
                        stableMidRouteSampleCount = 0,
                        dismissedDecisionKey = null,
                    )
            }
        guidanceSession = buildPhoneGuidanceSession(route, guidanceProgress)
        guidanceOffRoute = TrailGuidanceOffRouteConfirmationState()
        guidanceAlertTracker = TrailGuidanceAlertTracker()
        guidanceVoiceAlertedInstructionKey = null
        guidanceVoiceFailureLoggedInstructionKey = null
        persistGuidanceProgress()
        val state = computeTrailGuidanceState(guidanceSession, null, guidanceTuning)
        publishGuidance(
            current.copy(
                session = guidanceSession,
                guidance = state,
                paused = false,
                pendingDecision = null,
                message = PhoneGuidanceMessage.ACTIVE,
            ),
        )
        updateGuidanceGpsInterval(state, null)
        configureLocationUpdates()
    }

    private fun dismissGuidanceDecision() {
        serviceScope.launch {
            locationLock.withLock {
                val current = PhoneGuidanceSessionStore.state.value
                val decision = current.pendingDecision ?: return@withLock
                guidanceProgress =
                    guidanceProgress.copy(
                        dismissedDecisionKey = "${guidanceSession?.trackId}:${guidanceSession?.reversed}:$decision",
                    )
                persistGuidanceProgress()
                publishGuidance(current.copy(pendingDecision = null))
            }
        }
    }

    private fun processGuidanceLocation(
        location: Location,
        generation: Long,
        deliveryIntervalMillis: Long,
    ) {
        if (generation != activeLocationGeneration) return
        val route = guidanceRoute ?: return
        val current = PhoneGuidanceSessionStore.state.value
        if (current.session == null || current.paused || current.guidance?.mode == TrailGuidanceMode.FINISHED) return
        val elapsedMillis = location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND
        if (elapsedMillis <= 0L || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (!isPhoneGuidanceFixFresh(elapsedMillis, SystemClock.elapsedRealtime(), deliveryIntervalMillis)) return
        val previousFixElapsedMillis = guidanceLastFixElapsedRealtimeMillis
        if (previousFixElapsedMillis?.let { elapsedMillis <= it } == true) return
        guidanceLastFixElapsedRealtimeMillis = elapsedMillis
        scheduleGuidanceLocationExpiry(generation, deliveryIntervalMillis)
        val measuredDeliveryIntervalMillis =
            previousFixElapsedMillis?.let { elapsedMillis - it } ?: deliveryIntervalMillis
        val fix =
            PhoneMapLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f },
                fixElapsedRealtimeMillis = elapsedMillis,
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
            )
        publish(PhoneRecordingSessionStore.state.value.copy(latestLocation = fix))

        var session = guidanceSession ?: buildPhoneGuidanceSession(route, guidanceProgress)
        val point = GeoPoint(location.latitude, location.longitude)
        if (!session.startReached && isGuidanceStartReached(session, point, guidanceTuning)) {
            guidanceProgress = guidanceProgress.copy(startReached = true)
            session = session.copy(startReached = true)
            guidanceSession = session
        }
        val computed =
            computeTrailGuidanceState(
                session = session,
                currentLocation = point,
                tuning = guidanceTuning,
                previousDistanceFromStartMeters = guidanceProgress.distanceFromStartMeters,
            )
        val confirmation =
            updateGuidanceOffRouteConfirmation(
                previous = guidanceOffRoute,
                distanceToRouteMeters = computed.distanceToRouteMeters,
                thresholdMeters = guidanceTuning.offRouteDistanceMeters,
                allowOffRouteEntry = true,
            )
        guidanceOffRoute = confirmation
        val etaProjection = guidanceEtaProjection(route, session)
        val eta =
            if (computed.mode == TrailGuidanceMode.FOLLOW_ROUTE) {
                etaProjection?.remainingSecondsAtDistance(
                    session.cumulativeDistancesMeters,
                    computed.distanceFromStartMeters,
                )
            } else {
                null
            }
        val guidance =
            computed.copy(
                offRoute = confirmation.offRoute,
                estimatedRemainingSeconds = eta,
                currentAltitudeMeters = fix.altitudeMeters,
                alertSessionKey = "${session.trackId}:${session.reversed}",
                alertGpsDeliveryIntervalMs = deliveryIntervalMillis,
            )
        val feedbackEvent = phoneGuidanceFeedbackEvent(current.guidance, guidance)
        val startDecision =
            evaluateTrailGuidanceStartDecision(
                session = session,
                location = point,
                locationAccuracyMeters = fix.accuracyMeters,
                hasFreshLocationFix = true,
                previousStableMidRouteSampleCount = guidanceProgress.stableMidRouteSampleCount,
                routeStartBehavior = PHONE_GUIDANCE_ROUTE_START_BEHAVIOR,
                reverseSuggestionMode = PHONE_GUIDANCE_REVERSE_SUGGESTION_MODE,
                tuning = guidanceTuning,
            )
        guidanceProgress =
            guidanceProgress.copy(
                distanceFromStartMeters = guidance.distanceFromStartMeters ?: guidanceProgress.distanceFromStartMeters,
                stableMidRouteSampleCount = startDecision.stableMidRouteSampleCount,
                offRoute = confirmation.offRoute,
                outsideSampleCount = confirmation.outsideSampleCount,
                recoverySampleCount = confirmation.recoverySampleCount,
            )
        val pendingDecision =
            startDecision.decision
                ?.takeUnless { decision ->
                    guidanceProgress.dismissedDecisionKey == "${session.trackId}:${session.reversed}:$decision"
                }?.let { decision ->
                    if (decision == TrailGuidanceStartDecision.REVERSE_ROUTE) {
                        PhoneGuidanceDecision.REVERSE_ROUTE
                    } else {
                        PhoneGuidanceDecision.START_HERE
                    }
                }
        if (pendingDecision != null && pendingDecision != current.pendingDecision) {
            logEvent("event=guidance_start_decision decision=${pendingDecision.name.lowercase()} source=automatic")
        }
        val sampleInterval = measuredDeliveryIntervalMillis.takeIf { it > 0L } ?: deliveryIntervalMillis
        val feedbackSettings = guidanceFeedbackPreferences.load()
        val voiceInstruction = guidance.nextInstruction
        val voiceInstructionKey =
            phoneGuidanceVoiceInstructionKey(
                guidance = guidance,
                paused = current.paused,
                voiceEnabled = feedbackSettings.voiceEnabled,
                speedMps = location.speed.takeIf { location.hasSpeed() && it.isFinite() && it > 0f },
                activityProfile = route.activityProfile,
                turnAlertsMode = PHONE_GUIDANCE_TURN_ALERTS_MODE,
            )
        if (voiceInstruction != null &&
            voiceInstructionKey != null &&
            voiceInstructionKey != guidanceVoiceAlertedInstructionKey
        ) {
            val speakResult = speakGuidanceTurn(voiceInstruction.command)
            if (speakResult == TextToSpeech.SUCCESS) {
                guidanceVoiceAlertedInstructionKey = voiceInstructionKey
                guidanceVoiceFailureLoggedInstructionKey = null
                logEvent("event=guidance_alert outcome=delivered reason=turn_distance channel=voice")
            } else if (guidanceVoiceFailureLoggedInstructionKey != voiceInstructionKey) {
                guidanceVoiceFailureLoggedInstructionKey = voiceInstructionKey
                val reason = if (speakResult == null) "tts_unavailable" else "tts_rejected"
                logEvent("event=guidance_alert outcome=suppressed reason=$reason channel=voice retry=eligible")
            }
        }
        val alertEvents =
            guidanceAlertTracker.update(
                TrailGuidanceAlertSample(
                    routeKey = session.trackId + if (session.reversed) ":reverse" else ":forward",
                    active = !current.paused && guidance.mode == TrailGuidanceMode.FOLLOW_ROUTE,
                    mode = guidance.mode,
                    offRoute = confirmation.offRoute,
                    instruction = guidance.nextInstruction,
                    distanceToInstructionMeters = guidance.distanceToInstructionMeters,
                    distanceFromStartMeters = guidance.distanceFromStartMeters,
                    speedMps = location.speed.takeIf { location.hasSpeed() && it.isFinite() && it > 0f },
                    activityProfile = route.activityProfile,
                    gpsDeliveryIntervalMs = sampleInterval.coerceAtLeast(1L),
                    hapticsEnabled = feedbackSettings.hapticsEnabled,
                    turnAlertsMode = PHONE_GUIDANCE_TURN_ALERTS_MODE,
                    fixTimestampMs = elapsedMillis,
                ),
            )
        alertEvents.forEach { event ->
            val reason = event.reason ?: event.trigger?.telemetryValue ?: event.outcome.telemetryValue
            when {
                event.outcome != TrailGuidanceAlertOutcome.FIRED ->
                    logEvent("event=guidance_alert outcome=suppressed channel=haptics reason=$reason")
                !shouldAlertForTurn(PHONE_GUIDANCE_TURN_ALERTS_MODE, event.instruction.command) ->
                    logEvent("event=guidance_alert outcome=suppressed channel=haptics reason=turn_mode")
                event.instructionKey == guidanceProgress.lastAlertInstructionKey ->
                    logEvent("event=guidance_alert outcome=suppressed channel=haptics reason=already_delivered")
                else -> {
                    if (feedbackSettings.hapticsEnabled) vibrateForGuidanceTurn()
                    guidanceProgress = guidanceProgress.copy(lastAlertInstructionKey = event.instructionKey)
                    val hapticsAvailable = vibrator?.hasVibrator() == true
                    val outcome = if (hapticsAvailable) "delivered" else "suppressed"
                    val deliveryReason = if (hapticsAvailable) reason else "haptics_unavailable"
                    logEvent(
                        "event=guidance_alert outcome=$outcome reason=$deliveryReason " +
                            "channel=haptics",
                    )
                }
            }
        }
        val message =
            when {
                guidance.mode == TrailGuidanceMode.FINISHED -> PhoneGuidanceMessage.FINISHED
                current.paused -> PhoneGuidanceMessage.PAUSED
                else -> PhoneGuidanceMessage.ACTIVE
            }
        publishGuidance(
            current.copy(
                session = session,
                guidance = guidance,
                paused = false,
                pendingDecision = pendingDecision,
                message = message,
            ),
        )
        handleGuidanceFeedback(feedbackEvent)
        persistGuidanceProgress()
        val nextInterval =
            resolveTrailGuidanceGpsIntervalMs(
                screenOn = screenIsOn,
                state = guidance,
                currentSpeedMps = location.speed.takeIf { location.hasSpeed() },
                activityProfile = route.activityProfile,
            )
        if (nextInterval != guidanceRequestedIntervalMillis) {
            guidanceRequestedIntervalMillis = nextInterval
            guidanceCadenceJob?.cancel()
            guidanceCadenceJob =
                serviceScope.launch {
                    delay(GUIDANCE_CADENCE_RECONFIGURE_DELAY_MS)
                    locationLock.withLock { configureLocationUpdates() }
                }
        }
        if (guidance.mode == TrailGuidanceMode.FINISHED) configureLocationUpdates()
    }

    private fun publishGuidance(state: PhoneGuidanceUiState) {
        PhoneGuidanceSessionStore.publish(state)
        updateNotificationIfActive()
    }

    private fun publishLocationFailure(
        recordingMessage: PhoneRecordingMessage,
        guidanceMessage: PhoneGuidanceMessage,
    ) {
        publishGuidanceLocationUnavailable(guidanceMessage)
        publish(PhoneRecordingSessionStore.state.value.copy(message = recordingMessage))
    }

    private fun scheduleGuidanceLocationExpiry(
        generation: Long,
        deliveryIntervalMillis: Long,
    ) {
        guidanceLocationExpiryJob?.cancel()
        val current = PhoneGuidanceSessionStore.state.value
        if (current.session == null || current.paused || current.guidance?.mode == TrailGuidanceMode.FINISHED) return
        val referenceMillis = guidanceLastFixElapsedRealtimeMillis ?: SystemClock.elapsedRealtime()
        guidanceLocationExpiryJob =
            schedulePhoneGuidanceLocationExpiry(
                scope = serviceScope,
                referenceElapsedRealtimeMillis = referenceMillis,
                deliveryIntervalMillis = deliveryIntervalMillis,
                nowElapsedRealtimeMillis = SystemClock::elapsedRealtime,
            ) {
                locationLock.withLock {
                    if (generation == activeLocationGeneration &&
                        !isPhoneGuidanceFixFresh(
                            guidanceLastFixElapsedRealtimeMillis ?: referenceMillis,
                            SystemClock.elapsedRealtime(),
                            deliveryIntervalMillis,
                        )
                    ) {
                        publishGuidanceLocationUnavailable(PhoneGuidanceMessage.GPS_UNAVAILABLE)
                    }
                }
            }
    }

    private fun publishGuidanceLocationUnavailable(message: PhoneGuidanceMessage) {
        val currentGuidance = PhoneGuidanceSessionStore.state.value
        val waitingGuidance = currentGuidance.withLocationUnavailable(message)
        if (waitingGuidance != currentGuidance) {
            guidanceOffRouteFeedbackJob?.cancel()
            guidanceOffRouteFeedbackJob = null
            guidanceSpeech?.stop()
            publishGuidance(waitingGuidance)
            logEvent("event=guidance_location_unavailable reason=${message.name.lowercase()}")
        }
    }

    private fun publishGuidanceFailure(message: PhoneGuidanceMessage) {
        PhoneGuidanceSessionStore.publish(PhoneGuidanceUiState(message = message))
        updateNotificationIfActive()
    }

    private fun persistGuidanceProgress() {
        persistGuidanceProgressSafely(
            write = { guidanceDraftStore.saveProgress(guidanceProgress) },
            onFailure = { logGuidancePersistenceFailure("save_progress") },
        )
    }

    private fun logGuidancePersistenceFailure(operation: String) {
        logEvent("event=guidance_persistence_failed operation=$operation")
    }

    private fun guidanceEtaProjection(
        route: PhoneGuidanceRouteDraft,
        session: TrailGuidanceSession,
    ): TrailGuidanceEtaProjection? {
        val key = GuidanceEtaProjectionKey(session.trackId, session.reversed, session.trackPoints, route.etaConfig)
        if (key != guidanceEtaProjectionKey) {
            guidanceEtaProjectionKey = key
            guidanceEtaProjection =
                buildTrailGuidanceEtaProjection(
                    session.trackPoints,
                    session.cumulativeDistancesMeters,
                    route.etaConfig,
                )
        }
        return guidanceEtaProjection
    }

    private fun updateGuidanceGpsInterval(
        state: TrailGuidanceState,
        speedMps: Float?,
    ) {
        val route = guidanceRoute ?: return
        guidanceRequestedIntervalMillis =
            resolveTrailGuidanceGpsIntervalMs(
                screenOn = screenIsOn,
                state = state,
                currentSpeedMps = speedMps,
                activityProfile = route.activityProfile,
            )
    }

    private fun handleGuidanceFeedback(event: PhoneGuidanceFeedbackEvent?) {
        when (event) {
            PhoneGuidanceFeedbackEvent.OFF_ROUTE -> {
                logGuidanceFeedbackTransition("off_route")
                startOffRouteFeedback()
            }
            PhoneGuidanceFeedbackEvent.BACK_ON_ROUTE -> {
                guidanceOffRouteFeedbackJob?.cancel()
                guidanceOffRouteFeedbackJob = null
                val settings = guidanceFeedbackPreferences.load()
                logGuidanceFeedbackTransition("back_on_route", settings)
                if (settings.hapticsEnabled) vibrateForGuidanceRecovery()
                if (settings.voiceEnabled) speakGuidanceFeedback(R.string.phone_guidance_speech_back_on_route)
            }
            PhoneGuidanceFeedbackEvent.ARRIVAL -> {
                guidanceOffRouteFeedbackJob?.cancel()
                guidanceOffRouteFeedbackJob = null
                val settings = guidanceFeedbackPreferences.load()
                logGuidanceFeedbackTransition("arrival", settings)
                if (settings.hapticsEnabled) vibrateForGuidanceArrival()
                if (settings.voiceEnabled) speakGuidanceFeedback(R.string.phone_guidance_speech_arrived)
            }
            null -> Unit
        }
    }

    private fun startOffRouteFeedback() {
        if (guidanceOffRouteFeedbackJob?.isActive == true) return
        guidanceOffRouteFeedbackJob =
            serviceScope.launch {
                while (true) {
                    locationLock.withLock {
                        val current = PhoneGuidanceSessionStore.state.value
                        if (current.session == null ||
                            current.paused ||
                            current.guidance?.offRoute != true
                        ) {
                            return@launch
                        }
                        if (!isPhoneGuidanceFixFresh(
                                guidanceLastFixElapsedRealtimeMillis,
                                SystemClock.elapsedRealtime(),
                                locationDeliveryIntervalMillis,
                            )
                        ) {
                            publishGuidanceLocationUnavailable(PhoneGuidanceMessage.GPS_UNAVAILABLE)
                            return@launch
                        }
                        val settings = guidanceFeedbackPreferences.load()
                        if (settings.hapticsEnabled) vibrateForGuidanceOffRoute()
                        if (settings.voiceEnabled) speakGuidanceFeedback(R.string.phone_guidance_speech_off_route)
                        logGuidanceFeedbackDelivery("off_route_repeat", settings)
                    }
                    delay(GUIDANCE_OFF_ROUTE_REPEAT_SECONDS * 1_000L)
                }
            }
    }

    private fun logGuidanceFeedbackTransition(
        transition: String,
        settings: PhoneGuidanceFeedbackSettings = guidanceFeedbackPreferences.load(),
    ) {
        val channels = guidanceFeedbackChannels(settings)
        val outcome = if (channels.isEmpty()) "suppressed" else "delivered"
        val reason = if (channels.isEmpty()) guidanceFeedbackSuppressionReason(settings) else "transition"
        logEvent(
            "event=guidance_feedback transition=$transition outcome=$outcome reason=$reason " +
                "channels=${channels.ifEmpty { listOf("none") }.joinToString(",")}",
        )
    }

    private fun logGuidanceFeedbackDelivery(
        source: String,
        settings: PhoneGuidanceFeedbackSettings,
    ) {
        val channels = guidanceFeedbackChannels(settings)
        val outcome = if (channels.isEmpty()) "suppressed" else "delivered"
        val reason = if (channels.isEmpty()) guidanceFeedbackSuppressionReason(settings) else source
        logEvent(
            "event=guidance_alert outcome=$outcome reason=$reason " +
                "channels=${channels.ifEmpty { listOf("none") }.joinToString(",")}",
        )
    }

    private fun guidanceFeedbackChannels(settings: PhoneGuidanceFeedbackSettings): List<String> =
        buildList {
            if (settings.hapticsEnabled && vibrator?.hasVibrator() == true) add("haptics")
            if (settings.voiceEnabled && guidanceSpeechReady) add("voice")
        }

    private fun guidanceFeedbackSuppressionReason(settings: PhoneGuidanceFeedbackSettings): String =
        when {
            !settings.hapticsEnabled && !settings.voiceEnabled -> "feedback_disabled"
            settings.hapticsEnabled && vibrator?.hasVibrator() != true -> "haptics_unavailable"
            settings.voiceEnabled && !guidanceSpeechReady -> "voice_unavailable"
            else -> "feedback_unavailable"
        }

    private fun vibrateForGuidanceRecovery() {
        vibrator?.vibrate(VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateForGuidanceOffRoute() {
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 100L, 70L, 100L, 70L, 100L, 70L, 100L, 70L, 220L), -1),
        )
    }

    private fun vibrateForGuidanceArrival() {
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 90L, 70L, 90L, 70L, 180L), -1))
    }

    private fun speakGuidanceFeedback(resource: Int) {
        if (!guidanceSpeechReady) return
        guidanceSpeech?.speak(getString(resource), TextToSpeech.QUEUE_FLUSH, null, "phone-guidance-feedback")
    }

    private fun vibrateForGuidanceTurn() {
        val activeVibrator = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeVibrator.vibrate(VibrationEffect.createOneShot(85L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            activeVibrator.vibrate(85L)
        }
    }

    private fun speakGuidanceTurn(command: TrailGuidanceCommand): Int? {
        if (!guidanceSpeechReady) return null
        val speech = guidanceSpeech ?: return null
        val resource =
            when (command) {
                TrailGuidanceCommand.SLIGHT_LEFT -> R.string.phone_guidance_speech_slight_left
                TrailGuidanceCommand.LEFT -> R.string.phone_guidance_speech_left
                TrailGuidanceCommand.SHARP_LEFT -> R.string.phone_guidance_speech_sharp_left
                TrailGuidanceCommand.SLIGHT_RIGHT -> R.string.phone_guidance_speech_slight_right
                TrailGuidanceCommand.RIGHT -> R.string.phone_guidance_speech_right
                TrailGuidanceCommand.SHARP_RIGHT -> R.string.phone_guidance_speech_sharp_right
                else -> R.string.phone_guidance_speech_continue
            }
        return speech.speak(getString(resource), TextToSpeech.QUEUE_FLUSH, null, "phone-guidance-turn")
    }

    // Keep the established acceptance order intact; these early exits correspond to distinct fix gates.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun processLocation(
        location: Location,
        generation: Long,
        requestedIntervalMillis: Long,
        deliveryIntervalMillis: Long = requestedIntervalMillis,
        terminalRefresh: Boolean = false,
    ) {
        if (generation != activeLocationGeneration) return
        processGuidanceLocation(location, generation, deliveryIntervalMillis)
        val phase = sessionLifecycle.state
        val canMonitorAutoPause = canMonitorAutoResume(phase)
        if ((!phase.acceptsFixes && !canMonitorAutoPause) || requestedIntervalMillis == Long.MAX_VALUE) return
        val elapsedMillis = location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND
        if (elapsedMillis <= 0L) {
            reject("invalid_timestamp")
            return
        }
        var previousElapsed = lastAcceptedElapsedRealtimeMillis
        if (previousElapsed != null && elapsedMillis <= previousElapsed) {
            reject("older_fix")
            return
        }
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
            reject("invalid_coordinates")
            return
        }
        val callbackTiming = callbackGapTracker.observeCallback(elapsedMillis)
        val requiredInterval = acceptedSampleThresholdMillis(requestedIntervalMillis)
        if (!terminalRefresh && previousElapsed != null && elapsedMillis - previousElapsed < requiredInterval) {
            reject("cadence")
            return
        }
        val fix =
            PhoneMapLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f },
                fixElapsedRealtimeMillis = elapsedMillis,
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
            )
        var current = PhoneRecordingSessionStore.state.value
        publish(current.copy(latestLocation = fix))
        current = current.copy(latestLocation = fix)
        val profileId = profile.storageValue
        val timeMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val accuracyProvenance = recordingAccuracyProvenance(fix.accuracyMeters, watchGpsAccuracyFloorEligible = false)
        val preliminary =
            RecordingPoint(
                location = GeoPoint(location.latitude, location.longitude),
                elevationMeters = fix.altitudeMeters,
                timeMillis = timeMillis,
                accuracyMeters = fix.accuracyMeters,
                speedMps = location.speed.takeIf { location.hasSpeed() && it.isFinite() && it >= 0f },
                effectiveAccuracyMeters = accuracyProvenance.effectiveAccuracyMeters,
                accuracyInterpretation = accuracyProvenance.interpretation,
            )
        val priorPoint = current.points.lastOrNull()
        if (current.session.phase == RecordingSessionPhase.PAUSED) {
            if (!tryAutoResume(current, preliminary, elapsedMillis)) return
            current = PhoneRecordingSessionStore.state.value
            previousElapsed = null
        }
        if (shouldAutoPause(current, preliminary, elapsedMillis)) return

        val motion =
            movementGate.evaluate(
                previous = priorPoint,
                candidate =
                    RecordingMotionSample(
                        location = preliminary.location,
                        elapsedRealtimeMillis = elapsedMillis,
                        accuracyMeters = preliminary.accuracyMeters,
                        speedMps = preliminary.speedMps,
                        speedAccuracyMps =
                            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
                        stepCount = latestStepCount,
                        cadenceSpm = null,
                        delayedDelivery =
                            RecordingDelayedDeliveryEvidence(
                                acceptedPointGapMillis = previousElapsed?.let { elapsedMillis - it } ?: -1L,
                                callbackGapMillis = callbackTiming.callbackGapMillis,
                                expectedIntervalMillis = callbackTiming.expectedIntervalMillis,
                            ),
                    ),
                activityProfile = profileId,
                previousFilterAccuracyMeters = priorPoint?.effectiveAccuracyMeters,
            )
        if (!motion.accepted && current.session.pendingSegmentStartReason == null) {
            reject("movement_${motion.reason.name.lowercase()}")
            return
        }

        val quality =
            fixQualityGate.evaluate(
                RecordingFixSample(
                    location = preliminary.location,
                    timeMillis = timeMillis,
                    elapsedRealtimeMillis = elapsedMillis,
                    accuracyMeters = preliminary.effectiveAccuracyMeters,
                    speedMps = preliminary.speedMps,
                    speedAccuracyMps =
                        if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
                ),
                activityProfile = profileId,
            )
        if (!quality.accepted) {
            reject("quality_${quality.reason.name.lowercase()}")
            return
        }

        val continuityGap =
            previousElapsed?.let { previous ->
                (elapsedMillis - previous).takeIf { gap -> gap >= continuityGapThreshold(requestedIntervalMillis) }
            }
        val startsNewSegment =
            continuityGap != null &&
                shouldStartRecordingGpsGapSegment(
                    previous = priorPoint,
                    current = preliminary,
                    continuityGapMillis = continuityGap,
                    activityProfile = profileId,
                )

        val segmentReason =
            if (startsNewSegment) {
                RecordingSegmentStartReason.GPS_GAP
            } else {
                current.session.pendingSegmentStartReason
            }
        val elevation = resolveElevation(location, elapsedMillis, startsNewSegment || segmentReason != null)
        val point =
            preliminary.copy(
                elevationMeters = elevation.elevationMeters,
                elevationSource = elevation.elevationSource,
                barometricPressureHpa = elevationFilter.diagnostics().pressure.lastFilteredPressureHpa,
                startsNewSegment = startsNewSegment,
                segmentStartReason = segmentReason,
            )
        val append =
            appendRecordingSessionPoint(
                existingPoints = current.points,
                point = point,
                options = settings.smoothingOptions(profile, requestedIntervalMillis),
                context =
                    RecordingSessionAppendContext(
                        elapsedRealtimeMillis = elapsedMillis,
                        previousElapsedRealtimeMillis = previousElapsed,
                        pendingSegmentStartReason = current.session.pendingSegmentStartReason,
                    ),
            ) ?: run {
                reject("stale_append")
                return
            }
        val distanceSegment =
            distanceGeometry.append(
                current = append.point.copy(accuracyMeters = append.point.effectiveAccuracyMeters),
                isContinuityRecovery = continuityGap != null || quality.reason.name == "CONFIRMED_RELOCATION",
                activityProfile = profileId,
                sampleIntervalSeconds = settings.sampleIntervalSeconds,
            )
        val estimate =
            distanceSegment?.let { segment ->
                estimateRecordingDistanceDelta(
                    RecordingDistanceInput(
                        geometricDeltaMeters = segment.geometricDeltaMeters,
                        previous = segment.previous,
                        current = segment.current,
                        elapsedSincePreviousMs = segment.elapsedSincePreviousMs,
                        activityProfile = profileId,
                        isContinuityRecovery = segment.isContinuityRecovery,
                    ),
                )
            }
        if (previousElapsed != null && current.session.pendingSegmentStartReason == null) {
            gpsActiveDurationMillis +=
                minOf(
                    (elapsedMillis - previousElapsed).coerceAtLeast(0L),
                    requestedIntervalMillis.coerceIn(1_000L, 15_000L),
                )
        }
        lastAcceptedElapsedRealtimeMillis = append.nextLastElapsedRealtimeMillis
        counters = counters.copy(accepted = counters.accepted + 1)
        val nextLifecycle =
            if (append.nextPendingSegmentStartReason != current.session.pendingSegmentStartReason) {
                sessionLifecycle.setPendingSegmentStart(append.nextPendingSegmentStartReason)
            } else {
                sessionLifecycle.state
            }
        val nextDistance = current.distanceMeters + (estimate?.distanceMeters ?: 0.0)
        publish(
            current.copy(
                session = nextLifecycle,
                points = append.canonical.points,
                pointsRevision = current.pointsRevision + 1L,
                pointsBaseRevision = current.pointsRevision,
                pointsChangedFromIndex = append.canonical.firstChangedPointIndex,
                segmentStartIndices =
                    if (append.point.startsNewSegment) {
                        current.segmentStartIndices + append.canonical.points.lastIndex
                    } else {
                        current.segmentStartIndices
                    },
                distanceMeters = nextDistance,
                latestLocation = fix,
                message = PhoneRecordingMessage.RECORDING,
            ),
        )
        persistDraftSoon()
        triggerProgressVibration(nextDistance)
    }

    private fun startTimeVibrationTimer() {
        timeVibrationJob?.cancel()
        timeVibrationJob = null
        if (!settings.progressVibrationSettings().timeEnabled || !sessionLifecycle.state.acceptsFixes) return
        timeVibrationJob =
            serviceScope.launch {
                while (sessionLifecycle.state.acceptsFixes) {
                    val waitMillis =
                        locationLock.withLock {
                            val state = PhoneRecordingSessionStore.state.value
                            if (state.session.acceptsFixes) {
                                triggerProgressVibration(state.distanceMeters)
                                vibrationTracker.millisecondsUntilNextTimeMilestone(
                                    state.session.elapsedMillis(System.currentTimeMillis()),
                                )
                            } else {
                                null
                            }
                        }
                    delay(waitMillis ?: return@launch)
                }
            }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun shouldAutoPause(
        current: PhoneRecordingUiState,
        point: RecordingPoint,
        elapsedMillis: Long,
    ): Boolean {
        if (!current.session.acceptsFixes ||
            settings.autoPauseMode(profile) == RecordingSettings.RECORDING_AUTO_PAUSE_OFF
        ) {
            return false
        }
        val started = current.session.startedAtMillis ?: return false
        if (System.currentTimeMillis() - started < AUTO_PAUSE_GRACE_MILLIS || current.points.size < 2) return false
        val bike = profile == PhoneActivityProfile.BIKE
        val accuracy =
            point.accuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: run {
                stationarySinceElapsedMillis = null
                return false
            }
        if (accuracy > (if (bike) BIKE_AUTO_PAUSE_MAX_ACCURACY else HIKE_AUTO_PAUSE_MAX_ACCURACY)) {
            stationarySinceElapsedMillis = null
            return false
        }
        val previous = current.points.lastOrNull() ?: return false
        val distance = recordingHaversineMeters(previous.location, point.location)
        val radius =
            max(
                if (bike) BIKE_STATIONARY_RADIUS else HIKE_STATIONARY_RADIUS,
                accuracy.toDouble() * AUTO_PAUSE_RADIUS_FACTOR,
            )
        val speed = point.speedMps
        val stationary =
            distance <= radius && (speed == null || speed <= if (bike) BIKE_STOP_SPEED else HIKE_STOP_SPEED)
        if (!stationary) {
            stationarySinceElapsedMillis = null
            return false
        }
        val stationarySince = stationarySinceElapsedMillis ?: elapsedMillis.also { stationarySinceElapsedMillis = it }
        val required = if (bike) BIKE_AUTO_PAUSE_DURATION else HIKE_AUTO_PAUSE_DURATION
        if (elapsedMillis - stationarySince < required) return false
        val wallNow = System.currentTimeMillis()
        val stationaryDurationMillis = (elapsedMillis - stationarySince).coerceAtLeast(0L)
        val effectivePauseStart =
            effectiveRecordingAutoPauseStartMillis(
                startedAtMillis = current.session.startedAtMillis,
                confirmationMillis = wallNow,
                stationaryDurationMillis = stationaryDurationMillis,
            )
        val state = sessionLifecycle.pause(effectivePauseStart, automatic = true)
        autoPauseStartedAtElapsedRealtimeMillis = elapsedMillis
        val finalized = flushTrackTail(current.copy(session = state))
        publish(finalized.copy(session = state, message = PhoneRecordingMessage.PAUSED))
        updateNotification()
        startLocationUpdates()
        scheduleAutoPauseCadenceChange()
        persistDraftNow()
        logEvent("session_auto_paused generation=${state.generation}")
        return true
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun tryAutoResume(
        current: PhoneRecordingUiState,
        point: RecordingPoint,
        elapsedMillis: Long,
    ): Boolean {
        if (settings.autoPauseMode(profile) == RecordingSettings.RECORDING_AUTO_PAUSE_OFF &&
            current.session.autoPaused
        ) {
            autoResumeRecording(current, movingDurationMillis = 0L)
            return true
        }
        val previous = current.points.lastOrNull() ?: return false
        val bike = profile == PhoneActivityProfile.BIKE
        val accuracy =
            point.accuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: run {
                movingSinceElapsedMillis = null
                return false
            }
        if (accuracy > (if (bike) BIKE_AUTO_PAUSE_MAX_ACCURACY else HIKE_AUTO_PAUSE_MAX_ACCURACY)) {
            movingSinceElapsedMillis = null
            return false
        }
        val moving =
            (point.speedMps ?: 0f) >= (if (bike) BIKE_RESUME_SPEED else HIKE_RESUME_SPEED) ||
                recordingHaversineMeters(previous.location, point.location) >=
                max(
                    if (bike) BIKE_RESUME_DISTANCE else HIKE_RESUME_DISTANCE,
                    accuracy.toDouble() * AUTO_PAUSE_RESUME_RADIUS_FACTOR,
                )
        if (!moving) {
            movingSinceElapsedMillis = null
            return false
        }
        val movingSince = movingSinceElapsedMillis ?: elapsedMillis.also { movingSinceElapsedMillis = it }
        if (elapsedMillis - movingSince < if (bike) BIKE_AUTO_RESUME_DURATION else HIKE_AUTO_RESUME_DURATION) {
            return false
        }
        val movingDurationMillis = (elapsedMillis - movingSince).coerceAtLeast(0L)
        autoResumeRecording(current, movingDurationMillis)
        movingSinceElapsedMillis = null
        return true
    }

    private fun autoResumeRecording(
        current: PhoneRecordingUiState,
        movingDurationMillis: Long,
    ) {
        val nowMillis = System.currentTimeMillis()
        val addedPausedMillis =
            recordingAutoPauseAddedMillisAtResume(
                pausedAtMillis = current.session.pausedAtMillis,
                confirmationMillis = nowMillis,
                movingDurationMillis = movingDurationMillis,
            )
        val resumeAtMillis = (current.session.pausedAtMillis ?: nowMillis) + addedPausedMillis
        val state = sessionLifecycle.resume(resumeAtMillis, automatic = true)
        clearAutoPauseCadence()
        fixQualityGate.reset()
        movementGate.reset()
        distanceGeometry.reset()
        lastAcceptedElapsedRealtimeMillis = null
        callbackGapTracker.reset()
        publish(current.copy(session = state, message = PhoneRecordingMessage.RECORDING))
        startLocationUpdates()
        updateNotification()
        persistDraftSoon()
        logEvent("session_auto_resumed generation=${state.generation}")
    }

    private var stationarySinceElapsedMillis: Long? = null
    private var movingSinceElapsedMillis: Long? = null

    // Keep local DEM/GPS fallback precedence explicit so missing sensors remain an offline-safe path.
    @Suppress("CyclomaticComplexMethod")
    private fun resolveElevation(
        location: Location,
        elapsedMillis: Long,
        startsNewSegment: Boolean,
    ) = runCatching {
        val source = settings.elevationSource
        val gps = location.altitude.takeIf { location.hasAltitude() && it.isFinite() }
        val demSample =
            if (source == RecordingSettings.RECORDING_ELEVATION_SOURCE_DEM ||
                source == RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO
            ) {
                elevationRepository.elevationSampleAt(location.latitude, location.longitude)
            } else {
                null
            }
        val dem = demSample?.elevationMeters
        val anchor = resolveRecordingAbsoluteElevation(source, dem, gps)
        elevationFilter.update(
            RecordingHybridElevationInput(
                demElevationMeters = dem,
                demAxisLen = demSample?.axisLen,
                gpsElevationMeters = gps,
                gpsVerticalAccuracyMeters =
                    if (location.hasVerticalAccuracy()) {
                        location.verticalAccuracyMeters.takeIf { it.isFinite() && it >= 0f }
                    } else {
                        null
                    },
                horizontalAccuracyMeters =
                    location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f },
                absoluteElevationMeters = anchor.elevationMeters,
                absoluteElevationSource = anchor.source,
                elapsedRealtimeMillis = elapsedMillis,
                enabled = source == RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO,
                startsNewSegment = startsNewSegment,
                activityProfile = profile.storageValue,
            ),
        )
    }.getOrElse {
        val anchor =
            resolveRecordingAbsoluteElevation(
                settings.elevationSource,
                demElevationMeters = null,
                gpsElevationMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
            )
        com.glancemap.trailcore.recording.RecordingHybridElevationResult(
            elevationMeters = anchor.elevationMeters,
            elevationSource = anchor.source,
            pressureUsed = false,
            pressureDeltaMeters = 0.0,
            absoluteAnchorCorrectionMeters = 0.0,
            barometerReason = "unavailable",
        )
    }

    private fun triggerProgressVibration(distanceMeters: Double) {
        val current = PhoneRecordingSessionStore.state.value
        val triggers =
            vibrationTracker.next(
                settings = settings.progressVibrationSettings(),
                distanceMeters = distanceMeters,
                activeDurationMillis = current.session.elapsedMillis(System.currentTimeMillis()),
            )
        if (
            triggers.any { trigger ->
                trigger is RecordingProgressVibrationTrigger.Distance ||
                    trigger is RecordingProgressVibrationTrigger.Time
            }
        ) {
            val vibrator = getSystemService(android.os.Vibrator::class.java) ?: return
            try {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(120L, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } catch (_: RuntimeException) {
                logEvent("vibration_failed category=platform")
            }
        }
    }

    private fun flushTrackTail(state: PhoneRecordingUiState): PhoneRecordingUiState {
        val track =
            flushCanonicalRecordingTail(state.points, settings.smoothingOptions(profile, effectiveIntervalMillis))
        val segment = distanceGeometry.flush()
        val delta =
            segment?.let { pending ->
                estimateRecordingDistanceDelta(
                    RecordingDistanceInput(
                        geometricDeltaMeters = pending.geometricDeltaMeters,
                        previous = pending.previous,
                        current = pending.current,
                        elapsedSincePreviousMs = pending.elapsedSincePreviousMs,
                        activityProfile = profile.storageValue,
                        isContinuityRecovery = pending.isContinuityRecovery,
                    ),
                ).distanceMeters
            } ?: 0.0
        val hasChangedPoints = track.firstChangedPointIndex < track.points.size
        return state.copy(
            points = track.points,
            pointsRevision = if (hasChangedPoints) state.pointsRevision + 1L else state.pointsRevision,
            pointsBaseRevision = if (hasChangedPoints) state.pointsRevision else state.pointsBaseRevision,
            pointsChangedFromIndex =
                if (hasChangedPoints) track.firstChangedPointIndex else state.pointsChangedFromIndex,
            distanceMeters = state.distanceMeters + delta,
        )
    }

    @Suppress("LongMethod", "NestedBlockDepth")
    private fun buildRecordedTraceSummary(
        state: PhoneRecordingUiState,
        points: List<RecordingPoint>,
    ): RecordedTraceSummary {
        val segmentLengths =
            DoubleArray((points.size - 1).coerceAtLeast(0)) { index ->
                if (points[index + 1].startsNewSegment) {
                    0.0
                } else {
                    recordingHaversineMeters(points[index].location, points[index + 1].location)
                }
            }
        val cumulativeDistances = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulativeDistances[index] = cumulativeDistances[index - 1] + segmentLengths[index - 1]
        }
        val (ascent, descent) =
            buildRecordingElevationProfileCumulative(
                points = points.map { RecordingElevationProfilePoint(it.elevationMeters, it.startsNewSegment) },
                segmentLengths = segmentLengths,
                cumulativeDistances = cumulativeDistances,
            )
        val now = System.currentTimeMillis()
        val activeDurationMillis = state.session.elapsedMillis(now)
        val totalDurationMillis = (now - (state.session.startedAtMillis ?: now)).coerceAtLeast(0L)
        val elevationDiagnostics = elevationFilter.diagnostics()
        val generalSettings = generalSettingsPreferences.load()
        val calories =
            estimateRecordingCalories(
                points = points,
                userWeightKg = generalSettings.userWeightKg,
                backpackWeightKg = generalSettings.backpackWeightKg,
                bikeWeightKg = generalSettings.bikeWeightKg,
                activityProfile = profile.storageValue,
            )
        return RecordedTraceSummary(
            activityProfile = profile.storageValue,
            durationSeconds = activeDurationMillis / 1_000.0,
            totalDurationSeconds = totalDurationMillis / 1_000.0,
            distanceMeters = state.distanceMeters,
            elevationGainMeters = ascent.lastOrNull() ?: 0.0,
            elevationLossMeters = descent.lastOrNull() ?: 0.0,
            currentElevationMeters = points.lastOrNull()?.elevationMeters,
            currentSpeedMps = points.lastOrNull()?.speedMps,
            averageSpeedMps =
                (state.distanceMeters / (activeDurationMillis / 1_000.0)).takeIf { activeDurationMillis > 0L },
            fastestSpeedMps = points.fastestRecordingPointSegmentSpeedMps(),
            fastestSpeedMethod = FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1,
            gpsAccuracyMeters = points.lastOrNull()?.accuracyMeters,
            pointCount = points.size,
            gpsActiveDurationSeconds = gpsActiveDurationMillis / 1_000.0,
            recordingGapCount =
                points.count { point -> point.segmentStartReason == RecordingSegmentStartReason.GPS_GAP },
            recordingMaxGapSeconds =
                points.zipWithNext().maxOfOrNull { (previous, current) ->
                    if (current.segmentStartReason == RecordingSegmentStartReason.GPS_GAP) {
                        (current.timeMillis - previous.timeMillis).coerceAtLeast(0L) / 1_000.0
                    } else {
                        0.0
                    }
                } ?: 0.0,
            caloriesGrossKcal = calories.grossKcal,
            caloriesActiveKcal = calories.activeKcal,
            caloriesRestingKcal = calories.restingKcal,
            calorieModel = calories.model,
            cyclingMechanicalKj = calories.cyclingMechanicalKj,
            cyclingPowerSampleSegments = calories.cyclingPowerSampleSegments,
            cyclingPhysicsSegments = calories.cyclingPhysicsSegments,
            heartRateBpm = null,
            averageHeartRateBpm = null,
            maxHeartRateBpm = null,
            stepCount = sessionStepCount,
            cadenceSpm = null,
            averageCadenceSpm = null,
            maxCadenceSpm = null,
            powerWatts = null,
            averagePowerWatts = null,
            maxPowerWatts = null,
            barometricPressureHpa = elevationDiagnostics.pressure.lastFilteredPressureHpa,
            recordingTrackSmoothingMode = settings.smoothingMode(profile),
            recordingDistanceSource = RecordingSettings.DEFAULT_RECORDING_DISTANCE_SOURCE,
            recordingTrackFilterVersion = com.glancemap.trailcore.recording.RECORDING_TRACK_FILTER_VERSION,
            recordingElevationFilterVersion = com.glancemap.trailcore.recording.RECORDING_ELEVATION_FILTER_VERSION,
            smartElevationPressurePointCount = elevationDiagnostics.pressureDeltaCount,
            smartElevationDemAnchorPointCount = elevationDiagnostics.demAnchorCount,
            smartElevationGpsFallbackPointCount = elevationDiagnostics.gpsAnchorCount,
        )
    }

    private fun registerRecordingSensors() {
        val settings = settings
        if (settings.elevationSource == RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO) {
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensor ->
                registerOptionalSensor(sensor)
            }
        }
        val canReadSteps =
            Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        if (canReadSteps) sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let(::registerOptionalSensor)
    }

    private fun registerOptionalSensor(sensor: Sensor) {
        try {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: RuntimeException) {
            logEvent("sensor_unavailable type=${sensor.type}")
        }
    }

    private fun unregisterRecordingSensors() {
        runCatching { sensorManager.unregisterListener(this) }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    private fun stopLocationUpdates(reason: String) {
        guidanceLocationExpiryJob?.cancel()
        guidanceLocationExpiryJob = null
        val generation = callbackGeneration.incrementAndGet()
        activeLocationGeneration = generation
        locationCallback?.let { callback -> runCatching { locationClient.removeLocationUpdates(callback) } }
        locationCallback = null
        publish(PhoneRecordingSessionStore.state.value)
        logEvent("location_subscription_stopped generation=$generation reason=$reason")
    }

    private fun publish(state: PhoneRecordingUiState) {
        val previousPhase = PhoneRecordingSessionStore.state.value.session.phase
        val published = state.copy(locationUpdatesActive = locationCallback != null)
        PhoneRecordingSessionStore.publish(published)
        updateNotificationIfActive(published)
        if (previousPhase != published.session.phase) startTimeVibrationTimer()
    }

    private fun persistDraftSoon() {
        if (!sessionLifecycle.state.isActive) return
        draftJob =
            schedulePhoneRecordingCheckpoint(serviceScope, draftJob, DRAFT_PERSIST_DELAY_MILLIS) {
                locationLock.withLock { persistDraftNow() }
            }
    }

    private suspend fun persistDraftNow() =
        draftLock.withLock {
            val current = PhoneRecordingSessionStore.state.value
            if (!current.session.isActive || current.session.phase == RecordingSessionPhase.SAVING) return@withLock
            val draft =
                PhoneRecordingDraft(
                    startedAtMillis = current.session.startedAtMillis ?: return@withLock,
                    pausedAtMillis = current.session.pausedAtMillis,
                    accumulatedPausedMillis = current.session.accumulatedPausedMillis,
                    paused = current.session.phase == RecordingSessionPhase.PAUSED,
                    autoPaused = current.session.autoPaused,
                    points = current.points,
                    lastAcceptedElapsedRealtimeMillis = lastAcceptedElapsedRealtimeMillis,
                    distanceMeters = current.distanceMeters,
                    settings = settings,
                    activityProfile = profile.storageValue,
                    recordingId = recordingId,
                    autoPauseStartedAtElapsedRealtimeMillis = autoPauseStartedAtElapsedRealtimeMillis,
                    gpsActiveDurationMillis = gpsActiveDurationMillis,
                    sessionStepCount = sessionStepCount,
                )
            try {
                withContext(Dispatchers.IO) { draftStore.save(draft) }
                logEvent("draft_saved points=${draft.points.size}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                logEvent("draft_save_failed category=draft_io")
            }
        }

    private fun resetProcessingState() {
        clearAutoPauseCadence()
        fixQualityGate.reset()
        movementGate.reset()
        callbackGapTracker.reset()
        distanceGeometry.reset()
        elevationFilter.reset()
        lastAcceptedElapsedRealtimeMillis = null
        stationarySinceElapsedMillis = null
        movingSinceElapsedMillis = null
        counters = PhoneRecordingCounters()
        gpsActiveDurationMillis = 0L
        sessionStepCount = null
        latestStepCount = null
        lastSensorStepCount = null
    }

    private fun scheduleAutoPauseCadenceChange() {
        autoPauseCadenceJob?.cancel()
        autoPauseCadenceJob = null
        val startedAt = autoPauseStartedAtElapsedRealtimeMillis ?: return
        val generation = sessionLifecycle.state.generation
        val remainingMillis =
            (
                RecordingSettings.AUTO_PAUSE_PROLONGED_AFTER_MS -
                    (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            ).coerceAtLeast(0L)
        if (remainingMillis == 0L) return
        autoPauseCadenceJob =
            serviceScope.launch {
                delay(remainingMillis)
                locationLock.withLock {
                    val state = sessionLifecycle.state
                    if (state.generation == generation &&
                        canMonitorAutoResume(state)
                    ) {
                        configureLocationUpdates()
                    }
                }
            }
    }

    private fun clearAutoPauseCadence() {
        autoPauseCadenceJob?.cancel()
        autoPauseCadenceJob = null
        autoPauseStartedAtElapsedRealtimeMillis = null
    }

    private fun startSummaryTimer() {
        summaryJob?.cancel()
        summaryJob =
            serviceScope.launch {
                while (sessionLifecycle.state.isActive) {
                    delay(DIAGNOSTIC_SUMMARY_INTERVAL_MILLIS)
                    val snapshot = counters
                    if (snapshot.total > 0) {
                        logEvent(
                            "fix_summary accepted=${snapshot.accepted} rejected_cadence=${snapshot.cadence} " +
                                "rejected_invalid=${snapshot.invalid} rejected_older=${snapshot.older} " +
                                "rejected_movement=${snapshot.movement} rejected_quality=${snapshot.quality} " +
                                "obsolete_callbacks=${snapshot.obsolete}",
                        )
                        counters = PhoneRecordingCounters()
                    }
                }
            }
    }

    private fun reject(category: String) {
        counters =
            when {
                category == "cadence" -> counters.copy(cadence = counters.cadence + 1)
                category == "invalid_timestamp" || category == "invalid_coordinates" ->
                    counters.copy(invalid = counters.invalid + 1)
                category == "older_fix" || category == "stale_append" -> counters.copy(older = counters.older + 1)
                category.startsWith("movement_") -> counters.copy(movement = counters.movement + 1)
                category.startsWith("quality_") -> counters.copy(quality = counters.quality + 1)
                else -> counters
            }
    }

    private fun acceptedSampleThresholdMillis(requestedIntervalMillis: Long): Long =
        max(
            MIN_ACCEPT_INTERVAL_MILLIS,
            requestedIntervalMillis - SAMPLE_INTERVAL_TOLERANCE_MILLIS,
        )

    private fun continuityGapThreshold(requestedIntervalMillis: Long): Long = max(MIN_CONTINUITY_GAP_MILLIS, requestedIntervalMillis * 2L)

    private fun startForegroundNotification(): Boolean {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification().build(), type)
            true
        } catch (_: RuntimeException) {
            logEvent("foreground_start_failed category=platform_or_permission")
            stopSelf()
            false
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification().build())
        } catch (_: RuntimeException) {
            logEvent("notification_update_failed category=platform_or_permission")
        }
    }

    private fun updateNotificationIfActive(state: PhoneRecordingUiState) {
        if (state.session.isActive) updateNotification()
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val state = PhoneRecordingSessionStore.state.value
        val isPaused = state.session.phase == RecordingSessionPhase.PAUSED
        val open =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivityMobile::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pauseResume =
            servicePendingIntent(
                if (isPaused) ACTION_RESUME else ACTION_PAUSE,
                if (isPaused) REQUEST_RESUME else REQUEST_PAUSE,
            )
        val save = servicePendingIntent(ACTION_SAVE, REQUEST_SAVE)
        val guidanceUi = PhoneGuidanceSessionStore.state.value
        if (guidanceUi.session != null || guidanceUi.message == PhoneGuidanceMessage.LOADING) {
            val guidancePaused = guidanceUi.paused
            val command = guidanceUi.guidance?.nextInstruction?.command
            val guidanceText =
                when {
                    guidanceUi.message == PhoneGuidanceMessage.LOADING -> getString(R.string.phone_guidance_loading)
                    guidanceUi.message == PhoneGuidanceMessage.GPS_UNAVAILABLE ->
                        getString(R.string.phone_guidance_notification_gps_unavailable)
                    guidanceUi.message == PhoneGuidanceMessage.PERMISSION_REQUIRED ->
                        getString(R.string.phone_guidance_notification_permission_required)
                    guidancePaused -> getString(R.string.phone_guidance_notification_paused)
                    guidanceUi.guidance?.mode == TrailGuidanceMode.FINISHED ->
                        getString(R.string.phone_guidance_notification_finished)
                    command != null -> getString(guidanceCommandResource(command))
                    else ->
                        getString(
                            R.string.phone_guidance_notification_active,
                            guidanceUi.session?.trackTitle.orEmpty(),
                        )
                }
            val builder =
                NotificationCompat
                    .Builder(this, GUIDANCE_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
                    .setContentTitle(getString(R.string.phone_guidance_notification_title))
                    .setContentText(guidanceText)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(open)
            if (guidanceUi.session != null) {
                builder
                    .addAction(
                        0,
                        getString(
                            if (guidancePaused) R.string.phone_guidance_notification_resume else R.string.phone_guidance_notification_pause,
                        ),
                        servicePendingIntent(
                            if (guidancePaused) ACTION_GUIDANCE_RESUME else ACTION_GUIDANCE_PAUSE,
                            if (guidancePaused) REQUEST_GUIDANCE_RESUME else REQUEST_GUIDANCE_PAUSE,
                        ),
                    ).addAction(
                        0,
                        getString(R.string.phone_guidance_notification_stop),
                        servicePendingIntent(ACTION_GUIDANCE_STOP, REQUEST_GUIDANCE_STOP),
                    )
            }
            if (state.session.isActive) builder.addAction(0, getString(R.string.phone_rec_save), save)
            return builder
        }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_companionapp_foreground)
            .setContentTitle(getString(R.string.phone_rec_notification_title))
            .setContentText(
                getString(
                    if (isPaused) R.string.phone_rec_notification_paused else R.string.phone_rec_notification_recording,
                ),
            ).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                0,
                getString(if (isPaused) R.string.common_action_resume else R.string.common_action_pause),
                pauseResume,
            ).addAction(0, getString(R.string.phone_rec_save), save)
    }

    private fun servicePendingIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PhoneRecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.phone_rec_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            createNotificationChannel(
                NotificationChannel(
                    GUIDANCE_CHANNEL_ID,
                    getString(R.string.phone_guidance_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun guidanceCommandResource(command: TrailGuidanceCommand): Int =
        when (command) {
            TrailGuidanceCommand.SLIGHT_LEFT -> R.string.phone_guidance_slight_left
            TrailGuidanceCommand.LEFT -> R.string.phone_guidance_left
            TrailGuidanceCommand.SHARP_LEFT -> R.string.phone_guidance_sharp_left
            TrailGuidanceCommand.SLIGHT_RIGHT -> R.string.phone_guidance_slight_right
            TrailGuidanceCommand.RIGHT -> R.string.phone_guidance_right
            TrailGuidanceCommand.SHARP_RIGHT -> R.string.phone_guidance_sharp_right
            TrailGuidanceCommand.FINISH -> R.string.phone_guidance_arrived
            TrailGuidanceCommand.CONTINUE -> R.string.phone_guidance_continue
        }

    private fun updateNotificationIfActive() {
        if (PhoneRecordingSessionStore.state.value.session.isActive ||
            PhoneGuidanceSessionStore.state.value.session != null
        ) {
            updateNotification()
        }
    }

    private fun logEvent(message: String) {
        PhoneDebugCapture.log(TAG, message)
    }

    private data class PhoneRecordingCounters(
        val accepted: Int = 0,
        val cadence: Int = 0,
        val invalid: Int = 0,
        val older: Int = 0,
        val movement: Int = 0,
        val quality: Int = 0,
        val obsolete: Int = 0,
    ) {
        val total get() = accepted + cadence + invalid + older + movement + quality + obsolete
    }

    private data class GuidanceEtaProjectionKey(
        val trackId: String,
        val reversed: Boolean,
        val trackPoints: List<TrailPoint>,
        val etaConfig: TrailGuidanceEtaConfig,
    )

    companion object {
        const val ACTION_START = "com.glancemap.glancemapcompanionapp.map.rec.START"
        const val ACTION_PAUSE = "com.glancemap.glancemapcompanionapp.map.rec.PAUSE"
        const val ACTION_RESUME = "com.glancemap.glancemapcompanionapp.map.rec.RESUME"
        const val ACTION_SAVE = "com.glancemap.glancemapcompanionapp.map.rec.SAVE"
        const val ACTION_DISCARD = "com.glancemap.glancemapcompanionapp.map.rec.DISCARD"
        const val ACTION_RESTORE = "com.glancemap.glancemapcompanionapp.map.rec.RESTORE"
        const val ACTION_GUIDANCE_START = "com.glancemap.glancemapcompanionapp.map.guidance.START"
        const val ACTION_GUIDANCE_PAUSE = "com.glancemap.glancemapcompanionapp.map.guidance.PAUSE"
        const val ACTION_GUIDANCE_RESUME = "com.glancemap.glancemapcompanionapp.map.guidance.RESUME"
        const val ACTION_GUIDANCE_RETRY = "com.glancemap.glancemapcompanionapp.map.guidance.RETRY"
        const val ACTION_GUIDANCE_STOP = "com.glancemap.glancemapcompanionapp.map.guidance.STOP"
        const val ACTION_GUIDANCE_ACCEPT_START_HERE = "com.glancemap.glancemapcompanionapp.map.guidance.START_HERE"
        const val ACTION_GUIDANCE_ACCEPT_REVERSE = "com.glancemap.glancemapcompanionapp.map.guidance.REVERSE"
        const val ACTION_GUIDANCE_DISMISS_DECISION = "com.glancemap.glancemapcompanionapp.map.guidance.DISMISS_DECISION"

        fun request(
            context: Context,
            action: String,
        ) {
            val intent = Intent(context, PhoneRecordingService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_GUIDANCE_START) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        internal fun nextRecordingCallbackGeneration(current: Long): Long = current + 1L

        private const val TAG = "PhoneRecording"
        private const val CHANNEL_ID = "phone_recording"
        private const val GUIDANCE_CHANNEL_ID = "phone_guidance"
        private const val NOTIFICATION_ID = 4203
        private const val REQUEST_OPEN = 4210
        private const val REQUEST_PAUSE = 4211
        private const val REQUEST_RESUME = 4212
        private const val REQUEST_SAVE = 4213
        private const val REQUEST_GUIDANCE_PAUSE = 4220
        private const val REQUEST_GUIDANCE_RESUME = 4221
        private const val REQUEST_GUIDANCE_STOP = 4222
        private const val DRAFT_PERSIST_DELAY_MILLIS = 30_000L
        private const val GUIDANCE_CADENCE_RECONFIGURE_DELAY_MS = 250L
        private const val GUIDANCE_OFF_ROUTE_REPEAT_SECONDS = 60L
        private const val DIAGNOSTIC_SUMMARY_INTERVAL_MILLIS = 30_000L
        private const val MIN_LOCATION_INTERVAL_MILLIS = 1_000L
        private const val MIN_ACCEPT_INTERVAL_MILLIS = 850L
        private const val SAMPLE_INTERVAL_TOLERANCE_MILLIS = 500L
        private const val MIN_CONTINUITY_GAP_MILLIS = 15_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MIN_SAVE_POINTS = 2
        private const val REBOOT_TIMESTAMP_TOLERANCE_MILLIS = 60_000L
        private const val AUTO_PAUSE_GRACE_MILLIS = RecordingSettings.AUTO_PAUSE_START_GRACE_MS
        private const val HIKE_AUTO_PAUSE_DURATION = RecordingSettings.AUTO_PAUSE_HIKE_STOP_DURATION_MS
        private const val BIKE_AUTO_PAUSE_DURATION = RecordingSettings.AUTO_PAUSE_BIKE_STOP_DURATION_MS
        private const val HIKE_AUTO_RESUME_DURATION = RecordingSettings.AUTO_PAUSE_HIKE_RESUME_DURATION_MS
        private const val BIKE_AUTO_RESUME_DURATION = RecordingSettings.AUTO_PAUSE_BIKE_RESUME_DURATION_MS
        private const val HIKE_STOP_SPEED = RecordingSettings.AUTO_PAUSE_HIKE_STOP_SPEED_MPS
        private const val BIKE_STOP_SPEED = RecordingSettings.AUTO_PAUSE_BIKE_STOP_SPEED_MPS
        private const val HIKE_RESUME_SPEED = RecordingSettings.AUTO_PAUSE_HIKE_RESUME_SPEED_MPS
        private const val BIKE_RESUME_SPEED = RecordingSettings.AUTO_PAUSE_BIKE_RESUME_SPEED_MPS
        private const val HIKE_AUTO_PAUSE_MAX_ACCURACY = RecordingSettings.AUTO_PAUSE_HIKE_MAX_ACCURACY_M
        private const val BIKE_AUTO_PAUSE_MAX_ACCURACY = RecordingSettings.AUTO_PAUSE_BIKE_MAX_ACCURACY_M
        private val HIKE_STATIONARY_RADIUS = RecordingSettings.AUTO_PAUSE_HIKE_STATIONARY_RADIUS_M.toDouble()
        private val BIKE_STATIONARY_RADIUS = RecordingSettings.AUTO_PAUSE_BIKE_STATIONARY_RADIUS_M.toDouble()
        private val HIKE_RESUME_DISTANCE = RecordingSettings.AUTO_PAUSE_HIKE_RESUME_DISTANCE_M.toDouble()
        private val BIKE_RESUME_DISTANCE = RecordingSettings.AUTO_PAUSE_BIKE_RESUME_DISTANCE_M.toDouble()
        private const val AUTO_PAUSE_RADIUS_FACTOR = RecordingSettings.AUTO_PAUSE_ACCURACY_RADIUS_FACTOR
        private const val AUTO_PAUSE_RESUME_RADIUS_FACTOR = RecordingSettings.AUTO_PAUSE_ACCURACY_RESUME_FACTOR
        private const val PHONE_GUIDANCE_ROUTE_START_BEHAVIOR = "GO_TO_START"
        private const val PHONE_GUIDANCE_REVERSE_SUGGESTION_MODE = "ASK"
        private const val PHONE_GUIDANCE_TURN_ALERTS_MODE = "ALL"
    }
}

internal object PhoneMapLocationContext {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
