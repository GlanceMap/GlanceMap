package com.glancemap.trailcore.recording

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousSegmentSpeedTest {
    @Test
    fun sparseCornerCuttingSegmentFarAboveDopplerIsNotTheFastestSpeed() {
        // Phone bike ride in HIKE mode: 119 m in 9 s while the provider reported ~5 m/s.
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 5.04f),
                point(xMeters = 119.2, timeMillis = 9_000L, speedMps = 5.36f),
                point(xMeters = 136.9, timeMillis = 13_000L, speedMps = 6.21f),
            )

        assertEquals(17.7 / 4.0, points.fastestRecordingPointSegmentSpeedMps() ?: -1.0, 0.05)
    }

    @Test
    fun oneHertzJitterStepAboveDopplerIsNotTheFastestSpeed() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 1.42f),
                point(xMeters = 2.3, timeMillis = 1_000L, speedMps = 1.42f),
                point(xMeters = 5.3, timeMillis = 2_000L, speedMps = 1.49f),
            )

        assertEquals(2.3, points.fastestRecordingPointSegmentSpeedMps() ?: -1.0, 0.05)
    }

    @Test
    fun geometricSpeedWithinDopplerToleranceIsKept() {
        // Fused Doppler under-reports and speed can peak mid-segment; up to ratio * max + margin is real.
        val boundMps = 8.0 * FASTEST_SPEED_DOPPLER_GUARD_RATIO + FASTEST_SPEED_DOPPLER_GUARD_MARGIN_MPS
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 6f),
                point(xMeters = boundMps * 3.0 * 0.98, timeMillis = 3_000L, speedMps = 8f),
            )

        assertEquals(boundMps * 0.98, points.fastestRecordingPointSegmentSpeedMps() ?: -1.0, 0.05)
    }

    @Test
    fun segmentsWithoutDopplerKeepTheGeometricSpeed() {
        val samples =
            listOf(
                sample(xMeters = 0.0, timeMillis = 0L, speedMps = null),
                sample(xMeters = 40.0, timeMillis = 4_000L, speedMps = null),
            )

        assertEquals(10.0, calculateFastestContinuousSegmentSpeedMps(samples) ?: -1.0, 0.05)
    }

    @Test
    fun oneEndpointDopplerIsEnoughToBoundTheSegment() {
        // 37 m in 3 s right after a stop, provider 7.04 m/s at the end only.
        val samples =
            listOf(
                sample(xMeters = 0.0, timeMillis = 0L, speedMps = null),
                sample(xMeters = 37.0, timeMillis = 3_000L, speedMps = 7.04f),
                sample(xMeters = 64.0, timeMillis = 6_000L, speedMps = 9.0f),
            )

        assertEquals(9.0, calculateFastestContinuousSegmentSpeedMps(samples) ?: -1.0, 0.05)
    }

    private fun point(
        xMeters: Double,
        timeMillis: Long,
        speedMps: Float?,
    ): RecordingPoint =
        RecordingPoint(
            location = location(xMeters),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = 5f,
            speedMps = speedMps,
        )

    private fun sample(
        xMeters: Double,
        timeMillis: Long,
        speedMps: Float?,
    ): RecordingContinuousSpeedSample =
        RecordingContinuousSpeedSample(
            location = location(xMeters),
            timeMillis = timeMillis,
            speedMps = speedMps,
        )

    private fun location(xMeters: Double): GeoPoint = GeoPoint(45.0 + xMeters / METERS_PER_DEGREE, 6.0)

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
