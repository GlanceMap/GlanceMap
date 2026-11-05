package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWindCameraTest {
    @Test
    fun `cache budget scales with heap but is capped`() {
        assertEquals(8L * 1024 * 1024, worldWindCacheBudget(64L * 1024 * 1024))
        assertEquals(16L * 1024 * 1024, worldWindCacheBudget(512L * 1024 * 1024))
        assertTrue(worldWindCacheBudget(32L * 1024 * 1024) <= 4L * 1024 * 1024)
    }

    @Test
    fun `worldwind can zoom closer than the custom terrain minimum`() {
        assertTrue(worldWindZoomByStep(1000.0, 1) < 1000.0)
        assertTrue(worldWindZoomByStep(14000.0, -1) > 14000.0)
        assertTrue(worldWindZoomByStep(250.0, 1) < 250.0)
        assertTrue(worldWindZoomByStep(50000.0, -1) > 50000.0)
    }

    @Test
    fun `initial field of view maps the existing camera range`() {
        assertEquals(2000.0, worldWindInitialRange(), 0.01)
        assertEquals(60.0, worldWindViewingAngle(60), 0.01)
        assertEquals(45.0, worldWindViewingAngle(45), 0.01)
        assertEquals(70.0, worldWindViewingAngle(70), 0.01)
    }

    @Test
    fun `wide views flatten while local views retain the chosen tilt`() {
        assertEquals(0.0, worldWindDisplayedTilt(0.0, 2_000.0), 0.01)
        assertEquals(85.0, worldWindDisplayedTilt(85.0, 2_000.0), 0.01)
        assertTrue(worldWindDisplayedTilt(85.0, 500_000.0) in 0.0..84.9)
        assertEquals(0.0, worldWindDisplayedTilt(85.0, 2_000_000.0), 0.01)
    }

    @Test
    fun `approximate map zoom changes with camera range`() {
        assertTrue(worldWindApproximateZoom(0.0, 1_000.0) > worldWindApproximateZoom(0.0, 10_000.0))
        assertTrue(worldWindApproximateZoom(45.0, 1_000_000.0) in 1..22)
    }
}
