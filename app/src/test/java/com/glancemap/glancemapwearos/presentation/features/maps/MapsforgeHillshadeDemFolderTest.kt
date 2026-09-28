package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.map.layer.hills.DemFile
import org.mapsforge.map.layer.hills.HgtFileInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class MapsforgeHillshadeDemFolderTest {
    @Test
    fun folderExposesOnlyDemTilesRequiredByCurrentMap() {
        val root = Files.createTempDirectory("hillshade-folder").toFile()
        File(root, "N46/N46E006.hgt").apply {
            parentFile?.mkdirs()
            writeText("required")
        }
        File(root, "N46/N46E007.hgt").writeText("unrelated")
        File(root, "N47/N47E006.hgt.gz").apply {
            parentFile?.mkdirs()
            writeText("unrelated")
        }

        val files =
            MapsforgeHillshadeDemFolder(
                demRootDirs = listOf(root),
                requiredTileIds = setOf("n46e006"),
            ).files()

        assertEquals(listOf("N46E006.hgt"), files.map { demFile -> demFile.name })
        root.deleteRecursively()
    }

    @Test
    fun detailedWithoutRealTilesFallsBackToStandardRootOnly() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N46/N46E006.hgt.missing").apply {
            parentFile?.mkdirs()
            writeText("missing_upstream")
        }
        File(detailed, "N46/.N46E006.hgt.gz.part").apply {
            parentFile?.mkdirs()
            writeText("partial")
        }
        File(standard, "N46/N46E006.hgt.zip").apply {
            parentFile?.mkdirs()
            writeText("standard")
        }

        val resolved = resolveHillshadeDemRootDirs(listOf(detailed, standard))

        assertEquals(listOf(standard), resolved)
        root.deleteRecursively()
    }

    @Test
    fun detailedAndStandardRootsRemainInPreferenceOrderWhenBothHaveTiles() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N46E006.hgt.gz").writeText("detailed")
        File(standard, "N46E006.hgt.zip").writeText("standard")

        val resolved = resolveHillshadeDemRootDirs(listOf(detailed, standard))

        assertEquals(listOf(detailed, standard), resolved)
        root.deleteRecursively()
    }

    @Test
    fun markerOnlyRootsAreNotRenderable() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        File(root, "N46E006.hgt.missing").writeText("missing_upstream")

        val resolved = resolveHillshadeDemRootDirs(listOf(root))

        assertTrue(resolved.isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun unrelatedDetailedTilesDoNotBlockStandardFallbackForCurrentMap() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N45/N45E005.hgt.gz").apply {
            parentFile?.mkdirs()
            writeText("unrelated-detailed")
        }
        File(standard, "N46/N46E006.hgt.zip").apply {
            parentFile?.mkdirs()
            writeText("standard")
        }

        val resolved =
            resolveHillshadeDemRootDirs(
                demRootDirs = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006"),
            )

        assertEquals(listOf(standard), resolved)
        root.deleteRecursively()
    }

    @Test
    fun partialDetailedCoverageUsesStandardPerTileFallback() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N46E006.hgt.gz").writeText("detailed")
        File(standard, "N46E006.hgt.zip").writeText("standard-1")
        File(standard, "N46E007.hgt.zip").writeText("standard-2")

        val resolved =
            resolveHillshadeDemRootDirs(
                demRootDirs = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006", "N46E007"),
            )

        assertEquals(listOf(detailed, standard), resolved)
        root.deleteRecursively()
    }

    @Test
    fun completeDetailedCoverageRemainsPreferredForCurrentMap() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N46E006.hgt.gz").writeText("detailed-1")
        File(detailed, "N46E007.hgt.gz").writeText("detailed-2")
        File(standard, "N46E006.hgt.zip").writeText("standard-1")
        File(standard, "N46E007.hgt.zip").writeText("standard-2")

        val resolved =
            resolveHillshadeDemRootDirs(
                demRootDirs = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006", "N46E007"),
            )

        assertEquals(listOf(detailed), resolved)
        root.deleteRecursively()
    }

    @Test
    fun visibleCoverageCountsDetailedFallbackAndMissingCells() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N46E006.hgt.gz").writeText("detailed")
        File(standard, "N46E006.hgt.zip").writeText("shadowed-standard")
        File(standard, "N46E007.hgt.zip").writeText("fallback-standard")

        val coverage =
            resolveVisibleHillshadeTerrainCoverage(
                demRootDirs = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006", "N46E007", "N46E008"),
            )

        assertEquals(1, coverage.detailedTileCount)
        assertEquals(1, coverage.standardFallbackTileCount)
        assertEquals(1, coverage.missingTileCount)
        assertEquals(2, coverage.availableTileCount)
        assertTrue(coverage.hasAnyTerrain)
        root.deleteRecursively()
    }

    @Test
    fun visibleCoverageReportsNoTerrainWhenNeitherSourceCoversTiles() {
        val root = Files.createTempDirectory("hillshade-no-coverage").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }

        val coverage =
            resolveVisibleHillshadeTerrainCoverage(
                demRootDirs = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006"),
            )

        assertEquals(0, coverage.availableTileCount)
        assertEquals(1, coverage.missingTileCount)
        assertFalse(coverage.hasAnyTerrain)
        root.deleteRecursively()
    }

    @Test
    fun emptyDetailedCellDoesNotShadowStandardFallback() {
        val root = Files.createTempDirectory("hillshade-roots").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N46E006.hgt").createNewFile()
        File(standard, "N46E006.hgt").writeText("fallback-standard")

        val coverage =
            resolveVisibleHillshadeTerrainCoverage(
                demRootDirs = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006"),
            )
        val demFiles =
            MapsforgeHillshadeDemFolder(
                demRootDirs = listOf(detailed, standard),
                requiredTileIds = setOf("N46E006"),
            ).files()

        assertEquals(0, coverage.detailedTileCount)
        assertEquals(1, coverage.standardFallbackTileCount)
        assertEquals(1, demFiles.count())
        root.deleteRecursively()
    }

    @Test
    fun detailedDemIsDownsampledBeforeMapsforgeCalculatesItsGrid() {
        val detailed = InMemoryDemFile(axisLen = 6)

        val limited = limitHillshadeDemFileInput(detailed, maxAxisLen = 3)

        assertEquals(2, hillshadeInputDownsamplingStride(sourceAxisLen = 6, maxAxisLen = 3))
        assertEquals(3, HgtFileInfo.computeAxisLen(limited.size))
        assertEquals(
            listOf(0, 2, 4, 6, 14, 16, 18, 20, 28, 30, 32, 34, 42, 44, 46, 48),
            limited.asRawStream().readBytes().toHgtSamples(),
        )
    }

    @Test
    fun standardDemIsNotDownsampled() {
        val standard = InMemoryDemFile(axisLen = 3)

        assertSame(standard, limitHillshadeDemFileInput(standard, maxAxisLen = 3))
    }

    private class InMemoryDemFile(
        axisLen: Int,
    ) : DemFile {
        private val bytes =
            ByteArray((axisLen + 1) * (axisLen + 1) * 2).also { output ->
                repeat((axisLen + 1) * (axisLen + 1)) { sample ->
                    output[sample * 2] = (sample shr 8).toByte()
                    output[(sample * 2) + 1] = sample.toByte()
                }
            }

        override fun getName(): String = "N00E000.hgt"

        override fun getSize(): Long = bytes.size.toLong()

        override fun openInputStream(bufferSize: Int): InputStream = ByteArrayInputStream(bytes)

        override fun asStream(): InputStream = ByteArrayInputStream(bytes)

        override fun asRawStream(): InputStream = ByteArrayInputStream(bytes)
    }
}

private fun ByteArray.toHgtSamples(): List<Int> =
    indices
        .step(2)
        .map { index ->
            ((this[index].toInt() and 0xff) shl 8) or (this[index + 1].toInt() and 0xff)
        }
