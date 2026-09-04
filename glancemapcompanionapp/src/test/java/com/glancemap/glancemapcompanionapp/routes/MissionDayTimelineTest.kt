package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPacingConfig
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionDayTimelineTest {
    @Test
    fun `timeline orders GPX climb and waypoints inside the selected day`() {
        val timeline = routeDetails().missionDayTimeline(day(startMeters = 200.0, endMeters = 3_200.0))

        assertEquals(
            listOf(
                MissionDayTimelineEventType.START,
                MissionDayTimelineEventType.CLIMB,
                MissionDayTimelineEventType.WAYPOINT,
                MissionDayTimelineEventType.WAYPOINT,
                MissionDayTimelineEventType.FINISH,
            ),
            timeline.events.map(MissionDayTimelineEvent::type),
        )
        val waypointTitles =
            timeline.events
                .filter { it.type == MissionDayTimelineEventType.WAYPOINT }
                .map { it.title }
        assertEquals(listOf("Water source", "Panorama"), waypointTitles)
        assertTrue(timeline.events.first { it.type == MissionDayTimelineEventType.CLIMB }.ascentMeters!! >= 100.0)
        assertTrue(timeline.events.last().distanceFromDayStartMeters in 2_900.0..3_100.0)
    }

    @Test
    fun `timeline does not claim a climb when GPX elevation is unavailable`() {
        val details = routeDetails(elevations = listOf(null, null, null, null, null))

        val timeline = details.missionDayTimeline(day(startMeters = 0.0, endMeters = 3_200.0))

        assertFalse(timeline.events.any { it.type == MissionDayTimelineEventType.CLIMB })
        assertEquals(MissionDayTimelineEventType.START, timeline.events.first().type)
        assertEquals(MissionDayTimelineEventType.FINISH, timeline.events.last().type)
    }

    private fun routeDetails(
        elevations: List<Double?> = listOf(100.0, 150.0, 225.0, 225.0, 175.0),
    ): RouteLibraryRouteDetails {
        val profile =
            buildTrailRouteProfile(
                points =
                    elevations.mapIndexed { index, elevation ->
                        TrailPoint(
                            location = GeoPoint(latitude = 0.0, longitude = index * 0.01),
                            elevationMeters = elevation,
                        )
                    },
                pacing = TrailPacingConfig(flatSpeedMetersPerSecond = 1.0, uphillVerticalMetersPerHour = 3_600.0),
            )
        return RouteLibraryRouteDetails(
            route =
                RouteLibraryRoute(
                    id = "route",
                    displayName = "Dolomites Loop",
                    storedFileName = "dolomites.gpx",
                    importedAtMillis = 1L,
                    summary =
                        RouteLibrarySummary(
                            distanceMeters = profile.totalDistanceMeters,
                            elevationGainMeters = profile.totalAscentMeters,
                            elevationLossMeters = profile.totalDescentMeters,
                            estimatedDurationSeconds = profile.estimatedDurationSeconds,
                            waypointCount = 4,
                            firstThirtyMinutesDistanceMeters = 0.0,
                            firstThirtyMinutesAscentMeters = 0.0,
                        ),
                ),
            profile = profile,
            waypoints =
                listOf(
                    RouteLibraryWaypoint("Before day", null, 100.0),
                    RouteLibraryWaypoint("Water source", "GPX note: refill", 1_200.0),
                    RouteLibraryWaypoint("Panorama", null, 2_600.0),
                    RouteLibraryWaypoint("After day", null, 3_800.0),
                ),
        )
    }

    private fun day(
        startMeters: Double,
        endMeters: Double,
    ) = MissionPlanDay(
        id = "day",
        dayNumber = 1,
        routeId = "route",
        startDistanceMeters = startMeters,
        endDistanceMeters = endMeters,
    )
}
