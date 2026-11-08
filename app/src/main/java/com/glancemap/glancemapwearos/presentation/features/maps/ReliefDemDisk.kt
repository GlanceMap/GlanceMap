package com.glancemap.glancemapwearos.presentation.features.maps

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

private const val DEM_DISK_DIR = "dem-uncompressed"

/** Directory used by the first, 3D-only version of the disk cache; removed on first use. */
private const val LEGACY_DEM_DISK_DIR = "worldwind-dem"

/** About six uncompressed 1-arc-second tiles (3601 × 3601 × 2 bytes each). */
internal const val DEM_DISK_BYTES = 160L * 1024 * 1024

private const val HGT_SAMPLE_BYTES = 2L

/** Elevation tile that can be sampled with the shared bilinear HGT interpolation. */
internal interface DemTile {
    val axisLen: Int

    /** Decoded sample bytes held on the heap; zero for a memory-mapped tile. */
    val heapBytes: Long

    fun elevationOrNaN(
        tileLat: Int,
        tileLon: Int,
        lat: Double,
        lon: Double,
    ): Double
}

/** Raw big-endian HGT samples read from a memory-mapped file; no heap copy of the tile is kept. */
internal class MappedDemTile(
    override val axisLen: Int,
    val rowLen: Int,
    private val samples: ShortBuffer,
) : DemTile {
    override val heapBytes: Long get() = 0L

    override fun elevationOrNaN(
        tileLat: Int,
        tileLon: Int,
        lat: Double,
        lon: Double,
    ): Double =
        interpolateDemSamplesOrNaN(axisLen, rowLen, samples.capacity(), tileLat, tileLon, lat, lon) {
            samples.get(it)
        }
}

/** Maps a raw HGT file; returns null unless it holds a square grid of 16-bit samples. */
internal fun mapRawDemTile(file: File): MappedDemTile? =
    RandomAccessFile(file, "r").use { access ->
        val bytes = access.length()
        val count = bytes / HGT_SAMPLE_BYTES
        val rowLen = sqrt(count.toDouble()).toInt()
        if (bytes % HGT_SAMPLE_BYTES != 0L || rowLen < 2 || rowLen.toLong() * rowLen != count) {
            null
        } else {
            // The mapping stays valid after the channel closes; HGT is big-endian, the buffer default.
            val buffer = access.channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes).asShortBuffer()
            MappedDemTile(rowLen - 1, rowLen, buffer)
        }
    }

/**
 * Uncompressed copies of DEM tiles, written once and then read in place by every DEM consumer.
 *
 * Every source, including a raw `.hgt`, is copied so that a mapped file is only ever replaced
 * by an atomic rename or deleted, never rewritten in place. Copies are keyed by source path,
 * size and modification time, and the least recently used copies are removed above [maxBytes];
 * the most recent copy is always kept. Use [shared] so all consumers coordinate on one lock.
 */
