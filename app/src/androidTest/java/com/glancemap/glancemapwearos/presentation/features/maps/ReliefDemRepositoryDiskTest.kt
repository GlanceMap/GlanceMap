package com.glancemap.glancemapwearos.presentation.features.maps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class ReliefDemRepositoryDiskTest {
    private lateinit var root: File
    private lateinit var demDir: File
    private val rowLen = 9
    private val points =
        listOf(45.0, 45.07, 45.33, 45.5, 45.81, 45.999).flatMap { lat ->
            listOf(6.0, 6.11, 6.42, 6.5, 6.97).map { lon -> lat to lon }
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File.createTempFile("dem-disk-", "", context.cacheDir)
        check(root.delete() && root.mkdir())
        demDir = File(root, "dem").apply { mkdirs() }
        val samples =
            ShortArray(rowLen * rowLen) { index ->
                if (index == 40) DEM_VOID_SAMPLE else (index * 53 % 2_400 - 90).toShort()
            }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = (value.toInt() shr 8).toByte()
            bytes[index * 2 + 1] = value.toByte()
        }
        val gzip = ByteArrayOutputStream()
        GZIPOutputStream(gzip).use { it.write(bytes) }
        File(demDir, "N45E006.hgt.gz").writeBytes(gzip.toByteArray())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun diskBackedRepositoryReturnsTheSameHeightsWithoutDecodingOntoTheHeap() {
        val stats = ReliefDemCacheStats()
        val disk = ReliefDemRepository(listOf(demDir), "DemDiskTest", cacheStats = stats, diskCache = cache())
        val memory = ReliefDemRepository(listOf(demDir), "DemDiskTest")

        assertSameHeights(memory, disk)
        assertEquals(1L, stats.diskCopies.get())
        assertEquals(0L, stats.loads.get())
        assertEquals(0L, stats.retainedBytes.get())
        assertEquals(0L, stats.fallbackTiles.get())
    }

    @Test
    fun repositoryDecodesInMemoryWhenTheCopyCannotBeWritten() {
        val blocked = File(root, "blocked").apply { writeBytes(ByteArray(1)) }
        val stats = ReliefDemCacheStats()
        val disk =
            ReliefDemRepository(
                listOf(demDir),
                "DemDiskTest",
                cacheStats = stats,
                diskCache = DemDiskCache(File(blocked, "copies"), DEM_DISK_BYTES),
            )
        val memory = ReliefDemRepository(listOf(demDir), "DemDiskTest")

        assertSameHeights(memory, disk)
        assertEquals(1L, stats.fallbackTiles.get())
        assertEquals(1L, stats.loads.get())
        assertTrue(stats.retainedBytes.get() > 0L)
    }

    private fun cache() = DemDiskCache(File(root, "copies"), DEM_DISK_BYTES)

    private fun assertSameHeights(
        expected: ReliefDemRepository,
        actual: ReliefDemRepository,
    ) {
        assertEquals(
            points.map { (lat, lon) -> expected.elevationSampleAt(lat, lon) },
            points.map { (lat, lon) -> actual.elevationSampleAt(lat, lon) },
        )
        val expectedTile = checkNotNull(expected.loadDemTileFor(45.5, 6.5))
        val actualTile = checkNotNull(actual.loadDemTileFor(45.5, 6.5))
        assertEquals(
            points.map { (lat, lon) -> expected.elevationAtUsingTile(expectedTile, 45, 6, lat, lon) },
            points.map { (lat, lon) -> actual.elevationAtUsingTile(actualTile, 45, 6, lat, lon) },
        )
    }
}
