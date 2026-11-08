@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapwearos.presentation.features.maps

import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.BenchmarkTrace
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal class ReliefDemRepository(
    private val demRootDirs: List<File>,
    private val tag: String,
    private val cacheEntryLimit: Int? = null,
    private val cacheStats: ReliefDemCacheStats? = null,
    private val cacheByteLimit: Long? = null,
    private val diskCache: DemDiskCache? = null,
) {
    constructor(
        demRootDir: File,
        tag: String,
    ) : this(listOf(demRootDir), tag)

    companion object {
        private const val MIN_DEM_TILE_CACHE_ENTRIES = 3
        private const val MAX_DEM_TILE_CACHE_ENTRIES = 8
    }

    private val maxDemTileCacheEntries: Int =
        cacheEntryLimit?.let { minOf(it.coerceAtLeast(1), computeMaxDemTileCacheEntries()) }
            ?: computeMaxDemTileCacheEntries()
    private val demTileCache = ReliefDemTileCache(maxDemTileCacheEntries)

    fun loadDemTileFor(
        lat: Double,
        lon: Double,
    ): DemTile? = loadDemTile(floor(lat).toInt(), floor(lon).toInt())

    fun elevationAt(
        lat: Double,
        lon: Double,
    ): Double? = elevationSampleAt(lat, lon)?.elevationMeters

    fun elevationSampleAt(
        lat: Double,
        lon: Double,
    ): DemElevationSample? {
        val latTile = floor(lat).toInt()
        val lonTile = floor(lon).toInt()
        return loadDemTile(latTile, lonTile)?.let { tile ->
            tile.elevationOrNaN(latTile, lonTile, lat, lon).takeUnless { it.isNaN() }?.let { elevationMeters ->
                DemElevationSample(
                    elevationMeters = elevationMeters,
                    tileId = tileId(latTile, lonTile),
                    axisLen = tile.axisLen,
                    resolutionLabel = demResolutionLabel(tile.axisLen),
                )
            }
        }
    }

    fun elevationAtUsingTile(
        tile: DemTile,
        tileLat: Int,
        tileLon: Int,
        lat: Double,
        lon: Double,
    ): Double? {
        if (floor(lat).toInt() != tileLat || floor(lon).toInt() != tileLon) {
            return elevationAt(lat, lon)
        }
        return tile.elevationOrNaN(tileLat, tileLon, lat, lon).takeUnless { it.isNaN() }
    }

    fun elevationAtUsingTileOrNaN(
        tile: DemTile,
        tileLat: Int,
        tileLon: Int,
        lat: Double,
        lon: Double,
    ): Double {
        if (floor(lat).toInt() != tileLat || floor(lon).toInt() != tileLon) {
            return elevationAt(lat, lon) ?: Double.NaN
        }
        return tile.elevationOrNaN(tileLat, tileLon, lat, lon)
    }

    fun clear() {
        synchronized(demTileCache) {
            demTileCache.clear()
            cacheStats?.recordRetained(0)
        }
    }

    private fun loadDemTile(
        latTile: Int,
        lonTile: Int,
    ): DemTile? {
        val tileId = tileId(latTile, lonTile)
        synchronized(demTileCache) {
            if (demTileCache.containsKey(tileId)) {
                cacheStats?.hits?.incrementAndGet()
                return demTileCache[tileId]
            }
        }

        cacheStats?.misses?.incrementAndGet()
        val loaded =
            runCatching {
                val file = resolveDemFile(demRootDirs, tileId) ?: return@runCatching null
                mapFromDisk(file, tileId) ?: decode(file)
            }.onFailure { error ->
                Log.w(tag, "Failed to load DEM tile $tileId", error)
            }.getOrNull()

        synchronized(demTileCache) {
            demTileCache[tileId] = loaded
            cacheByteLimit?.let(demTileCache::trimToBytes)
            cacheStats?.recordRetained(demTileCache.retainedBytes())
        }
        return loaded
    }

    /** Reads the shared uncompressed copy in place; null falls back to decoding onto the heap. */
    private fun mapFromDisk(
        file: File,
        tileId: String,
    ): DemTile? {
        val cache = diskCache ?: return null
        val mapped =
            try {
                BenchmarkTrace.section("relief.demDiskMap") { cache.map(file, cacheStats) }
            } catch (error: IOException) {
                Log.w(tag, "Decoding DEM tile $tileId in memory", error)
                null
            }
        if (mapped == null) {
            cacheStats?.fallbackTiles?.incrementAndGet()
            cache.recordFallback()
        }
        return mapped
    }

    private fun decode(file: File): DemTileData? {
        val started = if (cacheStats != null) System.nanoTime() else 0L
        val tile = BenchmarkTrace.section("relief.demReadDecode") { readReliefDemTile(file) }
        if (tile != null && cacheStats != null) {
            cacheStats.loads.incrementAndGet()
            cacheStats.decodedBytes.addAndGet(tile.heapBytes)
            cacheStats.loadNanos.addAndGet(System.nanoTime() - started)
        }
        return tile
    }

    private fun tileId(
        latTile: Int,
        lonTile: Int,
    ): String = demTileId(latTile, lonTile)

    private fun computeMaxDemTileCacheEntries(): Int {
        val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        val adaptive =
            when {
                maxHeapMb <= 128L -> 3
                maxHeapMb <= 192L -> 4
                maxHeapMb <= 256L -> 5
                maxHeapMb <= 384L -> 6
                else -> 8
            }
        return adaptive.coerceIn(MIN_DEM_TILE_CACHE_ENTRIES, MAX_DEM_TILE_CACHE_ENTRIES)
    }
}

