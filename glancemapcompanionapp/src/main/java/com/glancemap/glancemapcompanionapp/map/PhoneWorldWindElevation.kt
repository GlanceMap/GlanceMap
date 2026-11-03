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
    private val activity: PhoneWorldWindActivity,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val demDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val unpackDispatcher = Dispatchers.IO.limitedParallelism(1)
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
                                activity.track {
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
                                    val localCoversGrid =
                                        prepareLocalTiles(bounds, tileMatrix.tileWidth to tileMatrix.tileHeight)
                                    if (!localCoversGrid) preloadOnlineTiles(bounds, zoom)
                                    withContext(demDispatcher) {
                                        if (closed.get()) return@withContext null
                                        val started = android.os.SystemClock.elapsedRealtime()
                                        val heights =
                                            phoneWorldWindElevationGrid(
                                                bounds,
                                                tileMatrix.tileWidth to tileMatrix.tileHeight,
                                                repository,
                                                online,
                                                zoom,
                                            )
                                        if (closed.get()) return@withContext null
                                        if (heights == null) {
                                            diagnostics?.elevationIncomplete()
                                            return@withContext null
                                        }
                                        val valid = heights.count { it != Short.MIN_VALUE }
                                        diagnostics?.elevation(
                                            android.os.SystemClock.elapsedRealtime() - started,
                                            heights.size,
                                            valid,
                                        )
                                        if (valid == 0 && online != null) return@withContext null
                                        ShortBuffer.wrap(heights)
                                    }
                                }
                        },
                    )
            },
        ).apply { displayName = "Phone elevation" }

    /**
     * Unpacking a compressed HGT file takes seconds, so it runs on its own lane. Grids whose files are
     * ready keep loading on the elevation lane meanwhile, instead of queueing behind the unpack.
     */
    private suspend fun prepareLocalTiles(
        bounds: PhoneWorldWindBounds,
        dimensions: Pair<Int, Int>,
    ): Boolean {
        val local = repository ?: return false
        val tiles = phoneWorldWindGridLocalDemTiles(bounds, dimensions, online != null)
        return withContext(unpackDispatcher) {
            for ((latitude, longitude) in tiles) {
                if (closed.get()) break
                val started = android.os.SystemClock.elapsedRealtime()
                if (local.prepareTile(latitude, longitude)) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    diagnostics?.demUnpacked(phoneDemTileId(latitude, longitude), elapsed)
                }
            }
            tiles.isNotEmpty() && tiles.all { (latitude, longitude) -> local.hasTile(latitude, longitude) }
        }
    }

    /**
     * Online tiles download a second or more each. Loading a grid's tiles here, in parallel and before it
     * joins the single elevation lane, keeps one download from holding up every grid queued behind it.
     * Grids fully covered by local files download nothing.
     */
    private suspend fun preloadOnlineTiles(
        bounds: PhoneWorldWindBounds,
        zoom: Int,
    ) {
        val source = online ?: return
        if (closed.get()) return
        val started = android.os.SystemClock.elapsedRealtime()
        val tiles = phoneWorldWindGridOnlineDemTiles(bounds, zoom)
        source.preload(tiles)
        diagnostics?.onlineDemPreloaded(tiles.size, android.os.SystemClock.elapsedRealtime() - started)
    }

    override fun close() {
        closed.set(true)
        coverage.clear()
        repository?.invalidate()
    }
}

/** Matches the four-tile online limit; a grid spanning more 1° HGT files leaves local elevation out. */
internal const val PHONE_WORLDWIND_MAX_LOCAL_DEM_TILES = 4

/** About 250 m: eight 1-arc-second HGT samples per grid cell, so a 64-cell grid spans about 16 km. */
internal const val PHONE_WORLDWIND_LOCAL_DEM_MAX_CELL_DEGREES = 8.0 / 3600.0

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

/** WorldWind reads a coverage value as the centre of its cell, not as a point on the tile edge. */
internal fun phoneWorldWindCellCenter(
    start: Double,
    end: Double,
    index: Int,
    count: Int,
): Double = start + (end - start) * (index + 0.5) / count

private fun phoneWorldWindGridLatitudes(
    bounds: PhoneWorldWindBounds,
    height: Int,
): DoubleArray =
    DoubleArray(height) { y ->
        phoneWorldWindCellCenter(bounds.north, bounds.south, y, height).coerceIn(-89.999999, 89.999999)
    }

private fun phoneWorldWindGridLongitudes(
    bounds: PhoneWorldWindBounds,
    width: Int,
): DoubleArray =
    DoubleArray(width) { x ->
        phoneWorldWindCellCenter(bounds.west, bounds.east, x, width).coerceIn(-179.999999, 179.999999)
    }

