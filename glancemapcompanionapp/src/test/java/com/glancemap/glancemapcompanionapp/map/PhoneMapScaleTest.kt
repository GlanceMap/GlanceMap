package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMapScaleTest {
    @Test
    fun scaleDistanceTracksZoomAndUsesNiceMetricSteps() {
        val zoom14 = calculatePhoneMapScaleIndicator(latitudeDegrees = 45.0, zoom = 14.0, viewportWidthPx = 1_080.0)
        val zoom15 = calculatePhoneMapScaleIndicator(latitudeDegrees = 45.0, zoom = 15.0, viewportWidthPx = 1_080.0)

        assertEquals("2.0 km", requireNotNull(zoom14).label)
        assertEquals("1.0 km", requireNotNull(zoom15).label)
        assertEquals(requireNotNull(zoom14).widthRatio, requireNotNull(zoom15).widthRatio, 0.001f)
    }

    @Test
    fun invalidViewportDoesNotProduceScaleBar() {
        assertNull(calculatePhoneMapScaleIndicator(latitudeDegrees = 45.0, zoom = 14.0, viewportWidthPx = 0.0))
    }
}
