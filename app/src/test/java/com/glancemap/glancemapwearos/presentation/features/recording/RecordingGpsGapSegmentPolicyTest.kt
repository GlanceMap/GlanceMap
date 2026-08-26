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
