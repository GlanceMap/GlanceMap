package com.glancemap.glancemapwearos.data.repository

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

internal interface PoiMetadataCodec<T> {
    fun read(input: DataInputStream): T

    fun write(
        output: DataOutputStream,
        value: T,
    )
}

internal data class PoiStoredMetadata<T>(
    val value: T,
)

internal class PoiMetadataDiskStore<T>(
    private val directory: File,
    private val codec: PoiMetadataCodec<T>,
    private val maxEntries: Int = 64,
) {
    companion object {
        // Bump when the stored format or category/coverage interpretation changes.
        private const val SCHEMA_VERSION = 1
        private const val MAX_ENTRY_BYTES = 256L * 1024L
    }

    init {
        require(maxEntries > 0)
    }

    fun read(
        file: File,
        categoryIds: Set<Int>,
        signature: PoiMetadataSignature,
    ): PoiStoredMetadata<T>? {
        val target = recordFile(file, categoryIds)
        if (signature.fileKey == null || !target.isFile) return null
        return runCatching {
            require(target.length() in 1..MAX_ENTRY_BYTES)
            val bytes = target.readBytes()
            val payloadSize = bytes.size - Long.SIZE_BYTES
            require(payloadSize > 0)
            val checksum = CRC32().apply { update(bytes, 0, payloadSize) }.value
            require(ByteBuffer.wrap(bytes, payloadSize, Long.SIZE_BYTES).long == checksum)
            val value =
                DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { input ->
                    require(input.readInt() == SCHEMA_VERSION)
                    require(input.readUTF() == file.absolutePath)
                    require(input.readUTF() == categoryKey(categoryIds))
                    require(input.readLong() == signature.size)
                    require(input.readUTF() == signature.modified.toString())
                    require(input.readUTF() == signature.fileKey.toString())
                    codec.read(input).also { require(input.read() == -1) }
                }
            target.setLastModified(System.currentTimeMillis())
            PoiStoredMetadata(value)
        }.getOrElse {
            target.delete()
            null
        }
    }

    fun write(
        file: File,
        categoryIds: Set<Int>,
        signature: PoiMetadataSignature,
        value: T,
    ) {
        if (signature.fileKey == null) return
        var temporary: File? = null
        // Derived metadata is optional: a full/unwritable cache must not reject a readable POI file.
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val target = recordFile(file, categoryIds)
            val pending = File.createTempFile("poi-metadata-", ".tmp", directory)
            temporary = pending
            DataOutputStream(pending.outputStream().buffered()).use { output ->
                output.writeInt(SCHEMA_VERSION)
                output.writeUTF(file.absolutePath)
                output.writeUTF(categoryKey(categoryIds))
                output.writeLong(signature.size)
                output.writeUTF(signature.modified.toString())
                output.writeUTF(signature.fileKey.toString())
                codec.write(output, value)
            }
            check(pending.length() <= MAX_ENTRY_BYTES - Long.SIZE_BYTES)
            val checksum = CRC32().apply { update(pending.readBytes()) }.value
            DataOutputStream(FileOutputStream(pending, true)).use { it.writeLong(checksum) }
            check(pending.renameTo(target))
            trim(target)
        }
        temporary?.delete()
    }

    fun invalidate(file: File) {
        val prefix = "${digest(file.absolutePath)}-"
        runCatching {
            records().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
        }
    }

    private fun trim(current: File) {
        records()
            .filter { it != current }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            .drop(maxEntries - 1)
            .forEach { it.delete() }
    }

    private fun records(): List<File> = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") }

    private fun recordFile(
        file: File,
        categoryIds: Set<Int>,
    ): File = File(directory, "${digest(file.absolutePath)}-${digest(categoryKey(categoryIds))}.bin")

    private fun categoryKey(categoryIds: Set<Int>): String = categoryIds.sorted().joinToString(",")

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0xf])
            }
        }
    }
}
