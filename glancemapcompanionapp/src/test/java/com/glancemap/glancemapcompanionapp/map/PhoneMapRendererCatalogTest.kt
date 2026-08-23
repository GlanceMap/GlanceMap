package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapRendererCatalogTest {
    @Test
    fun onlineModeUsesAReplaceableProviderConfiguration() {
        val renderer = PhoneMapRendererCatalog.rendererFor(MapMode.ONLINE)

        assertTrue(renderer.isAvailable)
        assertEquals(MapMode.ONLINE, renderer.mode)
        assertEquals("open_street_map", renderer.onlineProvider?.id)
        assertEquals(renderer.onlineProvider, PhoneMapRendererCatalog.onlineProvider)
    }

    @Test
    fun offlineModeIsExplicitBeforeItsPhoneAdapterExists() {
        val renderer = PhoneMapRendererCatalog.rendererFor(MapMode.OFFLINE)

        assertFalse(renderer.isAvailable)
        assertTrue(renderer.capabilities.hillshade)
        assertTrue(renderer.capabilities.slopeOverlay)
        assertTrue(renderer.capabilities.themes)
    }
}
