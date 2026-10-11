package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapRouteAnalysisTest {
    @Test
    fun longPressSnapsAndBuildsAAndBRouteStatistics() {
        val item =
            PhoneMapGpxItem(
                id = "route",
                displayName = "Route",
                track =
                    PhoneMapGpxTrack(
                        id = "route",
                        points =
                            listOf(
                                TrailPoint(GeoPoint(46.0, 6.0), elevationMeters = 100.0),
                                TrailPoint(GeoPoint(46.0, 6.01), elevationMeters = 150.0),
                                TrailPoint(GeoPoint(46.0, 6.02), elevationMeters = 120.0),
                            ),
                    ),
                enabled = true,
            )

        val pointA =
            findClosestPhoneMapRoutePosition(
                press = PhoneMapCoordinate(46.0, 6.005),
                items = listOf(item),
            )
        val pointB =
            findClosestPhoneMapRoutePosition(
                press = PhoneMapCoordinate(46.0, 6.015),
                items = listOf(item),
                allowedTrackId = "route",
            )

        assertNotNull(pointA)
        assertNotNull(pointB)
        assertEquals(6.005, requireNotNull(pointA).coordinate.longitude, 0.0001)
        val analysis =
            buildPhoneMapRouteAnalysis(
                pointA = requireNotNull(pointA),
                pointB = requireNotNull(pointB),
                settings = PhoneMapGpxSettings(),
                generalSettings = PhoneGeneralSettings(),
            )

        assertEquals("route", analysis.trackId)
        assertTrue(analysis.startToA.distanceMeters > 0.0)
        assertTrue(requireNotNull(analysis.aToB).distanceMeters > 0.0)
        assertTrue(requireNotNull(analysis.bToEnd).distanceMeters > 0.0)
        assertTrue(analysis.startToA.durationSeconds > 0.0)
    }

    @Test
    fun reverseAtoBUsesReverseElevationDirection() {
        val item =
            PhoneMapGpxItem(
                id = "route",
                displayName = "Route",
                track =
                    PhoneMapGpxTrack(
                        id = "route",
                        points =
                            listOf(
                                TrailPoint(GeoPoint(46.0, 6.0), elevationMeters = 100.0),
                                TrailPoint(GeoPoint(46.0, 6.01), elevationMeters = 150.0),
                                TrailPoint(GeoPoint(46.0, 6.02), elevationMeters = 120.0),
                            ),
                    ),
                enabled = true,
            )
        val pointA =
            requireNotNull(
                findClosestPhoneMapRoutePosition(
                    press = PhoneMapCoordinate(46.0, 6.015),
                    items = listOf(item),
                ),
            )
        val pointB =
            requireNotNull(
                findClosestPhoneMapRoutePosition(
                    press = PhoneMapCoordinate(46.0, 6.005),
                    items = listOf(item),
                    allowedTrackId = "route",
                ),
            )

        val analysis =
            buildPhoneMapRouteAnalysis(
                pointA = pointA,
                pointB = pointB,
                settings = PhoneMapGpxSettings(),
                generalSettings = PhoneGeneralSettings(),
            )

        assertEquals(15.0, requireNotNull(analysis.aToB).ascentMeters, 0.5)
        assertEquals(25.0, requireNotNull(analysis.aToB).descentMeters, 0.5)
    }

    @Test
    fun aToBDurationUsesTravelDirectionAndFractionalEndpointPacing() {
        val item = routeWithUphillAndDownhill()
        val pointA = routeHit(item, PhoneMapRouteAnalysisPosition(segmentIndex = 0, fraction = 0.25))
        val pointB = routeHit(item, PhoneMapRouteAnalysisPosition(segmentIndex = 1, fraction = 0.5))
        val settings =
            PhoneMapGpxSettings(
                flatSpeedMetersPerSecond = 4f,
                advancedEtaEnabled = true,
                uphillVerticalMetersPerHour = 3_600f,
                downhillVerticalMetersPerHour = 900f,
            )
        val points = item.track.points
        val horizontalDistanceMeters =
            haversineDistanceMeters(points[0].location, points[1].location) * 0.75 +
                haversineDistanceMeters(points[1].location, points[2].location) * 0.5
        val forwardAscentMeters = 37.5
        val forwardDescentMeters = 15.0
        val expectedForwardDurationSeconds =
            horizontalDistanceMeters / 4.0 +
                forwardAscentMeters / 3_600.0 * 3_600.0 +
                forwardDescentMeters / 900.0 * 3_600.0
        val expectedReverseDurationSeconds =
            horizontalDistanceMeters / 4.0 +
                forwardDescentMeters / 3_600.0 * 3_600.0 +
                forwardAscentMeters / 900.0 * 3_600.0

        val forward =
            requireNotNull(
                buildPhoneMapRouteAnalysis(
                    pointA = pointA,
                    pointB = pointB,
                    settings = settings,
                    generalSettings = PhoneGeneralSettings(),
                ).aToB,
            )
        val reverse =
            requireNotNull(
                buildPhoneMapRouteAnalysis(
                    pointA = pointB,
                    pointB = pointA,
                    settings = settings,
                    generalSettings = PhoneGeneralSettings(),
                ).aToB,
            )

        assertEquals(expectedForwardDurationSeconds, forward.durationSeconds, 0.001)
        assertEquals(expectedReverseDurationSeconds, reverse.durationSeconds, 0.001)
        assertEquals(forwardAscentMeters, forward.ascentMeters, 0.001)
        assertEquals(forwardDescentMeters, forward.descentMeters, 0.001)
        assertEquals(forwardDescentMeters, reverse.ascentMeters, 0.001)
        assertEquals(forwardAscentMeters, reverse.descentMeters, 0.001)
        assertEquals(horizontalDistanceMeters, forward.distanceMeters, 0.001)
        assertEquals(horizontalDistanceMeters, reverse.distanceMeters, 0.001)
    }

    @Test
    fun aToBDurationUsesOnlyFlatPacingWhenVerticalRatesAreDisabled() {
        val item = routeWithUphillAndDownhill()
        val pointA = routeHit(item, PhoneMapRouteAnalysisPosition(segmentIndex = 0, fraction = 0.0))
        val pointB = routeHit(item, PhoneMapRouteAnalysisPosition(segmentIndex = 1, fraction = 1.0))
        val settings =
            PhoneMapGpxSettings(
                flatSpeedMetersPerSecond = 4f,
                advancedEtaEnabled = true,
                uphillVerticalMetersPerHour = 0f,
                downhillVerticalMetersPerHour = 0f,
            )
        val expectedDurationSeconds =
            item.track.points.zipWithNext().sumOf { (from, to) ->
                haversineDistanceMeters(from.location, to.location)
            } / 4.0

        listOf(pointA to pointB, pointB to pointA).forEach { (start, end) ->
            val leg =
                requireNotNull(
                    buildPhoneMapRouteAnalysis(
                        pointA = start,
                        pointB = end,
                        settings = settings,
                        generalSettings = PhoneGeneralSettings(),
                    ).aToB,
                )
            assertEquals(expectedDurationSeconds, leg.durationSeconds, 0.001)
        }
    }

    @Test
    fun aToBDurationIsZeroWhenEndpointsAreEqual() {
        val item = routeWithUphillAndDownhill()
        val point = routeHit(item, PhoneMapRouteAnalysisPosition(segmentIndex = 0, fraction = 0.5))
        val leg =
            requireNotNull(
                buildPhoneMapRouteAnalysis(
                    pointA = point,
                    pointB = point,
                    settings =
                        PhoneMapGpxSettings(
                            advancedEtaEnabled = true,
                            uphillVerticalMetersPerHour = 3_600f,
                            downhillVerticalMetersPerHour = 900f,
                        ),
                    generalSettings = PhoneGeneralSettings(),
                ).aToB,
            )

        assertEquals(0.0, leg.distanceMeters, 0.0)
        assertEquals(0.0, leg.ascentMeters, 0.0)
        assertEquals(0.0, leg.descentMeters, 0.0)
        assertEquals(0.0, leg.durationSeconds, 0.0)
    }

    private fun routeWithUphillAndDownhill(): PhoneMapGpxItem =
        PhoneMapGpxItem(
            id = "route",
            displayName = "Route",
            track =
                PhoneMapGpxTrack(
                    id = "route",
                    points =
                        listOf(
                            TrailPoint(GeoPoint(46.0, 6.0), elevationMeters = 100.0),
                            TrailPoint(GeoPoint(46.0, 6.01), elevationMeters = 150.0),
                            TrailPoint(GeoPoint(46.0, 6.02), elevationMeters = 120.0),
                        ),
                ),
            enabled = true,
        )

    private fun routeHit(
        item: PhoneMapGpxItem,
        position: PhoneMapRouteAnalysisPosition,
    ): PhoneMapRouteAnalysisHit =
        PhoneMapRouteAnalysisHit(
            item = item,
            position = position,
            coordinate =
                PhoneMapCoordinate(
                    latitude = 46.0,
                    longitude = 6.0 + (position.segmentIndex + position.fraction) * 0.01,
                ),
            distanceToTrackMeters = 0.0,
        )
}
