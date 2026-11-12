package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSensorFreshnessTest {
    @Test
    fun receiptOfOldMeasurementDoesNotRenewItsFreshness() {
        val measuredAt = recordingSensorMeasurementTimeMillis(1_000L, 119_000L, 200_000L)
        assertEquals(82_000L, measuredAt)
        val snapshot =
            RecordingSensorMetrics(stepCount = 438, stepCountUpdatedAtMillis = checkNotNull(measuredAt))
                .freshForRecording(nowMillis = 200_000L)
        assertNull(snapshot)
    }

    @Test
    fun missingAndFutureSourceTimesCannotRefreshSensors() {
        assertNull(recordingSensorMeasurementTimeMillis(0L, 10_000L, 100_000L))
        assertNull(recordingSensorMeasurementTimeMillis(10_001L, 10_000L, 100_000L))
        assertNull(
            RecordingSensorMetrics(cadenceSpm = 130, cadenceUpdatedAtMillis = 100_001L).freshForRecording(100_000L),
        )
    }

    @Test
    fun freshnessExpiresWithoutAnotherCallbackWhilePressureStaysAvailable() {
        val metrics =
            RecordingSensorMetrics(
                stepCount = 438,
                stepCountUpdatedAtMillis = 1_000L,
                cadenceSpm = 130,
                cadenceUpdatedAtMillis = 1_000L,
                barometricPressureHpa = 979.0,
                barometricPressureUpdatedAtMillis = 16_000L,
            )
        assertEquals(438, metrics.freshForRecording(16_000L)?.stepCount)
        assertNull(metrics.freshForRecording(16_001L)?.stepCount)
        assertNull(metrics.freshForRecording(16_001L)?.cadenceSpm)
        assertEquals(979.0, metrics.freshForRecording(16_001L)?.barometricPressureHpa)
    }
}
