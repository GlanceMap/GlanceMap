package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingAutoPauseStopDecisionTest {
    @Suppress("LongParameterList")
    private fun decision(
        speed: Float?,
        uncertainty: Float?,
        steps: RecordingPauseStepEvidence = RecordingPauseStepEvidence.UNKNOWN,
        bike: Boolean = false,
        distance: Double = 2.0,
        reliable: Boolean = true,
    ): RecordingStopDecision =
        recordingAutoPauseStopDecision(
            bike,
            reliable,
            distance,
            12.0,
            speed,
            uncertainty,
            if (bike) 0.8f else 0.3f,
            if (bike) 1.8f else 0.8f,
            steps,
        )

    @Test
    fun observedStopQualifiesEvenWhenLowSpeedIsUncertain() {
        val window = RecordingGpsConfirmationWindow()
        val samples =
            listOf(
                0L to 0.16f,
                8_000L to 0.22f,
                20_000L to 0.17f,
                30_000L to 0.18f,
                41_000L to 0.14f,
                54_000L to 0.37f,
            )
        val durations =
            samples.map { (time, speed) ->
                val outcome = decision(speed, if (speed == 0.37f) 0.41f else 0.17f)
                window.observe(100_000L + time, outcome.stationary, 10_000L)
            }
        assertEquals(54_000L, durations.last())
        assertEquals(RecordingStopReason.UNCERTAIN_SPEED, decision(0.37f, 0.41f).reason)
    }

    @Test
    fun reliableMotionFreshStepsAndLargeDisplacementStopConfirmation() {
        assertFalse(decision(0.6f, 0.1f).stationary)
        assertFalse(decision(0.8f, 1f).stationary)
        assertFalse(decision(0.1f, 0.1f, steps = RecordingPauseStepEvidence.ADVANCED).stationary)
        assertFalse(decision(0.1f, 0.1f, distance = 13.0).stationary)
        assertFalse(decision(0.1f, 0.1f, reliable = false).stationary)
    }

    @Test
    fun unavailableAndInvalidSpeedAccuracyDoNotLoosenExistingRules() {
        assertFalse(decision(0.37f, null).stationary)
        assertFalse(decision(0.37f, Float.NaN).stationary)
        assertFalse(decision(0.37f, -1f).stationary)
        assertTrue(decision(0.2f, null).stationary)
        assertTrue(decision(null, null).stationary)
        assertFalse(decision(0.9f, 1f, bike = true).stationary)
        assertTrue(decision(0.7f, null, bike = true).stationary)
    }

    @Test
    fun slowMovementCannotMoveTheStationaryAreaWithEachAcceptedFix() {
        val anchor = RecordingPausePositionAnchor()

        fun point(meters: Double) = LatLong(49.0 + meters / 111_320.0, 9.0)
        anchor.update(point(0.0), true, 0L, false)
        assertTrue(anchor.distanceMeters(point(8.0), point(12.0), false) >= 11.9)
        assertTrue(anchor.distanceMeters(point(16.0), point(20.0), false) > 19.9)
        assertTrue(anchor.distanceMeters(point(16.0), point(20.0), true) < 4.1)
        anchor.update(point(20.0), false, null, true)
        assertTrue(anchor.distanceMeters(point(16.0), point(20.0), false) > 19.9)
        anchor.update(point(20.0), false, null, false)
        assertTrue(anchor.distanceMeters(point(16.0), point(20.0), false) < 4.1)
    }

    @Test
    fun firstFreshStepAfterLongQuietPeriodVetoesPendingStop() {
        val evidence = RecordingPauseStepTracker()
        assertEquals(RecordingPauseStepEvidence.BASELINE, evidence.observe(100, 10_000, 10_000, "watch"))
        val steps = evidence.observe(101, 54_000, 54_000, "watch")
        assertEquals(RecordingPauseStepEvidence.ADVANCED, steps)
        assertFalse(decision(0.37f, 0.41f, steps = steps).stationary)
    }

    @Test
    fun stepEvidenceRequiresFreshOrderedConsecutiveReadingsFromSameSource() {
        val evidence = RecordingPauseStepTracker()
        assertEquals(RecordingPauseStepEvidence.BASELINE, evidence.observe(100, 10_000, 10_000, "watch"))
        assertEquals(RecordingPauseStepEvidence.ADVANCED, evidence.observe(102, 13_000, 14_000, "watch"))
        assertEquals(RecordingPauseStepEvidence.UNCHANGED, evidence.observe(102, 13_000, 15_000, "watch"))
        assertEquals(RecordingPauseStepEvidence.UNKNOWN, evidence.observe(130, 12_000, 15_000, "watch"))
        assertEquals(RecordingPauseStepEvidence.UNKNOWN, evidence.observe(140, 20_000, 19_000, "watch"))
        assertEquals(RecordingPauseStepEvidence.UNKNOWN, evidence.observe(150, 21_000, 40_000, "watch"))
        assertEquals(RecordingPauseStepEvidence.ADVANCED, evidence.observe(200, 50_000, 50_000, "watch"))
        assertEquals(RecordingPauseStepEvidence.BASELINE, evidence.observe(210, 51_000, 51_000, "other"))
    }
}

class RecordingAutoPauseTelemetryTest {
    @Test
    fun countersAreBoundedAndConfirmationIsReportedImmediately() {
        val telemetry = RecordingAutoPauseStopTelemetry()
        assertTrue(telemetry.observe(1_000, RecordingStopReason.STATIONARY, false, false, false))
        assertFalse(telemetry.observe(2_000, RecordingStopReason.UNCERTAIN_SPEED, false, false, false))
        assertFalse(telemetry.observe(3_000, RecordingStopReason.FRESH_STEPS, true, false, false))
        assertTrue(telemetry.observe(12_000, RecordingStopReason.MOVING_SPEED, true, false, false))
        val summary = telemetry.takeSummary()
        assertEquals(1, summary.reasons[RecordingStopReason.UNCERTAIN_SPEED])
        assertEquals(1, summary.reasons[RecordingStopReason.FRESH_STEPS])
        assertEquals(2, summary.resets)
        assertTrue(telemetry.observe(13_000, RecordingStopReason.STATIONARY, false, false, true))
    }
}
