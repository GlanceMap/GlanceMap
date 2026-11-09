package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingGpsGapSegmentPolicyTest {
    @Test
    fun hikeGapNeedsBothLongDurationAndLargeDisplacement() {
        assertFalse(hardGap(gapMillis = 20_000L, displacementMeters = 15.0, profile = HIKE))
        assertFalse(hardGap(gapMillis = 90_000L, displacementMeters = 10.0, profile = HIKE))
        // 100 m in 90 s fits walking pace, so it now stays one continuous track.
        assertFalse(hardGap(gapMillis = 90_000L, displacementMeters = 100.0, profile = HIKE))
        assertTrue(hardGap(gapMillis = 90_000L, displacementMeters = 400.0, profile = HIKE))
    }

    @Test
    fun bikeGapUsesThirtySecondThresholdAndBikeAllowance() {
        assertFalse(hardGap(gapMillis = 29_999L, displacementMeters = 200.0, profile = BIKE))
        assertFalse(hardGap(gapMillis = 30_000L, displacementMeters = 100.0, profile = BIKE))
        assertTrue(hardGap(gapMillis = 30_000L, displacementMeters = 400.0, profile = BIKE))
        assertFalse(hardGap(gapMillis = 45_000L, displacementMeters = 40.0, profile = BIKE))
        assertTrue(hardGap(gapMillis = 45_000L, displacementMeters = 600.0, profile = BIKE))
    }

    @Test
    fun combinedValidAccuracyExpandsAllowanceButIsCapped() {
        assertFalse(
            hardGap(
                gapMillis = LONG_HIKE_GAP_MILLIS,
                displacementMeters = 55.0,
                profile = HIKE,
                beforeAccuracyMeters = 25f,
                afterAccuracyMeters = 30f,
            ),
        )
        assertTrue(
            hardGap(
                gapMillis = LONG_HIKE_GAP_MILLIS,
                displacementMeters = 81.0,
                profile = HIKE,
                beforeAccuracyMeters = 90f,
                afterAccuracyMeters = 90f,
            ),
        )
    }

    @Test
    fun missingOrInvalidAccuracyUsesTheProfileBaseAllowance() {
        assertFalse(
            hardGap(
                gapMillis = LONG_HIKE_GAP_MILLIS,
                displacementMeters = 40.0,
                profile = HIKE,
                beforeAccuracyMeters = null,
                afterAccuracyMeters = 100f,
            ),
        )
        assertTrue(
            hardGap(
                gapMillis = LONG_HIKE_GAP_MILLIS,
                displacementMeters = 41.0,
                profile = HIKE,
                beforeAccuracyMeters = -1f,
                afterAccuracyMeters = 100f,
            ),
        )
    }

    @Test
    fun normalSparseCadenceDoesNotReachHardGapPolicyForHikeOrBike() {
        sparseCadenceScenarios().forEach { scenario ->
            assertFalse(
                "profile=${scenario.activityProfile} cadence=${scenario.sampleIntervalSeconds}s",
                startsGpsGap(
                    sampleIntervalSeconds = scenario.sampleIntervalSeconds,
                    actualGapMillis = scenario.sampleIntervalSeconds * 1_000L,
                    displacementMeters = 200.0,
                    activityProfile = scenario.activityProfile,
                ),
            )
        }
    }

    @Test
    fun sparseCadenceOutageNeedsBothRecoveryThresholdAndQualifyingDisplacement() {
        sparseCadenceScenarios().forEach { scenario ->
            val thresholdMillis = cadenceAwareContinuityThresholdMillis(scenario.sampleIntervalSeconds)
            val nonQualifyingDisplacementMeters =
                if (scenario.activityProfile == BIKE) 80.0 else 40.0
            // Implausible for either profile, so it splits even when the gap could be bridged.
            val qualifyingDisplacementMeters = 2_000.0

            assertFalse(
                "profile=${scenario.activityProfile} cadence=${scenario.sampleIntervalSeconds}s before threshold",
                startsGpsGap(
                    sampleIntervalSeconds = scenario.sampleIntervalSeconds,
                    actualGapMillis = thresholdMillis - 1L,
                    displacementMeters = 200.0,
                    activityProfile = scenario.activityProfile,
                ),
            )
            assertFalse(
                "profile=${scenario.activityProfile} cadence=${scenario.sampleIntervalSeconds}s below allowance",
                startsGpsGap(
                    sampleIntervalSeconds = scenario.sampleIntervalSeconds,
                    actualGapMillis = thresholdMillis + 1L,
                    displacementMeters = nonQualifyingDisplacementMeters,
                    activityProfile = scenario.activityProfile,
                ),
            )
            assertTrue(
                "profile=${scenario.activityProfile} cadence=${scenario.sampleIntervalSeconds}s qualifying outage",
                startsGpsGap(
                    sampleIntervalSeconds = scenario.sampleIntervalSeconds,
                    actualGapMillis = thresholdMillis + 1L,
                    displacementMeters = qualifyingDisplacementMeters,
                    activityProfile = scenario.activityProfile,
                ),
            )
        }
    }

    /** 2026-10-27 19:17 field case: 57 m in 60 s at about 1 m/s split the track before. */
    @Test
    fun steadyWalkingAcrossAMinuteGapStaysContinuous() {
        assertFalse(
            shouldStartRecordingGpsGapSegment(
                previous = point(0.0, 12f, timeMillis = 0L, speedMps = 1.2f),
                current = point(57.0, 32f, timeMillis = 60_000L, speedMps = 1.2f),
                continuityGapMillis = 60_000L,
                activityProfile = HIKE,
            ),
        )
    }

    @Test
    fun gapsBeyondTheBridgeLimitSplitEvenAtAPlausiblePace() {
        assertFalse(
            shouldStartRecordingGpsGapSegment(
                previous = point(0.0, 8f, timeMillis = 0L, speedMps = 1.25f),
                current = point(220.0, 8f, timeMillis = 180_000L, speedMps = 1.25f),
                continuityGapMillis = 180_000L,
                activityProfile = HIKE,
            ),
        )
        assertTrue(
            shouldStartRecordingGpsGapSegment(
                previous = point(0.0, 8f, timeMillis = 0L, speedMps = 1.25f),
                current = point(220.0, 8f, timeMillis = 180_001L, speedMps = 1.25f),
                continuityGapMillis = 180_001L,
                activityProfile = HIKE,
            ),
        )
        assertTrue(
            shouldStartRecordingGpsGapSegment(
                previous = point(0.0, 8f, timeMillis = 0L, speedMps = 5f),
                current = point(400.0, 8f, timeMillis = 90_001L, speedMps = 5f),
                continuityGapMillis = 90_001L,
                activityProfile = BIKE,
            ),
        )
    }

    @Test
    fun bridgedGapAddsItsFullDistanceSoLiveAndSavedDistanceAgree() {
        val previous = point(0.0, 12f, timeMillis = 0L, speedMps = 1.2f)
        val current = point(57.0, 32f, timeMillis = 60_000L, speedMps = 1.2f)
        val estimate =
            estimateRecordingDistanceDelta(
                RecordingDistanceInput(
                    geometricDeltaMeters = 57.0,
                    previous = previous,
                    current = current,
                    elapsedSincePreviousMs = 60_000L,
                    activityProfile = HIKE,
                    isContinuityRecovery = true,
                ),
            )
        assertFalse(estimate.capped)
        assertEquals(57.0, estimate.distanceMeters, 0.001)
    }

    @Test
    fun splitGapCountsTheCappedStraightLineInTheSavedTrack() {
        val before = point(0.0, 8f, timeMillis = 0L, speedMps = 1.2f)
        // 5 minutes later and 2 km away: implausible on foot, so only the believable part counts.
        val after = point(2_000.0, 8f, timeMillis = 300_000L, speedMps = 1.2f)
        val gapMeters = recordingGpsGapDistanceMeters(before, after, HIKE)
        val split =
            after.copy(
                startsNewSegment = true,
                segmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                gapDistanceMeters = gapMeters,
            )

        assertEquals(1.2 * 300.0 * 1.35 + 16.0, gapMeters, 0.5)
        assertEquals(gapMeters, recordingCanonicalPathDistance(listOf(before, split)), 0.001)
        assertEquals(400.0, recordingGpsGapDistanceMeters(before, point(400.0, 8f, 300_000L, 1.2f), HIKE), 0.5)
        assertEquals(0.0, recordingGpsGapDistanceMeters(before, point(400.0, 8f, 0L, 1.2f), HIKE), 0.0)
    }

    /** 2026-11-09 field case: resumed 14 m from the pause point, drawn as one continuous line. */
    @Test
    fun pauseJoinedIntoTheLineCountsTheDistanceWalkedLiveAndSaved() {
        val paused = point(0.0, 8f, timeMillis = 0L)
        val resumed =
            point(14.0, 8f, timeMillis = 120_000L)
                .copy(
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.MANUAL_PAUSE,
                ).withSegmentTransitionDistance(paused, HIKE)

        assertEquals(14.0, resumed.gapDistanceMeters ?: -1.0, 0.1)
        assertEquals(14.0, resumed.bridgedPauseDistanceMeters(), 0.1)
        assertEquals(14.0, recordingCanonicalPathDistance(listOf(paused, resumed)), 0.1)
        assertEquals(1, recordedTraceSegments(listOf(paused, resumed)).size)
    }

    @Test
    fun pauseShownWithAGreyConnectorStillCountsNothing() {
        val paused = point(0.0, 8f, timeMillis = 0L)
        val resumed =
            point(200.0, 8f, timeMillis = 600_000L)
                .copy(
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.AUTO_PAUSE,
                ).withSegmentTransitionDistance(paused, HIKE)

        assertEquals(null, resumed.gapDistanceMeters)
        assertEquals(0.0, resumed.bridgedPauseDistanceMeters(), 0.0)
        assertEquals(0.0, recordingCanonicalPathDistance(listOf(paused, resumed)), 0.0)
        assertEquals(2, recordedTraceSegments(listOf(paused, resumed)).size)
    }

    @Test
    fun gpsGapDistanceIsNotAddedTwiceByThePauseBridge() {
        val before = point(0.0, 8f, timeMillis = 0L, speedMps = 1.2f)
        val split =
            point(400.0, 8f, timeMillis = 300_000L, speedMps = 1.2f)
                .copy(
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                ).withSegmentTransitionDistance(before, HIKE)

        assertEquals(400.0, split.gapDistanceMeters ?: -1.0, 0.5)
        assertEquals(0.0, split.bridgedPauseDistanceMeters(), 0.0)
    }

    @Test
    fun pauseBoundaryWithoutGapDistanceStillCountsNothing() {
        val before = point(0.0, 8f, timeMillis = 0L)
        val resumed =
            point(500.0, 8f, timeMillis = 600_000L).copy(
                startsNewSegment = true,
                segmentStartReason = RecordingSegmentStartReason.AUTO_PAUSE,
            )
        assertEquals(0.0, recordingCanonicalPathDistance(listOf(before, resumed)), 0.0)
    }

    @Test
    fun fixedWatchGpsAccuracyFloorDoesNotWidenThePlausibilityCheck() {
        val previous = point(0.0, 125f, timeMillis = 0L).copy(effectiveAccuracyMeters = 18f)
        val current = point(400.0, 125f, timeMillis = 90_000L).copy(effectiveAccuracyMeters = 18f)
        assertTrue(shouldStartRecordingGpsGapSegment(previous, current, 90_000L, HIKE))
    }

    private fun hardGap(
        gapMillis: Long,
        displacementMeters: Double,
        profile: String,
        beforeAccuracyMeters: Float? = 8f,
        afterAccuracyMeters: Float? = 8f,
    ): Boolean =
        shouldStartRecordingGpsGapSegment(
            previous = point(0.0, beforeAccuracyMeters),
            current = point(displacementMeters, afterAccuracyMeters),
            continuityGapMillis = gapMillis,
            activityProfile = profile,
        )

    /** Mirrors the ViewModel's continuity-recovery gate before the hard-gap policy. */
    private fun startsGpsGap(
        sampleIntervalSeconds: Int,
        actualGapMillis: Long,
        displacementMeters: Double,
        activityProfile: String,
    ): Boolean =
        shouldStartRecordingGpsGapSegment(
            previous = point(0.0, 8f),
            current = point(displacementMeters, 8f),
            continuityGapMillis =
                resolveRecordingContinuityRecoveryGapMillis(
                    deliveryGapMillis = actualGapMillis,
                    committedPointGapMillis = actualGapMillis,
                    thresholdMillis = cadenceAwareContinuityThresholdMillis(sampleIntervalSeconds),
                ),
            activityProfile = activityProfile,
        )

    private fun cadenceAwareContinuityThresholdMillis(sampleIntervalSeconds: Int): Long =
        maxOf(
            sampleIntervalSeconds * 2_000L,
            15_000L,
            sampleIntervalSeconds * 1_000L * (5L / 2L),
        )

    private fun sparseCadenceScenarios() =
        listOf(
            SparseCadenceScenario(HIKE, 60),
            SparseCadenceScenario(HIKE, 120),
            SparseCadenceScenario(BIKE, 60),
            SparseCadenceScenario(BIKE, 120),
        )

    private data class SparseCadenceScenario(
        val activityProfile: String,
        val sampleIntervalSeconds: Int,
    )

    private fun point(
        northMeters: Double,
        accuracyMeters: Float?,
        timeMillis: Long = 0L,
        speedMps: Float? = null,
    ) = RecordedTracePoint(
        latLong = LatLong(45.0 + northMeters / 111_320.0, 6.0),
        elevationMeters = null,
        timeMillis = timeMillis,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
    )

    private companion object {
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE

        // Beyond the hike bridge limit, so only the displacement allowance decides.
        const val LONG_HIKE_GAP_MILLIS = 200_000L
    }
}
