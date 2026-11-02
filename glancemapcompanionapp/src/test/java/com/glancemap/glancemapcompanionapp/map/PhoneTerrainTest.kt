package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.model.BoundingBox
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private val gridValues = listOf<Short>(-5, 1_000, Short.MIN_VALUE, 2_000, 300, 400, 500, 600, 8_000)

    private fun hgtBytes(values: List<Short> = gridValues): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> values.forEach { output.writeShort(it.toInt()) } }
        return bytes.toByteArray()
    }

    private val samplePoints = listOf(0.9 to 0.1, 0.75 to 0.75, 0.5 to 0.5, 0.95 to 0.95, 0.1 to 0.9)

    private fun sampled(dem: PhoneElevationRepository) = samplePoints.map { (lat, lon) -> dem.elevationAt(lat, lon) }

    @Test
    fun plainGzipAndZipTilesReadTheSameBigEndianValuesWithVoids() {
        val plain = temporaryFolder.newFolder().also { it.resolve("N00E000.hgt").writeBytes(hgtBytes()) }
        val gzip =
            temporaryFolder.newFolder().also { folder ->
                GZIPOutputStream(folder.resolve("N00E000.hgt.gz").outputStream()).use { it.write(hgtBytes()) }
            }
        val zip =
            temporaryFolder.newFolder().also { folder ->
                ZipOutputStream(folder.resolve("N00E000.hgt.zip").outputStream()).use { output ->
                    output.putNextEntry(ZipEntry("readme.txt"))
                    output.putNextEntry(ZipEntry("N00E000.hgt"))
                    output.write(hgtBytes())
                }
            }
        val plainRepository = PhoneElevationRepository(listOf(plain))
        val expected = sampled(plainRepository)
        assertEquals(-5.0, plainRepository.elevationAt(0.999999, 0.000001)!!, 0.1)
        assertEquals(8_000.0, plainRepository.elevationAt(0.000001, 0.999999)!!, 0.1)
        assertEquals(300.0, expected[2]!!, 1e-9)
        for (folder in listOf(gzip, zip)) {
            assertEquals(expected, sampled(PhoneElevationRepository(listOf(folder))))
            val unpacking = PhoneElevationRepository({ listOf(folder) }, false, temporaryFolder.newFolder())
            assertEquals(expected, sampled(unpacking))
        }
    }

    @Test
    fun compressedTileIsUnpackedOnceAndReplacedWhenTheSourceChanges() {
        val folder = temporaryFolder.newFolder()
        val source = folder.resolve("N00E000.hgt.gz")
        GZIPOutputStream(source.outputStream()).use { it.write(hgtBytes()) }
        val cache = temporaryFolder.newFolder()
        val first = phoneUnpackedDemFile(source, "N00E000", cache)!!
        assertEquals(first, phoneUnpackedDemFile(source, "N00E000", cache))
        assertEquals(listOf(first.name), cache.list()!!.toList())

        GZIPOutputStream(source.outputStream()).use { it.write(hgtBytes(gridValues.map { (it + 1).toShort() })) }
        source.setLastModified(source.lastModified() + 2_000)
        val second = phoneUnpackedDemFile(source, "N00E000", cache)!!
        assertNotEquals(first.name, second.name)
        assertEquals(listOf(second.name), cache.list()!!.toList())
        val repository = PhoneElevationRepository({ listOf(folder) }, false, cache)
        assertEquals(1_001.0, repository.elevationAt(0.999999, 0.5)!!, 0.1)
    }

    @Test
    fun unpackedCacheKeepsTheNewestTilesWithinItsBudget() {
        val folder = temporaryFolder.newFolder()
        val cache = temporaryFolder.newFolder()
        val bytes = hgtBytes().size.toLong()
        val unpacked =
            listOf("N00E000", "N00E001", "N00E002").mapIndexed { index, tileId ->
                val source = folder.resolve("$tileId.hgt.gz")
                GZIPOutputStream(source.outputStream()).use { it.write(hgtBytes()) }
                phoneUnpackedDemFile(source, tileId, cache, budgetBytes = 2 * bytes)!!.also {
                    it.setLastModified(1_000_000L + index * 10_000L)
                }
            }
        assertFalse(unpacked[0].exists())
        assertTrue(unpacked[1].exists())
        assertTrue(unpacked[2].exists())
    }

    @Test
    fun preparingATileUnpacksItOnceSoLoadingOnlyMapsIt() {
        val folder = temporaryFolder.newFolder()
        GZIPOutputStream(folder.resolve("N00E000.hgt.gz").outputStream()).use { it.write(hgtBytes()) }
        folder.resolve("N00E001.hgt").writeBytes(hgtBytes())
        val cache = temporaryFolder.newFolder()
        val repository = PhoneElevationRepository({ listOf(folder) }, false, cache)
        assertTrue(repository.prepareTile(0, 0))
        assertFalse(repository.prepareTile(0, 0))
        assertFalse(repository.prepareTile(0, 1))
        assertFalse(repository.prepareTile(5, 5))
        assertEquals(1, cache.list()!!.size)
        val unpacked = cache.listFiles()!!.single()
        unpacked.setLastModified(1_000_000L)
        assertEquals(300.0, repository.elevationAt(0.5, 0.5)!!, 1e-9)
        assertEquals(1, cache.list()!!.size)
        assertFalse(PhoneElevationRepository(listOf(folder)).prepareTile(0, 0))
    }

    @Test
    fun corruptOrEmptyCompressedTileIsNotUnpacked() {
        val folder = temporaryFolder.newFolder()
        val cache = temporaryFolder.newFolder()
        val noEntry = folder.resolve("N00E000.hgt.zip")
        ZipOutputStream(noEntry.outputStream()).use { it.putNextEntry(ZipEntry("readme.txt")) }
        assertNull(phoneUnpackedDemFile(noEntry, "N00E000", cache))
        val oddSize = folder.resolve("N00E001.hgt.gz")
        GZIPOutputStream(oddSize.outputStream()).use { it.write(ByteArray(10)) }
        assertNull(phoneUnpackedDemFile(oddSize, "N00E001", cache))
        assertEquals(emptyList<String>(), cache.list()!!.toList())
        assertNull(PhoneElevationRepository({ listOf(folder) }, false, cache).elevationAt(0.5, 1.5))
    }
}
