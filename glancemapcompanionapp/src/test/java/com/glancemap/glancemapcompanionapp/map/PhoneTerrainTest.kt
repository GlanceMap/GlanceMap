package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.BoundingBox

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

    @Test
    fun demBoundsUseOnlyTilesIntersectingTheMap() {
        val tileIds =
            phoneDemTileIdsForBounds(
                BoundingBox(
                    45.2,
                    6.1,
                    46.8,
                    7.9,
                ),
            )

        assertEquals(4, tileIds.size)
        assertTrue("N45E006" in tileIds)
        assertTrue("N46E007" in tileIds)
    }
}
