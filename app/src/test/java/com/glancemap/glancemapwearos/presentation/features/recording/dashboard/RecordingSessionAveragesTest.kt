package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingHeartRateObservation
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingHeartRateStatistics
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingHeartRateStatisticsState
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingSessionAveragesTest {
    @Test
    fun heartRateSummaryUsesSensorTimeIndependentlyOfGpsPointValues() {
        val statistics = RecordingHeartRateStatistics()
        statistics.setActive(true, "WATCH", 1_000L)
        statistics.accept(
            (1_000L..60_000L step 10_000L).map { RecordingHeartRateObservation(it, 120.0, "WATCH") } +
                (61_000L..180_000L step 10_000L).map { RecordingHeartRateObservation(it, 180.0, "WATCH") },
            181_000L,
        )
        val state =
            TraceRecordingUiState(
                startedAtMillis = 1_000L,
                heartRateStatistics = statistics.snapshot(181_000L),
                points = listOf(point(2_000L, 80).copy(heartRateBpm = 250)),
            )

        val snapshot = buildRecordingDashboardSnapshot(state, nowMillis = 181_000L)
        assertEquals(160, snapshot.averageHeartRateBpm)
        assertEquals(180, snapshot.maxHeartRateBpm)
    }

    @Test
    fun emptyNewSessionStatisticsCannotBorrowAnAverageFromGpsPoints() {
        val state =
            TraceRecordingUiState(
                startedAtMillis = 1_000L,
                heartRateStatistics = RecordingHeartRateStatisticsState(),
                points = listOf(point(2_000L, 80).copy(heartRateBpm = 250)),
            )

        assertNull(buildRecordingDashboardSnapshot(state, nowMillis = 3_000L).averageHeartRateBpm)
    }

    @Test
    fun watchCadenceUsesRecoveredStepsAndActiveTimeRatherThanGpsSamples() {
        val state =
            TraceRecordingUiState(
                active = true,
                startedAtMillis = 1_000L,
                stepCount = 690,
                points = listOf(point(2_000L, 50), point(4_000L, 200)),
            )

        assertEquals(97, buildRecordingDashboardSnapshot(state, nowMillis = 427_492L).averageCadenceSpm)
        assertEquals(
            97,
            buildRecordingDashboardSnapshot(
                state.copy(points = state.points + point(10_000L, 3_333)),
                nowMillis = 427_492L,
            ).averageCadenceSpm,
        )
    }

    @Test
    fun watchCadenceSelectionIgnoresOldPodInstantaneousCadenceProvenance() {
        val state =
            TraceRecordingUiState(
                startedAtMillis = 1_000L,
                stepCount = 690,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                cadenceFromBluetooth = true,
                points = listOf(point(2_000L, 50), point(4_000L, 200)),
            )

        assertEquals(97, buildRecordingDashboardSnapshot(state, nowMillis = 427_492L).averageCadenceSpm)
    }

    @Test
    fun cadenceUsesTheSamePauseExclusionsAsActiveTime() {
        val state =
            TraceRecordingUiState(
                active = true,
                paused = true,
                startedAtMillis = 1_000L,
                accumulatedPausedMillis = 60_000L,
                pausedAtMillis = 121_000L,
                stepCount = 120,
                points = listOf(point(2_000L, 50)),
            )
        val snapshot = buildRecordingDashboardSnapshot(state, nowMillis = 181_000L)

        assertEquals(60.0, snapshot.durationSeconds, 0.0)
        assertEquals(120, snapshot.averageCadenceSpm)
    }

    @Test
    fun knownZeroStepsProducesZeroAverageRatherThanAStoredCadence() {
        val state = TraceRecordingUiState(startedAtMillis = 1_000L, stepCount = 0, points = listOf(point(2_000L, 90)))

        assertEquals(0, buildRecordingDashboardSnapshot(state, nowMillis = 61_000L).averageCadenceSpm)
    }

    @Test
    fun noActiveTimeHasNoCadenceAverage() {
        val state = TraceRecordingUiState(startedAtMillis = 1_000L, stepCount = 10, points = listOf(point(1_000L, 90)))

        assertNull(buildRecordingDashboardSnapshot(state, nowMillis = 1_000L).averageCadenceSpm)
    }

    @Test
    fun podCadenceRetainsItsMeasuredSourceInsteadOfUsingWatchSteps() {
        val state =
            TraceRecordingUiState(
                startedAtMillis = 1_000L,
                stepCount = 10,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                active = true,
                paused = true,
                pausedAtMillis = 31_000L,
                points = listOf(point(2_000L, 80), point(4_000L, 100)),
            )

        assertEquals(90, buildRecordingDashboardSnapshot(state, nowMillis = 61_000L).averageCadenceSpm)
    }

    private fun point(
        timeMillis: Long,
        cadence: Int,
    ) = RecordedTracePoint(LatLong(0.0, 0.0), null, timeMillis, 5f, 1f, cadenceSpm = cadence)
}
