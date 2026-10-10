package com.glancemap.glancemapwearos.presentation.features.maps

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

internal fun readReliefDemTile(file: File): DemTileData? =
    when {
        file.name.endsWith(".zip", ignoreCase = true) -> readZipDemTile(file)
        file.name.endsWith(".gz", ignoreCase = true) ->
            GZIPInputStream(file.inputStream().buffered()).use { input ->
                decodeReliefDemStream(input, readGzipUncompressedSize(file))
            }
        else -> file.inputStream().use { input -> decodeReliefDemStream(input, file.length()) }
    }

private fun readZipDemTile(file: File): DemTileData? {
    ZipInputStream(file.inputStream().buffered()).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            if (!entry.isDirectory && entry.name.endsWith(".hgt", ignoreCase = true)) {
                val byteSizeHint = entry.size.takeIf { it >= 0L } ?: zipDemEntrySize(file, entry.name)
                return decodeReliefDemStream(input, byteSizeHint)
            }
        }
    }
    return null
}

private fun zipDemEntrySize(
    file: File,
    entryName: String,
): Long =
    try {
        ZipFile(file).use { zip -> zip.getEntry(entryName)?.size ?: -1L }
    } catch (_: IOException) {
        // A streaming ZIP can still contain a readable HGT entry without a central directory.
        -1L
    }

internal fun decodeReliefDemStream(
    input: InputStream,
    byteSizeHint: Long,
): DemTileData? {
    var samples = ShortArray(initialDemSampleCapacity(byteSizeHint))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var sampleCount = 0
    var carryBytes = 0
    while (true) {
        val read = readDemChunk(input, buffer, carryBytes)
        if (read < 0) break
        val byteCount = carryBytes + read
        val completeBytes = byteCount - byteCount % HGT_SAMPLE_BYTES
        val requiredSamples = sampleCount.toLong() + completeBytes / HGT_SAMPLE_BYTES
        if (requiredSamples > MAX_DEM_SAMPLE_COUNT) throw IOException("DEM tile is too large")
        samples = ensureDemSampleCapacity(samples, requiredSamples.toInt())
        decodeDemChunk(buffer, completeBytes, samples, sampleCount)
        sampleCount = requiredSamples.toInt()
        carryBytes = byteCount - completeBytes
        if (carryBytes != 0) buffer[0] = buffer[completeBytes]
    }
    return if (carryBytes == 0) buildDecodedDemTile(samples, sampleCount) else null
}

private fun initialDemSampleCapacity(byteSizeHint: Long): Int {
    val sampleCount = byteSizeHint / HGT_SAMPLE_BYTES
    // Metadata is a hint: concatenated GZIP members and streaming ZIP entries can differ.
    return if (byteSizeHint % HGT_SAMPLE_BYTES == 0L && sampleCount in 1..MAX_PREALLOCATED_DEM_SAMPLES) {
        sampleCount.toInt()
    } else {
        DEFAULT_BUFFER_SIZE / HGT_SAMPLE_BYTES
    }
}

private fun readDemChunk(
    input: InputStream,
    buffer: ByteArray,
    offset: Int,
): Int {
    val read = input.read(buffer, offset, buffer.size - offset)
    return if (read == 0) {
        val nextByte = input.read()
        if (nextByte < 0) {
            -1
        } else {
            buffer[offset] = nextByte.toByte()
            1
        }
    } else {
        read
    }
}

private fun ensureDemSampleCapacity(
    samples: ShortArray,
    requiredSamples: Int,
): ShortArray =
    if (requiredSamples <= samples.size) {
        samples
    } else {
        val doubledCapacity = (samples.size.toLong() * 2).coerceAtMost(MAX_DEM_SAMPLE_COUNT.toLong()).toInt()
        samples.copyOf(maxOf(requiredSamples, doubledCapacity))
    }

private fun decodeDemChunk(
    buffer: ByteArray,
    byteCount: Int,
    samples: ShortArray,
    sampleOffset: Int,
) {
    var byteOffset = 0
    var sampleIndex = sampleOffset
    while (byteOffset < byteCount) {
        val hi = buffer[byteOffset].toInt() and 0xff
        val lo = buffer[byteOffset + 1].toInt() and 0xff
        samples[sampleIndex++] = ((hi shl 8) or lo).toShort()
        byteOffset += HGT_SAMPLE_BYTES
    }
}

private fun buildDecodedDemTile(
    samples: ShortArray,
    sampleCount: Int,
): DemTileData? {
    val rowLen = sqrt(sampleCount.toDouble()).toInt()
    return if (rowLen >= 2 && rowLen * rowLen == sampleCount) {
        DemTileData(
            axisLen = rowLen - 1,
            rowLen = rowLen,
            samples = if (samples.size == sampleCount) samples else samples.copyOf(sampleCount),
        )
    } else {
        null
    }
}

private const val HGT_SAMPLE_BYTES = 2
private const val MAX_DEM_SAMPLE_COUNT = Int.MAX_VALUE / HGT_SAMPLE_BYTES
private const val MAX_PREALLOCATED_DEM_SAMPLES = 3_601L * 3_601L
