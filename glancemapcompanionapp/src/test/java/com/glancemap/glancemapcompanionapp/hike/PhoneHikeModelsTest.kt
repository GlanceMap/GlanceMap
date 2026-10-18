package com.glancemap.glancemapcompanionapp.hike

import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceProgressDraft
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceRouteDraft
import com.glancemap.glancemapcompanionapp.map.buildPhoneGuidanceSession
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.guidance.buildTrailGuidanceEtaProjection
import com.glancemap.trailcore.guidance.reverseTrailPoints
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.*
import org.junit.Test

class PhoneHikeModelsTest {
    @Test
    fun openingGpxInNewTabAddsAndSelectsDistinctRouteTabs() {
        val today = PhoneHikeWorkspaceData().useForToday("today")
        val first = today.openPreviewTab("route-a", "Ridge route")
        val second = first.openPreviewTab("route-b", "Valley route")
        val repeated = second.openPreviewTab("route-b", "Valley route")

        assertEquals(listOf("route-a", "route-b"), repeated.previewTabs.map(PhoneHikePreviewTab::routeId))
        assertEquals("route-b", repeated.selectedId)
        assertEquals("Valley route", repeated.previewTabs.last().title)
        assertEquals("today", repeated.copy(previewSelected = false).selectedId)
        assertEquals("route-a", repeated.selectPreviewTab("route-a").selectedId)
    }

    @Test
    fun previewOfSameRouteDoesNotEditPinnedHike() {
        val original = PhoneHikePlan("a", startTime = "08:00")
        val data = PhoneHikeWorkspaceData().updatePlan(original).useForToday("a")
        val preview = data.openPreview("a").updateSelectedPlan(original.copy(startTime = "10:00", inspecting = true))
        assertEquals("08:00", preview.todayPlan?.startTime)
        assertEquals("10:00", preview.selectedPlan?.startTime)
        assertEquals("08:00", preview.copy(previewSelected = false).selectedPlan?.startTime)
        val promoted = preview.useForToday("a")
        assertEquals("10:00", promoted.selectedPlan?.startTime)
        assertFalse(requireNotNull(promoted.selectedPlan).inspecting)
    }

    @Test
    fun replacingTodayArchivesItsCurrentCheckpointsAndSchedule() {
        val original = PhoneHikePlan("a", startTime = "08:00")
        val changed = original.copy(startTime = "09:00", checkpoints = listOf(checkpoint(0, .5)))
        val data = PhoneHikeWorkspaceData().updatePlan(original).useForToday("a").updateSelectedPlan(changed)
        val preview = data.openPreview("b")
        assertEquals("a", preview.todayId)
        assertEquals(changed, preview.todayPlan)
        val promoted = preview.useForToday("b")
        assertEquals(changed, promoted.plan("a"))
        assertEquals("b", promoted.selectedId)
    }

    @Test
    fun contentRevisionChangesWithoutChangingTrackPath() {
        val points = hikeRoute().points
        val id = phoneHikeRouteId("same-path", points, 0.0)
        assertNotEquals(
            id,
            phoneHikeRouteId("same-path", points + points.last().copy(location = GeoPoint(46.01, 11.01)), 0.0),
        )
        assertNotEquals(id, phoneHikeRouteId("same-path", points, 100.0))
        assertEquals(id, phoneHikeRouteId("same-path", reverseTrailPoints(reverseTrailPoints(points)), 0.0))
    }

    @Test
    fun inspectionUsesCanonicalSegmentReferencesInEitherDirection() {
        val route = hikeRoute()
        val forward = PhoneHikeRouteModel(route)
        val reverse = PhoneHikeRouteModel(route, reversed = true)
        val a = requireNotNull(forward.position(1, .25))
        val b = requireNotNull(reverse.position(1, .25))
        assertEquals(a.coordinate, b.coordinate)
        assertEquals(a.elevationMeters!!, b.elevationMeters!!, .00001)
        assertEquals(forward.totalDistanceMeters, a.distanceMeters + b.distanceMeters, .00001)
        assertEquals(route.points.last().location, forward.atDistance(Double.MAX_VALUE)?.coordinate)
        assertEquals(route.points.first().location, reverse.atDistance(Double.MAX_VALUE)?.coordinate)
        assertEquals(
            route.points.last().location,
            reverse.mapSegments
                .first()
                .points
                .first(),
        )
    }

