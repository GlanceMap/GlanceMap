package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import kotlin.math.cos

class RecordingTrackFilterTest {
    @Test
    fun qualityGateRejectsNonMonotonicFixes() {
        val gate = RecordingFixQualityGate()
        assertTrue(gate.evaluate(sample(x = 0.0, elapsedMillis = 1_000L), HIKE).accepted)

        val result = gate.evaluate(sample(x = 1.0, elapsedMillis = 1_000L), HIKE)

        assertEquals(RecordingFixQualityStatus.REJECTED, result.status)
        assertEquals(RecordingFixQualityReason.NON_MONOTONIC, result.reason)
    }

    @Test
    fun qualityGateRejectsExtremelyPoorAccuracy() {
        val gate = RecordingFixQualityGate()

        val result =
            gate.evaluate(
                sample(x = 0.0, elapsedMillis = 1_000L, accuracyMeters = 120f),
                HIKE,
            )

        assertEquals(RecordingFixQualityStatus.REJECTED, result.status)
        assertEquals(RecordingFixQualityReason.POOR_ACCURACY, result.reason)
    }

    @Test
    fun qualityGateRejectsPoorUrbanAccuracyBeforeItBecomesTheFirstPoint() {
        val hikeGate = RecordingFixQualityGate()
        val bikeGate = RecordingFixQualityGate()

        val hikeResult =
            hikeGate.evaluate(
                sample(x = 0.0, elapsedMillis = 1_000L, accuracyMeters = 36f),
                HIKE,
            )
        val bikeResult =
            bikeGate.evaluate(
                sample(x = 0.0, elapsedMillis = 1_000L, accuracyMeters = 51f),
                BIKE,
            )

        assertEquals(RecordingFixQualityReason.POOR_ACCURACY, hikeResult.reason)
        assertEquals(RecordingFixQualityReason.POOR_ACCURACY, bikeResult.reason)
    }

    @Test
    fun qualityGateLearnsConsistentlyConservativeWatchAccuracy() {
        val gate = RecordingFixQualityGate()

        repeat(4) { index ->
            val result =
                gate.evaluate(
                    sample(
                        x = index.toDouble(),
                        elapsedMillis = (index + 1) * 3_000L,
                        accuracyMeters = 45f,
                    ),
                    HIKE,
                )
            assertEquals(RecordingFixQualityReason.POOR_ACCURACY, result.reason)
        }

        val learned =
            gate.evaluate(
                sample(x = 4.0, elapsedMillis = 15_000L, accuracyMeters = 45f),
                HIKE,
            )

        assertTrue(learned.accepted)
        assertEquals(RecordingFixQualityReason.FIRST_FIX, learned.reason)
        val policy = gate.latestAccuracyPolicySnapshot
        assertNotNull(policy)
        assertEquals(5, policy!!.sampleCount)
        assertEquals(45f, policy.baselineMedianMeters ?: 0f, 0.01f)
        assertEquals(35f, policy.profileLimitMeters, 0.01f)
        assertEquals(81.75f, policy.resolvedLimitMeters, 0.01f)
        assertTrue(policy.adaptiveLimitActive)
    }

    @Test
    fun knownWatchGpsAccuracyFloorUsesFilterAccuracyWithoutChangingRawAccuracy() {
        assertEquals(
            125f,
            resolveRecordingFilterAccuracyMeters(
                rawAccuracyMeters = 125f,
                knownWatchGpsAccuracyFloorActive = false,
            ) ?: 0f,
            0f,
        )
        assertEquals(
            RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M,
            resolveRecordingFilterAccuracyMeters(
                rawAccuracyMeters = 125f,
                knownWatchGpsAccuracyFloorActive = true,
            ) ?: 0f,
            0f,
        )
        assertTrue(isKnownWatchGpsAccuracyFloor(125f))
    }

