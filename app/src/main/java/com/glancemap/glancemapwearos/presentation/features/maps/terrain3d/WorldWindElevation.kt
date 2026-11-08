package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.DemSignatureStore
import com.glancemap.glancemapwearos.presentation.features.maps.DemElevationSample
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemCacheStats
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemRepository
import com.glancemap.glancemapwearos.presentation.features.maps.TerrainSource
import com.glancemap.glancemapwearos.presentation.features.maps.demResolutionLabel
import com.glancemap.glancemapwearos.presentation.features.maps.demTileId
import com.glancemap.glancemapwearos.presentation.features.maps.resolveDemFile
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.BoundingBox
import java.io.File
import java.io.IOException
import java.nio.Buffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.roundToInt

private const val WORLDWIND_DEM_CACHE_ENTRIES = 4
private const val WORLDWIND_DEM_CACHE_BYTES = 16L * 1024 * 1024
private const val WORLDWIND_GRID_CACHE_BYTES = 1024L * 1024
private const val WORLDWIND_MAPPED_DEM_TILES = 8

private interface WorldWindGridFactory : ElevationSource.ElevationDataFactory {
    val skipped: AtomicBoolean
}

internal fun worldWindHeight(value: Double?): Short = worldWindHeightPrimitive(value ?: Double.NaN)

private fun worldWindHeightPrimitive(value: Double): Short =
    if (value.isFinite() && value > Short.MIN_VALUE && value <= Short.MAX_VALUE) {
        value.roundToInt().coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt()).toShort()
    } else {
        Short.MIN_VALUE
    }

internal fun <T> worldWindElevationGrid(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
    loadTile: (Double, Double) -> T?,
    sampleTile: (T, Int, Int, Double, Double) -> Double,
    checkCancelled: () -> Unit,
): ShortArray {
    val result = ShortArray(latitudes.size * longitudes.size) { Short.MIN_VALUE }
    val rows = latitudes.indices.groupBy { floor(latitudes[it]).toInt() }
    val columns = longitudes.indices.groupBy { floor(longitudes[it]).toInt() }
    val tileCoordinates = rows.keys.flatMap { lat -> columns.keys.map { lon -> lat to lon } }
    for ((tileLat, tileLon) in tileCoordinates) {
        val tileRows = rows.getValue(tileLat)
        val tileColumns = columns.getValue(tileLon)
        checkCancelled()
        val tile = loadTile(latitudes[tileRows.first()], longitudes[tileColumns.first()]) ?: continue
        for (y in tileRows) {
            checkCancelled()
            for (x in tileColumns) {
                result[y * longitudes.size + x] =
                    worldWindHeightPrimitive(
                        sampleTile(tile, tileLat, tileLon, latitudes[y], longitudes[x]),
                    )
            }
        }
    }
    return result
}

private typealias MappedDemEntry = MutableMap.MutableEntry<String, MappedDemTile?>

/** Least recently used mapped tiles; a dropped mapping is released once it is collected. */
private class MappedDemTiles : LinkedHashMap<String, MappedDemTile?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MappedDemEntry?): Boolean = size > WORLDWIND_MAPPED_DEM_TILES
}

