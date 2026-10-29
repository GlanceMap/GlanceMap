package com.glancemap.glancemapcompanionapp.map

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
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

/** Supplies the existing local HGT reader to WorldWind's asynchronous elevation tile pipeline. */
internal class PhoneWorldWindElevation(
    private val repository: PhoneElevationRepository?,
    bounds: BoundingBox,
    private val online: PhoneOnlineElevationRepository? = null,
    resolutionSeconds: Int = 1,
    private val diagnostics: PhoneWorldWindDiagnostics? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val demDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val sector =
        Sector.fromDegrees(bounds.minLatitude, bounds.minLongitude, bounds.latitudeSpan, bounds.longitudeSpan)
    private val matrices =
        TileMatrixSet.fromTilePyramid(
            sector,
            1,
            1,
            64,
            64,
            (resolutionSeconds.toDouble() / 3600.0).degrees,
        )
    val coverage =
        TiledElevationCoverage(
            matrices,
            object : ElevationSourceFactory {
                override val contentType = "Phone elevation"

                override fun createElevationSource(
                    tileMatrix: TileMatrix,
                    row: Int,
                    column: Int,
                ): ElevationSource =
                    ElevationSource.fromElevationDataFactory(
                        object : ElevationSource.ElevationDataFactory {
                            override suspend fun fetchElevationData(): Buffer? =
                                withContext(demDispatcher) {
                                    if (closed.get()) return@withContext null
                                    val started = android.os.SystemClock.elapsedRealtime()
                                    val tile = tileMatrix.tileSector(row, column)
                                    val bounds =
                                        PhoneWorldWindBounds(
                                            tile.minLatitude.inDegrees,
                                            tile.minLongitude.inDegrees,
                                            tile.maxLatitude.inDegrees,
                                            tile.maxLongitude.inDegrees,
                                        )
                                    val zoom =
                                        phoneWorldWindOnlineDemZoom(
                                            bounds.south,
                                            bounds.west,
                                            bounds.north,
                                            bounds.east,
                                            tileMatrix.tileWidth,
                                        )
                                    val heights =
                                        phoneWorldWindElevationGrid(
                                            bounds,
                                            tileMatrix.tileWidth to tileMatrix.tileHeight,
                                            repository,
                                            online,
                                            zoom,
                                        )
                                    if (closed.get()) return@withContext null
                                    val valid = heights.count { it != Short.MIN_VALUE }
                                    diagnostics?.elevation(
                                        android.os.SystemClock.elapsedRealtime() - started,
                                        heights.size,
                                        valid,
                                    )
                                    if (valid == 0 && online != null) return@withContext null
                                    ShortBuffer.wrap(heights)
                                }
                        },
                    )
            },
        ).apply { displayName = "Phone elevation" }

    override fun close() {
        closed.set(true)
        coverage.clear()
        repository?.invalidate()
    }
}

/** Match the DEM resolution to the grid while limiting each grid to four online tiles. */
internal fun phoneWorldWindOnlineDemZoom(
    south: Double,
    west: Double,
    north: Double,
    east: Double,
    gridWidth: Int,
): Int {
    var zoom =
        floor(log2(360.0 / (east - west).coerceAtLeast(1e-9) * gridWidth / PHONE_ONLINE_DEM_TILE_SIZE))
            .toInt()
            .coerceIn(0, 14)
    while (zoom > 0) {
        val first = phoneOnlineDemPoint(PhoneMapCoordinate(north, west.coerceAtLeast(-179.999999)), zoom)?.tile
        val last = phoneOnlineDemPoint(PhoneMapCoordinate(south, east.coerceAtMost(179.999999)), zoom)?.tile
        if (first == null || last == null || (last.x - first.x + 1) * (last.y - first.y + 1) <= 4) break
        zoom--
    }
    return zoom
}

internal suspend fun phoneWorldWindElevationAt(
    latitude: Double,
    longitude: Double,
    local: PhoneElevationRepository?,
    online: PhoneOnlineElevationRepository?,
    zoom: Int,
): Double? =
    local?.elevationAt(latitude, longitude) ?: online?.elevationAt(
        PhoneMapCoordinate(latitude, longitude.coerceIn(-179.999999, 179.999999)),
        zoom,
    )

/** The center HGT grid determines the coverage's finest meaningful level. Online DEM may be finer. */
internal fun phoneWorldWindDemResolutionSeconds(
    axisLength: Int?,
    onlineEnabled: Boolean,
): Int = if (onlineEnabled || axisLength == null || axisLength > 1200) 1 else 3

/** Group points by HGT tile to reuse each decoded tile even when the small repository LRU churns. */
internal suspend fun phoneWorldWindElevationGrid(
    bounds: PhoneWorldWindBounds,
    dimensions: Pair<Int, Int>,
    local: PhoneElevationRepository?,
    online: PhoneOnlineElevationRepository?,
    zoom: Int,
): ShortArray {
    val (width, height) = dimensions
    val heights = ShortArray(width * height) { Short.MIN_VALUE }
    val latitudes =
        DoubleArray(height) { y ->
            (bounds.north - (bounds.north - bounds.south) * y / (height - 1)).coerceIn(-89.999999, 89.999999)
        }
    val longitudes =
        DoubleArray(width) { x ->
            (bounds.west + (bounds.east - bounds.west) * x / (width - 1)).coerceIn(-179.999999, 179.999999)
        }
    val groups = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val tile = floor(latitudes[y]).toInt() to floor(longitudes[x]).toInt()
            groups.getOrPut(tile) { mutableListOf() }.add(y * width + x)
        }
    }
    for ((tileId, indices) in groups) {
        currentCoroutineContext().ensureActive()
        val tile = local?.loadTile(tileId.first, tileId.second)
        for (index in indices) {
            if (index % 64 == 0) currentCoroutineContext().ensureActive()
            val latitude = latitudes[index / width]
            val longitude = longitudes[index % width]
            val elevation =
                (
                    if (tile != null) {
                        local.elevationAtUsingTile(tile, tileId.first, tileId.second, latitude, longitude)
                    } else {
                        null
                    }
                )
                    ?: online?.elevationAt(PhoneMapCoordinate(latitude, longitude), zoom)
            if (elevation != null &&
                elevation.isFinite() &&
                elevation > Short.MIN_VALUE &&
                elevation <= Short.MAX_VALUE
            ) {
                heights[index] = elevation.roundToInt().coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
    return heights
}
