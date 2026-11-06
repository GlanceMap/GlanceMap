package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.presentation.features.maps.DemElevationSample
import com.glancemap.glancemapwearos.presentation.features.maps.DemTileData
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemCacheStats
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemRepository
import com.glancemap.glancemapwearos.presentation.features.maps.TerrainSource
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
import java.nio.Buffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.roundToInt

private const val WORLDWIND_DEM_CACHE_ENTRIES = 4
private const val WORLDWIND_DEM_CACHE_BYTES = 16L * 1024 * 1024

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

internal fun worldWindElevationGrid(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
    loadTile: (Double, Double) -> DemTileData?,
    sampleTile: (DemTileData, Int, Int, Double, Double) -> Double,
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

/** Conservative three-arcsecond coverage also handles mixed Standard/Detailed fallback. */
internal class WorldWindElevation(
    context: Context,
    source: TerrainSource,
    bounds: BoundingBox,
) : AutoCloseable {
    val cacheStats = ReliefDemCacheStats()
    val cacheRetainedBeforeClose = AtomicLong()
    private val repository =
        ReliefDemRepository(
            source.demSource.readFallbackOrder().map { Dem3CoverageUtils.demRootDir(context, it) },
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
                                            val started = SystemClock.elapsedRealtime()
                                            val result =
                                                worldWindElevationGrid(
                                                    latitudes,
                                                    longitudes,
                                                    repository::loadDemTileFor,
                                                    repository::elevationAtUsingTileOrNaN,
                                                    job::ensureActive,
                                                )
                                            gridCount.incrementAndGet()
                                            sampleCount.addAndGet(result.size.toLong())
                                            voidCount.addAndGet(result.count { it == Short.MIN_VALUE }.toLong())
                                            sampleMs.addAndGet(SystemClock.elapsedRealtime() - started)
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
            if (closed) null else repository.elevationSampleAt(latitude, longitude)
        }

    override fun close() {
        synchronized(lock) {
            if (!closed) cacheRetainedBeforeClose.set(cacheStats.retainedBytes.get())
            closed = true
            coverage.clear()
            repository.clear()
        }
    }
}
