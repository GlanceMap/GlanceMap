package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapLayerStatusTest {
    @Test
    fun defaultMapDoesNotShowAnActiveLayer() {
        listOf(false, true).forEach { offline ->
            assertFalse(
                PhoneMapSettings().hasActiveMapLayer(offline, hasElevationData = true, comparisonActive = false),
            )
        }
    }

    @Test
    fun visibleComparisonShowsAnActiveLayerOnEitherBaseMap() {
        listOf(false, true).forEach { offline ->
            assertTrue(PhoneMapSettings().hasActiveMapLayer(offline, hasElevationData = false, comparisonActive = true))
        }
    }

    @Test
    fun onlineHillShadingDoesNotRequireOfflineElevationData() {
        val settings = PhoneMapSettings(hillShadingEnabled = true)
        assertTrue(settings.hasActiveMapLayer(offlineBase = false, hasElevationData = false, comparisonActive = false))
        assertFalse(settings.hasActiveMapLayer(offlineBase = true, hasElevationData = false, comparisonActive = false))
        assertTrue(settings.hasActiveMapLayer(offlineBase = true, hasElevationData = true, comparisonActive = false))
    }

    @Test
    fun slopeReliefNeedsAnOfflineMapElevationDataAndVisibleOpacity() {
        val settings = PhoneMapSettings(reliefOverlayEnabled = true, reliefOverlayOpacityPercent = 50)
        assertTrue(settings.hasActiveMapLayer(offlineBase = true, hasElevationData = true, comparisonActive = false))
        assertFalse(settings.hasActiveMapLayer(offlineBase = false, hasElevationData = true, comparisonActive = false))
        assertFalse(settings.hasActiveMapLayer(offlineBase = true, hasElevationData = false, comparisonActive = false))
        assertFalse(
            settings.copy(reliefOverlayOpacityPercent = 0).hasActiveMapLayer(
                offlineBase = true,
                hasElevationData = true,
                comparisonActive = false,
            ),
        )
    }
}
