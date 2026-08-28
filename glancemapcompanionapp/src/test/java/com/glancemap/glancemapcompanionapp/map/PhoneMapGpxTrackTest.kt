package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
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
    fun segmentBoundaryCreatesSeparateRenderedSegmentsWithoutABridge() {
        val segments =
            track(
                point(11.0),
                point(11.1),
                point(12.0, startsNewSegment = true),
                point(12.1),
            ).toRouteSegments()

        assertEquals(
            listOf(listOf(11.0, 11.1), listOf(12.0, 12.1)),
            segments.map { segment -> segment.points.map(GeoPoint::longitude) },
        )
    }

    @Test
    fun emptyAndInsufficientGeometryProduceNoOverlay() {
        assertTrue(track().toRouteSegments().isEmpty())
        assertTrue(track(point(11.0)).toRouteSegments().isEmpty())
        assertTrue(track(point(11.0), point(12.0, startsNewSegment = true)).toRouteSegments().isEmpty())
    }

    private fun track(vararg points: TrailPoint): PhoneMapGpxTrack =
        PhoneMapGpxTrack(
            id = "test",
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
}
