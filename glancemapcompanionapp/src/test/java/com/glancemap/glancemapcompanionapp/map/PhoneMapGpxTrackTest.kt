package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.recording.RecordingPoint
import com.glancemap.trailcore.recording.RecordingSessionLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapGpxTrackTest {
    @Test
    fun normalTrackBecomesOneRenderedSegment() {
        val segments = track(point(11.0), point(11.1)).toRouteSegments()

        assertEquals(1, segments.size)
        assertEquals(listOf(11.0, 11.1), segments.single().points.map(GeoPoint::longitude))
    }

    @Test
    fun segmentBoundaryCreatesASeparateGrayBridgeSegment() {
        val segments =
            track(
                point(11.0),
                point(11.1),
                point(12.0, startsNewSegment = true),
                point(12.1),
            ).toRouteSegments()

        assertEquals(
            listOf(listOf(11.0, 11.1), listOf(11.1, 12.0), listOf(12.0, 12.1)),
            segments.map { segment -> segment.points.map(GeoPoint::longitude) },
        )
        assertTrue(segments[1].isGap)
    }

    @Test
    fun emptyAndInsufficientGeometryProduceNoOverlay() {
        assertTrue(track().toRouteSegments().isEmpty())
        assertTrue(track(point(11.0)).toRouteSegments().isEmpty())
        val gap = track(point(11.0), point(12.0, startsNewSegment = true)).toRouteSegments()
        assertEquals(1, gap.size)
        assertTrue(gap.single().isGap)
    }

    @Test
    fun itemVisibilityIsIndependentAndTheGlobalToggleDoesNotEraseIt() {
        val first =
            PhoneMapGpxItem(
                "first",
                "First",
                trackWithId("first", point(11.0), point(11.1)),
                enabled = true,
            )
        val second =
            PhoneMapGpxItem(
                "second",
                "Second",
                trackWithId("second", point(12.0), point(12.1)),
                enabled = false,
            )

        val withBothEnabled = listOf(first, second).toggleEnabled("second")
        val renderedSegments =
            withBothEnabled.enabledOverlays(globalVisible = true).flatMap(PhoneMapGpxOverlay::segments)

        assertEquals(2, renderedSegments.size)
        assertTrue(withBothEnabled.enabledOverlays(globalVisible = false).isEmpty())
        assertEquals(2, withBothEnabled.count(PhoneMapGpxItem::enabled))
    }

    @Test
    fun enabledOverlaysKeepTrackIdsAndSeparateSegmentsForEachTrack() {
        val first =
            PhoneMapGpxItem(
                "first",
                "First",
                trackWithId(
                    "first",
                    point(11.0),
                    point(11.05),
                    point(11.1, startsNewSegment = true),
                    point(11.2),
                ),
                enabled = true,
            )
        val second =
            PhoneMapGpxItem(
                "second",
                "Second",
                trackWithId("second", point(12.0), point(12.1)),
                enabled = true,
            )

        val overlays = listOf(first, second).enabledOverlays(globalVisible = true)

        assertEquals(listOf("first", "second"), overlays.map(PhoneMapGpxOverlay::id))
        assertEquals(listOf(2, 2, 2), overlays.first().segments.map { it.points.size })
        assertEquals(4, overlays.flatMap(PhoneMapGpxOverlay::segments).size)
        assertTrue(listOf(first, second).enabledOverlays(globalVisible = false).isEmpty())
    }

    @Test
    fun activeRecordingOverlayReusesPointsAndKeepsPauseSegmentsSeparate() {
        val recordingPoints =
            listOf(
                recordingPoint(11.0),
                recordingPoint(11.1),
                recordingPoint(12.0, startsNewSegment = true),
                recordingPoint(12.1),
            )
        val state =
            PhoneRecordingUiState(
                session = RecordingSessionLifecycle().start(1_000L),
                points = recordingPoints,
                pointsRevision = 4L,
                segmentStartIndices = listOf(2),
            )

        val overlay = requireNotNull(phoneRecordingOverlay(state, "Live recording"))

        assertEquals(3, overlay.segments.size)
        assertSame(recordingPoints[1].location, overlay.segments[0].points[1])
        assertTrue(overlay.segments[1].isGap)
        assertSame(recordingPoints[2].location, overlay.segments[2].points[0])
        assertEquals(4L, overlay.renderRevision)
        assertNotEquals(overlay, phoneRecordingOverlay(state.copy(pointsRevision = 5L), "Live recording"))
    }

    @Test
    fun savedRecordingActivitiesRespectTheirVisibilityPreference() {
        val recorded =
            PhoneMapGpxItem(
                id = "recording-id",
                displayName = "Recorded activity",
                track = trackWithId("recording-id", point(11.0), point(11.1)),
                enabled = true,
                isRecording = true,
            )

        assertTrue(listOf(recorded).enabledOverlays(globalVisible = true, showSavedActivities = false).isEmpty())
        assertEquals(1, listOf(recorded).enabledOverlays(globalVisible = true, showSavedActivities = true).size)
    }

    @Test
    fun skippedSingletonSegmentsKeepTheirOriginalRecordingOffsets() {
        val state =
            PhoneRecordingUiState(
                session = RecordingSessionLifecycle().start(1_000L),
                points = listOf(recordingPoint(10.0), recordingPoint(11.0), recordingPoint(12.0), recordingPoint(12.1)),
                segmentStartIndices = listOf(1, 2),
                pointsChangedFromIndex = 3,
            )
        val overlay = requireNotNull(phoneRecordingOverlay(state, "REC"))
        assertEquals(3, overlay.segments.size)
        assertEquals(2, overlay.segments.last().recordingStartIndex)
        assertEquals(
            listOf(12.0, 12.1),
            overlay.segments
                .last()
                .points
                .map(GeoPoint::longitude),
        )
    }

    private fun track(vararg points: TrailPoint): PhoneMapGpxTrack = trackWithId("test", *points)

    private fun trackWithId(
        id: String,
        vararg points: TrailPoint,
    ): PhoneMapGpxTrack =
        PhoneMapGpxTrack(
            id = id,
            points = points.toList(),
        )

    private fun point(
        longitude: Double,
        startsNewSegment: Boolean = false,
    ): TrailPoint =
        TrailPoint(
            location = GeoPoint(latitude = 46.0, longitude = longitude),
            startsNewSegment = startsNewSegment,
        )

    private fun recordingPoint(
        longitude: Double,
        startsNewSegment: Boolean = false,
    ): RecordingPoint =
        RecordingPoint(
            location = GeoPoint(latitude = 46.0, longitude = longitude),
            elevationMeters = null,
            timeMillis = 1_000L,
            accuracyMeters = 4f,
            speedMps = null,
            startsNewSegment = startsNewSegment,
        )
}
