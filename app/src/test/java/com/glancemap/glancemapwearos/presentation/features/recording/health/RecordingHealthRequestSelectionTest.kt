package com.glancemap.glancemapwearos.presentation.features.recording.health

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingHealthRequestSelectionTest {
    @Test
    fun summaryCollectionAllowsNormalHeartRateBatching() {
        val request =
            selectWatchHealthRequest(
                state = TraceRecordingUiState(),
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH,
                selectedMetricIds = listOf(SettingsRepository.RECORDING_METRIC_HEART_RATE),
                heartRateGranted = true,
                activityGranted = true,
            )

        assertEquals(true, request.heartRate)
        assertEquals(false, request.preferFrequentHeartRate)
    }

    @Test
    fun normalBuildRequestsWatchHeartRateWithoutTakingOverStepsOrCadence() {
        val request =
            selectWatchHealthRequest(
                state = TraceRecordingUiState(),
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH,
                selectedMetricIds = listOf(SettingsRepository.RECORDING_METRIC_HEART_RATE),
                heartRateGranted = true,
                activityGranted = true,
            ).withNativeStepMetrics(false)

        assertEquals(true, request.heartRate)
        assertEquals(false, request.steps)
        assertEquals(false, request.cadence)
        assertEquals(false, request.preferFrequentHeartRate)
    }

    @Test
    fun strapAndPodSelectionsRemainOutsideHealthServices() {
        val request =
            selectWatchHealthRequest(
                state =
                    TraceRecordingUiState(
                        activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                        cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                    ),
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
                selectedMetricIds = listOf(SettingsRepository.RECORDING_METRIC_HEART_RATE),
                heartRateGranted = true,
                activityGranted = true,
            )
        assertEquals(false, request.heartRate)
        assertEquals(false, request.cadence)
        assertEquals(true, request.steps)
    }

    @Test
    fun bikeDoesNotRequestWalkingStepsAndDeniedHeartRateIsOmitted() {
        val request =
            selectWatchHealthRequest(
                state = TraceRecordingUiState(activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE),
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH,
                selectedMetricIds = listOf(SettingsRepository.RECORDING_METRIC_HEART_RATE),
                heartRateGranted = false,
                activityGranted = true,
            )
        assertEquals(RecordingHealthActivity.BIKE, request.activity)
        assertEquals(false, request.heartRate)
        assertEquals(false, request.steps)
        assertEquals(false, request.cadence)
    }
}
