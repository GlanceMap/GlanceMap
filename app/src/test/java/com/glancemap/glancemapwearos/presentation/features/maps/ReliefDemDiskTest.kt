package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReliefDemDiskTest {
    @get:Rule val temp = TemporaryFolder()
    private val stats = ReliefDemCacheStats()

    private val rowLen = 7
    private val samples =
        ShortArray(rowLen * rowLen) { index ->
            if (index == 17) DEM_VOID_SAMPLE else (index * 37 % 1_900 - 120).toShort()
        }
    private val hgtBytes =
        ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { index, value ->
                bytes[index * 2] = (value.toInt() shr 8).toByte()
                bytes[index * 2 + 1] = value.toByte()
            }
        }

    @Test
    fun `mapped tile heights match the in-memory decoder for raw gzip and zip sources`() {
        val decoded = checkNotNull(decodeReliefDemStream(hgtBytes.inputStream(), hgtBytes.size.toLong()))
        val cache = DemDiskCache(temp.newFolder("cache"), Long.MAX_VALUE)
        val sources =
            listOf(
                temp.newFile("N45E006.hgt").apply { writeBytes(hgtBytes) },
                temp.newFile("N45E006.hgt.gz").apply { writeBytes(gzip(hgtBytes)) },
                temp.newFile("N45E006.hgt.zip").apply { writeBytes(zip("N45E006.hgt", hgtBytes)) },
            )
        val points =
            listOf(45.0, 45.013, 45.31, 45.5, 45.77, 45.999, 46.0).flatMap { lat ->
                listOf(6.0, 6.021, 6.4, 6.66, 6.93, 7.0).map { lon -> lat to lon }
            }
        val expected = points.map { (lat, lon) -> interpolateDemElevationOrNaN(decoded, 45, 6, lat, lon) }
        for (source in sources) {
            val tile = checkNotNull(mapRawDemTile(checkNotNull(cache.rawFileFor(source, stats))))
            assertEquals(decoded.axisLen, tile.axisLen)
            val actual = points.map { (lat, lon) -> tile.elevationOrNaN(45, 6, lat, lon) }
            // Compared as lists so NaN (void) results also have to match exactly.
            assertEquals(expected, actual)
        }
        assertEquals(3L, stats.diskCopies.get())
        assertEquals(3L * hgtBytes.size, stats.diskCopyBytes.get())
    }

    @Test
    fun `a tile is uncompressed once and then reused from disk`() {
        val cache = DemDiskCache(temp.newFolder("cache"), Long.MAX_VALUE)
        val source = temp.newFile("N45E006.hgt.gz").apply { writeBytes(gzip(hgtBytes)) }

        val first = cache.rawFileFor(source, stats)
        val second = cache.rawFileFor(source, stats)

        assertEquals(first, second)
        assertEquals(1L, stats.diskCopies.get())
        assertEquals(1L, stats.diskHits.get())
    }

    @Test
    fun `a changed source gets a fresh copy`() {
        val cache = DemDiskCache(temp.newFolder("cache"), Long.MAX_VALUE)
        val source = temp.newFile("N45E006.hgt").apply { writeBytes(hgtBytes) }
        val first = cache.rawFileFor(source, stats)
        source.writeBytes(hgtBytes + hgtBytes.copyOf(2 * (2 * rowLen + 1)))

        val second = cache.rawFileFor(source, stats)

        assertFalse(first == second)
        assertEquals(2L, stats.diskCopies.get())
    }

    @Test
    fun `least recently used copies are removed above the byte limit but the newest stays`() {
        val dir = temp.newFolder("cache")
        val cache = DemDiskCache(dir, hgtBytes.size * 2L)
        val copies =
            listOf("N45E006", "N45E007").map { id ->
                checkNotNull(cache.rawFileFor(temp.newFile("$id.hgt").apply { writeBytes(hgtBytes) }))
            }
        copies[0].setLastModified(1_000_000)
        copies[1].setLastModified(2_000_000)

        val newest = checkNotNull(cache.rawFileFor(temp.newFile("N46E006.hgt").apply { writeBytes(hgtBytes) }))

        assertFalse(copies[0].exists())
        assertTrue(copies[1].exists())
        assertTrue(newest.exists())

        val lastSource = temp.newFile("N47E006.hgt").apply { writeBytes(hgtBytes) }
        val kept = checkNotNull(DemDiskCache(dir, 1L).rawFileFor(lastSource))
        assertEquals(listOf(kept), dir.listFiles()!!.toList())
    }

    @Test
    fun `a zip without an hgt entry or a non-square file cannot be mapped`() {
        val cache = DemDiskCache(temp.newFolder("cache"), Long.MAX_VALUE)
        val emptyZip = temp.newFile("N45E006.hgt.zip").apply { writeBytes(zip("readme.txt", ByteArray(4))) }
        assertNull(cache.rawFileFor(emptyZip))

        val odd = temp.newFile("N45E007.hgt").apply { writeBytes(ByteArray(6)) }
        assertNull(mapRawDemTile(checkNotNull(cache.rawFileFor(odd))))
        val square = temp.newFile("N45E008.hgt").apply { writeBytes(hgtBytes) }
        assertNotNull(mapRawDemTile(checkNotNull(cache.rawFileFor(square))))
    }

    @Test
    fun `clear removes all copies`() {
        val dir = temp.newFolder("cache")
        val cache = DemDiskCache(dir, Long.MAX_VALUE)
        cache.rawFileFor(temp.newFile("N45E006.hgt").apply { writeBytes(hgtBytes) })

        cache.clear()

        assertEquals(0, dir.listFiles()!!.size)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun zip(
        name: String,
        bytes: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }.toByteArray()
}
