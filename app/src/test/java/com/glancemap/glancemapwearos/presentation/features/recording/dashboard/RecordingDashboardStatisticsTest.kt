package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingDashboardStatisticsTest {
    @Test
    fun clockAndLiveReadingChangesReuseRecordedStatisticsAndKeepSnapshotResults() {
        val points = samplePoints()
        val cache = RecordingDashboardStatisticsCache()
        val statistics = cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        val sameStatistics =
            cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        assertSame(statistics, sameStatistics)

        val firstState = recordingState(points, latestLivePoint = samplePoint(3, timeMillis = 9_500L, speedMps = 2.5f))
        val firstSnapshot =
            buildRecordingDashboardSnapshot(
                state = firstState,
                nowMillis = 10_000L,
                userWeightKg = USER_WEIGHT_KG,
                backpackWeightKg = BACKPACK_WEIGHT_KG,
                bikeWeightKg = BIKE_WEIGHT_KG,
                activityProfile = WALK_PROFILE,
                recordedStatistics = statistics,
            )
        assertEquals(
            buildRecordingDashboardSnapshot(
                state = firstState,
                nowMillis = 10_000L,
                userWeightKg = USER_WEIGHT_KG,
                backpackWeightKg = BACKPACK_WEIGHT_KG,
                bikeWeightKg = BIKE_WEIGHT_KG,
                activityProfile = WALK_PROFILE,
            ),
            firstSnapshot,
        )

        val laterState = firstState.copy(latestLivePoint = samplePoint(3, timeMillis = 19_500L, speedMps = 3.5f))
        val laterSnapshot =
            buildRecordingDashboardSnapshot(
                state = laterState,
                nowMillis = 20_000L,
                userWeightKg = USER_WEIGHT_KG,
                backpackWeightKg = BACKPACK_WEIGHT_KG,
                bikeWeightKg = BIKE_WEIGHT_KG,
                activityProfile = WALK_PROFILE,
                recordedStatistics = sameStatistics,
            )
        assertEquals(
            buildRecordingDashboardSnapshot(
                state = laterState,
                nowMillis = 20_000L,
                userWeightKg = USER_WEIGHT_KG,
                backpackWeightKg = BACKPACK_WEIGHT_KG,
                bikeWeightKg = BIKE_WEIGHT_KG,
                activityProfile = WALK_PROFILE,
            ),
            laterSnapshot,
        )
        assertNotEquals(firstSnapshot.durationSeconds, laterSnapshot.durationSeconds)
        assertNotEquals(firstSnapshot.currentSpeedMps, laterSnapshot.currentSpeedMps)
    }

    @Test
    fun sensorOnlyChangesReuseRecordedStatisticsAndUpdateLiveValues() {
        val points = samplePoints()
        val cache = RecordingDashboardStatisticsCache()
        val statistics = cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        val firstState =
            recordingState(points).copy(
                heartRateBpm = 140,
                cadenceSpm = 82,
                externalPowerWatts = 180,
            )
        val secondState =
            firstState.copy(
                heartRateBpm = 155,
                cadenceSpm = 96,
                externalPowerWatts = 240,
            )

        val firstSnapshot = buildSnapshotWithStatistics(firstState, 30_000L, statistics)
        val secondSnapshot = buildSnapshotWithStatistics(secondState, 30_000L, statistics)

        assertSame(
            statistics,
            cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE),
        )
        assertEquals(140, firstSnapshot.heartRateBpm)
        assertEquals(155, secondSnapshot.heartRateBpm)
        assertEquals(82, firstSnapshot.cadenceSpm)
        assertEquals(96, secondSnapshot.cadenceSpm)
        assertEquals(180, firstSnapshot.powerWatts)
        assertEquals(240, secondSnapshot.powerWatts)
        assertEquals(
            buildRecordingDashboardSnapshot(
                state = secondState,
                nowMillis = 30_000L,
                userWeightKg = USER_WEIGHT_KG,
                backpackWeightKg = BACKPACK_WEIGHT_KG,
                bikeWeightKg = BIKE_WEIGHT_KG,
                activityProfile = WALK_PROFILE,
            ),
            secondSnapshot,
        )
    }

    @Test
    fun appendedAndSameSizeReplacedPointsInvalidateRecordedStatistics() {
        val points = samplePoints()
        val cache = RecordingDashboardStatisticsCache()
        val initial = cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)

        val appendedPoints = points + samplePoint(4, timeMillis = 40_000L)
        val appended =
            cache.getOrBuild(
                appendedPoints,
                USER_WEIGHT_KG,
                BACKPACK_WEIGHT_KG,
                BIKE_WEIGHT_KG,
                WALK_PROFILE,
            )
        assertNotSame(initial, appended)
        val appendedState = recordingState(appendedPoints)
        assertSnapshotMatchesFreshBuilder(
            state = appendedState,
            nowMillis = 50_000L,
            statistics = appended,
            inputs = WALK_INPUTS,
        )

        val replacedPoints =
            points.mapIndexed { index, point ->
                if (index == 1) point.copy(elevationMeters = 160.0, speedMps = 4.0f, heartRateBpm = 180) else point
            }
        val replaced =
            cache.getOrBuild(
                replacedPoints,
                USER_WEIGHT_KG,
                BACKPACK_WEIGHT_KG,
                BIKE_WEIGHT_KG,
                WALK_PROFILE,
            )
        assertNotSame(initial, replaced)
        assertNotEquals(initial, replaced)

        val state = recordingState(replacedPoints)
        assertSnapshotMatchesFreshBuilder(
            state = state,
            nowMillis = 50_000L,
            statistics = replaced,
            inputs = WALK_INPUTS,
        )
    }

    @Test
    fun weightAndActivityProfileChangesInvalidateStatistics() {
        val points = samplePoints()
        val cache = RecordingDashboardStatisticsCache()
        val initial = cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        val heavier = cache.getOrBuild(points, 90f, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        assertNotSame(initial, heavier)
        assertNotEquals(initial.calorieEstimate, heavier.calorieEstimate)

        val bike = cache.getOrBuild(points, 90f, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, BIKE_PROFILE)
        assertNotSame(heavier, bike)
        assertNotEquals(heavier.calorieEstimate, bike.calorieEstimate)

        val state = recordingState(points)
        assertSnapshotMatchesFreshBuilder(
            state = state,
            nowMillis = 30_000L,
            statistics = heavier,
            inputs = HEAVY_WALK_INPUTS,
        )
        assertSnapshotMatchesFreshBuilder(
            state = state,
            nowMillis = 30_000L,
            statistics = bike,
            inputs = HEAVY_BIKE_INPUTS,
        )
    }

    @Test
    fun pauseResumeKeepsRecordedStatisticsButRebuildsCurrentDurationOnly() {
        val points = samplePoints()
        val cache = RecordingDashboardStatisticsCache()
        val statistics = cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        val pausedState = recordingState(points).copy(paused = true, pausedAtMillis = 30_000L)
        val resumedState = recordingState(points).copy(accumulatedPausedMillis = 5_000L)
        val pausedStatistics =
            cache.getOrBuild(
                points,
                USER_WEIGHT_KG,
                BACKPACK_WEIGHT_KG,
                BIKE_WEIGHT_KG,
                WALK_PROFILE,
            )

        assertSame(statistics, pausedStatistics)
        val pausedSnapshot = buildSnapshotWithStatistics(pausedState, 40_000L, statistics)
        val resumedSnapshot = buildSnapshotWithStatistics(resumedState, 40_000L, pausedStatistics)
        assertNotEquals(pausedSnapshot.durationSeconds, resumedSnapshot.durationSeconds)
        assertEquals(statistics.calorieEstimate, pausedSnapshot.calorieEstimate)
        assertEquals(statistics.calorieEstimate, resumedSnapshot.calorieEstimate)
    }

    @Test
    fun staleExternalDistanceFallbackRemainsCurrentInputLogicWithCachedStatistics() {
        val points = samplePoints()
        val cache = RecordingDashboardStatisticsCache()
        val statistics = cache.getOrBuild(points, USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        val staleState =
            recordingState(points).copy(
                distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                distanceMeters = 950.0,
                externalDistanceMeters = 500.0,
                externalDistanceUpdatedAtMillis = 1_000L,
                externalDistanceFallbackBaseMeters = 1_000.0,
                externalDistanceFallbackGpsMeters = 900.0,
            )
        val freshState = staleState.copy(externalDistanceUpdatedAtMillis = 49_000L)
        val staleSnapshot = buildSnapshotWithStatistics(staleState, 50_000L, statistics)
        val freshSnapshot = buildSnapshotWithStatistics(freshState, 50_000L, statistics)

        assertEquals(1_050.0, staleSnapshot.distanceMeters, 0.0)
        assertEquals(500.0, freshSnapshot.distanceMeters, 0.0)
        assertEquals(
            buildRecordingDashboardSnapshot(
                state = staleState,
                nowMillis = 50_000L,
                userWeightKg = USER_WEIGHT_KG,
                backpackWeightKg = BACKPACK_WEIGHT_KG,
                bikeWeightKg = BIKE_WEIGHT_KG,
                activityProfile = WALK_PROFILE,
            ),
            staleSnapshot,
        )
        assertEquals(
            buildRecordingDashboardSnapshot(
                state = freshState,
                nowMillis = 50_000L,
                userWeightKg = USER_WEIGHT_KG,
                backpackWeightKg = BACKPACK_WEIGHT_KG,
                bikeWeightKg = BIKE_WEIGHT_KG,
                activityProfile = WALK_PROFILE,
            ),
            freshSnapshot,
        )
    }

    private fun buildSnapshotWithStatistics(
        state: TraceRecordingUiState,
        nowMillis: Long,
        statistics: RecordingDashboardStatistics,
    ): RecordingDashboardSnapshot =
        buildRecordingDashboardSnapshot(
            state = state,
            nowMillis = nowMillis,
            userWeightKg = USER_WEIGHT_KG,
            backpackWeightKg = BACKPACK_WEIGHT_KG,
            bikeWeightKg = BIKE_WEIGHT_KG,
            activityProfile = WALK_PROFILE,
            recordedStatistics = statistics,
        )

    private fun assertSnapshotMatchesFreshBuilder(
        state: TraceRecordingUiState,
        nowMillis: Long,
        statistics: RecordingDashboardStatistics,
        inputs: StatisticsInputs,
    ) {
        val freshSnapshot =
            buildRecordingDashboardSnapshot(
                state = state,
                nowMillis = nowMillis,
                userWeightKg = inputs.userWeightKg,
                backpackWeightKg = inputs.backpackWeightKg,
                bikeWeightKg = inputs.bikeWeightKg,
                activityProfile = inputs.activityProfile,
            )
        val assembledSnapshot =
            buildRecordingDashboardSnapshot(
                state = state,
                nowMillis = nowMillis,
                userWeightKg = inputs.userWeightKg,
                backpackWeightKg = inputs.backpackWeightKg,
                bikeWeightKg = inputs.bikeWeightKg,
                activityProfile = inputs.activityProfile,
                recordedStatistics = statistics,
            )
        assertEquals(freshSnapshot, assembledSnapshot)
    }

    private fun recordingState(
        points: List<RecordedTracePoint>,
        latestLivePoint: RecordedTracePoint? = null,
    ): TraceRecordingUiState =
        TraceRecordingUiState(
            active = true,
            startedAtMillis = 0L,
            points = points,
            latestLivePoint = latestLivePoint,
            distanceMeters = 1_000.0,
            gpsActiveDurationMillis = 30_000L,
        )

    private fun samplePoints(): List<RecordedTracePoint> =
        listOf(
            samplePoint(index = 0, timeMillis = 0L),
            samplePoint(index = 1, timeMillis = 10_000L),
            samplePoint(index = 2, timeMillis = 20_000L),
        )

    private fun samplePoint(
        index: Int,
        timeMillis: Long,
        speedMps: Float = (index + 1).toFloat(),
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = LatLong(0.0, index * 0.001),
            elevationMeters = 100.0 + index,
            timeMillis = timeMillis,
            accuracyMeters = 5f,
            speedMps = speedMps,
            heartRateBpm = 130 + index,
            cadenceSpm = 80 + index,
            powerWatts = 150 + index,
        )

    private data class StatisticsInputs(
        val userWeightKg: Float,
        val backpackWeightKg: Float,
        val bikeWeightKg: Float,
        val activityProfile: String,
    )

    private companion object {
        const val USER_WEIGHT_KG = 75f
        const val BACKPACK_WEIGHT_KG = 5f
        const val BIKE_WEIGHT_KG = 12f
        const val WALK_PROFILE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE_PROFILE = SettingsRepository.ACTIVITY_PROFILE_BIKE
        val WALK_INPUTS =
            StatisticsInputs(USER_WEIGHT_KG, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        val HEAVY_WALK_INPUTS =
            StatisticsInputs(90f, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, WALK_PROFILE)
        val HEAVY_BIKE_INPUTS =
            StatisticsInputs(90f, BACKPACK_WEIGHT_KG, BIKE_WEIGHT_KG, BIKE_PROFILE)
    }
}
