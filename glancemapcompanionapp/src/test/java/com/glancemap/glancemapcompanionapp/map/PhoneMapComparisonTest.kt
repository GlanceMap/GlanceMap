package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapComparisonTest {
    @Test
    fun cameraSynchronizationUsesTolerancesIncludingAcrossNorth() {
        val camera =
            PhoneMapCameraSnapshot(
                latitude = 46.0,
                longitude = 7.0,
                zoom = 12.0,
                bearingDegrees = 359.8f,
            )

        assertFalse(
            phoneMapComparisonCameraNeedsSync(
                current = camera,
                target = camera.copy(longitude = 7.0000005, bearingDegrees = 0.1f),
            ),
        )
        assertTrue(
            phoneMapComparisonCameraNeedsSync(
                current = camera,
                target = camera.copy(zoom = 12.2),
            ),
        )
    }

    @Test
    fun transparencyControlsOnlyTheUpperLayerOpacity() {
        val comparison = PhoneMapComparisonState()

        assertTrue(comparison.overlayAlpha() == 0.5f)
        assertTrue(comparison.withTransparency(100f).overlayAlpha() == 0f)
        assertTrue(comparison.withTransparency(-1f).overlayAlpha() == 1f)
    }
}
