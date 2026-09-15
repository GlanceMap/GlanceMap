package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingPresentationStateTest {
    @Test
    fun sensorOnlyUpdatesDoNotChangeHighLevelPresentationState() {
        val base =
            TraceRecordingUiState(
                active = true,
                paused = true,
                points = emptyList(),
                distanceMeters = 125.0,
                startedAtMillis = 1_000L,
                message = "Recording",
            )
        val sensorUpdate =
            base.copy(
                heartRateBpm = 142,
                heartRateFromBluetooth = true,
                externalSpeedMps = 3.4f,
                externalRawDistanceUnits = 900L,
                externalDistanceMeters = 12.5,
                externalDistanceUpdatedAtMillis = 2_000L,
                externalDistanceFallbackBaseMeters = 4.0,
                externalDistanceFallbackGpsMeters = 121.0,
                externalIntegratedDistanceMeters = 8.5,
                externalPowerWatts = 240,
                externalPowerFromBluetooth = true,
                externalBatteryLevelPercent = 74,
                stepCount = 2_400,
                stepCountFromBluetooth = true,
                cadenceSpm = 166,
                cadenceFromBluetooth = true,
                barometricPressureHpa = 1_013.2,
            )

        assertEquals(
            base.toRecordingPresentationState(),
            sensorUpdate.toRecordingPresentationState(),
        )
    }

    @Test
    fun recordingChangesRemainVisibleToHighLevelPresentationState() {
        val base =
            TraceRecordingUiState(
                active = true,
                distanceMeters = 10.0,
                points =
                    listOf(
                        RecordedTracePoint(
                            latLong = LatLong(48.0, 2.0),
                            elevationMeters = 100.0,
                            timeMillis = 1_000L,
                            accuracyMeters = 4f,
                            speedMps = 2f,
                        ),
                    ),
            )
        val changed =
            base.copy(
                distanceMeters = 11.0,
                points = base.points + base.points.first().copy(timeMillis = 2_000L),
            )

        assertNotEquals(base.toRecordingPresentationState(), changed.toRecordingPresentationState())
    }
}
