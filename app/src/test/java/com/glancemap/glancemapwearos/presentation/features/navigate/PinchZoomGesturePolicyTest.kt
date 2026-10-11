package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchZoomGesturePolicyTest {
    @Test
    fun `touch jitter before a suppressed pinch does not start panning`() {
        val start = ScreenAnchor(100.0, 100.0)

        listOf(ScreenAnchor(101.0, 101.0), ScreenAnchor(102.0, 101.0)).forEach { point ->
            assertFalse(shouldStartMapPan(start, point, touchSlopPx = 8))
        }
        assertTrue(shouldSuppressMultiTouchMapGesture(pinchZoomEnabled = false, isMultiTouchGesture = true))
    }

    @Test
    fun `drag starts only after leaving the platform touch slop`() {
        val start = ScreenAnchor(100.0, 100.0)

        assertFalse(shouldStartMapPan(start, ScreenAnchor(108.0, 100.0), touchSlopPx = 8))
        assertTrue(shouldStartMapPan(start, ScreenAnchor(109.0, 100.0), touchSlopPx = 8))
        assertTrue(shouldStartMapPan(start, ScreenAnchor(91.0, 100.0), touchSlopPx = 8))
        assertTrue(shouldStartMapPan(start, ScreenAnchor(106.0, 106.0), touchSlopPx = 8))
    }

    @Test
    fun `a cancelled gesture cannot start panning without another touch down`() {
        assertFalse(shouldStartMapPan(null, ScreenAnchor(150.0, 100.0), touchSlopPx = 8))
    }

    @Test
    fun `multi-touch stays blocked when pinch zoom is disabled`() {
        assertTrue(
            shouldSuppressMultiTouchMapGesture(
                pinchZoomEnabled = false,
                isMultiTouchGesture = true,
            ),
        )
    }

    @Test
    fun `multi-touch reaches Mapsforge when pinch zoom is enabled`() {
        assertFalse(
            shouldSuppressMultiTouchMapGesture(
                pinchZoomEnabled = true,
                isMultiTouchGesture = true,
            ),
        )
    }

    @Test
    fun `pinch in produces an unzoom step`() {
        assertEquals(-1, pinchZoomOutStep(0.8f))
        assertEquals(-2, pinchZoomOutStep(0.25f))
    }

    @Test
    fun `pinch out and invalid scale produce no unzoom step`() {
        assertEquals(0, pinchZoomOutStep(1.2f))
        assertEquals(0, pinchZoomOutStep(Float.NaN))
    }
}