internal class DemDiskCache(
    private val dir: File,
    private val maxBytes: Long,
) {
    companion object {
        @Volatile private var shared: DemDiskCache? = null

        fun shared(context: Context): DemDiskCache =
            shared ?: synchronized(this) {
                shared ?: run {
                    val cacheDir = context.applicationContext.cacheDir
                    File(cacheDir, LEGACY_DEM_DISK_DIR).deleteRecursively()
                    DemDiskCache(File(cacheDir, DEM_DISK_DIR), DEM_DISK_BYTES).also { shared = it }
                }
            }
    }

    /** Counters for every consumer since the process started, for the diagnostics export. */
    val totals = ReliefDemCacheStats()

    /** Maps the uncompressed copy of [source]; null when a ZIP has no HGT entry or the grid is not square. */
    @Synchronized
    fun map(
        source: File,
        stats: ReliefDemCacheStats? = null,
    ): MappedDemTile? = rawFileFor(source, stats)?.let(::mapRawDemTile)

    @Synchronized
    fun rawFileFor(
        source: File,
        stats: ReliefDemCacheStats? = null,
    ): File? {
        val target = File(dir, demDiskCacheName(source))
        if (target.isFile) {
            target.setLastModified(System.currentTimeMillis())
            stats?.diskHits?.incrementAndGet()
            totals.diskHits.incrementAndGet()
        } else if (!copy(source, target, stats)) {
            return null
        }
        return target
    }

    /** Records a tile decoded onto the heap because its copy could not be written or mapped. */
    fun recordFallback() {
        totals.fallbackTiles.incrementAndGet()
    }

    @Synchronized
    fun diagnostics(): DemDiskDiagnostics {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".hgt") }.orEmpty()
        return DemDiskDiagnostics(
            fileCount = files.size,
            sizeBytes = files.sumOf(File::length),
            limitBytes = maxBytes,
            copies = totals.diskCopies.get(),
            copyBytes = totals.diskCopyBytes.get(),
            copyMs = totals.diskCopyNanos.get() / 1_000_000,
            hits = totals.diskHits.get(),
            fallbackTiles = totals.fallbackTiles.get(),
        )
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach(File::delete)
    }

    private fun copy(
        source: File,
        target: File,
        stats: ReliefDemCacheStats?,
    ): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val temp = File(dir, "${target.name}.tmp")
        val started = System.nanoTime()
        val written =
            try {
                openRawDemStream(source)
                    ?.use { input -> temp.outputStream().use(input::copyTo) }
                    ?.also { if (!temp.renameTo(target)) throw IOException("Cannot move DEM copy into $dir") }
            } finally {
                temp.delete()
            }
        if (written != null) {
            val nanos = System.nanoTime() - started
            for (counters in listOfNotNull(stats, totals)) {
                counters.diskCopies.incrementAndGet()
                counters.diskCopyBytes.addAndGet(written)
                counters.diskCopyNanos.addAndGet(nanos)
            }
            trim(target)
        }
        return written != null
    }

    private fun trim(keep: File) {
        val files = oldestFirst(dir.listFiles { file -> file.isFile && file.name.endsWith(".hgt") }.orEmpty().asList())
        var total = files.sumOf(CacheFileSnapshot::length)
        for (entry in files) {
            if (total <= maxBytes) break
            if (entry.file != keep && entry.file.delete()) total -= entry.length
        }
    }
}

/** Shared DEM copy folder state; counters cover every consumer since the process started. */
data class DemDiskDiagnostics(
    val fileCount: Int,
    val sizeBytes: Long,
    val limitBytes: Long,
    val copies: Long,
    val copyBytes: Long,
    val copyMs: Long,
    val hits: Long,
    val fallbackTiles: Long,
)

/** A cache file's age and size, read once so that a concurrent touch cannot reorder a sort. */
internal data class CacheFileSnapshot(
    val file: File,
    val lastModified: Long,
    val length: Long,
)

/**
 * Sorts cache files oldest first using one reading of each file's time. Comparing live
 * [File.lastModified] values breaks the sort's contract when another thread touches a file
 * mid-sort, which crashed the app.
 */
internal fun oldestFirst(
    files: List<File>,
    lastModified: (File) -> Long = File::lastModified,
): List<CacheFileSnapshot> =
    files
        .map { CacheFileSnapshot(it, lastModified(it), it.length()) }
        .sortedBy(CacheFileSnapshot::lastModified)

internal fun demDiskCacheName(source: File): String {
    val tileId = source.name.substringBefore('.')
    val path =
        source.absolutePath
            .hashCode()
            .toUInt()
            .toString(16)
    return "$tileId-$path-${source.length()}-${source.lastModified()}.hgt"
}

private fun openRawDemStream(source: File): InputStream? {
    val name = source.name.lowercase()
    return when {
        name.endsWith(".gz") -> GZIPInputStream(source.inputStream().buffered())
        name.endsWith(".zip") -> {
            val zip = ZipInputStream(source.inputStream().buffered())
            var entry = zip.nextEntry
            while (entry != null && (entry.isDirectory || !entry.name.endsWith(".hgt", ignoreCase = true))) {
                entry = zip.nextEntry
            }
            if (entry == null) {
                zip.close()
                null
            } else {
                zip
            }
        }
        else -> source.inputStream().buffered()
    }
}
