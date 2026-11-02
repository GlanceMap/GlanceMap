package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorldWindGeometryTest {
    @Test
    fun exaggerationPolicyKeepsExperimentsWithinReasonableBounds() {
        listOf(1.0, 1.15, 1.25).forEach { assertEquals(it, phoneWorldWindExaggeration(it), 0.0) }
        assertEquals(1.0, phoneWorldWindExaggeration(0.5), 0.0)
        assertEquals(1.25, phoneWorldWindExaggeration(2.0), 0.0)
    }

    @Test
    fun xyzAddressesWrapLongitudeAndClampLatitude() {
        assertEquals(PhoneWorldWindTile(0, 0, 0), phoneWorldWindTile(0.0, 0.0, 0))
        assertEquals(PhoneWorldWindTile(2, 0, 2), phoneWorldWindTile(0.0, 180.0, 2))
        assertEquals(0, phoneWorldWindTile(90.0, 0.0, 4).y)
        assertEquals(15, phoneWorldWindTile(-90.0, 0.0, 4).y)
    }

    @Test
    fun tileBoundsIntersectPartialCoverageButRejectOutside() {
        val tile = phoneWorldWindTile(46.0, 11.0, 8)
        val bounds = phoneWorldWindTileBounds(tile)
        assertTrue(bounds.intersects(46.0, 11.0, 46.1, 11.1))
        assertFalse(bounds.intersects(-20.0, -20.0, -10.0, -10.0))
    }

    @Test
    fun cacheKeysIncludeMapThemeAndScale() {
        val a = PhoneWorldWindTileKey("map-a", "theme-a", 1f, PhoneWorldWindTile(2, 1, 1))
        assertNotEquals(a, a.copy(themeIdentity = "theme-b"))
        assertNotEquals(a, a.copy(mapIdentity = "map-b"))
        assertNotEquals(a, a.copy(scale = 2f))
    }

    @Test
    fun cameraTiltStaysBetweenStraightDownAndThirtyDegreesAboveTheHorizon() {
        assertEquals(55.0, PHONE_WORLDWIND_DEFAULT_TILT_DEGREES, 0.0)
        assertEquals(0.0, phoneWorldWindClampedTilt(-10.0), 0.0)
        assertEquals(30.0, phoneWorldWindClampedTilt(30.0), 0.0)
        assertEquals(60.0, phoneWorldWindClampedTilt(80.0), 0.0)
    }

    @Test
    fun cameraRangeAndZoomRoundTripAtMountainLatitudes() {
        for (zoom in listOf(8.0, 12.0, 15.0, 17.0)) {
            val range = phoneWorldWindRangeMeters(46.0, zoom)
            assertEquals(zoom, phoneWorldWindZoomForRange(46.0, range), 0.0001)
        }
    }

    @Test
    fun cameraRangeShrinksWithZoomAndTracksDoNotBridgeGaps() {
        assertTrue(phoneWorldWindRangeMeters(46.0, 10.0) > phoneWorldWindRangeMeters(46.0, 15.0))
        val a = GeoPoint(46.0, 11.0)
        val b = GeoPoint(46.1, 11.1)
        val segments = listOf(PhoneMapRouteSegment(listOf(a, b)), PhoneMapRouteSegment(listOf(b, a), isGap = true))
        assertEquals(listOf(listOf(a, b)), phoneWorldWindTrackSegments(segments))
    }
}
