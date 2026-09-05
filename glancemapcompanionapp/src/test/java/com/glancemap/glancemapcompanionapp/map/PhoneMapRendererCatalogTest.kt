package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapRendererCatalogTest {
    @Test
    fun onlineModeUsesTheFutureMainMapProviderWithoutChangingUtilityPickers() {
        val renderer = PhoneMapRendererCatalog.rendererFor(MapMode.ONLINE)

        assertTrue(renderer.isAvailable)
        assertEquals(MapMode.ONLINE, renderer.mode)
        assertEquals("open_topo_map", renderer.rasterOnlineProvider?.id)
        assertEquals("OpenTopoMap", renderer.rasterOnlineProvider?.displayName)
        assertEquals(17, renderer.rasterOnlineProvider?.maximumZoom)
        assertEquals(renderer.rasterOnlineProvider, PhoneMapRendererCatalog.mainOnlineRasterProvider)
        assertEquals("open_street_map", PhoneMapRendererCatalog.utilityPickerRasterProvider.id)
        assertEquals("OpenStreetMap", PhoneMapRendererCatalog.utilityPickerRasterProvider.displayName)
    }

    @Test
    fun offlineModeIsAvailableWithoutAdvertisingUnsupportedCapabilities() {
        val renderer = PhoneMapRendererCatalog.rendererFor(MapMode.OFFLINE)

        assertTrue(renderer.isAvailable)
        assertTrue(!renderer.capabilities.hillshade)
        assertTrue(!renderer.capabilities.slopeOverlay)
        assertTrue(!renderer.capabilities.contoursToggle)
        assertTrue(renderer.capabilities.themes)
    }

    @Test
    fun standardOsmIsAvailableAsAnOnlineComparisonSource() {
        val sources = PhoneMapRendererCatalog.availableComparisonOnlineSources()

        assertTrue(sources.contains(PhoneOnlineMapSource.OPEN_TOPO))
        assertTrue(sources.contains(PhoneOnlineMapSource.OPEN_STREET_MAP))
        assertEquals(
            "open_street_map",
            PhoneMapRendererCatalog
                .providerForOnlineSource(PhoneOnlineMapSource.OPEN_STREET_MAP)
                ?.id,
        )
    }

    @Test
    fun planIgnV2IsAFreeRasterComparisonSource() {
        val sources = PhoneMapRendererCatalog.availableComparisonOnlineSources()
        val provider =
            requireNotNull(
                PhoneMapRendererCatalog.providerForOnlineSource(PhoneOnlineMapSource.PLAN_IGN_V2),
            )

        assertTrue(sources.contains(PhoneOnlineMapSource.PLAN_IGN_V2))
        assertEquals("plan_ign_v2", provider.id)
        assertEquals("Plan IGN V2", provider.displayName)
        assertEquals(19, provider.maximumZoom)
        assertEquals(
            "https://data.geopf.fr/wmts?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetTile&" +
                "LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&STYLE=normal&TILEMATRIXSET=PM_0_19&" +
                "TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/png",
            provider.rasterTileUrlTemplate,
        )
    }

    @Test
    fun storedOnlineSourceDefaultsToOpenTopoWhenItIsMissingOrUnknown() {
        assertEquals(PhoneOnlineMapSource.OPEN_TOPO, PhoneOnlineMapSource.fromStorageValue(null))
        assertEquals(PhoneOnlineMapSource.OPEN_TOPO, PhoneOnlineMapSource.fromStorageValue("removed_source"))
        assertEquals(
            PhoneOnlineMapSource.PLAN_IGN_V2,
            PhoneOnlineMapSource.fromStorageValue(PhoneOnlineMapSource.PLAN_IGN_V2.name),
        )
    }

    @Test
    fun supportedOnlineSourcesUseTheStableUserFacingOrder() {
        assertEquals(
            listOf(
                PhoneOnlineMapSource.OPEN_TOPO,
                PhoneOnlineMapSource.PLAN_IGN_V2,
                PhoneOnlineMapSource.OPEN_STREET_MAP,
                PhoneOnlineMapSource.CYCLOSM,
                PhoneOnlineMapSource.TRACESTRACK_TOPO,
                PhoneOnlineMapSource.SATELLITE,
            ),
            PhoneMapRendererCatalog.supportedOnlineSources(),
        )
        assertEquals("CyclOSM", PhoneMapRendererCatalog.onlineSourceLabel(PhoneOnlineMapSource.CYCLOSM))
        assertEquals("Tracestrack Topo", PhoneMapRendererCatalog.onlineSourceLabel(PhoneOnlineMapSource.TRACESTRACK_TOPO))
        assertEquals("Satellite", PhoneMapRendererCatalog.onlineSourceLabel(PhoneOnlineMapSource.SATELLITE))
    }

    @Test
    fun cyclOsmUsesTheOfficialKeylessRasterProvider() {
        val provider = requireNotNull(PhoneMapRendererCatalog.providerForOnlineSource(PhoneOnlineMapSource.CYCLOSM))

        assertEquals("cyclosm", provider.id)
        assertEquals("CyclOSM", provider.displayName)
        assertEquals(20, provider.maximumZoom)
        assertEquals("CyclOSM | © OpenStreetMap contributors", provider.attribution)
        assertEquals(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            provider.rasterTileUrlTemplate,
        )
        assertTrue(PhoneMapRendererCatalog.isOnlineSourceAvailable(PhoneOnlineMapSource.CYCLOSM))
    }

    @Test
    fun satelliteProviderFactoryRequiresAConfiguredKey() {
        assertEquals(null, mapTilerSatelliteProvider(""))
        val provider = requireNotNull(mapTilerSatelliteProvider("test-maptiler-key"))

        assertEquals("maptiler_satellite", provider.id)
        assertEquals("Satellite", provider.displayName)
        assertEquals(22, provider.maximumZoom)
        assertTrue(provider.rasterTileUrlTemplate.contains("satellite-v4/{z}/{x}/{y}.jpg"))
    }

    @Test
    fun unavailablePersistedSatelliteFallsBackWithoutChangingPreferenceValue() {
        assertEquals(
            PhoneOnlineMapSource.OPEN_TOPO,
            effectiveOnlineMapSource(PhoneOnlineMapSource.SATELLITE) { source ->
                source != PhoneOnlineMapSource.SATELLITE
            },
        )
        assertEquals(
            PhoneOnlineMapSource.SATELLITE,
            PhoneOnlineMapSource.fromStorageValue(PhoneOnlineMapSource.SATELLITE.name),
        )
    }

    @Test
    fun tracestrackProviderRequiresAConfiguredKey() {
        assertEquals(null, tracestrackTopoProvider(""))
        val provider = requireNotNull(tracestrackTopoProvider("test-tracestrack-key"))

        assertEquals("tracestrack_topo", provider.id)
        assertEquals("Tracestrack Topo", provider.displayName)
        assertEquals(19, provider.maximumZoom)
        assertEquals("Tracestrack | © OpenStreetMap contributors", provider.attribution)
        assertEquals(
            "https://tile.tracestrack.com/topo__/{z}/{x}/{y}.webp?key=test-tracestrack-key",
            provider.rasterTileUrlTemplate,
        )
    }

    @Test
    fun unavailablePersistedTracestrackFallsBackWithoutChangingPreferenceValue() {
        assertEquals(
            PhoneOnlineMapSource.OPEN_TOPO,
            effectiveOnlineMapSource(PhoneOnlineMapSource.TRACESTRACK_TOPO) { source ->
                source != PhoneOnlineMapSource.TRACESTRACK_TOPO
            },
        )
        assertEquals(
            PhoneOnlineMapSource.TRACESTRACK_TOPO,
            PhoneOnlineMapSource.fromStorageValue(PhoneOnlineMapSource.TRACESTRACK_TOPO.name),
        )
    }
}
