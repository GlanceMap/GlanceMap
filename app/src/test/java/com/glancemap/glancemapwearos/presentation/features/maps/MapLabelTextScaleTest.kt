package com.glancemap.glancemapwearos.presentation.features.maps

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLabelTextScaleTest {
    @Test
    fun `map label sizes map to renderer text scales`() {
        assertEquals(0.85f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_SMALL), 0.0f)
        assertEquals(1.0f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_DEFAULT), 0.0f)
        assertEquals(1.15f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_LARGE), 0.0f)
        assertEquals(1.30f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_EXTRA_LARGE), 0.0f)
    }

    @Test
    fun `unknown map label size keeps renderer default`() {
        assertEquals(1.0f, mapLabelTextScale("unexpected"), 0.0f)
    }
}
