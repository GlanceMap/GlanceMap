package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.data.repository.GpxRepository
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.SyncManager
import com.glancemap.glancemapwearos.presentation.features.gpx.parseGpxData
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.buildRecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorConnectionStatus
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.RecordingSensorMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class TraceRecordingViewModel(
    private val gpxRepository: GpxRepository,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager,
    private val elevationProvider: RecordingElevationProvider,
    private val draftStore: TraceRecordingDraftStore,
    private val applicationContext: Context? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TraceRecordingUiState())
    val uiState: StateFlow<TraceRecordingUiState> = _uiState.asStateFlow()
    private val _startWarning = MutableStateFlow<RecordingStartWarning?>(null)
    val startWarning: StateFlow<RecordingStartWarning?> = _startWarning.asStateFlow()
    private val _locationStartWarning = MutableStateFlow<RecordingLocationStartWarning?>(null)
    val locationStartWarning: StateFlow<RecordingLocationStartWarning?> = _locationStartWarning.asStateFlow()

    private var sampleIntervalSeconds = SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS
    private var recordingElevationSource = SettingsRepository.DEFAULT_RECORDING_ELEVATION_SOURCE
    private var selectedDemSource = DemSource.DEFAULT
    private var recordingHeartRateSource = SettingsRepository.DEFAULT_RECORDING_HEART_RATE_SOURCE
    private var recordingCadenceSource = SettingsRepository.DEFAULT_RECORDING_CADENCE_SOURCE
    private var recordingSpeedSource = SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE
    private var recordingDistanceSource = SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE
    private var recordingStepsSource = SettingsRepository.DEFAULT_RECORDING_STEPS_SOURCE
    private var recordingAutoPauseMode = SettingsRepository.DEFAULT_RECORDING_AUTO_PAUSE_MODE
    private var recordingTrackSmoothingMode = SettingsRepository.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE
    private var recordingProgressVibrationMode = SettingsRepository.DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE
    private var recordingExternalHeartRateAddress: String? = null
    private var recordingExternalRunPodAddress: String? = null
    private var userWeightKg = SettingsRepository.DEFAULT_USER_WEIGHT_KG
    private var backpackWeightKg = SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG
    private var bikeWeightKg = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG
    private var activityProfile = SettingsRepository.DEFAULT_ACTIVITY_PROFILE
    private var showSavedGpxOnMap = SettingsRepository.DEFAULT_RECORDING_SHOW_SAVED_GPX_ON_MAP
    private var lastAcceptedElapsedMs: Long = Long.MIN_VALUE
    private val locationPointMutex = Mutex()
    private var skippedIntervalCount = 0
    private var skippedPausedCount = 0
    private var skippedUnusableLocationCount = 0
    private var demElevationHitCount = 0
    private var demElevationMissCount = 0
    private var gpsElevationUsedCount = 0
    private var lastDemTileId: String? = null
    private var lastDemAxisLen: Int? = null
    private var lastDemResolutionLabel: String? = null
    private var acceptedAccuracySumMeters = 0.0
    private var acceptedAccuracyCount = 0
    private var acceptedAccuracyMinMeters: Float? = null
    private var acceptedAccuracyMaxMeters: Float? = null
    private var lastAcceptedPointTimeMillis: Long? = null
    private var gpsActiveDurationMillis: Long = 0L
    private var recordingGapCount: Int = 0
    private var recordingMaxGapMillis: Long = 0L
    private var lastUiAction: String? = null
    private var gapRecoveryAcceptCount = 0
    private var maxSkippedIntervalElapsedMs = 0L
    private var lastSkippedIntervalElapsedMs = 0L
    private var lastLiveFixProvider: String? = null
    private var lastLiveFixAccuracyMeters: Float? = null
    private var lastLiveFixTimeMillis: Long? = null
    private var startNewSegmentOnNextPoint = false
    private var suppressedJitterPointCount = 0
    private var suppressedJitterDistanceMeters = 0.0
    private var latestSensorMetrics = RecordingSensorMetrics()
    private var heartRateSensorEventCount = 0
    private var stepSensorEventCount = 0
    private var cadenceSensorEventCount = 0
    private var pressureSensorEventCount = 0
    private var externalDistanceBaseMeters: Double? = null
    private var externalSessionDistanceMeters: Double? = null
    private var externalIntegratedDistanceMeters: Double? = null
    private var externalSpeedIntegrationLastTimeMillis: Long? = null
    private var externalSpeedIntegrationLastMps: Float? = null
    private var autoPauseStationarySinceElapsedMs: Long? = null
    private var autoPauseMovingSinceElapsedMs: Long? = null
    private var autoPauseTriggerCount = 0
    private var autoResumeTriggerCount = 0
    private val recordingFixQualityGate = RecordingFixQualityGate()
    private var qualityHeldFixCount = 0
    private var qualityRejectedFixCount = 0
    private var qualityRelocationCount = 0
    private var smoothedPointCount = 0
    private var smoothedAdjustmentMeters = 0.0
    private var maxSmoothedAdjustmentMeters = 0.0
    private var latestRecordingStartLocation: Location? = null
    private var latestGpsSignalSnapshot = GpsSignalSnapshot()
    private var pendingRecordingStartSource: String? = null
    private var pendingRecordingStartTimeout: Job? = null
    private val recordingProgressVibrationTracker = RecordingProgressVibrationTracker()
    private var recordingProgressVibrationTimeJob: Job? = null

    init {
        settingsRepository.recordingSampleIntervalSeconds
            .onEach { sampleIntervalSeconds = it }
            .launchIn(viewModelScope)
        settingsRepository.recordingAutoPauseMode
            .onEach { recordingAutoPauseMode = it }
            .launchIn(viewModelScope)
        settingsRepository.recordingTrackSmoothingMode
            .onEach { nextMode ->
                recordingTrackSmoothingMode = nextMode
                val state = _uiState.value
                if (state.active && state.trackSmoothingMode != nextMode) {
                    DebugTelemetry.log(
                        "TraceRecording",
                        "event=track_smoothing_changed_during_rec " +
                            "sessionMode=${state.trackSmoothingMode} nextMode=$nextMode " +
                            "reason=session_mode_frozen",
                    )
                }
            }.launchIn(viewModelScope)
        settingsRepository.recordingProgressVibrationMode
            .onEach { nextMode ->
                if (recordingProgressVibrationMode == nextMode) return@onEach
                recordingProgressVibrationMode = nextMode
                rebaseRecordingProgressVibration(_uiState.value, System.currentTimeMillis())
                syncRecordingProgressVibrationTimer()
            }.launchIn(viewModelScope)
        settingsRepository.recordingElevationSource
            .onEach { recordingElevationSource = it }
            .launchIn(viewModelScope)
        settingsRepository.demSource
            .onEach { selectedDemSource = it }
            .launchIn(viewModelScope)
        settingsRepository.recordingHeartRateSource
            .onEach { recordingHeartRateSource = it }
            .launchIn(viewModelScope)
        settingsRepository.recordingCadenceSource
            .onEach { source ->
                recordingCadenceSource = source
                updateRecordingSourceState()
            }.launchIn(viewModelScope)
        settingsRepository.recordingSpeedSource
            .onEach { source ->
                recordingSpeedSource = source
                updateRecordingSourceState()
            }.launchIn(viewModelScope)
        settingsRepository.recordingDistanceSource
            .onEach { source ->
                recordingDistanceSource = source
                updateRecordingSourceState()
            }.launchIn(viewModelScope)
        settingsRepository.recordingStepsSource
            .onEach { source ->
                recordingStepsSource = source
                updateRecordingSourceState()
            }.launchIn(viewModelScope)
        settingsRepository.recordingExternalHeartRateAddress
            .onEach { recordingExternalHeartRateAddress = it }
            .launchIn(viewModelScope)
        settingsRepository.recordingExternalRunPodAddress
            .onEach { recordingExternalRunPodAddress = it }
            .launchIn(viewModelScope)
        settingsRepository.userWeightKg
            .onEach { userWeightKg = it }
            .launchIn(viewModelScope)
        settingsRepository.backpackWeightKg
            .onEach { backpackWeightKg = it }
            .launchIn(viewModelScope)
        settingsRepository.bikeWeightKg
            .onEach { bikeWeightKg = it }
            .launchIn(viewModelScope)
        settingsRepository.activityProfile
            .onEach { nextProfile ->
                val previousProfile = activityProfile
                activityProfile = nextProfile
                if (previousProfile != nextProfile && _uiState.value.active) {
                    DebugTelemetry.log(
                        "TraceRecording",
                        "event=activity_profile_changed_during_rec " +
                            "from=$previousProfile to=$nextProfile " +
                            "sampleIntervalSeconds=$sampleIntervalSeconds " +
                            "reason=session_profile_frozen",
                    )
                }
            }.launchIn(viewModelScope)
        settingsRepository.recordingShowSavedGpxOnMap
            .onEach { showSavedGpxOnMap = it }
            .launchIn(viewModelScope)
        restoreDraftIfPresent()
    }

    fun toggleRecording() {
        val state = _uiState.value
        when {
            state.saving -> Unit
            state.active && state.paused -> resumeRecording()
            state.active -> pauseRecording()
            else -> startRecording()
        }
    }

    fun consumeMessage(message: String) {
        val state = _uiState.value
        if (state.message == message) {
            _uiState.value = state.copy(message = null)
        }
    }

    fun startRecording() {
        if (isRecordingStartBlocked()) return
        if (isLocationReadyForRecordingStart()) {
            startRecordingAfterLocationPreflight()
        } else {
            waitForFreshLocationBeforeStartingRecording(source = "rec_tap")
        }
    }

    private fun isRecordingStartBlocked(): Boolean {
        val state = _uiState.value
        if (state.active || state.saving) return true
        return _startWarning.value != null ||
            _locationStartWarning.value != null ||
            pendingRecordingStartSource != null
    }

    private fun startRecordingAfterLocationPreflight() {
        val heartRateAvailability =
            ExternalSensorConnectionStatus.availabilitySummary(recordingExternalHeartRateAddress)
        val runPodAvailability =
            ExternalSensorConnectionStatus.availabilitySummary(recordingExternalRunPodAddress)
        val warning =
            resolveRecordingStartWarning(
                heartRateSource = recordingHeartRateSource,
                cadenceSource = recordingCadenceSource,
                speedSource = recordingSpeedSource,
                distanceSource = recordingDistanceSource,
                externalHeartRateAddress = recordingExternalHeartRateAddress,
                externalRunPodAddress = recordingExternalRunPodAddress,
                connectedExternalAddresses =
                    buildSet {
                        addRecentlyAvailableAddress(recordingExternalHeartRateAddress)
                        addRecentlyAvailableAddress(recordingExternalRunPodAddress)
                    },
            )
        DebugTelemetry.log(
            "TraceRecording",
            "event=external_sensor_preflight " +
                "heartRateSelected=${
                    recordingHeartRateSource == SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP
                } heartRateAvailability=$heartRateAvailability " +
                "runPodSelected=${
                    recordingCadenceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD ||
                        recordingSpeedSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD ||
                        recordingDistanceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD
                } runPodAvailability=$runPodAvailability warning=${warning != null}",
        )
        if (warning != null) {
            _startWarning.value = warning
            DebugTelemetry.log(
                "TraceRecording",
                "event=external_sensor_start_warning " +
                    "unlinked=${warning.unlinkedDevices.joinToString(separator = ",").ifBlank { "none" }} " +
                    "disconnected=${warning.disconnectedDevices.joinToString(separator = ",").ifBlank { "none" }}",
            )
            return
        }

        startRecordingNow()
    }

    private fun MutableSet<String>.addRecentlyAvailableAddress(address: String?) {
        if (!ExternalSensorConnectionStatus.isConnectedOrRecentlyVerified(address)) return
        ExternalSensorConnectionStatus.normalizedAddress(address)?.let(::add)
    }

    fun confirmStartRecordingWithUnavailableSensors() {
        val warning = _startWarning.value ?: return
        _startWarning.value = null
        DebugTelemetry.log(
            "TraceRecording",
            "event=external_sensor_start_warning_confirmed " +
                "unlinked=${warning.unlinkedDevices.size} disconnected=${warning.disconnectedDevices.size}",
        )
        if (ensureLocationReadyForRecordingStart(source = "external_sensor_warning_confirm")) {
            startRecordingNow()
        }
    }

    fun switchUnavailableSensorSourcesToWatchAndStartRecording() {
        val warning = _startWarning.value ?: return
        val state = _uiState.value
        if (state.active || state.saving) return
        _startWarning.value = null
        DebugTelemetry.log(
            "TraceRecording",
            "event=external_sensor_start_warning_use_watch " +
                "unlinked=${warning.unlinkedDevices.size} disconnected=${warning.disconnectedDevices.size}",
        )
        viewModelScope.launch {
            recordingHeartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH
            recordingCadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS
            recordingSpeedSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS
            recordingDistanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS
            recordingStepsSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS
            settingsRepository.setRecordingHeartRateSource(SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH)
            settingsRepository.setRecordingCadenceSource(SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS)
            settingsRepository.setRecordingSpeedSource(SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS)
            settingsRepository.setRecordingDistanceSource(SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS)
            settingsRepository.setRecordingStepsSource(SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS)
            if (ensureLocationReadyForRecordingStart(source = "external_sensor_warning_use_watch")) {
                startRecordingNow()
            }
        }
    }

    fun cancelStartRecordingWithUnavailableSensors() {
        if (_startWarning.value == null) return
        _startWarning.value = null
        DebugTelemetry.log("TraceRecording", "event=external_sensor_start_warning_cancelled")
    }

    fun cancelStartRecordingWithoutLocation() {
        if (_locationStartWarning.value == null) return
        _locationStartWarning.value = null
        DebugTelemetry.log("TraceRecording", "event=location_start_warning_cancelled")
    }

    fun onGpsSignalSnapshot(snapshot: GpsSignalSnapshot) {
        latestGpsSignalSnapshot = snapshot
        startPendingRecordingWhenLocationReady()
    }

    private fun startRecordingNow() {
        val now = System.currentTimeMillis()
        lastAcceptedElapsedMs = Long.MIN_VALUE
        resetSessionTelemetry()
        lastUiAction = "start"
        _uiState.value =
            TraceRecordingUiState(
                active = true,
                paused = false,
                activityProfile = activityProfile,
                trackSmoothingMode = recordingTrackSmoothingMode,
                startedAtMillis = now,
                cadenceSource = recordingCadenceSource,
                speedSource = recordingSpeedSource,
                distanceSource = recordingDistanceSource,
                stepsSource = recordingStepsSource,
                message = "REC on · ${recordingProfileLabel(activityProfile)}",
            )
        recordingProgressVibrationTracker.start(recordingProgressVibrationMode)
        syncRecordingProgressVibrationTimer()
        DebugTelemetry.log(
            "TraceRecording",
            "event=start sampleIntervalSeconds=$sampleIntervalSeconds elevationSource=$recordingElevationSource " +
                "trackSmoothingMode=$recordingTrackSmoothingMode " +
                "trackFilterVersion=$RECORDING_TRACK_FILTER_VERSION " +
                "cadenceSource=$recordingCadenceSource speedSource=$recordingSpeedSource " +
                "distanceSource=$recordingDistanceSource stepsSource=$recordingStepsSource " +
                "activityProfile=$activityProfile " +
                "draftPath=${sanitizeTelemetryValue(draftStore.draftPath())} lastUiAction=$lastUiAction",
        )
        persistDraftAsync(reason = "start")
    }

    fun onLocation(location: Location?) {
        latestRecordingStartLocation = location
        startPendingRecordingWhenLocationReady()
        if (location == null) return
        val state = _uiState.value
        if (!state.active || state.saving) return
        if (state.paused && !state.autoPaused) {
            skippedPausedCount += 1
            return
        }
        if (!isGpsSamplingEnabled()) {
            skippedIntervalCount += 1
            return
        }
        if (!isUsableLocation(location)) {
            skippedUnusableLocationCount += 1
            return
        }

        val livePoint = livePointFromLocation(location)
        lastLiveFixProvider = location.provider
        lastLiveFixAccuracyMeters = livePoint.accuracyMeters
        lastLiveFixTimeMillis = livePoint.timeMillis
        _uiState.value = _uiState.value.copy(latestLivePoint = livePoint)

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (state.paused && state.autoPaused) {
            if (!maybeAutoResumeRecording(livePoint = livePoint, nowElapsedMs = nowElapsedMs)) {
                skippedPausedCount += 1
                return
            }
        } else if (maybeAutoPauseRecording(state = state, livePoint = livePoint, nowElapsedMs = nowElapsedMs)) {
            skippedPausedCount += 1
            return
        }
        val elapsedSinceAcceptedMs =
            if (lastAcceptedElapsedMs != Long.MIN_VALUE) {
                nowElapsedMs - lastAcceptedElapsedMs
            } else {
                -1L
            }
        val sampleAcceptThresholdMillis = recordingSampleAcceptThresholdMillis()
        if (elapsedSinceAcceptedMs >= 0L) {
            val elapsedMs = elapsedSinceAcceptedMs
            if (elapsedMs < sampleAcceptThresholdMillis) {
                skippedIntervalCount += 1
                lastSkippedIntervalElapsedMs = elapsedMs
                maxSkippedIntervalElapsedMs = maxOf(maxSkippedIntervalElapsedMs, elapsedMs)
                if (skippedIntervalCount % RECORDING_LIVE_TELEMETRY_SKIP_INTERVAL == 0) {
                    val nowMillis = System.currentTimeMillis()
                    DebugTelemetry.log(
                        "TraceRecording",
                        "event=live_fix_skipped_interval skippedInterval=$skippedIntervalCount " +
                            "sampleIntervalSeconds=$sampleIntervalSeconds " +
                            "acceptThresholdMs=$sampleAcceptThresholdMillis " +
                            "elapsedSinceAcceptedMs=$elapsedMs " +
                            "maxSkippedIntervalElapsedMs=$maxSkippedIntervalElapsedMs " +
                            "liveFixAgeMs=${livePoint.timeMillis.let { nowMillis - it }.coerceAtLeast(0L)} " +
                            "lastPointAgeMs=${_uiState.value.points
                                .lastOrNull()
                                ?.timeMillis
                                ?.let { nowMillis - it }
                                ?.coerceAtLeast(0L) ?: -1} " +
                            "provider=${sanitizeTelemetryValue(location.provider ?: "na")} " +
                            "speedMps=${livePoint.speedMps ?: -1f} " +
                            "accuracyMeters=${livePoint.accuracyMeters?.toInt() ?: -1}",
                    )
                }
                return
            }
        }
        val previousRecordedPoint = _uiState.value.points.lastOrNull()
        val suppressedJitterDistance =
            previousRecordedPoint
                ?.takeIf { !startNewSegmentOnNextPoint }
                ?.let { previous ->
                    recordingJitterDistanceToSuppress(
                        previous = previous,
                        candidate = livePoint,
                    )
                }
        if (suppressedJitterDistance != null) {
            suppressedJitterPointCount += 1
            suppressedJitterDistanceMeters += suppressedJitterDistance
            if (suppressedJitterPointCount == 1 ||
                suppressedJitterPointCount % RECORDING_JITTER_TELEMETRY_INTERVAL == 0
            ) {
                DebugTelemetry.log(
                    "TraceRecording",
                    "event=jitter_suppressed count=$suppressedJitterPointCount " +
                        "distanceMeters=${suppressedJitterDistance.formatTelemetry(1)} " +
                        "totalDistanceMeters=${suppressedJitterDistanceMeters.formatTelemetry(1)} " +
                        "speedMps=${livePoint.speedMps?.formatTelemetry(2) ?: "na"} " +
                        "accuracyMeters=${livePoint.accuracyMeters?.formatTelemetry(1) ?: "na"}",
                )
            }
            return
        }
        val fixQualityResult =
            recordingFixQualityGate.evaluate(
                candidate =
                    RecordingFixSample(
                        latLong = livePoint.latLong,
                        timeMillis = livePoint.timeMillis,
                        elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000L,
                        accuracyMeters = livePoint.accuracyMeters,
                        speedMps = livePoint.speedMps,
                        speedAccuracyMps =
                            if (location.hasSpeedAccuracy()) {
                                location.speedAccuracyMetersPerSecond
                                    .takeIf { it.isFinite() && it >= 0f }
                            } else {
                                null
                            },
                    ),
                activityProfile = state.activityProfile,
            )
        if (!fixQualityResult.accepted) {
            when (fixQualityResult.status) {
                RecordingFixQualityStatus.HELD -> qualityHeldFixCount += 1
                RecordingFixQualityStatus.REJECTED -> qualityRejectedFixCount += 1
                RecordingFixQualityStatus.ACCEPTED -> Unit
            }
            val eventCount = qualityHeldFixCount + qualityRejectedFixCount
            if (eventCount == 1 || eventCount % RECORDING_QUALITY_TELEMETRY_INTERVAL == 0) {
                DebugTelemetry.log(
                    "TraceRecording",
                    "event=fix_quality_${fixQualityResult.status.name.lowercase(Locale.ROOT)} " +
                        "reason=${fixQualityResult.reason.name.lowercase(Locale.ROOT)} " +
                        "held=$qualityHeldFixCount rejected=$qualityRejectedFixCount " +
                        "accuracyMeters=${livePoint.accuracyMeters?.formatTelemetry(1) ?: "na"} " +
                        "speedMps=${livePoint.speedMps?.formatTelemetry(2) ?: "na"} " +
                        "provider=${sanitizeTelemetryValue(location.provider ?: "na")}",
                )
            }
            return
        }
        // A REC session stays continuous despite transient GPS gaps or fix reacquisition.
        // Only a user-initiated pause/resume creates an explicit GPX segment boundary.
        val startsNewSegmentForPoint = startNewSegmentOnNextPoint
        startNewSegmentOnNextPoint = false
        if (fixQualityResult.reason == RecordingFixQualityReason.CONFIRMED_RELOCATION) {
            qualityRelocationCount += 1
            DebugTelemetry.log(
                "TraceRecording",
                "event=fix_quality_confirmed_relocation count=$qualityRelocationCount " +
                    "action=continue_track accuracyMeters=${livePoint.accuracyMeters?.formatTelemetry(1) ?: "na"}",
            )
        }
        if (fixQualityResult.reason == RecordingFixQualityReason.CONFIRMED_SUSTAINED_MOVEMENT) {
            DebugTelemetry.log(
                "TraceRecording",
                "event=fix_quality_confirmed_sustained_movement action=continue_segment " +
                    "speedMps=${livePoint.speedMps?.formatTelemetry(2) ?: "na"} " +
                    "accuracyMeters=${livePoint.accuracyMeters?.formatTelemetry(1) ?: "na"}",
            )
        }
        if (elapsedSinceAcceptedMs >= recordingGapTelemetryThresholdMillis()) {
            gapRecoveryAcceptCount += 1
            DebugTelemetry.log(
                "TraceRecording",
                "event=gap_recovery_accept gapRecoveryAcceptCount=$gapRecoveryAcceptCount " +
                    "elapsedSinceAcceptedMs=$elapsedSinceAcceptedMs " +
                    "thresholdMs=${recordingGapTelemetryThresholdMillis()} " +
                    "sampleIntervalSeconds=$sampleIntervalSeconds " +
                    "provider=${sanitizeTelemetryValue(location.provider ?: "na")} " +
                    "accuracyMeters=${livePoint.accuracyMeters?.toInt() ?: -1}",
            )
        }
        lastAcceptedElapsedMs = nowElapsedMs
        val latitude = location.latitude
        val longitude = location.longitude
        val gpsAltitudeMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() }
        val timeMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }
        val speedMps = location.speed.takeIf { location.hasSpeed() }
        val selectedElevationSource = recordingElevationSource
        viewModelScope.launch {
            locationPointMutex.withLock {
                val elevation =
                    elevationProvider.resolveElevation(
                        latitude = latitude,
                        longitude = longitude,
                        gpsAltitudeMeters = gpsAltitudeMeters,
                        source = selectedElevationSource,
                        demSource = selectedDemSource,
                    )
                if (elevation.demAttempted) {
                    if (elevation.demHit) {
                        demElevationHitCount += 1
                        lastDemTileId = elevation.demTileId
                        lastDemAxisLen = elevation.demAxisLen
                        lastDemResolutionLabel = elevation.demResolutionLabel
                    } else {
                        demElevationMissCount += 1
                    }
                }
                if (elevation.gpsUsed) {
                    gpsElevationUsedCount += 1
                }
                val sensorMetrics = latestFreshSensorMetrics(nowMillis = System.currentTimeMillis())
                val startsNewSegment = startsNewSegmentForPoint
                val point =
                    RecordedTracePoint(
                        latLong = LatLong(latitude, longitude),
                        elevationMeters = elevation.elevationMeters,
                        timeMillis = timeMillis,
                        accuracyMeters = accuracyMeters,
                        speedMps = speedMps,
                        elevationSource = elevation.resolvedSource,
                        heartRateBpm = sensorMetrics?.heartRateBpm,
                        stepCount = sensorMetrics?.stepCount,
                        cadenceSpm = sensorMetrics?.cadenceSpm,
                        powerWatts = sensorMetrics?.externalPowerWatts,
                        barometricPressureHpa = sensorMetrics?.barometricPressureHpa,
                        startsNewSegment = startsNewSegment,
                    )
                val currentState = _uiState.value
                if (!currentState.active || currentState.saving) return@withLock
                val originalMiddle = currentState.points.lastOrNull()
                val beforeMiddle = currentState.points.getOrNull(currentState.points.lastIndex - 1)
                val smoothingResult =
                    if (beforeMiddle != null && originalMiddle != null) {
                        smoothRecordingMiddlePoint(
                            before = beforeMiddle,
                            middle = originalMiddle,
                            after = point,
                            options =
                                RecordingPointSmoothingOptions(
                                    mode = currentState.trackSmoothingMode,
                                    activityProfile = currentState.activityProfile,
                                    sampleIntervalSeconds = effectiveSampleIntervalSeconds(),
                                ),
                        )
                    } else {
                        null
                    }
                val pointsBeforeAppend =
                    if (smoothingResult != null) {
                        currentState.points.dropLast(1) + smoothingResult.point
                    } else {
                        currentState.points
                    }
                if (smoothingResult != null) {
                    smoothedPointCount += 1
                    smoothedAdjustmentMeters += smoothingResult.adjustmentMeters
                    maxSmoothedAdjustmentMeters =
                        maxOf(maxSmoothedAdjustmentMeters, smoothingResult.adjustmentMeters)
                    if (
                        smoothedPointCount == 1 ||
                        smoothedPointCount % RECORDING_SMOOTHING_TELEMETRY_INTERVAL == 0
                    ) {
                        DebugTelemetry.log(
                            "TraceRecording",
                            "event=track_point_smoothed mode=${currentState.trackSmoothingMode} " +
                                "count=$smoothedPointCount " +
                                "adjustmentMeters=${smoothingResult.adjustmentMeters.formatTelemetry(2)} " +
                                "totalAdjustmentMeters=${smoothedAdjustmentMeters.formatTelemetry(1)} " +
                                "maxAdjustmentMeters=${maxSmoothedAdjustmentMeters.formatTelemetry(2)}",
                        )
                    }
                }
                val previous = pointsBeforeAppend.lastOrNull()
                val previousSegmentCorrection =
                    if (smoothingResult != null && beforeMiddle != null && originalMiddle != null) {
                        haversineMeters(beforeMiddle.latLong, smoothingResult.point.latLong) -
                            haversineMeters(beforeMiddle.latLong, originalMiddle.latLong)
                    } else {
                        0.0
                    }
                val addedDistance =
                    if (startsNewSegment) {
                        0.0
                    } else {
                        previousSegmentCorrection +
                            (previous?.let { haversineMeters(it.latLong, point.latLong) } ?: 0.0)
                    }
                val pointCount = currentState.points.size + 1
                updateGapTelemetry(
                    previousPoint = previous,
                    point = point,
                    provider = location.provider,
                    nextPointCount = pointCount,
                )
                updateAccuracyTelemetry(point.accuracyMeters)
                val updatedState =
                    currentState.copy(
                        points = pointsBeforeAppend + point,
                        latestLivePoint = point,
                        distanceMeters = currentState.distanceMeters + addedDistance,
                        gpsActiveDurationMillis = gpsActiveDurationMillis,
                        recordingGapCount = recordingGapCount,
                        recordingMaxGapMillis = recordingMaxGapMillis,
                        message = null,
                    )
                _uiState.value =
                    updatedState
                maybeTriggerRecordingProgressVibration(
                    state = updatedState,
                    nowMillis = System.currentTimeMillis(),
                )
                if (pointCount == 1 || pointCount % RECORDING_TELEMETRY_POINT_INTERVAL == 0) {
                    DebugTelemetry.log(
                        "TraceRecording",
                        "event=point points=$pointCount " +
                            "distanceMeters=${(currentState.distanceMeters + addedDistance).toInt()} " +
                            "accuracyMeters=${point.accuracyMeters?.toInt() ?: -1} " +
                            "elevationMeters=${point.elevationMeters?.toInt() ?: -1} " +
                            "elevationSource=${point.elevationSource ?: "na"} " +
                            "demHits=$demElevationHitCount demMisses=$demElevationMissCount " +
                            "demResolution=${lastDemResolutionLabel ?: "na"} " +
                            "demAxisLen=${lastDemAxisLen ?: -1} demTile=${lastDemTileId ?: "na"} " +
                            "gpsElevationUsed=$gpsElevationUsedCount " +
                            "gpsActiveDurationMs=$gpsActiveDurationMillis " +
                            "recordingGapCount=$recordingGapCount recordingMaxGapMs=$recordingMaxGapMillis " +
                            sensorTelemetryTokens(nowMillis = System.currentTimeMillis()) + " " +
                            "heartRateBpm=${point.heartRateBpm ?: -1} stepCount=${point.stepCount ?: -1} " +
                            "cadenceSpm=${point.cadenceSpm ?: -1} " +
                            "powerWatts=${point.powerWatts ?: -1} " +
                            "pressureHpa=${point.barometricPressureHpa?.toInt() ?: -1} " +
                            "skippedInterval=$skippedIntervalCount skippedPaused=$skippedPausedCount " +
                            "skippedUnusable=$skippedUnusableLocationCount " +
                            "qualityHeld=$qualityHeldFixCount qualityRejected=$qualityRejectedFixCount " +
                            "qualityRelocations=$qualityRelocationCount smoothedPoints=$smoothedPointCount",
                    )
                }
                persistDraft(state = updatedState, reason = "point")
            }
        }
    }

    private fun ensureLocationReadyForRecordingStart(source: String): Boolean {
        if (isLocationReadyForRecordingStart()) return true
        val location = latestRecordingStartLocation
        val hasUsableLocation = location?.let(::isUsableLocation) == true
        _locationStartWarning.value =
            RecordingLocationStartWarning
        DebugTelemetry.log(
            "TraceRecording",
            "event=start_blocked_no_fresh_location source=$source " +
                "locationPresent=${location != null} usableLocation=$hasUsableLocation " +
                "locationAvailable=${latestGpsSignalSnapshot.isLocationAvailable} " +
                "lastFixFresh=${latestGpsSignalSnapshot.lastFixFresh} " +
                "lastFixAgeMs=${latestGpsSignalSnapshot.lastFixAgeMs}",
        )
        return false
    }

    private fun isLocationReadyForRecordingStart(): Boolean =
        isRecordingStartLocationReady(
            hasUsableLocation = latestRecordingStartLocation?.let(::isUsableLocation) == true,
            gpsSignalSnapshot = latestGpsSignalSnapshot,
        )

    private fun waitForFreshLocationBeforeStartingRecording(source: String) {
        pendingRecordingStartSource = source
        _uiState.value = _uiState.value.copy(message = "Starting REC…")
        DebugTelemetry.log(
            "TraceRecording",
            "event=start_waiting_for_fresh_location source=$source " +
                "timeoutMs=$RECORDING_START_FRESH_FIX_TIMEOUT_MS",
        )
        pendingRecordingStartTimeout =
            viewModelScope.launch {
                delay(RECORDING_START_FRESH_FIX_TIMEOUT_MS)
                if (pendingRecordingStartSource != source) return@launch
                pendingRecordingStartSource = null
                pendingRecordingStartTimeout = null
                _uiState.value = _uiState.value.copy(message = null)
                ensureLocationReadyForRecordingStart(source = "${source}_timeout")
            }
    }

    private fun startPendingRecordingWhenLocationReady() {
        val source = pendingRecordingStartSource ?: return
        if (!isLocationReadyForRecordingStart()) return

        pendingRecordingStartSource = null
        pendingRecordingStartTimeout?.cancel()
        pendingRecordingStartTimeout = null
        _uiState.value = _uiState.value.copy(message = null)
        DebugTelemetry.log(
            "TraceRecording",
            "event=start_pending_location_ready source=$source " +
                "lastFixAgeMs=${latestGpsSignalSnapshot.lastFixAgeMs}",
        )
        startRecordingAfterLocationPreflight()
    }

    fun onSensorMetrics(metrics: RecordingSensorMetrics) {
        val state = _uiState.value
        if (!state.active || state.saving) return
        updateSensorEventTelemetry(previous = latestSensorMetrics, next = metrics)
        latestSensorMetrics = metrics
        val integratedDistanceMeters = updateExternalSpeedIntegration(metrics)
        val externalDistanceUpdate =
            metrics.externalDistanceMeters?.let { distance ->
                advanceExternalDistanceSession(
                    totalDistanceMeters = distance,
                    baseMeters = externalDistanceBaseMeters,
                    previousSessionMeters = externalSessionDistanceMeters,
                )
            }
        externalDistanceUpdate?.let { update ->
            externalDistanceBaseMeters = update.baseMeters
            externalSessionDistanceMeters = update.sessionMeters
        }
        val externalDistanceMeters = externalDistanceUpdate?.sessionMeters
        val nextState =
            state.copy(
                heartRateBpm = metrics.heartRateBpm,
                heartRateFromBluetooth = metrics.heartRateFromBluetooth,
                externalSpeedMps = metrics.externalSpeedMps,
                externalRawDistanceUnits = metrics.externalDistanceRawUnits,
                externalDistanceMeters = externalDistanceMeters ?: state.externalDistanceMeters,
                externalIntegratedDistanceMeters = integratedDistanceMeters ?: state.externalIntegratedDistanceMeters,
                externalPowerWatts = metrics.externalPowerWatts,
                externalPowerFromBluetooth = metrics.externalPowerWatts != null,
                externalBatteryLevelPercent = metrics.externalBatteryLevelPercent ?: state.externalBatteryLevelPercent,
                cadenceSource = recordingCadenceSource,
                speedSource = recordingSpeedSource,
                distanceSource = recordingDistanceSource,
                stepsSource = recordingStepsSource,
                stepCount = metrics.stepCount,
                stepCountFromBluetooth = metrics.stepCountFromBluetooth,
                cadenceSpm = metrics.cadenceSpm,
                cadenceFromBluetooth = metrics.cadenceFromBluetooth,
                barometricPressureHpa = metrics.barometricPressureHpa,
            )
        if (nextState != state) {
            _uiState.value = nextState
            maybeTriggerRecordingProgressVibration(
                state = nextState,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun updateRecordingSourceState() {
        val currentState = _uiState.value
        val nextState =
            currentState.copy(
                cadenceSource = recordingCadenceSource,
                speedSource = recordingSpeedSource,
                distanceSource = recordingDistanceSource,
                stepsSource = recordingStepsSource,
            )
        _uiState.value = nextState
        if (
            nextState.active &&
            nextState.distanceSource != currentState.distanceSource &&
            recordingProgressVibrationInterval(recordingProgressVibrationMode) is RecordingProgressVibrationInterval.Distance
        ) {
            rebaseRecordingProgressVibration(nextState, System.currentTimeMillis())
        }
    }

    private fun rebaseRecordingProgressVibration(
        state: TraceRecordingUiState,
        nowMillis: Long,
    ) {
        recordingProgressVibrationTracker.rebase(
            mode = recordingProgressVibrationMode,
            distanceMeters = recordingDisplayDistanceMeters(state),
            activeDurationMillis = recordingActiveDurationMillis(state, nowMillis),
        )
    }

    private fun maybeTriggerRecordingProgressVibration(
        state: TraceRecordingUiState,
        nowMillis: Long,
    ) {
        if (!state.active || state.paused || state.saving) return
        val trigger =
            recordingProgressVibrationTracker.next(
                mode = recordingProgressVibrationMode,
                distanceMeters = recordingDisplayDistanceMeters(state),
                activeDurationMillis = recordingActiveDurationMillis(state, nowMillis),
            ) ?: return
        val vibrated = vibrateRecordingProgress(applicationContext)
        DebugTelemetry.log(
            "TraceRecording",
            "event=progress_vibration type=${trigger.javaClass.simpleName.lowercase()} " +
                "milestone=${trigger.milestone} mode=$recordingProgressVibrationMode " +
                "distanceMeters=${recordingDisplayDistanceMeters(state).toInt()} " +
                "activeDurationMs=${recordingActiveDurationMillis(state, nowMillis)} " +
                "vibratorAvailable=$vibrated",
        )
    }

    private fun syncRecordingProgressVibrationTimer() {
        recordingProgressVibrationTimeJob?.cancel()
        recordingProgressVibrationTimeJob = null
        if (recordingProgressVibrationInterval(recordingProgressVibrationMode) !is RecordingProgressVibrationInterval.Time) {
            return
        }
        val initialState = _uiState.value
        if (!initialState.active || initialState.paused || initialState.saving) return
        recordingProgressVibrationTimeJob =
            viewModelScope.launch {
                while (isActive) {
                    val state = _uiState.value
                    if (!state.active || state.paused || state.saving) return@launch
                    val nowMillis = System.currentTimeMillis()
                    maybeTriggerRecordingProgressVibration(state, nowMillis)
                    val delayMillis =
                        recordingProgressVibrationTracker.millisecondsUntilNextTimeMilestone(
                            recordingActiveDurationMillis(_uiState.value, System.currentTimeMillis()),
                        ) ?: return@launch
                    delay(delayMillis)
                }
            }
    }

    fun pauseRecording() {
        val state = _uiState.value
        if (!state.active || state.paused || state.saving) return
        resetAutoPauseMotionState()
        lastUiAction = "pause"
        _uiState.value =
            state.copy(
                paused = true,
                autoPaused = false,
                pausedAtMillis = System.currentTimeMillis(),
                message = "REC paused",
            )
        syncRecordingProgressVibrationTimer()
        DebugTelemetry.log(
            "TraceRecording",
            "event=pause ${recordingSummaryTokens(state, System.currentTimeMillis())}",
        )
        persistDraftAsync(reason = "pause")
    }

    fun resumeRecording() {
        val state = _uiState.value
        if (!state.active || !state.paused || state.saving) return
        val now = System.currentTimeMillis()
        val addedPausedMillis = state.pausedAtMillis?.let { now - it }?.coerceAtLeast(0L) ?: 0L
        lastAcceptedElapsedMs = Long.MIN_VALUE
        lastAcceptedPointTimeMillis = null
        startNewSegmentOnNextPoint = state.points.isNotEmpty()
        recordingFixQualityGate.reset()
        resetAutoPauseMotionState()
        lastUiAction = "resume"
        _uiState.value =
            state.copy(
                paused = false,
                autoPaused = false,
                pausedAtMillis = null,
                accumulatedPausedMillis = state.accumulatedPausedMillis + addedPausedMillis,
                message = "REC on",
            )
        syncRecordingProgressVibrationTimer()
        DebugTelemetry.log(
            "TraceRecording",
            "event=resume ${recordingSummaryTokens(_uiState.value, now)}",
        )
        persistDraftAsync(reason = "resume")
    }

    fun finishAndSaveRecording(titleOverride: String? = null) {
        val state = _uiState.value
        if (!state.active || state.saving) return
        lastUiAction = "save"
        if (state.points.size < 2) {
            _uiState.value = TraceRecordingUiState(message = "Too few points")
            syncRecordingProgressVibrationTimer()
            DebugTelemetry.log(
                "TraceRecording",
                "event=discard reason=not_enough_points ${recordingSummaryTokens(state, System.currentTimeMillis())}",
            )
            clearDraftAsync(reason = "not_enough_points")
            return
        }

        val now = System.currentTimeMillis()
        val finalPausedMillis =
            if (state.paused) {
                state.pausedAtMillis?.let { now - it }?.coerceAtLeast(0L) ?: 0L
            } else {
                0L
            }
        _uiState.value =
            state.copy(
                active = false,
                paused = false,
                autoPaused = false,
                saving = true,
                accumulatedPausedMillis = state.accumulatedPausedMillis + finalPausedMillis,
                message = "Saving REC",
            )
        syncRecordingProgressVibrationTimer()
        DebugTelemetry.log(
            "TraceRecording",
            "event=save_start ${recordingSummaryTokens(state, now, finalPausedMillis)}",
        )
        viewModelScope.launch {
            val saveResult =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val customTitle = titleOverride?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_RECORDING_TITLE_LENGTH)
                        val startedAtMillis = state.startedAtMillis ?: now
                        val title =
                            customTitle
                                ?: buildRecordingTitle(
                                    startedAtMillis = startedAtMillis,
                                    endedAtMillis = now,
                                )
                        val fileName =
                            uniqueRecordingFileName(
                                startedAtMillis = startedAtMillis,
                                endedAtMillis = now,
                                titleOverride = customTitle,
                            )
                        val summarySnapshot =
                            buildRecordingDashboardSnapshot(
                                state =
                                    state.copy(
                                        accumulatedPausedMillis = state.accumulatedPausedMillis + finalPausedMillis,
                                        paused = false,
                                        autoPaused = false,
                                        pausedAtMillis = null,
                                    ),
                                nowMillis = now,
                                userWeightKg = userWeightKg,
                                backpackWeightKg = backpackWeightKg,
                                bikeWeightKg = bikeWeightKg,
                                activityProfile = state.activityProfile,
                            )
                        val bytes =
                            encodeRecordedTraceAsGpx(
                                title = title,
                                points = state.points,
                                summary =
                                    summarySnapshot.toRecordedTraceSummary(
                                        activityProfile = state.activityProfile,
                                        trackSmoothingMode = state.trackSmoothingMode,
                                    ),
                            )
                        gpxRepository.saveGpxFileAtomic(
                            fileName = fileName,
                            inputStream = ByteArrayInputStream(bytes),
                            onProgress = {},
                            expectedSize = bytes.size.toLong(),
                        )
                        val savedPath = gpxRepository.absolutePathForFileName(fileName)
                        runCatching { parseGpxData(File(savedPath)) }
                            .onSuccess { parsed ->
                                DebugTelemetry.log(
                                    "TraceRecording",
                                    "event=saved_gpx_verified fileName=$fileName " +
                                        "writtenPoints=${state.points.size} parsedPoints=${parsed.points.size} " +
                                        "summaryPoints=${parsed.activitySummary?.pointCount ?: -1} " +
                                        "summaryDistanceMeters=${parsed.activitySummary?.distanceMeters?.toInt() ?: -1}",
                                )
                            }.onFailure { error ->
                                DebugTelemetry.log(
                                    "TraceRecording",
                                    "event=saved_gpx_verify_failed fileName=$fileName " +
                                        "error=${sanitizeTelemetryValue(error.javaClass.simpleName)}",
                                )
                            }
                        if (showSavedGpxOnMap) {
                            val activePaths = gpxRepository.getActiveGpxFiles().first()
                            gpxRepository.setActiveGpxFiles(activePaths + savedPath)
                        }
                        RecordingSaveInfo(fileName = fileName, byteSize = bytes.size)
                    }
                }
            if (saveResult.isSuccess) {
                val saveInfo = saveResult.getOrNull()
                syncManager.requestGpxSync()
                _uiState.value = TraceRecordingUiState(message = "REC saved")
                syncRecordingProgressVibrationTimer()
                draftStore.clear()
                DebugTelemetry.log(
                    "TraceRecording",
                    "event=save_success ${recordingSummaryTokens(state, now, finalPausedMillis)} " +
                        "fileName=${saveInfo?.fileName ?: "na"} byteSize=${saveInfo?.byteSize ?: -1} " +
                        "endReason=user_save",
                )
            } else {
                val errorMessage =
                    saveResult
                        .exceptionOrNull()
                        ?.localizedMessage
                        ?.takeIf { it.isNotBlank() }
                        ?: "Recording save failed"
                _uiState.value =
                    state.copy(
                        active = true,
                        saving = false,
                        message = errorMessage,
                    )
                syncRecordingProgressVibrationTimer()
                DebugTelemetry.log(
                    "TraceRecording",
                    "event=save_failure ${recordingSummaryTokens(state, now, finalPausedMillis)} " +
                        "error=${sanitizeTelemetryValue(errorMessage)}",
                )
            }
        }
    }

    fun discardRecording() {
        val state = _uiState.value
        if (!state.active && !state.saving) return
        lastUiAction = "discard"
        _uiState.value = TraceRecordingUiState(message = "Discarded")
        syncRecordingProgressVibrationTimer()
        clearDraftAsync(reason = "user_discard")
        DebugTelemetry.log(
            "TraceRecording",
            "event=discard reason=user ${recordingSummaryTokens(state, System.currentTimeMillis())}",
        )
    }

    private fun restoreDraftIfPresent() {
        viewModelScope.launch {
            val draft = draftStore.load() ?: return@launch
            if (!draft.active || draft.points.isEmpty()) {
                draftStore.clear()
                return@launch
            }
            resetSessionTelemetry()
            lastAcceptedElapsedMs = Long.MIN_VALUE
            lastUiAction = draft.lastUiAction ?: "restore"
            gpsActiveDurationMillis = draft.gpsActiveDurationMillis
            recordingGapCount = draft.recordingGapCount
            recordingMaxGapMillis = draft.recordingMaxGapMillis
            externalDistanceBaseMeters = null
            externalSessionDistanceMeters = draft.externalDistanceMeters
            externalIntegratedDistanceMeters = draft.externalIntegratedDistanceMeters
            externalSpeedIntegrationLastTimeMillis = null
            externalSpeedIntegrationLastMps = null
            rebuildTelemetryFromPoints(draft.points)
            val recoveredStepCount =
                draft.stepCount
                    ?: draft.points
                        .asReversed()
                        .firstOrNull { point -> point.stepCount != null }
                        ?.stepCount
            _uiState.value =
                TraceRecordingUiState(
                    active = true,
                    paused = draft.paused,
                    autoPaused = draft.autoPaused,
                    saving = false,
                    activityProfile = draft.activityProfile.toRecordingActivityProfile(activityProfile),
                    trackSmoothingMode = draft.trackSmoothingMode.toRecordingTrackSmoothingMode(),
                    points = draft.points,
                    latestLivePoint = draft.points.lastOrNull(),
                    distanceMeters = draft.distanceMeters,
                    startedAtMillis = draft.startedAtMillis,
                    pausedAtMillis = draft.pausedAtMillis,
                    accumulatedPausedMillis = draft.accumulatedPausedMillis,
                    gpsActiveDurationMillis = draft.gpsActiveDurationMillis,
                    recordingGapCount = draft.recordingGapCount,
                    recordingMaxGapMillis = draft.recordingMaxGapMillis,
                    externalRawDistanceUnits = draft.externalRawDistanceUnits,
                    externalDistanceMeters = draft.externalDistanceMeters,
                    externalIntegratedDistanceMeters = draft.externalIntegratedDistanceMeters,
                    stepCount = recoveredStepCount,
                    message = "REC recovered",
                )
            rebaseRecordingProgressVibration(_uiState.value, System.currentTimeMillis())
            syncRecordingProgressVibrationTimer()
            DebugTelemetry.log(
                "TraceRecording",
                "event=recovered ${recordingSummaryTokens(_uiState.value, System.currentTimeMillis())} " +
                    "trackSmoothingMode=${_uiState.value.trackSmoothingMode} " +
                    "trackFilterVersion=$RECORDING_TRACK_FILTER_VERSION " +
                    "draftPath=${sanitizeTelemetryValue(draftStore.draftPath())}",
            )
        }
    }

    private fun persistDraftAsync(reason: String) {
        val state = _uiState.value
        viewModelScope.launch {
            persistDraft(state = state, reason = reason)
        }
    }

    private suspend fun persistDraft(
        state: TraceRecordingUiState,
        reason: String,
    ) {
        if (!state.active || state.saving) return
        runCatching {
            draftStore.save(
                state = state,
                lastUiAction = lastUiAction,
            )
        }.onFailure { error ->
            DebugTelemetry.log(
                "TraceRecording",
                "event=draft_failure reason=$reason error=${sanitizeTelemetryValue(error.javaClass.simpleName)}",
            )
        }
    }

    private fun clearDraftAsync(reason: String) {
        viewModelScope.launch {
            runCatching { draftStore.clear() }
                .onFailure { error ->
                    DebugTelemetry.log(
                        "TraceRecording",
                        "event=draft_clear_failure reason=$reason error=${sanitizeTelemetryValue(error.javaClass.simpleName)}",
                    )
                }
        }
    }

    private suspend fun uniqueRecordingFileName(
        startedAtMillis: Long,
        endedAtMillis: Long,
        titleOverride: String?,
    ): String {
        val base =
            titleOverride
                ?.let { buildRecordingFileNameFromTitle(it).removeSuffix(".gpx") }
                ?: buildRecordingFileName(
                    startedAtMillis = startedAtMillis,
                    endedAtMillis = endedAtMillis,
                ).removeSuffix(".gpx")
        var candidate = "$base.gpx"
        var index = 2
        while (gpxRepository.fileExists(candidate)) {
            candidate = "$base-$index.gpx"
            index += 1
        }
        return candidate
    }

    private fun resetSessionTelemetry() {
        skippedIntervalCount = 0
        skippedPausedCount = 0
        skippedUnusableLocationCount = 0
        demElevationHitCount = 0
        demElevationMissCount = 0
        gpsElevationUsedCount = 0
        lastDemTileId = null
        lastDemAxisLen = null
        lastDemResolutionLabel = null
        acceptedAccuracySumMeters = 0.0
        acceptedAccuracyCount = 0
        acceptedAccuracyMinMeters = null
        acceptedAccuracyMaxMeters = null
        lastAcceptedPointTimeMillis = null
        gpsActiveDurationMillis = 0L
        recordingGapCount = 0
        recordingMaxGapMillis = 0L
        gapRecoveryAcceptCount = 0
        maxSkippedIntervalElapsedMs = 0L
        lastSkippedIntervalElapsedMs = 0L
        lastLiveFixProvider = null
        lastLiveFixAccuracyMeters = null
        lastLiveFixTimeMillis = null
        startNewSegmentOnNextPoint = false
        suppressedJitterPointCount = 0
        suppressedJitterDistanceMeters = 0.0
        latestSensorMetrics = RecordingSensorMetrics()
        externalDistanceBaseMeters = null
        externalSessionDistanceMeters = null
        externalIntegratedDistanceMeters = null
        externalSpeedIntegrationLastTimeMillis = null
        externalSpeedIntegrationLastMps = null
        heartRateSensorEventCount = 0
        stepSensorEventCount = 0
        cadenceSensorEventCount = 0
        pressureSensorEventCount = 0
        resetAutoPauseMotionState()
        autoPauseTriggerCount = 0
        autoResumeTriggerCount = 0
        recordingFixQualityGate.reset()
        qualityHeldFixCount = 0
        qualityRejectedFixCount = 0
        qualityRelocationCount = 0
        smoothedPointCount = 0
        smoothedAdjustmentMeters = 0.0
        maxSmoothedAdjustmentMeters = 0.0
    }

    private fun maybeAutoPauseRecording(
        state: TraceRecordingUiState,
        livePoint: RecordedTracePoint,
        nowElapsedMs: Long,
    ): Boolean {
        if (!isAutoPauseEnabledForCurrentProfile()) {
            resetAutoPauseMotionState()
            return false
        }
        val startedAtMillis = state.startedAtMillis ?: return false
        if (System.currentTimeMillis() - startedAtMillis < AUTO_PAUSE_START_GRACE_MS) return false
        val previousPoint = state.points.lastOrNull() ?: return false
        if (state.points.size < AUTO_PAUSE_MIN_POINTS) return false
        if (!hasReliableAutoPauseFix(livePoint)) {
            autoPauseStationarySinceElapsedMs = null
            return false
        }
        if (!isAutoPauseStationary(livePoint = livePoint, previousPoint = previousPoint)) {
            autoPauseStationarySinceElapsedMs = null
            return false
        }
        val stationarySince =
            autoPauseStationarySinceElapsedMs ?: nowElapsedMs.also {
                autoPauseStationarySinceElapsedMs = it
            }
        val stationaryDurationMs = nowElapsedMs - stationarySince
        if (stationaryDurationMs < autoPauseStopDurationMs()) return false

        autoPauseTriggerCount += 1
        resetAutoPauseMotionState()
        val nowMillis = System.currentTimeMillis()
        lastUiAction = "auto_pause"
        _uiState.value =
            state.copy(
                paused = true,
                autoPaused = true,
                pausedAtMillis = nowMillis,
                latestLivePoint = livePoint,
                message = "REC auto-paused",
            )
        syncRecordingProgressVibrationTimer()
        DebugTelemetry.log(
            "TraceRecording",
            "event=auto_pause count=$autoPauseTriggerCount " +
                "mode=$recordingAutoPauseMode activityProfile=${recordingActivityProfile()} " +
                "stationaryDurationMs=$stationaryDurationMs " +
                autoPauseMotionTelemetry(livePoint, previousPoint),
        )
        persistDraftAsync(reason = "auto_pause")
        return true
    }

    private fun maybeAutoResumeRecording(
        livePoint: RecordedTracePoint,
        nowElapsedMs: Long,
    ): Boolean {
        val state = _uiState.value
        if (!state.active || !state.paused || !state.autoPaused || state.saving) return false
        val previousPoint = state.points.lastOrNull()
        if (!isAutoPauseEnabledForCurrentProfile()) {
            autoResumeRecording(
                state = state,
                livePoint = livePoint,
                nowElapsedMs = nowElapsedMs,
                movingDurationMs = 0L,
                reason = "setting_off",
            )
            return true
        }
        if (previousPoint == null || !hasReliableAutoPauseFix(livePoint)) {
            autoPauseMovingSinceElapsedMs = null
            return false
        }
        if (!isAutoPauseMoving(livePoint = livePoint, previousPoint = previousPoint)) {
            autoPauseMovingSinceElapsedMs = null
            return false
        }
        val movingSince =
            autoPauseMovingSinceElapsedMs ?: nowElapsedMs.also {
                autoPauseMovingSinceElapsedMs = it
            }
        val movingDurationMs = nowElapsedMs - movingSince
        if (movingDurationMs < autoPauseResumeDurationMs()) return false
        autoResumeRecording(
            state = state,
            livePoint = livePoint,
            nowElapsedMs = nowElapsedMs,
            movingDurationMs = movingDurationMs,
            reason = "movement",
        )
        return true
    }

    private fun autoResumeRecording(
        state: TraceRecordingUiState,
        livePoint: RecordedTracePoint,
        nowElapsedMs: Long,
        movingDurationMs: Long,
        reason: String,
    ) {
        val nowMillis = System.currentTimeMillis()
        val addedPausedMillis = state.pausedAtMillis?.let { nowMillis - it }?.coerceAtLeast(0L) ?: 0L
        lastAcceptedElapsedMs = Long.MIN_VALUE
        lastAcceptedPointTimeMillis = null
        startNewSegmentOnNextPoint = false
        recordingFixQualityGate.reset()
        resetAutoPauseMotionState()
        autoResumeTriggerCount += 1
        lastUiAction = "auto_resume"
        _uiState.value =
            state.copy(
                paused = false,
                autoPaused = false,
                pausedAtMillis = null,
                latestLivePoint = livePoint,
                accumulatedPausedMillis = state.accumulatedPausedMillis + addedPausedMillis,
                message = "REC on",
            )
        syncRecordingProgressVibrationTimer()
        DebugTelemetry.log(
            "TraceRecording",
            "event=auto_resume count=$autoResumeTriggerCount reason=$reason " +
                "mode=$recordingAutoPauseMode activityProfile=${recordingActivityProfile()} " +
                "movingDurationMs=$movingDurationMs pausedAddedMs=$addedPausedMillis " +
                "nowElapsedMs=$nowElapsedMs " +
                autoPauseMotionTelemetry(livePoint, state.points.lastOrNull()),
        )
        persistDraftAsync(reason = "auto_resume")
    }

    private fun resetAutoPauseMotionState() {
        autoPauseStationarySinceElapsedMs = null
        autoPauseMovingSinceElapsedMs = null
    }

    private fun isAutoPauseEnabledForCurrentProfile(): Boolean = recordingAutoPauseMode == SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS

    private fun hasReliableAutoPauseFix(livePoint: RecordedTracePoint): Boolean {
        val accuracy = livePoint.accuracyMeters ?: return false
        if (!accuracy.isFinite() || accuracy < 0f) return false
        return accuracy <= autoPauseMaxAccuracyMeters()
    }

    private fun isAutoPauseStationary(
        livePoint: RecordedTracePoint,
        previousPoint: RecordedTracePoint,
    ): Boolean {
        val speed = livePoint.speedMps?.takeIf { it.isFinite() && it >= 0f }
        val distanceMeters = haversineMeters(previousPoint.latLong, livePoint.latLong)
        val radiusMeters = autoPauseStationaryRadiusMeters(livePoint)
        return (speed == null || speed <= autoPauseStopSpeedMps()) && distanceMeters <= radiusMeters
    }

    private fun isAutoPauseMoving(
        livePoint: RecordedTracePoint,
        previousPoint: RecordedTracePoint,
    ): Boolean {
        val speed = livePoint.speedMps?.takeIf { it.isFinite() && it >= 0f }
        val distanceMeters = haversineMeters(previousPoint.latLong, livePoint.latLong)
        return speed?.let { it >= autoPauseResumeSpeedMps() } == true ||
            distanceMeters >= autoPauseResumeDistanceMeters(livePoint)
    }

    private fun autoPauseMotionTelemetry(
        livePoint: RecordedTracePoint,
        previousPoint: RecordedTracePoint?,
    ): String {
        val distanceMeters = previousPoint?.let { haversineMeters(it.latLong, livePoint.latLong) }
        return "speedMps=${livePoint.speedMps?.formatTelemetry(2) ?: "na"} " +
            "accuracyMeters=${livePoint.accuracyMeters?.formatTelemetry(1) ?: "na"} " +
            "distanceFromLastPointMeters=${distanceMeters?.formatTelemetry(1) ?: "na"}"
    }

    private fun autoPauseStopDurationMs(): Long =
        if (recordingActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            AUTO_PAUSE_BIKE_STOP_DURATION_MS
        } else {
            AUTO_PAUSE_HIKE_STOP_DURATION_MS
        }

    private fun autoPauseResumeDurationMs(): Long =
        if (recordingActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            AUTO_PAUSE_BIKE_RESUME_DURATION_MS
        } else {
            AUTO_PAUSE_HIKE_RESUME_DURATION_MS
        }

    private fun autoPauseStopSpeedMps(): Float =
        if (recordingActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            AUTO_PAUSE_BIKE_STOP_SPEED_MPS
        } else {
            AUTO_PAUSE_HIKE_STOP_SPEED_MPS
        }

    private fun autoPauseResumeSpeedMps(): Float =
        if (recordingActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            AUTO_PAUSE_BIKE_RESUME_SPEED_MPS
        } else {
            AUTO_PAUSE_HIKE_RESUME_SPEED_MPS
        }

    private fun autoPauseMaxAccuracyMeters(): Float =
        if (recordingActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            AUTO_PAUSE_BIKE_MAX_ACCURACY_M
        } else {
            AUTO_PAUSE_HIKE_MAX_ACCURACY_M
        }

    private fun autoPauseStationaryRadiusMeters(livePoint: RecordedTracePoint): Double {
        val base =
            if (recordingActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                AUTO_PAUSE_BIKE_STATIONARY_RADIUS_M
            } else {
                AUTO_PAUSE_HIKE_STATIONARY_RADIUS_M
            }
        val accuracy = livePoint.accuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: return base.toDouble()
        return maxOf(base, accuracy * AUTO_PAUSE_ACCURACY_RADIUS_FACTOR).toDouble()
    }

    private fun autoPauseResumeDistanceMeters(livePoint: RecordedTracePoint): Double {
        val base =
            if (recordingActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                AUTO_PAUSE_BIKE_RESUME_DISTANCE_M
            } else {
                AUTO_PAUSE_HIKE_RESUME_DISTANCE_M
            }
        val accuracy = livePoint.accuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: return base.toDouble()
        return maxOf(base, accuracy * AUTO_PAUSE_ACCURACY_RESUME_FACTOR).toDouble()
    }

    private fun latestFreshSensorMetrics(nowMillis: Long): RecordingSensorMetrics? {
        val heartRateBpm =
            latestSensorMetrics.heartRateBpm
                ?.takeIf { latestSensorMetrics.heartRateUpdatedAtMillis.isFreshSensorTime(nowMillis) }
        val stepCount =
            latestSensorMetrics.stepCount
                ?.takeIf { latestSensorMetrics.stepCountUpdatedAtMillis.isFreshSensorTime(nowMillis) }
        val cadenceSpm =
            latestSensorMetrics.cadenceSpm
                ?.takeIf { latestSensorMetrics.cadenceUpdatedAtMillis.isFreshSensorTime(nowMillis) }
        val powerWatts =
            latestSensorMetrics.externalPowerWatts
                ?.takeIf { latestSensorMetrics.externalPowerUpdatedAtMillis.isFreshSensorTime(nowMillis) }
        val pressureHpa =
            latestSensorMetrics.barometricPressureHpa
                ?.takeIf { latestSensorMetrics.barometricPressureUpdatedAtMillis.isFreshSensorTime(nowMillis) }
        if (heartRateBpm == null && stepCount == null && cadenceSpm == null && powerWatts == null && pressureHpa == null) {
            return null
        }
        return RecordingSensorMetrics(
            heartRateBpm = heartRateBpm,
            stepCount = stepCount,
            cadenceSpm = cadenceSpm,
            externalPowerWatts = powerWatts,
            barometricPressureHpa = pressureHpa,
        )
    }

    private fun rebuildTelemetryFromPoints(points: List<RecordedTracePoint>) {
        points.forEach { point ->
            updateAccuracyTelemetry(point.accuracyMeters)
        }
        lastAcceptedPointTimeMillis = points.lastOrNull()?.timeMillis
    }

    private fun updateGapTelemetry(
        previousPoint: RecordedTracePoint?,
        point: RecordedTracePoint,
        provider: String?,
        nextPointCount: Int,
    ) {
        val previousPointTimeMillis = lastAcceptedPointTimeMillis
        if (previousPointTimeMillis != null) {
            val gapMillis = (point.timeMillis - previousPointTimeMillis).coerceAtLeast(0L)
            val expectedActiveGapMillis = expectedActivePointGapMillis()
            gpsActiveDurationMillis += minOf(gapMillis, expectedActiveGapMillis)
            val thresholdMillis = recordingGapTelemetryThresholdMillis()
            if (gapMillis > thresholdMillis) {
                recordingGapCount += 1
                recordingMaxGapMillis = maxOf(recordingMaxGapMillis, gapMillis)
                val expectedPointCount = expectedPointCountForElapsed(elapsedSinceFirstPointMillis(point.timeMillis))
                val endpointDistanceMeters =
                    previousPoint?.let { previous ->
                        haversineMeters(previous.latLong, point.latLong)
                    }
                DebugTelemetry.log(
                    "TraceRecording",
                    "event=gap gapMs=$gapMillis thresholdMs=$thresholdMillis " +
                        "sampleIntervalSeconds=$sampleIntervalSeconds " +
                        "acceptThresholdMs=${recordingSampleAcceptThresholdMillis()} " +
                        "expectedPointCount=$expectedPointCount " +
                        "acceptedPointCount=$nextPointCount " +
                        "accuracyMeters=${point.accuracyMeters?.toInt() ?: -1} " +
                        "gapEndpointDistanceM=${endpointDistanceMeters?.formatTelemetry(1) ?: "na"} " +
                        "gapPreviousSpeedMps=${previousPoint?.speedMps?.formatTelemetry(2) ?: "na"} " +
                        "gapCurrentSpeedMps=${point.speedMps?.formatTelemetry(2) ?: "na"} " +
                        "gapPreviousAccuracyM=${previousPoint?.accuracyMeters?.formatTelemetry(1) ?: "na"} " +
                        "gapCurrentAccuracyM=${point.accuracyMeters?.formatTelemetry(1) ?: "na"} " +
                        "provider=${sanitizeTelemetryValue(provider ?: "na")} " +
                        "lastLiveFixAgeMs=${lastLiveFixAgeMillis()} " +
                        "lastLiveProvider=${sanitizeTelemetryValue(lastLiveFixProvider ?: "na")} " +
                        "lastLiveAccuracyMeters=${lastLiveFixAccuracyMeters?.toInt() ?: -1} " +
                        "skippedInterval=$skippedIntervalCount " +
                        "lastSkippedIntervalElapsedMs=$lastSkippedIntervalElapsedMs " +
                        "maxSkippedIntervalElapsedMs=$maxSkippedIntervalElapsedMs " +
                        "gapCount=$recordingGapCount maxGapMs=$recordingMaxGapMillis",
                )
            }
        }
        lastAcceptedPointTimeMillis = point.timeMillis
    }

    private fun updateSensorEventTelemetry(
        previous: RecordingSensorMetrics,
        next: RecordingSensorMetrics,
    ) {
        if (next.heartRateUpdatedAtMillis > previous.heartRateUpdatedAtMillis) {
            heartRateSensorEventCount += 1
        }
        if (next.stepCountUpdatedAtMillis > previous.stepCountUpdatedAtMillis) {
            stepSensorEventCount += 1
        }
        if (next.cadenceUpdatedAtMillis > previous.cadenceUpdatedAtMillis) {
            cadenceSensorEventCount += 1
        }
        if (next.barometricPressureSensorEventCount > previous.barometricPressureSensorEventCount) {
            val newEvents =
                next.barometricPressureSensorEventCount - previous.barometricPressureSensorEventCount
            pressureSensorEventCount =
                (pressureSensorEventCount.toLong() + newEvents)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
        }
    }

    private fun expectedActivePointGapMillis(): Long =
        (effectiveSampleIntervalSeconds() * 1_000L)
            .coerceAtLeast(RECORDING_GPS_ACTIVE_GAP_FLOOR_MS)
            .coerceAtMost(RECORDING_GPS_ACTIVE_GAP_CAP_MS)

    private fun elapsedSinceFirstPointMillis(pointTimeMillis: Long): Long {
        val firstPointTimeMillis =
            _uiState.value.points
                .firstOrNull()
                ?.timeMillis ?: return 0L
        return (pointTimeMillis - firstPointTimeMillis).coerceAtLeast(0L)
    }

    private fun expectedPointCountForElapsed(durationMillis: Long): Int {
        val intervalMillis = (effectiveSampleIntervalSeconds() * 1_000L).coerceAtLeast(1_000L)
        return (durationMillis / intervalMillis + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun recordingGapTelemetryThresholdMillis(): Long =
        maxOf(
            effectiveSampleIntervalSeconds() * 2_000L,
            RECORDING_GAP_TELEMETRY_MIN_THRESHOLD_MS,
        )

    private fun updateAccuracyTelemetry(accuracyMeters: Float?) {
        val accuracy = accuracyMeters ?: return
        if (!accuracy.isFinite() || accuracy < 0f) return
        acceptedAccuracySumMeters += accuracy.toDouble()
        acceptedAccuracyCount += 1
        acceptedAccuracyMinMeters = minOf(acceptedAccuracyMinMeters ?: accuracy, accuracy)
        acceptedAccuracyMaxMeters = maxOf(acceptedAccuracyMaxMeters ?: accuracy, accuracy)
    }

    private fun recordingSummaryTokens(
        state: TraceRecordingUiState,
        nowMillis: Long,
        extraPausedMillis: Long = 0L,
    ): String {
        val pausedMillis =
            state.accumulatedPausedMillis +
                if (extraPausedMillis > 0L) {
                    extraPausedMillis
                } else if (state.paused) {
                    state.pausedAtMillis?.let { nowMillis - it }?.coerceAtLeast(0L) ?: 0L
                } else {
                    0L
                }
        val durationMillis =
            state.startedAtMillis
                ?.let { nowMillis - it - pausedMillis }
                ?.coerceAtLeast(0L)
                ?: 0L
        val elevation = elevationGainLossMeters(state.points)
        val lastPoint = state.points.lastOrNull()
        val avgAccuracy =
            if (acceptedAccuracyCount > 0) {
                acceptedAccuracySumMeters / acceptedAccuracyCount.toDouble()
            } else {
                null
            }
        val displaySnapshot =
            buildRecordingDashboardSnapshot(
                state = state,
                nowMillis = nowMillis,
                userWeightKg = userWeightKg,
                backpackWeightKg = backpackWeightKg,
                bikeWeightKg = bikeWeightKg,
                activityProfile = state.activityProfile,
            )
        val calories = displaySnapshot.calorieEstimate
        val sensorTokens = sensorTelemetryTokens(nowMillis)
        val expectedPointCount = expectedPointCountForElapsed(durationMillis)
        val averagePointIntervalMillis =
            if (state.points.size > 1) {
                durationMillis / (state.points.size - 1)
            } else {
                -1L
            }
        val pointCaptureRatePercent =
            if (expectedPointCount > 0) {
                ((state.points.size.toDouble() / expectedPointCount.toDouble()) * 100.0).roundToInt()
            } else {
                -1
            }
        return "points=${state.points.size} distanceMeters=${displaySnapshot.distanceMeters.toInt()} " +
            "gpsDistanceMeters=${state.distanceMeters.toInt()} " +
            "displayDistanceMeters=${displaySnapshot.distanceMeters.toInt()} " +
            "podSessionDistanceMeters=${state.externalDistanceMeters?.toInt() ?: -1} " +
            "podIntegratedDistanceMeters=${state.externalIntegratedDistanceMeters?.toInt() ?: -1} " +
            "active=${state.active} paused=${state.paused} autoPaused=${state.autoPaused} " +
            "lastUiAction=${lastUiAction ?: "na"} " +
            "durationMs=$durationMillis pausedMs=$pausedMillis " +
            "cadenceSource=$recordingCadenceSource speedSource=$recordingSpeedSource " +
            "distanceSource=$recordingDistanceSource stepsSource=$recordingStepsSource " +
            "gpsActiveDurationMs=${state.gpsActiveDurationMillis} " +
            "expectedPointCount=$expectedPointCount averagePointIntervalMs=$averagePointIntervalMillis " +
            "pointCaptureRatePercent=$pointCaptureRatePercent " +
            "recordingGapCount=${state.recordingGapCount} recordingMaxGapMs=${state.recordingMaxGapMillis} " +
            "gapRecoveryAcceptCount=$gapRecoveryAcceptCount " +
            "suppressedJitterPointCount=$suppressedJitterPointCount " +
            "suppressedJitterDistanceMeters=${suppressedJitterDistanceMeters.formatTelemetry(1)} " +
            "lastSkippedIntervalElapsedMs=$lastSkippedIntervalElapsedMs " +
            "maxSkippedIntervalElapsedMs=$maxSkippedIntervalElapsedMs " +
            "lastLiveProvider=${sanitizeTelemetryValue(lastLiveFixProvider ?: "na")} " +
            "lastLiveAccuracyMeters=${lastLiveFixAccuracyMeters?.toInt() ?: -1} " +
            "lastLiveFixAgeMs=${state.latestLivePoint
                ?.timeMillis
                ?.let { nowMillis - it }
                ?.coerceAtLeast(0L) ?: -1} " +
            "lastPointAgeMs=${lastPoint?.timeMillis?.let { nowMillis - it }?.coerceAtLeast(0L) ?: -1} " +
            "elevationGainMeters=${elevation.first.toInt()} elevationLossMeters=${elevation.second.toInt()} " +
            "elevationSource=$recordingElevationSource demHits=$demElevationHitCount " +
            "demResolution=${lastDemResolutionLabel ?: "na"} " +
            "demAxisLen=${lastDemAxisLen ?: -1} demTile=${lastDemTileId ?: "na"} " +
            "$sensorTokens " +
            "averageHeartRateBpm=${displaySnapshot.averageHeartRateBpm ?: -1} " +
            "lastHeartRateBpm=${lastPoint?.heartRateBpm ?: -1} lastStepCount=${lastPoint?.stepCount ?: -1} " +
            "lastCadenceSpm=${lastPoint?.cadenceSpm ?: -1} " +
            "lastPowerWatts=${lastPoint?.powerWatts ?: -1} " +
            "lastPressureHpa=${lastPoint?.barometricPressureHpa?.toInt() ?: -1} " +
            "demMisses=$demElevationMissCount gpsElevationUsed=$gpsElevationUsedCount " +
            "activityProfile=${state.activityProfile} " +
            "trackSmoothingMode=${state.trackSmoothingMode} " +
            "trackFilterVersion=$RECORDING_TRACK_FILTER_VERSION " +
            "qualityHeldFixCount=$qualityHeldFixCount " +
            "qualityRejectedFixCount=$qualityRejectedFixCount " +
            "qualityRelocationCount=$qualityRelocationCount " +
            "smoothedPointCount=$smoothedPointCount " +
            "smoothedAdjustmentMeters=${smoothedAdjustmentMeters.formatTelemetry(1)} " +
            "maxSmoothedAdjustmentMeters=${maxSmoothedAdjustmentMeters.formatTelemetry(2)} " +
            "calorieModel=${calories.model} " +
            "caloriesGrossKcal=${calories.grossKcal.roundToInt()} " +
            "caloriesActiveKcal=${calories.activeKcal.roundToInt()} " +
            "caloriesRestingKcal=${calories.restingKcal.roundToInt()} " +
            "pandolfBaseGrossKcal=${calories.pandolfBaseGrossKcal.roundToInt()} " +
            "pandolfBaseActiveKcal=${calories.pandolfBaseActiveKcal.roundToInt()} " +
            "pandolfBaseRestingKcal=${calories.pandolfBaseRestingKcal.roundToInt()} " +
            "calorieCompareModel=lcda_2024_weighted_load_backpack_v1 " +
            "lcdaGrossKcal=${calories.lcdaGrossKcal.roundToInt()} " +
            "lcdaActiveKcal=${calories.lcdaActiveKcal.roundToInt()} " +
            "lcdaRestingKcal=${calories.lcdaRestingKcal.roundToInt()} " +
            "cyclingMechanicalKj=${calories.cyclingMechanicalKj.roundToInt()} " +
            "cyclingPowerSampleSegments=${calories.cyclingPowerSampleSegments} " +
            "cyclingPhysicsSegments=${calories.cyclingPhysicsSegments} " +
            "accuracySamples=$acceptedAccuracyCount " +
            "accuracyAvgMeters=${avgAccuracy?.toInt() ?: -1} " +
            "accuracyMinMeters=${acceptedAccuracyMinMeters?.toInt() ?: -1} " +
            "accuracyMaxMeters=${acceptedAccuracyMaxMeters?.toInt() ?: -1} " +
            "skippedInterval=$skippedIntervalCount skippedPaused=$skippedPausedCount " +
            "skippedUnusable=$skippedUnusableLocationCount"
    }

    private fun sensorTelemetryTokens(nowMillis: Long): String =
        "liveHeartRateBpm=${latestSensorMetrics.heartRateBpm ?: -1} " +
            "liveStepCount=${latestSensorMetrics.stepCount ?: -1} " +
            "liveCadenceSpm=${latestSensorMetrics.cadenceSpm ?: -1} " +
            "liveExternalSpeedMps=${latestSensorMetrics.externalSpeedMps ?: -1f} " +
            "liveExternalRawDistanceUnits=${latestSensorMetrics.externalDistanceRawUnits ?: -1} " +
            "liveExternalDistanceMeters=${latestSensorMetrics.externalDistanceMeters?.toInt() ?: -1} " +
            "liveExternalSessionDistanceMeters=${externalSessionDistanceMeters?.toInt() ?: -1} " +
            "liveExternalIntegratedDistanceMeters=${externalIntegratedDistanceMeters?.toInt() ?: -1} " +
            "liveExternalPowerWatts=${latestSensorMetrics.externalPowerWatts ?: -1} " +
            "liveExternalBatteryPercent=${latestSensorMetrics.externalBatteryLevelPercent ?: -1} " +
            "livePressureHpa=${latestSensorMetrics.barometricPressureHpa?.toInt() ?: -1} " +
            "heartRateAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.heartRateUpdatedAtMillis)} " +
            "stepCountAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.stepCountUpdatedAtMillis)} " +
            "cadenceAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.cadenceUpdatedAtMillis)} " +
            "externalSpeedAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.externalSpeedUpdatedAtMillis)} " +
            "externalDistanceAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.externalDistanceUpdatedAtMillis)} " +
            "externalPowerAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.externalPowerUpdatedAtMillis)} " +
            "externalBatteryAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.externalBatteryUpdatedAtMillis)} " +
            "pressureAgeMs=${sensorAgeMillis(nowMillis, latestSensorMetrics.barometricPressureUpdatedAtMillis)} " +
            "heartRateSensorEvents=$heartRateSensorEventCount " +
            "stepSensorEvents=$stepSensorEventCount " +
            "cadenceSensorEvents=$cadenceSensorEventCount " +
            "pressureSensorEvents=$pressureSensorEventCount"

    private fun updateExternalSpeedIntegration(metrics: RecordingSensorMetrics): Double? {
        val speedMps = metrics.externalSpeedMps?.takeIf { it.isFinite() && it >= 0f } ?: return externalIntegratedDistanceMeters
        val sampleTimeMillis = metrics.externalSpeedUpdatedAtMillis.takeIf { it > 0L } ?: return externalIntegratedDistanceMeters
        val previousTimeMillis = externalSpeedIntegrationLastTimeMillis
        val previousSpeedMps = externalSpeedIntegrationLastMps
        if (previousTimeMillis != null && sampleTimeMillis > previousTimeMillis) {
            val deltaMillis = sampleTimeMillis - previousTimeMillis
            if (deltaMillis <= EXTERNAL_SPEED_INTEGRATION_MAX_GAP_MS) {
                val averageSpeedMps = ((previousSpeedMps ?: speedMps) + speedMps) / 2.0
                externalIntegratedDistanceMeters =
                    (externalIntegratedDistanceMeters ?: 0.0) + averageSpeedMps * (deltaMillis / 1000.0)
            }
        }
        externalSpeedIntegrationLastTimeMillis = sampleTimeMillis
        externalSpeedIntegrationLastMps = speedMps
        return externalIntegratedDistanceMeters
    }

    private fun recordingSampleAcceptThresholdMillis(): Long =
        (effectiveSampleIntervalSeconds() * 1_000L - RECORDING_SAMPLE_ACCEPT_TOLERANCE_MS)
            .coerceAtLeast(RECORDING_MIN_SAMPLE_ACCEPT_THRESHOLD_MS)

    private fun lastLiveFixAgeMillis(): Long = lastLiveFixTimeMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: -1L

    private fun isGpsSamplingEnabled(): Boolean = sampleIntervalSeconds != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS

    private fun recordingActivityProfile(): String = _uiState.value.activityProfile

    private fun effectiveSampleIntervalSeconds(): Int = sampleIntervalSeconds.takeIf { it > 0 } ?: SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS
}

