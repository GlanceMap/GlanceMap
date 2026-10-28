package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainCameraTest {
    @Test
    fun cameraLooksNorthAtZeroAndEastAtNinetyDegrees() {
        val north = terrainCameraOffset(2000f, 30f, 0f)
        assertEquals(0f, north.first, 0.01f)
        assertEquals(1000f, north.second, 0.01f)
        assertEquals(1732.05f, north.third, 0.1f)
        val east = terrainCameraOffset(2000f, 30f, 90f)
        assertEquals(-1732.05f, east.first, 0.1f)
        assertEquals(0f, east.third, 0.01f)
    }

    @Test
    fun cameraBearingWrapsAcrossNorthAndRejectsInvalidInput() {
        val bearing = TerrainBearing()
        assertTrue(bearing.set(350f))
        assertTrue(bearing.rotateByDrag(40f))
        assertEquals(10f, bearing.degrees, 0.01f)
        assertFalse(bearing.set(Float.NaN))
        assertEquals(10f, bearing.degrees, 0.01f)
    }

    @Test
    fun longPressTiltEndsOnReleaseAndDoesNotBeginAfterPanOrPinch() {
        val gesture = TerrainGesture()
        gesture.press()
        assertTrue(gesture.longPress())
        assertTrue(gesture.tilting)
        gesture.release()
        assertFalse(gesture.tilting)
        gesture.press()
        gesture.startPan()
        assertFalse(gesture.longPress())
        gesture.release()
        gesture.press()
        gesture.multiTouch()
        assertFalse(gesture.longPress())
    }

    @Test
    fun interactiveTiltClampsToTheSupportedViewingAngles() {
        val pitch = TerrainPitch(60)
        pitch.tiltByDrag(-50f)
        assertEquals(65f, pitch.viewingAngleDegrees, 0.01f)
        pitch.tiltByDrag(-1000f)
        assertEquals(70f, pitch.viewingAngleDegrees, 0.01f)
        pitch.tiltByDrag(1000f)
        assertEquals(45f, pitch.viewingAngleDegrees, 0.01f)
    }

    @Test
    fun everySupportedViewingAngleMapsToRendererElevation() {
        val cases = listOf(45 to 45f, 50 to 40f, 55 to 35f, 60 to 30f, 65 to 25f, 70 to 20f)
        for ((viewingAngle, elevation) in cases) {
            assertEquals(elevation, TerrainPitch(viewingAngle).rendererElevationDegrees, 0f)
        }
    }

    @Test
    fun viewingAngleConfiguresRendererElevationAndUpdatesWithoutRecreatingCamera() {
        val pitch = TerrainPitch(60)
        assertEquals(30f, pitch.rendererElevationDegrees, 0f)
        assertTrue(pitch.setViewingAngle(45))
        assertEquals(45f, pitch.rendererElevationDegrees, 0f)
        assertTrue(pitch.setViewingAngle(70))
        assertEquals(20f, pitch.rendererElevationDegrees, 0f)
        assertFalse(pitch.setViewingAngle(70))
        assertTrue(pitch.setViewingAngle(99))
        assertEquals(30f, pitch.rendererElevationDegrees, 0f)
    }

    @Test
    fun zoomStepsMoveCameraCloserOrFarther() {
        assertEquals(4642.857f, terrainZoomByStep(6500f, 1), 0.01f)
        assertEquals(9100f, terrainZoomByStep(6500f, -1), 0.01f)
        assertEquals(6500f, terrainZoomByStep(6500f, 0), 0.01f)
    }

    @Test
    fun zoomClampsAndIgnoresInvalidPinchFactors() {
        assertEquals(1000f, terrainZoomDistance(1500f, 10f), 0.01f)
        assertEquals(14000f, terrainZoomDistance(10000f, 0.1f), 0.01f)
        assertEquals(6500f, terrainZoomDistance(6500f, 0f), 0.01f)
        assertEquals(6500f, terrainZoomDistance(6500f, Float.NaN), 0.01f)
        assertEquals(6500f, terrainZoomDistance(6500f, Float.POSITIVE_INFINITY), 0.01f)
    }
}
