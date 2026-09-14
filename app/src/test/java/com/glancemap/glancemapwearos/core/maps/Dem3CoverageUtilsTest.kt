package com.glancemap.glancemapwearos.core.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Dem3CoverageUtilsTest {
    @Test
    fun markerOnlyTerrainReportsZeroAvailableTilesAndIsNotReady() {
        val root = Files.createTempDirectory("dem-coverage-marker").toFile()
        File(root, "N46E006.hgt.missing").writeText("missing_upstream")

        val availableTiles =
            Dem3CoverageUtils.countRenderableTiles(
                demRoots = listOf(root),
                requiredTileIds = setOf("N46E006"),
            )
        val coverage = DemCoverageSummary(1, availableTiles, isCoverageKnown = true)

        assertEquals(0, coverage.availableTiles)
        assertFalse(coverage.isReady)
        root.deleteRecursively()
    }

    @Test
    fun nonEmptyDetailedDemTileReportsAvailableAndReady() {
        val root = Files.createTempDirectory("dem-coverage-detailed").toFile()
        File(root, "N46/N46E006.hgt.gz").apply {
            parentFile?.mkdirs()
            writeText("detailed")
        }

        val availableTiles =
            Dem3CoverageUtils.countRenderableTiles(
                demRoots = listOf(root),
                requiredTileIds = setOf("N46E006"),
            )
        val coverage = DemCoverageSummary(1, availableTiles, isCoverageKnown = true)

        assertEquals(1, coverage.availableTiles)
        assertTrue(coverage.isReady)
        root.deleteRecursively()
    }

    @Test
    fun detailedMissingTileStillFallsBackToNonEmptyStandardTile() {
        val root = Files.createTempDirectory("dem-coverage-fallback").toFile()
        val detailed = File(root, "detailed").apply { mkdirs() }
        val standard = File(root, "standard").apply { mkdirs() }
        File(detailed, "N46/N46E006.hgt.missing").apply {
            parentFile?.mkdirs()
            writeText("missing_upstream")
        }
        File(standard, "N46/N46E006.hgt.zip").apply {
            parentFile?.mkdirs()
            writeText("standard")
        }

        val detailedCoverage =
            Dem3CoverageUtils.countRenderableTiles(
                demRoots = listOf(detailed),
                requiredTileIds = setOf("N46E006"),
            )
        val combinedCoverage =
            Dem3CoverageUtils.countRenderableTiles(
                demRoots = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006"),
            )

        assertEquals(0, detailedCoverage)
        assertEquals(1, combinedCoverage)
        root.deleteRecursively()
    }
}
