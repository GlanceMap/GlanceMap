package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPacingConfig
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailIntelligenceTest {
    @Test
    fun `uses the live route distance to forecast the upcoming window`() {
        val details = routeDetails()

        val intelligence =
            details.trailIntelligenceFor(
                activeSnapshot(
                    routeId = "/watch/routes/dolomites.gpx",
                    routeTitle = "Different title",
                    distanceFromStartMeters = 1_100.0,
                ),
            )

        requireNotNull(intelligence)
        assertEquals(1_100.0, intelligence.window.startDistanceMeters, 0.1)
        assertTrue(intelligence.window.endDistanceMeters in 2_500.0..2_900.0)
        assertTrue(intelligence.window.ascentMeters in 190.0..200.0)
        assertEquals(listOf("Water source", "Viewpoint"), intelligence.upcomingWaypoints.map { it.title })
        assertTrue(intelligence.upcomingWaypoints.first().distanceAheadMeters in 90.0..120.0)
    }

    @Test
    fun `matches an active route by title when the transferred filename changes`() {
        val details = routeDetails()
        val snapshot =
            activeSnapshot(
                routeId = "/watch/routes/renamed.gpx",
                routeTitle = "DOLOMITES LOOP",
                distanceFromStartMeters = 1_100.0,
            )

        assertTrue(details.matchesActiveHike(snapshot))
    }

    @Test
    fun `uses the selected day start and does not continue into the next day`() {
        val details = routeDetails()
        val day =
            MissionPlanDay(
                id = "day-2",
                dayNumber = 2,
                routeId = details.route.id,
                startDistanceMeters = 1_100.0,
                endDistanceMeters = 2_000.0,
            )

        val intelligence = details.trailIntelligenceFor(day)

        requireNotNull(intelligence)
        assertEquals(TrailIntelligenceContext.PLANNED_DAY, intelligence.context)
        assertEquals(1_100.0, intelligence.window.startDistanceMeters, 0.1)
        assertEquals(2_000.0, intelligence.window.endDistanceMeters, 0.1)
        assertEquals(listOf("Water source", "Viewpoint"), intelligence.upcomingWaypoints.map { it.title })
    }

    @Test
    fun `labels weather loaded from a later mission day as the planned day start`() {
        val location =
            routeDetails().weatherLocationFor(
                activeHikeSnapshot = null,
                plannedStartDistanceMeters = 1_100.0,
            )

        requireNotNull(location)
        assertEquals("Planned day start", location.label)
    }

    @Test
    fun `does not forecast a different or completed route`() {
        val details = routeDetails()
        val otherRoute =
            activeSnapshot(
                routeId = "/watch/routes/other.gpx",
                routeTitle = "Other route",
                distanceFromStartMeters = 1_100.0,
            )
        val finished =
            activeSnapshot(
                routeId = "/watch/routes/dolomites.gpx",
                routeTitle = "Dolomites Loop",
                distanceFromStartMeters = 4_000.0,
                phase = ActiveHikePhase.FINISHED,
            )

        assertFalse(details.matchesActiveHike(otherRoute))
        assertNull(details.trailIntelligenceFor(otherRoute))
        assertNull(details.trailIntelligenceFor(finished))
    }

    private fun routeDetails(): RouteLibraryRouteDetails =
        RouteLibraryRouteDetails(
            route =
                RouteLibraryRoute(
                    id = "route-id",
                    title = "Dolomites Loop",
                    storedFileName = "dolomites.gpx",
                    importedAtMillis = 1L,
                    summary =
                        RouteLibrarySummary(
                            distanceMeters = 0.0,
                            elevationGainMeters = 0.0,
                            elevationLossMeters = 0.0,
                            estimatedDurationSeconds = 0.0,
                            waypointCount = 0,
                            firstThirtyMinutesDistanceMeters = 0.0,
                            firstThirtyMinutesAscentMeters = 0.0,
                        ),
                ),
            profile =
                buildTrailRouteProfile(
                    points =
                        listOf(
                            point(longitude = 0.00, elevationMeters = 100.0),
                            point(longitude = 0.01, elevationMeters = 100.0),
                            point(longitude = 0.02, elevationMeters = 300.0),
                            point(longitude = 0.03, elevationMeters = 300.0),
                            point(longitude = 0.04, elevationMeters = 200.0),
                        ),
                    pacing =
                        TrailPacingConfig(
                            flatSpeedMetersPerSecond = 1.0,
                            uphillVerticalMetersPerHour = 3_600.0,
                        ),
                ),
            waypoints =
                listOf(
                    RouteLibraryWaypoint("Start", null, 1_000.0),
                    RouteLibraryWaypoint("Water source", "Fill bottles", 1_200.0),
                    RouteLibraryWaypoint("Viewpoint", null, 2_000.0),
                    RouteLibraryWaypoint("Alpine hut", null, 2_900.0),
                ),
        )

    private fun activeSnapshot(
        routeId: String,
        routeTitle: String,
        distanceFromStartMeters: Double,
        phase: ActiveHikePhase = ActiveHikePhase.FOLLOWING_ROUTE,
    ): ActiveHikeSnapshot =
        ActiveHikeSnapshot(
            phase = phase,
            routeId = routeId,
            routeTitle = routeTitle,
            distanceFromStartMeters = distanceFromStartMeters,
            distanceRemainingMeters = null,
            progressFraction = null,
            estimatedRemainingSeconds = null,
            remainingAscentMeters = null,
            remainingDescentMeters = null,
            offRoute = false,
            recordedAtEpochMillis = 1L,
        )

    private fun point(
        longitude: Double,
        elevationMeters: Double,
    ): TrailPoint =
        TrailPoint(
            location = GeoPoint(latitude = 0.0, longitude = longitude),
            elevationMeters = elevationMeters,
        )
}
