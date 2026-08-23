package com.glancemap.glancemapwearos.data.repository

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryMapZoomCompatibilityTest {
    @Test
    fun `legacy numeric zoom values convert to scale values`() {
        val preferences =
            mutablePreferencesOf(
                legacyDefaultZoomKey to 16,
                legacyMinZoomKey to 8,
                legacyMaxZoomKey to 20,
            )

        assertEquals(220, preferences.mapZoomScaleMeters(defaultScaleKey, legacyDefaultZoomKey, 200))
        assertEquals(56_419, preferences.mapZoomScaleMeters(minScaleKey, legacyMinZoomKey, 200_000))
        assertEquals(14, preferences.mapZoomScaleMeters(maxScaleKey, legacyMaxZoomKey, 20))
    }

    @Test
    fun `stored scale value takes precedence over legacy numeric zoom`() {
        val preferences =
            mutablePreferencesOf(
                legacyDefaultZoomKey to 16,
                defaultScaleKey to 5_000,
            )

        assertEquals(5_000, preferences.mapZoomScaleMeters(defaultScaleKey, legacyDefaultZoomKey, 200))
    }

    private companion object {
        val legacyDefaultZoomKey = intPreferencesKey("map_zoom_default")
        val legacyMinZoomKey = intPreferencesKey("map_zoom_min")
        val legacyMaxZoomKey = intPreferencesKey("map_zoom_max")
        val defaultScaleKey = intPreferencesKey("map_zoom_default_scale_meters")
        val minScaleKey = intPreferencesKey("map_zoom_min_scale_meters")
        val maxScaleKey = intPreferencesKey("map_zoom_max_scale_meters")
    }
}
