package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Resources
import btools.util.Crc32
import com.glancemap.glancemapwearos.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Performs the lightweight index validation BRouter runs when opening an RD5 file.
 * It reads only the fixed header and trailing index metadata, not the complete segment.
 * Keep every existing validation guard while localizing its failure.
 */
@Suppress("ThrowsCount")
internal fun validateRoutingSegmentIndex(
    file: File,
    resources: Resources,
) {
    if (!file.exists() || !file.isFile || file.length() <= 0L) {
        throw IOException(resources.getString(R.string.download_routing_segment_is_missing_or_empty))
    }

    RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(ROUTING_INDEX_HEADER_BYTES)
        input.readFully(header)
        val indexCrc = Crc32.crc(header, 0, header.size)
        val index = ByteBuffer.wrap(header)
        var trailingIndexPosition = 0L
        repeat(ROUTING_INDEX_ENTRY_COUNT) { entry ->
            val value = index.long
            if (entry == ROUTING_INDEX_ENTRY_COUNT - 1) {
                trailingIndexPosition = value and ROUTING_INDEX_POSITION_MASK
            }
        }

        if (file.length() == trailingIndexPosition) return
        var extraLength = ROUTING_TRAILING_INDEX_BYTES
        if (file.length() - trailingIndexPosition > extraLength) extraLength += 1
        if (file.length() < trailingIndexPosition + extraLength) {
            throw IOException(resources.getString(R.string.download_routing_segment_is_truncated))
        }

        val trailer = ByteArray(extraLength)
        input.seek(trailingIndexPosition)
        input.readFully(trailer)
        val trailerReader = ByteBuffer.wrap(trailer)
        trailerReader.long // creation time
        val storedIndexCrc = trailerReader.int
        if (storedIndexCrc != indexCrc && (storedIndexCrc xor 2) != indexCrc) {
            throw IOException(resources.getString(R.string.download_routing_segment_index_checksum_failed))
        }
    }
}

// Keep every existing validation guard and its typed, localizable failure.
@Suppress("ThrowsCount")
internal fun validateGzipDemStructure(
    file: File,
    resources: Resources,
) {
    if (!file.exists() || !file.isFile || file.length() < GZIP_MINIMUM_BYTES) {
        throw IOException(resources.getString(R.string.download_dem_tile_is_missing_empty_or_truncated))
    }
    RandomAccessFile(file, "r").use { input ->
        if (input.readUnsignedByte() != GZIP_MAGIC_FIRST || input.readUnsignedByte() != GZIP_MAGIC_SECOND) {
            throw IOException(resources.getString(R.string.download_dem_tile_has_an_invalid_gzip_header))
        }
        input.seek(file.length() - Int.SIZE_BYTES)
        val sizeBytes = ByteArray(Int.SIZE_BYTES)
        input.readFully(sizeBytes)
        val uncompressedSize =
            ByteBuffer
                .wrap(sizeBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and 0xFFFFFFFFL
        if (!isPlausibleHgtByteSize(uncompressedSize)) {
            throw IOException(resources.getString(R.string.download_dem_tile_has_an_invalid_uncompressed_size))
        }
    }
}

private fun isPlausibleHgtByteSize(size: Long): Boolean {
    if (size <= 0L || size % Short.SIZE_BYTES != 0L) return false
    val sampleCount = size / Short.SIZE_BYTES
    val rowLength = sqrt(sampleCount.toDouble()).toInt()
    return rowLength * rowLength.toLong() == sampleCount && rowLength in 1201..3601
}

private const val ROUTING_INDEX_ENTRY_COUNT = 25
private const val ROUTING_INDEX_HEADER_BYTES = ROUTING_INDEX_ENTRY_COUNT * Long.SIZE_BYTES
private const val ROUTING_TRAILING_INDEX_BYTES = Long.SIZE_BYTES + (26 * Int.SIZE_BYTES)
private const val ROUTING_INDEX_POSITION_MASK = 0x0000FFFFFFFFFFFFL
private const val GZIP_MINIMUM_BYTES = 18L
private const val GZIP_MAGIC_FIRST = 0x1F
private const val GZIP_MAGIC_SECOND = 0x8B
