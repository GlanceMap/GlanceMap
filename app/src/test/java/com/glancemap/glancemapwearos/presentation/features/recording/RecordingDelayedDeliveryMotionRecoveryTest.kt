package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingDelayedDeliveryMotionRecoveryTest {
    @Test
    fun delayedLowSpeedBikeEndpointIsHeldWithoutRecoveryEvidence() {
        val gate = RecordingMovementConfidenceGate()

        val result =
            gate.evaluate(
                previous =
                    recordedPoint(
                        xMeters = 0.0,
                        timeMillis = 0L,
                        accuracyMeters = 7f,
                        speedMps = 1.46f,
                    ),
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                    ),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, result.status)
        assertEquals(RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS, result.reason)
    }

    @Test
    fun delayedLowSpeedBikeEndpointWithCredibleMovementEntersNormalPipeline() {
        val gate = RecordingMovementConfidenceGate()
        val result =
            gate.evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery = delayedDeliveryEvidence(),
                    ),
                activityProfile = BIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.DELAYED_DELIVERY_RECOVERY, result.reason)
        val qualityGate = RecordingFixQualityGate()
        qualityGate.evaluate(fixSample(xMeters = 0.0, elapsedMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f), BIKE)
        val qualityResult =
            qualityGate.evaluate(
                fixSample(xMeters = 38.0, elapsedMillis = 20_000L, accuracyMeters = 9.6f, speedMps = 0.47f),
                BIKE,
            )
        assertTrue(qualityResult.accepted)
        val telemetry = RecordingSmartTrackTelemetry()
        telemetry.observeMotion(result, bypassedForSegmentStart = false)
        assertEquals(1, telemetry.snapshot().acceptedDelayedDeliveryRecoveryCount)
    }

    @Test
    fun wakeCallbackUsesTheIntervalThatGovernedThePreviousScreenOffCallback() {
        val tracker = RecordingCallbackGapTracker(initialEffectiveIntervalMillis = 5_000L)
        tracker.observeCallback(callbackElapsedMillis = 0L)
        tracker.updateEffectiveInterval(intervalMillis = 1_000L)
        val timing = tracker.observeCallback(callbackElapsedMillis = 12_000L)
        val result =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery =
                            delayedDeliveryEvidence(
                                callbackGapMillis = timing.callbackGapMillis,
                                expectedIntervalMillis = timing.expectedIntervalMillis,
                            ),
                    ),
                activityProfile = BIKE,
            )

        assertEquals(12_000L, timing.callbackGapMillis)
        assertEquals(5_000L, timing.expectedIntervalMillis)
        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.DELAYED_DELIVERY_RECOVERY, result.reason)
    }

    @Test
    fun flappingIntervalsUseTheMaximumObservedCadenceForTheWholeCallbackGap() {
        val tracker = RecordingCallbackGapTracker(initialEffectiveIntervalMillis = 1_000L)
        tracker.observeCallback(callbackElapsedMillis = 0L)
        tracker.updateEffectiveInterval(intervalMillis = 5_000L)
        tracker.updateEffectiveInterval(intervalMillis = 1_000L)

        val timing = tracker.observeCallback(callbackElapsedMillis = 4_001L)
        val result =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 8_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery =
                            delayedDeliveryEvidence(
                                acceptedPointGapMillis = 8_000L,
                                callbackGapMillis = timing.callbackGapMillis,
                                expectedIntervalMillis = timing.expectedIntervalMillis,
                            ),
                    ),
                activityProfile = BIKE,
            )

        assertEquals(5_000L, timing.expectedIntervalMillis)
        assertEquals(RecordingMotionStatus.HELD, result.status)
        assertEquals(RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS, result.reason)
    }

    @Test
    fun stalePreviousBikeMotionCannotRecoverAStoppedTail() {
        val result =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 100.0,
                        elapsedMillis = 60_001L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery =
                            delayedDeliveryEvidence(
                                acceptedPointGapMillis = 60_001L,
                                callbackGapMillis = 60_001L,
                            ),
                    ),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, result.status)
        assertEquals(RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS, result.reason)
    }

    @Test
    fun normalCadenceAndExactDeliveryBoundaryDoNotRecoverSlowBikeEndpoints() {
        listOf(5_000L, 10_000L, 60_000L, 120_000L).forEach { expectedIntervalMillis ->
            val result =
                RecordingMovementConfidenceGate().evaluate(
                    previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                    candidate =
                        motionSample(
                            xMeters = 38.0,
                            elapsedMillis = 20_000L,
                            accuracyMeters = 9.6f,
                            speedMps = 0.47f,
                            delayedDelivery =
                                delayedDeliveryEvidence(
                                    callbackGapMillis = expectedIntervalMillis,
                                    expectedIntervalMillis = expectedIntervalMillis,
                                ),
                        ),
                    activityProfile = BIKE,
                )

            assertEquals(RecordingMotionStatus.HELD, result.status)
            assertEquals(RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS, result.reason)
        }
        val atBoundary =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery = delayedDeliveryEvidence(callbackGapMillis = 10_000L),
                    ),
                activityProfile = BIKE,
            )
        val justBeyondBoundary =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery = delayedDeliveryEvidence(callbackGapMillis = 10_001L),
                    ),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, atBoundary.status)
        assertEquals(RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS, atBoundary.reason)
        assertTrue(justBeyondBoundary.accepted)
        assertEquals(RecordingMotionReason.DELAYED_DELIVERY_RECOVERY, justBeyondBoundary.reason)
    }

    @Test
    fun delayedLowSpeedBikeEndpointInsideStationaryRadiusRemainsSuppressed() {
        val result =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 3.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery = delayedDeliveryEvidence(),
                    ),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
        assertEquals(RecordingMotionReason.STATIONARY_JITTER, result.reason)
    }

    @Test
    fun saveGeometryFlushDoesNotAppendHeldEndpoint() {
        val previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f)
        val held =
            RecordingMovementConfidenceGate().evaluate(
                previous = previous,
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                    ),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, held.status)
        val flushed =
            flushCanonicalRecordingTail(
                existingPoints = listOf(previous),
                options =
                    RecordingPointSmoothingOptions(
                        mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                        activityProfile = BIKE,
                    ),
            )
        assertEquals(1, flushed.points.size)
        assertEquals(previous.latLong, flushed.points.single().latLong)
    }

    @Test
    fun implausibleDelayedDeliveryRelocationStillReachesExistingQualityProtection() {
        val previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f)
        val candidate =
            motionSample(
                xMeters = 1_000.0,
                elapsedMillis = 20_000L,
                accuracyMeters = 9.6f,
                speedMps = 0.47f,
                delayedDelivery = delayedDeliveryEvidence(),
            )
        val motionResult =
            RecordingMovementConfidenceGate().evaluate(
                previous = previous,
                candidate = candidate,
                activityProfile = BIKE,
            )
        val qualityGate = RecordingFixQualityGate()
        qualityGate.evaluate(fixSample(xMeters = 0.0, elapsedMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f), BIKE)

        assertTrue(motionResult.accepted)
        val qualityResult =
            qualityGate.evaluate(
                fixSample(xMeters = 1_000.0, elapsedMillis = 20_000L, accuracyMeters = 9.6f, speedMps = 0.47f),
                BIKE,
            )
        assertEquals(RecordingFixQualityStatus.HELD, qualityResult.status)
        assertEquals(RecordingFixQualityReason.IMPLAUSIBLE_JUMP, qualityResult.reason)
    }

    @Test
    fun continuouslyDeliveredBikeMovementUsesExistingReportedSpeedAcceptance() {
        val result =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 3f),
                candidate = motionSample(xMeters = 8.0, elapsedMillis = 1_000L, accuracyMeters = 7f, speedMps = 3f),
                activityProfile = BIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.REPORTED_MOTION, result.reason)
    }

    @Test
    fun stationaryFixAfterRecoveredEndpointDoesNotAccumulateDrift() {
        val result =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 38.0, timeMillis = 20_000L, accuracyMeters = 9.6f, speedMps = 0.47f),
                candidate =
                    motionSample(
                        xMeters = 40.0,
                        elapsedMillis = 21_000L,
                        accuracyMeters = 9.1f,
                        speedMps = 0f,
                        delayedDelivery = delayedDeliveryEvidence(),
                    ),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
        assertEquals(RecordingMotionReason.STATIONARY_JITTER, result.reason)
    }

    @Test
    fun delayedDeliveryRecoveryDoesNotChangeHikeBehavior() {
        val result =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, timeMillis = 0L, accuracyMeters = 7f, speedMps = 1.46f),
                candidate =
                    motionSample(
                        xMeters = 38.0,
                        elapsedMillis = 20_000L,
                        accuracyMeters = 9.6f,
                        speedMps = 0.47f,
                        delayedDelivery = delayedDeliveryEvidence(),
                    ),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, result.status)
        assertEquals(RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS, result.reason)
    }

    private fun recordedPoint(
        xMeters: Double,
        timeMillis: Long,
        accuracyMeters: Float,
        speedMps: Float,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = latLongFromMeters(xMeters),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
        )

    private fun motionSample(
        xMeters: Double,
        elapsedMillis: Long,
        accuracyMeters: Float,
        speedMps: Float,
        delayedDelivery: RecordingDelayedDeliveryEvidence? = null,
    ): RecordingMotionSample =
        RecordingMotionSample(
            latLong = latLongFromMeters(xMeters),
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            speedAccuracyMps = 1.01f,
            stepCount = null,
            cadenceSpm = null,
            delayedDelivery = delayedDelivery,
        )

    private fun fixSample(
        xMeters: Double,
        elapsedMillis: Long,
        accuracyMeters: Float,
        speedMps: Float,
    ): RecordingFixSample =
        RecordingFixSample(
            latLong = latLongFromMeters(xMeters),
            timeMillis = elapsedMillis,
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            speedAccuracyMps = 1.01f,
        )

    private fun delayedDeliveryEvidence(
        acceptedPointGapMillis: Long = 20_000L,
        callbackGapMillis: Long = 12_000L,
        expectedIntervalMillis: Long = 5_000L,
    ): RecordingDelayedDeliveryEvidence =
        RecordingDelayedDeliveryEvidence(
            acceptedPointGapMillis = acceptedPointGapMillis,
            callbackGapMillis = callbackGapMillis,
            expectedIntervalMillis = expectedIntervalMillis,
        )

    private fun latLongFromMeters(xMeters: Double): LatLong =
        LatLong(
            45.0,
            6.0 + Math.toDegrees(xMeters / (EARTH_RADIUS_METERS * COSINE_LATITUDE)),
        )

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val COSINE_LATITUDE = 0.7071067811865476
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
    }
}
