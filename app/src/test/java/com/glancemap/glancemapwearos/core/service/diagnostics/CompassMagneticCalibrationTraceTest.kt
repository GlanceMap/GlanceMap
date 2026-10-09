package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassMagneticCalibrationTraceTest {
    @Test
    fun exportsTheFieldBiasAccuracyAndMeasurementTimesWithoutConflatingTheSensors() {
        val trace = CompassMagneticCalibrationTrace()
        trace.record(CompassDeepTraceRawSensor.MAGNETOMETER, floatArrayOf(2_030f, 0f, 0f), 0, 1_000L, 1_001L)
        trace.record(
            CompassDeepTraceRawSensor.UNCALIBRATED_MAGNETOMETER,
            floatArrayOf(40f, 0f, 0f, -1_990f, 0f, 0f),
            3,
            1_002L,
            1_003L,
        )
        val line =
            requireNotNull(
                trace.record(CompassDeepTraceRawSensor.MAGNETOMETER, floatArrayOf(2_030f, 0f, 0f), 0, 2_000L, 2_001L),
            )
        assertTrue(line.contains("calibratedMagnitudeUt=2030.0"))
        assertTrue(line.contains("uncalibratedMagnitudeUt=40.0"))
        assertTrue(line.contains("biasVectorUt=-1990.0,0.0,0.0"))
        assertTrue(line.contains("compensatedMagnitudeUt=2030.0"))
        assertTrue(line.contains("calibratedAccuracy=0"))
        assertTrue(line.contains("uncalibratedAccuracy=3"))
        assertTrue(line.contains("uncalibratedSourceAtMs=1002"))
        assertTrue(line.contains("uncalibratedArrivalAtMs=1003"))
        assertTrue(line.contains("uncalibratedAgeMs=999"))
        assertTrue(line.contains("pairSkewMs=998"))
    }

    @Test
    fun highRateCallbacksProduceAtMostOneSnapshotPerSecondAndCopyTheSensorArray() {
        val trace = CompassMagneticCalibrationTrace()
        val values = floatArrayOf(30f, 40f, 0f)
        assertTrue(
            requireNotNull(trace.record(CompassDeepTraceRawSensor.MAGNETOMETER, values, 3, 1_000L, 1_001L))
                .contains("calibratedMagnitudeUt=50.0"),
        )
        values[0] = 2_000f
        var emitted = 0
        repeat(100) { index ->
            val line =
                trace.record(
                    CompassDeepTraceRawSensor.UNCALIBRATED_MAGNETOMETER,
                    floatArrayOf(30f, 40f, 0f, 0f, 0f, 0f),
                    3,
                    1_010L + index * 10L,
                    1_011L + index * 10L,
                )
            if (line != null) {
                emitted += 1
                assertTrue(line.contains("calibratedMagnitudeUt=50.0"))
                assertTrue(line.contains("calibratedAgeMs=1001"))
            }
        }
        assertEquals(1, emitted)
    }

    @Test
    fun rejectsMissingBiasNonFiniteAndOutOfOrderSamplesAndClearRemovesOldEvidence() {
        val trace = CompassMagneticCalibrationTrace()
        assertNull(
            trace.record(CompassDeepTraceRawSensor.UNCALIBRATED_MAGNETOMETER, floatArrayOf(1f, 2f, 3f), 3, 1L, 2L),
        )
        assertNull(trace.record(CompassDeepTraceRawSensor.MAGNETOMETER, floatArrayOf(Float.NaN, 0f, 0f), 3, 1L, 2L))
        trace.record(CompassDeepTraceRawSensor.MAGNETOMETER, floatArrayOf(2_030f, 0f, 0f), 0, 1_000L, 1_001L)
        assertNull(trace.record(CompassDeepTraceRawSensor.MAGNETOMETER, floatArrayOf(40f, 0f, 0f), 3, 999L, 2_001L))
        assertNull(trace.record(CompassDeepTraceRawSensor.MAGNETOMETER, floatArrayOf(40f, 0f, 0f), 3, 1_000L, 2_001L))
        trace.clear()
        val line =
            requireNotNull(
                trace.record(
                    CompassDeepTraceRawSensor.UNCALIBRATED_MAGNETOMETER,
                    floatArrayOf(40f, 0f, 0f, 0f, 0f, 0f),
                    3,
                    2_000L,
                    2_001L,
                ),
            )
        assertTrue(line.contains("calibratedVectorUt=na"))
        assertFalse(line.contains("2030"))
    }

    @Test
    fun uncalibratedMeasurementsCannotInflateCalibratedFieldStatistics() {
        val window = CompassDeepTraceWindowAccumulator(1_000L)
        window.recordRawSensor(CompassDeepTraceRawSensor.MAGNETOMETER, 30f, 40f, 0f)
        window.recordRawSensor(CompassDeepTraceRawSensor.UNCALIBRATED_MAGNETOMETER, 2_030f, 0f, 0f)
        val line = window.toTelemetryLine(1, 2_000L)
        assertTrue(line.contains("magSamples=1"))
        assertTrue(line.contains("magMagnitudeMaxUt=50.0"))
    }
}
