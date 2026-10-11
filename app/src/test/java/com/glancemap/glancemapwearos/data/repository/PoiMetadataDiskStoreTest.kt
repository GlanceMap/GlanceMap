package com.glancemap.glancemapwearos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32

class PoiMetadataDiskStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun signature(file: File): PoiMetadataSignature {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return PoiMetadataSignature(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    }

    @Test
    fun `corrupt truncated and oversized records are discarded without changing the source`() {
        val file = temporaryFolder.newFile("source.poi").apply { writeText("source data") }
        val directory = temporaryFolder.newFolder()
        val store = PoiMetadataDiskStore(directory, PoiPointCountMetadataCodec)
        val signature = signature(file)
        val mutations: List<(ByteArray) -> ByteArray> =
            listOf(
                { bytes ->
                    bytes.apply {
                        val index = lastIndex - Long.SIZE_BYTES
                        this[index] = (this[index].toInt() xor 1).toByte()
                    }
                },
                { bytes -> bytes.copyOf(bytes.size - 3) },
                { bytes -> bytes + byteArrayOf(1) },
                { ByteArray(256 * 1024 + 1) },
            )
        mutations.forEach { mutate ->
            store.write(file, emptySet(), signature, 8)
            val record = directory.listFiles().orEmpty().single()
            record.writeBytes(mutate(record.readBytes()))
            assertNull(store.read(file, emptySet(), signature))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals("source data", file.readText())
        }
    }

    @Test
    fun `unknown schema with valid checksum still triggers a miss`() {
        val file = temporaryFolder.newFile("version.poi")
        val directory = temporaryFolder.newFolder()
        val store = PoiMetadataDiskStore(directory, PoiPointCountMetadataCodec)
        val signature = signature(file)
        store.write(file, emptySet(), signature, 8)
        val record = directory.listFiles().orEmpty().single()
        val bytes = record.readBytes()
        ByteBuffer.wrap(bytes).putInt(Int.MAX_VALUE)
        val payloadSize = bytes.size - Long.SIZE_BYTES
        val checksum = CRC32().apply { update(bytes, 0, payloadSize) }.value
        ByteBuffer.wrap(bytes, payloadSize, Long.SIZE_BYTES).putLong(checksum)
        record.writeBytes(bytes)
        assertNull(store.read(file, emptySet(), signature))
    }

    @Test
    fun `missing file identity disables persistence`() {
        val file = temporaryFolder.newFile("identity.poi")
        val directory = temporaryFolder.newFolder()
        val store = PoiMetadataDiskStore(directory, PoiPointCountMetadataCodec)
        val signature = signature(file).copy(fileKey = null)
        store.write(file, emptySet(), signature, 8)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNull(store.read(file, emptySet(), signature))
    }

    @Test
    fun `failed encoding cleans temporary files and preserves previous complete record`() {
        val file = temporaryFolder.newFile("failure.poi")
        val directory = temporaryFolder.newFolder()
        val signature = signature(file)
        val store = PoiMetadataDiskStore(directory, PoiPointCountMetadataCodec)
        store.write(file, emptySet(), signature, 8)
        val failingCodec =
            object : PoiMetadataCodec<Int> {
                override fun read(input: DataInputStream): Int = input.readInt()

                override fun write(
                    output: DataOutputStream,
                    value: Int,
                ) {
                    output.writeInt(value)
                    error("Failed encoding")
                }
            }
        PoiMetadataDiskStore(directory, failingCodec).write(file, emptySet(), signature, 9)
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertEquals(8, store.read(file, emptySet(), signature)?.value)
        store.write(file, emptySet(), signature, 10)
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertEquals(10, store.read(file, emptySet(), signature)?.value)
    }
}
