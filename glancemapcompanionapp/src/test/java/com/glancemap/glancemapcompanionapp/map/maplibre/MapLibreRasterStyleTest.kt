package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.PhoneMapRendererCatalog
import com.glancemap.glancemapcompanionapp.map.RasterOnlineMapProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLibreRasterStyleTest {
    @Test
    fun styleIsBuiltFromProviderConfigurationAndEscapesText() {
        val style =
            RasterOnlineMapProvider(
                id = "test",
                displayName = "Test",
                attribution = "Test \"attribution\"",
                rasterTileUrlTemplate = "https://example.test/{z}/{x}/{y}.png",
            ).mapLibreRasterStyleJson()

        assertTrue(style.contains("https://example.test/{z}/{x}/{y}.png"))
        assertTrue(style.contains("Test \\\"attribution\\\""))
    }

    @Test
    fun mainOnlineProviderStyleUsesOpenTopoMapsMaximumZoom() {
        val style = PhoneMapRendererCatalog.mainOnlineRasterProvider.mapLibreRasterStyleJson()

        assertTrue(style.contains("\"maxzoom\": 17"))
    }
}
