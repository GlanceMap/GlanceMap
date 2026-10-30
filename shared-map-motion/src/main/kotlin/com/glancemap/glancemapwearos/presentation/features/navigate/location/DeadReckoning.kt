package com.glancemap.glancemapwearos.presentation.features.navigate

import org.mapsforge.core.model.LatLong
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

fun moveLatLong(
    start: LatLong,
    bearing: Float,
    distanceMeters: Float,
): LatLong {
    val r = 6371e3 // meters
    val lat1 = Math.toRadians(start.latitude)
    val lon1 = Math.toRadians(start.longitude)
    val brng = Math.toRadians(bearing.toDouble())

    val lat2 =
        asin(
            sin(lat1) * cos(distanceMeters / r) +
                cos(lat1) * sin(distanceMeters / r) * cos(brng),
        )

    val lon2 =
        lon1 +
            atan2(
                sin(brng) * sin(distanceMeters / r) * cos(lat1),
                cos(distanceMeters / r) - sin(lat1) * sin(lat2),
            )

    return LatLong(Math.toDegrees(lat2), Math.toDegrees(lon2))
}
