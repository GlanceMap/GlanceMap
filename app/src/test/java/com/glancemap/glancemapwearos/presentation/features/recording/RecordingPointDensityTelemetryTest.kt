package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPointDensityTelemetryTest {
    @Test
    fun `credible movement gap is distinguished from stationary suppression`() {
        val telemetry = RecordingPointDensityTelemetry()

        telemetry.observeCallbackReceived(callbackElapsedMs = 1_000L, significantGapMs = 10_000L)
        telemetry.observeUsableCallback()
        telemetry.observeStoredPoint(
            acceptedPointGapMs = 0L,
            endpointDistanceMeters = null,
            significantGapMs = 10_000L,
            storedAfterMotion = false,
        )
        telemetry.observeCallbackReceived(callbackElapsedMs = 4_000L, significantGapMs = 10_000L)
        telemetry.observeUsableCallback()
        telemetry.observeSmartTrackDecision(motionResult(RecordingMotionReason.REPORTED_MOTION))

        val gap =
            telemetry.observeStoredPoint(
                acceptedPointGapMs = 15_000L,
                endpointDistanceMeters = 62.0,
                significantGapMs = 10_000L,
                storedAfterMotion = true,
            )
        val snapshot = telemetry.snapshot()

        assertEquals(RecordingPointDensityGapClass.CREDIBLE_SUSTAINED_MOVEMENT, gap?.classification)
        assertTrue(gap?.credibleMovementExisted == true)
        assertFalse(gap?.callbacksWereMissing == true)
        assertEquals(3_000L, gap?.maxLiveCallbackGapMs)
        assertEquals(2, snapshot.callbackReceivedCount)
        assertEquals(2, snapshot.usableCallbackCount)
        assertEquals(1, snapshot.smartTrackDecisionCount)
        assertEquals(2, snapshot.storedPointCount)
        assertEquals(1, snapshot.movingExpectedStoredSampleCount)
        assertEquals(1, snapshot.movingStoredSampleCount)
        assertEquals(100, snapshot.movingStoredSampleCaptureRatePercent)
        assertEquals(1, snapshot.movingGapCount)
        assertEquals(15_000L, snapshot.movingGapMaxMs)
        assertEquals(62.0, snapshot.movingGapEndpointDistanceMaxM)
    }

    @Test
    fun `stationary and missing callback gaps are kept separate`() {
        val telemetry = RecordingPointDensityTelemetry()

        telemetry.observeStoredPoint(0L, null, 10_000L, storedAfterMotion = false)
        telemetry.observeCallbackReceived(callbackElapsedMs = 1_000L, significantGapMs = 10_000L)
        telemetry.observeUsableCallback()
        telemetry.observeSmartTrackDecision(motionResult(RecordingMotionReason.STATIONARY_JITTER))
        val stationaryGap = telemetry.observeStoredPoint(12_000L, 4.0, 10_000L, storedAfterMotion = false)

        telemetry.observeCallbackReceived(callbackElapsedMs = 23_000L, significantGapMs = 10_000L)
        telemetry.observeUsableCallback()
        telemetry.observeSmartTrackDecision(motionResult(RecordingMotionReason.REPORTED_MOTION))
        val unknownGap = telemetry.observeStoredPoint(22_000L, 75.0, 10_000L, storedAfterMotion = true)
        val snapshot = telemetry.snapshot()

        assertEquals(RecordingPointDensityGapClass.STATIONARY, stationaryGap?.classification)
        assertEquals(RecordingPointDensityGapClass.UNKNOWN_CALLBACK_GAP, unknownGap?.classification)
        assertEquals(1, snapshot.stationaryGapCount)
        assertEquals(12_000L, snapshot.stationaryGapMaxMs)
        assertEquals(1, snapshot.unknownCallbackGapCount)
        assertEquals(22_000L, snapshot.unknownCallbackGapMaxMs)
    }

    private fun motionResult(reason: RecordingMotionReason): RecordingMotionResult =
        RecordingMotionResult(
            status = RecordingMotionStatus.ACCEPTED,
            reason = reason,
            displacementMeters = 12.0,
            evidence =
                RecordingMotionEvidence(
                    stepDataAvailable = false,
                    stepsAdvanced = false,
                    stepsUnchanged = false,
                    cadenceDataAvailable = false,
                    cadenceShowsMotion = false,
                    speedAboveThreshold = reason == RecordingMotionReason.REPORTED_MOTION,
                    speedAccuracyAvailable = true,
                    reportedSpeedCredible = reason == RecordingMotionReason.REPORTED_MOTION,
                ),
        )
}
