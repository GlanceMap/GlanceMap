package com.glancemap.glancemapcompanionapp.map.maplibre

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLibreHillshadeTest {
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
