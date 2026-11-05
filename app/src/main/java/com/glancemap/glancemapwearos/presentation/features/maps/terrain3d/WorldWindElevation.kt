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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.roundToInt

private const val WORLDWIND_DEM_CACHE_ENTRIES = 4

private interface WorldWindGridFactory : ElevationSource.ElevationDataFactory {
    val skipped: AtomicBoolean
}

internal fun worldWindHeight(value: Double?): Short {
    val valid = value?.takeIf { it.isFinite() }?.takeIf { it > Short.MIN_VALUE && it <= Short.MAX_VALUE }
    return valid?.roundToInt()?.coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt())?.toShort() ?: Short.MIN_VALUE
}

/** Conservative three-arcsecond coverage also handles mixed Standard/Detailed fallback. */
internal class WorldWindElevation(
    context: Context,
    source: TerrainSource,
    bounds: BoundingBox,
) : AutoCloseable {
    val cacheStats = ReliefDemCacheStats()
    private val repository =
        ReliefDemRepository(
            source.demSource.readFallbackOrder().map { Dem3CoverageUtils.demRootDir(context, it) },
            TERRAIN_LOG_TAG,
            cacheEntryLimit = WORLDWIND_DEM_CACHE_ENTRIES,
            cacheStats = cacheStats,
        )
    val gridCount = AtomicLong()
    val sampleCount = AtomicLong()
    val voidCount = AtomicLong()
    val skippedGridCount = AtomicLong()
    val failedGridCount = AtomicLong()
    val sampleMs = AtomicLong()
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
                            override suspend fun fetchElevationData(): Buffer? =
                                withContext(dispatcher) {
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
                                        var voids = 0
                                        val result = ShortArray(width * height)
                                        val columns = IntArray(width) { x -> floor(longitudes[x]).toInt() }
                                        val loadedTiles = mutableMapOf<Pair<Int, Int>, DemTileData?>()
                                        for (y in 0 until height) {
                                            job.ensureActive()
                                            val tileLat = floor(latitudes[y]).toInt()
                                            var tileLon = Int.MIN_VALUE
                                            var tile: DemTileData? = null
                                            for (x in 0 until width) {
                                                if (columns[x] != tileLon) {
                                                    tileLon = columns[x]
                                                    val key = tileLat to tileLon
                                                    if (!loadedTiles.containsKey(key)) {
                                                        loadedTiles[key] =
                                                            repository.loadDemTileFor(latitudes[y], longitudes[x])
                                                    }
                                                    tile = loadedTiles[key]
                                                }
                                                val sample =
                                                    worldWindHeight(
                                                        tile?.let {
                                                            repository.elevationAtUsingTile(
                                                                it,
                                                                tileLat,
                                                                tileLon,
                                                                latitudes[y],
                                                                longitudes[x],
                                                            )
                                                        },
                                                    )
                                                if (sample == Short.MIN_VALUE) voids++
                                                result[y * width + x] = sample
                                            }
                                        }
                                        gridCount.incrementAndGet()
                                        sampleCount.addAndGet(result.size.toLong())
                                        voidCount.addAndGet(voids.toLong())
                                        sampleMs.addAndGet(SystemClock.elapsedRealtime() - started)
                                        ShortBuffer.wrap(result)
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
            closed = true
            coverage.clear()
            repository.clear()
        }
    }
}
