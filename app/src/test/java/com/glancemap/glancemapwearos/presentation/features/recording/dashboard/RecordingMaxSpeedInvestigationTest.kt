package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.haversineMeters
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingSegmentStartReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import kotlin.math.pow

class RecordingMaxSpeedInvestigationTest {
    @Test
    fun isolatedProviderSpeedSpikeCurrentlyDefinesMaximum() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 1.5f),
                point(xMeters = 4.5, timeMillis = 3_000L, speedMps = 18.0f),
                point(xMeters = 9.0, timeMillis = 6_000L, speedMps = 1.5f),
            )

        val statistics = buildStatistics(points)

        assertEquals(18.0, statistics.fastestRecordedSpeedMps ?: -1.0, 0.0)
        assertEquals(listOf(1.5, 1.5), adjacentGeometricSpeeds(points, index = 1).map { it.round(1) })
        assertFalse(isGeometricallyCorroborated(points, index = 1))
    }

    @Test
    fun sustainedHigherSpeedIsSupportedByGeometryAndRemainsTheMaximum() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 5.5f),
                point(xMeters = 18.0, timeMillis = 3_000L, speedMps = 6.0f),
                point(xMeters = 37.5, timeMillis = 6_000L, speedMps = 6.5f),
                point(xMeters = 57.0, timeMillis = 9_000L, speedMps = 6.0f),
            )

        val statistics = buildStatistics(points)

        assertEquals(6.5, statistics.fastestRecordedSpeedMps ?: -1.0, 0.0)
        assertTrue(points.count { it.speedMps?.let { speed -> speed >= 6.0f } == true } >= 3)
        assertTrue(isGeometricallyCorroborated(points, index = 2))
    }

    @Test
    fun geometrySeparatesNormalMovementFromHighProviderSpeed() {
        val slowGeometry =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 18.0f),
                point(xMeters = 4.5, timeMillis = 3_000L, speedMps = 18.0f),
                point(xMeters = 9.0, timeMillis = 6_000L, speedMps = 18.0f),
            )
        val fastGeometry =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 18.0f),
                point(xMeters = 54.0, timeMillis = 3_000L, speedMps = 18.0f),
                point(xMeters = 108.0, timeMillis = 6_000L, speedMps = 18.0f),
            )

        assertTrue(adjacentGeometricSpeeds(slowGeometry, index = 1).all { it < 2.0 })
        assertFalse(isGeometricallyCorroborated(slowGeometry, index = 1))
        assertTrue(adjacentGeometricSpeeds(fastGeometry, index = 1).all { it > 15.0 })
        assertTrue(isGeometricallyCorroborated(fastGeometry, index = 1))
    }

    @Test
    fun neighboringProviderSpeedsCanAgreeWhileGeometryRejectsTheSameSpike() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 18.0f),
                point(xMeters = 4.5, timeMillis = 3_000L, speedMps = 18.0f),
                point(xMeters = 9.0, timeMillis = 6_000L, speedMps = 18.0f),
            )

        assertEquals(listOf(18.0, 18.0), adjacentProviderSpeeds(points, index = 1))
        assertFalse(isGeometricallyCorroborated(points, index = 1))
    }

    @Test
    fun oneUnavailableNeighborStillAllowsGeometricCorroboration() {
        val points =
            listOf(
                point(xMeters = -18.0, timeMillis = 3_000L, speedMps = 6.0f),
                point(xMeters = 0.0, timeMillis = 3_000L, speedMps = 6.0f),
                point(xMeters = 18.0, timeMillis = 6_000L, speedMps = 6.0f),
            )

        assertEquals(listOf(6.0), adjacentGeometricSpeeds(points, index = 1).map { it.round(1) })
        assertTrue(isGeometricallyCorroborated(points, index = 1))
    }

    @Test
    fun twoUnavailableNeighborsDoNotCorroborateProviderSpeed() {
        val points =
            listOf(
                point(xMeters = -18.0, timeMillis = 3_000L, speedMps = 6.0f),
                point(xMeters = 0.0, timeMillis = 3_000L, speedMps = 6.0f),
                point(xMeters = 18.0, timeMillis = 3_000L, speedMps = 6.0f),
            )

        assertTrue(adjacentGeometricSpeeds(points, index = 1).isEmpty())
        assertFalse(isGeometricallyCorroborated(points, index = 1))
    }

    @Test
    fun geometricSpeedsUseActualCadenceRatherThanFixedThreeSecondIntervals() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 2.0f),
                point(xMeters = 6.0, timeMillis = 3_000L, speedMps = 2.0f),
                point(xMeters = 26.0, timeMillis = 13_000L, speedMps = 2.0f),
                point(xMeters = 32.5, timeMillis = 16_250L, speedMps = 2.0f),
            )

        adjacentGeometricSpeeds(points, index = 2).forEach { speed ->
            assertEquals(2.0, speed, 0.05)
        }
        assertEquals(2.0, buildStatistics(points).fastestRecordedSpeedMps ?: -1.0, 0.0)
    }

    @Test
    fun invalidProviderSpeedsDoNotBecomeMaximum() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = null),
                point(xMeters = 3.0, timeMillis = 3_000L, speedMps = 0.0f),
                point(xMeters = 6.0, timeMillis = 6_000L, speedMps = Float.NaN),
                point(xMeters = 9.0, timeMillis = 9_000L, speedMps = Float.POSITIVE_INFINITY),
                point(xMeters = 12.0, timeMillis = 12_000L, speedMps = 3.0f),
            )

        assertEquals(3.0, buildStatistics(points).fastestRecordedSpeedMps ?: -1.0, 0.0)
    }

    @Test
    fun stationaryGpsDriftDoesNotGeometricallySupportHighProviderSpeed() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 0.5f),
                point(xMeters = 5.0, timeMillis = 10_000L, speedMps = 12.0f),
                point(xMeters = 8.0, timeMillis = 13_000L, speedMps = 0.5f),
            )

        assertEquals(12.0, buildStatistics(points).fastestRecordedSpeedMps ?: -1.0, 0.0)
        assertTrue(adjacentGeometricSpeeds(points, index = 1).all { it < 1.5 })
        assertFalse(isGeometricallyCorroborated(points, index = 1))
    }

    @Test
    fun pauseGapIsNotUsedAsGeometricCorroboration() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 1.5f),
                point(
                    xMeters = 2_400.0,
                    timeMillis = 120_000L,
                    speedMps = 20.0f,
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                ),
                point(xMeters = 2_404.5, timeMillis = 123_000L, speedMps = 1.5f),
            )

        assertEquals(20.0, rawSegmentSpeed(points[0], points[1]) ?: -1.0, 0.05)
        assertEquals(listOf(1.5), adjacentGeometricSpeeds(points, index = 1).map { it.round(1) })
        assertFalse(isGeometricallyCorroborated(points, index = 1))
    }

    @Test
    fun sameGeometryRuleWorksForHikeAndBikeWithoutProfileCaps() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 8.0f),
                point(xMeters = 24.0, timeMillis = 3_000L, speedMps = 8.0f),
                point(xMeters = 48.0, timeMillis = 6_000L, speedMps = 8.0f),
                point(xMeters = 72.0, timeMillis = 9_000L, speedMps = 8.0f),
            )

        assertEquals(
            8.0,
            buildStatistics(points, SettingsRepository.ACTIVITY_PROFILE_HIKE).fastestRecordedSpeedMps ?: -1.0,
            0.0,
        )
        assertEquals(
            8.0,
            buildStatistics(points, SettingsRepository.ACTIVITY_PROFILE_BIKE).fastestRecordedSpeedMps ?: -1.0,
            0.0,
        )
        assertTrue(isGeometricallyCorroborated(points, index = 2))
    }

    private fun buildStatistics(
        points: List<RecordedTracePoint>,
        activityProfile: String = SettingsRepository.ACTIVITY_PROFILE_HIKE,
    ): RecordingDashboardStatistics =
        buildRecordingDashboardStatistics(
            points = points,
            userWeightKg = 75f,
            backpackWeightKg = 5f,
            bikeWeightKg = 12f,
            activityProfile = activityProfile,
        )

    private fun isGeometricallyCorroborated(
        points: List<RecordedTracePoint>,
        index: Int,
    ): Boolean {
        val providerSpeed = points[index].speedMps?.takeIf { it.isFinite() && it > 0f } ?: return false
        return adjacentGeometricSpeeds(points, index).any { geometricSpeed ->
            geometricSpeed in providerSpeed * 0.5..providerSpeed * 2.0
        }
    }

    private fun adjacentGeometricSpeeds(
        points: List<RecordedTracePoint>,
        index: Int,
    ): List<Double> =
        listOfNotNull(
            if (index > 0 && points[index].isNotSegmentStart()) {
                rawSegmentSpeed(points[index - 1], points[index])
            } else {
                null
            },
            if (index < points.lastIndex && points[index + 1].isNotSegmentStart()) {
                rawSegmentSpeed(points[index], points[index + 1])
            } else {
                null
            },
        )

    private fun adjacentProviderSpeeds(
        points: List<RecordedTracePoint>,
        index: Int,
    ): List<Double> =
        listOfNotNull(
            points.getOrNull(index - 1)?.speedMps?.toDouble(),
            points.getOrNull(index + 1)?.speedMps?.toDouble(),
        ).filter { it.isFinite() && it > 0.0 }

    private fun RecordedTracePoint.isNotSegmentStart(): Boolean = !startsNewSegment && segmentStartReason == null

    private fun rawSegmentSpeed(
        previous: RecordedTracePoint,
        current: RecordedTracePoint,
    ): Double? {
        val elapsedMillis = current.timeMillis - previous.timeMillis
        if (elapsedMillis <= 0L) return null
        return haversineMeters(previous.latLong, current.latLong) / (elapsedMillis / 1_000.0)
    }

    private fun point(
        xMeters: Double,
        timeMillis: Long,
        speedMps: Float?,
        startsNewSegment: Boolean = false,
        segmentStartReason: String? = null,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = LatLong(45.0 + xMeters / METERS_PER_DEGREE, 6.0),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = 5f,
            speedMps = speedMps,
            startsNewSegment = startsNewSegment,
            segmentStartReason = segmentStartReason,
        )

    private fun Double.round(decimals: Int): Double {
        val scale = 10.0.pow(decimals)
        return kotlin.math.round(this * scale) / scale
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
