package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOfflineThemeCatalogTest {
    @Test
    fun defaultThemeUsesEstablishedElevateHikingStyle() {
        assertEquals(
            PhoneOfflineThemeConfig(
                themeId = PhoneOfflineThemeCatalog.ELEVATE_THEME_ID,
                styleId = PhoneOfflineThemeCatalog.ELEVATE_HIKING_STYLE_ID,
            ),
            PhoneOfflineThemeCatalog.defaultConfig,
        )
    }

    @Test
    fun elevationStylesKeepTheirStableXmlIds() {
        val styles = PhoneOfflineThemeCatalog.themeFor(PhoneOfflineThemeCatalog.ELEVATE_THEME_ID).styles

        assertEquals(
            listOf(
                PhoneOfflineThemeCatalog.ELEVATE_HIKING_STYLE_ID,
                PhoneOfflineThemeCatalog.ELEVATE_CITY_STYLE_ID,
                PhoneOfflineThemeCatalog.ELEVATE_CYCLING_STYLE_ID,
                PhoneOfflineThemeCatalog.ELEVATE_MTB_STYLE_ID,
            ),
            styles.map(PhoneOfflineThemeStyle::id),
        )
    }

    @Test
    fun catalogIncludesWatchThemeFamiliesAndStyles() {
        assertEquals(
            listOf(
                PhoneOfflineThemeCatalog.ELEVATE_THEME_ID,
                PhoneOfflineThemeCatalog.ELEVATE_WINTER_THEME_ID,
                PhoneOfflineThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID,
                PhoneOfflineThemeCatalog.VOLUNTARY_THEME_ID,
                PhoneOfflineThemeCatalog.OS_MAP_THEME_ID,
                PhoneOfflineThemeCatalog.OPENHIKING_THEME_ID,
                PhoneOfflineThemeCatalog.FRENCH_KISS_THEME_ID,
                PhoneOfflineThemeCatalog.TIRAMISU_THEME_ID,
                PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID,
            ),
            PhoneOfflineThemeCatalog.themes.map(PhoneOfflineTheme::id),
        )
        assertEquals(
            6,
            PhoneOfflineThemeCatalog
                .themeFor(PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID)
                .styles
                .size,
        )
        assertEquals(
            "${PhoneOfflineThemeCatalog.OS_MAP_NIGHT_STYLE_PREFIX}os-landranger",
            PhoneOfflineThemeCatalog
                .themeFor(PhoneOfflineThemeCatalog.OS_MAP_THEME_ID)
                .styles
                .first { style -> style.label.contains("Landranger") && style.label.startsWith("Night") }
                .id,
        )
    }

    @Test
    fun invalidSavedThemeOrStyleFallsBackToThemeDefault() {
        assertEquals(
            PhoneOfflineThemeCatalog.defaultConfig,
            PhoneOfflineThemeCatalog.resolve("missing", "missing"),
        )
        assertEquals(
            PhoneOfflineThemeConfig(
                PhoneOfflineThemeCatalog.ELEVATE_THEME_ID,
                PhoneOfflineThemeCatalog.ELEVATE_HIKING_STYLE_ID,
            ),
            PhoneOfflineThemeCatalog.resolve(PhoneOfflineThemeCatalog.ELEVATE_THEME_ID, "missing"),
        )
    }

    @Test
    fun mapsforgeFallbackThemeRetainsStableStyleSelection() {
        val config =
            PhoneOfflineThemeCatalog.resolve(
                PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID,
                PhoneOfflineThemeCatalog.MAPSFORGE_DARK_STYLE_ID,
            )

        assertEquals(PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID, config.themeId)
        assertEquals(PhoneOfflineThemeCatalog.MAPSFORGE_DARK_STYLE_ID, config.styleId)
        assertTrue(config != PhoneOfflineThemeCatalog.defaultConfig)
    }

    @Test
    fun elevateFileResourcesResolveInsideBundledThemeDirectory() {
        assertEquals(
            "theme/elevate/ele-res/s_peak.svg",
            resolvePhoneOfflineThemeAssetPath("theme/elevate/", "file:ele-res/s_peak.svg"),
        )
        assertEquals(
            "theme/elevate/ele-res/s_city.svg",
            resolvePhoneOfflineThemeAssetPath("theme/elevate/", "file:ele-res/s_city.svg"),
        )
    }

    @Test
    fun nonFileResourcesRemainWithMapsforgeDefaultResolution() {
        assertEquals(
            null,
            resolvePhoneOfflineThemeAssetPath("theme/elevate/", "jar:ele-res/s_peak.svg"),
        )
    }
}
