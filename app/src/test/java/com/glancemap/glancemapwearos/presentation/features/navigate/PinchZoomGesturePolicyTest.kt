package com.glancemap.glancemapwearos.presentation.features.navigate

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
}
