package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.gpx.FileSig
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.buildProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.buildTurnByTurnGuidanceSessionFromProfile
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.buildCumulativeDistances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RouteProgressRingSegmentsTest {
    @Test
    fun keepsTerrainPlacementProportionalToRouteDistance() {
        val segments =
            buildSegments(
                distances = listOf(0.0, 200.0, 800.0),
                cumulativeAscent = listOf(0.0, 8.0, 8.0),
                cumulativeDescent = listOf(0.0, 0.0, 18.0),
            )

        assertEquals(2, segments.size)
        assertEquals(0.25f, segments[0].endFraction)
        assertEquals(1f, segments[1].endFraction)
    }

    @Test
    fun adjacentSameColorSectionsAreMerged() {
        val segments =
            buildSegments(
                distances = listOf(0.0, 60.0, 120.0, 180.0),
                cumulativeAscent = listOf(0.0, 6.0, 12.0, 18.0),
            )

        assertEquals(
            listOf(RouteProgressRingSegment(0f, 1f, elevationSegmentColor(GpxElevationSegmentType.CLIMB))),
            segments,
        )
    }

    @Test
    fun suppressesShortNonMeaningfulOppositeDirectionBlip() {
        val segments =
            buildSegments(
                distances = listOf(0.0, 120.0, 150.0, 270.0),
                cumulativeAscent = listOf(0.0, 0.0, 1.5, 1.5),
                cumulativeDescent = listOf(0.0, 6.0, 6.0, 12.0),
            )

        assertEquals(1, segments.size)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.DOWNHILL), segments.single().color)
    }

    @Test
    fun preservesMeaningfulShortSteepClimbBetweenLongerSections() {
        val segments =
            buildSegments(
                distances = listOf(0.0, 120.0, 150.0, 270.0),
                cumulativeAscent = listOf(0.0, 0.0, 4.0, 4.0),
                cumulativeDescent = listOf(0.0, 6.0, 6.0, 12.0),
            )

        assertEquals(3, segments.size)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.DOWNHILL), segments[0].color)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.CLIMB), segments[1].color)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.DOWNHILL), segments[2].color)
    }

    @Test
    fun missingCanonicalElevationFallsBackToGreenRing() {
        val points = listOf(point(0.0), point(0.001))
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
    fun keepsGpxSegmentBoundarySeparateFromTerrainAggregation() {
        val segments =
            buildSegments(
                distances = listOf(0.0, 120.0, 240.0),
                cumulativeAscent = listOf(0.0, 12.0, 24.0),
                startsNewSegments = setOf(1),
            )

        assertEquals(2, segments.size)
        assertEquals(ROUTE_PROGRESS_RING_FALLBACK_GREEN, segments[0].color)
        assertEquals(elevationSegmentColor(GpxElevationSegmentType.CLIMB), segments[1].color)
    }

    @Test
    fun reversedGuidanceUsesTerrainInTravelDirection() {
        val forwardPoints =
            listOf(
                point(0.0, elevation = 100.0),
                point(120.0, elevation = 100.0),
                point(240.0, elevation = 112.0),
            )
        val profile = buildProfile(FileSig(0L, 1L), forwardPoints)
        val reversedSession =
            buildTurnByTurnGuidanceSessionFromProfile(
                trackId = "reverse.gpx",
                trackTitle = "Reverse",
                profile = profile,
                startReached = true,
                reversed = true,
            )

        val segments = buildRouteProgressRingSegments(reversedSession)

        assertEquals(elevationSegmentColor(GpxElevationSegmentType.DESCENT), segments.first().color)
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

    private fun buildSegments(
        distances: List<Double>,
        cumulativeAscent: List<Double>,
        cumulativeDescent: List<Double> = List(distances.size) { 0.0 },
        startsNewSegments: Set<Int> = emptySet(),
    ): List<RouteProgressRingSegment> {
        val points =
            distances.mapIndexed { index, distance ->
                point(
                    latitude = distance,
                    startsNewSegment = index in startsNewSegments,
                )
            }
        return buildRouteProgressRingSegments(
            points = points,
            cumulativeDistancesMeters = distances,
            totalDistanceMeters = distances.last(),
            cumulativeAscentMeters = cumulativeAscent,
            cumulativeDescentMeters = cumulativeDescent,
        )
    }

    private fun point(
        latitude: Double,
        elevation: Double? = 100.0,
        startsNewSegment: Boolean = false,
    ) = TrackPoint(
        latLong = LatLong(0.0, latitude / 111_320.0),
        elevation = elevation,
        startsNewSegment = startsNewSegment,
    )
}
