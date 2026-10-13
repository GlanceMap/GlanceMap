package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.recording.RecordingExternalDistanceSessionState
import com.glancemap.trailcore.recording.RecordingPoint
import com.glancemap.trailcore.recording.effectiveRecordingAutoPauseStartMillis
import com.glancemap.trailcore.recording.recordingAutoPauseAddedMillisAtResume
import org.mapsforge.core.model.LatLong
import com.glancemap.trailcore.recording.RECORDING_ACCURACY_INTERPRETATION_RAW as RAW_ACCURACY
import com.glancemap.trailcore.recording.RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS as SUSPECT_GPS
import com.glancemap.trailcore.recording.advanceRecordingExternalDistanceSession as advanceExternalSession

typealias RecordedTracePoint = RecordingPoint

/** Mapsforge conversion stays at the watch adapter boundary. */
val RecordingPoint.latLong: LatLong get() = LatLong(location.latitude, location.longitude)

@Suppress("LongParameterList")
fun RecordedTracePoint(
    latLong: LatLong,
    elevationMeters: Double?,
    timeMillis: Long,
    accuracyMeters: Float?,
    speedMps: Float?,
    effectiveAccuracyMeters: Float? = accuracyMeters,
    accuracyInterpretation: String = RECORDING_ACCURACY_INTERPRETATION_RAW,
    elevationSource: String? = null,
    heartRateBpm: Int? = null,
    stepCount: Int? = null,
    cadenceSpm: Int? = null,
    powerWatts: Int? = null,
    barometricPressureHpa: Double? = null,
    startsNewSegment: Boolean = false,
    segmentStartReason: String? = null,
    trajectoryFinalized: Boolean = false,
): RecordedTracePoint =
    RecordingPoint(
        location = GeoPoint(latLong.latitude, latLong.longitude),
        elevationMeters = elevationMeters,
        timeMillis = timeMillis,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        effectiveAccuracyMeters = effectiveAccuracyMeters,
        accuracyInterpretation = accuracyInterpretation,
        elevationSource = elevationSource,
        heartRateBpm = heartRateBpm,
        stepCount = stepCount,
        cadenceSpm = cadenceSpm,
        powerWatts = powerWatts,
        barometricPressureHpa = barometricPressureHpa,
        startsNewSegment = startsNewSegment,
        segmentStartReason = segmentStartReason,
        trajectoryFinalized = trajectoryFinalized,
    )

internal const val RECORDING_ACCURACY_INTERPRETATION_RAW = RAW_ACCURACY
internal const val RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS =
    SUSPECT_GPS

internal object RecordingSegmentStartReason {
    const val MANUAL_PAUSE = com.glancemap.trailcore.recording.RecordingSegmentStartReason.MANUAL_PAUSE
    const val AUTO_PAUSE = com.glancemap.trailcore.recording.RecordingSegmentStartReason.AUTO_PAUSE
    const val GPS_GAP = com.glancemap.trailcore.recording.RecordingSegmentStartReason.GPS_GAP
    const val SOURCE_RELOCATION = com.glancemap.trailcore.recording.RecordingSegmentStartReason.SOURCE_RELOCATION
    const val SESSION_RECOVERY = com.glancemap.trailcore.recording.RecordingSegmentStartReason.SESSION_RECOVERY
}