    @Test
    fun isolatedJumpIsHeldAndFollowingGoodFixIsAccepted() {
        val gate = RecordingFixQualityGate()
        assertTrue(gate.evaluate(sample(x = 0.0, elapsedMillis = 1_000L), HIKE).accepted)

        val jump = gate.evaluate(sample(x = 500.0, elapsedMillis = 4_000L), HIKE)
        val recovered = gate.evaluate(sample(x = 6.0, elapsedMillis = 7_000L), HIKE)

        assertEquals(RecordingFixQualityStatus.HELD, jump.status)
        assertTrue(recovered.accepted)
    }

    @Test
    fun reportedWalkingSpeedHelpsRejectShortGpsSpike() {
        val gate = RecordingFixQualityGate()
        assertTrue(gate.evaluate(sample(x = 0.0, elapsedMillis = 1_000L), HIKE).accepted)

        val result = gate.evaluate(sample(x = 35.0, elapsedMillis = 4_000L), HIKE)

        assertEquals(RecordingFixQualityStatus.HELD, result.status)
        assertEquals(RecordingFixQualityReason.IMPLAUSIBLE_JUMP, result.reason)
    }

    @Test
    fun slowHighUncertaintyJumpIsHeldInsteadOfAddingDistance() {
        val gate = RecordingFixQualityGate()
        assertTrue(
            gate
                .evaluate(
                    sample(
                        x = 0.0,
                        elapsedMillis = 1_000L,
                        accuracyMeters = 24f,
                        speedMps = 1.44f,
                        speedAccuracyMps = 0.29f,
                    ),
                    HIKE,
                ).accepted,
        )

        val result =
            gate.evaluate(
                sample(
                    x = 26.0,
                    elapsedMillis = 5_030L,
                    accuracyMeters = 28f,
                    speedMps = 1.44f,
                    speedAccuracyMps = 0.29f,
                ),
                HIKE,
            )

        assertEquals(RecordingFixQualityStatus.HELD, result.status)
        assertEquals(RecordingFixQualityReason.IMPLAUSIBLE_JUMP, result.reason)
    }

    @Test
    fun sparseHikingFixRemainsAcceptedWithinMotionFloor() {
        val gate = RecordingFixQualityGate()
        assertTrue(
            gate
                .evaluate(
                    sample(
                        x = 0.0,
                        elapsedMillis = 1_000L,
                        accuracyMeters = 19f,
                        speedMps = 1.4f,
                        speedAccuracyMps = 0.3f,
                    ),
                    HIKE,
                ).accepted,
        )

        val result =
            gate.evaluate(
                sample(
                    x = 34.0,
                    elapsedMillis = 8_800L,
                    accuracyMeters = 31f,
                    speedMps = 1.5f,
                    speedAccuracyMps = 0.3f,
                ),
                HIKE,
            )

        assertTrue(result.accepted)
    }

    @Test
    fun twoConsistentFixesAfterJumpConfirmRelocation() {
        val gate = RecordingFixQualityGate()
        assertTrue(gate.evaluate(sample(x = 0.0, elapsedMillis = 1_000L), HIKE).accepted)
        assertEquals(
            RecordingFixQualityStatus.HELD,
            gate.evaluate(sample(x = 500.0, elapsedMillis = 4_000L), HIKE).status,
        )

        val confirmed = gate.evaluate(sample(x = 504.0, elapsedMillis = 7_000L), HIKE)

        assertTrue(confirmed.accepted)
        assertEquals(RecordingFixQualityReason.CONFIRMED_RELOCATION, confirmed.reason)
    }

