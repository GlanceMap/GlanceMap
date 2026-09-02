package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
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
}
