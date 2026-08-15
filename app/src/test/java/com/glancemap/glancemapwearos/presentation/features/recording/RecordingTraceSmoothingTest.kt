package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingTraceSmoothingTest {
    @Test
    fun lowSpeedMovementInsideAccuracyDeadbandIsSuppressed() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00001, elapsedMillis = 8_000L, speedMps = 0.2f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
        assertEquals(RecordingMotionReason.STATIONARY_JITTER, result.reason)
        assertEquals(4.2, result.evidence.stationaryRadiusMeters ?: 0.0, 0.01)
        assertEquals(false, result.evidence.speedAboveThreshold)
        assertEquals(false, result.evidence.reportedSpeedCredible)
        assertEquals(true, result.evidence.speedAccuracyAvailable)
    }

    @Test
    fun normalWalkingMovementIsPreserved() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 1.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00012, elapsedMillis = 8_000L, speedMps = 1.2f),
                activityProfile = HIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.REPORTED_MOTION, result.reason)
    }

    @Test
    fun normalWatchSpeedUncertaintyPreservesWalkingMovementOutsideDeadband() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 1.1f)

        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(latitude = 45.00012, elapsedMillis = 11_000L, speedMps = 1.24f)
                        .copy(speedAccuracyMps = 1.5f),
                activityProfile = HIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.REPORTED_MOTION, result.reason)
        assertEquals(true, result.evidence.reportedSpeedCredible)
    }

    @Test
    fun knownWatchGpsFloorAllowsMovingBikeFixWithVendorSpeedUncertainty() {
        val gate = RecordingMovementConfidenceGate()
        val previous =
            point(
                latitude = 45.0,
                longitude = 6.0,
                timeMillis = 1_000L,
                accuracyMeters = 125f,
                speedMps = 4f,
            )

        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(latitude = 45.00045, elapsedMillis = 11_000L, speedMps = 4f)
                        .copy(
                            accuracyMeters = RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M,
                            speedAccuracyMps = 25f,
                            trustReportedSpeedWithoutAccuracy = true,
                        ),
                activityProfile = BIKE,
                previousFilterAccuracyMeters = RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.REPORTED_MOTION, result.reason)
        assertEquals(true, result.evidence.reportedSpeedCredible)
    }

    @Test
    fun credibleSpeedCannotReleaseStationaryJitterInsideDeadband() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)

        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(latitude = 45.00001, elapsedMillis = 8_000L, speedMps = 1.2f)
                        .copy(speedAccuracyMps = 1.5f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
        assertEquals(RecordingMotionReason.STATIONARY_JITTER, result.reason)
        assertEquals(true, result.evidence.reportedSpeedCredible)
    }

    @Test
    fun unchangedStepsSuppressWeakLowSpeedGpsDrift() {
        val gate = RecordingMovementConfidenceGate()
        val previous =
            point(
                latitude = 45.0,
                longitude = 6.0,
                timeMillis = 1_000L,
                accuracyMeters = 24f,
                speedMps = 0.1f,
            ).copy(stepCount = 100)

        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(
                        latitude = 45.0002,
                        elapsedMillis = 11_000L,
                        speedMps = 1.0f,
                        stepCount = 100,
                    ).copy(accuracyMeters = 24f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
        assertEquals(RecordingMotionReason.STEP_STILLNESS, result.reason)
        assertEquals(true, result.evidence.stepsUnchanged)
    }

    @Test
    fun unchangedStepsDoNotBlockARecentBikeDeceleration() {
        val gate = RecordingMovementConfidenceGate()
        val previous =
            point(
                latitude = 45.0,
                longitude = 6.0,
                timeMillis = 1_000L,
                accuracyMeters = 24f,
                speedMps = 2.8f,
            ).copy(stepCount = 100)

        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(
                        latitude = 45.0003,
                        elapsedMillis = 11_000L,
                        speedMps = 1.6f,
                        stepCount = 100,
                    ).copy(accuracyMeters = 24f),
                activityProfile = HIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.REPORTED_MOTION, result.reason)
    }

    @Test
    fun continuityRecoveryCapsDistanceWithoutBreakingVisibleGeometry() {
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 1.2f)
        val current =
            point(latitude = 45.0018, longitude = 6.0, timeMillis = 21_000L, speedMps = 1.2f)
        val geometryMeters = haversineMeters(previous.latLong, current.latLong)

        val estimate =
            estimateRecordingDistanceDelta(
                geometricDeltaMeters = geometryMeters,
                previous = previous,
                current = current,
                elapsedSincePreviousMs = 20_000L,
                activityProfile = HIKE,
                isContinuityRecovery = true,
            )

        assertTrue(estimate.capped)
        assertTrue(estimate.distanceMeters < geometryMeters)
        assertTrue((estimate.maximumTrustedMeters ?: 0.0) >= estimate.distanceMeters)
    }

    @Test
    fun committedPointGapTriggersContinuityRecoveryWhenCallbacksAreRecent() {
        assertEquals(
            41_000L,
            resolveRecordingContinuityRecoveryGapMillis(
                deliveryGapMillis = 19_000L,
                committedPointGapMillis = 41_000L,
                thresholdMillis = 25_000L,
            ),
        )
    }

    @Test
    fun watchGpsAccuracyFloorDoesNotInflateRecoveryDistanceAllowance() {
        val previous =
            point(
                latitude = 45.0,
                longitude = 6.0,
                timeMillis = 1_000L,
                accuracyMeters = 125f,
                speedMps = 1.2f,
            )
        val current =
            point(
                latitude = 45.0018,
                longitude = 6.0,
                timeMillis = 21_000L,
                accuracyMeters = 125f,
                speedMps = 1.2f,
            )
        val geometryMeters = haversineMeters(previous.latLong, current.latLong)

        val estimate =
            estimateRecordingDistanceDelta(
                geometricDeltaMeters = geometryMeters,
                previous = previous.copy(accuracyMeters = RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M),
                current = current.copy(accuracyMeters = RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M),
                elapsedSincePreviousMs = 20_000L,
                activityProfile = HIKE,
                isContinuityRecovery = true,
            )

        assertTrue(estimate.capped)
        assertTrue(estimate.distanceMeters < geometryMeters)
    }

    @Test
    fun lowConfidenceSideArcIsPulledTowardConfirmedStraightChord() {
        val before = point(latitude = 45.0, longitude = 6.0, timeMillis = 0L, speedMps = 1.2f)
        val first = point(latitude = 45.00009, longitude = 6.00006, timeMillis = 10_000L, speedMps = 1.2f)
        val second = point(latitude = 45.00018, longitude = 6.00006, timeMillis = 20_000L, speedMps = 1.2f)
        val after = point(latitude = 45.00027, longitude = 6.0, timeMillis = 30_000L, speedMps = 1.2f)

        val result =
            smoothRecordingStraightDrift(
                before = before,
                firstInterior = first.copy(accuracyMeters = 16f),
                secondInterior = second.copy(accuracyMeters = 16f),
                after = after,
                options = RecordingPointSmoothingOptions(mode = ADAPTIVE, activityProfile = HIKE),
            )

        assertTrue(result != null)
        assertTrue(result!!.adjustmentMeters in 1.0..8.0)
        assertTrue(result.point.latLong.longitude < first.latLong.longitude)
    }

    @Test
    fun straightDriftSmoothingDoesNotFlattenCornerAcrossChordSides() {
        val before = point(latitude = 45.0, longitude = 6.0, timeMillis = 0L, speedMps = 1.2f)
        val first = point(latitude = 45.0, longitude = 6.00018, timeMillis = 10_000L, speedMps = 1.2f)
        val second = point(latitude = 45.00012, longitude = 6.00018, timeMillis = 20_000L, speedMps = 1.2f)
        val after = point(latitude = 45.00024, longitude = 6.00018, timeMillis = 30_000L, speedMps = 1.2f)

        val result =
            smoothRecordingStraightDrift(
                before = before,
                firstInterior = first.copy(accuracyMeters = 18f),
                secondInterior = second.copy(accuracyMeters = 18f),
                after = after,
                options = RecordingPointSmoothingOptions(mode = ADAPTIVE, activityProfile = HIKE),
            )

        assertEquals(null, result)
    }

    @Test
    fun unreliableReportedSpeedDoesNotReleaseStationaryJitter() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(latitude = 45.00001, elapsedMillis = 8_000L, speedMps = 1.2f)
                        .copy(speedAccuracyMps = 3f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
        assertEquals(true, result.evidence.speedAboveThreshold)
        assertEquals(false, result.evidence.reportedSpeedCredible)
    }

    @Test
    fun stationaryWanderingRemainsSuppressedWithoutKeepalive() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00001, elapsedMillis = 61_000L, speedMps = 0.1f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
    }

    @Test
    fun freshStepsPreserveVerySlowHikingMovement() {
        val gate = RecordingMovementConfidenceGate()
        val previous =
            point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
                .copy(stepCount = 100)
        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(
                        latitude = 45.00001,
                        elapsedMillis = 8_000L,
                        speedMps = 0.2f,
                        stepCount = 106,
                    ),
                activityProfile = HIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.SENSOR_MOTION, result.reason)
        assertEquals(true, result.evidence.stepDataAvailable)
        assertEquals(true, result.evidence.stepsAdvanced)
    }

    @Test
    fun consistentSlowProgressIsAcceptedWithoutMotionSensors() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)

        val first =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00009, elapsedMillis = 8_000L, speedMps = 0.2f),
                activityProfile = HIKE,
            )
        val confirmed =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00013, elapsedMillis = 15_000L, speedMps = 0.2f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, first.status)
        assertTrue(confirmed.accepted)
        assertEquals(RecordingMotionReason.CONFIRMED_SLOW_PROGRESS, confirmed.reason)
    }

    @Test
    fun smartTrackTelemetryAggregatesDecisionsWithoutPerFixHistory() {
        val gate = RecordingMovementConfidenceGate()
        val telemetry = RecordingSmartTrackTelemetry()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)

        val suppressed =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00001, elapsedMillis = 8_000L, speedMps = 0.2f),
                activityProfile = HIKE,
            )
        telemetry.observeMotion(suppressed, bypassedForSegmentStart = false)
        val snapshot = telemetry.snapshot()

        assertEquals(1, snapshot.motionEvaluatedFixCount)
        assertEquals(1, snapshot.suppressedStationaryCount)
        assertEquals(1, snapshot.noMotionSensorDataCount)
        assertEquals(1, snapshot.stationaryRadiusSampleCount)
        assertEquals(4.2, snapshot.stationaryRadiusAverageMeters ?: 0.0, 0.01)
        assertTrue((snapshot.nonAcceptedDisplacementAverageMeters ?: 0.0) > 1.0)
    }

    private fun sample(
        latitude: Double,
        elapsedMillis: Long,
        speedMps: Float,
        stepCount: Int? = null,
    ): RecordingMotionSample =
        RecordingMotionSample(
            latLong = LatLong(latitude, 6.0),
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = 12f,
            speedMps = speedMps,
            speedAccuracyMps = 0.2f,
            stepCount = stepCount,
            cadenceSpm = null,
        )

    private fun point(
        latitude: Double,
        longitude: Double,
        timeMillis: Long,
        speedMps: Float,
        accuracyMeters: Float = 12f,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = LatLong(latitude, longitude),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
        )

    private companion object {
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val ADAPTIVE = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE
    }
}
