package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapRendererCatalogTest {
    @Test
    fun onlineModeUsesTheFutureMainMapProviderWithoutChangingUtilityPickers() {
        val renderer = PhoneMapRendererCatalog.rendererFor(MapMode.ONLINE)

        assertTrue(renderer.isAvailable)
        assertEquals(MapMode.ONLINE, renderer.mode)
        assertEquals("open_topo_map", renderer.rasterOnlineProvider?.id)
        assertEquals(renderer.rasterOnlineProvider, PhoneMapRendererCatalog.mainOnlineRasterProvider)
        assertEquals("open_street_map", PhoneMapRendererCatalog.utilityPickerRasterProvider.id)
    }

    @Test
    fun offlineModeIsExplicitBeforeItsPhoneAdapterExists() {
        val renderer = PhoneMapRendererCatalog.rendererFor(MapMode.OFFLINE)

        assertFalse(renderer.isAvailable)
        assertFalse(renderer.capabilities.hillshade)
        assertFalse(renderer.capabilities.slopeOverlay)
        assertFalse(renderer.capabilities.contoursToggle)
        assertFalse(renderer.capabilities.themes)
    }
}