internal fun demTileId(
    latTile: Int,
    lonTile: Int,
): String {
    val latPrefix = if (latTile >= 0) "N" else "S"
    val lonPrefix = if (lonTile >= 0) "E" else "W"
    return String.format(
        Locale.US,
        "%s%02d%s%03d",
        latPrefix,
        abs(latTile),
        lonPrefix,
        abs(lonTile),
    )
}

internal fun resolveDemFile(
    demRootDirs: List<File>,
    tileId: String,
): File? {
    val folder = tileId.substring(0, 3)
    return demRootDirs
        .asSequence()
        .flatMap { demRootDir ->
            sequenceOf(
                File(File(demRootDir, folder), "$tileId.hgt.gz"),
                File(File(demRootDir, folder), "$tileId.hgt.zip"),
                File(File(demRootDir, folder), "$tileId.hgt"),
                File(demRootDir, "$tileId.hgt.gz"),
                File(demRootDir, "$tileId.hgt.zip"),
                File(demRootDir, "$tileId.hgt"),
            )
        }.firstOrNull { it.exists() && it.isFile }
}

internal data class DemElevationSample(
    val elevationMeters: Double,
    val tileId: String,
    val axisLen: Int,
    val resolutionLabel: String,
)

internal fun demResolutionLabel(axisLen: Int): String =
    when {
        axisLen >= 3_600 -> "detailed_1s"
        axisLen >= 1_200 -> "standard_3s"
        else -> "custom"
    }

internal fun interpolateDemElevation(
    tile: DemTileData,
    latTile: Int,
    lonTile: Int,
    lat: Double,
    lon: Double,
): Double? = interpolateDemElevationOrNaN(tile, latTile, lonTile, lat, lon).takeUnless { it.isNaN() }

internal fun interpolateDemElevationOrNaN(
    tile: DemTileData,
    latTile: Int,
    lonTile: Int,
    lat: Double,
    lon: Double,
): Double =
    interpolateDemSamplesOrNaN(tile.axisLen, tile.rowLen, tile.samples.size, latTile, lonTile, lat, lon) {
        tile.samples[it]
    }

