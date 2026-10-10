package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingTraceGeometryTest {
    @Test
    fun incrementalGeometryMatchesCanonicalSegmentationAndPauseFlushes() {
        listOf(
            SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
            SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
            SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
        ).forEach { mode ->
            val options = RecordingPointSmoothingOptions(mode, SettingsRepository.ACTIVITY_PROFILE_HIKE)
            var state = TraceRecordingUiState(startedAtMillis = 1_000L)
            var geometry: RecordingTraceGeometry? = null
            repeat(220) { index ->
                val incoming =
                    point(index).copy(
                        startsNewSegment = index == 80 || index == 150,
                        segmentStartReason =
                            if (index == 80) {
                                RecordingSegmentStartReason.MANUAL_PAUSE
                            } else {
                                RecordingSegmentStartReason.GPS_GAP
                            },
                    )
                val append = appendCanonicalRecordingPoint(state.points, incoming, options)
                assertEquals(
                    state.points.take(append.firstChangedPointIndex),
                    append.points.take(append.firstChangedPointIndex),
                )
                state =
                    state.copy(
                        points = append.points,
                        pointsRevision = state.pointsRevision + 1,
                        pointsChangedFromIndex = append.firstChangedPointIndex,
                    )
                geometry = prepareRecordingTraceGeometry(state, geometry)
                assertCanonicalGeometry(state, geometry)
            }
            val flushed = flushCanonicalRecordingTail(state.points, options)
            state =
                state.copy(
                    points = flushed.points,
                    pointsRevision = state.pointsRevision + 1,
                    pointsChangedFromIndex = flushed.firstChangedPointIndex,
                )
            geometry = prepareRecordingTraceGeometry(state, geometry)
            assertCanonicalGeometry(state, geometry)
        }
    }

    @Test
    fun appendToLargeHistoryReadsOnlyTheChangedTail() {
        val initialPoints = List(20_000, ::point)
        val initial = TraceRecordingUiState(points = initialPoints, startedAtMillis = 1_000L, pointsRevision = 10)
        val before = prepareRecordingTraceGeometry(initial, null)
        val nextPoints = CountingPoints(initialPoints + point(initialPoints.size))
        val next = initial.copy(points = nextPoints, pointsRevision = 11, pointsChangedFromIndex = initialPoints.size)
        val geometry = prepareRecordingTraceGeometry(next, before)
        assertTrue("whole history was read: ${nextPoints.reads}", nextPoints.reads <= 2)
        assertSame(nextPoints, geometry.points)
        assertEquals(listOf(0), geometry.segmentStarts)
        assertEquals(nextPoints.size, geometry.segments.single().size)
    }

    @Test
    fun missedUpdatesAndNewSessionsRebuildChangedEarlierBoundaries() {
        val points = List(12, ::point)
        val initial = TraceRecordingUiState(points = points, startedAtMillis = 1_000L, pointsRevision = 2)
        val before = prepareRecordingTraceGeometry(initial, null)
        val changed = points.mapIndexed { index, point -> point.copy(startsNewSegment = index == 2) }
        val missed = initial.copy(points = changed, pointsRevision = 5, pointsChangedFromIndex = 10)
        assertCanonicalGeometry(missed, prepareRecordingTraceGeometry(missed, before))
        val newSession = missed.copy(startedAtMillis = 2_000L, pointsRevision = 3)
        assertCanonicalGeometry(newSession, prepareRecordingTraceGeometry(newSession, before))
    }

    @Test
    fun revisingPauseEndpointsRecomputesTheBridgeAndFollowingBoundary() {
        val points = List(12, ::point).toMutableList()
        points[6] =
            points[6].copy(startsNewSegment = true, segmentStartReason = RecordingSegmentStartReason.MANUAL_PAUSE)
        val initial = TraceRecordingUiState(points = points.toList(), startedAtMillis = 1_000L, pointsRevision = 1)
        val before = prepareRecordingTraceGeometry(initial, null)
        assertEquals(listOf(0), before.segmentStarts)
        points[5] = points[5].copy(latLong = LatLong(46.0, 6.0))
        val revised = initial.copy(points = points.toList(), pointsRevision = 2, pointsChangedFromIndex = 5)
        val after = prepareRecordingTraceGeometry(revised, before)
        assertCanonicalGeometry(revised, after)
        assertEquals(listOf(0, 6), after.segmentStarts)
    }

    @Test
    fun emptyRestoreShrinkAndInvalidRevisionHintsAreSafe() {
        val initial =
            TraceRecordingUiState(points = List(12, ::point), startedAtMillis = 1_000L, pointsRevision = 2)
        val before = prepareRecordingTraceGeometry(initial, null)
        listOf(
            initial.copy(points = emptyList(), pointsRevision = 3),
            initial.copy(points = initial.points.take(3), pointsRevision = 3, pointsChangedFromIndex = 12),
            initial.copy(pointsRevision = 0),
            initial.copy(pointsRevision = 3, pointsChangedFromIndex = -1),
        ).forEach { changed -> assertCanonicalGeometry(changed, prepareRecordingTraceGeometry(changed, before)) }
    }

    @Test
    fun publishingANewSnapshotDoesNotMutateThePreviousDrawingViews() {
        val initial = TraceRecordingUiState(points = List(8, ::point), startedAtMillis = 1_000L, pointsRevision = 1)
        val before = prepareRecordingTraceGeometry(initial, null)
        val expected = before.segments.map { it.toList() }
        val revised =
            initial.copy(
                points = initial.points.dropLast(3) + List(3) { point(50 + it) },
                pointsRevision = 2,
                pointsChangedFromIndex = 5,
            )
        prepareRecordingTraceGeometry(revised, before)
        assertEquals(expected, before.segments)
        assertSame(before, prepareRecordingTraceGeometry(initial, before))
    }

    private fun assertCanonicalGeometry(
        state: TraceRecordingUiState,
        geometry: RecordingTraceGeometry,
    ) {
        val expected = recordedTraceSegments(state.points).map { segment -> segment.map { it.latLong } }
        assertEquals(expected, geometry.segments)
        listOf(false, true).forEach { following ->
            val expectedRender = recordingTraceRenderState(expected, following)
            val actualRender = recordingTraceRenderState(geometry.segments, following)
            assertEquals(expectedRender, actualRender)
        }
    }

    private fun point(index: Int) =
        RecordedTracePoint(
            latLong = LatLong(45.0 + index * 0.00002, 6.0),
            elevationMeters = null,
            timeMillis = index * 3_000L,
            accuracyMeters = 8f,
            speedMps = 2f,
        )

    private class CountingPoints(
        private val source: List<RecordedTracePoint>,
    ) : AbstractList<RecordedTracePoint>() {
        var reads = 0
        override val size: Int = source.size

        override fun get(index: Int): RecordedTracePoint {
            reads += 1
            return source[index]
        }
    }
}
