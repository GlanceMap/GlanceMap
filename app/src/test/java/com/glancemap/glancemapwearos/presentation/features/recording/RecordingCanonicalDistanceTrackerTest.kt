package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingCanonicalDistanceTrackerTest {
    /**
     * 2026-11-14 field pattern: a brief out-and-back GPS spike during a steady walk was counted by
     * the old live estimate but removed from the saved track, leaving live distance 5% longer.
     */
    @Test
    fun liveDistanceEqualsTheSavedTrackAtEveryPointAndAfterTheFinalFlush() {
        listOf(
            SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
            SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
        ).forEach { mode ->
            val options = RecordingPointSmoothingOptions(mode, SettingsRepository.ACTIVITY_PROFILE_HIKE, 10)
            val tracker = RecordingCanonicalDistanceTracker()
            var points = emptyList<RecordedTracePoint>()
            walkWithSpike().forEach { incoming ->
                val append = appendCanonicalRecordingPoint(points, incoming, options)
                points = append.points
                val live = tracker.distanceMeters(points, append.firstChangedPointIndex)
                assertEquals("mode=$mode points=${points.size}", recordingCanonicalPathDistance(points), live, 0.001)
            }
            val flushed = flushCanonicalRecordingTail(points, options)
            assertEquals(
                "mode=$mode after flush",
                recordingCanonicalPathDistance(flushed.points),
                tracker.distanceMeters(flushed.points, flushed.firstChangedPointIndex),
                0.001,
            )
        }
    }

    @Test
    fun aRevisionOfAlreadyCountedPointsIsMeasuredAgainNotKeptFromTheCache() {
        val tracker = RecordingCanonicalDistanceTracker()
        val straight = (0..5).map { index -> point(northMeters = index * 10.0, timeMillis = index * 10_000L) }
        assertEquals(50.0, tracker.distanceMeters(straight, unchangedPointCount = straight.size), 0.1)

        // A later revision moves point 2 sideways: the cache must not keep the old length.
        val revised =
            straight.toMutableList().also {
                it[2] = point(northMeters = 20.0, eastMeters = 10.0, timeMillis = 20_000L)
            }
        val expected = recordingCanonicalPathDistance(revised)
        assertTrue(expected > 50.0)
        assertEquals(expected, tracker.distanceMeters(revised, unchangedPointCount = 2), 0.01)
    }

    @Test
    fun segmentStartsCountOnlyTheirStoredGapDistance() {
        val tracker = RecordingCanonicalDistanceTracker()
        val points =
            listOf(
                point(northMeters = 0.0, timeMillis = 0L),
                point(northMeters = 10.0, timeMillis = 10_000L),
                point(northMeters = 500.0, timeMillis = 600_000L).copy(
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.AUTO_PAUSE,
                ),
                point(northMeters = 600.0, timeMillis = 900_000L).copy(
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                    gapDistanceMeters = 75.0,
                ),
            )

        assertEquals(10.0 + 0.0 + 75.0, tracker.distanceMeters(points, unchangedPointCount = points.size), 0.1)
    }

    private fun walkWithSpike(): List<RecordedTracePoint> =
        (0 until 40).map { index ->
            // Steady 1.3 m/s north every 10 s, with one 40 m sideways spike at fix 20.
            val east = if (index == 20) 40.0 else 0.0
            point(northMeters = index * 13.0, eastMeters = east, timeMillis = index * 10_000L, speedMps = 1.3f)
        }

    private fun point(
        northMeters: Double,
        eastMeters: Double = 0.0,
        timeMillis: Long,
        speedMps: Float? = null,
    ) = RecordedTracePoint(
        latLong =
            LatLong(
                45.0 + northMeters / METERS_PER_DEGREE,
                6.0 + eastMeters / (METERS_PER_DEGREE * kotlin.math.cos(Math.toRadians(45.0))),
            ),
        elevationMeters = null,
        timeMillis = timeMillis,
        accuracyMeters = 8f,
        speedMps = speedMps,
    )

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
