package com.glancemap.glancemapwearos.presentation.features.maps

import com.glancemap.glancemapwearos.core.maps.DemSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.tan

class ReliefTerrainMathTest {
    @Test
    fun primitiveInterpolationPreservesValuesAndVoidSamples() {
        val tile = DemTileData(1, 2, shortArrayOf(100, DEM_VOID_SAMPLE, 200, 300))
        assertEquals(200.0, interpolateDemElevationOrNaN(tile, 45, 6, 45.0, 6.0), 0.0)
        assertEquals(200.0, interpolateDemElevationOrNaN(tile, 45, 6, 45.5, 6.5), 0.0)
        assertTrue(interpolateDemElevationOrNaN(tile, 45, 6, 46.0, 7.0).isNaN())
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun bilinearInterpolationPreservesFlatTerrain() {
        val tile = DemTileData(axisLen = 1, rowLen = 2, samples = shortArrayOf(120, 120, 120, 120))

        assertEquals(120.0, interpolateDemElevation(tile, 45, 6, 45.37, 6.62) ?: Double.NaN, 0.001)
    }

    @Test
    fun bilinearInterpolationIgnoresSrtmVoidSamples() {
        val tile =
            DemTileData(
                axisLen = 1,
                rowLen = 2,
                samples = shortArrayOf(100, DEM_VOID_SAMPLE, 100, 100),
            )

        assertEquals(100.0, interpolateDemElevation(tile, 45, 6, 45.5, 6.5) ?: Double.NaN, 0.001)
    }

    @Test
    fun interpolationReturnsNoDataWhenOnlyWeightedSampleIsVoid() {
        val tile =
            DemTileData(
                axisLen = 1,
                rowLen = 2,
                samples = shortArrayOf(100, DEM_VOID_SAMPLE, 100, 100),
            )

        assertNull(interpolateDemElevation(tile, 45, 6, 46.0, 7.0))
    }

    @Test
    fun hornEstimatorMatchesKnownTerrainPlanes() {
        listOf(15.0, 30.0, 45.0).forEach { expectedSlopeDeg ->
            val risePerMeter = tan(Math.toRadians(expectedSlopeDeg))
            val slope =
                hornSlopeDegrees(
                    north = 0.0,
                    south = 0.0,
                    east = risePerMeter,
                    west = -risePerMeter,
                    northEast = risePerMeter,
                    northWest = -risePerMeter,
                    southEast = risePerMeter,
                    southWest = -risePerMeter,
                    cellX = 1.0,
                    cellY = 1.0,
                )

            assertEquals(expectedSlopeDeg, slope, 0.001)
        }
    }

    @Test
    fun demResolutionTelemetryDistinguishesDetailedAndStandardTiles() {
        assertEquals("detailed_1s", demResolutionLabel(axisLen = 3_600))
        assertEquals("standard_3s", demResolutionLabel(axisLen = 1_200))
        assertEquals("custom", demResolutionLabel(axisLen = 600))
    }

    @Test
    fun detailedSelectionFallsBackToStandardTerrain() {
        val detailedRoot = temporaryFolder.newFolder("detailed")
        val standardRoot = temporaryFolder.newFolder("standard")
        val standardTile = writeTilePlaceholder(standardRoot)

        assertEquals(standardTile, resolveDemFile(listOf(detailedRoot, standardRoot), "N45E006"))
        assertEquals(
            listOf(DemSource.MAPZEN_SKADI_1S, DemSource.MAPSFORGE_DEM3),
            DemSource.MAPZEN_SKADI_1S.readFallbackOrder(),
        )
    }

    @Test
    fun detailedTerrainRemainsPreferredWhenBothSourcesExist() {
        val detailedRoot = temporaryFolder.newFolder("detailed")
        val standardRoot = temporaryFolder.newFolder("standard")
        val detailedTile = writeTilePlaceholder(detailedRoot)
        writeTilePlaceholder(standardRoot)

        assertEquals(detailedTile, resolveDemFile(listOf(detailedRoot, standardRoot), "N45E006"))
    }

    @Test
    fun standardSelectionFallsBackToDetailedTerrain() {
        val detailedRoot = temporaryFolder.newFolder("detailed")
        val standardRoot = temporaryFolder.newFolder("standard")
        val detailedTile = writeTilePlaceholder(detailedRoot)

        assertEquals(detailedTile, resolveDemFile(listOf(standardRoot, detailedRoot), "N45E006"))
        assertEquals(
            listOf(DemSource.MAPSFORGE_DEM3, DemSource.MAPZEN_SKADI_1S),
            DemSource.MAPSFORGE_DEM3.readFallbackOrder(),
        )
    }

    private fun writeTilePlaceholder(root: File): File {
        val folder = File(root, "N45").apply { mkdirs() }
        return File(folder, "N45E006.hgt").apply { createNewFile() }
    }
}
