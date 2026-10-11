@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapwearos.presentation.features.maps

import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.BenchmarkTrace
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal class ReliefDemRepository(
    private val demRootDirs: List<File>,
    private val tag: String,
) {
    constructor(
        demRootDir: File,
        tag: String,
    ) : this(listOf(demRootDir), tag)

    companion object {
        private const val MIN_DEM_TILE_CACHE_ENTRIES = 3
        private const val MAX_DEM_TILE_CACHE_ENTRIES = 8
    }

    private val maxDemTileCacheEntries: Int = computeMaxDemTileCacheEntries()
    private val demTileCache =
        object : LinkedHashMap<String, DemTileData?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DemTileData?>?): Boolean = size > maxDemTileCacheEntries
        }

    fun loadDemTileFor(
        lat: Double,
        lon: Double,
    ): DemTileData? = loadDemTile(floor(lat).toInt(), floor(lon).toInt())

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
            interpolateDemElevation(tile, latTile, lonTile, lat, lon)?.let { elevationMeters ->
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
        tile: DemTileData,
        tileLat: Int,
        tileLon: Int,
        lat: Double,
        lon: Double,
    ): Double? {
        if (floor(lat).toInt() != tileLat || floor(lon).toInt() != tileLon) {
            return elevationAt(lat, lon)
        }
        return interpolateDemElevation(tile, tileLat, tileLon, lat, lon)
    }

    fun clear() {
        synchronized(demTileCache) {
            demTileCache.clear()
        }
    }

    private fun loadDemTile(
        latTile: Int,
        lonTile: Int,
    ): DemTileData? {
        val tileId = tileId(latTile, lonTile)
        synchronized(demTileCache) {
            if (demTileCache.containsKey(tileId)) {
                return demTileCache[tileId]
            }
        }

        val loaded =
            runCatching {
                val file = resolveDemFile(demRootDirs, tileId) ?: return@runCatching null
                BenchmarkTrace.section("relief.demReadDecode") { readReliefDemTile(file) }
            }.onFailure { error ->
                Log.w(tag, "Failed to load DEM tile $tileId", error)
            }.getOrNull()

        synchronized(demTileCache) {
            demTileCache[tileId] = loaded
        }
        return loaded
    }

    private fun tileId(
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
): Double? {
    val axisLen = tile.axisLen.coerceAtLeast(1)
    val rowLen = tile.rowLen
    if (rowLen <= axisLen || tile.samples.size < rowLen * rowLen) return null

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

    val z00 = tile.samples[r0 * rowLen + c0]
    val w00 = (1.0 - t) * (1.0 - u)
    if (z00 != DEM_VOID_SAMPLE && w00 > 0.0) {
        weightedElevation += z00.toDouble() * w00
        validWeight += w00
    }
    val z01 = tile.samples[r0 * rowLen + c1]
    val w01 = (1.0 - t) * u
    if (z01 != DEM_VOID_SAMPLE && w01 > 0.0) {
        weightedElevation += z01.toDouble() * w01
        validWeight += w01
    }
    val z10 = tile.samples[r1 * rowLen + c0]
    val w10 = t * (1.0 - u)
    if (z10 != DEM_VOID_SAMPLE && w10 > 0.0) {
        weightedElevation += z10.toDouble() * w10
        validWeight += w10
    }
    val z11 = tile.samples[r1 * rowLen + c1]
    val w11 = t * u
    if (z11 != DEM_VOID_SAMPLE && w11 > 0.0) {
        weightedElevation += z11.toDouble() * w11
        validWeight += w11
    }
    return if (validWeight > 0.0) weightedElevation / validWeight else null
}

internal const val DEM_VOID_SAMPLE: Short = Short.MIN_VALUE
