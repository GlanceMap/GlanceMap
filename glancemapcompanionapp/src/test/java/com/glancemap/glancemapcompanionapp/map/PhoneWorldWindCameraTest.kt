package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorldWindCameraTest {
    @Test
    fun cameraReanchorsWhenTerrainArrivesButIgnoresSmallRefinements() {
        assertTrue(phoneWorldWindGroundMoved(0.0, 2_400.0))
        assertTrue(phoneWorldWindGroundMoved(2_400.0, 2_380.0))
        assertFalse(phoneWorldWindGroundMoved(2_400.0, 2_403.0))
    }

    @Test
    fun cameraEasesTowardLoadedTerrainAndLandsExactlyOnIt() {
        var ground = 47.0
        var frames = 0
        while (ground != 1_047.0) {
            ground = phoneWorldWindEasedGround(ground, 1_047.0)
            frames++
        }
        assertTrue(frames in 10..20)
        assertEquals(297.0, phoneWorldWindEasedGround(47.0, 1_047.0), 1e-9)
        assertEquals(1_040.0, phoneWorldWindEasedGround(1_032.0, 1_040.0), 0.0)
        assertEquals(-4_000.0, phoneWorldWindEasedGround(-3_995.0, -4_000.0), 0.0)
    }

    @Test
    fun tiltKeepsTheChosenAngleUpCloseAndFlattensWhenFarOut() {
        assertEquals(55.0, phoneWorldWindTiltForRange(55.0, 2_000.0), 0.0)
        assertEquals(55.0, phoneWorldWindTiltForRange(55.0, 50_000.0), 0.0)
        assertEquals(30.0, phoneWorldWindTiltForRange(55.0, 316_228.0), 0.01)
        assertEquals(0.0, phoneWorldWindTiltForRange(55.0, 2_000_000.0), 0.0)
        assertEquals(0.0, phoneWorldWindTiltForRange(55.0, 20_000_000.0), 0.0)
        assertEquals(20.0, phoneWorldWindTiltForRange(20.0, 316_228.0), 0.0)
        assertEquals(60.0, phoneWorldWindTiltForRange(80.0, 1_000.0), 0.0)
    }

    @Test
    fun globeFitRangeShowsTheWholeGlobeAcrossTheNarrowerSide() {
        val radius = 6_378_137.0
        val portrait = phoneWorldWindGlobeFitRange(45.0, 720, 1280, radius)
        val landscape = phoneWorldWindGlobeFitRange(45.0, 1280, 720, radius)
        val halfWidth = kotlin.math.atan(kotlin.math.tan(Math.toRadians(22.5)) * 720 / 1280)
        assertTrue(kotlin.math.asin(radius / (portrait + radius)) < halfWidth)
        assertTrue(kotlin.math.asin(radius / (landscape + radius)) < Math.toRadians(22.5))
        // WorldWind's own cap, twice the vertical fit, still crops a portrait screen.
        val worldWindCap = 2 * (radius / kotlin.math.sin(Math.toRadians(22.5)) - radius)
        assertTrue(portrait > worldWindCap)
        assertTrue(landscape < portrait)
        assertEquals(Double.MAX_VALUE, phoneWorldWindGlobeFitRange(45.0, 0, 1280, radius), 0.0)
    }

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