private fun RecordingDashboardSnapshot.toRecordedTraceSummary(
    activityProfile: String,
    trackSmoothingMode: String,
): RecordedTraceSummary =
    RecordedTraceSummary(
        activityProfile = activityProfile,
        durationSeconds = durationSeconds,
        totalDurationSeconds = totalDurationSeconds,
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        currentElevationMeters = currentElevationMeters,
        currentSpeedMps = currentSpeedMps,
        averageSpeedMps = averageSpeedMps,
        fastestSpeedMps = fastestSpeedMps,
        gpsAccuracyMeters = gpsAccuracyMeters,
        pointCount = pointCount,
        gpsActiveDurationSeconds = gpsActiveDurationSeconds,
        recordingGapCount = recordingGapCount,
        recordingMaxGapSeconds = recordingMaxGapSeconds,
        caloriesGrossKcal = calorieEstimate.grossKcal,
        caloriesActiveKcal = calorieEstimate.activeKcal,
        caloriesRestingKcal = calorieEstimate.restingKcal,
        calorieModel = calorieEstimate.model,
        cyclingMechanicalKj = calorieEstimate.cyclingMechanicalKj,
        cyclingPowerSampleSegments = calorieEstimate.cyclingPowerSampleSegments,
        cyclingPhysicsSegments = calorieEstimate.cyclingPhysicsSegments,
        heartRateBpm = averageHeartRateBpm ?: heartRateBpm,
        averageHeartRateBpm = averageHeartRateBpm,
        maxHeartRateBpm = maxHeartRateBpm,
        stepCount = stepCount,
        cadenceSpm = cadenceSpm,
        averageCadenceSpm = averageCadenceSpm,
        maxCadenceSpm = maxCadenceSpm,
        powerWatts = powerWatts,
        averagePowerWatts = averagePowerWatts,
        maxPowerWatts = maxPowerWatts,
        barometricPressureHpa = barometricPressureHpa,
        recordingTrackSmoothingMode = trackSmoothingMode,
        recordingTrackFilterVersion = RECORDING_TRACK_FILTER_VERSION,
    )

