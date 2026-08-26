package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingGpsGapSegmentPolicyTest {
    @Test
    fun hikeGapNeedsBothLongDurationAndLargeDisplacement() {
        assertFalse(hardGap(gapMillis = 20_000L, displacementMeters = 15.0, profile = HIKE))
        assertFalse(hardGap(gapMillis = 90_000L, displacementMeters = 10.0, profile = HIKE))
        assertTrue(hardGap(gapMillis = 90_000L, displacementMeters = 100.0, profile = HIKE))
    }

    @Test
    fun bikeGapUsesThirtySecondThresholdAndBikeAllowance() {
        assertFalse(hardGap(gapMillis = 29_999L, displacementMeters = 200.0, profile = BIKE))
        assertTrue(hardGap(gapMillis = 30_000L, displacementMeters = 100.0, profile = BIKE))
        assertFalse(hardGap(gapMillis = 45_000L, displacementMeters = 40.0, profile = BIKE))
        assertTrue(hardGap(gapMillis = 45_000L, displacementMeters = 200.0, profile = BIKE))
    }

    @Test
    fun combinedValidAccuracyExpandsAllowanceButIsCapped() {
        assertFalse(
            hardGap(
                gapMillis = 90_000L,
                displacementMeters = 55.0,
                profile = HIKE,
                beforeAccuracyMeters = 25f,
                afterAccuracyMeters = 30f,
            ),
        )
        assertTrue(
            hardGap(
                gapMillis = 90_000L,
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
                gapMillis = 90_000L,
                displacementMeters = 40.0,
                profile = HIKE,
                beforeAccuracyMeters = null,
                afterAccuracyMeters = 100f,
            ),
        )
        assertTrue(
            hardGap(
                gapMillis = 90_000L,
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
                    displacementMeters = 200.0,
                    activityProfile = scenario.activityProfile,
                ),
            )
        }
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
    ) = RecordedTracePoint(
        latLong = LatLong(45.0 + northMeters / 111_320.0, 6.0),
        elevationMeters = null,
        timeMillis = 0L,
        accuracyMeters = accuracyMeters,
        speedMps = null,
    )

    private companion object {
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
    }
}