/** Conservative three-arcsecond coverage also handles mixed Standard/Detailed fallback. */
internal class WorldWindElevation(
    context: Context,
    source: TerrainSource,
    bounds: BoundingBox,
) : AutoCloseable {
    val cacheStats = ReliefDemCacheStats()
    val cacheRetainedBeforeClose = AtomicLong()
    val gridCache = WorldWindGridCache(WORLDWIND_GRID_CACHE_BYTES)
    val gridCacheRetainedBeforeClose = AtomicLong()
    private val demRootDirs = source.demSource.readFallbackOrder().map { Dem3CoverageUtils.demRootDir(context, it) }
    val diskCache = WorldWindDemDiskCache(File(context.cacheDir, WORLDWIND_DEM_DISK_DIR), WORLDWIND_DEM_DISK_BYTES)
    private val mappedTiles = MappedDemTiles()
    private val fallbackTiles = HashSet<String>()
    val fallbackTileCount = AtomicLong()

    fun diskLogFields(): String =
        "dem_disk_hits=${diskCache.diskHits.get()} dem_disk_copies=${diskCache.copies.get()} " +
            "dem_disk_copy_bytes=${diskCache.copiedBytes.get()} " +
            "dem_disk_copy_ms=${diskCache.copyNanos.get() / 1_000_000} " +
            "dem_fallback_tiles=${fallbackTileCount.get()}"

    /** Used only for a tile whose uncompressed copy cannot be written or mapped. */
    private val repository =
        ReliefDemRepository(
            demRootDirs,
            TERRAIN_LOG_TAG,
            cacheEntryLimit = WORLDWIND_DEM_CACHE_ENTRIES,
            cacheStats = cacheStats,
            cacheByteLimit = WORLDWIND_DEM_CACHE_BYTES,
        )
    val gridCount = AtomicLong()
    val sampleCount = AtomicLong()
    val voidCount = AtomicLong()
    val skippedGridCount = AtomicLong()
    val failedGridCount = AtomicLong()
    val sampleMs = AtomicLong()
    val pendingGrids = AtomicInteger()
    private val lock = Any()
    private var closed = false
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val matrices =
        TileMatrixSet.fromTilePyramid(
            Sector.fromDegrees(bounds.minLatitude, bounds.minLongitude, bounds.latitudeSpan, bounds.longitudeSpan),
            1,
            1,
            64,
            64,
            (3.0 / 3600.0).degrees,
        )
    val coverage =
        object : TiledElevationCoverage(
            matrices,
            object : ElevationSourceFactory {
                override val contentType = "Local HGT"

                override fun createElevationSource(
                    tileMatrix: TileMatrix,
                    row: Int,
                    column: Int,
                ): ElevationSource =
                    ElevationSource.fromElevationDataFactory(
                        object : WorldWindGridFactory {
                            override val skipped = AtomicBoolean()

                            @Suppress("LongMethod")
                            override suspend fun fetchElevationData(): Buffer? {
                                pendingGrids.incrementAndGet()
                                try {
                                    return withContext(dispatcher) {
                                        val job = currentCoroutineContext()
                                        val sector = tileMatrix.tileSector(row, column)
                                        val width = tileMatrix.tileWidth
                                        val height = tileMatrix.tileHeight
                                        val latitudes =
                                            DoubleArray(height) { y ->
                                                sector.maxLatitude.inDegrees -
                                                    sector.deltaLatitude.inDegrees * (y + 0.5) / height
                                            }
                                        val longitudes =
                                            DoubleArray(width) { x ->
                                                sector.minLongitude.inDegrees +
                                                    sector.deltaLongitude.inDegrees * (x + 0.5) / width
                                            }
                                        val tiles =
                                            latitudes.map { floor(it).toInt() }.distinct().size *
                                                longitudes.map { floor(it).toInt() }.distinct().size
                                        if (tiles > 4) {
                                            skipped.set(true)
                                            skippedGridCount.incrementAndGet()
                                            return@withContext null
                                        }
                                        synchronized(lock) {
                                            if (closed) return@synchronized null
                                            job.ensureActive()
                                            val generation = DemSignatureStore.generation()
                                            if (gridCache.prepareGeneration(generation)) clearTiles(diskCopies = true)
                                            val key = tileMatrix.tileKey(row, column)
                                            gridCache.get(key)?.let { return@synchronized ShortBuffer.wrap(it) }
                                            val started = SystemClock.elapsedRealtime()
                                            val result =
                                                worldWindElevationGrid(
                                                    latitudes,
                                                    longitudes,
                                                    ::loadTileFor,
                                                    ::elevationAtUsingTileOrNaN,
                                                    job::ensureActive,
                                                )
                                            gridCount.incrementAndGet()
                                            sampleCount.addAndGet(result.size.toLong())
                                            voidCount.addAndGet(result.count { it == Short.MIN_VALUE }.toLong())
                                            sampleMs.addAndGet(SystemClock.elapsedRealtime() - started)
                                            if (DemSignatureStore.generation() == generation) gridCache.put(key, result)
                                            ShortBuffer.wrap(result)
                                        }
                                    }
                                } finally {
                                    pendingGrids.decrementAndGet()
                                }
                            }
                        },
                    )
            },
        ) {
            override fun retrievalFailed(
                key: Long,
                source: ElevationSource,
                ex: Throwable?,
            ) {
                val factory = source.asElevationDataFactory() as? WorldWindGridFactory
                if (ex == null && factory?.skipped?.get() == true) {
                    retrievalFailed(key)
                } else {
                    failedGridCount.incrementAndGet()
                    super.retrievalFailed(key, source, ex)
                }
            }
        }

    fun sampleAt(
        latitude: Double,
        longitude: Double,
    ): DemElevationSample? =
        synchronized(lock) {
            if (closed) return@synchronized null
            val tileLat = floor(latitude).toInt()
            val tileLon = floor(longitude).toInt()
            val tile = loadTileFor(latitude, longitude) ?: return@synchronized null
            tile.elevationOrNaN(tileLat, tileLon, latitude, longitude).takeUnless { it.isNaN() }?.let {
                DemElevationSample(it, demTileId(tileLat, tileLon), tile.axisLen, demResolutionLabel(tile.axisLen))
            }
        }

    private fun loadTileFor(
        latitude: Double,
        longitude: Double,
    ): WorldWindDemTile? {
        val id = demTileId(floor(latitude).toInt(), floor(longitude).toInt())
        return when {
            id in fallbackTiles -> decodedTile(latitude, longitude)
            mappedTiles.containsKey(id) -> {
                cacheStats.hits.incrementAndGet()
                mappedTiles[id]
            }
            else -> {
                cacheStats.misses.incrementAndGet()
                openTile(id, latitude, longitude)
            }
        }
    }

    /** Maps the tile's uncompressed copy; a tile that cannot be mapped uses the in-memory fallback. */
    private fun openTile(
        id: String,
        latitude: Double,
        longitude: Double,
    ): WorldWindDemTile? {
        val file = resolveDemFile(demRootDirs, id)
        val mapped =
            try {
                file?.let(diskCache::rawFileFor)?.let(::mapRawDemTile)
            } catch (error: IOException) {
                Log.w(TERRAIN_LOG_TAG, "Using in-memory DEM for $id", error)
                null
            }
        if (file == null || mapped != null) {
            mappedTiles[id] = mapped
        } else if (fallbackTiles.add(id)) {
            fallbackTileCount.incrementAndGet()
        }
        return mapped ?: file?.let { decodedTile(latitude, longitude) }
    }

    private fun decodedTile(
        latitude: Double,
        longitude: Double,
    ): WorldWindDemTile? = repository.loadDemTileFor(latitude, longitude)?.let(::DecodedWorldWindDemTile)

    private fun elevationAtUsingTileOrNaN(
        tile: WorldWindDemTile,
        tileLat: Int,
        tileLon: Int,
        latitude: Double,
        longitude: Double,
    ): Double {
        val pointLat = floor(latitude).toInt()
        val pointLon = floor(longitude).toInt()
        val owner = if (pointLat == tileLat && pointLon == tileLon) tile else loadTileFor(latitude, longitude)
        return owner?.elevationOrNaN(pointLat, pointLon, latitude, longitude) ?: Double.NaN
    }

    private fun clearTiles(diskCopies: Boolean) {
        mappedTiles.clear()
        fallbackTiles.clear()
        repository.clear()
        if (diskCopies) diskCache.clear()
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) cacheRetainedBeforeClose.set(cacheStats.retainedBytes.get())
            if (!closed) gridCacheRetainedBeforeClose.set(gridCache.retainedBytes.get())
            closed = true
            coverage.clear()
            gridCache.clear()
            clearTiles(diskCopies = false)
        }
    }
}
