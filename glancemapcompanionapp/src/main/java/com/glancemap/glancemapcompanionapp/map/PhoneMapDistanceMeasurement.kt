package com.glancemap.glancemapcompanionapp.map

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PhoneMapCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal data class PhoneMapDistanceMeasurement(
    val first: PhoneMapCoordinate,
    val second: PhoneMapCoordinate,
) {
    val distanceMeters: Double
        get() = phoneMapDistanceMeters(first, second)
}

internal data class PhoneMapScreenPoint(
    val x: Float,
    val y: Float,
)

/** Finds the nearest visible measurement endpoint within the touch target. */
internal fun phoneMapMeasurementHandleIndex(
    first: PhoneMapScreenPoint?,
    second: PhoneMapScreenPoint?,
    x: Float,
    y: Float,
    maxDistancePx: Float,
): Int? =
    listOfNotNull(
        first?.let { point -> 0 to point },
        second?.let { point -> 1 to point },
    ).minByOrNull { (_, point) -> hypot(x - point.x, y - point.y) }
        ?.takeIf { (_, point) -> hypot(x - point.x, y - point.y) <= maxDistancePx }
        ?.first

internal fun PhoneMapDistanceMeasurement.moveEndpoint(
    index: Int,
    point: PhoneMapCoordinate,
): PhoneMapDistanceMeasurement =
    when (index) {
        0 -> copy(first = point)
        1 -> copy(second = point)
        else -> this
    }

internal data class PhoneMapLiveMetricsPosition(
    val target: PhoneMapCoordinate,
    val userScreenPoint: PhoneMapScreenPoint?,
    /** Geographic origin represented by the rendered location marker. */
    val origin: PhoneMapCoordinate? = null,
)

/** Keeps live-distance calculations anchored to the location marker, like the watch renderer. */
internal fun resolvePhoneMapLiveMetricsOrigin(
    markerPosition: PhoneMapCoordinate?,
    locationFallback: PhoneMapCoordinate?,
): PhoneMapCoordinate? = markerPosition ?: locationFallback

internal fun phoneMapLiveDistanceMeters(
    position: PhoneMapLiveMetricsPosition,
    fallbackOrigin: PhoneMapCoordinate?,
): Double? =
    resolvePhoneMapLiveMetricsOrigin(
        markerPosition = position.origin,
        locationFallback = fallbackOrigin,
    )?.let { origin ->
        phoneMapDistanceMeters(origin, position.target)
    }

internal fun phoneMapDistanceMeters(
    first: PhoneMapCoordinate,
    second: PhoneMapCoordinate,
): Double {
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val a =
        sin(latitudeDelta / 2.0).pow(2) +
            cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2.0).pow(2)
    return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

internal fun formatPhoneMapMeasuredDistance(
    meters: Double,
    isMetric: Boolean,
): String {
    if (!meters.isFinite() || meters < 0.0) return "—"
    return if (isMetric) {
        if (meters >= 1_000.0) {
            String.format(Locale.getDefault(), "%.1f km", meters / 1_000.0)
        } else {
            "${meters.roundToInt()} m"
        }
    } else {
        val feet = meters * METERS_TO_FEET
        if (feet >= FEET_PER_MILE) {
            String.format(Locale.getDefault(), "%.1f mi", feet / FEET_PER_MILE)
        } else {
            "${feet.roundToInt()} ft"
        }
    }
}

internal fun formatPhoneMapDistanceMeters(meters: Double): String {
    if (!meters.isFinite() || meters < 0.0) return "—"
    return String.format(Locale.getDefault(), "%.1f m", meters)
}

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val METERS_TO_FEET = 3.28084
private const val FEET_PER_MILE = 5_280.0
