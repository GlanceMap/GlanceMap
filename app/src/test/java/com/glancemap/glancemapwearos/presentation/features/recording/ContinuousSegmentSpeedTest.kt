package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.LatLong

class ContinuousSegmentSpeedTest {
    @Test
    fun firstAndLastProviderSpeedSpikesDoNotAffectGeometricMaximum() {
        val points =
            listOf(
                point(xMeters = 0.0, timeMillis = 0L, speedMps = 99f),
                point(xMeters = 6.0, timeMillis = 3_000L, speedMps = 2f),
                point(xMeters = 12.0, timeMillis = 6_000L, speedMps = 2f),
                point(xMeters = 18.0, timeMillis = 9_000L, speedMps = 99f),
            )

        assertEquals(2.0, points.fastestContinuousSegmentSpeedMps() ?: -1.0, 0.05)
    }

    @Test
    fun missingAndInvalidTimingCannotCreateASegment() {
        val samples =
            listOf(
                sample(xMeters = 0.0, timeMillis = null),
                sample(xMeters = 6.0, timeMillis = 3_000L),
                sample(xMeters = 12.0, timeMillis = 3_000L),
                sample(xMeters = 18.0, timeMillis = 2_000L),
            )

        assertNull(calculateFastestContinuousSegmentSpeedMps(samples))
    }

    @Test
    fun explicitSegmentReasonsAreSpeedBoundaries() {
        RecordingSegmentStartReasons.all.forEach { reason ->
            val samples =
                listOf(
                    sample(xMeters = 0.0, timeMillis = 0L),
                    sample(xMeters = 60.0, timeMillis = 3_000L, segmentStartReason = reason),
                    sample(xMeters = 66.0, timeMillis = 6_000L),
                )

            assertEquals(2.0, calculateFastestContinuousSegmentSpeedMps(samples) ?: -1.0, 0.05)
        }
    }

    @Test
    fun irregularAndSparseCadenceUsesActualTimestampDelta() {
        val samples =
            listOf(
                sample(xMeters = 0.0, timeMillis = 0L),
                sample(xMeters = 6.0, timeMillis = 3_000L),
                sample(xMeters = 26.0, timeMillis = 13_000L),
                sample(xMeters = 46.0, timeMillis = 23_000L),
            )

        assertEquals(2.0, calculateFastestContinuousSegmentSpeedMps(samples) ?: -1.0, 0.05)
    }

    @Test
    fun zeroDistanceAndNonFiniteCoordinatesAreIgnored() {
        val samples =
            listOf(
                sample(xMeters = 0.0, timeMillis = 0L),
                sample(xMeters = 0.0, timeMillis = 3_000L),
                sample(xMeters = 0.0, timeMillis = 6_000L),
            )

        assertNull(calculateFastestContinuousSegmentSpeedMps(samples))
    }

    private fun point(
        xMeters: Double,
        timeMillis: Long,
        speedMps: Float,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = latLong(xMeters),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = 5f,
            speedMps = speedMps,
        )

    private fun sample(
        xMeters: Double,
        timeMillis: Long?,
        segmentStartReason: String? = null,
    ): ContinuousSpeedSample =
        ContinuousSpeedSample(
            latLong = latLong(xMeters),
            timeMillis = timeMillis,
            segmentStartReason = segmentStartReason,
        )

    private fun latLong(xMeters: Double): LatLong = LatLong(45.0 + xMeters / METERS_PER_DEGREE, 6.0)

    private object RecordingSegmentStartReasons {
        val all =
            listOf(
                RecordingSegmentStartReason.MANUAL_PAUSE,
                RecordingSegmentStartReason.AUTO_PAUSE,
                RecordingSegmentStartReason.GPS_GAP,
                RecordingSegmentStartReason.SOURCE_RELOCATION,
                RecordingSegmentStartReason.SESSION_RECOVERY,
                "FUTURE_REASON",
            )
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
