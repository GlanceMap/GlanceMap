package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ReliefDemDecoderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `raw input preserves signed big endian samples and dimensions`() {
        val samples = shortArrayOf(Short.MIN_VALUE, -300, -1, 0, 1, 300, Short.MAX_VALUE, 123, -123)
        val file = writeRaw("tile.hgt", encodeSamples(samples))

        assertTile(samples, 3, readReliefDemTile(file))
    }

    @Test
    fun `gzip input preserves samples and accepts an uppercase extension`() {
        val samples = shortArrayOf(100, -100, DEM_VOID_SAMPLE, Short.MAX_VALUE)
        val file = writeRaw("tile.HGT.GZ", gzip(encodeSamples(samples)))

        assertTile(samples, 2, readReliefDemTile(file))
    }

    @Test
    fun `zip chooses the first HGT entry and skips directories and other files`() {
        val samples = shortArrayOf(100, -100, DEM_VOID_SAMPLE, 400)
        val bytes =
            zip(
                "folder/" to byteArrayOf(),
                "readme.txt" to "fixture".toByteArray(),
                "folder/FIRST.HGT" to encodeSamples(samples),
                "second.hgt" to encodeSamples(ShortArray(9) { 200 }),
            )
        val file = writeRaw("tile.HGT.ZIP", bytes)

        assertTile(samples, 2, readReliefDemTile(file))
    }

    @Test
    fun `zip without an HGT entry returns no tile`() {
        val file = writeRaw("tile.hgt.zip", zip("readme.txt" to byteArrayOf(1, 2)))

        assertNull(readReliefDemTile(file))
    }

    @Test
    fun `stored zip input preserves samples`() {
        val samples = shortArrayOf(100, -100, DEM_VOID_SAMPLE, 400)
        val file = writeStoredZip(encodeSamples(samples))

        assertTile(samples, 2, readReliefDemTile(file))
    }

    @Test
    fun `deflated zip entry with an unknown local size decodes correctly`() {
        val samples = ShortArray(9) { (it * 100 - 400).toShort() }
        val file = writeRaw("tile.hgt.zip", zip("tile.hgt" to encodeSamples(samples)))
        ZipInputStream(file.inputStream()).use { input ->
            assertEquals(-1L, input.nextEntry.size)
        }

        assertTile(samples, 3, readReliefDemTile(file))
    }

    @Test
    fun `streaming zip without a central directory remains readable`() {
        val samples = ShortArray(9) { (it * 100 - 400).toShort() }
        val output = ByteArrayOutputStream()
        val bytes =
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("tile.hgt"))
                zip.write(encodeSamples(samples))
                zip.closeEntry()
                output.toByteArray()
            }
        val file = writeRaw("tile.hgt.zip", bytes)

        assertTile(samples, 3, readReliefDemTile(file))
    }

    @Test
    fun `concatenated gzip members decode the complete sample grid`() {
        val samples = ShortArray(9) { (it * 100 - 400).toShort() }
        val payload = encodeSamples(samples)
        val bytes = gzip(payload.copyOfRange(0, 6)) + gzip(payload.copyOfRange(6, payload.size))
        val file = writeRaw("tile.hgt.gz", bytes)
        assertEquals(12L, readGzipUncompressedSize(file))

        assertTile(samples, 3, readReliefDemTile(file))
    }

    @Test
    fun `size hints cannot change decoded values or dimensions`() {
        val samples = ShortArray(9) { (it * 100 - 400).toShort() }
        val payload = encodeSamples(samples)
        listOf(-1L, 0L, 1L, 4L, 18L, 24L, Long.MAX_VALUE).forEach { hint ->
            assertTile(samples, 3, decodeReliefDemStream(ByteArrayInputStream(payload), hint))
        }
    }

    @Test
    fun `short and zero length reads preserve split sample bytes`() {
        val samples = ShortArray(81) { (it * 400 - 16_000).toShort() }
        val payload = encodeSamples(samples)
        val input =
            object : FilterInputStream(ByteArrayInputStream(payload)) {
                private var readCount = 0

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    readCount++
                    return if (readCount % 3 == 0) 0 else super.read(buffer, offset, minOf(3, length))
                }
            }

        assertTile(samples, 9, decodeReliefDemStream(input, -1L))
    }

    @Test
    fun `all formats reject empty odd incomplete and non square payloads`() {
        listOf(0, 1, 2, 3, 4, 6, 7, 9, 10, 12, 17, 19).forEach { size ->
            val payload = ByteArray(size)
            val files =
                listOf(
                    writeRaw("invalid-$size.hgt", payload),
                    writeRaw("invalid-$size.hgt.gz", gzip(payload)),
                    writeRaw("invalid-$size.hgt.zip", zip("tile.hgt" to payload)),
                )
            files.forEach { file -> assertNull(file.name, readReliefDemTile(file)) }
        }
    }

    @Test
    fun `gzip CRC corruption cannot publish a tile`() {
        val bytes = gzip(encodeSamples(shortArrayOf(100, 200, 300, 400)))
        bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 1).toByte()
        val file = writeRaw("corrupt.hgt.gz", bytes)

        assertThrows(IOException::class.java) { readReliefDemTile(file) }
    }

    @Test
    fun `truncated gzip cannot publish a tile`() {
        val bytes = gzip(encodeSamples(shortArrayOf(100, 200, 300, 400)))
        val file = writeRaw("truncated.hgt.gz", bytes.copyOf(bytes.size - 3))

        assertThrows(IOException::class.java) { readReliefDemTile(file) }
    }

    @Test
    fun `stored zip CRC corruption cannot publish a tile`() {
        val file = writeStoredZip(encodeSamples(shortArrayOf(100, 200, 300, 400)))
        val bytes = file.readBytes()
        val nameLength = (bytes[26].toInt() and 0xff) or ((bytes[27].toInt() and 0xff) shl 8)
        val extraLength = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
        val payloadOffset = 30 + nameLength + extraLength
        bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertThrows(IOException::class.java) { readReliefDemTile(file) }
    }

    @Test
    fun `truncated zip sample data cannot publish a tile`() {
        val file = writeStoredZip(encodeSamples(shortArrayOf(100, 200, 300, 400)))
        val bytes = file.readBytes()
        val nameLength = (bytes[26].toInt() and 0xff) or ((bytes[27].toInt() and 0xff) shl 8)
        val extraLength = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
        file.writeBytes(bytes.copyOf(30 + nameLength + extraLength + 6))

        assertThrows(IOException::class.java) { readReliefDemTile(file) }
    }

    @Test
    fun `decoded interpolation keeps void and negative elevation behavior for all formats`() {
        val samples = shortArrayOf(-100, DEM_VOID_SAMPLE, 100, 300)
        val payload = encodeSamples(samples)
        val files =
            listOf(
                writeRaw("terrain.hgt", payload),
                writeRaw("terrain.hgt.gz", gzip(payload)),
                writeRaw("terrain.hgt.zip", zip("tile.hgt" to payload)),
            )
        files.forEach { file ->
            val tile = requireNotNull(readReliefDemTile(file))
            assertEquals(100.0, interpolateDemElevation(tile, 45, 6, 45.5, 6.5) ?: Double.NaN, 0.001)
            assertEquals(-100.0, interpolateDemElevation(tile, 45, 6, 46.0, 6.0) ?: Double.NaN, 0.001)
            assertNull(interpolateDemElevation(tile, 45, 6, 46.0, 7.0))
        }
    }

    @Test
    fun `standard and detailed grids use bounded stream reads`() {
        listOf(1_201, 3_601).forEach { rowLen ->
            val input = GeneratedHgtInputStream(rowLen)
            val tile = requireNotNull(decodeReliefDemStream(input, rowLen.toLong() * rowLen * 2))

            assertEquals(rowLen, tile.rowLen)
            assertEquals(rowLen - 1, tile.axisLen)
            assertEquals(rowLen * rowLen, tile.samples.size)
            listOf(0, rowLen - 1, rowLen, rowLen * rowLen / 2, rowLen * rowLen - 1).forEach { index ->
                assertEquals(index.toShort(), tile.samples[index])
            }
            assertEquals(rowLen * rowLen * 2, input.bytesRead)
            assertTrue(input.maximumReadLength <= DEFAULT_BUFFER_SIZE)
        }
    }

    private fun assertTile(
        expectedSamples: ShortArray,
        expectedRowLen: Int,
        tile: DemTileData?,
    ) {
        val decoded = requireNotNull(tile)
        assertEquals(expectedRowLen, decoded.rowLen)
        assertEquals(expectedRowLen - 1, decoded.axisLen)
        assertArrayEquals(expectedSamples, decoded.samples)
    }

    private fun writeRaw(
        name: String,
        bytes: ByteArray,
    ): File = temporaryFolder.newFile(name).apply { writeBytes(bytes) }

    private fun encodeSamples(samples: ShortArray): ByteArray =
        ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { index, value ->
                bytes[index * 2] = (value.toInt() ushr 8).toByte()
                bytes[index * 2 + 1] = value.toByte()
            }
        }

    private fun gzip(payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload) }
        return output.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun writeStoredZip(payload: ByteArray): File {
        val file = temporaryFolder.newFile("stored.hgt.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            val entry =
                ZipEntry("tile.hgt").apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(payload) }.value
                }
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
        }
        return file
    }

    private class GeneratedHgtInputStream(
        rowLen: Int,
    ) : InputStream() {
        private val byteCount = rowLen * rowLen * 2
        var bytesRead = 0
            private set
        var maximumReadLength = 0
            private set

        override fun read(): Int = if (bytesRead == byteCount) -1 else sampleByte(bytesRead++).toInt() and 0xff

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            maximumReadLength = maxOf(maximumReadLength, length)
            if (bytesRead == byteCount) return -1
            val count = minOf(length, byteCount - bytesRead)
            repeat(count) { index -> buffer[offset + index] = sampleByte(bytesRead + index) }
            bytesRead += count
            return count
        }

        private fun sampleByte(index: Int): Byte {
            val value = (index / 2).toShort().toInt()
            return if (index % 2 == 0) (value ushr 8).toByte() else value.toByte()
        }
    }
}