private fun sensorAgeMillis(
    nowMillis: Long,
    updatedAtMillis: Long,
): Long = updatedAtMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) } ?: -1L

private const val RECORDING_GAP_TELEMETRY_MIN_THRESHOLD_MS = 15_000L
private const val RECORDING_GPS_ACTIVE_GAP_FLOOR_MS = 1_000L
private const val RECORDING_GPS_ACTIVE_GAP_CAP_MS = 15_000L
private const val RECORDING_SAMPLE_ACCEPT_TOLERANCE_MS = 500L
private const val RECORDING_MIN_SAMPLE_ACCEPT_THRESHOLD_MS = 850L
private const val RECORDING_QUALITY_TELEMETRY_INTERVAL = 5
private const val RECORDING_SMOOTHING_TELEMETRY_INTERVAL = 25
private const val EXTERNAL_SPEED_INTEGRATION_MAX_GAP_MS = 5_000L
private const val SENSOR_SNAPSHOT_MAX_AGE_MS = 15_000L
private const val MAX_RECORDING_TITLE_LENGTH = 64
private const val AUTO_PAUSE_MIN_POINTS = 2
private const val AUTO_PAUSE_START_GRACE_MS = 60_000L
private const val AUTO_PAUSE_BIKE_STOP_DURATION_MS = 20_000L
private const val AUTO_PAUSE_HIKE_STOP_DURATION_MS = 45_000L
private const val AUTO_PAUSE_BIKE_RESUME_DURATION_MS = 5_000L
private const val AUTO_PAUSE_HIKE_RESUME_DURATION_MS = 8_000L
private const val AUTO_PAUSE_BIKE_STOP_SPEED_MPS = 0.8f
private const val AUTO_PAUSE_HIKE_STOP_SPEED_MPS = 0.3f
private const val AUTO_PAUSE_BIKE_RESUME_SPEED_MPS = 1.8f
private const val AUTO_PAUSE_HIKE_RESUME_SPEED_MPS = 0.8f
private const val AUTO_PAUSE_BIKE_MAX_ACCURACY_M = 30f
private const val AUTO_PAUSE_HIKE_MAX_ACCURACY_M = 25f
private const val AUTO_PAUSE_BIKE_STATIONARY_RADIUS_M = 15f
private const val AUTO_PAUSE_HIKE_STATIONARY_RADIUS_M = 8f
private const val AUTO_PAUSE_BIKE_RESUME_DISTANCE_M = 30f
private const val AUTO_PAUSE_HIKE_RESUME_DISTANCE_M = 15f
private const val AUTO_PAUSE_ACCURACY_RADIUS_FACTOR = 1.2f
private const val AUTO_PAUSE_ACCURACY_RESUME_FACTOR = 1.5f

