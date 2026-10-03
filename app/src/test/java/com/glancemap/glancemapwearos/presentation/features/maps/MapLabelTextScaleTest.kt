package com.glancemap.glancemapwearos.presentation.features.maps

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLabelTextScaleTest {
    @Test
    fun `map label sizes map to renderer text scales`() {
        assertEquals(0.80f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_SMALL), 0.0f)
        assertEquals(1.0f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_DEFAULT), 0.0f)
        assertEquals(1.50f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_LARGE), 0.0f)
        assertEquals(1.75f, mapLabelTextScale(SettingsRepository.MAP_LABEL_SIZE_EXTRA_LARGE), 0.0f)
    }

    @Test
    fun `unknown map label size keeps renderer default`() {
        assertEquals(1.0f, mapLabelTextScale("unexpected"), 0.0f)
    }

    @Test
    fun `unchanged scale does not invalidate the base tile cache`() {
        assertEquals(
            false,
            shouldRecreateBaseLayerForMapLabelTextScaleChange(
                previousTextScale = 1.0f,
                nextTextScale = 1.0f,
                hasMapPath = true,
            ),
        )
    }

    @Test
    fun `changed scale recreates the base layer when a map is active`() {
        assertEquals(
            true,
            shouldRecreateBaseLayerForMapLabelTextScaleChange(
                previousTextScale = 1.0f,
                nextTextScale = 1.75f,
                hasMapPath = true,
            ),
        )
        assertEquals(
            false,
            shouldRecreateBaseLayerForMapLabelTextScaleChange(
                previousTextScale = 1.0f,
                nextTextScale = 1.75f,
                hasMapPath = false,
            ),
        )
    }

    @Test
    fun `label scale is part of the base rendered cache identity`() {
        val defaultCacheId =
            resolveMapRendererDesiredCacheId(
                mapSignature = "MAP:demo",
                themeSignature = "THEME:elevate",
                elevationLabelsMetric = true,
                labelTextScale = 1.0f,
            )
        val extraLargeCacheId =
            resolveMapRendererDesiredCacheId(
                mapSignature = "MAP:demo",
                themeSignature = "THEME:elevate",
                elevationLabelsMetric = true,
                labelTextScale = 1.75f,
            )

        assertEquals(false, defaultCacheId == extraLargeCacheId)
        assertEquals(
            defaultCacheId,
            resolveMapRendererDesiredCacheId(
                mapSignature = "MAP:demo",
                themeSignature = "THEME:elevate",
                elevationLabelsMetric = true,
                labelTextScale = 1.00f,
            ),
        )
    }

    @Test
    fun `new layers use the selected normalized label scale`() {
        assertEquals(1.75f, mapRendererLayerTextScale(1.75f), 0.0f)
        assertEquals(1.0f, mapRendererLayerTextScale(Float.NaN), 0.0f)
    }
}
