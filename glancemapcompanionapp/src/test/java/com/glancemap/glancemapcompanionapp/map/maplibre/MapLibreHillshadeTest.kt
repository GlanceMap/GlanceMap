package com.glancemap.glancemapcompanionapp.map.maplibre

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLibreHillshadeTest {
    @Test
    fun mapterhornUsesItsPublishedTileSizeAndGlobalCoverageZoom() {
        assertEquals("https://tiles.mapterhorn.com/{z}/{x}/{y}.webp", PHONE_MAPTERHORN_TILE_URL)
        assertEquals(512, PHONE_MAPTERHORN_TILE_SIZE)
        assertEquals(12f, PHONE_MAPTERHORN_MAX_ZOOM, 0f)
    }

    @Test
    fun disabledHillshadeKeepsOneUpdateKeyWhileComparisonOpacityChanges() {
        val opacities = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        assertEquals(listOf(0f), opacities.map { phoneMapHillshadeOpacity(false, it) }.distinct())
        assertEquals(opacities, opacities.map { phoneMapHillshadeOpacity(true, it) })
        assertEquals(0f, phoneMapHillshadeOpacity(true, -1f))
        assertEquals(1f, phoneMapHillshadeOpacity(true, 2f))
    }

    @Test
    fun hillshadeColorsFollowComparisonOpacityIncludingFullyTransparent() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { opacity ->
            assertEquals("rgba(0, 0, 0, $opacity)", phoneMapHillshadeColor("0, 0, 0", opacity))
            assertEquals("rgba(255, 255, 255, $opacity)", phoneMapHillshadeColor("255, 255, 255", opacity))
        }
        assertEquals("rgba(0, 0, 0, 0.0)", phoneMapHillshadeColor("0, 0, 0", -1f))
        assertEquals("rgba(0, 0, 0, 1.0)", phoneMapHillshadeColor("0, 0, 0", 2f))
    }
}
