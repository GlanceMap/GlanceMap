package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import com.glancemap.glancemapwearos.presentation.features.maps.DemTileData
import com.glancemap.glancemapwearos.presentation.features.maps.interpolateDemElevationOrNaN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.BoundingBox

class WorldWindElevationTest {
    @Test
    fun `missing nonfinite and out of range samples become void`() {
        assertEquals(Short.MIN_VALUE, worldWindHeight(null))
        assertEquals(Short.MIN_VALUE, worldWindHeight(Double.NaN))
        assertEquals(Short.MIN_VALUE, worldWindHeight(Double.POSITIVE_INFINITY))
        assertEquals(Short.MIN_VALUE, worldWindHeight(40000.0))
        assertEquals(123.toShort(), worldWindHeight(123.3))
        assertEquals((-52).toShort(), worldWindHeight(-52.0))
    }

    @Test
    fun `grid samples each source tile once and preserves boundaries and missing data`() {
        val loads = mutableListOf<Pair<Int, Int>>()
        val heights = mapOf((45 to 6) to 10, (45 to 7) to 20, (44 to 6) to 30)
        val result =
            worldWindElevationGrid(
                latitudes = doubleArrayOf(45.75, 45.25, 44.75),
                longitudes = doubleArrayOf(6.25, 7.25, 6.75),
                loadTile = { lat, lon ->
                    val key = kotlin.math.floor(lat).toInt() to kotlin.math.floor(lon).toInt()
                    loads.add(key)
                    heights[key]?.let { value -> DemTileData(1, 2, ShortArray(4) { value.toShort() }) }
                },
                sampleTile = ::interpolateDemElevationOrNaN,
                checkCancelled = {},
            )

        assertEquals(listOf(45 to 6, 45 to 7, 44 to 6, 44 to 7), loads)
        assertArrayEquals(
            shortArrayOf(10, 20, 10, 10, 20, 10, 30, Short.MIN_VALUE, 30),
            result,
        )
    }

    @Test
    fun `grid preserves interpolation and void samples`() {
        val tile = DemTileData(1, 2, shortArrayOf(0, 100, 200, 300))
        val result =
            worldWindElevationGrid(
                latitudes = doubleArrayOf(45.75, 45.25),
                longitudes = doubleArrayOf(6.25, 6.75),
                loadTile = { _, _ -> tile },
                sampleTile = ::interpolateDemElevationOrNaN,
                checkCancelled = {},
            )
        assertArrayEquals(shortArrayOf(75, 125, 175, 225), result)

        val voidTile = DemTileData(1, 2, ShortArray(4) { Short.MIN_VALUE })
        val missing =
            worldWindElevationGrid(
                latitudes = doubleArrayOf(45.75),
                longitudes = doubleArrayOf(6.25),
                loadTile = { _, _ -> voidTile },
                sampleTile = ::interpolateDemElevationOrNaN,
                checkCancelled = {},
            )
        assertArrayEquals(shortArrayOf(Short.MIN_VALUE), missing)
    }

    @Test
    fun `prewarm covers nearby tiles with the start tile first and stays inside the map`() {
        val bayern = BoundingBox(47.0, 10.0, 49.0, 13.0)
        assertEquals(listOf(47 to 11, 47 to 10), worldWindPrewarmTiles(47.3466, 11.0718, bayern))
        assertEquals(listOf(47 to 11), worldWindPrewarmTiles(47.5, 11.5, bayern))

        val corner = worldWindPrewarmTiles(47.98, 11.02, BoundingBox(46.0, 10.0, 49.0, 13.0))
        assertEquals(47 to 11, corner.first())
        assertEquals(setOf(47 to 11, 47 to 10, 48 to 11, 48 to 10), corner.toSet())

        val clipped = worldWindPrewarmTiles(47.02, 11.02, BoundingBox(47.0, 11.0, 48.0, 12.0))
        assertEquals(listOf(47 to 11), clipped)
    }

    @Test
    fun `start camera returns to ground clamping once terrain reaches the DEM sample`() {
        assertFalse(worldWindTerrainReachedStartGround(startGround = 1_480.0, terrainElevation = 0.0))
        assertFalse(worldWindTerrainReachedStartGround(startGround = 1_480.0, terrainElevation = 1_420.0))
        assertTrue(worldWindTerrainReachedStartGround(startGround = 1_480.0, terrainElevation = 1_462.0))
        assertTrue(worldWindTerrainReachedStartGround(startGround = 1_480.0, terrainElevation = 1_505.0))
    }
}
