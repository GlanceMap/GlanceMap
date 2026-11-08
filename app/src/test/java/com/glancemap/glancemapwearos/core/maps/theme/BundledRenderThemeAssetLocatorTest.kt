package com.glancemap.glancemapwearos.core.maps.theme

import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BundledRenderThemeAssetLocatorTest {
    @Test
    fun resolvesKnownBundledThemeIdsWithoutFallback() {
        assertEquals(
            MapsforgeThemeCatalog.ELEVATE_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.ELEVATE_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.OPENHIKING_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.OPENHIKING_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.FRENCH_KISS_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.FRENCH_KISS_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.HIKING_3D_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.HIKING_3D_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.TIRAMISU_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.TIRAMISU_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.VOLUNTARY_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.VOLUNTARY_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.OS_MAP_DAY_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.OS_MAP_DAY_THEME_ID),
        )
        assertEquals(
            MapsforgeThemeCatalog.OS_MAP_NIGHT_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.OS_MAP_NIGHT_THEME_ID),
        )
    }

    @Test
    fun blankThemeIdDefaultsToElevateButUnknownIdsDoNot() {
        assertEquals(
            MapsforgeThemeCatalog.ELEVATE_THEME_ID,
            BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(""),
        )
        assertNull(BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull("mapsforge"))
        assertNull(BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull(MapsforgeThemeCatalog.OS_MAP_THEME_ID))
        assertNull(BundledRenderThemeAssetLocator.resolveKnownThemeIdOrNull("frenchkiss-legacy"))
    }
}
