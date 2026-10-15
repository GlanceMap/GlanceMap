package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.model.BoundingBox
import java.io.DataOutputStream

class PhoneTerrainTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun demSamplePreservesTheWatchGridResolutionAndSeaLevelElevation() {
        val folder = temporaryFolder.newFolder()
        DataOutputStream(folder.resolve("N00E000.hgt").outputStream()).use { output ->
            repeat(9) { output.writeShort(0) }
        }
        val repository = PhoneElevationRepository(listOf(folder))

        assertEquals(PhoneDemElevationSample(0.0, axisLen = 2), repository.elevationSampleAt(0.25, 0.25))
        assertEquals(0.0, repository.elevationAt(0.25, 0.25)!!, 0.0)
    }

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
