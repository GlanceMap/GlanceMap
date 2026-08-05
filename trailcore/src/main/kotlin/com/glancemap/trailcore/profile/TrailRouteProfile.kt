package com.glancemap.trailcore.profile

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters

data class TrailPoint(
    val location: GeoPoint,
    val elevationMeters: Double? = null,
    val startsNewSegment: Boolean = false,
) {
    init {
        require(elevationMeters == null || elevationMeters.isFinite()) {
            "Elevation must be finite when it is available."
        }
    }
}

/**
 * A deliberately transparent planning estimate: horizontal travel time plus uphill vertical time.
 * Apps can replace the defaults with a user's calibrated hiking profile later.
 */
data class TrailPacingConfig(
    val flatSpeedMetersPerSecond: Double = 1.2,
    val uphillVerticalMetersPerHour: Double = 500.0,
) {
    init {
        require(flatSpeedMetersPerSecond.isFinite() && flatSpeedMetersPerSecond > 0.0) {
            "Flat speed must be a positive, finite value."
        }
        require(uphillVerticalMetersPerHour.isFinite() && uphillVerticalMetersPerHour >= 0.0) {
            "Uphill rate must be a non-negative, finite value."
        }
    }
}

data class TrailRouteProfile(
    val points: List<TrailPoint>,
    val cumulativeDistanceMeters: List<Double>,
    val cumulativeAscentMeters: List<Double>,
    val cumulativeDescentMeters: List<Double>,
    val cumulativeEstimatedDurationSeconds: List<Double>,
) {
    init {
        val expectedSize = points.size
        require(cumulativeDistanceMeters.size == expectedSize)
        require(cumulativeAscentMeters.size == expectedSize)
        require(cumulativeDescentMeters.size == expectedSize)
        require(cumulativeEstimatedDurationSeconds.size == expectedSize)
    }

    val totalDistanceMeters: Double
        get() = cumulativeDistanceMeters.lastOrNull() ?: 0.0

    val totalAscentMeters: Double
        get() = cumulativeAscentMeters.lastOrNull() ?: 0.0

    val totalDescentMeters: Double
        get() = cumulativeDescentMeters.lastOrNull() ?: 0.0

    val estimatedDurationSeconds: Double
        get() = cumulativeEstimatedDurationSeconds.lastOrNull() ?: 0.0
}

data class TrailWindow(
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val estimatedDurationSeconds: Double,
)

fun buildTrailRouteProfile(
    points: List<TrailPoint>,
    pacing: TrailPacingConfig = TrailPacingConfig(),
): TrailRouteProfile {
    val size = points.size
    val cumulativeDistance = MutableList(size) { 0.0 }
    val cumulativeAscent = MutableList(size) { 0.0 }
    val cumulativeDescent = MutableList(size) { 0.0 }
    val cumulativeDuration = MutableList(size) { 0.0 }

    for (index in 0 until points.lastIndex) {
        val from = points[index]
        val to = points[index + 1]
        val startsNewSegment = to.startsNewSegment
        val distanceMeters =
            if (startsNewSegment) {
                0.0
            } else {
                haversineDistanceMeters(from.location, to.location)
            }
        val elevationDelta =
            if (startsNewSegment) {
                null
            } else {
                from.elevationMeters?.let { fromElevation ->
                    to.elevationMeters?.minus(fromElevation)
                }
            }
        val ascentMeters = elevationDelta?.takeIf { it > 0.0 } ?: 0.0
        val descentMeters = elevationDelta?.takeIf { it < 0.0 }?.unaryMinus() ?: 0.0

        cumulativeDistance[index + 1] = cumulativeDistance[index] + distanceMeters
        cumulativeAscent[index + 1] = cumulativeAscent[index] + ascentMeters
        cumulativeDescent[index + 1] = cumulativeDescent[index] + descentMeters
        cumulativeDuration[index + 1] =
            cumulativeDuration[index] +
            estimateSegmentDurationSeconds(
                distanceMeters = distanceMeters,
                ascentMeters = ascentMeters,
                pacing = pacing,
            )
    }

    return TrailRouteProfile(
        points = points,
        cumulativeDistanceMeters = cumulativeDistance,
        cumulativeAscentMeters = cumulativeAscent,
        cumulativeDescentMeters = cumulativeDescent,
        cumulativeEstimatedDurationSeconds = cumulativeDuration,
    )
}

