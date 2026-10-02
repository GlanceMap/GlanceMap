package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchZoomGesturePolicyTest {
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
