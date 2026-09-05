package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
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
        val comparison =
            PhoneMapComparisonState(
                layer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
            )

        assertEquals(1f, comparison.withTransparency(0f).overlayAlpha(), 0f)
        assertEquals(0.75f, comparison.withTransparency(25f).overlayAlpha(), 0f)
        assertEquals(0.5f, comparison.overlayAlpha(), 0f)
        assertEquals(0.25f, comparison.withTransparency(75f).overlayAlpha(), 0f)
        assertEquals(0f, comparison.withTransparency(100f).overlayAlpha(), 0f)
        assertEquals(1f, comparison.withTransparency(-1f).overlayAlpha(), 0f)
        assertEquals(0f, comparison.withTransparency(101f).overlayAlpha(), 0f)
        assertEquals(comparison.layer, comparison.withTransparency(51f).layer)
        assertEquals(comparison.layer, comparison.withTransparency(100f).layer)
    }

    @Test
    fun onlyComparisonMapsUseTextureSurfaces() {
        assertFalse(phoneMapLibreSurfaceUsesTexture(PhoneMapLibreSurfaceMode.PRIMARY))
        assertTrue(phoneMapLibreSurfaceUsesTexture(PhoneMapLibreSurfaceMode.COMPARISON))
    }

    @Test
    fun visibleComparisonOwnsSemanticOverlays() {
        assertFalse(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = false,
                onlineComparisonActive = false,
            ),
        )
        assertTrue(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = true,
                onlineComparisonActive = false,
            ),
        )
        assertTrue(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = false,
                onlineComparisonActive = true,
            ),
        )
    }
}
