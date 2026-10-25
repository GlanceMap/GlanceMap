package com.glancemap.glancemapwearos.presentation.features.recording.health

import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.RecordingSensorMetrics
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.freshForRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingHealthPointAssociationTest {
    @Test
    fun firstMeasurementDeliveredBeforeActiveStatusIsRetained() {
        val history = RecordingHealthPointAssociation(startedAtMillis = 1_000L)
        history.accept(listOf(sample(RecordingHealthMetric.HEART_RATE, 95.0, 2_000L)), 2_001L)
        history.setOwnership(setOf(RecordingHealthMetric.HEART_RATE), 2_001L)

        assertEquals(95, history.overlay(RecordingSensorMetrics()).heartRateBpm)
        assertEquals(95, history.backfill(listOf(point(3_000L))).single().heartRateBpm)
    }

    @Test
    fun lateHeartRateFillsItsMeasurementTimeWithoutChangingGeometryOrPressure() {
        val history = RecordingHealthPointAssociation(startedAtMillis = 1_000L)
        history.setOwnership(setOf(RecordingHealthMetric.HEART_RATE), 1_000L)
        history.accept(listOf(sample(RecordingHealthMetric.HEART_RATE, 95.0, 2_000L)), 120_000L)
        val points = listOf(point(1_900L), point(3_000L), point(18_000L))

        val updated = history.backfill(points)

        assertNull(updated[0].heartRateBpm)
        assertEquals(95, updated[1].heartRateBpm)
        assertNull(updated[2].heartRateBpm)
        assertEquals(points[1].copy(heartRateBpm = 95), updated[1])
        assertNull(history.overlay(RecordingSensorMetrics()).heartRateBpm)
    }

    @Test
    fun previousPauseWindowCannotSupplyMetricsAfterResume() {
        val history = RecordingHealthPointAssociation(startedAtMillis = 1_000L)
        history.setOwnership(setOf(RecordingHealthMetric.HEART_RATE), 1_000L)
        history.setPaused(true, 3_000L)
        history.setPaused(false, 10_000L)
        history.accept(listOf(sample(RecordingHealthMetric.HEART_RATE, 95.0, 2_000L)), 12_000L)

        assertNull(history.backfill(listOf(point(11_000L))).single().heartRateBpm)
        assertNull(history.overlay(RecordingSensorMetrics()).freshForRecording(12_000L)?.heartRateBpm)
    }

    @Test
    fun healthMetricsCannotEraseBluetoothOrBarometerFields() {
        val history = RecordingHealthPointAssociation(startedAtMillis = 1_000L)
        history.setOwnership(setOf(RecordingHealthMetric.STEPS_TOTAL), 1_000L)
        history.setStepOffset(720)
        history.accept(listOf(sample(RecordingHealthMetric.STEPS_TOTAL, 5.0, 2_000L)), 2_001L)
        val raw =
            RecordingSensorMetrics(heartRateBpm = 155, heartRateFromBluetooth = true, barometricPressureHpa = 979.0)

        assertEquals(725, history.overlay(raw).stepCount)
        assertEquals(2_000L, history.overlay(raw).stepCountUpdatedAtMillis)
        assertEquals(155, history.overlay(raw).heartRateBpm)
        assertEquals(true, history.overlay(raw).heartRateFromBluetooth)
        assertEquals(979.0, history.overlay(raw).barometricPressureHpa)
    }

    @Test
    fun selectedSourceSwitchPreventsPreviousHealthDataFillingNewSourceGaps() {
        val history = RecordingHealthPointAssociation(startedAtMillis = 1_000L)
        history.setOwnership(setOf(RecordingHealthMetric.HEART_RATE), 1_000L)
        history.setOwnership(emptySet(), 3_000L)
        history.accept(listOf(sample(RecordingHealthMetric.HEART_RATE, 95.0, 2_000L)), 4_000L)

        assertNull(history.backfill(listOf(point(4_000L))).single().heartRateBpm)
    }

    private fun sample(
        kind: RecordingHealthMetric,
        value: Double,
        at: Long,
    ) = RecordingHealthSample(kind, value, measuredElapsedRealtimeMillis = at, measuredWallTimeMillis = at)

    private fun point(at: Long) =
        RecordedTracePoint(
            latLong = LatLong(45.0, 6.0),
            elevationMeters = 200.0,
            timeMillis = at,
            accuracyMeters = 10f,
            speedMps = 1.5f,
            barometricPressureHpa = 979.0,
        )
}
