package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneTerrainTest {
    @Test
    fun demTileIdsUseHgtHemisphereNaming() {
        assertEquals("N46E006", phoneDemTileId(46, 6))
        assertEquals("S01W001", phoneDemTileId(-1, -1))
    }

    @Test
    fun highResolutionHillshadeInputIsBoundedLikeWatch() {
        assertEquals(1, phoneHillshadeDownsamplingStride(1_200))
        assertEquals(2, phoneHillshadeDownsamplingStride(3_600))
        assertEquals(4, phoneHillshadeDownsamplingStride(7_200))
    }
}