    @Test
    fun gapsHaveNoInventedGeometryDistanceOrTime() {
        val points =
            listOf(
                TrailPoint(GeoPoint(46.0, 11.0), 100.0),
                TrailPoint(GeoPoint(46.0, 11.001), 100.0),
                TrailPoint(GeoPoint(47.0, 12.0), 200.0, startsNewSegment = true),
                TrailPoint(GeoPoint(47.0, 12.001), 200.0),
            )
        val model = PhoneHikeRouteModel(hikeRoute(points))
        assertNull(model.position(1, .5))
        assertEquals(2, model.mapSegments.size)
        assertTrue(model.totalDistanceMeters < 200.0)
        assertNotNull(model.atDistance(model.distances[2]))
        assertTrue(model.graphPoints.drop(1).any { it.startsNewSegment })
        assertNull(PhoneHikeRouteModel(hikeRoute(points), reversed = true).position(1, .5))
    }

    @Test
    fun sharedEtaIsUnchangedAndStopsOnlyAffectArrivalTimes() {
        val route = hikeRoute()
        val model = PhoneHikeRouteModel(route)
        val session =
            buildPhoneGuidanceSession(
                PhoneGuidanceRouteDraft(route.trackId, route.title, route.points, 0.0, "HIKE", route.etaConfig),
                PhoneGuidanceProgressDraft(),
            )
        val shared =
            requireNotNull(
                buildTrailGuidanceEtaProjection(
                    session.trackPoints,
                    session.cumulativeDistancesMeters,
                    route.etaConfig,
                ),
            )
        assertEquals(shared.totalSeconds!!, model.totalSeconds, .00001)
        val destination = requireNotNull(model.atDistance(model.totalDistanceMeters))
        val plan = PhoneHikePlan(route.id, checkpoints = listOf(checkpoint(0, .5, stopMinutes = 15)))
        assertEquals(model.totalSeconds + 900.0, model.plannedSecondsAt(destination, plan), .00001)
        assertEquals(model.totalSeconds, destination.elapsedSeconds, .00001)
    }

    @Test
    fun missingElevationStaysUnavailable() {
        val model = PhoneHikeRouteModel(hikeRoute(hikeRoute().points.map { it.copy(elevationMeters = null) }))
        val point = requireNotNull(model.atDistance(model.totalDistanceMeters / 2.0))
        assertNull(point.elevationMeters)
        assertNull(point.ascentMeters)
        assertNull(point.descentMeters)
        assertTrue(model.graphPoints.all { it.elevationMeters == null })
    }

    @Test
    fun checkpointProjectionRespectsLoopContinuity() {
        val a = GeoPoint(46.0, 11.0)
        val b = GeoPoint(46.0, 11.001)
        val model =
            PhoneHikeRouteModel(
                hikeRoute(listOf(a, b, GeoPoint(46.001, 11.001), b, GeoPoint(46.0, 11.002)).map { TrailPoint(it) }),
            )
        val later = requireNotNull(model.position(3, .0))
        val hit = requireNotNull(model.nearest(b, later.distanceMeters))
        assertEquals(later.distanceMeters, hit.first.distanceMeters, .001)
    }
}

internal fun hikeRoute(
    points: List<TrailPoint> =
        listOf(
            TrailPoint(GeoPoint(46.0, 11.0), 100.0),
            TrailPoint(GeoPoint(46.0, 11.01), 250.0),
            TrailPoint(GeoPoint(46.0, 11.02), 150.0),
        ),
): PhoneHikeRoute =
    PhoneHikeRoute(
        phoneHikeRouteId("gpx/test", points, 0.0),
        "gpx/test",
        "Test route",
        points,
        0.0,
        TrailGuidanceEtaConfig(flatSpeedMps = 1.2),
    )

internal fun checkpoint(
    segment: Int,
    fraction: Double,
    stopMinutes: Int = 0,
): PhoneHikeCheckpoint =
    PhoneHikeCheckpoint(
        "checkpoint-$segment-$fraction",
        "place-$segment-$fraction",
        "Checkpoint",
        46.0,
        11.0,
        segment,
        fraction,
        stopMinutes,
    )
