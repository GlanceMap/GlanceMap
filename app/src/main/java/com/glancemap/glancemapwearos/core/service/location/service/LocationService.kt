package com.glancemap.glancemapwearos.core.service.location.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.location.adapters.FusedLocationGateway
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationGateway
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationSettingsPreflight
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateEvent
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateSink
import com.glancemap.glancemapwearos.core.service.location.adapters.PassiveExternalLocationGateway
import com.glancemap.glancemapwearos.core.service.location.adapters.WatchGpsAvailabilityReason
import com.glancemap.glancemapwearos.core.service.location.adapters.WatchGpsLocationGateway
import com.glancemap.glancemapwearos.core.service.location.adapters.WearPhoneConnectionProbe
import com.glancemap.glancemapwearos.core.service.location.config.AUTO_PAUSE_GPS_INTERVAL_MS
import com.glancemap.glancemapwearos.core.service.location.config.AUTO_PAUSE_PROLONGED_AFTER_MS
import com.glancemap.glancemapwearos.core.service.location.config.AUTO_PAUSE_PROLONGED_GPS_INTERVAL_MS
import com.glancemap.glancemapwearos.core.service.location.config.BIND_CACHED_FIX_MAX_ACCURACY_COARSE_M
import com.glancemap.glancemapwearos.core.service.location.config.BIND_CACHED_FIX_MAX_ACCURACY_M
import com.glancemap.glancemapwearos.core.service.location.config.BIND_CACHED_FIX_MAX_MAX_AGE_MS
import com.glancemap.glancemapwearos.core.service.location.config.BIND_CACHED_FIX_MIN_MAX_AGE_MS
import com.glancemap.glancemapwearos.core.service.location.config.CHANNEL_ID
import com.glancemap.glancemapwearos.core.service.location.config.COARSE_FIX_MAX_ACCURACY_M
import com.glancemap.glancemapwearos.core.service.location.config.ENERGY_SAMPLE_INTERVAL_MS
import com.glancemap.glancemapwearos.core.service.location.config.FINE_FIX_MAX_ACCURACY_M
import com.glancemap.glancemapwearos.core.service.location.config.FIX_MAX_AGE_COARSE_MAX_MS
import com.glancemap.glancemapwearos.core.service.location.config.FIX_MAX_AGE_FINE_MAX_MS
import com.glancemap.glancemapwearos.core.service.location.config.GpsSettingsState
import com.glancemap.glancemapwearos.core.service.location.config.NOTIFICATION_ID
import com.glancemap.glancemapwearos.core.service.location.config.TELEMETRY_SUMMARY_INTERVAL_MS
import com.glancemap.glancemapwearos.core.service.location.config.TELEMETRY_TAG
import com.glancemap.glancemapwearos.core.service.location.config.WAKE_BURST_START_DEBOUNCE_MS
import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_AUTO_FALLBACK_INTERACTIVE_MAX_ACCURACY_M
import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_MAX_ACCEPTED_ACCURACY_M
import com.glancemap.glancemapwearos.core.service.location.engine.LocationEngine
import com.glancemap.glancemapwearos.core.service.location.engine.RequestSpec
import com.glancemap.glancemapwearos.core.service.location.model.LocationPermissionChecker
import com.glancemap.glancemapwearos.core.service.location.model.LocationPermissionSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive
import com.glancemap.glancemapwearos.core.service.location.model.isNonInteractive
import com.glancemap.glancemapwearos.core.service.location.model.resolveLocationTimingProfile
import com.glancemap.glancemapwearos.core.service.location.notifications.LocationNotificationFactory
import com.glancemap.glancemapwearos.core.service.location.policy.FixAcceptancePolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationRuntimeMode
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeDemandReason
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LocationService : Service() {
    private enum class KeepAliveNotificationMode {
        OFF,
        PINNED_NOTIFICATION,
        LOCATION_FOREGROUND,
    }

    private val binder = LocalBinder()
    private lateinit var fusedLocationGateway: FusedLocationGateway
    private lateinit var passiveExternalLocationGateway: PassiveExternalLocationGateway
    private lateinit var watchGpsLocationGateway: WatchGpsLocationGateway
    private lateinit var locationSettingsPreflight: LocationSettingsPreflight
    private lateinit var phoneConnectionProbe: WearPhoneConnectionProbe
    private lateinit var locationUpdateSink: LocationUpdateSink
    private lateinit var callbackProcessor: LocationCallbackProcessor
    private lateinit var immediateLocationCoordinator: ImmediateLocationCoordinator
    private lateinit var watchGpsRecoveryCoordinator: WatchGpsRecoveryCoordinator
    private lateinit var requestCoordinator: LocationRequestCoordinator
    private lateinit var settingsRepository: SettingsRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation = _currentLocation.asStateFlow()
    private val _acceptedLocationEvents =
        MutableSharedFlow<Location>(replay = 1, extraBufferCapacity = ACCEPTED_LOCATION_EVENT_BUFFER_CAPACITY)
    val acceptedLocationEvents = _acceptedLocationEvents.asSharedFlow()

    private val telemetry =
        LocationServiceTelemetry(
            tag = TELEMETRY_TAG,
            summaryIntervalMs = TELEMETRY_SUMMARY_INTERVAL_MS,
        )
    private val engine = LocationEngine(telemetry = telemetry)

    private val _gpsSignalSnapshot = MutableStateFlow(engine.gpsSignalSnapshot)
    val gpsSignalSnapshot = _gpsSignalSnapshot.asStateFlow()
    private val _effectiveUpdateIntervalMs = MutableStateFlow(SettingsRepository.DEFAULT_GPS_INTERVAL_MS)
    val effectiveUpdateIntervalMs = _effectiveUpdateIntervalMs.asStateFlow()

    private val isBound = MutableStateFlow(false)
    private val keepAppOpen = MutableStateFlow(false)
    private val notificationFactory by lazy { LocationNotificationFactory(this, CHANNEL_ID) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }

    // Dedicated background thread for GPS callbacks — keeps location processing off the
    // UI thread so Compose recomposition and map rendering never delay fix delivery.
    // All downstream writes are either StateFlow (thread-safe) or @Volatile fields.
    private val locationCallbackThreadDelegate =
        lazy {
            HandlerThread("LocationCallbackThread").apply { start() }
        }
    private val locationCallbackThread: HandlerThread by locationCallbackThreadDelegate
    private val locationCallbackHandler by lazy { Handler(locationCallbackThread.looper) }
    private val locationCallbackExecutor by lazy {
        java.util.concurrent.Executor { command ->
            if (!locationCallbackHandler.post(command)) {
                command.run()
            }
        }
    }

    @Volatile private var latestWatchGpsOnly: Boolean = false

    @Volatile private var latestAmbientGps: Boolean = false

    @Volatile private var latestRuntimeBackgroundGps: Boolean = false

    @Volatile private var latestRuntimeReason: String = "idle"

    @Volatile private var latestScreenState: LocationScreenState = LocationScreenState.INTERACTIVE

    @Volatile private var latestGpsDebugTelemetry: Boolean = false

    @Volatile private var latestDiagnosticsCaptureActive: Boolean = false

    @Volatile private var latestPassiveLocationExperiment: Boolean = false

    @Volatile private var latestPhoneConnected: Boolean? = null

    @Volatile private var latestUserIntervalMs: Long = SettingsRepository.DEFAULT_GPS_INTERVAL_MS

    @Volatile private var latestRecordingIntervalMs: Long =
        SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS * 1_000L

    @Volatile private var latestRecordingScreenOffIntervalMs: Long =
        SettingsRepository.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS * 1_000L

    @Volatile private var latestTurnByTurnIntervalMs: Long =
        SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS * 1_000L

    @Volatile private var latestTurnByTurnScreenOffIntervalMs: Long =
        SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS * 1_000L

    @Volatile private var latestTurnByTurnScreenOffIntervalOverrideMs: Long? = null

    @Volatile private var latestTurnByTurnScreenOffBatchingEnabled: Boolean = false

    @Volatile private var autoPauseStartedAtElapsedMs: Long = 0L

    private var autoPauseCadenceJob: Job? = null

    @Volatile private var latestAmbientIntervalMs: Long = SettingsRepository.DEFAULT_AMBIENT_GPS_INTERVAL_MS

    @Volatile private var latestTrackingEnabled: Boolean = false

    @Volatile private var latestHasFinePermission: Boolean = false

    @Volatile private var latestHasCoarsePermission: Boolean = false

    @Volatile private var lastAnyAcceptedFixAtElapsedMs: Long = 0L

    @Volatile private var lastCallbackAcceptedFixAtElapsedMs: Long = 0L

    @Volatile private var lastRequestAppliedAtElapsedMs: Long = 0L

    @Volatile private var lastRuntimeStateChangedAtElapsedMs: Long = 0L

    @Volatile private var sourceModeWarmupUntilElapsedMs: Long = 0L

    @Volatile private var sourceModeWarmupExpectedOrigin: LocationSourceMode? = null

    private var energySampleJob: Job? = null
    private var pendingDebouncedImmediateLocationJob: Job? = null
    private var keepAliveNotificationMode: KeepAliveNotificationMode = KeepAliveNotificationMode.OFF
    private var keepAliveNotificationRuntimeReason: String? = null

    private val selfHealFailoverCoordinator by lazy {
        SelfHealFailoverCoordinator(
            serviceScope = serviceScope,
            isServiceActive = { serviceJob.isActive },
            engine = engine,
            telemetry = telemetry,
            requestLocationUpdateIfNeeded = { requestLocationUpdateIfNeeded() },
            requestImmediateLocation = { source -> requestImmediateLocation(source) },
            trackingEnabled = { latestTrackingEnabled },
            ambientModeActive = { isNonInteractiveScreenState() },
            backgroundGpsEnabled = { effectiveBackgroundGpsEnabled() },
            hasFinePermission = { latestHasFinePermission },
            hasCoarsePermission = { latestHasCoarsePermission },
            watchGpsOnly = { latestWatchGpsOnly },
            passiveLocationExperiment = { latestGpsDebugTelemetry && latestPassiveLocationExperiment },
            phoneConnected = { latestPhoneConnected },
            watchGpsAvailable = {
                watchGpsLocationGateway.availabilityReason() == WatchGpsAvailabilityReason.AVAILABLE
            },
            checkPhoneConnection = {
                phoneConnectionProbe.isPhoneConnected()?.also { latestPhoneConnected = it }
            },
            lastAnyAcceptedFixAtElapsedMs = { lastAnyAcceptedFixAtElapsedMs },
            lastCallbackAcceptedFixAtElapsedMs = { lastCallbackAcceptedFixAtElapsedMs },
            lastRequestAppliedAtElapsedMs = { lastRequestAppliedAtElapsedMs },
            expectedIntervalMs = { _effectiveUpdateIntervalMs.value },
            strictFreshMaxAgeMs = { strictFreshMaxAgeMs() },
            requestWatchGpsRecovery = { fixGapMs, staleThresholdMs, expectedIntervalMs ->
                watchGpsRecoveryCoordinator.maybeRequest(
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    fixGapMs = fixGapMs,
                    staleThresholdMs = staleThresholdMs,
                    expectedIntervalMs = expectedIntervalMs,
                )
            },
        )
    }
    private val gnssDiagnosticsCoordinator by lazy {
        GnssDiagnosticsCoordinator(
            serviceScope = serviceScope,
            mainHandler = mainHandler,
            locationManagerProvider = { locationManager },
            hasFinePermission = { latestHasFinePermission },
            hasCoarsePermission = { latestHasCoarsePermission },
            trackingEnabled = { latestTrackingEnabled },
            bound = { isBound.value },
            keepOpen = { keepAppOpen.value },
            watchOnly = { latestWatchGpsOnly },
            sourceMode = { currentLocationSourceMode().telemetryValue },
            watchGpsReason = { currentWatchGpsReason() },
            ambientModeActive = { isNonInteractiveScreenState() },
            debugTelemetryEnabled = { latestGpsDebugTelemetry },
            gpsSignalSnapshot = { engine.gpsSignalSnapshot },
        )
    }

    override fun onCreate() {
        super.onCreate()
        val fused = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationGateway =
            FusedLocationGateway(
                client = fused,
                callbackExecutor = locationCallbackExecutor,
            )
        passiveExternalLocationGateway =
            PassiveExternalLocationGateway(
                locationManager = requireNotNull(locationManager) { "location_manager_unavailable" },
                callbackExecutor = locationCallbackExecutor,
            )
        locationSettingsPreflight = LocationSettingsPreflight(LocationServices.getSettingsClient(this))
        phoneConnectionProbe = WearPhoneConnectionProbe(Wearable.getNodeClient(this))
        watchGpsLocationGateway =
            WatchGpsLocationGateway(
                locationManager = requireNotNull(locationManager) { "location_manager_unavailable" },
                packageManager = packageManager,
                callbackExecutor = locationCallbackExecutor,
                telemetry = telemetry,
            )
        settingsRepository = (application as GlanceMapWearApp).container.settingsRepository
        callbackProcessor =
            LocationCallbackProcessor(
                engine = engine,
                telemetry = telemetry,
                currentPermissions = { currentLocationPermissions() },
                resolveFixAcceptancePolicy = { permissions, sourceMode ->
                    resolveFixAcceptancePolicy(permissions, sourceMode)
                },
                strictFreshMaxAgeMs = { strictFreshMaxAgeMs() },
                hardMaxAcceptedFixAgeMs = { hardMaxAcceptedFixAgeMs() },
                sourceModeWarmupExpectedOrigin = { sourceModeWarmupExpectedOrigin },
                sourceModeWarmupUntilElapsedMs = { sourceModeWarmupUntilElapsedMs },
                emitGpsSignalSnapshot = { _gpsSignalSnapshot.value = engine.gpsSignalSnapshot },
                emitAcceptedLocation = { location, acceptedAtMs ->
                    publishAcceptedLocation(location)
                    lastAnyAcceptedFixAtElapsedMs = acceptedAtMs
                    lastCallbackAcceptedFixAtElapsedMs = acceptedAtMs
                },
                maybeTriggerAutoFusedFailover = { acceptedLocation, callbackOrigin, nowElapsedMs ->
                    selfHealFailoverCoordinator.maybeTriggerAutoFusedFailover(
                        acceptedLocation = acceptedLocation,
                        callbackOrigin = callbackOrigin,
                        nowElapsedMs = nowElapsedMs,
                    )
                },
                endHighAccuracyBurstEarly = {
                    immediateLocationCoordinator.onGoodStreamFixAccepted()
                    immediateLocationCoordinator.endHighAccuracyBurst(reason = "early_fix")
                },
            )
        watchGpsRecoveryCoordinator =
            WatchGpsRecoveryCoordinator(
                serviceScope = serviceScope,
                telemetry = telemetry,
                locationGateway = watchGpsLocationGateway,
                strictFreshMaxAgeMs = { strictFreshMaxAgeMs() },
                processRecoveredLocation = { location ->
                    callbackProcessor
                        .processLocationEvent(
                            event =
                                LocationUpdateEvent(
                                    origin = LocationSourceMode.WATCH_GPS,
                                    candidates = listOf(location),
                                ),
                            nowElapsedMsProvider = { SystemClock.elapsedRealtime() },
                        ).acceptedCandidates > 0
                },
            )
        immediateLocationCoordinator =
            ImmediateLocationCoordinator(
                context = this,
                serviceScope = serviceScope,
                engine = engine,
                telemetry = telemetry,
                readAndStoreLocationPermissions = { readAndStoreLocationPermissions() },
                resolveFixAcceptancePolicy = { permissions, sourceMode ->
                    resolveFixAcceptancePolicy(permissions, sourceMode)
                },
                strictFreshMaxAgeMs = { strictFreshMaxAgeMs() },
                hardMaxAcceptedFixAgeMs = { hardMaxAcceptedFixAgeMs() },
                currentLocationSourceMode = { currentLocationSourceMode() },
                locationGatewayFor = { sourceMode -> locationGatewayFor(sourceMode) },
                requestLocationUpdateIfNeeded = { requestLocationUpdateIfNeeded() },
                passiveExperimentSourceMode = {
                    currentLocationSourceMode().takeIf { sourceMode ->
                        latestGpsDebugTelemetry &&
                            latestPassiveLocationExperiment &&
                            sourceMode == LocationSourceMode.PASSIVE_EXTERNAL
                    }
                },
                shouldRequestNavigateOneShot = { nowElapsedMs ->
                    shouldRequestStaleNavigateOneShot(
                        runtimeReason = latestRuntimeReason,
                        sourceMode = currentLocationSourceMode(),
                        signal = engine.gpsSignalSnapshot,
                        nowElapsedMs = nowElapsedMs,
                        freshnessMaxAgeMs = strictFreshMaxAgeMs(),
                    )
                },
                emitGpsSignalSnapshot = { _gpsSignalSnapshot.value = engine.gpsSignalSnapshot },
                emitAcceptedImmediateLocation = { location, acceptedAtMs ->
                    publishAcceptedLocation(location)
                    lastAnyAcceptedFixAtElapsedMs = acceptedAtMs
                },
                navigateOneShotTimeoutMs = NAVIGATE_ONE_SHOT_TIMEOUT_MS,
            )
        requestCoordinator =
            LocationRequestCoordinator(
                serviceScope = serviceScope,
                engine = engine,
                telemetry = telemetry,
                readAndStoreLocationPermissions = { readAndStoreLocationPermissions() },
                updateSelfHealMonitor = { selfHealFailoverCoordinator.updateSelfHealMonitor() },
                updateGnssDiagnostics = { updateGnssDiagnostics(enabled = latestGpsDebugTelemetry) },
                foregroundRefresh = { refreshKeepAliveNotificationState() },
                inspectLocationEnvironment = { requestSpec, state, nowElapsedMs ->
                    inspectLocationEnvironment(
                        requestSpec = requestSpec,
                        state = state,
                        nowElapsedMs = nowElapsedMs,
                    )
                },
                cancelImmediateLocationWork = { reason ->
                    immediateLocationCoordinator.cancelImmediateLocationWork(reason = reason)
                    watchGpsRecoveryCoordinator.cancel(reason = reason)
                },
                currentState = ::currentRequestUpdateState,
                effectiveUpdateIntervalMs = { _effectiveUpdateIntervalMs.value },
                strictSourceWarmupMs = SOURCE_MODE_WARMUP_MS,
                setSourceModeWarmup = { expectedOrigin, untilElapsedMs ->
                    sourceModeWarmupExpectedOrigin = expectedOrigin
                    sourceModeWarmupUntilElapsedMs = untilElapsedMs
                },
                clearSourceModeWarmup = {
                    sourceModeWarmupExpectedOrigin = null
                    sourceModeWarmupUntilElapsedMs = 0L
                },
                locationGatewayFor = { sourceMode -> locationGatewayFor(sourceMode) },
                locationUpdateSink = { locationUpdateSink },
                removeAllLocationUpdates = { removeAllLocationUpdates() },
                removeInactiveLocationUpdates = { activeSourceMode ->
                    removeInactiveLocationUpdates(activeSourceMode)
                },
                onNoPermissions = { nowElapsedMs ->
                    engine.onNoPermissions(nowElapsedMs = nowElapsedMs)
                    lastAnyAcceptedFixAtElapsedMs = 0L
                    lastCallbackAcceptedFixAtElapsedMs = 0L
                    sourceModeWarmupUntilElapsedMs = 0L
                    sourceModeWarmupExpectedOrigin = null
                    _gpsSignalSnapshot.value = engine.gpsSignalSnapshot
                    _effectiveUpdateIntervalMs.value = SettingsRepository.DEFAULT_GPS_INTERVAL_MS
                },
                onNoRequestSpec = { keepOpen, tracking ->
                    engine.onTrackingDisabled()
                    lastAnyAcceptedFixAtElapsedMs = 0L
                    lastCallbackAcceptedFixAtElapsedMs = 0L
                    sourceModeWarmupUntilElapsedMs = 0L
                    sourceModeWarmupExpectedOrigin = null
                    _effectiveUpdateIntervalMs.value = SettingsRepository.DEFAULT_GPS_INTERVAL_MS
                    if (!keepOpen && !tracking) {
                        stopAllAndSelf()
                    }
                },
                onRequestApplied = { nowElapsedMs, intervalMs ->
                    lastRequestAppliedAtElapsedMs = nowElapsedMs
                    _effectiveUpdateIntervalMs.value = intervalMs
                    updateTelemetryFixContext()
                },
                onSourceModeChanged = {
                    // A location from the previous backend is not a live position for the newly
                    // selected source. Keep it out of the UI and routing inputs until a fresh
                    // callback from that source has been accepted.
                    _currentLocation.value = null
                    lastAnyAcceptedFixAtElapsedMs = 0L
                    lastCallbackAcceptedFixAtElapsedMs = 0L
                    _gpsSignalSnapshot.value = engine.gpsSignalSnapshot
                },
                onRequestFailed = {
                    _effectiveUpdateIntervalMs.value = SettingsRepository.DEFAULT_GPS_INTERVAL_MS
                    updateTelemetryFixContext()
                },
                maybeTriggerInteractiveSelfHealNow = { nowElapsedMs, interactiveTracking, expectedIntervalMs ->
                    selfHealFailoverCoordinator.maybeTriggerInteractiveSelfHealNow(
                        nowElapsedMs = nowElapsedMs,
                        interactiveTracking = interactiveTracking,
                        expectedIntervalMs = expectedIntervalMs,
                    )
                },
                recordEnergySample = { reason, detail ->
                    EnergyDiagnostics.recordSample(
                        context = this,
                        reason = reason,
                        detail = detail,
                    )
                },
            )

        setupLocationUpdateSink()

        serviceScope.launch {
            runCatching { latestWatchGpsOnly = settingsRepository.watchGpsOnly.first() }
            runCatching { latestAmbientGps = settingsRepository.gpsInAmbientMode.first() }
            runCatching { latestAmbientIntervalMs = settingsRepository.ambientGpsInterval.first() }
            runCatching { latestPassiveLocationExperiment = settingsRepository.gpsPassiveLocationExperiment.first() }
            runCatching {
                applyDiagnosticsCaptureState(
                    captureActive = settingsRepository.gpsDebugTelemetry.first(),
                    captureMode = settingsRepository.diagnosticsCaptureMode.first(),
                )
            }
            runCatching { latestUserIntervalMs = settingsRepository.gpsInterval.first() }
            runCatching {
                latestRecordingIntervalMs =
                    recordingIntervalMillis(settingsRepository.recordingSampleIntervalSeconds.first())
            }
            runCatching {
                latestRecordingScreenOffIntervalMs =
                    screenOffIntervalMillis(
                        screenOnIntervalMs = latestRecordingIntervalMs,
                        screenOffSeconds = settingsRepository.recordingScreenOffSampleIntervalSeconds.first(),
                    )
            }
            runCatching {
                latestTurnByTurnIntervalMs =
                    gpsIntervalMillis(settingsRepository.turnByTurnGpsIntervalSeconds.first())
            }
            runCatching {
                latestTurnByTurnScreenOffIntervalMs =
                    screenOffIntervalMillis(
                        screenOnIntervalMs = latestTurnByTurnIntervalMs,
                        screenOffSeconds = settingsRepository.turnByTurnScreenOffGpsIntervalSeconds.first(),
                    )
            }
            requestLocationUpdateIfNeeded()
        }

        observeGpsSettings()
    }

    private fun recordingIntervalMillis(recordingSampleSeconds: Int): Long = gpsIntervalMillis(recordingSampleSeconds)

    private fun gpsIntervalMillis(seconds: Int): Long =
        if (seconds == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) {
            Long.MAX_VALUE
        } else {
            seconds.coerceAtLeast(1) * 1_000L
        }

    private fun screenOffIntervalMillis(
        screenOnIntervalMs: Long,
        screenOffSeconds: Int,
    ): Long =
        when (screenOffSeconds) {
            SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS ->
                SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS * 1_000L
            SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> screenOnIntervalMs
            else -> gpsIntervalMillis(screenOffSeconds)
        }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val hasScreenState = intent?.hasExtra(EXTRA_SCREEN_STATE) == true
        val hasTrackingEnabled = intent?.hasExtra(EXTRA_TRACKING_ENABLED) == true
        val hasBackgroundGpsEnabled = intent?.hasExtra(EXTRA_BACKGROUND_GPS_ENABLED) == true
        val hasRuntimeReason = intent?.hasExtra(EXTRA_RUNTIME_REASON) == true
        if (hasScreenState || hasTrackingEnabled || hasBackgroundGpsEnabled || hasRuntimeReason) {
            val screenStateName = intent.getStringExtra(EXTRA_SCREEN_STATE)
            val screenState =
                runCatching {
                    LocationScreenState.valueOf(screenStateName.orEmpty())
                }.getOrDefault(LocationScreenState.INTERACTIVE)
            setRuntimeState(
                screenState = if (hasScreenState) screenState else latestScreenState,
                trackingEnabled =
                    if (hasTrackingEnabled) {
                        intent.getBooleanExtra(EXTRA_TRACKING_ENABLED, false)
                    } else {
                        latestTrackingEnabled
                    },
                backgroundGpsEnabled =
                    if (hasBackgroundGpsEnabled) {
                        intent.getBooleanExtra(EXTRA_BACKGROUND_GPS_ENABLED, false)
                    } else {
                        latestRuntimeBackgroundGps
                    },
                runtimeReason =
                    if (hasRuntimeReason) {
                        intent.getStringExtra(EXTRA_RUNTIME_REASON).orEmpty()
                    } else {
                        latestRuntimeReason
                    },
            )
        }
        if (intent?.hasExtra(EXTRA_KEEP_APP_OPEN) == true) {
            setKeepAppOpenState(intent.getBooleanExtra(EXTRA_KEEP_APP_OPEN, false))
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopAllAndSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun setupLocationUpdateSink() {
        locationUpdateSink =
            object : LocationUpdateSink {
                override fun onLocationAvailability(isAvailable: Boolean) {
                    callbackProcessor.onLocationAvailability(
                        isAvailable = isAvailable,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                }

                override fun onLocations(event: LocationUpdateEvent) {
                    callbackProcessor.processLocationEvent(
                        event = event,
                        nowElapsedMsProvider = { SystemClock.elapsedRealtime() },
                    )
                }
            }
    }

    fun requestImmediateLocation(source: String = "service_unknown") {
        requestImmediateLocation(source = source, allowWakeDebounce = true)
    }

    private fun requestImmediateLocation(
        source: String,
        allowWakeDebounce: Boolean,
    ) {
        immediateBurstGuardReason()?.let { reason ->
            pendingDebouncedImmediateLocationJob?.cancel()
            pendingDebouncedImmediateLocationJob = null
            telemetry.logImmediateRequestGuarded(
                source = source,
                reason = reason,
                screenState = latestScreenState.name,
                trackingEnabled = latestTrackingEnabled,
            )
            return
        }

        val debounceMs = wakeBurstDebounceRemainingMs(source)
        if (allowWakeDebounce && debounceMs > 0L) {
            pendingDebouncedImmediateLocationJob?.cancel()
            telemetry.logImmediateRequestDeferred(
                source = source,
                delayMs = debounceMs,
                screenState = latestScreenState.name,
                trackingEnabled = latestTrackingEnabled,
            )
            pendingDebouncedImmediateLocationJob =
                serviceScope.launch {
                    delay(debounceMs)
                    requestImmediateLocation(source = source, allowWakeDebounce = false)
                }
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val signal = engine.gpsSignalSnapshot
        val streamIntervalMs = engine.currentRequestIntervalOr(_effectiveUpdateIntervalMs.value)
        val suppressForFreshStream =
            shouldSuppressImmediateBurstForFreshStream(
                runtimeReason = latestRuntimeReason,
                runtimeMode = engine.currentRuntimeModeOrNull(),
                intervalMs = streamIntervalMs,
                signal = signal,
                nowElapsedMs = nowElapsedMs,
            )
        if (suppressForFreshStream) {
            telemetry.logImmediateRequestSkippedFreshStream(
                source = source,
                runtimeReason = latestRuntimeReason,
                intervalMs = streamIntervalMs,
                fixAgeMs = (nowElapsedMs - signal.lastFixElapsedRealtimeMs).coerceAtLeast(0L),
                accuracyM = signal.lastFixAccuracyM,
            )
        } else {
            immediateLocationCoordinator.requestImmediateLocation(source)
        }
    }

    fun setRuntimeState(
        screenState: LocationScreenState,
        trackingEnabled: Boolean,
        backgroundGpsEnabled: Boolean = latestRuntimeBackgroundGps,
        runtimeReason: String = latestRuntimeReason,
    ) {
        val previousScreenState = latestScreenState
        val previousRuntimeReason = latestRuntimeReason
        val screenStateChanged = latestScreenState != screenState
        val trackingChanged = latestTrackingEnabled != trackingEnabled
        val backgroundGpsChanged = latestRuntimeBackgroundGps != backgroundGpsEnabled
        val reasonChanged = latestRuntimeReason != runtimeReason
        if (!screenStateChanged && !trackingChanged && !backgroundGpsChanged && !reasonChanged) return

        latestScreenState = screenState
        latestTrackingEnabled = trackingEnabled
        latestRuntimeBackgroundGps = backgroundGpsEnabled
        latestRuntimeReason = runtimeReason.ifBlank { "idle" }
        lastRuntimeStateChangedAtElapsedMs = SystemClock.elapsedRealtime()
        cancelPendingImmediateWorkForRuntimeState(screenState, trackingEnabled)
        val effectiveBackgroundGpsEnabled = effectiveBackgroundGpsEnabled()
        updateTelemetryFixContext(effectiveBackgroundGpsEnabled = effectiveBackgroundGpsEnabled)

        telemetry.logRuntimeStateApplied(
            screenState = screenState.name,
            trackingEnabled = trackingEnabled,
            screenStateChanged = screenStateChanged,
            trackingChanged = trackingChanged,
            backgroundGpsEnabled = effectiveBackgroundGpsEnabled,
            runtimeReason = latestRuntimeReason,
            runtimeReasonChanged = reasonChanged,
        )
        if (screenStateChanged) {
            telemetry.logScreenState(screenState.name)
        }
        if (trackingChanged) {
            telemetry.logTrackingEnabled(trackingEnabled)
        }
        when {
            !trackingEnabled -> {
                immediateLocationCoordinator.cancelImmediateLocationWork(reason = "tracking_disabled")
            }
            screenState.isNonInteractive && !effectiveBackgroundGpsEnabled -> {
                immediateLocationCoordinator.cancelImmediateLocationWork(
                    reason = "non_interactive_without_gps",
                )
            }
        }
        selfHealFailoverCoordinator.updateSelfHealMonitor()
        refreshKeepAliveNotificationState()
        updateAutoPauseCadence(previousRuntimeReason, latestRuntimeReason)
        if (
            previousScreenState.isNonInteractive &&
            screenState.isInteractive &&
            currentLocationSourceMode() == LocationSourceMode.AUTO_FUSED
        ) {
            serviceScope.launch {
                runCatching { fusedLocationGateway.flushLocations() }
                requestLocationUpdateIfNeeded()
            }
        } else {
            requestLocationUpdateIfNeeded()
        }
    }

    private fun updateAutoPauseCadence(
        previousReason: String,
        currentReason: String,
    ) {
        if (previousReason == currentReason) return
        autoPauseCadenceJob?.cancel()
        autoPauseCadenceJob = null
        if (currentReason != NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED) {
            autoPauseStartedAtElapsedMs = 0L
            return
        }
        autoPauseStartedAtElapsedMs = SystemClock.elapsedRealtime()
        autoPauseCadenceJob =
            serviceScope.launch {
                delay(AUTO_PAUSE_PROLONGED_AFTER_MS)
                if (latestRuntimeReason == NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED) {
                    requestLocationUpdateIfNeeded()
                }
            }
    }

    private fun cancelPendingImmediateWorkForRuntimeState(
        screenState: LocationScreenState,
        trackingEnabled: Boolean,
    ) {
        pendingDebouncedImmediateLocationJob?.cancel()
        pendingDebouncedImmediateLocationJob = null
        navigateOneShotCancellationReason(
            trackingEnabled = trackingEnabled,
            screenState = screenState,
            runtimeReason = latestRuntimeReason,
        )?.let(immediateLocationCoordinator::cancelNavigateOneShot)
    }

    private fun isProlongedAutoPause(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        autoPauseStartedAtElapsedMs > 0L &&
            nowElapsedMs - autoPauseStartedAtElapsedMs >= AUTO_PAUSE_PROLONGED_AFTER_MS

    private fun autoPauseGpsIntervalMs(): Long =
        if (isProlongedAutoPause()) {
            AUTO_PAUSE_PROLONGED_GPS_INTERVAL_MS
        } else {
            AUTO_PAUSE_GPS_INTERVAL_MS
        }

    private fun currentRequestUpdateState(): RequestUpdateState =
        RequestUpdateState(
            bound = isBound.value,
            tracking = latestTrackingEnabled,
            keepOpen = keepAppOpen.value,
            watchOnlyRequested = latestWatchGpsOnly,
            watchOnlyEffective =
                latestWatchGpsOnly || selfHealFailoverCoordinator.isAutoFusedFallbackToWatchGps(),
            screenState = latestScreenState,
            backgroundGps = effectiveBackgroundGpsEnabled(),
            runtimeReason = latestRuntimeReason,
            passiveLocationExperiment = latestGpsDebugTelemetry && latestPassiveLocationExperiment,
            userIntervalMs = effectiveRuntimeIntervalMs(),
            ambientIntervalMs = latestAmbientIntervalMs,
            turnByTurnScreenOffBatchingEnabled = latestTurnByTurnScreenOffBatchingEnabled,
        )

    private fun effectiveRuntimeIntervalMs(): Long =
        when {
            latestRuntimeReason == NavigationRuntimeDemandReason.RECORDING_GUIDANCE ->
                minOf(recordingIntervalForScreen(), guidanceIntervalForScreen())
            latestRuntimeReason == NavigationRuntimeDemandReason.RECORDING -> recordingIntervalForScreen()
            latestRuntimeReason == NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED ->
                maxOf(recordingIntervalForScreen(), autoPauseGpsIntervalMs())
            latestRuntimeReason.isGuidanceRuntimeReason() -> guidanceIntervalForScreen()
            else -> latestUserIntervalMs
        }

    private fun recordingIntervalForScreen(): Long =
        if (latestScreenState.isNonInteractive) {
            latestRecordingScreenOffIntervalMs
        } else {
            latestRecordingIntervalMs
        }

    private fun guidanceIntervalForScreen(): Long =
        if (latestScreenState.isNonInteractive) {
            latestTurnByTurnScreenOffIntervalOverrideMs ?: latestTurnByTurnScreenOffIntervalMs
        } else {
            latestTurnByTurnIntervalMs
        }

    private fun updateTelemetryFixContext(
        effectiveBackgroundGpsEnabled: Boolean = effectiveBackgroundGpsEnabled(),
    ) {
        telemetry.updateFixContext(
            screenState = latestScreenState.name,
            expectedIntervalMs = _effectiveUpdateIntervalMs.value,
            trackingEnabled = latestTrackingEnabled,
            backgroundGpsEnabled = effectiveBackgroundGpsEnabled,
        )
    }

    fun setScreenState(screenState: LocationScreenState) {
        setRuntimeState(
            screenState = screenState,
            trackingEnabled = latestTrackingEnabled,
        )
    }

    fun setTurnByTurnScreenOffIntervalOverride(intervalMs: Long?) {
        val sanitizedIntervalMs =
            intervalMs?.coerceIn(
                MIN_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS,
                MAX_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS,
            )
        if (latestTurnByTurnScreenOffIntervalOverrideMs == sanitizedIntervalMs) return
        latestTurnByTurnScreenOffIntervalOverrideMs = sanitizedIntervalMs
        if (latestScreenState.isNonInteractive && latestRuntimeReason.isGuidanceRuntimeReason()) {
            requestLocationUpdateIfNeeded()
        }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        setRuntimeState(
            screenState = latestScreenState,
            trackingEnabled = enabled,
        )
    }

    fun setKeepAppOpenState(enabled: Boolean) {
        if (keepAppOpen.value != enabled) {
            keepAppOpen.value = enabled
            telemetry.logKeepAppOpen(enabled)
        }
        refreshKeepAliveNotificationState()

        if (!enabled) {
            serviceScope.launch {
                if (!latestTrackingEnabled) {
                    stopAllAndSelf()
                }
            }
        }
    }

    private fun requestLocationUpdateIfNeeded() {
        requestCoordinator.requestLocationUpdateIfNeeded()
    }

    private suspend fun inspectLocationEnvironment(
        requestSpec: RequestSpec,
        state: RequestUpdateState,
        nowElapsedMs: Long,
    ): LocationEnvironmentAction {
        val locationSettings =
            if (requestSpec.sourceMode == LocationSourceMode.AUTO_FUSED) {
                locationSettingsPreflight.check(
                    LocationUpdateRequestParams(
                        priority = requestSpec.priority,
                        intervalMs = requestSpec.intervalMs,
                        minDistanceMeters = requestSpec.minDistanceMeters,
                        waitForAccurateLocation =
                            requestSpec.mode == LocationRuntimeMode.BURST &&
                                requestSpec.sourceMode == LocationSourceMode.AUTO_FUSED,
                        maxUpdateDelayMs = maxUpdateDelayMsFor(requestSpec),
                    ),
                )
            } else {
                null
            }
        val shouldCheckPhone =
            requestSpec.sourceMode == LocationSourceMode.AUTO_FUSED ||
                (requestSpec.sourceMode == LocationSourceMode.WATCH_GPS && !state.watchOnlyRequested)
        val phoneConnected =
            if (shouldCheckPhone) {
                phoneConnectionProbe.isPhoneConnected()
            } else {
                null
            }
        updateLatestPhoneConnection(
            phoneConnected = phoneConnected,
        )
        val shouldCheckWatchGps =
            requestSpec.sourceMode == LocationSourceMode.WATCH_GPS ||
                requestSpec.sourceMode == LocationSourceMode.AUTO_FUSED
        val watchGpsAvailability =
            if (shouldCheckWatchGps) {
                watchGpsLocationGateway.availabilityReason()
            } else {
                null
            }
        val decision =
            resolveLocationEnvironmentDecision(
                sourceMode = requestSpec.sourceMode,
                watchOnlyRequested = state.watchOnlyRequested,
                watchGpsAvailability = watchGpsAvailability,
                phoneConnected = phoneConnected,
                locationSettings = locationSettings,
                passiveLocationExperiment = state.passiveLocationExperiment,
            )
        val warningChanged =
            engine.updateEnvironmentWarning(
                warning = decision.warning,
                nowElapsedMs = nowElapsedMs,
            )
        if (
            warningChanged ||
            locationSettings?.satisfied == false ||
            phoneConnected == false
        ) {
            telemetry.logLocationEnvironmentPreflight(
                sourceMode = requestSpec.sourceMode.telemetryValue,
                watchGpsReason = watchGpsReason(requestSpec.sourceMode, state.watchOnlyRequested),
                locationSettingsSatisfied = locationSettings?.satisfied,
                locationSettingsStatusCode = locationSettings?.statusCode,
                phoneConnected = phoneConnected,
                watchGpsAvailability = watchGpsAvailability?.name,
                warning = decision.warning.name,
                action = decision.action.name,
            )
        }
        if (warningChanged) {
            telemetry.logLocationEnvironmentWarningChanged(decision.warning.name)
        }
        _gpsSignalSnapshot.value = engine.gpsSignalSnapshot

        if (decision.action != LocationEnvironmentAction.RESTART_REQUEST) {
            return LocationEnvironmentAction.CONTINUE
        }
        val forcedFallback =
            selfHealFailoverCoordinator.forceAutoFusedFallbackToWatchGps(
                reason = "phone_disconnected",
                nowElapsedMs = nowElapsedMs,
            )
        return if (forcedFallback) {
            LocationEnvironmentAction.RESTART_REQUEST
        } else {
            LocationEnvironmentAction.CONTINUE
        }
    }

    private fun updateLatestPhoneConnection(phoneConnected: Boolean?) {
        if (phoneConnected != null) {
            latestPhoneConnected = phoneConnected
            if (phoneConnected) {
                selfHealFailoverCoordinator.onPhoneConnectionStateChecked(
                    phoneConnected = true,
                )
            }
        }
    }

    private fun maxUpdateDelayMsFor(requestSpec: RequestSpec): Long =
        resolveMaxUpdateDelayMs(
            screenState = latestScreenState,
            runtimeReason = latestRuntimeReason,
            turnByTurnScreenOffBatchingEnabled = latestTurnByTurnScreenOffBatchingEnabled,
            requestSpec = requestSpec,
        )

    private fun immediateBurstGuardReason(): String? =
        when {
            !latestTrackingEnabled -> "tracking_disabled"
            latestScreenState.isNonInteractive && !effectiveBackgroundGpsEnabled() -> "non_interactive_without_gps"
            else -> null
        }

    private fun wakeBurstDebounceRemainingMs(source: String): Long {
        if (!source.startsWith("ui_startup_fresh_fix_ambient_exit")) return 0L
        val elapsedSinceRuntimeChangeMs =
            SystemClock.elapsedRealtime() - lastRuntimeStateChangedAtElapsedMs
        return (WAKE_BURST_START_DEBOUNCE_MS - elapsedSinceRuntimeChangeMs).coerceAtLeast(0L)
    }

    @SuppressLint("MissingPermission")
    private fun observeGpsSettings() {
        serviceScope.launch {
            combine(
                settingsRepository.watchGpsOnly,
                settingsRepository.gpsInterval,
                settingsRepository.ambientGpsInterval,
                settingsRepository.gpsInAmbientMode,
                settingsRepository.gpsDebugTelemetry,
            ) { watchOnly, interval, ambientInterval, ambientGps, debugTelemetry ->
                GpsSettingsState(
                    watchOnly = watchOnly,
                    intervalMs = interval,
                    ambientIntervalMs = ambientInterval,
                    recordingIntervalMs = SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS * 1_000L,
                    recordingScreenOffIntervalMs =
                        SettingsRepository.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS * 1_000L,
                    turnByTurnIntervalMs = SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS * 1_000L,
                    turnByTurnScreenOffIntervalMs = SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS * 1_000L,
                    turnByTurnScreenOffIntervalAdaptive = false,
                    turnByTurnScreenOffBatchingEnabled =
                        SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED,
                    ambientGps = ambientGps,
                    debugTelemetry = debugTelemetry,
                    diagnosticsCaptureMode = SettingsRepository.DEFAULT_DIAGNOSTICS_CAPTURE_MODE,
                    passiveLocationExperiment = false,
                )
            }.combine(settingsRepository.diagnosticsCaptureMode) { state, captureMode ->
                state.copy(diagnosticsCaptureMode = captureMode)
            }.combine(settingsRepository.recordingSampleIntervalSeconds) { state, recordingSampleSeconds ->
                state.copy(recordingIntervalMs = recordingIntervalMillis(recordingSampleSeconds))
            }.combine(settingsRepository.recordingScreenOffSampleIntervalSeconds) { state, recordingScreenOffSeconds ->
                state.copy(
                    recordingScreenOffIntervalMs =
                        screenOffIntervalMillis(
                            screenOnIntervalMs = state.recordingIntervalMs,
                            screenOffSeconds = recordingScreenOffSeconds,
                        ),
                )
            }.combine(settingsRepository.turnByTurnGpsIntervalSeconds) { state, turnByTurnSeconds ->
                state.copy(turnByTurnIntervalMs = gpsIntervalMillis(turnByTurnSeconds))
            }.combine(settingsRepository.turnByTurnScreenOffGpsIntervalSeconds) { state, turnByTurnScreenOffSeconds ->
                state.copy(
                    turnByTurnScreenOffIntervalMs =
                        screenOffIntervalMillis(
                            screenOnIntervalMs = state.turnByTurnIntervalMs,
                            screenOffSeconds = turnByTurnScreenOffSeconds,
                        ),
                    turnByTurnScreenOffIntervalAdaptive =
                        turnByTurnScreenOffSeconds ==
                            SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
                )
            }.combine(settingsRepository.turnByTurnScreenOffBatchingEnabled) { state, enabled ->
                state.copy(turnByTurnScreenOffBatchingEnabled = enabled)
            }.combine(settingsRepository.gpsPassiveLocationExperiment) { state, passiveLocationExperiment ->
                state.copy(passiveLocationExperiment = passiveLocationExperiment)
            }.collectLatest { state ->
                onGpsSettingsStateChanged(state)
            }
        }
    }

    private fun onGpsSettingsStateChanged(state: GpsSettingsState) {
        val captureActiveNow = state.debugTelemetry
        val debugTelemetryEnabledNow =
            captureActiveNow &&
                state.diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL
        val debugTelemetryJustEnabled = !latestGpsDebugTelemetry && debugTelemetryEnabledNow
        val passiveExperimentWasActive = latestGpsDebugTelemetry && latestPassiveLocationExperiment
        val passiveExperimentActiveNow = debugTelemetryEnabledNow && state.passiveLocationExperiment
        val passiveExperimentChanged = passiveExperimentWasActive != passiveExperimentActiveNow
        val watchOnlyChanged = latestWatchGpsOnly != state.watchOnly
        latestWatchGpsOnly = state.watchOnly
        latestUserIntervalMs = state.intervalMs
        latestRecordingIntervalMs = state.recordingIntervalMs
        latestRecordingScreenOffIntervalMs = state.recordingScreenOffIntervalMs
        latestTurnByTurnIntervalMs = state.turnByTurnIntervalMs
        latestTurnByTurnScreenOffIntervalMs = state.turnByTurnScreenOffIntervalMs
        if (!state.turnByTurnScreenOffIntervalAdaptive) {
            latestTurnByTurnScreenOffIntervalOverrideMs = null
        }
        latestTurnByTurnScreenOffBatchingEnabled = state.turnByTurnScreenOffBatchingEnabled
        latestAmbientIntervalMs = state.ambientIntervalMs
        latestAmbientGps = state.ambientGps
        latestPassiveLocationExperiment = state.passiveLocationExperiment
        applyDiagnosticsCaptureState(
            captureActive = captureActiveNow,
            captureMode = state.diagnosticsCaptureMode,
        )
        if (debugTelemetryJustEnabled || passiveExperimentChanged) {
            engine.forceRequestRefresh()
        }
        if (watchOnlyChanged) {
            selfHealFailoverCoordinator.clearAutoFusedFailoverState(reason = "watch_setting_changed")
        }
        if (passiveExperimentChanged) {
            selfHealFailoverCoordinator.clearAutoFusedFailoverState(reason = "passive_experiment_changed")
        }

        requestLocationUpdateIfNeeded()
    }

    @SuppressLint("MissingPermission")
    override fun onBind(intent: Intent): IBinder {
        isBound.value = true
        requestLocationUpdateIfNeeded()
        serviceScope.launch {
            if (!latestTrackingEnabled) return@launch
            val permissions = readAndStoreLocationPermissions()
            val hasFinePermission = permissions.hasFinePermission

            if (permissions.hasAnyPermission) {
                val location = runCatching { currentLocationGateway().getLastLocation() }.getOrNull()
                if (location != null) {
                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    val maxCachedAgeMs =
                        (latestUserIntervalMs * 2L)
                            .coerceIn(BIND_CACHED_FIX_MIN_MAX_AGE_MS, BIND_CACHED_FIX_MAX_MAX_AGE_MS)
                    val strictMaxAgeMs = strictFreshMaxAgeMs()
                    val effectiveMaxCachedAgeMs = minOf(maxCachedAgeMs, strictMaxAgeMs)
                    val ageMs = LocationFixPolicy.locationAgeMs(location, nowElapsedMs)

                    val accuracy = location.accuracy
                    val maxCachedAccuracyM =
                        if (hasFinePermission) {
                            BIND_CACHED_FIX_MAX_ACCURACY_M
                        } else {
                            BIND_CACHED_FIX_MAX_ACCURACY_COARSE_M
                        }
                    val acceptableCoordinates = LocationFixPolicy.hasValidCoordinates(location)
                    val acceptableAccuracy = accuracy.isFinite() && accuracy <= maxCachedAccuracyM
                    val acceptableAge = ageMs <= effectiveMaxCachedAgeMs

                    if (acceptableCoordinates && acceptableAccuracy && acceptableAge) {
                        engine.updateGpsSignalSample(
                            nowElapsedMs = nowElapsedMs,
                            ageMs = ageMs,
                            accuracyM = location.accuracy,
                            freshnessMaxAgeMs = strictMaxAgeMs,
                            sourceMode = currentLocationSourceMode(),
                            provider = location.provider,
                            accepted = null,
                        )
                        _gpsSignalSnapshot.value = engine.gpsSignalSnapshot
                        val outputLocation =
                            engine.acceptCachedLocation(
                                location = location,
                                nowElapsedMs = nowElapsedMs,
                                ageMs = ageMs,
                            )
                        publishAcceptedLocation(outputLocation)
                        lastAnyAcceptedFixAtElapsedMs = nowElapsedMs
                        telemetry.logCachedLocationAccepted(
                            ageMs = ageMs,
                            accuracyM = accuracy,
                            provider = location.provider,
                        )
                    } else {
                        telemetry.logCachedLocationRejected(
                            ageMs = ageMs,
                            accuracyM = accuracy,
                            maxAgeMs = effectiveMaxCachedAgeMs,
                            maxAccuracyM = maxCachedAccuracyM,
                            provider = location.provider,
                        )
                        if (!acceptableCoordinates) {
                            telemetry.logInvalidCoordinatesDropped(
                                nowElapsedMs = nowElapsedMs,
                                activityState = engine.activityState(),
                                burst = engine.isBurstActive(),
                                source = "cached_on_bind",
                                latitude = location.latitude,
                                longitude = location.longitude,
                                provider = location.provider,
                            )
                        }
                        if (!acceptableAge) {
                            telemetry.logStaleFixDropped(
                                nowElapsedMs = nowElapsedMs,
                                activityState = engine.activityState(),
                                burst = engine.isBurstActive(),
                                source = "cached_on_bind",
                                ageMs = ageMs,
                                maxAgeMs = effectiveMaxCachedAgeMs,
                            )
                        }
                        requestImmediateLocation(source = "service_bind_cached_reject")
                    }
                } else {
                    requestImmediateLocation(source = "service_bind_no_cached")
                }
            }
        }

        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound.value = false
        requestLocationUpdateIfNeeded()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        energySampleJob?.cancel()
        energySampleJob = null
        selfHealFailoverCoordinator.stop()
        watchGpsRecoveryCoordinator.cancel(reason = "service_destroy")
        unregisterGnssDiagnostics(reason = "service_destroy")
        stopAllAndSelf(
            stopSelf = false,
            preserveKeepOpenNotification = keepAppOpen.value,
        )
        serviceJob.cancel()
        // Quit the callback thread after all location updates are removed so no
        // in-flight callbacks can fire after the service is torn down.
        if (locationCallbackThreadDelegate.isInitialized()) {
            locationCallbackThread.quitSafely()
        }
        super.onDestroy()
    }

    private fun stopAllAndSelf(
        stopSelf: Boolean = true,
        preserveKeepOpenNotification: Boolean = false,
    ) {
        pendingDebouncedImmediateLocationJob?.cancel()
        pendingDebouncedImmediateLocationJob = null
        requestCoordinator.cancel()
        immediateLocationCoordinator.shutdown(reason = "service_stop")
        watchGpsRecoveryCoordinator.cancel(reason = "service_stop")
        energySampleJob?.cancel()
        energySampleJob = null
        selfHealFailoverCoordinator.stop()

        removeAllLocationUpdatesBestEffort()
        unregisterGnssDiagnostics(reason = "service_stop")
        engine.stopAndReset()
        lastAnyAcceptedFixAtElapsedMs = 0L
        lastCallbackAcceptedFixAtElapsedMs = 0L
        lastRequestAppliedAtElapsedMs = 0L
        sourceModeWarmupUntilElapsedMs = 0L
        sourceModeWarmupExpectedOrigin = null

        _gpsSignalSnapshot.value = engine.gpsSignalSnapshot
        _effectiveUpdateIntervalMs.value = SettingsRepository.DEFAULT_GPS_INTERVAL_MS

        if (preserveKeepOpenNotification) {
            if (keepAliveNotificationMode == KeepAliveNotificationMode.LOCATION_FOREGROUND) {
                runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
            }
            DebugTelemetry.log(
                TELEMETRY_TAG,
                "keepOpenNotification: preserved on service destroy " +
                    "mode=${keepAliveNotificationMode.name}",
            )
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            notificationFactory.cancel(NOTIFICATION_ID)
            keepAliveNotificationMode = KeepAliveNotificationMode.OFF
            keepAliveNotificationRuntimeReason = null
        }

        if (stopSelf) {
            runCatching { stopSelf() }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    private fun readAndStoreLocationPermissions(): LocationPermissionSnapshot {
        val permissions = LocationPermissionChecker.read(applicationContext)
        latestHasFinePermission = permissions.hasFinePermission
        latestHasCoarsePermission = permissions.hasCoarsePermission
        return permissions
    }

    private fun currentLocationPermissions(): LocationPermissionSnapshot =
        LocationPermissionSnapshot(
            hasFinePermission = latestHasFinePermission,
            hasCoarsePermission = latestHasCoarsePermission,
        )

    private fun resolveFixAcceptancePolicy(
        permissions: LocationPermissionSnapshot,
        sourceMode: LocationSourceMode,
    ): FixAcceptancePolicy {
        val expectedIntervalMs = engine.currentRequestIntervalOr(_effectiveUpdateIntervalMs.value)
        val basePolicy =
            LocationFixPolicy.resolveAcceptancePolicy(
                hasFinePermission = permissions.hasFinePermission,
                hasCoarsePermission = permissions.hasCoarsePermission,
                expectedIntervalMs = expectedIntervalMs,
                minMaxAgeMs = BIND_CACHED_FIX_MIN_MAX_AGE_MS,
                fineMaxAgeMs = FIX_MAX_AGE_FINE_MAX_MS,
                coarseMaxAgeMs = FIX_MAX_AGE_COARSE_MAX_MS,
                fineMaxAccuracyM = FINE_FIX_MAX_ACCURACY_M,
                coarseMaxAccuracyM = COARSE_FIX_MAX_ACCURACY_M,
            )
        val watchGpsMaxAccuracyM =
            LocationFixPolicy.resolveWatchGpsAcceptanceAccuracyM(
                sourceMode = sourceMode,
                watchGpsOnly = latestWatchGpsOnly,
                runtimeMode = engine.currentRuntimeModeOrNull(),
                watchGpsMaxAccuracyM = WATCH_GPS_MAX_ACCEPTED_ACCURACY_M,
                watchGpsAutoFallbackInteractiveMaxAccuracyM =
                WATCH_GPS_AUTO_FALLBACK_INTERACTIVE_MAX_ACCURACY_M,
            )
        return LocationFixPolicy.adaptAcceptanceForSourceMode(
            policy = basePolicy,
            sourceMode = sourceMode,
            watchGpsMaxAccuracyM = watchGpsMaxAccuracyM,
        )
    }

    private fun strictFreshMaxAgeMs(): Long {
        val expectedIntervalMs = engine.currentRequestIntervalOr(_effectiveUpdateIntervalMs.value)
        return resolveLocationTimingProfile(expectedIntervalMs).strictFreshFixMaxAgeMs
    }

    private fun hardMaxAcceptedFixAgeMs(): Long =
        when (engine.currentRuntimeModeOrNull()) {
            LocationRuntimeMode.PASSIVE -> HARD_STALE_FIX_MAX_AGE_PASSIVE_MS
            LocationRuntimeMode.INTERACTIVE,
            LocationRuntimeMode.BURST,
            null,
            -> HARD_STALE_FIX_MAX_AGE_INTERACTIVE_MS
        }

    private fun updateGnssDiagnostics(enabled: Boolean) {
        gnssDiagnosticsCoordinator.update(enabled)
    }

    private fun unregisterGnssDiagnostics(reason: String = "unspecified") {
        gnssDiagnosticsCoordinator.unregister(reason = reason)
    }

    private fun updateEnergySampling(enabled: Boolean) {
        if (!enabled) {
            energySampleJob?.cancel()
            energySampleJob = null
            return
        }

        if (energySampleJob?.isActive == true) return
        energySampleJob =
            serviceScope.launch {
                EnergyDiagnostics.recordSample(
                    context = this@LocationService,
                    reason = "capture_enabled",
                    detail = "source=location_service",
                )
                while (serviceJob.isActive && latestDiagnosticsCaptureActive) {
                    // The first fixed-cadence sample lands after a one-minute warm-up. This
                    // avoids counting the settings interaction that starts a battery benchmark.
                    delay(ENERGY_SAMPLE_INTERVAL_MS)
                    if (!latestDiagnosticsCaptureActive) break
                    EnergyDiagnostics.recordSample(
                        context = this@LocationService,
                        reason = "periodic",
                        detail = energyRuntimeDetail(),
                    )
                }
            }
    }

    private fun energyRuntimeDetail(): String {
        val gpsRequestActive = engine.hasAppliedRequest()
        val gpsBackend = engine.currentSourceModeOrNull()?.telemetryValue ?: "none"
        val gpsRequestIntervalMs =
            if (gpsRequestActive) {
                engine.currentRequestIntervalOr(_effectiveUpdateIntervalMs.value).toString()
            } else {
                "na"
            }
        return "effectiveIntervalMs=${_effectiveUpdateIntervalMs.value} " +
            "burst=${engine.isBurstActive()} tracking=$latestTrackingEnabled " +
            "bound=${isBound.value} keepOpen=${keepAppOpen.value} " +
            "screenState=${latestScreenState.name} runtimeReason=$latestRuntimeReason " +
            "gpsRequestActive=$gpsRequestActive gpsBackend=$gpsBackend " +
            "gpsRequestIntervalMs=$gpsRequestIntervalMs"
    }

    private fun applyDiagnosticsCaptureState(
        captureActive: Boolean,
        captureMode: String,
    ) {
        if (latestDiagnosticsCaptureActive && !captureActive) {
            EnergyDiagnostics.recordSample(
                context = this,
                reason = "capture_toggle_off",
                detail = "source=location_service",
            )
        }
        val fullDiagnostics =
            captureActive && captureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL
        latestDiagnosticsCaptureActive = captureActive
        latestGpsDebugTelemetry = fullDiagnostics
        telemetry.setDebugEnabled(fullDiagnostics)
        EnergyDiagnostics.configure(
            captureActive = captureActive,
            fullDiagnostics = fullDiagnostics,
        )
        updateEnergySampling(captureActive)
        updateGnssDiagnostics(enabled = fullDiagnostics)
    }

    private fun refreshKeepAliveNotificationState() {
        val guidanceActive = isGuidanceRuntimeReason(latestRuntimeReason)
        val desiredMode =
            when {
                guidanceActive && shouldUseLocationForegroundMode() ->
                    KeepAliveNotificationMode.LOCATION_FOREGROUND
                !keepAppOpen.value -> KeepAliveNotificationMode.OFF
                shouldUseLocationForegroundMode() -> KeepAliveNotificationMode.LOCATION_FOREGROUND
                else -> KeepAliveNotificationMode.PINNED_NOTIFICATION
            }
        if (
            desiredMode == keepAliveNotificationMode &&
            keepAliveNotificationRuntimeReason == latestRuntimeReason
        ) {
            return
        }

        when (desiredMode) {
            KeepAliveNotificationMode.OFF -> {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                notificationFactory.cancel(NOTIFICATION_ID)
                keepAliveNotificationRuntimeReason = null
            }
            KeepAliveNotificationMode.PINNED_NOTIFICATION -> {
                if (keepAliveNotificationMode == KeepAliveNotificationMode.LOCATION_FOREGROUND) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                }
                val notification =
                    notificationFactory.buildNotification(
                        isForegroundPinned = true,
                        notificationId = NOTIFICATION_ID,
                        runtimeReason = latestRuntimeReason,
                    )
                notificationFactory.show(NOTIFICATION_ID, notification)
            }
            KeepAliveNotificationMode.LOCATION_FOREGROUND -> {
                val notification =
                    notificationFactory.buildNotification(
                        isForegroundPinned = true,
                        notificationId = NOTIFICATION_ID,
                        runtimeReason = latestRuntimeReason,
                    )
                val foregroundStarted =
                    runCatching { startForeground(NOTIFICATION_ID, notification) }
                        .onFailure { error ->
                            DebugTelemetry.log(
                                TELEMETRY_TAG,
                                "startForegroundFailed mode=location_foreground " +
                                    "error=${error.javaClass.simpleName} " +
                                    "message=${error.localizedMessage?.sanitizeTelemetryValue() ?: "na"}",
                            )
                        }.isSuccess
                if (!foregroundStarted) {
                    notificationFactory.show(NOTIFICATION_ID, notification)
                    keepAliveNotificationMode = KeepAliveNotificationMode.PINNED_NOTIFICATION
                    keepAliveNotificationRuntimeReason = latestRuntimeReason
                    return
                }
            }
        }

        keepAliveNotificationMode = desiredMode
        keepAliveNotificationRuntimeReason = latestRuntimeReason
    }

    private fun isGuidanceRuntimeReason(reason: String): Boolean =
        reason == NavigationRuntimeDemandReason.GUIDANCE_VISIBLE ||
            reason == NavigationRuntimeDemandReason.GUIDANCE_AMBIENT ||
            reason == NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND

    private fun publishAcceptedLocation(location: Location) {
        _currentLocation.value = location
        if (!_acceptedLocationEvents.tryEmit(location)) {
            DebugTelemetry.log(
                TELEMETRY_TAG,
                "event=recording_location_delivery_dropped reason=buffer_full",
            )
        }
    }

    private fun currentLocationSourceMode(): LocationSourceMode =
        when {
            latestGpsDebugTelemetry &&
                latestPassiveLocationExperiment &&
                !latestWatchGpsOnly &&
                !selfHealFailoverCoordinator.isAutoFusedFallbackToWatchGps() -> LocationSourceMode.PASSIVE_EXTERNAL
            else -> selfHealFailoverCoordinator.currentLocationSourceMode()
        }

    private fun currentWatchGpsReason(): String =
        watchGpsReason(
            sourceMode = currentLocationSourceMode(),
            watchOnlyRequested = latestWatchGpsOnly,
        )

    private fun watchGpsReason(
        sourceMode: LocationSourceMode,
        watchOnlyRequested: Boolean,
    ): String =
        when {
            sourceMode != LocationSourceMode.WATCH_GPS -> "not_watch_gps"
            watchOnlyRequested -> "user_setting"
            else -> "auto_failover"
        }

    private fun currentLocationGateway(): LocationGateway = locationGatewayFor(currentLocationSourceMode())

    private fun locationGatewayFor(sourceMode: LocationSourceMode): LocationGateway =
        when (sourceMode) {
            LocationSourceMode.AUTO_FUSED -> fusedLocationGateway
            LocationSourceMode.PASSIVE_EXTERNAL -> passiveExternalLocationGateway
            LocationSourceMode.WATCH_GPS -> watchGpsLocationGateway
        }

    private suspend fun removeAllLocationUpdates() {
        var firstError: Exception? = null
        try {
            fusedLocationGateway.removeLocationUpdates()
        } catch (error: Exception) {
            firstError = error
        }
        try {
            passiveExternalLocationGateway.removeLocationUpdates()
        } catch (error: Exception) {
            if (firstError == null) {
                firstError = error
            }
        }
        try {
            watchGpsLocationGateway.removeLocationUpdates()
        } catch (error: Exception) {
            if (firstError == null) {
                firstError = error
            }
        }
        firstError?.let { throw it }
    }

    private suspend fun removeInactiveLocationUpdates(activeSourceMode: LocationSourceMode) {
        when (activeSourceMode) {
            LocationSourceMode.AUTO_FUSED -> {
                passiveExternalLocationGateway.removeLocationUpdates()
                watchGpsLocationGateway.removeLocationUpdates()
            }
            LocationSourceMode.PASSIVE_EXTERNAL -> {
                fusedLocationGateway.removeLocationUpdates()
                watchGpsLocationGateway.removeLocationUpdates()
            }
            LocationSourceMode.WATCH_GPS -> {
                fusedLocationGateway.removeLocationUpdates()
                passiveExternalLocationGateway.removeLocationUpdates()
            }
        }
    }

    private fun removeAllLocationUpdatesBestEffort() {
        fusedLocationGateway.removeLocationUpdatesBestEffort()
        passiveExternalLocationGateway.removeLocationUpdatesBestEffort()
        watchGpsLocationGateway.removeLocationUpdatesBestEffort()
    }

    private fun shouldUseLocationForegroundMode(): Boolean {
        val locationAllowedByUiState =
            latestTrackingEnabled &&
                (latestScreenState.isInteractive || effectiveBackgroundGpsEnabled())
        return locationAllowedByUiState
    }

    private fun isNonInteractiveScreenState(): Boolean = latestScreenState.isNonInteractive

    private fun effectiveBackgroundGpsEnabled(): Boolean = latestAmbientGps || latestRuntimeBackgroundGps

    companion object {
        const val EXTRA_KEEP_APP_OPEN = "extra_keep_app_open"
        const val EXTRA_TRACKING_ENABLED = "extra_tracking_enabled"
        const val EXTRA_SCREEN_STATE = "extra_screen_state"
        const val EXTRA_BACKGROUND_GPS_ENABLED = "extra_background_gps_enabled"
        const val EXTRA_RUNTIME_REASON = "extra_runtime_reason"
        private const val NAVIGATE_ONE_SHOT_TIMEOUT_MS = 6_000L
        private const val HARD_STALE_FIX_MAX_AGE_INTERACTIVE_MS = 20_000L
        private const val HARD_STALE_FIX_MAX_AGE_PASSIVE_MS = 60_000L
        private const val SOURCE_MODE_WARMUP_MS = 1_500L
        private const val MIN_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS = 1_000L
        private const val MAX_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS = 10_000L
        private const val ACCEPTED_LOCATION_EVENT_BUFFER_CAPACITY = 64
    }
}

private fun String.sanitizeTelemetryValue(): String =
    replace(Regex("\\s+"), "_")
        .take(80)

private fun String.isGuidanceRuntimeReason(): Boolean =
    this == NavigationRuntimeDemandReason.GUIDANCE_VISIBLE ||
        this == NavigationRuntimeDemandReason.GUIDANCE_AMBIENT ||
        this == NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND ||
        this == NavigationRuntimeDemandReason.RECORDING_GUIDANCE
