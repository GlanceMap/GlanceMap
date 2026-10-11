package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingCalorieEstimate
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.buildRecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.recordingRecapMetricsForSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GpxDistanceSemanticsTest {
    @Test
    fun gpxDetailsUseGeometryWhileLiveAndSavedActivityMetricsRemainIntact() {
        val live = recordedSummary()
        val file = fileState(live)
        val details = requireNotNull(file.summaryForGpxDetails())

        assertEquals(10_500.0, live.distanceMeters, 0.0)
        assertEquals(10_500.0 / 3_600.0, requireNotNull(live.averageSpeedMps), 0.0)
        assertEquals(10_000.0, file.distance, 0.0)
        assertEquals(10_000.0, details.distanceMeters, 0.0)
        assertEquals(10_000.0 / 3_600.0, requireNotNull(details.averageSpeedMps), 0.0)
        assertSame(live, file.activitySummary)
        // Every other metric, including max speed, sensors, calories and both durations, is preserved.
        assertEquals(live.copy(distanceMeters = 10_000.0, averageSpeedMps = 10_000.0 / 3_600.0), details)
    }

    @Test
    fun activityDetailsRecapFormatsGeometryDistanceSpeedAndPaceTogether() {
        val file = fileState(recordedSummary())
        val metrics =
            activityDetailsMetrics(
                gpxFile = file,
                isMetric = true,
                fallbackDistanceValue = "10.00",
                fallbackDistanceUnit = "km",
                fallbackElevationValue = "100",
                fallbackElevationUnit = "m",
                fallbackElevationLossValue = "90",
                fallbackElevationLossUnit = "m",
            ).associateBy { it.label }

        assertEquals("10.00 km", metrics.getValue("Distance").valueText)
        assertEquals("10.0 km/h", metrics.getValue("Speed (Avg)").valueText)
        assertEquals("6:00 min/km", metrics.getValue("Pace (Avg)").valueText)

        val recordingMetrics =
            recordingRecapMetricsForSnapshot(requireNotNull(file.activitySummary), true).associateBy { it.label }
        assertEquals("10.50 km", recordingMetrics.getValue("Distance").valueText)
        assertEquals("10.5 km/h", recordingMetrics.getValue("Speed (Avg)").valueText)
        assertEquals("5:43 min/km", recordingMetrics.getValue("Pace (Avg)").valueText)
    }

    @Test
    fun canonicalProfileWinsOverParserAndActivityOnColdAndWarmCachePaths() {
        assertEquals(10_000.0, resolveGpxDisplayDistance(10_000.0, 10_100.0, 10_500.0), 0.0)
        assertEquals(10_000.0, resolveGpxDisplayDistance(10_000.0, null, 10_500.0), 0.0)
    }

    @Test
    fun importedGpxRetainsGeometryDistanceWithoutAnActivitySummary() {
        val file = fileState(null)

        assertEquals(10_000.0, resolveGpxDisplayDistance(10_000.0, 10_000.0, null), 0.0)
        assertEquals("10" to "km", file.formattedDistance(isMetric = true))
        assertNull(file.summaryForGpxDetails())
    }

    @Test
    fun invalidGeometryFallsBackToParserThenRecordingThenZero() {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(10_000.0, resolveGpxDisplayDistance(invalid, 10_000.0, 10_500.0), 0.0)
            assertEquals(10_500.0, resolveGpxDisplayDistance(invalid, invalid, 10_500.0), 0.0)
            assertEquals(0.0, resolveGpxDisplayDistance(invalid, invalid, invalid), 0.0)
        }
        assertEquals(10_500.0, resolveGpxDisplayDistance(0.0, null, 10_500.0), 0.0)
        assertEquals(0.0, resolveGpxDisplayDistance(0.0, null, null), 0.0)
    }

    @Test
    fun missingActiveDurationDoesNotReuseActivityAverageSpeedForGeometry() {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val summary = recordedSummary().copy(durationSeconds = invalid)
            val file = fileState(summary)

            assertNull(requireNotNull(file.summaryForGpxDetails()).averageSpeedMps)
            assertEquals(10_500.0 / 3_600.0, requireNotNull(summary.averageSpeedMps), 0.0)
        }
    }

    private fun recordedSummary(): RecordingDashboardSnapshot =
        buildRecordingDashboardSnapshot(
            state =
                TraceRecordingUiState(
                    active = true,
                    startedAtMillis = 0L,
                    accumulatedPausedMillis = 3_600_000L,
                    distanceMeters = 10_500.0,
                ),
            nowMillis = 7_200_000L,
        ).copy(
            fastestSpeedMps = 5.0,
            averageHeartRateBpm = 120,
            maxHeartRateBpm = 150,
            stepCount = 12_345,
            averageCadenceSpm = 90,
            averagePowerWatts = 180,
            barometricPressureHpa = 1_013.0,
            calorieEstimate = RecordingCalorieEstimate(grossKcal = 500.0, activeKcal = 400.0, restingKcal = 100.0),
        )

    private fun fileState(summary: RecordingDashboardSnapshot?): GpxFileState =
        GpxFileState(
            name = "distance-test",
            path = "distance-test.gpx",
            title = null,
            distance = resolveGpxDisplayDistance(10_000.0, 10_000.0, summary?.distanceMeters),
            elevationGain = 100.0,
            elevationLoss = 90.0,
            estimatedDurationSec = null,
            isActivity = summary != null,
            activitySummary = summary,
        )
}
