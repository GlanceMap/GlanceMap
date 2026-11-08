package com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsforgeThemeCatalogTest {
    @Test
    fun bundledThemeIdsRemainRecognized() {
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.ELEVATE_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.OPENHIKING_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.FRENCH_KISS_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.TIRAMISU_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.VOLUNTARY_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.OS_MAP_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.OS_MAP_DAY_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.OS_MAP_NIGHT_THEME_ID))
        assertTrue(MapsforgeThemeCatalog.isBundledAssetTheme(MapsforgeThemeCatalog.HIKING_3D_THEME_ID))
    }

    @Test
    fun defaultMapsforgeStyleStaysClassic() {
        assertTrue(MapsforgeThemeCatalog.isMapsforgeFamilyTheme(MapsforgeThemeCatalog.MAPSFORGE_THEME_ID))
        assertEquals("Classic", MapsforgeThemeCatalog.defaultOption().label)
        assertEquals(
            MapsforgeThemeCatalog.DEFAULT_MAPSFORGE_STYLE_ID,
            MapsforgeThemeCatalog.defaultOption().id,
        )
    }
}
