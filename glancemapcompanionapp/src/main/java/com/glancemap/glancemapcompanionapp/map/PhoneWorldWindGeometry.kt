package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

// WorldWind measures tilt from straight down; 60° tilt is 30° above the horizon.
internal const val PHONE_WORLDWIND_DEFAULT_TILT_DEGREES = 55.0

internal fun phoneWorldWindExaggeration(value: Double): Double = value.coerceIn(1.0, 1.25)

internal const val PHONE_WORLDWIND_MAX_TILT_DEGREES = 60.0

internal fun phoneWorldWindClampedTilt(tilt: Double): Double = tilt.coerceIn(0.0, PHONE_WORLDWIND_MAX_TILT_DEGREES)

internal data class PhoneWorldWindTile(
    val z: Int,
    val x: Int,
    val y: Int,
)

internal data class PhoneWorldWindBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun intersects(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): Boolean = this.south <= north && this.north >= south && this.west <= east && this.east >= west
}

internal fun phoneWorldWindTile(
    latitude: Double,
    longitude: Double,
    zoom: Int,
): PhoneWorldWindTile {
    val z = zoom.coerceIn(0, 22)
    val count = 1 shl z
    val lon = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    val latRadians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
    val x = floor((lon + 180.0) / 360.0 * count).toInt().coerceIn(0, count - 1)
    val y = floor((1.0 - ln(tan(latRadians) + 1.0 / cos(latRadians)) / PI) * 0.5 * count).toInt().coerceIn(0, count - 1)
    return PhoneWorldWindTile(z, x, y)
}

internal fun phoneWorldWindTileBounds(tile: PhoneWorldWindTile): PhoneWorldWindBounds {
    val count = (1 shl tile.z).toDouble()

    fun latitude(y: Int) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / count))))
    return PhoneWorldWindBounds(latitude(tile.y + 1), tile.x / count * 360.0 - 180.0, latitude(tile.y), (tile.x + 1) / count * 360.0 - 180.0)
}

internal fun phoneWorldWindRangeMeters(
    latitude: Double,
    zoom: Double,
): Double =
    (40_075_016.686 * cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) / 2.0.pow(zoom.coerceIn(0.0, 22.0)) * 1.4)
        .coerceIn(100.0, 20_000_000.0)

internal fun phoneWorldWindZoomForRange(
    latitude: Double,
    range: Double,
): Double =
    log2(40_075_016.686 * cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) * 1.4 / range.coerceIn(100.0, 20_000_000.0))
        .coerceIn(0.0, 22.0)

internal fun phoneWorldWindTrackSegments(segments: List<PhoneMapRouteSegment>): List<List<GeoPoint>> = segments.filter { !it.isGap && it.points.size >= 2 }.map { it.points }

internal data class PhoneWorldWindTileKey(
    val mapIdentity: String,
    val themeIdentity: String,
    val scale: Float,
    val tile: PhoneWorldWindTile,
    val resolution: Int = 256,
)
