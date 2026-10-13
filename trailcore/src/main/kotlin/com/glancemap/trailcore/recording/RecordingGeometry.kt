package com.glancemap.trailcore.recording

import com.glancemap.trailcore.geo.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

const val RECORDING_EARTH_RADIUS_METERS = 6_371_000.0

/** Preserves the watch recorder's historical floating-point operation order. */
fun recordingHaversineMeters(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val deltaLatitude = Math.toRadians(to.latitude - from.latitude)
    val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val haversine =
        sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(fromLatitude) * cos(toLatitude) *
            sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
    return 2.0 * RECORDING_EARTH_RADIUS_METERS * atan2(sqrt(haversine), sqrt(1.0 - haversine))
}

data class RecordingLocalMeters(
    val x: Double,
    val y: Double,
) {
    fun length(): Double = hypot(x, y)

    fun toGeoPoint(origin: GeoPoint): GeoPoint {
        val latitude = origin.latitude + Math.toDegrees(y / RECORDING_EARTH_RADIUS_METERS)
        val longitude =
            origin.longitude +
                Math.toDegrees(
                    x /
                        (
                            RECORDING_EARTH_RADIUS_METERS *
                                cos(Math.toRadians(origin.latitude)).coerceAtLeast(0.01)
                        ),
                )
        return GeoPoint(latitude, longitude)
    }
}

fun GeoPoint.toRecordingLocalMeters(origin: GeoPoint): RecordingLocalMeters {
    val longitudeScale = cos(Math.toRadians((latitude + origin.latitude) / 2.0)).coerceAtLeast(0.01)
    return RecordingLocalMeters(
        x = Math.toRadians(longitude - origin.longitude) * RECORDING_EARTH_RADIUS_METERS * longitudeScale,
        y = Math.toRadians(latitude - origin.latitude) * RECORDING_EARTH_RADIUS_METERS,
    )
}