    @Test
    fun sustainedAccurateMovementAboveProfileSpeedRemainsContinuous() {
        val gate = RecordingFixQualityGate()
        assertTrue(
            gate
                .evaluate(
                    sample(x = 0.0, elapsedMillis = 1_000L, speedMps = 15f),
                    HIKE,
                ).accepted,
        )

        val firstFastFix =
            gate.evaluate(
                sample(x = 45.0, elapsedMillis = 4_000L, speedMps = 15f),
                HIKE,
            )
        val confirmedFastFix =
            gate.evaluate(
                sample(x = 90.0, elapsedMillis = 7_000L, speedMps = 15f),
                HIKE,
            )
        val followingFastFix =
            gate.evaluate(
                sample(x = 135.0, elapsedMillis = 10_000L, speedMps = 15f),
                HIKE,
            )

        assertEquals(RecordingFixQualityStatus.HELD, firstFastFix.status)
        assertTrue(confirmedFastFix.accepted)
        assertEquals(
            RecordingFixQualityReason.CONFIRMED_SUSTAINED_MOVEMENT,
            confirmedFastFix.reason,
        )
        assertTrue(followingFastFix.accepted)
    }

    @Test
    fun longGpsLossDoesNotUseSustainedMovementRecovery() {
        val gate = RecordingFixQualityGate()
        assertTrue(
            gate
                .evaluate(
                    sample(x = 0.0, elapsedMillis = 1_000L, speedMps = 15f),
                    HIKE,
                ).accepted,
        )
        assertEquals(
            RecordingFixQualityStatus.HELD,
            gate
                .evaluate(
                    sample(x = 450.0, elapsedMillis = 31_000L, speedMps = 15f),
                    HIKE,
                ).status,
        )

        val result =
            gate.evaluate(
                sample(x = 454.0, elapsedMillis = 34_000L, speedMps = 15f),
                HIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingFixQualityReason.CONFIRMED_RELOCATION, result.reason)
    }

    @Test
    fun adaptiveSmoothingReducesSmallLateralZigzag() {
        val before = point(x = 0.0, y = 0.0, timeMillis = 1_000L, accuracyMeters = 5f)
        val middle = point(x = 10.0, y = 2.0, timeMillis = 4_000L, accuracyMeters = 12f)
        val after = point(x = 20.0, y = 0.0, timeMillis = 7_000L, accuracyMeters = 5f)

        val result =
            smoothRecordingMiddlePoint(
                before = before,
                middle = middle,
                after = after,
                mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                activityProfile = HIKE,
            )

        assertNotNull(result)
        assertTrue(result!!.adjustmentMeters > 0.35)
        assertTrue(
            haversineMeters(before.latLong, result.point.latLong) <
                haversineMeters(before.latLong, middle.latLong),
        )
    }

    @Test
    fun adaptiveSmoothingNearlyStraightensNoisyStraightTravel() {
        val before = point(x = 0.0, y = 0.0, timeMillis = 1_000L, accuracyMeters = 6f)
        val middle = point(x = 10.0, y = 5.0, timeMillis = 4_000L, accuracyMeters = 12f)
        val after = point(x = 20.0, y = 0.0, timeMillis = 7_000L, accuracyMeters = 6f)

        val result =
            smoothRecordingMiddlePoint(
                before = before,
                middle = middle,
                after = after,
                mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                activityProfile = HIKE,
            )

        assertNotNull(result)
        val straightLineMidpoint = latLongFromMeters(x = 10.0, y = 0.0)
        assertTrue(haversineMeters(result!!.point.latLong, straightLineMidpoint) < 1.5)
    }

    @Test
    fun strongSmoothingAdjustsMoreThanAdaptive() {
        val before = point(x = 0.0, y = 0.0, timeMillis = 1_000L, accuracyMeters = 5f)
        val middle = point(x = 10.0, y = 2.0, timeMillis = 4_000L, accuracyMeters = 12f)
        val after = point(x = 20.0, y = 0.0, timeMillis = 7_000L, accuracyMeters = 5f)

        val adaptive =
            smoothRecordingMiddlePoint(
                before,
                middle,
                after,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                HIKE,
            )
        val strong =
            smoothRecordingMiddlePoint(
                before,
                middle,
                after,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
                HIKE,
            )

        assertNotNull(adaptive)
        assertNotNull(strong)
        assertTrue(strong!!.adjustmentMeters > adaptive!!.adjustmentMeters)
    }

