package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

internal const val PHONE_ONLINE_DEM_TILE_URL =
    "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
internal const val PHONE_ONLINE_DEM_ATTRIBUTION_URL =
    "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"
internal const val PHONE_ONLINE_DEM_TILE_SIZE = 256
private const val ONLINE_DEM_READING_ZOOM = 14
private const val ONLINE_DEM_MERCATOR_LIMIT = 85.05112878
private const val ONLINE_DEM_CACHE_ENTRIES = 32
private const val ONLINE_DEM_PARALLEL_LOADS = 4
private const val ONLINE_DEM_LOAD_STRIPES = 16
internal const val PHONE_ONLINE_DEM_FAILURE_RETRY_MS = 30_000L
internal const val PHONE_MAP_LIVE_ELEVATION_RESAMPLE_DISTANCE_METERS = 3.0

internal data class PhoneOnlineDemTileId(
    val zoom: Int,
    val x: Int,
    val y: Int,
) {
    fun url(): String =
        PHONE_ONLINE_DEM_TILE_URL
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
}

internal data class PhoneOnlineDemPoint(
    val tile: PhoneOnlineDemTileId,
    val pixelX: Double,
    val pixelY: Double,
)

internal fun phoneOnlineDemPoint(
    point: PhoneMapCoordinate,
    zoom: Int = ONLINE_DEM_READING_ZOOM,
): PhoneOnlineDemPoint? {
    if (!point.latitude.isFinite() ||
        !point.longitude.isFinite() ||
        point.latitude !in -ONLINE_DEM_MERCATOR_LIMIT..ONLINE_DEM_MERCATOR_LIMIT
    ) {
        return null
    }
    val safeZoom = zoom.coerceIn(0, ONLINE_DEM_READING_ZOOM)
    val tileCount = 1 shl safeZoom
    val longitude = ((point.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    val worldX = (longitude + 180.0) / 360.0 * tileCount
    val latitudeRadians = point.latitude * PI / 180.0
    val worldY = (1.0 - ln(tan(PI / 4.0 + latitudeRadians / 2.0)) / PI) / 2.0 * tileCount
    val tileX = floor(worldX).toInt().coerceIn(0, tileCount - 1)
    val tileY = floor(worldY).toInt().coerceIn(0, tileCount - 1)
    return PhoneOnlineDemPoint(
        tile = PhoneOnlineDemTileId(safeZoom, tileX, tileY),
        pixelX = (worldX - tileX) * PHONE_ONLINE_DEM_TILE_SIZE - 0.5,
        pixelY = (worldY - tileY) * PHONE_ONLINE_DEM_TILE_SIZE - 0.5,
    )
}

internal fun phoneTerrariumElevation(argb: Int): Double? {
    if ((argb ushr 24) == 0 || (argb and 0x00ffffff) == 0) return null
    val red = (argb ushr 16) and 0xff
    val green = (argb ushr 8) and 0xff
    val blue = argb and 0xff
    return red * 256.0 + green + blue / 256.0 - 32768.0
}

internal class PhoneTerrariumTile(
    private val pixels: IntArray,
    private val size: Int = PHONE_ONLINE_DEM_TILE_SIZE,
) {
    init {
        require(size > 0 && pixels.size == size * size)
    }

    fun elevationAt(
        x: Double,
        y: Double,
    ): Double? {
        val safeX = x.coerceIn(0.0, (size - 1).toDouble())
        val safeY = y.coerceIn(0.0, (size - 1).toDouble())
        val x0 = floor(safeX).toInt()
        val y0 = floor(safeY).toInt()
        val x1 = minOf(x0 + 1, size - 1)
        val y1 = minOf(y0 + 1, size - 1)
        val dx = safeX - x0
        val dy = safeY - y0
        val samples =
            listOf(
                pixels[y0 * size + x0] to (1.0 - dx) * (1.0 - dy),
                pixels[y0 * size + x1] to dx * (1.0 - dy),
                pixels[y1 * size + x0] to (1.0 - dx) * dy,
                pixels[y1 * size + x1] to dx * dy,
            )
        var height = 0.0
        var weight = 0.0
        samples.forEach { (pixel, sampleWeight) ->
            phoneTerrariumElevation(pixel)?.let { elevation ->
                height += elevation * sampleWeight
                weight += sampleWeight
            }
        }
        return if (weight > 0.0) height / weight else null
    }
}

/**
 * Loads of different tiles run in parallel, up to [ONLINE_DEM_PARALLEL_LOADS]; callers wanting the same tile
 * share one load, so A/B and live reads still coalesce. Success and failure caching stay bounded. Each load
 * stays cancellable: a cancelled load caches nothing and the next caller loads again.
 */
internal class PhoneOnlineElevationRepository(
    private val fetchTile: suspend (PhoneOnlineDemTileId) -> PhoneTerrariumTile?,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    constructor(context: Context) : this({ tile -> PhoneOnlineDemHttp.loadTile(context.applicationContext, tile) })

    private data class Entry(
        val tile: PhoneTerrariumTile?,
        val loadedAtMillis: Long,
    )

    private val cacheLock = Any()

    // A tile always maps to the same lock, so its waiters share one load while other tiles load alongside.
    private val tileLocks = Array(ONLINE_DEM_LOAD_STRIPES) { Mutex() }
    private val loads = Semaphore(ONLINE_DEM_PARALLEL_LOADS)
    private val tiles =
        object : LinkedHashMap<PhoneOnlineDemTileId, Entry>(ONLINE_DEM_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<PhoneOnlineDemTileId, Entry>?,
            ): Boolean = size > ONLINE_DEM_CACHE_ENTRIES
        }

    suspend fun elevationAt(
        point: PhoneMapCoordinate,
        zoom: Int = ONLINE_DEM_READING_ZOOM,
    ): Double? {
        val sample = phoneOnlineDemPoint(point, zoom) ?: return null
        return tile(sample.tile)?.elevationAt(sample.pixelX, sample.pixelY)
    }

    /** Loads every tile in [ids] at once, so a later sampling pass finds them in memory. */
    suspend fun preload(ids: Collection<PhoneOnlineDemTileId>) =
        coroutineScope {
            ids.distinct().forEach { id -> launch { tile(id) } }
        }

    private suspend fun tile(id: PhoneOnlineDemTileId): PhoneTerrariumTile? {
        cached(id)?.let { return it.tile }
        return tileLocks[Math.floorMod(id.hashCode(), ONLINE_DEM_LOAD_STRIPES)].withLock {
            cached(id)?.tile ?: loads
                .withPermit { fetchTile(id) }
                .also { loaded -> synchronized(cacheLock) { tiles[id] = Entry(loaded, clockMillis()) } }
        }
    }

    /** A loaded tile is kept until evicted; a failed load is retried after [PHONE_ONLINE_DEM_FAILURE_RETRY_MS]. */
    private fun cached(id: PhoneOnlineDemTileId): Entry? =
        synchronized(cacheLock) {
            tiles[id]?.takeIf { entry ->
                entry.tile != null || clockMillis() - entry.loadedAtMillis in 0 until PHONE_ONLINE_DEM_FAILURE_RETRY_MS
            }
        }

    /** True while the tile covering [point] failed to load, as opposed to loading with no data there. */
    fun isTileUnavailable(
        point: PhoneMapCoordinate,
        zoom: Int = ONLINE_DEM_READING_ZOOM,
    ): Boolean {
        val sample = phoneOnlineDemPoint(point, zoom) ?: return false
        return synchronized(cacheLock) { tiles[sample.tile]?.let { it.tile == null } == true }
    }
}

internal suspend fun phoneMapElevationAt(
    point: PhoneMapCoordinate,
    local: PhoneElevationRepository,
    online: PhoneOnlineElevationRepository?,
): Double? =
    withContext(Dispatchers.IO) { local.elevationAt(point.latitude, point.longitude) }
        ?: online?.elevationAt(point)

internal fun phoneMapElevationTargetIsCurrent(
    sampled: PhoneMapCoordinate,
    current: PhoneMapCoordinate?,
): Boolean =
    current != null &&
        phoneMapDistanceMeters(sampled, current) < PHONE_MAP_LIVE_ELEVATION_RESAMPLE_DISTANCE_METERS

internal fun phoneMapLiveElevation(
    demElevation: Double?,
    target: PhoneMapCoordinate,
    location: PhoneMapLocation?,
): Double? =
    demElevation ?: location
        ?.takeIf {
            phoneMapDistanceMeters(PhoneMapCoordinate(it.latitude, it.longitude), target) <= 100.0
        }?.altitudeMeters