private data class RecordingSaveInfo(
    val fileName: String,
    val byteSize: Int,
)

private fun isUsableLocation(location: Location): Boolean =
    location.latitude.isFinite() &&
        location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 &&
        location.longitude in -180.0..180.0

private fun livePointFromLocation(location: Location): RecordedTracePoint =
    RecordedTracePoint(
        latLong = LatLong(location.latitude, location.longitude),
        elevationMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
        timeMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
        speedMps = location.speed.takeIf { location.hasSpeed() },
        elevationSource = SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
    )

private fun Long.isFreshSensorTime(nowMillis: Long): Boolean = this > 0L && (nowMillis - this).coerceAtLeast(0L) <= SENSOR_SNAPSHOT_MAX_AGE_MS

internal fun haversineMeters(
    a: LatLong,
    b: LatLong,
): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1.0 - h))
}

internal fun recordingJitterDistanceToSuppress(
    previous: RecordedTracePoint,
    candidate: RecordedTracePoint,
): Double? {
    val elapsedMs = candidate.timeMillis - previous.timeMillis
    if (elapsedMs <= 0L || elapsedMs >= RECORDING_JITTER_KEEPALIVE_MS) return null
    val speedMps = candidate.speedMps?.takeIf { it.isFinite() && it >= 0f } ?: return null
    if (speedMps > RECORDING_JITTER_MAX_SPEED_MPS) return null
    val accuracyMeters =
        listOfNotNull(previous.accuracyMeters, candidate.accuracyMeters)
            .filter { it.isFinite() && it >= 0f }
            .maxOrNull()
            ?: return null
    val deadbandMeters =
        (accuracyMeters * RECORDING_JITTER_ACCURACY_FACTOR)
            .coerceIn(RECORDING_JITTER_MIN_DEADBAND_M, RECORDING_JITTER_MAX_DEADBAND_M)
            .toDouble()
    val distanceMeters = haversineMeters(previous.latLong, candidate.latLong)
    return distanceMeters.takeIf { it <= deadbandMeters }
}