fun TrailRouteProfile.windowFromDistance(
    startDistanceMeters: Double,
    maximumDurationSeconds: Double,
): TrailWindow {
    require(maximumDurationSeconds.isFinite() && maximumDurationSeconds >= 0.0) {
        "Window duration must be a non-negative, finite value."
    }
    val startDistance = startDistanceMeters.coerceIn(0.0, totalDistanceMeters)
    val startDuration = interpolateAtDistance(cumulativeEstimatedDurationSeconds, startDistance)
    val endDuration = (startDuration + maximumDurationSeconds).coerceAtMost(estimatedDurationSeconds)
    val endDistance = interpolateDistanceAtDuration(endDuration)
    val ascentAtStart = interpolateAtDistance(cumulativeAscentMeters, startDistance)
    val ascentAtEnd = interpolateAtDistance(cumulativeAscentMeters, endDistance)
    val descentAtStart = interpolateAtDistance(cumulativeDescentMeters, startDistance)
    val descentAtEnd = interpolateAtDistance(cumulativeDescentMeters, endDistance)
    val ascentMeters =
        (ascentAtEnd - ascentAtStart).coerceAtLeast(0.0)
    val descentMeters =
        (descentAtEnd - descentAtStart).coerceAtLeast(0.0)

    return TrailWindow(
        startDistanceMeters = startDistance,
        endDistanceMeters = endDistance,
        distanceMeters = (endDistance - startDistance).coerceAtLeast(0.0),
        ascentMeters = ascentMeters,
        descentMeters = descentMeters,
        estimatedDurationSeconds = (endDuration - startDuration).coerceAtLeast(0.0),
    )
}

/** Calculates an exact planning summary for a bounded portion of a route. */
fun TrailRouteProfile.windowBetweenDistances(
    startDistanceMeters: Double,
    endDistanceMeters: Double,
): TrailWindow {
    val startDistance = startDistanceMeters.coerceIn(0.0, totalDistanceMeters)
    val endDistance = endDistanceMeters.coerceIn(startDistance, totalDistanceMeters)
    val startDuration = interpolateAtDistance(cumulativeEstimatedDurationSeconds, startDistance)
    val endDuration = interpolateAtDistance(cumulativeEstimatedDurationSeconds, endDistance)
    val ascentAtStart = interpolateAtDistance(cumulativeAscentMeters, startDistance)
    val ascentAtEnd = interpolateAtDistance(cumulativeAscentMeters, endDistance)
    val descentAtStart = interpolateAtDistance(cumulativeDescentMeters, startDistance)
    val descentAtEnd = interpolateAtDistance(cumulativeDescentMeters, endDistance)
    return TrailWindow(
        startDistanceMeters = startDistance,
        endDistanceMeters = endDistance,
        distanceMeters = (endDistance - startDistance).coerceAtLeast(0.0),
        ascentMeters = (ascentAtEnd - ascentAtStart).coerceAtLeast(0.0),
        descentMeters = (descentAtEnd - descentAtStart).coerceAtLeast(0.0),
        estimatedDurationSeconds = (endDuration - startDuration).coerceAtLeast(0.0),
    )
}

private fun estimateSegmentDurationSeconds(
    distanceMeters: Double,
    ascentMeters: Double,
    pacing: TrailPacingConfig,
): Double {
    val horizontalSeconds = distanceMeters.coerceAtLeast(0.0) / pacing.flatSpeedMetersPerSecond
    val uphillSeconds =
        if (pacing.uphillVerticalMetersPerHour <= 0.0) {
            0.0
        } else {
            ascentMeters.coerceAtLeast(0.0) / pacing.uphillVerticalMetersPerHour * 3600.0
        }
    return horizontalSeconds + uphillSeconds
}

private fun TrailRouteProfile.interpolateAtDistance(
    values: List<Double>,
    targetDistanceMeters: Double,
): Double =
    if (points.size < 2) {
        values.lastOrNull() ?: 0.0
    } else {
        val target = targetDistanceMeters.coerceIn(0.0, totalDistanceMeters)
        val index = segmentIndexForValue(cumulativeDistanceMeters, target)
        val startDistance = cumulativeDistanceMeters[index]
        val endDistance = cumulativeDistanceMeters[index + 1]
        val fraction = interpolationFraction(target, startDistance, endDistance)
        values[index] + fraction * (values[index + 1] - values[index])
    }

private fun TrailRouteProfile.interpolateDistanceAtDuration(targetDurationSeconds: Double): Double =
    if (points.size < 2) {
        totalDistanceMeters
    } else {
        val target = targetDurationSeconds.coerceIn(0.0, estimatedDurationSeconds)
        val index = segmentIndexForValue(cumulativeEstimatedDurationSeconds, target)
        val startDuration = cumulativeEstimatedDurationSeconds[index]
        val endDuration = cumulativeEstimatedDurationSeconds[index + 1]
        val fraction = interpolationFraction(target, startDuration, endDuration)
        cumulativeDistanceMeters[index] +
            fraction * (cumulativeDistanceMeters[index + 1] - cumulativeDistanceMeters[index])
    }

private fun segmentIndexForValue(
    cumulativeValues: List<Double>,
    target: Double,
): Int =
    (0 until cumulativeValues.lastIndex)
        .firstOrNull { target <= cumulativeValues[it + 1] }
        ?: (cumulativeValues.lastIndex - 1).coerceAtLeast(0)

private fun interpolationFraction(
    target: Double,
    start: Double,
    end: Double,
): Double =
    if (end <= start) {
        0.0
    } else {
        ((target - start) / (end - start)).coerceIn(0.0, 1.0)
    }
