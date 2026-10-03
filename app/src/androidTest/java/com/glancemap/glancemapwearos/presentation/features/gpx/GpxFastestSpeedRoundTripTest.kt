package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingSegmentStartReason
import com.glancemap.glancemapwearos.presentation.features.recording.encodeRecordedTraceAsGpx
import com.glancemap.glancemapwearos.presentation.features.recording.fastestContinuousSegmentSpeedMps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import java.io.File

class GpxFastestSpeedRoundTripTest {
    @Test
    fun visuallyBridgedPausePreservesReasonAndRemainsSpeedBoundaryAfterParsing() {
        val points =
            listOf(
                point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L),
                point(
                    latitude = 45.000045,
                    longitude = 6.0,
                    timeMillis = 4_000L,
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.MANUAL_PAUSE,
                ),
            )
        val parsed = roundTrip(points)

        assertEquals(2, parsed.size)
        assertFalse(parsed[1].startsNewSegment)
        assertEquals(RecordingSegmentStartReason.MANUAL_PAUSE, parsed[1].segmentStartReason)
        assertNull(parsed.fastestContinuousSegmentSpeedMps())
    }

    @Test
    fun importedGeometryUsesTimestampsAndPreservesRawProviderSpeed() {
        val parsed =
            roundTrip(
                listOf(
                    point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 18f),
                    point(latitude = 45.000135, longitude = 6.0, timeMillis = 4_000L, speedMps = 18f),
                    point(latitude = 45.00027, longitude = 6.0, timeMillis = 7_000L, speedMps = 18f),
                ),
            )

        assertEquals(18f, parsed[1].speedMps)
        assertEquals(5.0, parsed.fastestContinuousSegmentSpeedMps() ?: -1.0, 0.2)
    }

    @Test
    fun trueTrackSegmentBoundaryRemainsSpeedBoundaryAfterParsing() {
        val parsed =
            roundTrip(
                listOf(
                    point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L),
                    point(
                        latitude = 46.0,
                        longitude = 6.0,
                        timeMillis = 4_000L,
                        startsNewSegment = true,
                        segmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                    ),
                    point(latitude = 46.000045, longitude = 6.0, timeMillis = 7_000L),
                ),
            )

        assertTrue(parsed[1].startsNewSegment)
        assertEquals(1.7, parsed.fastestContinuousSegmentSpeedMps() ?: -1.0, 0.2)
    }

    private fun roundTrip(points: List<RecordedTracePoint>): List<TrackPoint> {
        val file = File.createTempFile("glancemap-speed", ".gpx")
        return try {
            file.writeBytes(encodeRecordedTraceAsGpx("speed", points))
            parseGpxData(file).points
        } finally {
            file.delete()
        }
    }

    private fun point(
        latitude: Double,
        longitude: Double,
        timeMillis: Long,
        speedMps: Float = 1f,
        startsNewSegment: Boolean = false,
        segmentStartReason: String? = null,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = LatLong(latitude, longitude),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = 5f,
            speedMps = speedMps,
            startsNewSegment = startsNewSegment,
            segmentStartReason = segmentStartReason,
        )
}
