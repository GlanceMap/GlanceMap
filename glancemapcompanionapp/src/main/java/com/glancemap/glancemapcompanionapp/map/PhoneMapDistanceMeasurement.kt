package com.glancemap.glancemapcompanionapp.map

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
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

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val METERS_TO_FEET = 3.28084
private const val FEET_PER_MILE = 5_280.0
