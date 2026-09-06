package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.PhoneMapRendererCatalog
import com.glancemap.glancemapcompanionapp.map.RasterOnlineMapProvider
import com.glancemap.glancemapcompanionapp.map.mapTilerSatelliteProvider
import com.glancemap.glancemapcompanionapp.map.tracestrackTopoProvider
import org.junit.Assert.assertEquals
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
        assertTrue(style.contains("\"id\": \"$PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID\""))
    }

    @Test
    fun mainOnlineProviderStyleUsesOpenTopoMapsMaximumZoom() {
        val style = PhoneMapRendererCatalog.mainOnlineRasterProvider.mapLibreRasterStyleJson()

        assertTrue(style.contains("\"maxzoom\": 17"))
    }

    @Test
    fun configuredProviderCreditsContainLinksForMapLibreAttributionParser() {
        val providers =
            listOf(
                PhoneMapRendererCatalog.mainOnlineRasterProvider,
                PhoneMapRendererCatalog.utilityPickerRasterProvider,
                PhoneMapRendererCatalog.providerForOnlineSource(
                    com.glancemap.glancemapcompanionapp.map.PhoneOnlineMapSource.PLAN_IGN_V2,
                ),
                PhoneMapRendererCatalog.providerForOnlineSource(
                    com.glancemap.glancemapcompanionapp.map.PhoneOnlineMapSource.CYCLOSM,
                ),
                mapTilerSatelliteProvider("placeholder-maptiler-key"),
                tracestrackTopoProvider("placeholder-tracestrack-key"),
            )

        providers.filterNotNull().forEach { provider ->
            assertTrue(provider.attribution, provider.attribution.contains("<a href=\"https://"))
        }
    }

    @Test
    fun comparisonStyleKeepsBothDisplayedProviderCreditsAccessible() {
        val comparison = PhoneMapRendererCatalog.utilityPickerRasterProvider
        val base = PhoneMapRendererCatalog.mainOnlineRasterProvider

        val style = comparison.mapLibreRasterStyleJson(additionalAttribution = base.attribution)

        assertTrue(style.contains("https://tile.openstreetmap.org"))
        assertTrue(style.contains("https://www.openstreetmap.org/copyright"))
        assertTrue(style.contains("https://opentopomap.org/about"))
    }

    @Test
    fun rasterOpacityApplicationClampsWithoutChangingLayerIdentity() {
        assertEquals(0f, phoneMapLibreRasterOpacity(-1f), 0f)
        assertEquals(0.5f, phoneMapLibreRasterOpacity(0.5f), 0f)
        assertEquals(1f, phoneMapLibreRasterOpacity(2f), 0f)
        assertEquals("online-raster", PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID)
    }
}