data class TraceRecordingUiState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val autoPaused: Boolean = false,
    val recordingAutoPauseEnabled: Boolean =
        SettingsRepository.DEFAULT_RECORDING_AUTO_PAUSE_MODE == SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS,
    val saving: Boolean = false,
    val activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    val trackSmoothingMode: String = SettingsRepository.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
    val points: List<RecordedTracePoint> = emptyList(),
    /** Display-only invalidation; neither field is persisted or exported. */
    val pointsRevision: Long = 0L,
    val pointsChangedFromIndex: Int = 0,
    val latestLivePoint: RecordedTracePoint? = null,
    val distanceMeters: Double = 0.0,
    val startedAtMillis: Long? = null,
    val pausedAtMillis: Long? = null,
    val accumulatedPausedMillis: Long = 0L,
    val gpsActiveDurationMillis: Long = 0L,
    val recordingGapCount: Int = 0,
    val recordingMaxGapMillis: Long = 0L,
    val heartRateBpm: Int? = null,
    val heartRateFromBluetooth: Boolean = false,
    val externalSpeedMps: Float? = null,
    val externalRawDistanceUnits: Long? = null,
    val externalDistanceMeters: Double? = null,
    val externalDistanceUpdatedAtMillis: Long = 0L,
    val externalDistanceFallbackBaseMeters: Double? = null,
    val externalDistanceFallbackGpsMeters: Double? = null,
    val externalIntegratedDistanceMeters: Double? = null,
    val externalPowerWatts: Int? = null,
    val externalPowerFromBluetooth: Boolean = false,
    val externalBatteryLevelPercent: Int? = null,
    val cadenceSource: String = SettingsRepository.DEFAULT_RECORDING_CADENCE_SOURCE,
    val speedSource: String = SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE,
    val distanceSource: String = SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE,
    val stepsSource: String = SettingsRepository.DEFAULT_RECORDING_STEPS_SOURCE,
    val stepCount: Int? = null,
    val stepCountFromBluetooth: Boolean = false,
    val cadenceSpm: Int? = null,
    val cadenceFromBluetooth: Boolean = false,
    val barometricPressureHpa: Double? = null,
    val message: String? = null,
) {
    val pointCount: Int get() = points.size
}

internal fun TraceRecordingUiState.isPausedForAutoResumeMonitoring(
    autoPauseEnabled: Boolean,
): Boolean = active && paused && (autoPaused || autoPauseEnabled)

internal fun autoResumeSegmentStartReason(
    autoPaused: Boolean,
    hasRecordedPoints: Boolean,
): String? =
    when {
        !hasRecordedPoints -> null
        autoPaused -> RecordingSegmentStartReason.AUTO_PAUSE
        else -> RecordingSegmentStartReason.MANUAL_PAUSE
    }

/**
 * State needed by high-level navigation and recording controls.
 *
 * Sensor readings remain available from [TraceRecordingViewModel.uiState] to the dashboard, but
 * they do not need to invalidate the navigation composition that owns the map and runtime work.
 */
internal fun TraceRecordingUiState.toRecordingPresentationState(): TraceRecordingUiState =
    copy(
        heartRateBpm = null,
        heartRateFromBluetooth = false,
        externalSpeedMps = null,
        externalRawDistanceUnits = null,
        externalDistanceMeters = null,
        externalDistanceUpdatedAtMillis = 0L,
        externalDistanceFallbackBaseMeters = null,
        externalDistanceFallbackGpsMeters = null,
        externalIntegratedDistanceMeters = null,
        externalPowerWatts = null,
        externalPowerFromBluetooth = false,
        externalBatteryLevelPercent = null,
        stepCount = null,
        stepCountFromBluetooth = false,
        cadenceSpm = null,
        cadenceFromBluetooth = false,
        barometricPressureHpa = null,
    )

internal fun effectiveAutoPauseStartMillis(
    state: TraceRecordingUiState,
    confirmationMillis: Long,
    stationaryDurationMillis: Long,
): Long = effectiveRecordingAutoPauseStartMillis(state.startedAtMillis, confirmationMillis, stationaryDurationMillis)

internal fun autoPauseAddedMillisAtResume(
    state: TraceRecordingUiState,
    confirmationMillis: Long,
    movingDurationMillis: Long,
): Long = recordingAutoPauseAddedMillisAtResume(state.pausedAtMillis, confirmationMillis, movingDurationMillis)

internal typealias ExternalDistanceSessionState = RecordingExternalDistanceSessionState

internal fun advanceExternalDistanceSession(
    totalDistanceMeters: Double,
    baseMeters: Double?,
    previousSessionMeters: Double?,
): ExternalDistanceSessionState? =
    advanceExternalSession(
        totalDistanceMeters,
        baseMeters,
        previousSessionMeters,
    )
