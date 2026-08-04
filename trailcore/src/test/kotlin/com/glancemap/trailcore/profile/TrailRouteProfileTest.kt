package com.glancemap.trailcore.profile

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailRouteProfileTest {
    @Test
    fun profileAccumulatesDistanceElevationAndPlanningDuration() {
        val profile =
            buildTrailRouteProfile(
                points =
                    listOf(
                        point(longitude = 0.0, elevation = 100.0),
                        point(longitude = 0.001, elevation = 150.0),
                        point(longitude = 0.002, elevation = 120.0),
                    ),
                pacing =
                    TrailPacingConfig(
                        flatSpeedMetersPerSecond = 1.0,
                        uphillVerticalMetersPerHour = 3_600.0,
                    ),
            )

        assertEquals(50.0, profile.totalAscentMeters, 0.01)
        assertEquals(30.0, profile.totalDescentMeters, 0.01)
        assertTrue(profile.totalDistanceMeters in 220.0..225.0)
        assertTrue(profile.estimatedDurationSeconds in 270.0..275.0)
    }

    @Test
    fun routeWindowStopsAtFinishAndReportsOnlyTheWindowClimb() {
        val profile =
            buildTrailRouteProfile(
                points =
                    listOf(
                        point(longitude = 0.0, elevation = 100.0),
                        point(longitude = 0.001, elevation = 150.0),
                        point(longitude = 0.002, elevation = 120.0),
                    ),
                pacing =
                    TrailPacingConfig(
                        flatSpeedMetersPerSecond = 1.0,
                        uphillVerticalMetersPerHour = 3_600.0,
                    ),
            )

        val window = profile.windowFromDistance(startDistanceMeters = 0.0, maximumDurationSeconds = 180.0)

        assertTrue(window.distanceMeters in 125.0..135.0)
        assertEquals(50.0, window.ascentMeters, 0.01)
        assertTrue(window.descentMeters in 5.0..5.2)

        val finishWindow =
            profile.windowFromDistance(
                startDistanceMeters = profile.totalDistanceMeters - 5.0,
                maximumDurationSeconds = 1_800.0,
            )
        assertEquals(5.0, finishWindow.distanceMeters, 0.1)
        assertTrue(finishWindow.estimatedDurationSeconds < 10.0)
    }

    @Test
    fun segmentBoundariesDoNotCreateArtificialDistanceOrElevation() {
        val profile =
            buildTrailRouteProfile(
                points =
                    listOf(
                        point(longitude = 0.0, elevation = 100.0),
                        TrailPoint(
                            location = GeoPoint(latitude = 10.0, longitude = 10.0),
                            elevationMeters = 2_000.0,
                            startsNewSegment = true,
                        ),
                    ),
            )

        assertEquals(0.0, profile.totalDistanceMeters, 0.0)
        assertEquals(0.0, profile.totalAscentMeters, 0.0)
        assertEquals(0.0, profile.estimatedDurationSeconds, 0.0)
    }

    private fun point(
        longitude: Double,
        elevation: Double,
    ): TrailPoint =
        TrailPoint(
            location = GeoPoint(latitude = 0.0, longitude = longitude),
            elevationMeters = elevation,
        )
}
