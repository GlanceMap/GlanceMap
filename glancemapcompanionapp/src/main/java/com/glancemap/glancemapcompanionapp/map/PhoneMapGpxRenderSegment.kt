package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.buildCumulativeDistancesMeters
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.geo.initialBearingDegrees
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class PhoneMapGpxRenderSegment(
    val points: List<GeoPoint>,
    val colorArgb: Int,
)

internal fun List<PhoneMapRouteSegment>.toPhoneMapGpxRenderSegments(
    settings: PhoneMapGpxSettings,
): List<PhoneMapGpxRenderSegment> {
    val trackSegments =
        if (settings.colorMode == PhoneMapGpxColorMode.ELEVATION) {
            flatMap(PhoneMapRouteSegment::toElevationRenderSegments)
        } else {
            map { segment -> PhoneMapGpxRenderSegment(segment.points, settings.trackColorArgb) }
        }
    if (!settings.directionArrowsEnabled) return trackSegments

    return trackSegments + flatMap { segment -> segment.toDirectionArrowSegments() }
}

private fun PhoneMapRouteSegment.toElevationRenderSegments(): List<PhoneMapGpxRenderSegment> {
    if (points.size < 2) return emptyList()

    val result = mutableListOf<PhoneMapGpxRenderSegment>()
    var currentColor: Int? = null
    var currentPoints = mutableListOf<GeoPoint>()

    fun flush() {
        val color = currentColor
        if (color != null && currentPoints.size >= 2) {
            result += PhoneMapGpxRenderSegment(currentPoints.toList(), color)
        }
        currentColor = null
        currentPoints = mutableListOf()
    }

    for (index in 1..points.lastIndex) {
        val from = points[index - 1]
        val to = points[index]
        val color = elevationSegmentColor(elevationAt(index - 1), elevationAt(index), from, to)
        if (color != currentColor) {
            flush()
            currentColor = color
            currentPoints = mutableListOf(from, to)
        } else {
            currentPoints += to
        }
    }
    flush()
    return result
}

private fun PhoneMapRouteSegment.toDirectionArrowSegments(): List<PhoneMapGpxRenderSegment> =
    if (points.size < 2) {
        emptyList()
    } else {
        val cumulative = buildCumulativeDistancesMeters(points)
        val totalDistance = cumulative.lastOrNull() ?: 0.0
        if (totalDistance < DIRECTION_ARROW_FIRST_DISTANCE_METERS) {
            emptyList()
        } else {
            buildDirectionArrowSegments(points, cumulative, totalDistance)
        }
    }

private fun buildDirectionArrowSegments(
    points: List<GeoPoint>,
    cumulative: List<Double>,
    totalDistance: Double,
): List<PhoneMapGpxRenderSegment> {
    val arrows = mutableListOf<PhoneMapGpxRenderSegment>()
    var distance = DIRECTION_ARROW_FIRST_DISTANCE_METERS
    while (distance < totalDistance && arrows.size < MAX_DIRECTION_ARROWS_PER_SEGMENT) {
        val center = pointAtDistance(points, cumulative, distance)
        val before =
            pointAtDistance(
                points,
                cumulative,
                (distance - DIRECTION_ARROW_HEADING_SAMPLE_METERS).coerceAtLeast(0.0),
            )
        val after =
            pointAtDistance(
                points,
                cumulative,
                (distance + DIRECTION_ARROW_HEADING_SAMPLE_METERS).coerceAtMost(totalDistance),
            )
        val heading = initialBearingDegrees(before, after)
        val leftBase =
            destination(
                center,
                heading + 180.0 - DIRECTION_ARROW_ARM_ANGLE_DEGREES,
                DIRECTION_ARROW_ARM_LENGTH_METERS,
            )
        val tip = destination(center, heading, DIRECTION_ARROW_TIP_LENGTH_METERS)
        val rightBase =
            destination(
                center,
                heading + 180.0 + DIRECTION_ARROW_ARM_ANGLE_DEGREES,
                DIRECTION_ARROW_ARM_LENGTH_METERS,
            )
        arrows += PhoneMapGpxRenderSegment(listOf(leftBase, tip, rightBase), DIRECTION_ARROW_COLOR_ARGB)
        distance += DIRECTION_ARROW_SPACING_METERS
    }
    return arrows
}

private fun elevationSegmentColor(
    fromElevation: Double?,
    toElevation: Double?,
    from: GeoPoint,
    to: GeoPoint,
): Int {
    val gradePercent =
        if (fromElevation == null || toElevation == null) {
            null
        } else {
            val distanceMeters = haversineDistanceMeters(from, to)
            if (distanceMeters > 0.0) ((toElevation - fromElevation) / distanceMeters) * 100.0 else null
        }
    return when {
        gradePercent == null -> rgbColor(217, 227, 234)
        gradePercent >= 8.0 -> rgbColor(255, 138, 60)
        gradePercent >= 2.0 -> rgbColor(255, 200, 87)
        gradePercent <= -8.0 -> rgbColor(59, 130, 246)
        gradePercent <= -2.0 -> rgbColor(115, 194, 251)
        else -> rgbColor(217, 227, 234)
    }
}

private fun PhoneMapRouteSegment.elevationAt(index: Int): Double? = elevationMeters.getOrNull(index)

private fun pointAtDistance(
    points: List<GeoPoint>,
    cumulative: List<Double>,
    distance: Double,
): GeoPoint {
    val index =
        cumulative
            .indexOfFirst { value -> value >= distance }
            .takeIf { it > 0 }
            ?: cumulative.lastIndex
    if (index <= 0) return points.first()
    val startDistance = cumulative[index - 1]
    val segmentDistance = (cumulative[index] - startDistance).coerceAtLeast(0.001)
    val fraction = ((distance - startDistance) / segmentDistance).coerceIn(0.0, 1.0)
    val start = points[index - 1]
    val end = points[index]
    return GeoPoint(
        latitude = start.latitude + (end.latitude - start.latitude) * fraction,
        longitude = start.longitude + (end.longitude - start.longitude) * fraction,
    )
}

private fun destination(
    origin: GeoPoint,
    bearingDegrees: Double,
    distanceMeters: Double,
): GeoPoint {
    val radiusMeters = 6_371_000.0
    val angularDistance = distanceMeters / radiusMeters
    val bearing = Math.toRadians(bearingDegrees)
    val latitude = Math.toRadians(origin.latitude)
    val longitude = Math.toRadians(origin.longitude)
    val destinationLatitude =
        asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
    val destinationLongitude =
        longitude +
            atan2(
                sin(bearing) * sin(angularDistance) * cos(latitude),
                cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
            )
    return GeoPoint(
        latitude = Math.toDegrees(destinationLatitude),
        longitude = ((Math.toDegrees(destinationLongitude) + 540.0) % 360.0) - 180.0,
    )
}

private fun rgbColor(
    red: Int,
    green: Int,
    blue: Int,
): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

private const val DIRECTION_ARROW_SPACING_METERS = 180.0
private const val DIRECTION_ARROW_FIRST_DISTANCE_METERS = 90.0
private const val DIRECTION_ARROW_HEADING_SAMPLE_METERS = 12.0
private const val DIRECTION_ARROW_ARM_LENGTH_METERS = 18.0
private const val DIRECTION_ARROW_TIP_LENGTH_METERS = 22.0
private const val DIRECTION_ARROW_ARM_ANGLE_DEGREES = 35.0
private const val MAX_DIRECTION_ARROWS_PER_SEGMENT = 36
private const val DIRECTION_ARROW_COLOR_ARGB = 0xFF000000.toInt()
