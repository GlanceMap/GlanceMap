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
        assertEquals(listOf(2, 2), overlays.first().segments.map { it.points.size })
        assertEquals(3, overlays.flatMap(PhoneMapGpxOverlay::segments).size)
        assertTrue(listOf(first, second).enabledOverlays(globalVisible = false).isEmpty())
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
}