/** The online elevation tiles a grid can sample; [phoneWorldWindOnlineDemZoom] keeps them to four or fewer. */
internal fun phoneWorldWindGridOnlineDemTiles(
    bounds: PhoneWorldWindBounds,
    zoom: Int,
): List<PhoneOnlineDemTileId> {
    val northWest = PhoneMapCoordinate(bounds.north, bounds.west.coerceAtLeast(-179.999999))
    val southEast = PhoneMapCoordinate(bounds.south, bounds.east.coerceAtMost(179.999999))
    val first = phoneOnlineDemPoint(northWest, zoom)?.tile
    val last = phoneOnlineDemPoint(southEast, zoom)?.tile
    if (first == null || last == null) return emptyList()
    return (first.y..last.y).flatMap { y -> (first.x..last.x).map { x -> PhoneOnlineDemTileId(first.zoom, x, y) } }
}

/** The 1° HGT tiles a grid samples locally, or none when it is too coarse to read local files. */
internal fun phoneWorldWindGridLocalDemTiles(
    bounds: PhoneWorldWindBounds,
    dimensions: Pair<Int, Int>,
    onlineAvailable: Boolean,
): List<Pair<Int, Int>> {
    val latitudeTiles = phoneWorldWindGridLatitudes(bounds, dimensions.second).map { floor(it).toInt() }.distinct()
    val longitudeTiles = phoneWorldWindGridLongitudes(bounds, dimensions.first).map { floor(it).toInt() }.distinct()
    val tiles = latitudeTiles.flatMap { latitude -> longitudeTiles.map { longitude -> latitude to longitude } }
    return tiles.takeIf { phoneWorldWindGridUsesLocalDem(bounds, dimensions.first, it.size, onlineAvailable) }.orEmpty()
}

/**
 * Local HGT is read only where it can add detail. A grid spanning more than four 1° files would decode or
 * probe one full file per degree, so it never reads local files. With online elevation available, a grid
 * whose cells are wider than [PHONE_WORLDWIND_LOCAL_DEM_MAX_CELL_DEGREES] also uses online elevation: a
 * capture showed 25 files, 16.8 s of unpacking, for a fly-over at that scale. Offline maps have no other
 * source, so they keep reading local files at any scale up to the four-file limit.
 */
internal fun phoneWorldWindGridUsesLocalDem(
    bounds: PhoneWorldWindBounds,
    width: Int,
    localTileCount: Int,
    onlineAvailable: Boolean,
): Boolean {
    val cellDegrees = (bounds.east - bounds.west) / width
    return localTileCount <= PHONE_WORLDWIND_MAX_LOCAL_DEM_TILES &&
        (!onlineAvailable || cellDegrees <= PHONE_WORLDWIND_LOCAL_DEM_MAX_CELL_DEGREES)
}

/**
 * Group points by HGT tile to reuse each decoded tile even when the small repository LRU churns.
 * Returns null when a source failed, because WorldWind keeps a returned grid but retries a missing one.
 */
internal suspend fun phoneWorldWindElevationGrid(
    bounds: PhoneWorldWindBounds,
    dimensions: Pair<Int, Int>,
    local: PhoneElevationRepository?,
    online: PhoneOnlineElevationRepository?,
    zoom: Int,
): ShortArray? {
    val (width, height) = dimensions
    val heights = ShortArray(width * height) { Short.MIN_VALUE }
    val latitudes = phoneWorldWindGridLatitudes(bounds, height)
    val longitudes = phoneWorldWindGridLongitudes(bounds, width)
    val groups = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val tile = floor(latitudes[y]).toInt() to floor(longitudes[x]).toInt()
            groups.getOrPut(tile) { mutableListOf() }.add(y * width + x)
        }
    }
    val tileLocal = local.takeIf { phoneWorldWindGridUsesLocalDem(bounds, width, groups.size, online != null) }
    val sampler = PhoneWorldWindGridSampler(latitudes, longitudes, heights, tileLocal, online, zoom)
    val complete = groups.all { (tileId, indices) -> sampler.fill(tileId, indices) }
    return heights.takeIf { complete }
}

private class PhoneWorldWindGridSampler(
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val heights: ShortArray,
    private val local: PhoneElevationRepository?,
    private val online: PhoneOnlineElevationRepository?,
    private val zoom: Int,
) {
    /** False when a source failed, as opposed to having no data for these samples. */
    suspend fun fill(
        tileId: Pair<Int, Int>,
        indices: List<Int>,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val tile = local?.loadTile(tileId.first, tileId.second)
        if (tile == null && local?.tileExceededMemory(tileId.first, tileId.second) == true) return false
        for (index in indices) {
            if (index % 64 == 0) currentCoroutineContext().ensureActive()
            val latitude = latitudes[index / longitudes.size]
            val longitude = longitudes[index % longitudes.size]
            val point = PhoneMapCoordinate(latitude, longitude)
            val elevation =
                tile?.let { local?.elevationAtUsingTile(it, tileId.first, tileId.second, latitude, longitude) }
                    ?: online?.elevationAt(point, zoom)
            if (elevation == null && online?.isTileUnavailable(point, zoom) == true) return false
            if (elevation != null &&
                elevation.isFinite() &&
                elevation > Short.MIN_VALUE &&
                elevation <= Short.MAX_VALUE
            ) {
                heights[index] = elevation.roundToInt().coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return true
    }
}