private fun elevationGainLossMeters(points: List<RecordedTracePoint>): Pair<Double, Double> {
    var gain = 0.0
    var loss = 0.0
    var previous = points.firstOrNull()?.elevationMeters ?: return 0.0 to 0.0
    points.drop(1).forEach { point ->
        val elevation = point.elevationMeters ?: return@forEach
        if (point.startsNewSegment) {
            previous = elevation
            return@forEach
        }
        val delta = elevation - previous
        if (delta > 0.0) {
            gain += delta
        } else {
            loss += -delta
        }
        previous = elevation
    }
    return gain to loss
}

private fun sanitizeTelemetryValue(value: String): String =
    value
        .replace(Regex("\\s+"), "_")
        .take(80)

private fun recordingProfileLabel(activityProfile: String): String =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        "Bike"
    } else {
        "Hike"
    }

private fun String?.toRecordingActivityProfile(fallback: String): String =
    when (this) {
        SettingsRepository.ACTIVITY_PROFILE_BIKE -> SettingsRepository.ACTIVITY_PROFILE_BIKE
        SettingsRepository.ACTIVITY_PROFILE_HIKE,
        SettingsRepository.ACTIVITY_PROFILE_WALK_HIKE,
        -> SettingsRepository.ACTIVITY_PROFILE_HIKE
        else -> fallback
    }

private fun Double.formatTelemetry(decimalPlaces: Int): String = String.format(Locale.US, "%.${decimalPlaces}f", this)

private fun Float.formatTelemetry(decimalPlaces: Int): String = toDouble().formatTelemetry(decimalPlaces)

private const val RECORDING_TELEMETRY_POINT_INTERVAL = 10
private const val RECORDING_LIVE_TELEMETRY_SKIP_INTERVAL = 20
private const val RECORDING_START_FRESH_FIX_TIMEOUT_MS = 6_000L
private const val RECORDING_JITTER_TELEMETRY_INTERVAL = 10
private const val RECORDING_JITTER_KEEPALIVE_MS = 30_000L
private const val RECORDING_JITTER_MAX_SPEED_MPS = 0.5f
private const val RECORDING_JITTER_ACCURACY_FACTOR = 0.2f
private const val RECORDING_JITTER_MIN_DEADBAND_M = 2f
private const val RECORDING_JITTER_MAX_DEADBAND_M = 4f