    @Test
    fun smoothingPreservesSharpTurn() {
        val before = point(x = 0.0, y = 0.0, timeMillis = 1_000L)
        val middle = point(x = 10.0, y = 0.0, timeMillis = 4_000L)
        val after = point(x = 10.0, y = 10.0, timeMillis = 7_000L)

        assertNull(
            smoothRecordingMiddlePoint(
                before,
                middle,
                after,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
                HIKE,
            ),
        )
    }

    @Test
    fun adaptiveSmoothingPullsLikelyIsolatedGpsSpikeTowardTravelLine() {
        val before = point(x = 0.0, y = 0.0, timeMillis = 1_000L, accuracyMeters = 6f)
        val middle = point(x = 10.0, y = 18.0, timeMillis = 4_000L, accuracyMeters = 16f)
        val after = point(x = 20.0, y = 0.0, timeMillis = 7_000L, accuracyMeters = 6f)

        val result =
            smoothRecordingMiddlePoint(
                before = before,
                middle = middle,
                after = after,
                mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                activityProfile = HIKE,
            )

        assertNotNull(result)
        assertTrue(result!!.adjustmentMeters >= 5.5)
        assertTrue(
            haversineMeters(result.point.latLong, after.latLong) <
                haversineMeters(middle.latLong, after.latLong),
        )
    }

    @Test
    fun smoothingSkipsGapBeyondActiveRecordingCadence() {
        val result =
            smoothRecordingMiddlePoint(
                before = point(x = 0.0, y = 0.0, timeMillis = 1_000L, accuracyMeters = 5f),
                middle = point(x = 10.0, y = 2.0, timeMillis = 4_000L, accuracyMeters = 12f),
                after = point(x = 20.0, y = 0.0, timeMillis = 10_000L, accuracyMeters = 5f),
                options =
                    RecordingPointSmoothingOptions(
                        mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                        activityProfile = HIKE,
                        sampleIntervalSeconds = 1,
                    ),
            )

        assertNull(result)
    }

    @Test
    fun smoothingAllowsExpectedGapForSlowerRecordingCadence() {
        val result =
            smoothRecordingMiddlePoint(
                before = point(x = 0.0, y = 0.0, timeMillis = 1_000L, accuracyMeters = 5f),
                middle = point(x = 10.0, y = 2.0, timeMillis = 7_000L, accuracyMeters = 12f),
                after = point(x = 20.0, y = 0.0, timeMillis = 13_000L, accuracyMeters = 5f),
                options =
                    RecordingPointSmoothingOptions(
                        mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                        activityProfile = HIKE,
                        sampleIntervalSeconds = 5,
                    ),
            )

        assertNotNull(result)
    }

    @Test
    fun smoothingOffLeavesPointUntouched() {
        assertNull(
            smoothRecordingMiddlePoint(
                before = point(x = 0.0, y = 0.0, timeMillis = 1_000L),
                middle = point(x = 10.0, y = 2.0, timeMillis = 4_000L),
                after = point(x = 20.0, y = 0.0, timeMillis = 7_000L),
                mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
                activityProfile = HIKE,
            ),
        )
    }

    @Test
    fun fourthPointConfirmsAndCorrectsShortGpsReversal() {
        val options =
            RecordingPointSmoothingOptions(
                mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                activityProfile = HIKE,
                sampleIntervalSeconds = 10,
            )
        var points =
            listOf(
                point(x = 0.0, y = 0.0, timeMillis = 0L, accuracyMeters = 5f),
                point(x = 10.0, y = 18.0, timeMillis = 10_000L, accuracyMeters = 7f),
                point(x = 20.0, y = 0.0, timeMillis = 20_000L, accuracyMeters = 5f),
            )

        val result =
            appendCanonicalRecordingPoint(
                existingPoints = points,
                point = point(x = 30.0, y = 0.0, timeMillis = 30_000L, accuracyMeters = 5f),
                options = options,
            )
        points = result.points

        assertTrue(result.confirmedReversalCorrected)
        assertTrue(result.adjustedPointCount >= 1)
        assertTrue(haversineMeters(points[1].latLong, latLongFromMeters(10.0, 0.0)) < 6.0)
        assertEquals(recordingCanonicalPathDistance(points), 30.0, 1.0)
    }

