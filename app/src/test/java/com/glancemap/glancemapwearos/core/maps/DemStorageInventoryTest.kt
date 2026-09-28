package com.glancemap.glancemapwearos.core.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DemStorageInventoryTest {
    @Test
    fun emptyRootsReportNoTerrain() {
        val root = Files.createTempDirectory("dem-inventory-empty").toFile()

        val inventory = DemStorageInventoryScanner.scan(roots(rootMap(root)))

        assertEquals(0, inventory.forSource(DemSource.MAPZEN_SKADI_1S).renderableFileCount)
        assertEquals(0, inventory.forSource(DemSource.MAPSFORGE_DEM3).renderableFileCount)
        assertFalse(inventory.truncated)
        root.deleteRecursively()
    }

    @Test
    fun detailedAndStandardFilesAreReportedSeparately() {
        val root = Files.createTempDirectory("dem-inventory-sources").toFile()
        val detailed = File(root, "dem1").apply { mkdirs() }
        val standard = File(root, "dem3").apply { mkdirs() }
        File(detailed, "N46/N46E006.hgt.gz").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(7))
        }
        File(standard, "N46/N46E006.hgt.zip").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(11))
        }

        val inventory = DemStorageInventoryScanner.scan(roots(detailed, standard))

        assertEquals(1, inventory.forSource(DemSource.MAPZEN_SKADI_1S).renderableFileCount)
        assertEquals(7L, inventory.forSource(DemSource.MAPZEN_SKADI_1S).renderableBytes)
        assertEquals(1, inventory.forSource(DemSource.MAPSFORGE_DEM3).renderableFileCount)
        assertEquals(11L, inventory.forSource(DemSource.MAPSFORGE_DEM3).renderableBytes)
        root.deleteRecursively()
    }

    @Test
    fun partialMarkersAndEmptyFilesAreClassifiedAsNonRenderable() {
        val root = Files.createTempDirectory("dem-inventory-ignored").toFile()
        val standard = Files.createTempDirectory("dem-inventory-ignored-standard").toFile()
        File(root, ".N46E006.hgt.gz.part").writeText("partial")
        File(root, "N46E007.hgt.missing").writeText("missing")
        File(root, "N46E008.hgt").createNewFile()
        File(root, "README.txt").writeText("ignored")

        val inventory =
            DemStorageInventoryScanner
                .scan(
                    roots(
                        detailed = root,
                        standard = standard,
                    ),
                ).forSource(DemSource.MAPZEN_SKADI_1S)

        assertEquals(0, inventory.renderableFileCount)
        assertEquals(1, inventory.partialFileCount)
        assertEquals(1, inventory.missingMarkerCount)
        assertEquals(2, inventory.ignoredFileCount)
        root.deleteRecursively()
        standard.deleteRecursively()
    }

    @Test
    fun depthLimitIsReported() {
        val root = Files.createTempDirectory("dem-inventory-depth").toFile()
        var deepDirectory = root
        repeat(7) { index ->
            deepDirectory = File(deepDirectory, "level$index").apply { mkdirs() }
        }
        File(deepDirectory, "N46E006.hgt").writeBytes(ByteArray(1))

        val inventory = DemStorageInventoryScanner.scan(roots(root)).forSource(DemSource.MAPZEN_SKADI_1S)

        assertTrue(inventory.depthTruncated)
        assertFalse(inventory.fileCountTruncated)
        root.deleteRecursively()
    }

    @Test
    fun fileCountLimitIsReported() {
        val root = Files.createTempDirectory("dem-inventory-file-count").toFile()
        repeat(4097) { index ->
            File(root, "N46E${index.toString().padStart(3, '0')}.hgt").writeBytes(ByteArray(1))
        }

        val inventory = DemStorageInventoryScanner.scan(roots(root)).forSource(DemSource.MAPZEN_SKADI_1S)

        assertTrue(inventory.fileCountTruncated)
        assertFalse(inventory.depthTruncated)
        assertEquals(4096, inventory.renderableFileCount)
        root.deleteRecursively()
    }

    @Test
    fun inventoryFailureIsReportedWithoutThrowing() {
        val capture =
            DemStorageInventoryScanner.captureSafely {
                throw SecurityException("path must not be exposed")
            }

        assertEquals("error", capture.status)
        assertEquals("SecurityException", capture.errorType)
        assertNull(capture.inventory)
    }

    @Test
    fun renderableBytesSaturateInsteadOfOverflowing() {
        val accumulated = saturatingAddBytes(Long.MAX_VALUE - 1L, 2L)

        assertEquals(Long.MAX_VALUE, accumulated.first)
        assertTrue(accumulated.second)
    }

    private fun roots(
        detailed: File,
        standard: File = File(detailed.parentFile, "dem3").apply { mkdirs() },
    ): Map<DemSource, File> =
        mapOf(
            DemSource.MAPZEN_SKADI_1S to detailed,
            DemSource.MAPSFORGE_DEM3 to standard,
        )

    private fun rootMap(root: File): File = File(root, "dem1")

    private fun DemStorageInventory.forSource(source: DemSource): DemStorageSourceInventory =
        sources
            .single { inventory -> inventory.source == source }
}
