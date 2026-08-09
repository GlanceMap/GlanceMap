package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionDayWeatherTest {
    @Test
    fun `samples the exact GPX day start midpoint and finish`() {
        val details = routeDetails()
        val day =
            MissionPlanDay(
                id = "day-2",
                dayNumber = 2,
                routeId = details.route.id,
                startDistanceMeters = 1_000.0,
                endDistanceMeters = 3_000.0,
            )

        val targets = details.missionDayWeatherTargets(day)

        assertEquals(
            listOf(
                MissionDayWeatherSamplePosition.START,
                MissionDayWeatherSamplePosition.MIDPOINT,
                MissionDayWeatherSamplePosition.FINISH,
            ),
            targets.map { target -> target.position },
        )
        assertEquals(0.0, targets[0].distanceFromDayStartMeters, 0.1)
        assertEquals(1_000.0, targets[1].distanceFromDayStartMeters, 0.1)
        assertEquals(2_000.0, targets[2].distanceFromDayStartMeters, 0.1)
        assertEquals("Day 2 start", targets[0].location.label)
        assertEquals("Day 2 midpoint", targets[1].location.label)
        assertEquals("Day 2 finish", targets[2].location.label)
    }

    @Test
    fun `uses only locations from the selected mission day route`() {
        val targets =
            routeDetails()
                .missionDayWeatherTargets(
                    MissionPlanDay(
                        id = "day",
                        dayNumber = 1,
                        routeId = "route",
                        startDistanceMeters = 0.0,
                        endDistanceMeters = 2_000.0,
                    ),
                )

        assertTrue(targets.all { target -> target.location.latitude == 46.0 })
        assertTrue(targets.all { target -> target.location.longitude in 11.0..11.04 })
    }

    private fun routeDetails(): RouteLibraryRouteDetails {
        val profile =
            buildTrailRouteProfile(
                points =
                    (0..4).map { index ->
                        TrailPoint(
                            location = GeoPoint(latitude = 46.0, longitude = 11.0 + index * 0.01),
                            elevationMeters = 1_000.0 + index * 100.0,
                        )
                    },
            )
        return RouteLibraryRouteDetails(
            route =
                RouteLibraryRoute(
                    id = "route",
                    title = "Dolomites Loop",
                    storedFileName = "dolomites.gpx",
                    importedAtMillis = 1L,
                    summary =
                        RouteLibrarySummary(
                            distanceMeters = profile.totalDistanceMeters,
                            elevationGainMeters = profile.totalAscentMeters,
                            elevationLossMeters = profile.totalDescentMeters,
                            estimatedDurationSeconds = profile.estimatedDurationSeconds,
                            waypointCount = 0,
                            firstThirtyMinutesDistanceMeters = 0.0,
                            firstThirtyMinutesAscentMeters = 0.0,
                        ),
                ),
            profile = profile,
            waypoints = emptyList(),
        )
    }
}
