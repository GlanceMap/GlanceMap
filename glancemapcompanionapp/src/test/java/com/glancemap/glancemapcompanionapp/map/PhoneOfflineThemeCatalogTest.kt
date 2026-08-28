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
                PhoneOfflineThemeCatalog.ELEVATE_CYCLING_STYLE_ID,
                PhoneOfflineThemeCatalog.ELEVATE_MTB_STYLE_ID,
            ),
            styles.map(PhoneOfflineThemeStyle::id),
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
}
