package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.OnlineMapProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLibreRasterStyleTest {
    @Test
    fun styleIsBuiltFromProviderConfigurationAndEscapesText() {
        val style =
            OnlineMapProvider(
                id = "test",
                displayName = "Test",
                attribution = "Test \"attribution\"",
                rasterTileUrlTemplate = "https://example.test/{z}/{x}/{y}.png",
            ).mapLibreRasterStyleJson()

        assertTrue(style.contains("https://example.test/{z}/{x}/{y}.png"))
        assertTrue(style.contains("Test \\\"attribution\\\""))
    }
}
