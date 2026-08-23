package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSensorMetricsTest {
    @Test
    fun barometerRunsOnlyForSmartElevationOrDisplayedPressure() {
        assertEquals(
            true,
            shouldCollectRecordingBarometricPressure(
                active = true,
                elevationSource = SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO,
                selectedMetricIds = emptyList(),
            ),
        )
        assertEquals(
            false,
            shouldCollectRecordingBarometricPressure(
                active = true,
                elevationSource = SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM,
                selectedMetricIds = emptyList(),
            ),
        )
        assertEquals(
            true,
            shouldCollectRecordingBarometricPressure(
                active = true,
                elevationSource = SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
                selectedMetricIds = listOf(SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE),
            ),
        )
    }

    @Test
    fun unavailableRunPodClearsOnlyMetricsOwnedByExternalSensor() {
        val metrics =
            RecordingSensorMetrics(
                cadenceSpm = 174,
                cadenceUpdatedAtMillis = 1_000L,
                cadenceFromBluetooth = true,
                externalSpeedMps = 3.2f,
                externalSpeedUpdatedAtMillis = 1_000L,
                externalDistanceRawUnits = 100L,
                externalDistanceMeters = 10.0,
                externalDistanceUpdatedAtMillis = 1_000L,
                externalPowerWatts = 245,
                externalPowerUpdatedAtMillis = 1_000L,
                externalBatteryLevelPercent = 80,
            ).withExternalRunPodUnavailable(clearCadence = true, clearPower = true)

        assertNull(metrics.cadenceSpm)
        assertNull(metrics.externalSpeedMps)
        assertNull(metrics.externalDistanceRawUnits)
        assertNull(metrics.externalDistanceMeters)
        assertNull(metrics.externalPowerWatts)
        assertEquals(80, metrics.externalBatteryLevelPercent)
    }

    @Test
    fun unavailableRunPodPreservesWatchCadenceAndUnselectedPower() {
        val metrics =
            RecordingSensorMetrics(
                cadenceSpm = 168,
                cadenceUpdatedAtMillis = 2_000L,
                cadenceFromBluetooth = false,
                externalPowerWatts = 230,
                externalPowerUpdatedAtMillis = 2_000L,
                externalSpeedMps = 4f,
            ).withExternalRunPodUnavailable(clearCadence = false, clearPower = false)

        assertEquals(168, metrics.cadenceSpm)
        assertEquals(2_000L, metrics.cadenceUpdatedAtMillis)
        assertEquals(false, metrics.cadenceFromBluetooth)
        assertEquals(230, metrics.externalPowerWatts)
        assertEquals(2_000L, metrics.externalPowerUpdatedAtMillis)
        assertNull(metrics.externalSpeedMps)
    }

    @Test
    fun recoveredStepCountContinuesFromPersistedSessionTotal() {
        val runtime = RecordingSensorRuntimeState()

        runtime.prepareRecoveredStepCount(720)
        val first = runtime.updateStepCounter(value = 10_000f, nowMillis = 1_000L)
        val next = runtime.updateStepCounter(value = 10_005f, nowMillis = 61_000L)

        assertEquals(720, first.steps)
        assertEquals(725, next.steps)
        assertEquals(5, next.cadenceSpm)
    }

    @Test
    fun recoveredStepOffsetCannotChangeAfterSensorBaselineIsEstablished() {
        val runtime = RecordingSensorRuntimeState()

        runtime.prepareRecoveredStepCount(100)
        runtime.updateStepCounter(value = 2_000f, nowMillis = 1_000L)
        runtime.prepareRecoveredStepCount(500)
        val next = runtime.updateStepCounter(value = 2_003f, nowMillis = 61_000L)

        assertEquals(103, next.steps)
    }

    @Test
    fun watchHeartRatePublishesAtOneHertzOrPromptlyForMeaningfulChange() {
        assertEquals(true, RecordingSensorPublishPolicy.shouldPublishWatchHeartRate(1_000L, 0L, 80, null))
        assertEquals(false, RecordingSensorPublishPolicy.shouldPublishWatchHeartRate(1_200L, 1_000L, 82, 80))
        assertEquals(true, RecordingSensorPublishPolicy.shouldPublishWatchHeartRate(1_250L, 1_000L, 86, 80))
        assertEquals(true, RecordingSensorPublishPolicy.shouldPublishWatchHeartRate(2_000L, 1_000L, 82, 80))
    }
}
