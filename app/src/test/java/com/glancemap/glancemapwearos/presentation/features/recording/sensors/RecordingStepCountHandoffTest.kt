package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStepCountHandoffTest {
    @Test
    fun finalFlushedTotalSeedsRestartAfterFailedSave() {
        assertEquals(1_062, recordingStepCountForHandoff(1_060, 1_062, 1_062))
    }

    @Test
    fun pressurePublicationCannotRestorePreHealthStepTotal() {
        val handoff = RecordingStepCountHandoff()
        handoff.begin(1_062, 40_000L)
        val pressure =
            RecordingSensorMetrics(stepCount = 3, stepCountUpdatedAtMillis = 1_000L, barometricPressureHpa = 979.0)

        val merged = handoff.apply(pressure)

        assertEquals(1_062, merged.stepCount)
        assertEquals(0L, merged.stepCountUpdatedAtMillis)
        assertEquals(979.0, merged.barometricPressureHpa)
    }

    @Test
    fun newerRebasedRawMeasurementReleasesCarryForward() {
        val handoff = RecordingStepCountHandoff()
        handoff.begin(1_062, 40_000L)
        val reading = RecordingSensorMetrics(stepCount = 1_064, stepCountUpdatedAtMillis = 41_000L)

        assertEquals(reading, handoff.apply(reading))
        assertEquals(
            1_065,
            handoff.apply(reading.copy(stepCount = 1_065, stepCountUpdatedAtMillis = 42_000L)).stepCount,
        )
    }
}
