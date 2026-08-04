package com.glancemap.trailcore.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A platform-neutral geographic coordinate used by shared trail calculations. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be a finite value between -90 and 90 degrees."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be a finite value between -180 and 180 degrees."
        }
    }
}

data class RouteProjection(
    val segmentIndex: Int,
    val segmentFraction: Double,
    val distanceFromStartMeters: Double,
    val distanceToRouteMeters: Double,
)

data class RouteProjectionConfig(
    val maximumBacktrackMeters: Double = 45.0,
    val maximumForwardJumpMeters: Double = 300.0,
    val progressPenalty: Double = 0.015,
    val relockAdvantageMeters: Double = 35.0,
)

fun haversineDistanceMeters(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val earthRadiusMeters = 6_371_000.0
    val deltaLatitude = Math.toRadians(to.latitude - from.latitude)
    val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val haversine =
        sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(fromLatitude) * cos(toLatitude) *
            sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
    return 2 * earthRadiusMeters * asin(min(1.0, sqrt(haversine)))
}

fun initialBearingDegrees(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
    val y = sin(deltaLongitude) * cos(toLatitude)
    val x =
        cos(fromLatitude) * sin(toLatitude) -
            sin(fromLatitude) * cos(toLatitude) * cos(deltaLongitude)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

fun buildCumulativeDistancesMeters(points: List<GeoPoint>): List<Double> {
    if (points.isEmpty()) return emptyList()

    val cumulative = MutableList(points.size) { 0.0 }
    var totalMeters = 0.0
    for (index in 0 until points.lastIndex) {
        totalMeters += haversineDistanceMeters(points[index], points[index + 1])
        cumulative[index + 1] = totalMeters
    }
    return cumulative
}

fun projectPointOntoRoute(
    points: List<GeoPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeDistancesMeters(points),
    point: GeoPoint,
    previousDistanceFromStartMeters: Double? = null,
    config: RouteProjectionConfig = RouteProjectionConfig(),
): RouteProjection? =
    if (points.size < 2 || cumulativeDistancesMeters.size != points.size) {
        null
    } else {
        val candidates = ArrayList<RouteProjection>(points.lastIndex)
        for (index in 0 until points.lastIndex) {
            val projection =
                projectPointOntoSegment(
                    start = points[index],
                    end = points[index + 1],
                    point = point,
                )
            val segmentLength =
                cumulativeDistancesMeters[index + 1] - cumulativeDistancesMeters[index]
            candidates +=
                RouteProjection(
                    segmentIndex = index,
                    segmentFraction = projection.segmentFraction,
                    distanceFromStartMeters =
                        cumulativeDistancesMeters[index] + segmentLength * projection.segmentFraction,
                    distanceToRouteMeters = projection.distanceMeters,
                )
        }

        val nearest = checkNotNull(candidates.minByOrNull(RouteProjection::distanceToRouteMeters))
        val previousDistance =
            previousDistanceFromStartMeters?.takeIf { it.isFinite() && it >= 0.0 }
        if (previousDistance == null) {
            nearest
        } else {
            val continuityCandidate =
                candidates
                    .asSequence()
                    .filter {
                        it.distanceFromStartMeters >= previousDistance - config.maximumBacktrackMeters &&
                            it.distanceFromStartMeters <= previousDistance + config.maximumForwardJumpMeters
                    }.minByOrNull { candidate ->
                        candidate.distanceToRouteMeters +
                            abs(candidate.distanceFromStartMeters - previousDistance) * config.progressPenalty
                    }
            if (
                continuityCandidate != null &&
                continuityCandidate.distanceToRouteMeters <=
                nearest.distanceToRouteMeters + config.relockAdvantageMeters
            ) {
                continuityCandidate
            } else {
                nearest
            }
        }
    }

private data class SegmentProjection(
    val segmentFraction: Double,
    val distanceMeters: Double,
)

private fun projectPointOntoSegment(
    start: GeoPoint,
    end: GeoPoint,
    point: GeoPoint,
): SegmentProjection {
    val latitudeRadians = Math.toRadians(start.latitude)
    val metersPerLatitude = 111_320.0
    val metersPerLongitude = max(1.0, metersPerLatitude * cos(latitudeRadians))

    val segmentX = (end.longitude - start.longitude) * metersPerLongitude
    val segmentY = (end.latitude - start.latitude) * metersPerLatitude
    val pointX = (point.longitude - start.longitude) * metersPerLongitude
    val pointY = (point.latitude - start.latitude) * metersPerLatitude
    val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY
    val segmentFraction =
        if (segmentLengthSquared <= 0.0) {
            0.0
        } else {
            ((pointX * segmentX + pointY * segmentY) / segmentLengthSquared).coerceIn(0.0, 1.0)
        }
    val projectedX = segmentX * segmentFraction
    val projectedY = segmentY * segmentFraction
    val deltaX = pointX - projectedX
    val deltaY = pointY - projectedY
    return SegmentProjection(
        segmentFraction = segmentFraction,
        distanceMeters = sqrt(deltaX * deltaX + deltaY * deltaY),
    )
}
