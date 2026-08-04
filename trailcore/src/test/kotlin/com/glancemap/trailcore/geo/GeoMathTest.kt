package com.glancemap.trailcore.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun distanceAndBearingUseExpectedCardinalDirection() {
        val start = GeoPoint(latitude = 45.0, longitude = 6.0)
        val east = GeoPoint(latitude = 45.0, longitude = 6.001)

        assertEquals(78.6, haversineDistanceMeters(start, east), 1.0)
        assertEquals(90.0, initialBearingDegrees(start, east), 0.1)
    }

    @Test
    fun projectionTracksPositionAlongStraightRoute() {
        val route =
            listOf(
                GeoPoint(latitude = 45.0, longitude = 6.0),
                GeoPoint(latitude = 45.0, longitude = 6.002),
            )

        val projection =
            projectPointOntoRoute(
                points = route,
                point = GeoPoint(latitude = 45.0, longitude = 6.001),
            )

        assertNotNull(projection)
        assertEquals(0, projection?.segmentIndex)
        assertEquals(0.5, projection?.segmentFraction ?: 0.0, 0.05)
    }

    @Test
    fun projectionPrefersContinuousProgressAtCrossing() {
        val route =
            listOf(
                GeoPoint(45.0000, 6.0000),
                GeoPoint(45.0010, 6.0010),
                GeoPoint(45.0000, 6.0020),
                GeoPoint(44.9990, 6.0010),
                GeoPoint(45.0000, 6.0000),
                GeoPoint(45.0010, 5.9990),
            )
        val cumulative = buildCumulativeDistancesMeters(route)

        val projection =
            projectPointOntoRoute(
                points = route,
                cumulativeDistancesMeters = cumulative,
                point = GeoPoint(45.0000, 6.0000),
                previousDistanceFromStartMeters = cumulative[3],
            )

        assertNotNull(projection)
        assertTrue((projection?.distanceFromStartMeters ?: 0.0) > cumulative[2])
    }
}
