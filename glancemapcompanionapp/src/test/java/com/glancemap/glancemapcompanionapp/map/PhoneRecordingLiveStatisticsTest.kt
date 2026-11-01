package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.recording.RecordingPoint
import com.glancemap.trailcore.recording.RecordingSessionLifecycleState
import com.glancemap.trailcore.recording.RecordingSessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneRecordingLiveStatisticsTest {
    @Test
    fun idleStatisticsUseTheSelectedProfile() {
        val state = PhoneRecordingUiState()
        val settings = PhoneGeneralSettings(activityProfile = PhoneActivityProfile.BIKE)
        val track = phoneRecordingTrackStatistics(state, settings)
        val statistics = phoneRecordingLiveStatistics(state, track, 1_000L)
        assertEquals(PhoneActivityProfile.BIKE.storageValue, statistics.activityProfile)
        assertNull(statistics.distanceMeters)
    }

    @Test
    fun liveTimingExcludesPausesAndLeavesUnavailableSensorsUnknown() {
        val state =
            PhoneRecordingUiState(
                session =
                    RecordingSessionLifecycleState(
                        phase = RecordingSessionPhase.PAUSED,
                        startedAtMillis = 1_000L,
                        pausedAtMillis = 11_000L,
                    ),
                distanceMeters = 100.0,
            )
        val track = phoneRecordingTrackStatistics(state, PhoneGeneralSettings())
        val statistics = phoneRecordingLiveStatistics(state, track, 21_000L)

        assertEquals(100.0, requireNotNull(statistics.distanceMeters), 0.0)
        assertEquals(10.0, requireNotNull(statistics.durationSeconds), 0.0)
        assertEquals(20.0, requireNotNull(statistics.totalDurationSeconds), 0.0)
        assertEquals(10.0, requireNotNull(statistics.averageSpeedMps), 0.0)
        assertNull(statistics.stepCount)
        assertNull(statistics.averageHeartRateBpm)
        assertNull(statistics.elevationGainMeters)
    }

    @Test
    fun liveTrackDoesNotCountTheGapBetweenSegmentsAsSpeed() {
        val points =
            listOf(
                point(0.0, 1_000L),
                point(0.001, 11_000L, startsNewSegment = true),
            )
        val state =
            PhoneRecordingUiState(
                session =
                    RecordingSessionLifecycleState(phase = RecordingSessionPhase.RECORDING, startedAtMillis = 1_000L),
                points = points,
            )
        val track = phoneRecordingTrackStatistics(state, PhoneGeneralSettings())
        val statistics = phoneRecordingLiveStatistics(state, track, 11_000L)
        assertNull(statistics.fastestSpeedMps)
        assertEquals(0.0, requireNotNull(statistics.caloriesGrossKcal), 0.0)
        assertEquals(2, statistics.pointCount)
    }

    @Test
    fun liveStepCountUsesActiveTimeForHikingCadence() {
        val state =
            PhoneRecordingUiState(
                session =
                    RecordingSessionLifecycleState(phase = RecordingSessionPhase.RECORDING, startedAtMillis = 1_000L),
            )
        val track = phoneRecordingTrackStatistics(state, PhoneGeneralSettings())
        val statistics = phoneRecordingLiveStatistics(state, track, 61_000L, stepCount = 90)
        assertEquals(90, statistics.stepCount)
        assertEquals(90, statistics.averageCadenceSpm)
    }

    private fun point(
        longitude: Double,
        timestamp: Long,
        startsNewSegment: Boolean = false,
    ) = RecordingPoint(
        location = GeoPoint(46.0, longitude),
        elevationMeters = null,
        timeMillis = timestamp,
        accuracyMeters = 5f,
        speedMps = null,
        startsNewSegment = startsNewSegment,
    )
}
