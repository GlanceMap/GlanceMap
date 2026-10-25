package com.glancemap.glancemapwearos.presentation.features.recording.health

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState

internal fun selectWatchHealthRequest(
    state: TraceRecordingUiState,
    heartRateSource: String,
    selectedMetricIds: List<String>,
    heartRateGranted: Boolean,
    activityGranted: Boolean,
): RecordingHealthRequest {
    val bike = state.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    val steps = !bike && activityGranted && state.stepsSource == SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS
    val heartRate =
        heartRateGranted &&
            heartRateSource == SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH &&
            selectedMetricIds.any {
                it == SettingsRepository.RECORDING_METRIC_HEART_RATE ||
                    it == SettingsRepository.RECORDING_METRIC_MAX_HEART_RATE
            }
    return RecordingHealthRequest(
        activity = if (bike) RecordingHealthActivity.BIKE else RecordingHealthActivity.HIKE,
        heartRate = heartRate,
        steps = steps,
        cadence =
            !bike &&
                activityGranted &&
                state.cadenceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS &&
                (steps || selectedMetricIds.any { it in cadenceMetrics }),
        preferFrequentHeartRate = heartRate,
    )
}

private val cadenceMetrics =
    setOf(
        SettingsRepository.RECORDING_METRIC_CADENCE,
        SettingsRepository.RECORDING_METRIC_AVERAGE_CADENCE,
        SettingsRepository.RECORDING_METRIC_MAX_CADENCE,
    )