/** Shared bilinear HGT interpolation, so in-memory and file-backed samples produce identical heights. */
@Suppress("LongParameterList")
internal inline fun interpolateDemSamplesOrNaN(
    tileAxisLen: Int,
    rowLen: Int,
    sampleCount: Int,
    latTile: Int,
    lonTile: Int,
    lat: Double,
    lon: Double,
    sampleAt: (Int) -> Short,
): Double {
    val axisLen = tileAxisLen.coerceAtLeast(1)
    if (rowLen <= axisLen || sampleCount < rowLen * rowLen) return Double.NaN

    val fracLat = (lat - latTile.toDouble()).coerceIn(0.0, 1.0)
    val fracLon = (lon - lonTile.toDouble()).coerceIn(0.0, 1.0)
    val rowF = (1.0 - fracLat) * axisLen
    val colF = fracLon * axisLen
    val r0 = floor(rowF).toInt().coerceIn(0, axisLen)
    val c0 = floor(colF).toInt().coerceIn(0, axisLen)
    val r1 = min(axisLen, r0 + 1)
    val c1 = min(axisLen, c0 + 1)
    val t = (rowF - r0).coerceIn(0.0, 1.0)
    val u = (colF - c0).coerceIn(0.0, 1.0)

    var weightedElevation = 0.0
    var validWeight = 0.0

    val z00 = sampleAt(r0 * rowLen + c0)
    val w00 = (1.0 - t) * (1.0 - u)
    if (z00 != DEM_VOID_SAMPLE && w00 > 0.0) {
        weightedElevation += z00.toDouble() * w00
        validWeight += w00
    }
    val z01 = sampleAt(r0 * rowLen + c1)
    val w01 = (1.0 - t) * u
    if (z01 != DEM_VOID_SAMPLE && w01 > 0.0) {
        weightedElevation += z01.toDouble() * w01
        validWeight += w01
    }
    val z10 = sampleAt(r1 * rowLen + c0)
    val w10 = t * (1.0 - u)
    if (z10 != DEM_VOID_SAMPLE && w10 > 0.0) {
        weightedElevation += z10.toDouble() * w10
        validWeight += w10
    }
    val z11 = sampleAt(r1 * rowLen + c1)
    val w11 = t * u
    if (z11 != DEM_VOID_SAMPLE && w11 > 0.0) {
        weightedElevation += z11.toDouble() * w11
        validWeight += w11
    }
    return if (validWeight > 0.0) weightedElevation / validWeight else Double.NaN
}

internal const val DEM_VOID_SAMPLE: Short = Short.MIN_VALUE

internal class ReliefDemCacheStats {
    val hits = AtomicLong()
    val misses = AtomicLong()
    val loads = AtomicLong()
    val decodedBytes = AtomicLong()
    val loadNanos = AtomicLong()
    val retainedBytes = AtomicLong()
    val peakRetainedBytes = AtomicLong()
    val diskHits = AtomicLong()
    val diskCopies = AtomicLong()
    val diskCopyBytes = AtomicLong()
    val diskCopyNanos = AtomicLong()
    val fallbackTiles = AtomicLong()

    fun recordRetained(bytes: Long) {
        retainedBytes.set(bytes)
        peakRetainedBytes.updateAndGet { maxOf(it, bytes) }
    }
}

internal class ReliefDemTileCache(
    private val maxEntries: Int,
) : LinkedHashMap<String, DemTile?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DemTile?>?): Boolean = size > maxEntries

    fun retainedBytes(): Long = values.sumOf { it?.heapBytes ?: 0L }

    /** Evicts decoded tiles above [maxBytes]; mapped tiles hold no heap and stay cached. */
    fun trimToBytes(maxBytes: Long) {
        var bytes = retainedBytes()
        var populatedTiles = values.count { it != null && it.heapBytes > 0L }
        val iterator = entries.iterator()
        while (bytes > maxBytes && populatedTiles > 1 && iterator.hasNext()) {
            val tile = iterator.next().value
            if (tile != null && tile.heapBytes == 0L) continue
            if (tile != null) populatedTiles--
            bytes -= tile?.heapBytes ?: 0L
            iterator.remove()
        }
    }
}
