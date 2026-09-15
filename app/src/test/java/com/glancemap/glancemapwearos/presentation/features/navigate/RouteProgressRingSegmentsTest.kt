package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.buildCumulativeDistances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RouteProgressRingSegmentsTest {
    @Test
    fun mapsMixedTerrainByRouteDistance() {
        val points =
            listOf(
                point(0.0, 100.0),
                point(0.001, 110.0),
                point(0.003, 100.0),
                point(0.004, 100.0),
                point(0.005, 100.0),
            )
        val cumulative = buildCumulativeDistances(points.map { it.latLong })

        val segments =
            buildRouteProgressRingSegments(
                points = points,
                cumulativeDistancesMeters = cumulative,
                totalDistanceMeters = cumulative.last(),
            )

        assertEquals(3, segments.size)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.CLIMB), segments[0].color)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.DOWNHILL), segments[1].color)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.FLAT), segments[2].color)
        assertEquals((cumulative[1] / cumulative.last()).toFloat(), segments[0].endFraction)
        assertEquals((cumulative[2] / cumulative.last()).toFloat(), segments[1].endFraction)
        assertEquals((cumulative[4] / cumulative.last()).toFloat(), segments[2].endFraction)
    }

    @Test
    fun clipsUpcomingSegmentsWithoutChangingTheirColors() {
        val segments =
            listOf(
                RouteProgressRingSegment(0f, 0.25f, 1),
                RouteProgressRingSegment(0.25f, 0.75f, 2),
                RouteProgressRingSegment(0.75f, 1f, 3),
            )

        assertEquals(
            listOf(
                RouteProgressRingSegment(0.5f, 0.75f, 2),
                RouteProgressRingSegment(0.75f, 1f, 3),
            ),
            clipUpcomingRouteProgressRingSegments(segments, progress = 0.5f),
        )
    }

    @Test
    fun missingElevationFallsBackToGreenRing() {
        val points = listOf(point(0.0, 100.0), point(0.001, null))
        val cumulative = buildCumulativeDistances(points.map { it.latLong })

        assertTrue(
            buildRouteProgressRingSegments(
                points = points,
                cumulativeDistancesMeters = cumulative,
                totalDistanceMeters = cumulative.last(),
            ).isEmpty(),
        )
    }

    @Test
    fun keepsGpxSegmentBoundariesOutOfGradeClassification() {
        val points =
            listOf(
                point(0.0, 100.0),
                point(0.001, 110.0),
                point(0.002, 120.0, startsNewSegment = true),
                point(0.003, 100.0),
            )
        val cumulative = buildCumulativeDistances(points.map { it.latLong })

        val segments =
            buildRouteProgressRingSegments(
                points = points,
                cumulativeDistancesMeters = cumulative,
                totalDistanceMeters = cumulative.last(),
            )

        assertEquals(ROUTE_PROGRESS_RING_FALLBACK_GREEN, segments[1].color)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.DESCENT), segments[2].color)
    }

    @Test
    fun reversesGradeDirectionAndReadsReversedBoundary() {
        val points =
            listOf(
                point(0.003, 100.0),
                point(0.002, 110.0, startsNewSegment = true),
                point(0.001, 120.0),
                point(0.0, 110.0),
            )
        val cumulative = buildCumulativeDistances(points.map { it.latLong })

        val segments =
            buildRouteProgressRingSegments(
                points = points,
                cumulativeDistancesMeters = cumulative,
                totalDistanceMeters = cumulative.last(),
                reversed = true,
            )

        assertEquals(elevationSegmentColor(GpxElevationSegmentType.CLIMB), segments[0].color)
        assertEquals(ROUTE_PROGRESS_RING_FALLBACK_GREEN, segments[1].color)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.DESCENT), segments[2].color)
    }

    private fun point(
        latitude: Double,
        elevation: Double?,
        startsNewSegment: Boolean = false,
    ) = TrackPoint(
        latLong = LatLong(latitude, 6.0),
        elevation = elevation,
        startsNewSegment = startsNewSegment,
    )
}