    @Test
    fun fourthPointCorrectsBackwardGpsZWithoutStepProgress() {
        val points =
            listOf(
                point(x = 0.0, y = 0.0, timeMillis = 0L, accuracyMeters = 6f, stepCount = 100),
                point(x = -6.2, y = -4.5, timeMillis = 12_000L, accuracyMeters = 13f, stepCount = 100),
                point(x = 4.2, y = -6.1, timeMillis = 23_000L, accuracyMeters = 31f, stepCount = 100),
            )

        val result =
            appendCanonicalRecordingPoint(
                existingPoints = points,
                point = point(x = 33.1, y = -4.2, timeMillis = 33_000L, accuracyMeters = 15f, stepCount = 110),
                options =
                    RecordingPointSmoothingOptions(
                        mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                        activityProfile = HIKE,
                        sampleIntervalSeconds = 10,
                    ),
            )

        assertTrue(result.confirmedReversalCorrected)
        assertTrue(
            haversineMeters(result.points[1].latLong, result.points[0].latLong) <
                haversineMeters(points[1].latLong, points[0].latLong),
        )
        assertTrue(
            recordingCanonicalPathDistance(result.points) <
                recordingCanonicalPathDistance(points + result.points.last()),
        )
    }

    @Test
    fun canonicalDistanceNeverConnectsAcrossNamedSegmentBoundary() {
        val points =
            listOf(
                point(x = 0.0, y = 0.0, timeMillis = 0L),
                point(x = 10.0, y = 0.0, timeMillis = 10_000L),
                point(x = 1_000.0, y = 0.0, timeMillis = 60_000L).copy(
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                ),
                point(x = 1_010.0, y = 0.0, timeMillis = 70_000L),
            )

        assertEquals(20.0, recordingCanonicalPathDistance(points), 0.2)
    }

    @Test
    fun straightRouteDistanceIsStableAcrossPointDensities() {
        val oneSecondPoints =
            (0..600).map { second ->
                point(
                    x = second * 1.5,
                    y = 0.0,
                    timeMillis = second * 1_000L,
                    accuracyMeters = 6f,
                )
            }
        val tenSecondPoints = oneSecondPoints.filterIndexed { index, _ -> index % 10 == 0 }

        assertEquals(
            recordingCanonicalPathDistance(oneSecondPoints),
            recordingCanonicalPathDistance(tenSecondPoints),
            0.5,
        )
    }

    private fun sample(
        x: Double,
        elapsedMillis: Long,
        accuracyMeters: Float = 5f,
        speedMps: Float = 1.2f,
        speedAccuracyMps: Float = 0.2f,
    ): RecordingFixSample =
        RecordingFixSample(
            latLong = latLongFromMeters(x = x, y = 0.0),
            timeMillis = elapsedMillis,
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            speedAccuracyMps = speedAccuracyMps,
        )

    private fun point(
        x: Double,
        y: Double,
        timeMillis: Long,
        accuracyMeters: Float = 8f,
        stepCount: Int? = null,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = latLongFromMeters(x, y),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = accuracyMeters,
            speedMps = 1.2f,
            stepCount = stepCount,
        )

    private fun latLongFromMeters(
        x: Double,
        y: Double,
    ): LatLong {
        val latitude = ORIGIN.latitude + Math.toDegrees(y / EARTH_RADIUS_METERS)
        val longitude =
            ORIGIN.longitude +
                Math.toDegrees(x / (EARTH_RADIUS_METERS * cos(Math.toRadians(ORIGIN.latitude))))
        return LatLong(latitude, longitude)
    }

    private companion object {
        val ORIGIN = LatLong(45.0, 6.0)
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
    }
}
