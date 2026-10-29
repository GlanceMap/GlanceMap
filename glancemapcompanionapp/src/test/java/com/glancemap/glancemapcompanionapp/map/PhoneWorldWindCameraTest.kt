package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneWorldWindCameraTest {
    private fun camera() =
        LookAt().set(
            46.0.degrees,
            11.0.degrees,
            0.0,
            AltitudeMode.CLAMP_TO_GROUND,
            phoneWorldWindRangeMeters(46.0, 14.0),
            25.0.degrees,
            50.0.degrees,
            0.0.degrees,
        )

    @Test
    fun zoomCommandsPreserveCenterBearingAndTilt() {
        val result = phoneWorldWindCommandCamera(camera(), PhoneMapCameraCommand(1, zoomDelta = 1), PhoneMapSettings(), 400.0)
        assertEquals(15.0, phoneWorldWindZoomForRange(46.0, result.range), 0.0001)
        assertEquals(46.0, result.position.latitude.inDegrees, 0.0001)
        assertEquals(11.0, result.position.longitude.inDegrees, 0.0001)
        assertEquals(25.0, result.heading.inDegrees, 0.0001)
        assertEquals(50.0, result.tilt.inDegrees, 0.0001)
    }

    @Test
    fun followUsesNewLocationAndHeadingWithoutOverridingFreeView() {
        val point = GeoPoint(46.1, 11.2)
        val result = phoneWorldWindFollowCamera(camera(), PhoneMapMode(), point, 90f)!!
        assertEquals(46.1, result.position.latitude.inDegrees, 0.0001)
        assertEquals(11.2, result.position.longitude.inDegrees, 0.0001)
        assertEquals(90.0, result.heading.inDegrees, 0.0001)
        assertEquals(50.0, result.tilt.inDegrees, 0.0001)
        assertNull(phoneWorldWindFollowCamera(camera(), PhoneMapMode(follow = PhoneMapFollowMode.FREE), point, 90f))
    }

    @Test
    fun searchChangesTargetButKeepsOrientation() {
        val result =
            phoneWorldWindCommandCamera(
                camera(),
                PhoneMapCameraCommand(2, target = PhoneMapCoordinate(47.0, 12.0)),
                PhoneMapSettings(),
                400.0,
            )
        assertEquals(47.0, result.position.latitude.inDegrees, 0.0001)
        assertEquals(12.0, result.position.longitude.inDegrees, 0.0001)
        assertEquals(50.0, result.tilt.inDegrees, 0.0001)
        assertEquals(25.0, result.heading.inDegrees, 0.0001)
    }
}
